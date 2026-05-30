# 190 — Add Claude Opus 4.8 + /speed + /effort + mid-conversation system messages

## Goal

1. Register `claude-opus-4-8` as a supported model in the psi model catalog.
2. Add a `/speed` command (analogous to `/thinking`) that controls a per-session
   speed mode, propagated to providers that support it.
3. Add an `/effort` command that directly controls the provider reasoning-effort
   string, orthogonal to thinking on/off.
4. Make `:xhigh` thinking level genuinely distinct from `:high` on all providers
   that support it.
5. Support mid-conversation system messages: a model capability that allows
   `role: "system"` messages to be injected into the message array after user
   turns, with a first-class extension API and EQL queryable capability flag.

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

No new runtime effect type is needed for `/speed`. Unlike model and thinking
level, speed mode is consumed entirely from canonical session data via
`session->request-options` at request-build time, so there is no parallel
agent-core state to keep in sync.

#### 4. Session settings (`components/agent-session/src/psi/agent_session/session_settings.clj`)

Add `set-speed-mode-in!` delegating to `:session/set-speed-mode`.

#### 5. `session->request-options` (`components/agent-session/src/psi/agent_session/prompt_request.clj`)

Propagate `:speed-mode` from session data into `:turn/ai-options` when present
and non-nil (same guard as `:temperature`).

#### 6. Anthropic provider (`components/ai/src/psi/ai/providers/anthropic.clj`)

In `build-request` (and streaming equivalent), when `:speed-mode` is `:fast`
in options, add `speed: "fast"` to the request body and include the Anthropic
fast-mode beta header token `fast-mode-2026-02-01` via `beta-header` /
`request-headers`.

Omit the body key and the beta token entirely for `:normal` or nil.

#### 7. OpenAI provider (`components/ai/src/psi/ai/providers/openai/chat_completions.clj`)

In `build-request`, when `:speed-mode` is `:fast`, add `service_tier: "flex"`.
Omit for `:normal` or nil.  No change needed for Codex/responses API — it does
not expose `service_tier`.

#### 8. `/speed` command (`components/agent-session/src/psi/agent_session/commands.clj`)

New `dispatch-speed-command` function:
- No args → show current speed mode: `"Current speed mode: normal"`.
- One or two args: `<mode> [session|project|user]`.
  - `normal` | `fast` validates and calls `set-speed-mode-in!`.
  - Optional scope token follows the existing `/model` persistence pattern:
    `session` = in-memory only, `project` = persist to project prefs,
    `user` = persist to user config.
- Unknown mode or unknown scope → error with allowed values.

Register in `exact-command-handlers` / `prefixed-command-prefixes` and
`format-help` (same pattern as `/thinking`, but with optional scope support).

#### 9. Footer / status (`components/app-runtime/src/psi/app_runtime/footer.clj`)

Add `:psi.agent-session/speed-mode` to `footer-query`.  Display `• fast` in
the footer context line when speed mode is `:fast`.

#### 10. Resolvers (`components/agent-session/src/psi/agent_session/resolvers/session.clj`)

Add `:psi.agent-session/speed-mode` resolver projecting the display/effective
mode from session state.  The resolver must coerce nil session state to
`:normal`, so query/UI surfaces always report the user-facing default even
though the canonical in-memory override remains nil for provider-default
request shaping.  After `/speed normal session`, session state is cleared back
to nil and the resolver again returns `:normal`.

#### 11. Persistence (`components/shared-config/`)

Add `speed-mode` to the project/user config schema so `/speed fast project`
persists across sessions (same as thinking-level).

Scoped clearing uses **explicit persistent defaults**, not key deletion.  This
matches the existing merge-only project/user config update helpers and gives the
selected scope a stable value that masks lower-precedence layers:

- `/speed normal session` clears the in-memory session override (`nil` in
  session state).
- `/speed normal project` writes `{:speed-mode :normal}` to project prefs.
- `/speed normal user` writes `{:speed-mode :normal}` to user config.

When effective config is applied to a new session, persisted `:normal` is
interpreted as provider default/no native speed parameter, but it still wins over
lower-precedence `:fast` values.

### Acceptance criteria — Part 2

- `(session/query-in ctx sid [:psi.agent-session/speed-mode])` returns `:normal`
  (nil coerced) or `:fast` after `/speed fast`.
- `/speed` with no args prints the current mode.
- `/speed fast` sets mode; `/speed normal` clears it.
- `/speed fast project` persists the setting to project prefs; `session` leaves
  it in-memory only; `user` writes user config.
