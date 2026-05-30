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
| `:llm-stream-idle-timeout-ms` | positive integer | `600000` | Milliseconds without provider stream progress before the backend aborts the run |

Both `:model-provider` and `:model-id` must be set together; a partial entry is
ignored and the next lower source is used instead.

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
models such as Claude Opus 4.7 and Claude Opus 4.8, `:xhigh` is distinct from
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
