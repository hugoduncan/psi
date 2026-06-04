# Project nREPL

Project nREPL support lets psi connect to or launch an nREPL for the current
project worktree, distinct from psi's own runtime nREPL.

Project nREPL resolution is session-scoped by worktree path. When a command or
mutation targets project nREPL, psi resolves the target worktree from explicit
input first and otherwise from the invoking session worktree-path.

This keeps project REPL ownership aligned with the target project rather than
adapter focus inference as the primary semantic input.

## Acquisition modes

Psi supports two acquisition modes.

### Started mode

Psi launches a configured start command vector in the target worktree.

Configuration lives in psi config files under `:agent-session :project-nrepl`:

- user: `~/.psi/agent/config.edn`
- project shared: `<worktree>/.psi/project.edn`
- project local: `<worktree>/.psi/project.local.edn`

Canonical shape:

```clojure
{:agent-session
 {:project-nrepl
  {:start-command ["bb" "nrepl-server"]
   :start-readiness-timeout-ms 120000}}}
```

If both project files exist, psi deep-merges shared then local, so local values
win.

Rules:

- `:start-command` must be a non-empty vector of strings
- first element is the command path/name
- remaining elements are args in order
- psi starts the process in the effective target worktree
- psi discovers the endpoint from `<worktree>/.nrepl-port`
- the older nested `:started :command-vector` shape is no longer the canonical or supported config surface

#### `:start-readiness-timeout-ms`

How long psi waits for the launched process to write a fresh
`<worktree>/.nrepl-port` before reporting a readiness failure.

- optional integer milliseconds; default `120000` (120 s) so a cold
  `clojure -M …` JVM + classpath build + nREPL/cider middleware load succeeds
  out of the box
- must be in the range `[1000 600000]` (1 s–10 min); out-of-range or
  non-integer values are rejected at config validation
- follows the same `system < user < project` precedence as the rest of the
  project nREPL config
- the effective resolved timeout is observable as the instance status field
  `:readiness-timeout-ms` (via `/project-repl status`), even on a readiness
  failure

Started mode also guards against a **stale `.nrepl-port`**: psi deletes any
pre-existing `<worktree>/.nrepl-port` immediately before launching and only
accepts a port file written at or after the launch instant, so start connects
to the process it launched rather than latching onto a leftover port. A timeout
reached while only a too-old port is present is reported distinctly
(`:phase :started-stale-port`) from a plain readiness timeout
(`:phase :started-readiness`).

### Attach mode

Psi connects to an already-running nREPL endpoint.

Config shape:

```clojure
{:agent-session
 {:project-nrepl
  {:attach {:host "localhost"
            :port 7888}}}}
```

You may omit `:host` to use the default host behavior, and you may omit `:port`
to let psi fall back to `<worktree>/.nrepl-port` discovery.

## Precedence

Project nREPL config follows the same general config precedence as other
project-scoped settings:

```text
session runtime targeting
> <worktree>/.psi/project.local.edn
> <worktree>/.psi/project.edn
> ~/.psi/agent/config.edn
> system defaults
```

## Commands

- `/project-repl` — show status for the current worktree
- `/project-repl start` — start configured project nREPL
- `/project-repl attach` — attach to configured/discovered project nREPL
- `/project-repl stop` — stop the managed project nREPL instance
- `/project-repl interrupt` — interrupt active eval if available
- `/project-repl eval <code>` — evaluate code in the project nREPL

## Notes

- project-local writable overrides belong in `.psi/project.local.edn`
- shared project defaults belong in `.psi/project.edn`
- malformed shared/local project config files warn and are ignored best-effort
