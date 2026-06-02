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

## Inconsistency review (ψ)

Reviewed design.md for internal inconsistency and design-vs-artifact
inconsistency; grounded against step_execution.clj, core.clj
(invoke-operation), registry.clj (invoke-operation-in), defs.clj (result
schemas). Two actionable inconsistencies:

1. **`:operation-id` placed in the invocation map vs. positional arg in the
   referenced mechanism.** Scope, decision #10, and AC all state the
   *direct-invocation invocation map* carries `:operation-id`. But the
   referenced boundary `registry/invoke-operation-in` takes `operation-id` as a
   separate positional arg, and `runtime/invoke-operation` injects it via
   `(assoc invocation :operation-id (:id operation))`. The existing workflow
   path (step_execution.clj) does NOT put `:operation-id` in its invocation map.
   So decision #10's reconciliation claim — "the two entry points populate the
   same invocation-map schema with the subset of identity keys" — is false for
   `:operation-id`: the workflow entry point populates it nowhere, relying on
   injection. Including `:operation-id` in the caller-built map is redundant
   (overwritten by assoc) and inconsistent with the established mechanism.

2. **Decision #7 mis-phrases the `:ok` result schema.** Decision #7 says "`:ok`
   results carry `:status :data` plus optional `:summary`/`:details`". The
   actual `operation-success-result-schema` (defs.clj) has `:status` and `:data`
   as two separate required keys (`[:status [:= :ok]]`, `[:data :any]`). "`:status
   :data`" reads as one key/value pair, contradicting the referenced schema and
   muddying the authoritative projection rule it is meant to ground.

## Resolution of inconsistency follow-ups (ψ)

Resolved both inconsistency follow-ups (design-only). Re-grounded against live
code:
- `step_execution.clj`: workflow path calls
  `(invoke-operation-in registry (:operation invoke-spec) {:ctx :parent-session-id
  :workflow-run-id :step-id :args} invoke-operation)` — `operation-id` passed
  **positionally**, NOT a map key.
- `registry.clj` `invoke-operation-in [reg operation-id invocation invoke-operation]`
  — `operation-id` is a positional param.
- `core.clj` `invoke-operation`: `((:handler operation) (assoc invocation
  :operation-id (:id operation)))` — injects `:operation-id` into the map.
- `defs.clj` `operation-success-result-schema`: `[:status [:= :ok]]` and
  `[:data :any]` are two separate required keys.

Changes:
- #1 (`:operation-id`): dropped `:operation-id` from the caller-built
  direct-invocation map across Scope, decision #10, and the AC. Now documents
  passing `operation-id` positionally (mirroring the workflow path) and that
  `runtime/invoke-operation` injects `:operation-id` via `assoc`. Corrected
  decision #10's reconciliation claim — both entry points pass `operation-id`
  positionally; the false "same map carries :operation-id" claim is gone.
- #2 (#7 schema phrasing): reworded "`:status :data`" → "required `:status`
  and `:data` keys" for `:ok`, and "`:status :reason :message`" → "required
  `:status`, `:reason`, and `:message` keys" for `:error`, matching defs.clj.

No code touched (design-only). No blocked steps.

## Plan/steps ambiguity review (ψ)

Reviewed plan.md + steps.md for actionable ambiguities; grounded against
psi_tool.clj (validate-psi-tool-request, make-psi-tool case/catch, tool schema
:op enum), psi_tool_workflow.clj (parse-workflow-input-string), commands.clj
(dispatch*, prefixed-command, exact handlers), registry.clj/core.clj
(invoke-operation swallows Throwable → only :missing-deterministic-operation
and :malformed-operation-result propagate). Five actionable ambiguities:

1. **`args` parse location + outer-catch arm.** Steps put a try/catch *inside*
   `execute-psi-tool-operation-report` and say parse `args` "where consumed",
   but the *outer* `make-psi-tool` exception handler (psi_tool.clj `case action`
   ~L766) has no `"operation"` arm → falls to generic `format-psi-tool-error`.
   Whether `args` EDN parse/validate runs in `validate-psi-tool-request`
   (outer-try; needs an outer-catch `"operation"` arm) or inside the helper
   (covered by its try/catch) is undecided. Two materially different wirings;
   pick one and, if validation-side, add the matching outer-catch arm.

2. **Command text layout of `project-result`.** `project-result` yields a
   `{k truncated-string}` map; the command step says render it "as `:type
   :text`" but the actual text layout (one `key value` line per entry? key
   order? how `:status` shows) is unspecified. AC only says "renders its
   result." Define the line format (and ordering) so command output is
   deterministic and testable.

3. **Surface catch predicate for propagated ex-infos.** Runtime
   `invoke-operation` canonicalizes arbitrary throwables to `:error` results;
   only `:missing-deterministic-operation` and `:malformed-operation-result`
   propagate. Steps say "catch :missing-deterministic-operation/malformed" but
   not whether to dispatch on `(:type (ex-data e))` vs a blanket catch, nor how
   to render the malformed case distinctly from missing. Specify the catch
   predicate and the distinct rendering for each propagated type.

4. **Tool schema `:op` enum extension.** Steps add `"operation"` to the
   `:action` enum but are silent on the `:op` enum, which currently lists only
   project-repl ops (`status start attach stop eval interrupt`); workflow and
   scheduler `op` values are *not* enumerated there. Convention is therefore
   unclear: extend `:op` with `list`/`invoke` or follow the existing
   non-enumerated pattern. Decide explicitly (the plan's own "schema drift"
   risk motivates this).

5. **`args` handling when `op: list`.** Decision #12 says `args` is ignored for
   `list`, but steps place `args` EDN parse/validate in the shared validation
   path. It is unspecified whether a malformed `args` string is still rejected
   when `op` is `list` (i.e. is parse skipped for `list`?). As written, a `list`
   call with bad `args` could error, contradicting "ignored". State whether
   `args` is parsed at all for `list`.

No code touched (plan/steps-review only).

## Resolution of plan/steps-review follow-ups (ψ)

Resolved all five plan/steps-review follow-ups by recording decisions D1–D5 in
plan.md ("Plan/steps-review resolutions") and wiring them into the slice-2/3
steps + tests. Grounded against live code:
- `psi_tool.clj`: tool schema `:op` `:enum` lists only project-repl ops
  (status/start/attach/stop/eval/interrupt); `workflow`/`scheduler` `op` values
  are NOT enumerated — they validate in `validate-psi-tool-request`. The outer
  `make-psi-tool` exception `case action` (~L766) has per-action arms
  (`workflow`, `scheduler`, …) rendering structured `:psi-tool/...` errors;
  unmatched actions fall to `format-psi-tool-error`.
- `psi_tool_workflow.clj`: `parse-workflow-input-string` validates the EDN map
  inside the report fn (throws `:phase :validate` ex-info); the outer-catch
  `"workflow"` arm is the structured backstop.
- runtime `invoke-operation`: canonicalizes arbitrary throwables to `:error`;
  only `:missing-deterministic-operation` and `:malformed-operation-result`
  ex-infos propagate to callers.

Decisions:
- D1 — parse `args` in `validate-psi-tool-request` (outer-try), add an
  `"operation"` arm to the outer exception `case action`.
- D2 — command text: one `"<key> <value>"` line per top-level key, `:status`
  first then keys sorted ascending by `pr-str`.
- D3 — surface catch dispatches on `(:type (ex-data e))` for the two
  propagating types, renders each distinctly, re-throws others (not blanket).
- D4 — do not extend the `:op` schema enum; `op` validated in
  `validate-psi-tool-request` only (matches workflow/scheduler convention).
- D5 — parse `args` only on the `"invoke"` branch; `list` skips it, so a
  malformed `args` string never errors a `list` call.

No code touched (plan/steps-review resolution only — implementation slices not
yet started). No blocked steps.