- `/speed normal project|user` persists explicit `:normal` at that scope, masking
  lower-precedence `:fast` values while still omitting provider speed params.
- `/speed unknown` returns an error listing allowed values.
- `/speed fast bogus` returns an error listing allowed scopes.
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
| `:xhigh` | `"highest"` (no fallback in this slice; provider 400s surface as-is if unsupported) | `"high"` (API ceiling) |

The effort override is **only applied when thinking is enabled** (i.e.
`thinking-level` ≠ `:off`).  When thinking is off the effort parameter is
irrelevant and is not sent.

For provider asymmetry, the user-facing meaning of `/speed fast` is **use the
provider's non-default alternate throughput tier** rather than a strict global
promise of "faster". Help text and docs should note the concrete provider
mapping: Anthropic `fast` means higher throughput at premium pricing, while
OpenAI chat-completions `fast` maps to `service_tier: "flex"`, which is a
lower-priority / cheaper tier rather than a latency upgrade.

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
- Emits optional persist effect for `:project` / `:user` scope, matching `/effort <value> [session|project|user]`.

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

`"highest"` has **no transparent retry fallback in this slice**. Psi should
always send `"highest"` for adaptive Anthropic `:xhigh`, and if a model/API
combination rejects it with a 400, that provider error should surface to the
user as-is. Remove any implied "fallback with warning" behaviour from code,
tests, and docs in this task.

Because provider rejection is the intended unsupported-value surface,
`components/ai/src/psi/ai/providers/anthropic/request_schema.clj` must accept
`output_config.effort = "highest"` in `anthropic-output-config-schema` alongside
`"low"`, `"medium"`, and `"high"`.  The local request schema is a psi request
well-formedness gate, not the Anthropic capability ceiling for this value; it
must not reject `"highest"` before the HTTP request is attempted.

#### 6. OpenAI providers

`/effort` applies to both OpenAI transports that currently send reasoning effort:

- `components/ai/src/psi/ai/providers/openai/reasoning.clj` for chat completions.
- `components/ai/src/psi/ai/providers/openai/codex_responses.clj` for Codex/responses.

Update `reasoning-effort` to accept an optional `:effort-override` from
options.  When present, map it: `:xhigh` → `"high"` (API ceiling); others
pass through directly.  When absent, use the existing `thinking-level->effort`
table.  Codex/responses request shaping must use the same mapping instead of
reading `thinking-level->effort` directly, so `/effort xhigh` produces
`{"reasoning": {"effort": "high", "summary": "auto"}}` for Codex models
when thinking is enabled.

The override is still omitted from both OpenAI transports when thinking is off.

#### 7. `/effort` command (`components/agent-session/src/psi/agent_session/commands.clj`)

New `dispatch-effort-command`:
- No args → show current effort override: `"Current effort override: none"` or
  `"Current effort override: xhigh"`.
- One or two args: `<value> [session|project|user]`.
  - `low`|`medium`|`high`|`xhigh`|`none` validates and calls
    `set-effort-override-in!`; `none` clears the override (nil).
  - Optional scope token follows the same persistence pattern as `/speed`:
    `session` = in-memory only, `project` = persist to project prefs,
    `user` = persist to user config.
- Unknown value or unknown scope → error with allowed values/scopes.

Register in `prefixed-command-prefixes` and `format-help`, documenting the
optional scope form.

#### 8. Footer / status (`components/app-runtime/src/psi/app_runtime/footer.clj`)

Add `:psi.agent-session/effort-override` to `footer-query`.  When an effort
override is active and thinking is on, display `• effort:xhigh` (or the active
value) appended to the thinking label, e.g. `thinking high • effort:xhigh`.

#### 9. Resolvers (`components/agent-session/src/psi/agent_session/resolvers/session.clj`)

Add `:psi.agent-session/effort-override` resolver.

#### 10. Persistence (`components/shared-config/`)

Add `effort-override` to project/user config schema.

Scoped clearing uses **explicit persistent nil**, not key deletion.  This keeps
persistence compatible with the existing merge-only update helpers and lets the
chosen higher-precedence layer mask lower-precedence effort overrides:

- `/effort none session` clears the in-memory session override (`nil` in
  session state).
- `/effort none project` writes `{:effort-override nil}` to project prefs.
- `/effort none user` writes `{:effort-override nil}` to user config.

When effective config is applied to a new session, an explicit nil means “no
effort override; use thinking-level-derived provider defaults” and should not
fall through to a lower-precedence project/user override.

