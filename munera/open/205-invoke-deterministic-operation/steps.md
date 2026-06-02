# 205 — Steps

## Slice 1 — Shared invocation/listing helper

- [x] Create ns `psi.agent-session.deterministic-operation-action` in
  `components/agent-session/src/psi/agent_session/deterministic_operation_action.clj`,
  requiring `psi.deterministic-operation-registry.registry :as registry`,
  `psi.deterministic-operation-runtime.core :as runtime`, and
  `psi.session-state.state :as session-state`. (INC-2: do **not** require
  `clojure.edn` or `clojure.string` here — no slice-1 fn uses them
  (`truncate-value` uses core `subs`/`str`/`count`; `args` EDN parsing is a
  slice-2 concern in `validate-psi-tool-request` per D1/D5), and unused
  requires fail the slice-1 `clj-kondo --lint` step. Add `clojure.edn` only in
  slice-2 if the EDN parse helper is placed in this shared ns.)
- [x] Implement `list-operations [ctx]`: read
  `(registry/all-operations-in (:deterministic-operation-registry ctx))`,
  map each to `{:id (:id op) :description (:description op)}`, sort ascending by
  `:id` (string compare); return a vector (empty `[]` for empty registry).
- [x] Implement `build-invocation [ctx session-id args]`: return
  `{:args (or args {}) :ctx ctx :session-id session-id}`, conditionally
  `assoc :parent-session-id` only when
  `(:parent-session-id (session-state/get-session-data-in ctx session-id))`
  is non-nil. Do **not** include `:operation-id`, `:workflow-run-id`, `:step-id`.
- [x] Implement `invoke-operation [ctx session-id operation-id args]`: build the
  invocation map, then call
  `(registry/invoke-operation-in (:deterministic-operation-registry ctx)
     operation-id invocation runtime/invoke-operation)` passing `operation-id`
  positionally; return the tagged result. Let `:missing-deterministic-operation`
  / malformed ex-info propagate.
- [x] Implement `truncate-value [s]`: `pr-str`-derived string `s`; when
  `(> (count s) 2000)`, return
  `(str (subs s 0 2000) " … (truncated, " (count s) " chars total)")`; else `s`.
- [x] Implement `project-result [result]`: for each top-level key, map value →
  `(truncate-value (pr-str v))`; return a map of `{k truncated-string}`
  preserving all keys present (data-level, surface-independent).
- [x] Write tests
  `components/agent-session/test/psi/agent_session/deterministic_operation_action_test.clj`
  using a real registry (`registry/create-registry`) + real runtime:
  - [x] `list-operations` returns id+description sorted by id; empty registry → `[]`.
  - [x] register a fake op; `invoke-operation` returns its `:ok` tagged result.
  - [x] handler receives `:operation-id` injected by runtime; caller map has no
    `:operation-id`, no `:workflow-run-id`, no `:step-id`.
  - [x] `:parent-session-id` present only when session-data has a parent.
  - [x] error op → `:error` tagged result passes through.
  - [x] unknown id → `:missing-deterministic-operation` ex-info thrown.
  - [x] `truncate-value` on >2000-char string truncates with exact marker + N.
  - [x] `project-result` includes all top-level keys, each `pr-str`'d+truncated.
- [x] Run slice-1 tests; `clj-paren-repair` the new files; `clj-kondo --lint`.
- [x] Commit: `⚒ 205: shared deterministic-operation invocation/listing helper`.

## Slice 2 — psi-tool `operation` action

