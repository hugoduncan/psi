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
                 :model-id                 "claude-sonnet-4-6"
                 :thinking-level           :medium
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
| `:prompt-mode` | keyword | `:lambda` | System prompt style — `:lambda` or `:prose` |
| `:nucleus-prelude-override` | string | — | Replace the nucleus prelude block in the system prompt |
| `:llm-stream-idle-timeout-ms` | positive integer | `600000` | Milliseconds without provider stream progress before the backend aborts the run |

Both `:model-provider` and `:model-id` must be set together; a partial entry is
ignored and the next lower source is used instead.

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
support reasoning ignore levels above `:off`.

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