#### 11. `:xhigh` budget for extended thinking — no change needed

`thinking-level->budget {:xhigh 32000}` already differentiates `:xhigh` from
`:high` (16000) for extended-thinking models.  No change.

### Acceptance criteria — Part 3

- `/effort` with no args prints current override (`none` when unset).
- `/effort xhigh` sets override; `/effort none` clears it.
- `/effort xhigh project` persists the override to project prefs; `session` leaves
  it in-memory only; `user` writes user config.
- `/effort none project|user` persists explicit nil at that scope, masking
  lower-precedence effort overrides while restoring level-derived defaults.
- `/effort unknown` returns error listing allowed values.
- `/effort xhigh bogus` returns an error listing allowed scopes.
- Anthropic adaptive `build-request`: when effort-override is `:xhigh`,
  `output_config.effort` is `"highest"`; when `:high`, `"high"`; when nil,
  falls back to level-derived value; local request schema validation permits
  `"highest"` so any unsupported-value failure comes from the provider.
- OpenAI chat-completions `reasoning-effort`: when effort-override is `:xhigh`, returns `"high"`
  (ceiling); when `:medium`, returns `"medium"`; when nil, falls back to
  level-derived value.
- OpenAI Codex/responses `codex-reasoning`: when effort-override is `:xhigh`, sends `"high"`
  (ceiling); when `:medium`, sends `"medium"`; when nil, falls back to
  level-derived value.
- Effort override is omitted from the request when thinking-level is `:off` for all supported providers.
- Footer shows `• effort:xhigh` when override is active and thinking is on.
- Unit tests cover: command dispatch (all branches), Anthropic request shaping
  (override present / absent / xhigh), OpenAI chat-completions and Codex/responses
  request shaping (override present / absent / xhigh ceiling), session state
  mutation, resolver projection.
- `bb test` green.

---

## Part 4 — Mid-conversation system messages

### Background

Opus 4.8 introduces `role: "system"` messages that can appear inside the
`messages` array, immediately after a user turn.  This is distinct from the
top-level `system` field (which is the base system prompt).  A mid-conversation
system message lets callers append updated instructions — revised permissions,
changed token budgets, updated context — without restating the full system
prompt, preserving cache hits on earlier turns and reducing input cost on
agentic loops.

**Placement rule** (Anthropic): a mid-conversation system message is valid only
when it is associated with a preceding user turn. In psi request assembly this
means it may appear immediately after the most recent user message, including as
the final message in the next generation request, because the assistant response
being generated is the implicit following turn. It must not appear immediately
after another system message or after an assistant message. In practice the safe
position is immediately after the most recent user turn and before the assistant
turn that is about to be generated.

**OpenAI**: The OpenAI chat-completions API has always supported
`{"role": "system", "content": "..."}` objects inside the `messages` array at
any position; no special handling is needed beyond passing the message through.
Codex/responses API does not support mid-conversation system messages.

### Model capability flag

A new boolean model attribute `:supports-mid-conversation-system-messages`
(default `false`) gates the feature at the model layer.  This is queryable via
EQL so extensions and workflows can introspect before injecting.

Models that support it:
- `:opus-4.8` — `true`
- All other Anthropic models — `false` for now (add as Anthropic confirms)
- All OpenAI chat-completions models — `true` (already supported by the API)
- Codex/responses models — `false`

### New internal message kind: `:mid-system`

The journal and provider message pipeline need a new message kind to carry
mid-conversation system text without conflating it with user or assistant turns.

**Journal entry kind**: add `:mid-system` to `session-entry-kind-schema` in
`components/session-state/src/psi/session_state/model.clj`.

**Provider message role**: add `:system` to `MessageRole` schema in
`components/ai/src/psi/ai/schemas.clj`.

### Architecture — full stack

#### 1. Model schema (`components/ai/src/psi/ai/schemas.clj`)

Add `:system` to `MessageRole` enum.

#### 2. Model definitions (`components/ai/src/psi/ai/models.clj`)

Add optional `:supports-mid-conversation-system-messages` boolean metadata to
the `Model` schema in `schemas.clj`:
`[:supports-mid-conversation-system-messages {:optional true} boolean?]`.
Absent means false at every read/capability surface.  Set `true` on `:opus-4.8`
and all OpenAI chat-completions models.  Other models may either omit the key or
set it explicitly to `false`; both forms are semantically equivalent.

#### 3. Session state (`components/session-state/src/psi/session_state/model.clj`)

Add `:mid-system` to `session-entry-kind-schema`.

