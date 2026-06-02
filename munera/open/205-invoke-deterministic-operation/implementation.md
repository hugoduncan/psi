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