- [x] Create ns `psi.agent-session.psi-tool-operation` in
  `components/agent-session/src/psi/agent_session/psi_tool_operation.clj`,
  requiring the shared helper. Implement
  `execute-psi-tool-operation-report [{:keys [ctx session-id]} {:keys [op operation-id args]}]`:
  - [x] guard `ctx` present (mirror `psi-tool-workflow`).
  - [x] `op "list"` → call `list-operations`, build
    `{:psi-tool/action :operation :psi-tool/operation-op :list
      :psi-tool/overall-status :ok :psi-tool/operations [...]}` (empty `[]` ok).
  - [x] `op "invoke"` → call shared `invoke-operation`, project result via
    `project-result`, build `{:psi-tool/action :operation
      :psi-tool/operation-op :invoke :psi-tool/overall-status (:status result)
      :psi-tool/result <projected>}`.
  - [x] wrap in try/catch dispatching on `(:type (ex-data e))` (D3, not a
    blanket catch): `:missing-deterministic-operation` and
    `:malformed-operation-result` each render `:psi-tool/overall-status :error`
    + a distinct `:psi-tool/error` summary; any other throwable is re-thrown
    (runtime already canonicalizes non-propagating throwables to `:error`).
  - [x] add `:psi-tool/duration-ms`.
- [x] In `psi_tool.clj`: add `"operation"` to `psi-tool-supported-actions`.
- [x] In `psi_tool.clj` tool schema: add `"operation"` to
  `:properties :action :enum`; add `:operation-id` property; ensure `:args`
  property exists (add if absent) with EDN-map-string description; extend the
  `:description` text listing the new action.
- [x] In `validate-psi-tool-request`: add `operation-id` to the `:strs`
  destructuring (INC-1: do **not** add `args` to `:strs` — the fn already binds
  the whole request map `:as args`, used by `(psi-tool-action args)` and the
  outer-catch `(get args "op")`; read the `"args"` EDN-map-string param via a
  distinct binding, e.g. `(get args "args")` or a separate `:strs` symbol such
  as `operation-args`, never the bare `args` symbol).
  Add `(= effective-action "operation")` cond branch — require
  `op ∈ #{"list" "invoke"}`; for `"invoke"` require non-blank `operation-id`
  AND parse+validate the `"args"` param (read via the distinct binding) as an
  EDN map (default `{}`, "must be an EDN map" error); for `"list"` **skip** the
  `"args"` parse and do not require `operation-id` (D5 — `args` ignored for
  list). Return `{:action "operation" :op op :operation-id operation-id
  :args parsed-args}`. (D4: do **not** add `list`/`invoke` to the schema `:op`
  `:enum` — `op` is validated here only, matching the workflow/scheduler
  convention.)
- [x] Add EDN-map parse+validate for `args` (default `{}`, "must be an EDN map"
  error) — reuse shared parse helper (place in shared helper ns or mirror
  `parse-workflow-input-string`); call it on the `"invoke"` branch only (D1:
  parse in `validate-psi-tool-request`, outer-try path).
- [x] (D1) Add an `"operation"` arm to `make-psi-tool`'s **outer** exception
  `case action` (~L766) rendering `{:psi-tool/action :operation
  :psi-tool/operation-op (some-> (get args "op") keyword) :psi-tool/duration-ms
  0 :psi-tool/overall-status :error :psi-tool/error (psi-tool-error-summary
  :operation e)}` so validate-phase errors (e.g. malformed `args`) render as a
  structured operation error, not the generic fallback.
- [x] In `make-psi-tool` `case action`: add `"operation"` arm calling
  `execute-psi-tool-operation-report`, `sanitize-psi-tool-data`, `pr-str`,
  `serialize-operation-output`, set `:is-error` on non-`:ok` overall-status.
- [x] Add `operation-id`/`args` to `telemetry-args`.
- [x] Write tests
  `components/agent-session/test/psi/agent_session/psi_tool_operation_test.clj`:
  - [x] `op list` returns sorted operations; empty registry → `:operations []`.
  - [x] `op list` ignores `operation-id`/`args`.
  - [x] (D5) `op list` with malformed `args` string → still lists, not error.
  - [x] (D3) `op invoke` malformed-result op → `:malformed-operation-result`
    rendered distinctly from unknown-id.
  - [x] `op invoke` ok-result projected, all keys present.
  - [x] `op invoke` error-result → `:is-error true`, projected.
  - [x] unknown id → error report, not crash.
  - [x] malformed args (non-map / unreadable EDN) → validate error, not crash.
  - [x] side-effecting op invokable (assert observable effect).
  - [x] over-2000-char value truncated identically to slice-1 helper.
  - [x] `validate-psi-tool-request` accepts `action "operation"`.
