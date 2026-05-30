# 190 — Add Claude Opus 4.8 + /speed + /effort commands

## Goal

1. Register `claude-opus-4-8` as a supported model in the psi model catalog.
2. Add a `/speed` command (analogous to `/thinking`) that controls a per-session
   speed mode, propagated to providers that support it.
3. Add an `/effort` command that directly controls the provider reasoning-effort
   string, orthogonal to thinking on/off.
4. Make `:xhigh` thinking level genuinely distinct from `:high` on all providers
   that support it.

---

## Part 1 — Claude Opus 4.8 model

### Context

The model catalog lives in `components/ai/src/psi/ai/models.clj`.  New
Anthropic models follow the established pattern:

- Add a keyed entry to `anthropic-models` with provider, API, context window,
  pricing, and capability flags.
- Add the key to `anthropic-json-schema-native-model-keys` if the model
  supports native JSON Schema structured output (all Anthropic models from
  4.5 onward do).
- Add `:adaptive-thinking true` if the model uses the adaptive thinking API
  (introduced with Opus 4.7).

Opus 4.8 is the next Opus model after 4.7.  It uses the same adaptive-thinking
API protocol and the same Anthropic Messages API transport.

### Anthropic Models API

Anthropic exposes a models endpoint documented at
https://docs.anthropic.com/en/api/models:

- `GET /v1/models` — lists all available models for the authenticated key.
- `GET /v1/models/{model_id}` — retrieves a single model by ID.

Both endpoints require the standard `x-api-key` and `anthropic-version` headers.
The `GET /v1/models/claude-opus-4-8` response is the authoritative source for
the model's `id`, `display_name`, and `created_at` fields.  Pricing and
capability flags (context window, max tokens, adaptive-thinking) are not
returned by the API and must be sourced from Anthropic's published documentation.

### Scope

Changes in `components/ai/src/psi/ai/models.clj`:

1. Add `:opus-4.8` entry to `anthropic-models`.
2. Add `:opus-4.8` to `anthropic-json-schema-native-model-keys`.

No provider-layer changes are needed: the `adaptive-thinking?` predicate in
`providers/anthropic.clj` already dispatches on the `:adaptive-thinking` flag,
so the new model inherits correct request shaping automatically.

New gated test file `components/ai/test/psi/ai/providers/anthropic_models_api_test.clj`:

3. `^:integration` test gated on `PSI_LIVE_ANTHROPIC_MODELS_API=1` and
   `ANTHROPIC_API_KEY` that calls `GET /v1/models` and asserts
   `"claude-opus-4-8"` appears in the response.
4. `^:integration` test that calls `GET /v1/models/claude-opus-4-8` and
   asserts the response `id` field equals `"claude-opus-4-8"`.

Both tests skip gracefully (pass with a skip message) when the env-var gate or
API key is absent, following the same pattern as
`anthropic_live_structured_output_test.clj`.

### Model attributes

| Attribute | Value |
|---|---|
| `:id` | `"claude-opus-4-8"` |
| `:name` | `"Claude Opus 4.8"` |
| `:provider` | `:anthropic` |
| `:api` | `:anthropic-messages` |
| `:base-url` | `"https://api.anthropic.com"` |
| `:supports-reasoning` | `true` |
| `:adaptive-thinking` | `true` |
| `:supports-images` | `true` |
| `:supports-text` | `true` |
| `:context-window` | `1000000` |
| `:max-tokens` | `128000` |
| `:input-cost` | `5.0` ($/M tokens — placeholder, update when pricing is published) |
| `:output-cost` | `25.0` |
| `:cache-read-cost` | `0.5` |
| `:cache-write-cost` | `6.25` |

Pricing mirrors Opus 4.7 as a placeholder; update once Anthropic publishes
official pricing for 4.8.

---

## Part 2 — `/speed` command

### Background

