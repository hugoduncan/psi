# Configuration

Psi resolves configuration from multiple sources with a fixed precedence order.
Higher sources win:

```
session         (runtime, not persisted)
  ↑
project-local   — <cwd>/.psi/project.local.edn
  ↑
project-shared  — <cwd>/.psi/project.edn
  ↑
user            — ~/.psi/agent/config.edn
  ↑
system          (compiled-in defaults)
```

Session-level overrides are held in memory for the lifetime of the session and
are not written to disk unless you specify a scope when setting them.

---

## Config files

For custom provider/model definitions loaded from `models.edn` rather than the
session settings files documented below, see [`doc/custom-providers.md`](custom-providers.md).

All config files use the same EDN shape:

```edn
{:version 1
 :agent-session {}}
```

The `:agent-session` map holds the settings described below.

### Project shared config — `<cwd>/.psi/project.edn`

Per-project defaults intended to be shared and committed when you want the whole
team to use the same defaults.

```edn
{:version 1
 :agent-session {:model-provider           "anthropic"
                 :model-id                 "claude-opus-4-8"
                 :thinking-level           :medium
                 :speed-mode               :normal
                 :effort-override          nil
                 :prompt-mode              :lambda
                 :nucleus-prelude-override nil}}
```

### Project local config — `<cwd>/.psi/project.local.edn`

Per-project local overrides intended to remain uncommitted. This is the writable
project preference layer used by persisted project-scoped updates such as
`/model` and `/thinking`.

```edn
{:version 1
 :agent-session {:model-provider "openai"
                 :model-id       "gpt-5.3-codex"
                 :thinking-level :high}}
```

Effective project config is computed by deep-merging:

```clojure
project.edn ← project.local.edn
```

That means:
- local overrides shared on overlapping keys
- nested maps merge recursively
- non-map values replace earlier values
- config may live in either file

### User config — `~/.psi/agent/config.edn`

Personal defaults that apply across all projects. Overridden by both project
layers.

```edn
{:version 1
 :agent-session {:model-provider "anthropic"
                 :model-id       "claude-sonnet-4-6"
                 :thinking-level :off
                 :prompt-mode    :lambda}}
```

---

## Settings reference

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:model-provider` | string | — | Provider name: `"anthropic"` or `"openai"` |
| `:model-id` | string | — | Model id string (e.g. `"claude-sonnet-4-6"`) |
| `:thinking-level` | keyword | `:off` | Extended thinking budget — see below |
| `:speed-mode` | keyword | — | Optional throughput-tier override — `:normal` or `:fast` |
| `:effort-override` | keyword or nil | — | Optional provider reasoning-effort override — `:low`, `:medium`, `:high`, `:xhigh`, or nil |
| `:prompt-mode` | keyword | `:lambda` | System prompt style — `:lambda` or `:prose` |
| `:nucleus-prelude-override` | string | — | Replace the nucleus prelude block in the system prompt |
| `:auto-retry-enabled` | boolean | `true` | Master switch for provider auto-retry |
| `:auto-retry-max-retries` | non-negative signed 64-bit integer or nil | `nil` | Optional hard cap on retries; nil leaves the active time window as the limiter |
| `:auto-retry-base-delay-ms` | positive signed 64-bit integer | `2000` | Initial exponential-backoff delay in milliseconds |
| `:auto-retry-max-delay-ms` | positive signed 64-bit integer | `60000` | Maximum exponential-backoff delay in milliseconds |
| `:auto-retry-total-timeout-ms` | signed 64-bit integer or nil | `600000` | Total retry-window duration in milliseconds; nil, absent, zero, or negative disables the time budget |
| `:llm-stream-idle-timeout-ms` | positive integer | `1200000` | Milliseconds without provider stream progress before the backend aborts the run |

### Provider auto-retry policy

Retry settings use the same user → project-shared → project-local precedence as
other `:agent-session` settings. Runtime session overrides, including CLI or RPC
settings where available, take precedence over file configuration.

With defaults, an enabled session retries retryable provider failures for up to
10 minutes. Set `:auto-retry-enabled` to `false` to disable retries for sessions
created from that configuration. Backoff starts at 2 seconds, doubles per attempt, and is capped at
60 seconds; a positive provider `Retry-After` value takes precedence for that
attempt. The retry window remains interruptible by turn cancellation.

All integer retry settings must be EDN integers within the signed 64-bit range
(`-9223372036854775808` through `9223372036854775807`); fractional, wrong-type,
and out-of-range values are rejected with `Invalid retry configuration` when
the setting becomes active. `:auto-retry-max-retries` is a hard cap only when
set to a non-negative integer. Its default `nil` value means the active
total-time window is the sole limiter. Set `:auto-retry-total-timeout-ms` to
`nil`, `0`, or a negative integer to disable the time budget; when no explicit
retry cap is set in that count-only mode, Psi preserves the fallback cap of 3
retries. Setting either delay control to zero or a negative value is invalid
when an enabled, retryable failure reaches retry scheduling; successful requests
and non-retryable failures do not use or validate inactive delay settings.

Example project policy:

```edn
{:version 1
 :agent-session
 {:auto-retry-enabled          true
  :auto-retry-total-timeout-ms 300000
  :auto-retry-max-retries      8
  :auto-retry-base-delay-ms    1000
  :auto-retry-max-delay-ms     30000}}