- [x] Run slice-2 tests; `clj-paren-repair`; `clj-kondo --lint`.
- [x] Commit: `⚒ 205: psi-tool operation action (list|invoke)`.

## Slice 3 — slash commands `/operations` + `/operation`

- [x] In `commands.clj`: require the shared helper ns.
- [x] Add `"/operations" :operations` to `exact-command-handlers`; add
  `:operations` arm in `dispatch*` exact `case` →
  `{:type :text :message (format-operations ctx)}`.
- [x] Implement `format-operations [ctx]`: call `list-operations`; empty →
  `"No deterministic operations registered."`; else lines of
  `"<id> — <description>"` (sorted already by helper).
- [x] Add `"/operation"` to `prefixed-command-prefixes`; add arm to
  `dispatch-prefixed-command` → `dispatch-operation-command`.
- [x] Implement `dispatch-operation-command [ctx session-id trimmed]` (decision
  #11): strip `^/operation\s*`; split tail once on first whitespace run into
  `<id>` + remaining text; blank `<id>` →
  `{:type :text :message "Usage: /operation <id> {edn-args}"}`; blank remaining
  → `args {}`; parse remaining as EDN map, non-map/unreadable →
  `{:type :text :message "<clear parse error>"}`; else call shared
  `invoke-operation`, render projected result via `project-result` as
  `:type :text` using the D2 layout: one `"<key> <value>"` line per top-level
  key, `:status` line first, remaining keys sorted ascending by `pr-str`.
  Catch dispatching on `(:type (ex-data e))` (D3):
  `:missing-deterministic-operation` → distinct "unknown operation" text;
  `:malformed-operation-result` → distinct "malformed result" text; any other
  throwable re-thrown (not a blanket catch).
- [x] Confirm precedence: `/operations` matched as exact before `/operation`
  prefix; add a test asserting `/operations` does not dispatch as `/operation`.
- [x] Write tests
  `components/agent-session/test/psi/agent_session/operation_command_test.clj`:
  - [x] `/operations` lists id+description sorted; empty → exact message.
  - [x] `/operation <id> {args}` invokes, renders result text (all keys).
  - [x] (D2) exact multi-line text for a known result: `:status` line first,
    remaining keys sorted ascending by `pr-str`, one `"<key> <value>"` per line.
  - [x] (D3) malformed-result op → distinct text from unknown-id text.
  - [x] `/operation <id>` (no args) → `args {}` default.
  - [x] blank id → usage message.
  - [x] malformed/non-map args → clear text error, not crash.
  - [x] unknown id → clear text error, not crash.
  - [x] side-effecting op invokable.
  - [x] `/operations` vs `/operation` precedence (no collision).
- [x] Run slice-3 tests; `clj-paren-repair`; `clj-kondo --lint`.
- [x] Commit: `⚒ 205: /operations + /operation slash commands`.

## Slice 4 — docs + CHANGELOG

- [x] Update README and relevant `doc/` (command reference + psi-tool action
  reference) documenting `/operations`, `/operation <id> {edn-args}`, and
  `action: "operation"` with `op list|invoke`, params, truncation, side-effects.
- [x] Add CHANGELOG `[Unreleased] > Added` entry for the new commands +
  psi-tool action (before commit, user-visible).
- [x] Verify coherence: design ↔ commands/psi-tool ↔ tests ↔ docs.
- [x] Run full agent-session test suite for touched namespaces.
- [x] Commit: `⚒ 205: docs + CHANGELOG for deterministic-operation surfaces`.

## Plan/steps-review follow-ups (ψ)

- [x] Decide where `args` EDN parse/validate runs (psi-tool): in
  `validate-psi-tool-request` (outer-try → add an `"operation"` arm to
  `make-psi-tool`'s outer exception `case action` so it renders a structured
  `:psi-tool/action :operation … :overall-status :error`, not the generic
  fallback) **or** inside `execute-psi-tool-operation-report` (covered by its
  own try/catch). Record the choice in plan.md and wire accordingly.
  → **D1** (plan.md): parse in `validate-psi-tool-request` + add outer-catch
  `"operation"` arm. Slice-2 steps updated.
- [x] Specify the command text layout for the projected result: define the
  per-key line format (e.g. one `<key> <value>` line per top-level key), key
  ordering, and how `:status` renders. Update `dispatch-operation-command` /
  `format-operations` step + add a test asserting the exact text.
  → **D2** (plan.md): one `"<key> <value>"` line per top-level key, `:status`
  first, rest sorted ascending by `pr-str`. Slice-3 step + test updated.
- [x] Specify the surface catch predicate: dispatch on `(:type (ex-data e))`
  for `:missing-deterministic-operation` vs `:malformed-operation-result`,
  render each distinctly (not a blanket catch), and only those two propagate
  (runtime swallows other throwables into `:error`). Apply to both psi-tool and
  command surfaces; test both propagated types.
  → **D3** (plan.md): dispatch on `(:type (ex-data e))`, render each distinctly,
  re-throw others. Slice-2/3 steps + tests updated.
- [x] Decide the tool-schema `:op` enum policy for the `operation` action:
  either extend `:op` `:enum` with `list`/`invoke`, or follow the existing
  non-enumerated convention (workflow/scheduler ops are not in the enum).
  Record the decision in plan.md and apply consistently.
  → **D4** (plan.md): follow the non-enumerated convention; `op` validated in
  `validate-psi-tool-request` only; only `:action` enum gains `"operation"`.
  Slice-2 step updated.
- [x] State whether `args` is parsed for `op: "list"`: per decision #12 `args`
  is ignored for list — skip `args` EDN parse/validate when `op` is `list` so a
  malformed `args` string does not error a list call. Update the validate/parse
  step and add a test (`op list` with bad `args` → still lists, not error).
  → **D5** (plan.md): `args` parsed only on the `"invoke"` branch; `list` skips
  it. Slice-2 step + test updated.

## Plan/steps inconsistency follow-ups (ψ)

- [x] (INC-1) Fix the `args` binding collision in `validate-psi-tool-request`:
  do **not** add `args` to the fn's `:strs` destructuring (it already binds the
  whole request map `:as args`, used by `(psi-tool-action args)` and the
  outer-catch `(get args "op")`). Read the `"args"` EDN-map-string param via a
  distinct binding (`(get args "args")` or a separate symbol e.g.
  `operation-args`). Update the slice-2 "add `operation-id`/`args` to
  destructuring" item accordingly.
