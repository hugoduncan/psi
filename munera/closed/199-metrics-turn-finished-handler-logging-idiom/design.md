# 199 Replace raw println logging in metrics turn-finished handler with timbre

## Intent

`psi/metrics` extension's `make-turn-finished-handler` swallows exceptions from
token-usage tracking and logs them via a raw `println` with a hand-written
`"DEBUG [psi/metrics]"` prefix, rather than the project-standard `taoensso.timbre`
logging. This is a `consistent(idioms)` drift: the rest of the codebase logs via
timbre with structured levels, and a raw `println` bypasses log-level control,
output routing, and structured context.

This was surfaced (and deferred as out-of-scope) by the task-198
implementation review.

### Which consistency: project-standard, not local-file

`consistent(idioms)` here means **project-standard** consistency (timbre),
**not** local-file consistency with the sibling `println` sites. The nearest
siblings — `extensions/metrics/src/psi/metrics/persistence.clj:47,51` — use raw
`println` with hand-written `"WARN [psi/metrics]"` prefixes. Those sites are an
**excluded counter-example**: they share the same `println` drift this task
exists to correct, and converting them is **out of scope** (see Out of Scope).
The reference idiom is the rest of the codebase, where swallowed, non-fatal
exceptions are logged via `taoensso.timbre` (e.g.
`agent-session/.../extensions/loader.clj` "Failed to load extension",
`agent-session/.../tool_output.clj` "Temp store cleanup failed",
`agent-session/.../runtime.clj:217` "Extension run-fn failed").

## Context

- File: `extensions/metrics/src/psi/metrics/extension.clj`
- Function: `make-turn-finished-handler` (the `catch` branch, ~line 99)
- Current code:
  ```clojure
  (catch Exception e
    (println (str "DEBUG [psi/metrics] skipping token tracking for session "
                  session-id ": " (ex-message e))))
  ```
- Introduced in `2a04436fb` (#94), not by task 198.

## Scope

- Replace the `println` call with a **`timbre/warn`** call. `warn` is the
  project-standard level for swallowed, non-fatal exception paths (see
  `extensions/loader.clj`, `tool_output.clj`, `runtime.clj` references above).
  There is no in-extension timbre precedent to match (the siblings are
  `println`), so the level is **pinned to `warn`** by the project-wide
  swallowed-exception idiom — not inferred from the in-file siblings.
- Add the `taoensso.timbre` require to the `psi.metrics.extension` namespace:
  `[taoensso.timbre :as timbre]`.
- Add `com.taoensso/timbre` as an explicit dependency in
  `extensions/metrics/deps.edn` `:deps`, pinned to `{:mvn/version "6.8.0"}`
  (the version declared by the most recent component, `state-kernel`). The dep
  is currently absent — `:deps` declares only clojure + malli — so the
  extension's standalone `src` load would otherwise lack timbre. The `:test`
  alias resolves timbre transitively today via the `agent-session` local dep
  (statecharts → timbre), but the standalone runtime load requires the explicit
  declaration. After the change, confirm both standalone load and the `:test`
  alias resolve timbre.
- No behavioural change beyond logging mechanism: the exception remains
  swallowed and the handler still returns `nil`.

### Replacement call content contract

Preserve the existing content (session-id + `ex-message`) and pass the
exception `e` as a **structured timbre argument** (timbre's first-argument
Throwable convention), matching `runtime.clj:217`
(`(timbre/warn e "Extension run-fn failed")`) and
`app_runtime.clj:222`. Concretely:

```clojure
(catch Exception e
  (timbre/warn e "skipping token tracking for session" session-id))
```

Rationale: passing `e` structurally retains the stack trace under timbre's
output routing (lost by the old string-interpolated `(ex-message e)`), while
`session-id` remains as a structured trailing arg. The hand-written
`"DEBUG [psi/metrics]"` prefix is dropped — timbre supplies level + namespace
context.

## Acceptance Criteria

- The turn-finished handler logs the skipped-token-tracking condition via
  `timbre/warn` (not `println`), passing the exception `e` as the structured
  first argument followed by `"skipping token tracking for session"` and
  `session-id`.
- The `"DEBUG"`-prefixed raw `println` is removed.
- `com.taoensso/timbre {:mvn/version "6.8.0"}` is declared in
  `extensions/metrics/deps.edn` `:deps`, and `[taoensso.timbre :as timbre]` is
  required in `psi.metrics.extension`.
- The extension loads standalone (src) and under its `:test` alias with timbre
  resolved.
- `clj-kondo` clean on the changed file.
- No change to token-tracking success-path behaviour or to the handler's
  `nil` return contract.

## Out of Scope

- The separate `dispatch-tool-result-in` / `wrap-tool-executor` verbose-predicate
  / dead-code cleanup (tracked in task 200).
- Any change to what conditions are caught or whether the exception is swallowed.
- Converting the sibling `println "WARN [psi/metrics]"` sites in
  `extensions/metrics/src/psi/metrics/persistence.clj:47,51`. They share the same
  drift but are deliberately not in scope for this task.