#### 4. Conversation assembly (`components/turn-runtime/src/psi/turn_runtime/conversation.clj`)

In `append-msg`, handle `"system"` role by appending the projected provider
message produced by `journal->provider-messages`: `{:role "system" :content
[{:type :text :text "..."}]}`.

The AI conversation representation remains schema-normalized, not
provider-shaped.  Add `:system` to `psi.ai.schemas/MessageRole` and add/use a
conversation helper (for example `conv/add-system-message`) that appends a
schema-valid AI message with keyword role `:system` and normalized text content
`{:kind :text :text ...}`.  The `append-msg` `"system"` branch is responsible
for normalizing provider-style text blocks (`{:type :text :text ...}`) into
that canonical `MessageContent` shape before appending.  Provider-specific
transformers later turn the canonical `:system` message back into provider
wire shape.

#### 5. Anthropic provider (`components/ai/src/psi/ai/providers/anthropic.clj`)

In `transform-message`, add a `:system` case:
```clojure
:system
(conj acc {:role "system"
           :content (user-content msg)})
```
This emits `{"role": "system", "content": [...]}` inline in the `messages`
array.  No beta header is required.

`components/ai/src/psi/ai/providers/anthropic/request_schema.clj` must admit
inline system messages in the local Anthropic request validator. Add a system
message schema with `:role [:= "system"]` and text-block content only (the same
text/cache-control block shape used for user text content), and include it in
`anthropic-message-schema` alongside user and assistant messages. Mid-system
requests must therefore pass psi's local request-shape gate; unsupported
placement or capability failures are handled by the explicit placement logic and
provider/model gates, not by rejecting the `"system"` role as an unknown local
schema value.

**Placement validation**: when assembling `transform-messages`, assert that every
inline system message is preceded by a user message, allowing it to be the final
message in the next generation request. Violations (consecutive system messages
or a system message after an assistant message / at the beginning) log a warning
and drop the offending message rather than sending a malformed request.

#### 6. OpenAI chat-completions provider (`components/ai/src/psi/ai/providers/openai/chat_completions.clj`)

OpenAI already accepts `{"role": "system", ...}` objects in the messages array.
Map the internal `:system` role → `"system"` string in the message transform.
No other change needed.

#### 7. Dispatch handler — inject mid-system message

New `:session/inject-mid-system-message` handler in
`dispatch_handlers/session_mutations.clj`:
- Validates that the active model supports the capability (queries
  `:supports-mid-conversation-system-messages`).  Returns an error map if not.
- Validates placement at dispatch time before mutating the journal. Injection is
  accepted only when the current journal tail has a user turn as the latest
  conversational entry and there is not already a pending `:mid-system` entry
  after that user turn. This covers the intended use: after a user turn, before
  the assistant response being generated.
- Invalid placements are rejected without modifying the journal:
  - before any user turn → `{:ok false :error :invalid-placement :reason :no-preceding-user}`
  - after an assistant turn → `{:ok false :error :invalid-placement :reason :after-assistant}`
  - after another pending `:mid-system` entry → `{:ok false :error :invalid-placement :reason :pending-mid-system}`
- On valid placement, appends a `:mid-system` journal entry with `{:text text :source source}`.
- The journal entry is projected into a `{:role "system" ...}` provider message
  at request-build time via the conversation assembly path above. Because valid
  injection occurs after the latest user turn and before the next assistant
  response, that system message is intentionally final in the generation
  request and must be retained.
- Emits no runtime effects (journal-only; no notify, no steering message).

#### 8. Extension API (`components/agent-session/src/psi/agent_session/extensions/api.clj`)

Add `inject-mid-system-message!` to the extension API:
```clojure
inject-mid-system-message!
(fn [text]
  (mutate-ext-required :inject-mid-system-message
                       'psi.extension/inject-mid-system-message
                       {:text text}))
```
Extensions call this to append updated instructions mid-conversation.  The
function returns `{:ok true}`, `{:ok false :error :capability-not-supported}`
when the active model does not support the feature, or `{:ok false :error
:invalid-placement :reason ...}` for the invalid timing cases enforced by the
dispatch handler.

#### 9. EQL resolver (`components/agent-session/src/psi/agent_session/resolvers/session.clj`)

Add `:psi.agent-session/model-supports-mid-system-messages` resolver:
- Input: `[:psi/agent-session-ctx :psi.agent-session/session-id]`
- Output: boolean derived from the active model's
  `:supports-mid-conversation-system-messages` flag, treating an absent flag as
  `false`.

