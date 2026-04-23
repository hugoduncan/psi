# ψ Psi — A Clojure AI Agent

A self-evolving AI coding agent built in Clojure. Statechart-driven,
EQL-queryable, extensible. Inspired by
[pi-mono](https://github.com/badlogic/pi-mono).


## Values

- Extensions can completely customise the agent.
- Everything is introspectable
- AI provider agnostic
- Minimal builtin behaviour

---

## Quick Start

Define a user alias in `~/.clojure/deps.edn` that points to your local psi clone:

```clojure
{:aliases
 {:psi {:replace-deps {psi/psi {:local/root "/path/to/your/psi-main"}}
        :main-opts ["-m" "psi.main"]}}}
```

Then run psi with that alias:

```bash
# Bare console
clojure -M:psi

# Terminal UI
clojure -M:psi --tui
```

For CLI flags, environment variables, and switch behavior, see:
- [`doc/cli.md`](doc/cli.md)

### Emacs UI usage

For keybindings, rendering behavior, reconnect semantics, and developer checks, see:
- [`doc/emacs-ui.md`](doc/emacs-ui.md)

### TUI usage

For TUI login flow, in-session commands, and runtime behavior, see:
- [`doc/tui.md`](doc/tui.md)

### Built-in Tools

`read` `bash` `edit` `write` `psi-tool`

`psi-tool` is the live runtime introspection/modification tool with canonical action-based requests:
- `query` — EQL graph reads
- `eval` — in-process namespace-scoped Clojure eval
- `reload-code` — explicit namespace/worktree code reload with distinct reload and graph-refresh reporting
- `project-repl` — managed project REPL status/start/attach/stop/eval/interrupt operations with structured reports
- `scheduler` — delayed one-shot work via explicit `create|list|cancel`, including both delayed same-session prompts and delayed fresh top-level session creation

See:
- [`doc/psi-project-config.md`](doc/psi-project-config.md) for examples and reload targeting rules
- [`doc/scheduler.md`](doc/scheduler.md) for scheduler kinds, session-config support, status semantics, and introspection attrs

### Extension API

For extension-facing runtime/query details (including memory durability operations), see:
- [`doc/extension-api.md`](doc/extension-api.md)

This includes the preferred workflow public-data display convention for
workflow-backed extensions.

### Extension install manifests

For the deps-shaped `extensions.edn` install model, apply semantics, and
introspection fields, see:
- [`doc/extensions-install.md`](doc/extensions-install.md)

Note: extension slash commands now route implicit extension query/mutate calls through
the active session that invoked the command. Explicit `query-session` / `mutate-session`
helpers remain the preferred surface for cross-session or delayed/background extension work.

For built-in extension docs (`extensions/` per-project local roots), see:
- [`doc/extensions.md`](doc/extensions.md)

Project-local extension/config examples in this repo include:
- [`.psi/extensions.edn`](.psi/extensions.edn)
- [`.psi/commit-checks.edn`](.psi/commit-checks.edn)
- `bb commit-check:rama-cc`
- `bb commit-check:file-lengths`
- `bb commit-check:dispatch-architecture`

## Architecture

For architecture overview, components, EQL introspection guidance, and roadmap, see:
- [`doc/architecture.md`](doc/architecture.md)

## Graph discovery

For the session-root graph discovery surface (`:psi.graph/*`), canonical discovery
workflow, and graph semantics, see:
- [`doc/graph-surface.md`](doc/graph-surface.md)

For prompt lifecycle introspection summaries and normalized prompt-turn attrs, see:
- [`doc/architecture.md`](doc/architecture.md)

## Configuration

Config file locations, precedence (session > project-local > project-shared > user > system), settings
reference, runtime scoped setters, and custom provider setup:
- [`doc/configuration.md`](doc/configuration.md)
- [`doc/custom-providers.md`](doc/custom-providers.md)

## ψ Psi project config

Project query/config tool details:
- [`doc/psi-project-config.md`](doc/psi-project-config.md)

## Project nREPL

For direct project-local REPL support distinct from psi's own runtime nREPL, see:
- [`doc/project-nrepl.md`](doc/project-nrepl.md)

## References

- [pi-mono](https://github.com/badlogic/pi-mono) — inspiration
- [charm.clj](https://codeberg.org/timokramer/charm.clj) — TUI framework
- [Fulcrologic statecharts](https://github.com/fulcrologic/statecharts)
- [Pathom3](https://pathom3.wsscode.com/)
- [nucleus](https://github.com/michaelwhitford/nucleus)
