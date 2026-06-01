# 200 Remove dead wrap-tool-executor and de-duplicate tool-result filter predicate

## Intent

Two related, pre-existing code-shape issues in
`psi.agent-session.extensions` were surfaced (and deferred as out-of-scope) by
the task-198 implementation review:

1. `wrap-tool-executor` is **dead code in production** — it has no production
   callers, only test references. It defines a tool-executor wrapping path that
   duplicates the post-result modification `cond->` shape.
2. `dispatch-tool-result-in` contains a verbose inline result-filter predicate
   (`#(and (map? %) (or (contains? % :content) ...))`) that mirrors the
   modification-key contract also expressed in `wrap-tool-executor`'s `cond->`.
   The "which result keys are extension-modifiable" contract is expressed in
   two places.

Resolving these reduces duplication and removes a dead maintenance surface.

## Context

- File: `components/agent-session/src/psi/agent_session/extensions.clj`
- `wrap-tool-executor` — defined ~line 335; production callers: none. Test
  references: `components/agent-session/test/psi/agent_session/extensions_test.clj`
  (~lines 386–441, multiple `testing` blocks).
- `dispatch-tool-result-in` — defined ~line 322; contains the verbose
  `(first (filter #(and (map? %) (or (contains? % :content) ...)) results))`
  predicate.
- The modifiable-key set is `#{:content :details :is-error}`, expressed both in
  the filter predicate and in `wrap-tool-executor`'s `cond->`.

## Scope (to be decided during planning)

Candidate directions — the plan must choose and justify one:

1. **Remove `wrap-tool-executor` entirely** (it is dead), and delete or migrate
   its test coverage. Then simplify `dispatch-tool-result-in`'s predicate if the
   duplication driver (the mirrored `cond->`) is gone.
2. **Keep `wrap-tool-executor`** but single-source the modifiable-key contract
   (a shared predicate / key-set / helper) consumed by both the
   `dispatch-tool-result-in` filter and the `wrap-tool-executor` `cond->`.

Decision input: confirm at planning time whether `wrap-tool-executor` is an
intentional public compatibility surface (a test asserts it "remains an
extension-local compatibility wrapper") or genuinely removable. If it is a
documented public surface, prefer direction 2; if it is internal dead code,
prefer direction 1.

## Acceptance Criteria

- The "which tool-result keys are extension-modifiable" contract
  (`:content` / `:details` / `:is-error`) is expressed once, not duplicated
  across `dispatch-tool-result-in` and `wrap-tool-executor`.
- If `wrap-tool-executor` is removed, its tests are removed/migrated and no
  production caller breaks (there are none today).
- If `wrap-tool-executor` is retained, the duplication is eliminated via a shared
  helper/key-set and its compatibility-wrapper tests still pass.
- `clj-kondo` clean on changed files.
- Existing extension tests pass (no regression on plan-path tool blocking /
  result override behaviour).

## Out of Scope

- The metrics turn-finished `println` → `timbre` logging idiom fix (tracked in
  task 199).
- Any change to the interactive/batch `emit-tool-lifecycle!` bridge added by
  task 198 (that path is correct and deliberately does not route through
  `wrap-tool-executor`).