Anthropic offers a "fast mode" (research preview) via `speed: "fast"` in the
request body.  Setting it yields up to 2.5× higher output tokens per second
from the same model at premium pricing.  OpenAI exposes equivalent throughput
control via `service_tier: "flex"` (lower priority / cheaper) or
`service_tier: "auto"` (default).  The canonical psi abstraction is a
per-session **speed mode** that maps onto each provider's native parameter.

### Speed mode values

| Value | Meaning | Anthropic | OpenAI |
|---|---|---|---|
| `:normal` | Default provider behaviour | omit `speed` | omit / `"default"` |
| `:fast` | Higher throughput, premium pricing | `speed: "fast"` | `service_tier: "flex"` |

`:normal` is the default (session starts with no speed override).

### Architecture — full stack

The speed mode follows the exact same path as `thinking-level`:

```
/speed command
  → session/set-speed-mode dispatch handler
    → :speed-mode stored in agent-session-schema
      → session->request-options includes :speed-mode in ai-options
        → provider build-request maps :speed-mode → native param
```

#### 1. Session state (`components/session-state/`)

- Add `:speed-mode` to `agent-session-schema` as
  `[:speed-mode {:optional true} [:maybe [:enum :normal :fast]]]`.
- Add `speed-mode-schema` value `[:enum :normal :fast]`.
- `initial-session` defaults `:speed-mode` to `nil` (provider default).

#### 2. Dispatch handler (`components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj`)

New `:session/set-speed-mode` handler:
- Stores `:speed-mode` on the session.
- Emits optional `:persist/project-prefs-update` or `:persist/user-config-update`
  effect when `scope` is `:project` or `:user` (same pattern as
  `:session/set-thinking-level`).

#### 3. Dispatch schema (`components/agent-session/src/psi/agent_session/dispatch_schema.clj`)

Add `:runtime/agent-set-speed-mode` effect type to the effect schema.

#### 4. Session settings (`components/agent-session/src/psi/agent_session/session_settings.clj`)

Add `set-speed-mode-in!` delegating to `:session/set-speed-mode`.

#### 5. `session->request-options` (`components/agent-session/src/psi/agent_session/prompt_request.clj`)

Propagate `:speed-mode` from session data into `:turn/ai-options` when present
and non-nil (same guard as `:temperature`).

#### 6. Anthropic provider (`components/ai/src/psi/ai/providers/anthropic.clj`)

In `build-request` (and streaming equivalent), when `:speed-mode` is `:fast`
in options, add `speed: "fast"` to the request body.  Omit the key entirely
for `:normal` or nil.

#### 7. OpenAI provider (`components/ai/src/psi/ai/providers/openai/chat_completions.clj`)

In `build-request`, when `:speed-mode` is `:fast`, add `service_tier: "flex"`.
Omit for `:normal` or nil.  No change needed for Codex/responses API — it does
not expose `service_tier`.

#### 8. `/speed` command (`components/agent-session/src/psi/agent_session/commands.clj`)

New `dispatch-speed-command` function:
- No args → show current speed mode: `"Current speed mode: normal"`.
- One arg (`normal` | `fast`) → validate, call `set-speed-mode-in!`, confirm.
- Unknown arg → error with allowed values.

Register in `exact-command-handlers` / `prefixed-command-prefixes` and
`format-help` (same pattern as `/thinking`).

#### 9. Footer / status (`components/app-runtime/src/psi/app_runtime/footer.clj`)

Add `:psi.agent-session/speed-mode` to `footer-query`.  Display `• fast` in
the footer context line when speed mode is `:fast`.

#### 10. Resolvers (`components/agent-session/src/psi/agent_session/resolvers/session.clj`)

Add `:psi.agent-session/speed-mode` resolver projecting from session state.

#### 11. Persistence (`components/shared-config/`)

Add `speed-mode` to the project/user config schema so `/speed fast project`
persists across sessions (same as thinking-level).

### Acceptance criteria — Part 2

