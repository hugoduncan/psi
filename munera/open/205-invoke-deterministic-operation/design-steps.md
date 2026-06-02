# Design follow-up steps — 205

## Architecture-fit follow-ups

- [x] Resolve the listing read-path tension: operation *listing* is a read but
  the design routes it as a direct `registry/all-operations-in` call rather than
  through resolvers/EQL, contradicting the design's own "reads go through
  resolvers" alignment claim and the runtime-handle read model in
  doc/architecture.md. Either (a) surface listing via a resolver/EQL attribute
  consumed by both surfaces, or (b) document an explicit decision justifying the
  direct registry read as part of the runtime-boundary path; update design.md
  accordingly. (invoke as execution-at-boundary is fine and needs no change.)

## Ambiguity follow-ups

- [x] Specify per-key truncation (decision #7 / AC2): define the bound (e.g.
  max chars or coll size per top-level value), the unit, and the truncation
  marker, so command and psi-tool surfaces render identically and the rule is
  implementable.
- [x] Define the direct-invocation invocation-map id semantics: state what
  `:session-id` and `:parent-session-id` mean for a direct call, when
  `:parent-session-id` is "available" (vs nil), and reconcile with the existing
  workflow path that passes only `:parent-session-id` (step_execution).
- [x] Reconcile the result-key projection contradiction: AC2 enumerates
  `:data/:summary/:details` but decision #7 says render all top-level keys
  (error results add `:reason/:message`). State the single authoritative rule.
- [x] Specify the command arg grammar for `/operation <id> {edn-args}`: how
  `<id>` is separated from `{edn-args}`, whether args are optional (default
  `{}`), and the rendering path for malformed-EDN args on both the command and
  psi-tool `args` param.
- [x] Specify `op: list` behaviour: whether `operation-id`/`args` are
  rejected or ignored, the listing order (define deterministic ordering, e.g.
  sorted by id), and empty-list rendering.

## Inconsistency follow-ups

- [ ] Reconcile `:operation-id` in the invocation map (Scope / decision #10 /
  AC) with the actual mechanism: `registry/invoke-operation-in` takes
  `operation-id` as a positional arg and `runtime/invoke-operation` injects it
  via `assoc`; the existing workflow path (step_execution.clj) does NOT put
  `:operation-id` in its invocation map. Either drop `:operation-id` from the
  documented direct-invocation map (pass it positionally like the workflow
  path) or correct decision #10's "same invocation-map schema" reconciliation
  claim, which is false for `:operation-id`.
- [ ] Fix decision #7's `:ok` schema phrasing "`:status :data`": the
  success-result schema (defs.clj) has `:status` and `:data` as two separate
  required keys, not a `:status :data` pair. Reword so the authoritative
  projection rule matches the referenced schema.
