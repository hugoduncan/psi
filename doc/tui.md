# TUI Guide

This document covers interactive terminal usage for psi (`--tui`).

## Integration harness (tmux)

A baseline tmux-backed integration harness test exists at:
- `components/tui/test/psi/tui/tmux_integration_harness_test.clj`

Reusable harness helpers live at:
- `components/tui/test/psi/tui/test_harness/tmux.clj`

What it validates:
1. start psi TUI in detached tmux using the resolved launcher command
   - for unreleased worktree testing, prefers the repo-local launcher so the harness proves the current checkout rather than a stale installed `psi` on `PATH`
   - otherwise prefers canonical installed command: `exec psi --tui`
   - falls back to repo-local launcher when `psi` is not on `PATH`: `exec bb bb/psi.clj -- --tui`
2. wait until prompt is ready (`刀:` / `Type a message`)
3. send `/help` and assert help output marker appears
4. send `/quit` and assert pane process exits the Java TUI process

Run it explicitly (integration tests are skipped by default in `tests.edn`):

```bash
clojure -M:test --focus psi.tui.tmux-integration-harness-test --skip-meta foo
```

## Start

```bash
# Basic TUI start
psi --tui

# OpenAI example
psi --model gpt-4o --tui

# TUI with live nREPL introspection
psi --tui --nrepl 8888
```

Development/non-canonical alternatives may still use repo-local `clojure -M:run ...`
paths, but the launcher-owned `psi` command is now the primary operator path.
For released installs, that path now depends on the jar-owned release metadata
packaged inside the published psi artifact, so `--tui` smoke coverage should run
through the installed/package-shaped launcher path rather than only through
repo-local startup.

## OAuth Login (no env var needed)

```text
/login    # browser-based OAuth flow
/logout
```

## In-session commands

`/status` `/history` `/new` `/resume` `/tree [session-id]` `/worktree` `/help` `/quit` `/skills` `/prompts` `/reload-prompts` `/reload-models` `/reload-extension-installs` `/remember [text]`
`/model <provider> <model-id>` `/thinking <off|minimal|low|medium|high|xhigh>` `/speed [normal|fast [session|project|user]]` `/effort [low|medium|high|xhigh|none [session|project|user]]`
`/jobs [status ...]` `/job <job-id>` `/cancel-job <job-id>`
`/project-repl` `/project-repl start` `/project-repl attach` `/project-repl stop` `/project-repl eval <code>` `/project-repl interrupt`
`/operations` `/operation <id> {edn-args}`
`/skill:<name>` plus any extension commands such as `/work-on`, `/work-done`, `/work-rebase`, `/work-status`


### Model, speed, and effort commands

- `/model anthropic claude-opus-4-8` selects Claude Opus 4.8, an Anthropic
  adaptive-thinking model with native JSON Schema structured output and
  mid-conversation system-message support.
- `/speed` prints the effective speed mode. `/speed fast` selects the provider's
  alternate throughput tier for the current session. Add `project` or `user` to
  persist the setting; `/speed normal session` clears the session override.
  Anthropic maps fast mode to `speed: "fast"`; OpenAI chat-completions maps it
  to `service_tier: "flex"`.
- `/effort` prints the active reasoning-effort override (`none` when unset).
  `/effort xhigh` overrides provider effort while thinking is enabled without
  changing `/thinking`; `/effort none` clears the override. Add `project` or
  `user` to persist the value or explicit clear.

The footer shows `• fast` when speed mode is fast and appends
`• effort:<value>` to the thinking label when an effort override is active.
Speed and effort are not restored from cold journal resume; project/user config
only applies to newly created root sessions.

### Prompt templates

- `/prompts` lists the prompt templates discovered for the session (from
  `~/.psi/agent/prompts/*.md` and `<worktree>/.psi/prompts/*.md`), each
  invokable as `/<name>`.
- `/reload-prompts` re-discovers the prompt templates from disk for the
  session's worktree and replaces the session's registered templates, so
  editing, adding, or deleting a prompt `.md` takes effect without restarting
  the session. It reports the worktree path and the resulting template count.
  The same reload is available to extensions/`psi-tool` via the
  `psi.extension/reload-prompts` mutation.

### Multi-session commands

- `/resume` dispatches to the backend; when selection is needed, the backend requests
  a frontend action and TUI renders the persisted-session picker (session files on disk).
- `/tree` dispatches to the backend; when selection is needed, the backend requests
  a frontend action and TUI renders the live context session picker (in-process multi-session view).
- `/tree <session-id|prefix>` switches directly to a live context session by id.
- `/tree name <session-id|prefix> <name>` assigns an explicit human name to a live context session.
- In `/tree` picker mode, sessions render in parent/child order with explicit tree
  glyphs (root `●`, branch connectors `├─` / `└─` / `│`).
- Right-side status cells are column-aligned across rows (`[active] [stream]`) to
  keep deep trees and mixed session states visually stable.

The selector UI is frontend-native, but candidate lists and command semantics are backend-owned.

### Deterministic operation commands

- `/operations` lists the deterministic operations registered in the session as
  `<id> — <description>` lines, sorted by id. An empty registry prints
  `No deterministic operations registered.`
- `/operation <id> {edn-args}` invokes operation `<id>` with the EDN-map
  `{edn-args}` (default `{}` when omitted) and renders the tagged result as text:
  the `:status` line first, then the remaining top-level result keys sorted by
  their printed form, one `<key> <value>` line per key. Each value is `pr-str`'d
  and truncated to 2000 characters (with a `… (truncated, N chars total)` marker).
- `<id>` is the first whitespace-delimited token; the remainder is parsed as the
  EDN args map. A blank id prints the usage message; malformed or non-map args
  print a clear parse error; an unknown id or a malformed operation result is
  surfaced as a distinct text message (never a crash).
- Both surfaces (this command and the psi-tool `operation` action) share one
  invocation/listing mechanism and render identically; side-effecting operations
  are invokable.
