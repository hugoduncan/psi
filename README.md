# ψ Psi — A Clojure AI Agent

Psi is an AI coding agent harness built in Clojure. Inspired by
[pi-mono](https://github.com/badlogic/pi-mono).

## The problem

AI coding agents often contain a lot of built-in tools, behaviour and
assumptions, which can be hard to override. They can also be hard to extend.

## The approach

Psi treats the agent as an inspectable, programmable system rather than a fixed
product:

- **Extensions can completely customise the agent** — built-in behaviour is
  deliberately minimal; extensions add tools, prompts, skills, and workflows.
- **Everything is introspectable** — runtime state is read through an EQL graph
  and changed through dispatched mutations, so you can query and drive the agent
  live.
- **Deterministic and replayable** — all changes flow through an event log;
  statecharts enforce valid transitions.
- **AI-provider agnostic** — providers are pluggable.

See [`doc/architecture.md`](doc/architecture.md) for how these fit together.

## AI usage disclaimer

Psi is developed with substantial AI-agent assistance, including its own
agent. Review code and documentation before relying on them.

## Project maturity

Psi is pre-1.0 and under active development. It is usable but not yet stable:
interfaces, configuration, and behaviour may change between releases without a
compatibility layer. Pin a specific release for reproducible environments.

## Quick Start

### Requirements

- **Java 22+** (Java 25 recommended) — the TUI requires the Java FFM API
- **Babashka** — for the launcher

### Installation

Install the latest release:

```bash
bbin install org.hugoduncan/psi --as psi --mvn/version LATEST
```

Install a specific release (recommended for reproducible environments):

```bash
bbin install org.hugoduncan/psi --as psi --mvn/version 0.1.2123
```

Check the installed version:

```bash
psi --version
# psi 0.1.2123
```

Upgrade to the latest release:

```bash
bbin uninstall psi
bbin install org.hugoduncan/psi --as psi --mvn/version LATEST
```

Repo-local / development alternative:

```bash
bbin install . --as psi
```

Releases are tagged `vMAJOR.MINOR.PATCH` on the
[releases page](https://github.com/hugoduncan/psi/releases).
See [CHANGELOG.md](CHANGELOG.md) for what changed in each release.

Each release is also published to [Clojars](https://clojars.org/org.hugoduncan/psi)
as `org.hugoduncan/psi`. For launcher resolution strategy
(`PSI_LAUNCHER_POLICY`) and startup details, see [`doc/cli.md`](doc/cli.md).

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

### Emacs UI usage

For keybindings, rendering behavior, and reconnect semantics, see:
- [`doc/emacs-ui.md`](doc/emacs-ui.md)

Frontend contributors: see
[`doc/emacs-ui-development.md`](doc/emacs-ui-development.md).

### TUI usage

For TUI login flow, in-session commands, and runtime behavior, see:
- [`doc/tui.md`](doc/tui.md)

### Model controls

Interactive sessions support `/speed` for provider throughput-tier selection and
`/effort` for provider reasoning-effort override. Named session profiles bundle
reusable model/thinking/speed/effort settings for interactive selection
(`/session-profile`) and workflow steps (`:session-profile`); see
[`doc/tui.md`](doc/tui.md), [`doc/configuration.md`](doc/configuration.md), and
[`doc/workflows.md`](doc/workflows.md).

## Configuration

Config file locations, precedence (session > project-local > project-shared > user > system), settings
reference, runtime scoped setters, outbound model API proxy environment variables, and custom provider setup:
- [`doc/configuration.md`](doc/configuration.md)
- [`doc/custom-providers.md`](doc/custom-providers.md)

### Built-in Tools

`read` `bash` `edit` `write` `psi-tool`

`psi-tool` is the live runtime introspection/modification tool with canonical action-based requests:
- `query` — EQL graph reads
- `eval` — in-process ψ namespace-scoped Clojure eval
- `mutate` — invoke registered runtime mutations with structured success/error reports
- `reload-code` — explicit namespace/worktree code reload with distinct reload and graph-refresh reporting
- `project-repl` — managed project REPL status/start/attach/stop/eval/interrupt operations with structured reports
- `scheduler` — delayed one-shot work via explicit `create|list|cancel`, including both delayed same-session prompts and delayed fresh top-level session creation
- `operation` — list and invoke registered deterministic operations via explicit `list|invoke`
- `workflow` — inspect and manage workflow runs, including `cancel-run` to stop in-flight delegated workflows; see [`doc/workflows.md`](doc/workflows.md) for cancellation/removal details

### Project nREPL

For direct project-local REPL support distinct from psi's own runtime nREPL, see:
- [`doc/project-nrepl.md`](doc/project-nrepl.md)

### Workflows

For user-facing workflow usage, workflow file location, `/delegate`, reload
behavior, and workflow-run retention/cleanup behavior, see:
- [`doc/workflows.md`](doc/workflows.md)

Project workflows include:

- `/delegate task-lifecycle <task>` runs a Munera task through design → plan →
  implement → review → extract knowledge. Unresolved `SCOPE_QUESTION:` items halt
  the lifecycle before plan creation and hand back to the human; see
  [`doc/workflows.md`](doc/workflows.md).
- `/delegate reduce-incidental-complexity` for function/executable-unit
  incidental complexity and `/delegate reduce-architectural-complexity` for
  namespace/family/pair/community architecture targets selected by Gordian.

Completed workflow runs are retained per originating session and older runs are
cleaned up automatically; retention is configurable. See
[`doc/workflows.md`](doc/workflows.md).

## Extensions

Extensions customise psi by adding tools, commands, event handlers, and UI.
Built-in extensions that ship with this repo (activated via
`.psi/extensions.edn`):

- **auto-session-name** — derive a session name automatically from early
  conversation context for top-level user-interactive sessions only; delegated
  workflow, workflow-step, nested workflow, and helper sessions are excluded.
- **commit-checks** — run project-local checks after a local commit and feed
  failures back into the session.
- **context-manager** — registers pre-turn turn augmenters:
  `project-context` and automatic `entity-resolution` (a bash-only local-model
  helper that injects a `Resolved entities` block); also runs a post-turn
  tooling-friction analyzer (fire-and-forget: auto-creates capped, deduped
  `munera/open/NNN-slug/design.md` tooling/dependency-friction tasks in the
  analyzed session's worktree, excluding known helper/infra sessions). See
  [`doc/extensions.md`](doc/extensions.md).
- **dev-http** — dev-time localhost HTTP side channel (`/dev-http`,
  `dev-present`) for presenting markdown/tables/Vega/Mermaid/files/hiccup and
  choice prompts in a browser, with choices flowing back as user input. See
  [`doc/dev-http.md`](doc/dev-http.md).
- **edit-clj** — structural Clojure/EDN editing tool that replaces whole forms
  by structural equality (`edit-clj`).
- **mementum** — git-based memory protocol: memories, knowledge, and
  working-memory `state.md`.
- **metrics** — accumulate persistent per-capability usage counters (`/metrics`).
- **munera** — git-native Markdown task protocol (design → plan → implement →
  review) under `munera/`.
- **ramora** — prompt-contribution extension that injects the Ramora protocol
  (lambda-form project knowledge organization) into the system prompt.

For the extension list, configuration, and authoring details, see:
- [`doc/extensions.md`](doc/extensions.md)

## Developer documentation

The sections below cover extending psi, runtime introspection, and internals.

### Extension API

For extension-facing runtime/query details (including memory durability operations and
mid-conversation system-message injection), see:
- [`doc/extension-api.md`](doc/extension-api.md)

This includes the preferred workflow public-data display convention for
workflow-backed extensions.

### Extension install manifests

For the deps-shaped `extensions.edn` install model, launcher-owned startup basis construction,
concise psi-owned manifest syntax, apply semantics, and introspection fields, see:
- [`doc/extensions-install.md`](doc/extensions-install.md)

For built-in extension docs (`extensions/` per-project local roots), see:
- [`doc/extensions.md`](doc/extensions.md)

Project-local extension/config examples in this repo include:
- [`.psi/extensions.edn`](.psi/extensions.edn)
- [`.psi/commit-checks.edn`](.psi/commit-checks.edn)
- `bb commit-check:rama-cc`
- `bb commit-check:file-lengths` — scans `components/`, `bases/`, and `extensions/` `src/`/`test/` paths; legacy oversized extension files are ratcheted to fail if they grow
- `bb commit-check:dispatch-architecture`

### Architecture

For architecture overview, components, EQL introspection guidance, and
roadmap, see:
- [`doc/architecture.md`](doc/architecture.md)

### Graph discovery

For the session-root graph discovery surface (`:psi.graph/*`), canonical
discovery workflow, and graph semantics, see:
- [`doc/graph-surface.md`](doc/graph-surface.md)

For prompt lifecycle introspection summaries and normalized prompt-turn
attrs, see:
- [`doc/architecture.md`](doc/architecture.md)

### ψ Psi project config

Project query/config tool details, for query/mutate/reload examples and
worktree-authoritative reload targeting rules, including the recommended
self-reload loop:
- [`doc/psi-project-config.md`](doc/psi-project-config.md)

### Scheduler

For scheduler kinds, session-config support, status semantics, and
introspection attrs:
- [`doc/scheduler.md`](doc/scheduler.md)

### Determinisitc Operations

for the deterministic-operation `list`/`invoke` request shapes, params,
all-key + 2000-char truncation rendering, and error surfacing (both the
psi-tool action and the `/operations` / `/operation` commands)
- [`doc/operations.md`](doc/operations.md)


## References

- [pi-mono](https://github.com/badlogic/pi-mono) — inspiration
- [charm.clj](https://codeberg.org/timokramer/charm.clj) — TUI framework
- [Fulcrologic statecharts](https://github.com/fulcrologic/statecharts)
- [Pathom3](https://pathom3.wsscode.com/)
- [nucleus](https://github.com/michaelwhitford/nucleus)