- `(session/query-in ctx sid [:psi.agent-session/speed-mode])` returns `:normal`
  (nil coerced) or `:fast` after `/speed fast`.
- `/speed` with no args prints the current mode.
- `/speed fast` sets mode; `/speed normal` clears it.
- `/speed unknown` returns an error listing allowed values.
- Anthropic `build-request` includes `speed: "fast"` iff speed-mode is `:fast`;
  omits it otherwise.
- OpenAI chat-completions `build-request` includes `service_tier: "flex"` iff
  speed-mode is `:fast`; omits it otherwise.
- Footer displays `• fast` when speed mode is `:fast`.
- Unit tests cover: command dispatch (all branches), Anthropic request shaping,
  OpenAI request shaping, session state mutation, resolver projection.
- `bb test` green.

---

## Part 3 — `/effort` command and `:xhigh` differentiation

### Background — current state

The `thinking-level` abstraction (`off/minimal/low/medium/high/xhigh`) serves
two roles simultaneously: it controls whether reasoning is enabled, and it
controls how much computation the provider applies.  This conflation causes a
gap:

- **Anthropic extended thinking** (pre-4.7 models): `:xhigh` is already
  distinct — `budget_tokens: 32000` vs `16000` for `:high`.
- **Anthropic adaptive thinking** (Opus 4.7+): `output_config.effort` accepts
  `"low"/"medium"/"high"`.  Currently `:xhigh` maps to `"high"` — identical
  to `:high`.  No `"highest"` value exists in the current API.
- **OpenAI** `reasoning_effort` accepts `"low"/"medium"/"high"`.  Same ceiling;
  `:xhigh` maps to `"high"` today.

The `/effort` command introduces a direct, provider-level effort override that
is independent of the thinking on/off level.  It also lays the groundwork for
`:xhigh` to become genuinely distinct when providers add higher effort tiers.

### Effort override values

| Value | Anthropic adaptive `output_config.effort` | OpenAI `reasoning_effort` |
|---|---|---|
| `:low` | `"low"` | `"low"` |
| `:medium` | `"medium"` | `"medium"` |
| `:high` | `"high"` | `"high"` |
| `:xhigh` | `"highest"` (when supported; else `"high"` with warning) | `"high"` (API ceiling) |

The effort override is **only applied when thinking is enabled** (i.e.
`thinking-level` ≠ `:off`).  When thinking is off the effort parameter is
irrelevant and is not sent.

The effort override is separate from `thinking-level`: setting `/effort xhigh`
does not change the thinking level; it overrides what effort value is sent for
the current level.  When no effort override is set, the existing
`thinking-level->effort` mapping applies unchanged (preserving backward
compatibility).

### Architecture — full stack

#### 1. Session state (`components/session-state/`)

Add `:effort-override` to `agent-session-schema`:
```
[:effort-override {:optional true} [:maybe [:enum :low :medium :high :xhigh]]]
```
`initial-session` defaults `:effort-override` to `nil` (use level-derived
effort).

#### 2. Dispatch handler (`components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj`)

New `:session/set-effort-override` handler:
- Stores `:effort-override` on the session.
- Emits optional persist effect for `:project` / `:user` scope.

#### 3. Session settings (`components/agent-session/src/psi/agent_session/session_settings.clj`)

Add `set-effort-override-in!`.

#### 4. `session->request-options` (`components/agent-session/src/psi/agent_session/prompt_request.clj`)

Propagate `:effort-override` from session data into `:turn/ai-options` when
non-nil.

#### 5. Anthropic provider (`components/ai/src/psi/ai/providers/anthropic.clj`)

Rename the private `thinking-level->effort` table to
`thinking-level->effort-default`.  Add `thinking-level->effort-xhigh` for
the adaptive path:

```
thinking-level->effort-xhigh:
  {:off nil :minimal "low" :low "low" :medium "medium" :high "high" :xhigh "highest"}
```

