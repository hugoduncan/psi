# 137 — reload-code: propagate new mutations to live runtime ctx

## Issue Provenance

- **Issue:** #65 — `reload-code does not propagate new mutations to live runtime ctx`
- **Branch:** `065-reload-code-does-not-propagate-new-mutations`
- **Reproduction:** Static analysis confirms causal chain (see `repro-065.md`).
- **Label:** `fix` (implementation handoff)

## Problem Statement

`reload-code` reloads namespace bytecode but the runtime `ctx` map's `:all-mutations`
field is a frozen snapshot taken at startup. Any mutations added to a reloaded namespace
are never reflected in the live ctx, so subsequent extension EQL invocations (via
`runtime_eql/run-extension-mutation-in!` and related functions) cannot see new mutations.

### Causal chain

1. `context/create-context*` stores `:all-mutations mutations` — a frozen vector captured
   at startup time.
2. Three callers read `(:all-mutations ctx)` to populate a per-request `qctx` for
   extension EQL execution:
   - `psi_tool/refresh-query-runtime!`
   - `tool_plan.clj:50`
   - `runtime_eql.clj:50,74`
3. After `reload-code`, namespace bytecode is fresh, but `:all-mutations` in ctx still
   holds the old vector. New mutations from the reloaded namespace are invisible.
4. The `:mutation-registration-refresh` step in `execute-psi-tool-reload-report` is a
   hardcoded no-op `{:status :ok}` — nothing is actually re-registered.
5. `refresh-query-runtime!` also creates a throwaway isolated `qctx` that is discarded
   immediately — any registrations into it have no effect on the live runtime.

## Goal

After a successful `reload-code`, the live ctx must reflect the post-reload mutation set
so that extension EQL queries and tool-plan execution see new mutations without restarting.

## Constraints

- **No behaviour change for the psi-tool query path** (uses separate `query-fn` closure).
- **Backward-compatible**: existing ctx maps without `:all-mutations-atom` must still work.
- **Minimal surface change**: keep the existing `(:all-mutations ctx)` key for callers
  outside the agent-session component; migration is internal.
- The fix must not introduce circular namespace dependencies inside `agent-session`.

## Approach

### A — Atom-wrapped all-mutations in ctx

1. **`context.clj`**: Add `:all-mutations-atom (atom mutations)` alongside the existing
   `:all-mutations` entry. Add helper:
   ```clojure
   (defn all-mutations-in [ctx]
     (if-let [a (:all-mutations-atom ctx)]
       @a
       (:all-mutations ctx)))
   ```

2. **All callers** of `(:all-mutations ctx)`:
   - `psi_tool.clj` (line 292 in `refresh-query-runtime!`)
   - `tool_plan.clj` (line 50)
   - `runtime_eql.clj` (lines 50, 74)
   → replace with `(context/all-mutations-in ctx)`

3. **`psi_tool.clj` — `execute-psi-tool-reload-report`**: After the namespace reload loop
   succeeds, re-derive the current mutation set by resolving the live var
   `psi.agent-session.mutations/all-mutations` and reset the atom:
   ```clojure
   (when-let [a (:all-mutations-atom ctx)]
     (when-let [v (resolve 'psi.agent-session.mutations/all-mutations)]
       (reset! a @v)))
   ```
   Emit this as the real `:mutation-registration-refresh` step (replace the no-op).

4. **`psi_tool.clj` — `refresh-query-runtime!`**: Remove the throwaway isolated qctx
   creation — it has no effect on the live runtime. Replace with a check/report only,
   or remove the function and inline the step report.

### Mutation set derivation after reload

- Use `(resolve 'psi.agent-session.mutations/all-mutations)` — safe: returns `nil` if
  the var doesn't exist rather than throwing.
- Only resolves if the user reloaded `psi.agent-session.mutations` (the aggregator) in
  addition to sub-namespaces. If they didn't, `:all-mutations-atom` remains as-is (no
  regression; no worse than today).
- The common correct workflow is to reload the sub-namespace then the aggregator; this
  covers that case fully.

## Acceptance Criteria

1. After `reload-code` that includes `psi.agent-session.mutations` (and a sub-namespace
   with a new `defmutation`), `(context/all-mutations-in ctx)` returns a vector that
   includes the new mutation.
2. A new test in `workflow_reload_runtime_test.clj` (or a companion test namespace)
   verifies that after reload the mutation count in `all-mutations-in` equals or exceeds
   the pre-reload count, and includes the newly added mutation.
3. All existing reload tests continue to pass.
4. `clj-kondo --lint src` emits no new warnings.
