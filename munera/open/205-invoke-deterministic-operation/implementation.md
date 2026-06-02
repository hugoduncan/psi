# Implementation notes — 205

## Architecture-fit review (ψ)

Reviewed design.md for architectural fit against AGENTS.md, META.md, doc/architecture.md.

Verified against live code:
- psi-tool `workflow`/`scheduler` actions are dispatched by a `case` in
  `make-psi-tool` and live in dedicated `psi-tool-<x>` namespaces — the proposed
  `operation` action mirrors this faithfully (no shim). ✓ fit.
- commands are `(ctx session-id) → pure data map`, rendered by adapters;
  `/operations` (exact) + `/operation <id>` (prefixed) fit the existing
  `exact-command-handlers` / `prefixed-command-prefixes` mechanisms. ✓ fit.
- single mechanism: both surfaces route through the existing
  `deterministic-operation-runtime/invoke-operation` +
  `registry/invoke-operation-in` boundary — consistent with `one_way` and the
  "execution at the runtime boundary" pattern already used by workflow invoke
  steps (`step_execution`). ✓ fit.
- `:deterministic-operation-registry` is on session ctx; both psi-tool helpers
  (`{:ctx :session-id}`) and commands (`ctx session-id`) can reach it the same
  way `step_execution` does. ✓ fit.
- listing reuses existing `registry/all-operations-in` — no new boundary. ✓ fit.

Actionable misfit found (1):
- **Listing is a read but bypasses resolvers.** The design's own "Architecture
  alignment" asserts "reads go through resolvers; state changes go through
  dispatch." Operation *listing* is a read of registry state, yet the design
  routes it as a direct `all-operations-in` registry call rather than through
  the EQL/Pathom query surface. doc/architecture.md models the registry as a
  runtime handle whose queryable status is projected into `:state*` and read
  via resolvers. The design should explicitly decide whether listing conforms
  to reads-through-resolvers (resolver/EQL-backed) or is justified as part of
  the runtime-boundary execution path, and record that decision. (invoke is
  genuinely execution-at-boundary and is fine; only listing is in tension.)

## Resolution of architecture-fit follow-up (ψ)

Resolved with option (b): document the direct registry read as the
runtime-boundary path. Decision recorded as locked decision #8 and expanded in
design.md "Architecture alignment".

Verified against live code:
- `DeterministicOperationRegistry` is a `defrecord` wrapping its own internal
  `atom` (registry.clj) — infrastructure machinery owning internal mutable
  lifecycle. By `doc/architecture.md` "State boundary" this is a *runtime
  handle*, explicitly "not queryable domain state". The architecture table
  lists the workflow registry as the same kind of handle.
- It has no `:state*` projection and no resolver/EQL surface today.
- "Reads go through resolvers" governs canonical `:state*` domain data, not
  runtime-handle infrastructure → the flagged tension was a misread of scope.
- The workflow invoke-step path (`step_execution`) already reads this same
  registry directly. Listing via `all-operations-in` shares that single path
  → `one_way`. A resolver/EQL listing surface would create a *second* read
  path over the same handle (violating `one_way`) and would first require
  projecting an infrastructure handle into `:state*` — out of scope.

Conclusion: no resolver work needed; listing-via-`all-operations-in` is the
architecturally aligned choice. design.md updated accordingly.

## Ambiguity review (ψ)

Reviewed design.md for ambiguities; grounded against psi_tool.clj, commands.clj,
step_execution.clj, registry.clj, defs.clj. Five actionable ambiguities:

1. **Per-key truncation unspecified** (decision #7 / AC2). "Per-key truncation
   to bound oversized values" gives no limit, unit (chars/lines/depth/coll
   size), or truncation marker. Both surfaces must render identically but the
   bound is undefined → not implementable as written.
2. **`:session-id` vs `:parent-session-id` semantics** (Scope / Minimum
   concepts). Scope says direct invocation builds `:session-id` (and
   `:parent-session-id` "where available"). The existing workflow path
   (step_execution) passes `:parent-session-id` and NOT `:session-id`. What each
   id means for a direct call, and when `:parent-session-id` is "available", are
   undefined → invocation-map shape ambiguous.
3. **Result-key projection: enumerated vs all keys** (AC2 vs decision #7). AC
   says success projects `:ok → :data/:summary/:details`; decision #7 says render
   *all* top-level result keys. Error results also carry `:reason/:message`. The
   authoritative projection rule is contradictory.
4. **Command arg grammar / args optionality** (decision #3 & #4). `/operation
   <id> {edn-args}`: how `<id>` is split from `{edn-args}`, whether args may be
   omitted (default `{}` like workflow), and the rendering path for malformed
   EDN (only unknown-op error is specified) are all undefined for both the
   command and the psi-tool `args` param.
5. **List op param validation + ordering** (decision #6). Whether `op: list`
   rejects/ignores `operation-id`/`args`, the listing order (sorted-by-id vs
   registry order — relevant given the "deterministic" framing), and empty-list
   rendering are unspecified.

## Resolution of ambiguity follow-ups (ψ)

Resolved all five ambiguity follow-ups by adding locked decisions #9–#12 to
design.md and aligning the acceptance criteria. Grounded against live code:
`registry.clj` (all-operations-in, get-operation-in), `defs.clj` (result
schema: ok→:status/:data/+:summary?/:details?; error→:status/:reason/:message
/+:details?), `core.clj` invoke-operation (invocation "may include" keys →
absent-key tolerant), `step_execution.clj` (workflow path passes
:parent-session-id/:workflow-run-id/:step-id, no :session-id), `psi_tool.clj`
+ `psi_tool_workflow.clj` (parse-edn-string + "must be an EDN map" validation,
op-style actions), `commands.clj` (exact handlers matched before prefixed;
`/operation` prefix matcher requires `=prefix` or `prefix " "`, so `/operations`
exact never collides).

Decisions recorded:
- #9 per-key truncation: pr-str each top-level value, 2000-char bound, marker
  `… (truncated, N chars total)`; unit = chars (not coll size/depth); single
  surface-independent rule.
- #10 direct-invocation map: :operation-id/:args(default {})/:ctx/:session-id;
  :parent-session-id only when invoking session has a known parent on ctx else
  nil; :workflow-run-id/:step-id always nil. Reconciled with workflow path:
  same schema, each entry point fills the meaningful identity subset.
- #7 (clarified) + AC: render ALL top-level result keys — single authoritative
  rule, supersedes any :data/:summary/:details enumeration. (Current AC already
  said "all top-level result keys"; tightened wording, no contradiction left.)
- #11 command arg grammar: split tail once on first whitespace → <id> + edn;
  default {}; blank id → usage; malformed/non-map EDN → clear :type :text error;
  psi-tool `args` identical; /operations exact never collides with /operation.
- #12 op:list: ignore (not reject) operation-id/args; sort by id (deterministic,
  stable, both surfaces); empty registry → explicit empty message / :operations [].

No code touched (design-only follow-ups). No blocked steps.