In `request-body` / `build-request`, resolve effort as:
1. If `:effort-override` is present in options, use it directly mapped to the
   provider string (`:xhigh` → `"highest"` for adaptive, `"high"` for extended).
2. Otherwise fall back to the existing `thinking-level->effort-default` mapping.

When `"highest"` is sent and the model does not yet support it (detectable via
a 400 response), the provider error surface already handles this — no special
retry logic is needed at design time.

#### 6. OpenAI provider (`components/ai/src/psi/ai/providers/openai/reasoning.clj`)

Update `reasoning-effort` to accept an optional `:effort-override` from
options.  When present, map it: `:xhigh` → `"high"` (API ceiling); others
pass through directly.  When absent, use the existing `thinking-level->effort`
table.

#### 7. `/effort` command (`components/agent-session/src/psi/agent_session/commands.clj`)

New `dispatch-effort-command`:
- No args → show current effort override: `"Current effort override: none"` or
  `"Current effort override: xhigh"`.
- One arg (`low`|`medium`|`high`|`xhigh`|`none`) → validate, call
  `set-effort-override-in!`, confirm.  `none` clears the override (nil).
- Unknown arg → error listing allowed values.

Register in `prefixed-command-prefixes` and `format-help`.

#### 8. Footer / status (`components/app-runtime/src/psi/app_runtime/footer.clj`)

Add `:psi.agent-session/effort-override` to `footer-query`.  When an effort
override is active and thinking is on, display `• effort:xhigh` (or the active
value) appended to the thinking label, e.g. `thinking high • effort:xhigh`.

#### 9. Resolvers (`components/agent-session/src/psi/agent_session/resolvers/session.clj`)

Add `:psi.agent-session/effort-override` resolver.

#### 10. Persistence (`components/shared-config/`)

Add `effort-override` to project/user config schema.

#### 11. `:xhigh` budget for extended thinking — no change needed

`thinking-level->budget {:xhigh 32000}` already differentiates `:xhigh` from
`:high` (16000) for extended-thinking models.  No change.

### Acceptance criteria — Part 3

- `/effort` with no args prints current override (`none` when unset).
- `/effort xhigh` sets override; `/effort none` clears it.
- `/effort unknown` returns error listing allowed values.
- Anthropic adaptive `build-request`: when effort-override is `:xhigh`,
  `output_config.effort` is `"highest"`; when `:high`, `"high"`; when nil,
  falls back to level-derived value.
- OpenAI `reasoning-effort`: when effort-override is `:xhigh`, returns `"high"`
  (ceiling); when `:medium`, returns `"medium"`; when nil, falls back to
  level-derived value.
- Effort override is omitted from the request when thinking-level is `:off`.
- Footer shows `• effort:xhigh` when override is active and thinking is on.
- Unit tests cover: command dispatch (all branches), Anthropic request shaping
  (override present / absent / xhigh), OpenAI request shaping (override present
  / absent / xhigh ceiling), session state mutation, resolver projection.
- `bb test` green.

---

## Combined acceptance criteria — Part 1

- `(psi.ai.model-registry/find-model :anthropic "claude-opus-4-8")` returns a
  non-nil model map.
- The returned map includes `:adaptive-thinking true`.
- The model appears in `(psi.ai.model-registry/models-for-provider :anthropic)`.
- `psi.ai.models/anthropic-json-schema-native-model-keys` contains `:opus-4.8`.
- Existing model tests remain green (`bb test`).
- A focused unit test confirms the new model entry and its structured-output
  capability annotation.
- A gated `^:integration` test (env `PSI_LIVE_ANTHROPIC_MODELS_API=1` +
  `ANTHROPIC_API_KEY`) calls `GET /v1/models` and asserts `"claude-opus-4-8"`
  is present in the response.
- A gated `^:integration` test calls `GET /v1/models/claude-opus-4-8` and
  asserts the response `id` equals `"claude-opus-4-8"`.
- Both gated tests skip gracefully when the gate or key is absent.
