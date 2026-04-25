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

### Requirements

- **Java 22+** (Java 25 recommended) — the TUI requires the Java FFM API
- **Babashka** — for the launcher

### Installation

Install the latest release:

```bash
bbin install io.github.hugoduncan/psi --as psi
```

Install a specific release by tag (recommended for reproducible environments):

```bash
bbin install io.github.hugoduncan/psi --as psi --git/tag v0.1.1987
```

Check the installed version:

```bash
psi --version
# psi 0.1.1987
```

Upgrade to the latest release:

```bash
bbin uninstall psi
bbin install io.github.hugoduncan/psi --as psi
```

Repo-local / development alternative:

```bash
bbin install . --as psi
```

Releases are tagged `vMAJOR.MINOR.PATCH` on the
[releases page](https://github.com/hugoduncan/psi/releases).
See [CHANGELOG.md](CHANGELOG.md) for what changed in each release.

Each release is also published to [Clojars](https://clojars.org/io.github.hugoduncan/psi)
as `io.github.hugoduncan/psi`. The launcher auto-detects released versions and
resolves psi from the Maven cache instead of re-fetching from git — no action
required. Force a specific resolution strategy with `PSI_LAUNCHER_POLICY`
(`jar` | `installed` | `development`); see [`doc/cli.md`](doc/cli.md).

Then run psi directly:

```bash
# Bare console
psi

# Terminal UI
psi --tui

# RPC mode
psi --rpc-edn
```

For CLI flags, launcher-only flags, environment variables, and switch behavior, see:
- [`doc/cli.md`](doc/cli.md)

### Migration note

Old alias-based startup is now non-canonical:

```bash
clojure -M:psi           -> psi
clojure -M:psi --tui     -> psi --tui
clojure -M:psi --rpc-edn -> psi --rpc-edn
```

Development contributors may still use repo-local invocation paths during transition,
and may set `PSI_LAUNCHER_POLICY=development` when they want launcher basis construction to use repo-local roots. The launcher-owned `psi` command remains the primary operator-facing startup surface.

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

For the deps-shaped `extensions.edn` install model, launcher-owned startup basis construction,
concise psi-owned manifest syntax, apply semantics, and introspection fields, see:
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
