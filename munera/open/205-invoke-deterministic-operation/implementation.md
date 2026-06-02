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

## Plan/steps inconsistency review (ψ)

Reviewed plan.md + steps.md for internal/cross-file inconsistency; grounded
against psi_tool.clj (validate-psi-tool-request `:as args` binding L105–106,
outer-catch `case action` L765 reads `(get args "op")`, schema `:op` enum L52),
psi_tool_workflow.clj (parse-workflow-input-string), step_execution.clj
(workflow invoke-spec positional operation-id), defs.clj (result schemas).
Two actionable inconsistencies:

1. **`args` destructuring collides with `:as args` request-map binding.** Slice-2
   steps say "add `operation-id`/`args` to destructuring" in
   `validate-psi-tool-request`, but that fn binds the *whole request map* `:as
   args` (used at L106 `(psi-tool-action args)` and by the outer catch L765
   `(get args "op")`). Adding a `:strs` entry `args` (the `"args"` EDN-map-string
   param) shadows/conflicts with the `:as args` binding → breaks
   `psi-tool-action` dispatch. The steps must read the EDN-map param via a
   distinct binding (e.g. `(get args "args")` or destructure under a different
   symbol like `operation-args`), not add `args` to `:strs`.

2. **Slice-1 declares `clojure.edn` + `clojure.string` requires no slice-1
   function uses.** Slice-1's five functions (`list-operations`,
   `build-invocation`, `invoke-operation`, `truncate-value`, `project-result`)
   use only core fns — `truncate-value` is `subs`/`str`/`count`, not
   `clojure.string`; EDN `args` parsing is a slice-2 concern living in
   `validate-psi-tool-request` (D1/D5), not slice-1. Requiring `clojure.edn`/
   `clojure.string` in slice-1 contradicts the same slice's `clj-kondo --lint`
   step (unused-namespace warning). Drop both requires from slice-1; add
   `clojure.edn` only in slice-2 if the parse helper is placed in the shared ns.

No code touched (plan/steps inconsistency review only — implementation slices
not yet started). No blocked steps.

## Resolution of plan/steps inconsistency follow-ups (ψ)

Resolved both inconsistency follow-ups (INC-1, INC-2) by correcting the slice
instructions in steps.md. Implementation slices still not started — these are
fixes to the instructions, not code. Re-grounded against live `psi_tool.clj`:
- `validate-psi-tool-request` (L105) binds the whole request map `:as args`,
  read at L106 `(psi-tool-action args)` and outer-catch L765 `(get args "op")`.
  Adding `args` to its `:strs` would shadow that binding → INC-1 confirmed.
- `telemetry-args` (L235) does NOT bind `:as args`, so adding `operation-id`/
  `args` to its `:strs` is safe; that step left unchanged.

Changes:
- INC-1: slice-2 destructuring step now adds only `operation-id` to `:strs` and
  reads the `"args"` EDN-map param via a distinct binding ((get args "args") or
  a separate symbol), never the bare `args` symbol.
- INC-2: slice-1 require step now omits `clojure.edn`/`clojure.string` (no
  slice-1 fn uses them; would fail slice-1 clj-kondo); `clojure.edn` added in
  slice-2 only if the parse helper lands in the shared ns. Consistent with the
  existing slice-2 parse-helper step (steps.md L91-92).

No code touched (instruction-correction only). No blocked steps.

## Implementation (ψ)

All four slices implemented and tested; design followed without material
deviation. Notable concrete decisions made during implementation:

- **Slice 1** `psi.agent-session.deterministic-operation-action`: `list-operations`,
  `build-invocation`, `invoke-operation`, `truncate-value`, `project-result`.
  `build-invocation` omits `:operation-id`/`:workflow-run-id`/`:step-id`;
  conditionally assocs `:parent-session-id`. Verified the runtime injects
  `:operation-id`. Requires only registry/runtime/session-state (INC-2 honoured).