```

Both `:model-provider` and `:model-id` must be set together; a partial entry is
ignored and the next lower source is used instead. Catalog validation is distinct
from credential-specific runtime support: `{:model-provider "openai" :model-id
"gpt-5.6"}` is a valid catalog selection for non-OAuth/API-key OpenAI use, but
OpenAI OAuth-backed bare `gpt-5.6` is unsupported until an evidenced ChatGPT/Codex
alias or alternate OAuth-compatible transport is added. When OpenAI is backed by
stored OAuth credentials, configured or profiled bare `gpt-5.6` is rejected at
selection surfaces or fails turn preflight with an unsupported-model error rather
than silently falling back; OAuth-backed `gpt-5.5` and the
`gpt-5.6-sol`/`gpt-5.6-terra`/`gpt-5.6-luna` variants remain on the
OAuth/ChatGPT Codex path.

## Session profiles

Named session profiles let you define reusable model/session-setting bundles in
the same existing config files. They live under `:agent-session :session-profiles`:

```edn
{:version 1
 :agent-session
 {:session-profiles
  {:planning {:model-provider "anthropic"
              :model-id "claude-opus-4-8"
              :thinking-level :high
              :speed-mode :normal
              :effort-override :high}
   :coding   {:model-provider "openai"
              :model-id "gpt-5.5"
              :thinking-level :medium
              :speed-mode :fast}
   :review   {:thinking-level :high}}}}
