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
- `tool-result-event` — `defn` at lines 299–313 (the `:content`/`:details`/
  `:is-error` map entries are its trailing lines 311–313); the canonical
  bus-event payload constructor, intentionally out of scope (see A2).
- `dispatch-tool-result-in` — defined ~line 322; contains the verbose
  `(first (filter #(and (map? %) (or (contains? % :content) ...)) results))`
  predicate.
- The modifiable-key set is `#{:content :details :is-error}`, expressed both in
  the filter predicate and in `wrap-tool-executor`'s `cond->`.

## Scope (decided)

**Direction 1 — remove `wrap-tool-executor` entirely.**

### Scope-decision criterion (A1, resolved)

The direction is selected deterministically by a single rule:

> A function is *internal dead code* (→ remove) iff it has **zero production
> callers** AND it is **not a documented public-API surface**. A test that
> merely asserts pass-through behaviour does not constitute a documented public
> surface, regardless of the test's name.

Applying the rule to `wrap-tool-executor`:

- Production callers: **zero** (only its own `defn` plus test references —
  confirmed by grep over `components/**.clj` excluding `*_test.clj`).
- Public-surface evidence: the test named "wrap-tool-executor remains an
  extension-local compatibility wrapper" (`extensions_test.clj:393`) asserts
  only that the wrapper passes tool-name/args through to `execute-fn`; it
  records no intentional public contract. The test *name* is not evidence.

Both conditions for "internal dead code" hold ⇒ **direction 1 (remove)**. There
is no documented public compatibility surface to preserve, so direction 2 is
not applicable.

### `tool-result-event` is intentionally excluded (A2, resolved)

The "expressed once" contract is the **set of result keys an extension may
modify** (`:content` / `:details` / `:is-error`). It is currently expressed in
two places:

1. the `dispatch-tool-result-in` filter predicate
   (`#(and (map? %) (or (contains? % :content) …))`), and
2. the `wrap-tool-executor` post-result `cond->`.

`tool-result-event` (`extensions.clj`, `defn` at lines 299–313) is a
**different concern**: it is the canonical *bus-event payload constructor* — the
single source of the cross-path payload *shape*, building the `tool_result` event
consumed by the plan path and the `emit-tool-lifecycle!` bridge. Its key
enumeration constructs a payload; it does not declare which keys are
extension-*modifiable*. `tool_runtime_adapter.clj` (lines 37–42) reads
`:content`/`:details`/`:is-error` off the *incoming* `lifecycle-event` and passes
them *as constructor arguments into* `tool-result-event`; it sources the
constructor inputs, it does not consume the constructed payload. Either way it
touches the *payload shape*, not the modifiable-key contract. `tool-result-event`
is therefore **intentionally excluded** from the single-sourcing; this task does
not refactor it.

Once `wrap-tool-executor` is removed, the `cond->` (place 2) disappears, leaving
the modifiable-key contract expressed exactly once — in the
`dispatch-tool-result-in` filter predicate. No shared helper/key-set is required
to satisfy the "expressed once" acceptance criterion; removal alone achieves it.

### Coverage that must remain after removal (A3, resolved)

`wrap-tool-executor` is removed, so its test (`tool-wrapping-test`,
`extensions_test.clj:386–441`) is removed. Before removal, audit which
behaviours that test *uniquely* covers, and ensure each is independently
covered afterward:

- **`:content` / `:is-error` / `:details` coercion and normalization on the
  plan path** — already independently covered by
  `dispatch-tool-result-normalizes-content-test` (`extensions_test.clj:445`) and
  `dispatch-tool-result-coerces-is-error-test` (`extensions_test.clj:459`),
  spanning lines 445–473, which exercise `dispatch-tool-result-in` directly.
  **No migration needed.**
- **tool_call blocking** — `wrap-tool-executor`'s blocking sub-test exercises
  `dispatch-tool-call-in`'s `:block` return *through the wrapper*. The wrapper's
  blocked-result shaping (`{:content reason :is-error true}`) is wrapper-only
  glue and dies with the wrapper; `dispatch-tool-call-in` `:block` detection is
  covered by the plan-path / `dispatch-in` tests. **No migration needed.**
- **non-map filter return-shape** — the "tool_result handler returning non-map
  is silently ignored" sub-test is the *only* assertion exercising the
  `dispatch-tool-result-in` filter predicate's map?/contains? guard at the
  `dispatch-tool-result-in` return boundary. This is **the one behaviour to
  migrate**: add a direct `dispatch-tool-result-in` test asserting a non-map
  handler return yields `nil` (no override), so the surviving predicate keeps
  test coverage.

## Acceptance Criteria

- `wrap-tool-executor` is removed. The modifiable-key contract
  (`:content` / `:details` / `:is-error`) is **single-sourced**: the key set is
  enumerated exactly once in the named `modifiable-tool-result-keys` set, and
  both production sites of the producer/consumer pair derive from it — the
  `dispatch-tool-result-in` *selection* guard (`modifiable-tool-result-override?`)
  and the `tool_plan.clj` override *application* (`merge-tool-result-override`).
  (The code-shaper C1 review found the live application `cond->` in
  `tool_plan.clj` re-enumerated the same three keys, so removal of the dead
  wrapper alone did *not* leave the contract expressed once — single-sourcing
  across the producer/consumer pair is the honest fix.) `tool-result-event` is
  unchanged (intentionally excluded — it constructs the payload, it does not
  declare the modifiable-key contract).
- `tool-wrapping-test` is removed; its uniquely-covered behaviour
  (non-map handler return ⇒ no override from `dispatch-tool-result-in`) is
  migrated to a direct `dispatch-tool-result-in` test. Coercion/normalization
  and `:block` detection need no migration (already independently covered).
- No production caller breaks (there are none today).
- `clj-kondo` clean on changed files.
- Existing extension tests pass (no regression on plan-path tool blocking /
  result override behaviour).

## Out of Scope

- The metrics turn-finished `println` → `timbre` logging idiom fix (tracked in
  task 199).
- Any change to the interactive/batch `emit-tool-lifecycle!` bridge added by
  task 198 (that path is correct and deliberately does not route through
  `wrap-tool-executor`).
- Any change to `tool-result-event` (`extensions.clj`, `defn` at lines 299–313)
  or its caller `tool_runtime_adapter.clj` (lines 37–42, which sources the
  constructor inputs). It constructs the canonical bus-event payload shape, a
  separate concern from the modifiable-key contract being single-sourced here
  (see A2 resolution above).