- **Slice 2** `psi.agent-session.psi-tool-operation` + wiring in `psi_tool.clj`.
  `args` parsed in `validate-psi-tool-request` (D1) via a new
  `parse-operation-args-string`, read through `(get args "args")` to avoid the
  `:as args` collision (INC-1). Added `operation` to the `:action` enum,
  `psi-tool-supported-actions`, the case-action dispatch arm, the outer-catch
  arm (D1), `telemetry-args`, `truncation-visible-prefix`, and schema
  `operation-id`/`args` properties. Did NOT extend the `:op` enum (D4).
  - **Deviation (minor):** the report fn's own try/catch renders not only the
    two propagating runtime ex-info types (`:missing-deterministic-operation`,
    `:malformed-operation-result`, D3) but also its own `:phase :validate`
    guard (e.g. missing ctx) as a structured operation error; any other
    throwable is re-thrown. This keeps direct calls of the report fn (and the
    no-ctx guard) returning a structured `:overall-status :error` rather than
    leaking. Validate-phase errors raised by `validate-psi-tool-request` are
    still handled by the outer-catch `"operation"` arm as designed.
- **Slice 3** `commands.clj`: `/operations` (exact) + `/operation` (prefixed),
  `format-operations`, `dispatch-operation-command`, `render-operation-result`
  (D2: `:status` first then keys sorted by `pr-str`), `parse-operation-command-args`.
  Precedence confirmed: `/operations` matched as exact before `/operation`
  prefix; the prefix matcher requires `=`/`prefix " "` so no collision.
- **Slice 4** docs: README psi-tool action list, `doc/tui.md` command list +
  new "Deterministic operation commands" section, CHANGELOG `[Unreleased] >
  Added`.

Tests: 42 new tests / 89 assertions across the four test namespaces, all green;
psi-tool-mutate + psi-tool-scheduler suites still green (no regression).
clj-kondo clean on all new/modified files.

## Implementation review (ψ)

Reviewed code/tests/docs against design + architecture (task-implementation-review
skill). Ran focused suites: 34 unit + 8 integration tests green; clj-kondo clean.

Verified:
- **Matches design** — all locked decisions #1–#12 + D1–D5 reflected in code:
  shared `deterministic-operation-action` helper (list/invoke/truncate/project),
  positional `operation-id` (no `:operation-id` caller-map key, runtime injects),
  conditional `:parent-session-id`, 2000-char per-key truncation with exact
  marker, all-top-level-key projection, `op` validated in
  `validate-psi-tool-request` only (¬`:op` enum, D4), `args` parsed on invoke
  only (D5), outer-catch `"operation"` arm (D1), `(:type (ex-data e))` dispatch
  re-throwing others (D3), command `:status`-first sorted layout (D2).
- **Architecture fit** — `operation` action mirrors `workflow`/`scheduler`
  helper-per-ns shape (no shim); both surfaces route the single existing
  registry+runtime boundary (`one_way`); no change to registry/runtime/
  workflow-runtime (git diff --stat empty for those components).
- **Tests** — real registry + real runtime, no mocks; assert state/outputs
  (sinks for side-effects, exact text/keys), cover all AC incl. empty list,
  sorted, default `{}`, blank-id usage, malformed/non-map args, unknown id,
  malformed result distinct from missing, side-effecting op, >2000-char
  truncation identical across surfaces, precedence `/operations` vs `/operation`.
- **Docs/CHANGELOG** — README psi-tool action list, doc/tui.md command section,
  CHANGELOG [Unreleased]>Added — all accurate and user-facing.
- **No leak** — `malformed-operation-result-ex` dissocs `:ctx`; report is
  `sanitize-psi-tool-data`'d at the make-psi-tool call site.

Non-actionable observations (no follow-up):
- `psi_tool_operation.clj` re-declares a private `psi-tool-error-summary`
  identical to the copies already in `psi_tool_workflow.clj` /
  `psi_tool_scheduler.clj`. This follows the *established* per-helper-ns
  convention the design chose to mirror; it is pre-existing duplication across
  the psi-tool helper family, not introduced by this task. A future cross-cutting
  cleanup could hoist a shared `psi-tool-error-summary` for all four helpers, but
  that is out of scope here and changing only this one file would worsen
  inconsistency.
- Report fn additionally renders its own `:phase :validate` guard (missing ctx)
  as a structured error beyond the two propagating runtime ex-info types — already
  documented as an intentional minor deviation; correct and tested
  (`missing-ctx-renders-error`).

Conclusion: implementation is simple, consistent, robust, and complete against
design. No new actionable issues found.

## Test review (ψ)

Applied task-test-review skill: well-formed tests ∧ behaviour coverage of
design ∧ infra-deps injectable/nullable/¬mock/¬stub. Ran the four task suites
focused: 42 tests / 89 assertions, all green. Grounded against
`deterministic_operation_action_test.clj`, `psi_tool_operation_test.clj`,
`psi_tool_operation_integration_test.clj`, `operation_command_test.clj`, plus
runtime `core.clj` (malformed canonicalization) and `session_state/state.clj`
(`get-session-data-in` path `[:agent-session :sessions sid :data]`).