```

Supported profile fields are exactly `:model-provider`, `:model-id`,
`:thinking-level`, `:speed-mode`, and `:effort-override`. Unknown keys may remain
in the raw EDN files, but profile resolution ignores them and they are not
applied to sessions or stored in workflow snapshots.

Profile definitions are deep-merged only for the `:session-profiles` map, with
precedence:

```text
project.local.edn > project.edn > ~/.psi/agent/config.edn
```

That lets a project override one field of a user profile without redefining the
whole profile. This profile-specific merge does not change ordinary top-level
`:agent-session` setting resolution.

A profile is valid when it has at least one supported concrete setting, its
model provider/id pair is complete and known in the catalog, enum values are
valid, and its name is a selectable unqualified keyword that is not reserved.
Credential-specific runtime policy is checked when the profile is applied or a
turn is prepared, so a catalog-known OpenAI bare `gpt-5.6` profile is not
credential-agnostically supported: with stored OpenAI OAuth credentials it is
unsupported and is rejected or fails preflight rather than falling back. A
profile targeting `gpt-5.6-sol`, `gpt-5.6-terra`, or `gpt-5.6-luna` is
OAuth/Codex-supported, the same as `gpt-5.5`.
Selectable names
match the `/session-profile` command token grammar: the first character must be
a letter or digit, and later characters may be letters, digits, `.`, `_`, or
`-`. Namespaced keywords such as `:team/coding` and command-unparseable names
such as `:fast+coding` are invalid. `:clear` is reserved for `/session-profile
clear` and is not selectable. `/session-profiles` lists valid profiles with
readable settings and invalid profiles with terse reasons.

Commands:

- `/session-profiles` — list effective valid and invalid profiles.
- `/session-profile` — show the current selected-profile metadata and concrete
  model/thinking/speed/effort settings.
- `/session-profile <name>` — apply a valid profile atomically to the current
  session. Names can be typed as `planning` or `:planning`.
- `/session-profile clear` — clear only selected-profile metadata; concrete
  settings already applied remain unchanged.

Applying a profile is session-scoped. It does not write config files. Model and
thinking use the same journaled session mutation semantics as `/model` and
`/thinking`; speed and effort remain transient like `/speed` and `/effort`.
Selected-profile metadata is session-local observability state and is not
journaled, cold-resumed, or inherited by child sessions.

---

## Outbound model API proxy configuration

Psi inherits outbound proxy settings for model API HTTP traffic from the process
environment. There is no parallel proxy field in session config, project config,
or custom provider definitions.

Supported environment variables:

- `HTTPS_PROXY` / `https_proxy` — used for `https://...` model API URLs
- `HTTP_PROXY` / `http_proxy` — used for `http://...` model API URLs
- `ALL_PROXY` / `all_proxy` — fallback when the scheme-specific variable is absent

Precedence rules:

1. uppercase wins over lowercase for the same logical variable
2. scheme-specific wins over `ALL_PROXY`
3. blank values behave as unset

Supported proxy URI schemes in this implementation:

- `http://`
- `https://`
- `socks://`
- `socks5://`

Proxy URIs must include both host and numeric port. Invalid or unsupported proxy
URIs fail explicitly when psi prepares the provider request.

`NO_PROXY` / `no_proxy` is not currently supported for model API transport in
this implementation.

Examples:

```bash
# HTTPS model APIs through an HTTP proxy
export HTTPS_PROXY=http://proxy.example:8443
psi

# HTTP model APIs through a dedicated HTTP proxy
export HTTP_PROXY=http://proxy.example:8080
psi

# Shared fallback proxy, including SOCKS5
export ALL_PROXY=socks5://proxy.example:1080
psi
```

These environment variables affect the shared OpenAI and Anthropic transport
paths used by built-in providers and by custom providers that reuse those same
transport implementations.

### `:thinking-level` values

| Value | Meaning |
|-------|---------|
| `:off` | No extended thinking (default) |
| `:minimal` | Minimal budget |
| `:low` | Low budget |
| `:medium` | Medium budget |
| `:high` | High budget |
| `:xhigh` | Maximum budget |

The level is clamped to what the selected model supports. Models that do not
support reasoning ignore levels above `:off`. For Anthropic adaptive-thinking
models such as Claude Opus 5 and Claude Sonnet 5, `:xhigh` is distinct from
`:high` and sends the provider effort value `"highest"`; if the provider rejects
that preview value, psi surfaces the provider error without retrying as `:high`.

### `:speed-mode` values

| Value | Meaning | Provider mapping |
|-------|---------|------------------|
| `:normal` | Provider default throughput tier | no native speed parameter |
| `:fast` | Provider alternate throughput tier | Anthropic `speed: "fast"` with the fast-mode beta header; OpenAI chat-completions `service_tier: "flex"` |