- [x] (INC-2) Remove `clojure.edn` and `clojure.string` from the slice-1
  `deterministic-operation-action` ns requires — no slice-1 function uses them
  (`truncate-value` uses core `subs`/`str`/`count`; `args` EDN parsing is a
  slice-2 concern per D1/D5). Add `clojure.edn` only in slice-2 if the EDN
  parse helper is placed in the shared ns. Update the slice-1 require step so it
  passes `clj-kondo --lint` (no unused namespace).

## Test-review follow-ups (ψ)

- [x] (TR-1) Cover the optional `:details` result key in projection (decision
  #7 names it as a top-level key on both `:ok` and `:error` results; its value
  is a nested **map** — the non-trivial `pr-str` case — and is currently
  exercised by no test). Add:
  - [x] `project-result` unit test
    (`deterministic_operation_action_test.clj`) on `{:status :ok :data …
    :details {…}}` asserting `:details` is present in the projected map and
    rendered via `pr-str`.
  - [x] a psi-tool `op invoke` test (`psi_tool_operation_test.clj`) on an op
    returning `:details`, asserting the `:psi-tool/result` carries the
    `:details` key with its `pr-str`'d value.
  - [x] a `/operation` command test (`operation_command_test.clj`) on the same op,
    asserting a `:details {…}` line appears in the `:type :text` output (closes
    the all-keys rule for the nested-map case on both surfaces).