Verified:
- **Well-formed** — focused, deterministic, no flakiness; all green.
- **Infra deps real, not mocked** — all four suites use a real
  `registry/create-registry` + real `runtime/invoke-operation`; unit ctx uses a
  real `:state*` atom whose shape (`{:agent-session {:sessions {sid {:data …}}}}`)
  matches production `session-data-path` exactly; integration/command use a real
  `session/create-context`. No mocks/stubs. Side-effects asserted via real sinks
  (atoms), outputs asserted via exact keys/text — no interaction assertions. ✓.
- **Behaviour coverage** — every acceptance criterion has a covering test:
  list sorted/empty/ignores-args/-id, invoke ok/error/unknown/malformed
  (distinct), all-top-level-key projection, 2000-char truncation + exact marker
  identical across surfaces, positional `operation-id` (caller map lacks
  `:operation-id`/`:workflow-run-id`/`:step-id`), conditional `:parent-session-id`,
  default `{}`, blank-id usage, non-map/unreadable args, side-effecting op,
  D2 `:status`-first sorted layout, `/operations` vs `/operation` precedence,
  end-to-end validate→dispatch→outer-catch. ✓.

Actionable test gap found (1):
- **Optional `:details` result key is never exercised in projection.** Decision
  #7 (the single authoritative projection rule) explicitly names `:details` as a
  top-level key on both `:ok` (optional) and `:error` (optional) results that
  must be projected ("render ALL top-level keys, each `pr-str`'d + per-key
  truncated"). No test in any of the four suites registers an operation whose
  tagged result carries `:details`, so the rendering of a `:details` value
  (a nested **map** — the non-trivial `pr-str` case the rule governs) is
  unverified on both surfaces. `:status`/`:data`/`:reason`/`:message`/`:summary`
  are each covered; only `:details` (the one key whose value is a collection
  rather than a scalar/atom) is uncovered. Add: (a) a `project-result` unit test
  with `{:status :ok :data … :details {…}}` asserting `:details` present and
  `pr-str`'d; (b) a psi-tool `op invoke` test and a `/operation` command test
  on an op returning `:details`, asserting the `:details` line/key appears in
  the rendered output of each surface (closes the all-keys rule for the nested-
  map case across both surfaces).

Non-actionable observations (no follow-up):
- Truncation `N` count: tested directly on a raw string in `truncate-value`
  (`2500`/`3000` "x"/"y"/"z" → exact marker `N`) and composed through
  `project-result`; because `truncate-value` operates on the already-`pr-str`'d
  string, the char-count semantics are precisely proven. Adequate.
- The minor `:phase :validate` (missing-ctx) deviation is covered
  (`missing-ctx-renders-error`).

## Resolution of test-review follow-up TR-1 (ψ)

Closed the one actionable test-review gap: the optional `:details` result key
(nested map — the non-trivial `pr-str` case named by decision #7) was unexercised
in projection across all three surfaces. Added one test per surface, each on an op
returning `{:status :ok :data … :details {:k :v :n 2}}`:

- `deterministic_operation_action_test.clj` →
  `project-result-includes-details-nested-map`: asserts `:details` present in the
  projected map and equal to `(pr-str {:k :v :n 2})`. (1 test / 2 assertions.)
- `psi_tool_operation_test.clj` → `invoke-projects-details-nested-map`: asserts
  `:psi-tool/result` carries `:details` equal to `(pr-str {:k :v :n 2})`,
  `:overall-status :ok`.
- `operation_command_test.clj` → `operation-invoke-renders-details-nested-map`:
  asserts the `:type :text` output includes the `:details (pr-str …)` line.

All three pass (focused runs: unit 1/2, psi-tool+command 2/5 combined, all green).
clj-kondo clean; clj-paren-repair no-ops. No production code changed — projection
already rendered all top-level keys; these tests close the all-keys rule for the
nested-map case on both surfaces. No blocked steps.

The remaining unchecked close-out box (`git mv open/ → closed/` + remove from
plan.md) is the lifecycle's terminal move; the task stays in `open/` under this
worktree handoff and the close move is performed by the orchestrating lifecycle,
not this follow-up pass.

## Test review (ψ, second pass)

Re-applied task-test-review skill (well-formed ∧ behaviour-coverage ∧
infra-deps real/¬mock). Ran the four task suites focused: 45 tests / 96
assertions, all green. Grounded against `deterministic_operation_action.clj`,
`psi_tool_operation.clj`, `commands/operation.clj`, `psi_tool_validate.clj`
and the four test namespaces.

Confirmed (unchanged from first pass): well-formed, deterministic; real
registry + real runtime, no mocks/stubs; side-effects via real sinks; AC
behaviour coverage incl. TR-1 `:details` (now closed across all three surfaces).

Actionable test gap found (1):
- **Command surface (`/operation`) has no per-key truncation test (decision
  #9).** Decision #9 is a *surface-independent* rule whose explicit purpose is
  that the command and psi-tool action "render identically", and the plan's own
  "Truncation identical across surfaces" risk states: "Test **both surfaces** on
  an over-2000-char value and assert identical truncated value + marker." Today
  truncation is exercised at the helper level
  (`truncate-value-over-limit-marked`, `project-result-truncates-oversized-value`)
  and on the **psi-tool** surface
  (`invoke-over-2000-char-value-truncated-identically`), but
  `operation_command_test.clj` contains **no** over-2000-char case — the
  `render-operation-result` text path (which calls `project-result` →
  `truncate-value` and emits a `"<key> <value>"` line) is never asserted to
  carry the truncation marker. The "identical across surfaces" claim is
  therefore only half-verified; a regression that broke truncation on the
  command path (e.g. a future surface re-implementing rendering) would go
  undetected. This is distinct from TR-1 (which covered `:details` nested-map
  projection, not value truncation). Add a `/operation` command test invoking
  an op whose result value `pr-str`s to >2000 chars, asserting the rendered
  `:type :text` line for that key contains the exact
  `… (truncated, N chars total)` marker and matches
  `(op-action/truncate-value (pr-str value))`, closing the
  decision-#9 surface-parity guarantee on the command surface.

Non-actionable observations (no follow-up):
- `parse-operation-args-string` hardcodes `:op "invoke"` in its ex-info `:phase
  :validate` data; it is only ever called from the `invoke` branch, so the
  constant is correct (not a gap).
- `validate-psi-tool-request` has no isolated unit test, but its operation
  branch behaviour (valid op, blank-id reject, invalid-op reject, non-map args
  reject, default `{}`, list-ignores-args) is fully exercised end-to-end through
  the `psi_tool_operation_integration_test` `make-psi-tool` path. Adequate.

## Resolution of test-review follow-up TR-2 (ψ)

Closed the one actionable second-pass test-review gap: the `/operation` command
surface had no per-key truncation test, leaving decision #9's surface-parity
("render identically") only half-verified (helper + psi-tool covered, command
uncovered).

Added `operation-invoke-over-2000-char-value-truncated-identically` to
`operation_command_test.clj`: registers an op returning a 3000-char `:data`
value, dispatches `/operation big/op`, and asserts the rendered `:type :text`
output contains the line `":data " + (op-action/truncate-value (pr-str big))`,
which carries the exact marker `… (truncated, 3002 chars total)` (`pr-str` adds
the two surrounding quotes → 3002 chars). Required `op-action` in the test ns to
share the helper's canonical truncation, matching the psi-tool surface test
(`invoke-over-2000-char-value-truncated-identically`) — so both surfaces assert
against the same `truncate-value` output, closing the "identical across surfaces"
guarantee on the command path.

Focused run: `operation-command-test` 13 tests / 27 assertions, all green
(was 12/25; +1 test, +2 assertions). clj-kondo clean; clj-paren-repair no-op.
No production code changed — `render-operation-result` already routed values
through `project-result` → `truncate-value`; this test locks the command-surface
behaviour against regression. No blocked steps.

The remaining unchecked close-out box (`git mv open/ → closed/` + remove from
plan.md) is the lifecycle's terminal move, performed by the orchestrating
lifecycle, not this follow-up pass.

## Test review (ψ, third pass)

Re-applied task-test-review skill (well-formed ∧ behaviour-coverage ∧
infra-deps real/¬mock). Ran the four task suites focused: 46 tests / 99
assertions, all green. Grounded against `psi_tool_validate.clj`
(`parse-operation-args-string` → `parse-edn-string`, which does NOT catch read
errors), the integration suite (`psi_tool_operation_integration_test.clj`), and
the command suite (`operation_command_test.clj`).

Confirmed (unchanged from prior passes): well-formed, deterministic; real
registry + real runtime + real `session/create-context`, no mocks/stubs;
side-effects via real sinks; AC behaviour coverage incl. TR-1 `:details` and
TR-2 command-surface truncation (both closed).

Actionable test gap found (1):
- **psi-tool `op invoke` covers only the non-map malformed-args branch, not the
  unreadable-EDN branch (decision #11 surface-parity).** Decision #11 states
  the psi-tool `args` param "follows the identical default-`{}` and 'must be an
  EDN map' validation" as the command, and malformed args must surface as a
  clear error "naming the parse problem" (not a crash) on *both* surfaces. The
  **command** surface tests *both* sub-cases — non-map (`[1 2]` → "EDN map") and
  unreadable EDN (`{:x` → "Could not parse") — in `operation-bad-args-error`.
  The **psi-tool** surface (`operation-invoke-malformed-args-validate-error`)
  tests *only* the non-map case (`"[1 2 3]"`). The two sub-cases take **distinct
  code paths**: non-map throws the explicit `:phase :validate` "must be an EDN
  map" ex-info inside `parse-operation-args-string`; unreadable EDN throws a raw
  `RuntimeException` ("EOF while reading", `:phase :operation`, `:data nil`)
  directly out of `parse-edn-string` (which has no try/catch). Verified live: an
  `op invoke` with `args "{:x"` yields `:is-error true` + a structured
  `:psi-tool/action :operation … :overall-status :error` report (surfaced via
  the outer-catch `"operation"` arm) — correct behaviour, but **unverified by
  any test**. The slice-2 steps.md checklist item "malformed args (non-map /
  unreadable EDN) → validate error, not crash" is therefore checked but only
  half-covered on the psi-tool surface. This is distinct from TR-1 (`:details`
  projection) and TR-2 (command truncation). Add a `psi_tool_operation_integration_test`
  case dispatching `op invoke` with an *unreadable* EDN `args` string (e.g.
  `"{:x"`), asserting `:is-error true`, `:psi-tool/action :operation`, and
  `:psi-tool/overall-status :error` — closing the decision-#11 surface-parity
  guarantee for the parse-failure (as opposed to non-map) sub-case on the
  psi-tool surface.

Non-actionable observations (no follow-up):
- The unreadable-EDN error message (`"EOF while reading"`) is less descriptive
  than the command surface's "Could not parse args as EDN: …" wrapping, but
  decision #11 only requires a clear non-crashing error "naming the parse
  problem"; the raw reader message names the problem and the surface is
  structured. Wording parity is not an AC; not actionable here.
- `parse-operation-args-string`'s ex-info hardcodes `:op "invoke"`; it is only
  invoked on the `invoke` branch, so the constant is correct (matches prior
  pass's note on the command-side analogue).

## Resolution of test-review follow-up TR-3 (ψ)

Closed the one actionable third-pass test-review gap: the psi-tool `op invoke`
surface covered only the non-map malformed-args branch, not the unreadable-EDN
branch (the two take distinct code paths — non-map throws the explicit
`:phase :validate` "must be an EDN map" ex-info inside `parse-operation-args-string`;
unreadable EDN throws a raw `RuntimeException` directly out of `parse-edn-string`,
surfaced via the outer-catch `"operation"` arm). Decision #11 requires both
sub-cases to surface as a clear non-crashing error on *both* surfaces; the
command surface (`operation-bad-args-error`) tested both, the psi-tool surface
tested only non-map.

Added `operation-invoke-unreadable-args-validate-error` to
`psi_tool_operation_integration_test.clj`: dispatches `op invoke` with an
unreadable EDN `args` string (`"{:x"`) through the real `make-psi-tool` execute
path, asserting `:is-error true`, `:psi-tool/action :operation`, and
`:psi-tool/overall-status :error` — mirroring the non-map test
(`operation-invoke-malformed-args-validate-error`) and closing the decision-#11
parse-failure surface-parity guarantee on the psi-tool surface.

Focused run: `psi-tool-operation-integration-test` 9 tests / 22 assertions, all
green (was 8/20; +1 test, +2 assertions). clj-kondo clean; clj-paren-repair
success (no structural change). No production code changed — behaviour was
already correct; this test locks the unreadable-EDN psi-tool path against
regression. No blocked steps.

The remaining unchecked close-out box (`git mv open/ → closed/` + remove from
plan.md) is the lifecycle's terminal move, performed by the orchestrating
lifecycle, not this follow-up pass.