`:normal` and a missing value both omit native provider speed parameters.
`/speed normal session` clears the session override; `/speed normal project` and
`/speed normal user` persist an explicit `:normal` mask at that scope. Speed mode
is session-transient on cold resume; persisted project/user config applies when a
new root session is created, not when a journal is resumed.

### `:effort-override` values

| Value | Meaning |
|-------|---------|
| nil | No override; derive effort from `:thinking-level` |
| `:low` | Force low provider effort while thinking is enabled |
| `:medium` | Force medium provider effort while thinking is enabled |
| `:high` | Force high provider effort while thinking is enabled |
| `:xhigh` | Force maximum psi effort; Anthropic adaptive models send `"highest"`, OpenAI transports cap to `"high"` |

The effort override is only sent when thinking is enabled. `/effort none` clears
the in-memory override; `/effort none project` and `/effort none user` persist an
explicit nil mask at that scope. Effort override is session-transient on cold
resume; persisted project/user config applies when a new root session is created.

### `:prompt-mode` values

| Value | Meaning |
|-------|---------|
| `:lambda` | Lambda-calculus compressed system prompt (default) |
| `:prose` | Plain prose system prompt |

---

## Runtime setters and scope

Settings can be changed at runtime via EQL mutations. Each setter accepts an
optional `:scope` keyword controlling where the change is persisted.

| `:scope` | Persists to |
|----------|-------------|
| `:session` | Memory only — lost when session ends |
| `:project` | `<cwd>/.psi/project.local.edn` |
| `:user` | `~/.psi/agent/config.edn` |

### `psi.extension/set-model`

```clojure
;; runtime only
{:model {:provider :anthropic :id "claude-sonnet-4-6"} :scope :session}

;; save to project-local overrides (default when :scope is omitted)
{:model {:provider :anthropic :id "claude-sonnet-4-6"} :scope :project}

;; save to user config
{:model {:provider :anthropic :id "claude-sonnet-4-6"} :scope :user}
```

Default scope: `:project`.

### `psi.extension/set-thinking-level` (via RPC `set_thinking_level`)

```clojure
{:level :medium :scope :project}
```

Default scope: `:project`.

### Speed and effort runtime settings

The interactive commands are:

```text
/speed                         # show current effective speed mode
/speed fast                    # session-local alternate throughput tier
/speed normal project          # persist explicit project default/provider default
/effort                        # show current effort override
/effort xhigh                  # session-local effort override
/effort none user              # persist explicit user-level clear/mask
```

There is currently no extension EQL mutation named `psi.extension/set-speed-mode`
or `psi.extension/set-effort-override`. Use the interactive `/speed` and
`/effort` commands for these runtime settings; their optional scope arguments
use the same `:session`, `:project`, and `:user` meanings as model and thinking
settings.

### `psi.extension/set-prompt-mode`

```clojure
{:mode :prose :scope :user}
```

Default scope: `:session` (prompt-mode changes are session-local unless you
explicitly persist them).

---

## Resolution behavior

When psi resolves effective configuration for a session worktree, it:

1. Starts from compiled system defaults
2. Reads `~/.psi/agent/config.edn` (user config — missing file is silently ignored)
3. Reads `<cwd>/.psi/project.edn` (shared project config — missing file is silently ignored)
4. Reads `<cwd>/.psi/project.local.edn` (local project config — missing file is silently ignored)
5. Applies session runtime overrides held in memory
6. Merges config with later/higher layers winning

At the project layer specifically, psi deep-merges:

```clojure
shared project.edn ← local project.local.edn
```

Malformed project config files emit warnings and are ignored best-effort:
- malformed shared + valid local => local still applies
- malformed local + valid shared => shared still applies
- both malformed => fall back to lower layers/defaults

---

## Precedence summary

Effective precedence is:

```text
session runtime overrides
> project local config
> project shared config
> user config
> system defaults
```

For model selection, that means project-local overrides beat project-shared
committed defaults.