- [x] (TR-2) Cover per-key truncation on the `/operation` **command** surface
  (decision #9 surface-parity). Truncation is currently tested only at the
  helper level and on the psi-tool surface; `operation_command_test.clj` has no
  over-2000-char case, so the command `render-operation-result` text path is
  unverified for the truncation marker. Add a `/operation` command test invoking
  an op whose result value `pr-str`s to >2000 chars, asserting the rendered
  `:type :text` line for that key contains the exact
  `… (truncated, N chars total)` marker and equals
  `(op-action/truncate-value (pr-str value))` — closing the "identical across
  surfaces" guarantee (plan risk) on the command surface. Distinct from TR-1
  (nested-map `:details` projection).

- [x] (TR-3) Cover the unreadable-EDN malformed-args branch on the **psi-tool**
  `op invoke` surface (decision #11 surface-parity). Today
  `operation-invoke-malformed-args-validate-error` only tests the **non-map**
  case (`"[1 2 3]"` → explicit `:phase :validate` "must be an EDN map" ex-info);
  the **unreadable-EDN** sub-case takes a different path — a raw
  `RuntimeException` ("EOF while reading") thrown directly out of
  `parse-edn-string` (no try/catch), surfaced via the outer-catch `"operation"`
  arm — and is exercised by no psi-tool test. The command surface
  (`operation-bad-args-error`) already tests *both* sub-cases. Add a
  `psi_tool_operation_integration_test` case dispatching `op invoke` with an
  unreadable EDN `args` string (e.g. `"{:x"`), asserting `:is-error true`,
  `:psi-tool/action :operation`, and `:psi-tool/overall-status :error` — closing
  the decision-#11 parse-failure surface-parity guarantee (distinct from the
  non-map case already covered). This is the gap the checked slice-2 item
  "malformed args (non-map / unreadable EDN) → validate error, not crash"
  overstated for the psi-tool surface.

- [x] (TR-4) Cover the end-to-end psi-tool `:is-error` flag for a
  tagged-`:error` operation result (slice-2 surface-parity). `make-psi-tool`'s
  `"operation"` arm sets `:is-error (not= :ok (:psi-tool/overall-status
  safe-report))` (psi_tool.clj ~L693); for `invoke` the overall-status is
  `(:status tagged)`, so a handler *returning* `{:status :error …}` (a domain
  error, NOT an exception) drives `:is-error true` via a distinct code path from
  every currently-tested `:is-error true` integration case (all of which are
  validation/lookup/parse failures via validate/outer-catch/missing-operation).
  The tagged-error case is verified only at the report-unit level
  (`invoke-error-sets-overall-status`, which asserts `:psi-tool/overall-status
  :error` but not the serialized `:is-error`). Add a
  `psi_tool_operation_integration_test` case registering an op whose handler
  returns `{:status :error :reason … :message …}`, dispatching `op invoke`
  through the real `make-psi-tool`, and asserting `:is-error true`,
  `:psi-tool/action :operation`, `:psi-tool/overall-status :error`, and the
  projected `:reason`/`:message` keys on `:psi-tool/result`. Distinct from TR-1
  (`:details` projection), TR-2 (command truncation), TR-3 (unreadable-EDN
  *exception* path; this is the *tagged-error* non-exception path). Run the
  integration suite focused; `clj-paren-repair`; `clj-kondo --lint`; commit.

## Test-shaper follow-ups (ψ, sixth pass)

- [x] (TS-1) Consolidate the duplicated/inconsistent test fixtures across the
  four task suites. The `make-ctx` (two variants — one takes `sessions`, one
  hard-codes `{}`), `ok-op` (two variants — whole-invocation echo vs `:args`
  echo), `create-session-context`, and `register-op!` are independently
  re-defined per suite with incidental variation and no shared home. Extract a
  single shared fixture set (e.g. in `test_support.clj` or a small
  `deterministic-operation-test-support` helper ns) with one canonical
  `make-ctx`/`ok-op`/`create-session-context`/`register-op!`, and have all four
  suites use it — `consistent(fixtures) ∧ minimal(incidental_variation) ∧
  helpers_that_compress(ceremony)`. Keep helpers that compress ceremony, not
  ones that hide intent. Re-run the four suites; `clj-paren-repair`;
  `clj-kondo --lint`; commit.
  → Added canonical `make-op-ctx`/`ok-op`/`create-op-session-context`/
  `register-op!` to `test_support.clj`; all four suites now alias them. One
  canonical `ok-op` (whole-invocation echo, `"desc for " id`); both unit suites
  rebind to it (psi-tool list assertion updated to the canonical description).
- [x] (TS-2) Strengthen `operation-invoke-renders-result` (command) signal.
  Replace the `str/includes? ":status :ok"` / `":data {:x 1}"` substring
  assertions with an exact line-set/line-equality assertion (as
  `operation-invoke-status-line-first` already does), or fold the case into the
  exact-line test, so a malformed concatenation cannot pass. Avoid leaving two
  overlapping layout tests where one is weaker-signal. Re-run; commit.
  → Replaced the two `str/includes?` assertions with an exact
  `(= [":status :ok" ":data {:x 1}"] (str/split-lines …))` line-equality check.
- [x] (TS-3) Make `operation-list-ignores-args-and-id` (integration) actually
  verify the *id*-ignored half. Register an op whose handler writes a sink (or
  has an observable effect) under the id passed as `operation-id "ignored"`,
  then assert the sink is **untouched** by the `op list` call (list neither
  invokes nor errors on the supplied id) — or rename the test to reflect that
  only the args-ignored half is asserted. Re-run; commit.
  → Registered a sink-writing op under a valid id `side/effect`, passed that as
  `operation-id`, and asserted the sink stays `:untouched` and the op still
  appears in `:psi-tool/operations` (list neither invokes nor errors on the id).
  (Used a schema-valid `ns/name` id; the literal `"ignored"` fails the
  `^ns/name$` operation-id schema at registration.)

## Docs-review follow-ups (ψ)

- [x] (DR-1) Bring the psi-tool `operation` **action** surface to documentation
  parity with its sibling federated actions (`scheduler` has a dedicated
  `doc/scheduler.md` + README "See:" link; `operation` has only a one-line
  README bullet and a passing mention in `doc/tui.md`, which documents the
  *command* surface only). Decision #1 makes the action an equal first-class
  surface, so it warrants equivalent reference docs. Add either a dedicated
  `doc/operations.md` (mirroring `doc/scheduler.md`'s shape) or a "psi-tool
  `operation` action" subsection in `doc/tui.md`, covering: the
  `{:action "operation" :op "list"|"invoke"}` request shape with a worked
  example for each op; the `operation-id` and `args` (EDN-map-string, default
  `{}`) params; that `list` ignores `operation-id`/`args` and sorts by id;
  empty-list `:operations []`; all-top-level-key + 2000-char-per-value
  truncation rendering; and unknown-id / malformed-result / malformed-args
  error surfacing. If a dedicated page is added, also add a README "See:" link
  to it. Verify the new doc matches `psi_tool_operation.clj` /
  `psi_tool_validate.clj` exactly; re-check coherence design ↔ code ↔ docs.

## Close-out

- [x] Re-read design acceptance criteria; confirm each is covered by a test.
- [ ] `git mv` task dir `open/ → closed/` and remove from `munera/plan.md`
  (only when implementation + reviews complete).