This is the queryable capability surface extensions use before calling
`inject-mid-system-message!`.

#### 10. `prompt_request.clj` — journal projection

`journal->provider-messages` already projects `:message` entries. Extend it
to also project `:mid-system` entries as `{:role "system" :content [{:type
:text :text "..."}]}` messages, inserted at the correct position in the
provider message sequence.

This exact shape is the contract consumed by Part 4 step 4's `append-msg`
`"system"` branch: string role key `"system"`, and `:content` as a vector of
provider-style content blocks `{:type :text :text ...}`.

Prepared-turn assembly must treat a pending mid-system entry after the current
user turn as attached to that user turn. `replace-current-user-message` (or an
equivalent helper in this assembly path) should detect the tail shape
`... user, system` where the final system message came from a pending
`:mid-system` entry, replace the preceding user message with the expanded/current
`user-message`, and preserve the system message after it. The resulting order
must remain `user → system`. If the tail is just `... user`, keep the existing
replacement behaviour; if the tail is any other role sequence, do not infer a
replacement target. This ensures template/skill expansion or cache-breakpoint
updates to the current user turn are not skipped merely because a valid pending
mid-system instruction is already attached to it.

#### 11. Compaction (`components/agent-session/src/psi/agent_session/compaction.clj`)

Mid-system messages must be preserved across compaction boundaries: they are
not part of the conversation history to be summarised, but are instructions
that remain valid for the remainder of the session. Compaction must therefore
not expire pre-cut `:mid-system` instructions.

The concrete preservation mechanism in this slice is:

1. Extend `entry->message` to handle `:mid-system` entries by returning a
   provider-style message map `{:role "system" :content [{:type :text :text
   ...}]}` so post-compaction message rebuilds can retain them.
2. Carry forward post-cut `:mid-system` journal entries normally.
3. Preserve pre-cut active `:mid-system` entries outside the summarised
   conversation by coalescing their text, in original order, into one retained
   `:mid-system` entry immediately after the compaction summary user turn. This
   avoids consecutive inline system messages after compaction while preserving
   their continuing instruction effect.

#### 12. Cache interaction

Mid-system messages inserted after a cached user turn will break the cache hit
on the message immediately preceding them.  This is expected and is the stated
trade-off: the cache hit on all earlier turns is preserved; only the turn
immediately preceding the injection loses its cache breakpoint.  No special
cache-control logic is required.

### Acceptance criteria — Part 4

- `:opus-4.8` model map has `:supports-mid-conversation-system-messages true`.
- All OpenAI chat-completions models have `:supports-mid-conversation-system-messages true`.
- Codex/responses models and pre-4.8 Anthropic models have the flag `false` or absent.
- `(session/query-in ctx sid [:psi.agent-session/model-supports-mid-system-messages])`
  returns `true` when an opus-4.8 session is active, `false` otherwise.
- `inject-mid-system-message!` on an opus-4.8 session appends a `:mid-system`
  journal entry, conversation assembly normalizes it to a schema-valid
  `:system` AI message, and the next `build-prepared-request` includes a
  `{"role": "system", ...}` message in the Anthropic messages array.
- `inject-mid-system-message!` on a non-supporting model returns
  `{:ok false :error :capability-not-supported}` and does not modify the journal.
- `inject-mid-system-message!` before any user turn, after an assistant turn, or
  after another pending mid-system entry returns `{:ok false :error
  :invalid-placement ...}` and does not modify the journal.
- Anthropic `request_schema.clj` accepts inline `{"role": "system", ...}`
  messages with text-block content in the `messages` array, so valid mid-system
  requests are not rejected by local request validation.
- Anthropic `transform-messages` emits `{"role": "system", ...}` for `:system`
  role messages; allows a system message that immediately follows a user message
  even when it is final in the generation request; drops (with warning) any
  system message at the beginning, immediately after another system message, or
  immediately after an assistant message.
- Prepared-turn current-user replacement preserves an attached pending
  mid-system tail by replacing `... user, system` as `... current-user, system`,
  maintaining provider order `user → system`.
- OpenAI chat-completions message transform maps `:system` → `"system"` string.
- Compaction preserves both post-cut `:mid-system` entries and pre-cut active mid-system instructions (coalesced after the summary user turn).
- Unit tests cover: model capability flag, resolver, dispatch handler (supported
  / unsupported model), Anthropic transform (valid placement, invalid placement
  warning+drop), OpenAI transform, journal projection, compaction preservation.
- Extension API `inject-mid-system-message!` integration test using nullable
  extension helpers.
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
