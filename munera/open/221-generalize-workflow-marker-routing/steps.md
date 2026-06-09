# Steps — Generalize workflow marker routing

## Slice 1 — Preflight and source mapping

- [x] Read `components/agent-session/src/psi/agent_session/workflow/routing.clj` and confirm all proof-sync / validation-capture hard-coded route vocabularies and wrappers to remove.
- [x] Read `components/agent-session/src/psi/agent_session/workflow/core.clj` and identify the built-in deterministic operation registration block to change.
- [x] Read `.psi/workflows/reduce-architectural-complexity.edn` and record the validation/proof disposition steps, current operation ids, text arg sources, route labels, and `:on` maps.
- [x] Read `.psi/workflows/reduce-incidental-complexity.edn` and record the validation/proof disposition steps, current operation ids, text arg sources, route labels, and `:on` maps.
- [x] Search `README.md`, `doc/`, and `CHANGELOG.md` for old/new workflow operation ids and note which user-facing docs, if any, require updates.

## Slice 2 — Generic exact-marker parser

- [x] Make `parse-exact-marker-routing` public/testable and accept a map containing `:text`, `:marker-label`, and `:allowed-routes`.
- [x] Add argument validation that accumulates `:details :errors` and returns `{:status :error :reason :invalid-route-marker-args :message "workflow/exact-marker-routing args are invalid" ...}` before candidate parsing.
- [x] Implement validation errors for missing/non-string `:text`, missing/non-string/invalid `:marker-label`, missing/non-vector/empty `:allowed-routes`, invalid route entries, and duplicate route tokens with indices.
- [x] Restrict marker labels and route tokens to strings matching `^[A-Z_]+$`.
- [x] Update candidate classification so candidates include exact marker lines, whitespace-before-colon attempts, and leading-whitespace marker attempts, while ordinary prose mentions without marker colon remain ordinary prose.
- [x] Include per-candidate maps with `:line`, `:kind`, and route/reason/value fields for exact, malformed, and unsupported candidates.
- [x] Apply result precedence exactly: missing candidates, ambiguous multi-candidate, supported exact, unsupported single candidate, malformed single candidate.
- [x] Include `:marker-label`, `:route-marker-lines`, and `:route-marker-candidates` in ambiguous diagnostics.
- [x] Keep `proof-sync-routes`, `validation-capture-routes`, `parse-proof-sync-disposition-routing`, and `parse-validation-capture-disposition-routing` as temporary compile-safe delegators until Slice 4; do not remove them in Slice 2 while `workflow.core` still registers the old operations.

## Slice 3 — Parser test net

- [x] Replace workflow-specific routing parser tests with generic `parse-exact-marker-routing` tests using arbitrary marker labels and route tokens.
- [x] Add valid-route tests proving surrounding prose and `PASS_STATUS` lines are ignored.
- [x] Add missing-marker and prose-mention tests proving prose mentions of the marker label are ignored.
- [x] Add duplicate valid marker tests asserting `:ambiguous-route-marker`, complete `:route-marker-lines`, and complete `:route-marker-candidates`.
- [x] Add unsupported single-candidate tests asserting `:unsupported-route-marker` diagnostics include value and allowed routes.
- [x] Add malformed single-candidate tests for leading whitespace, whitespace before colon, missing post-colon space, trailing whitespace, same-line extra text, and invalid/lowercase route tokens.
- [x] Add mixed-candidate precedence tests for valid+malformed, valid+unsupported, malformed+unsupported, multiple malformed/unsupported, and duplicate valid candidates; assert `:ambiguous-route-marker` always wins.
- [x] Add invalid-arg tests for all required invalid arg cases, including missing/non-string text, invalid marker labels, empty/non-vector allowed routes, duplicate routes, invalid route tokens, and accumulation of multiple errors.
- [x] Run `bb clojure:test:scry --namespace psi.agent-session.workflow.routing-test` and fix failures before editing workflow EDNs.

## Slice 4 — Coherent operation/EDN handoff

- [x] Register `workflow/exact-marker-routing` in `register-built-in-deterministic-operations!` with a handler that passes resolved args to `routing/parse-exact-marker-routing`.
- [x] In the same Slice 4 handoff commit, update architecture workflow `validation-capture-disposition` to use `workflow/exact-marker-routing` with `:marker-label "VALIDATION_CAPTURE_ROUTE"` and allowed routes `["IMPLEMENTATION_REPAIR" "TERMINAL_STOP"]`.
- [x] In the same Slice 4 handoff commit, update architecture workflow `proof-sync-disposition` to use `workflow/exact-marker-routing` with `:marker-label "PROOF_SYNC_ROUTE"` and allowed routes `["COVERAGE_REVIEW" "VALIDATION_RECAPTURE" "BOOKKEEPING_FIXED_POINT"]`.
- [x] In the same Slice 4 handoff commit, update incidental workflow `validation-capture-disposition` to use `workflow/exact-marker-routing`, sourcing text from `incidental-validation-capture` and supplying the validation marker policy args.
- [x] In the same Slice 4 handoff commit, update incidental workflow `proof-sync-disposition` to use `workflow/exact-marker-routing` and supply the proof-sync marker policy args.
- [x] In the same Slice 4 handoff commit, remove registration for `workflow/proof-sync-disposition-routing` and remove the now-unused `routing/parse-proof-sync-disposition-routing` wrapper / `proof-sync-routes` constant; do not leave `workflow.core` referencing a deleted var and do not commit checked-in workflow EDNs that still call the removed operation id.
- [x] In the same Slice 4 handoff commit, remove registration for `workflow/validation-capture-disposition-routing` and remove the now-unused `routing/parse-validation-capture-disposition-routing` wrapper / `validation-capture-routes` constant; do not leave `workflow.core` referencing a deleted var and do not commit checked-in workflow EDNs that still call the removed operation id.
- [x] Update built-in workflow operation registration tests to assert the new operation is present and the two old operation ids are absent.
- [x] Update live operation invocation smoke tests to invoke `workflow/exact-marker-routing` with an arbitrary marker label and allowed routes.
- [x] Add an operation smoke test proving invalid args return `:invalid-route-marker-args` without throwing through the registry.
- [x] Run `bb clojure:test:scry --namespace psi.agent-session.workflow-delegate-review-step-live-test` for built-in deterministic operation registration and live invocation smoke coverage.

## Slice 5 — Post-handoff EDN verification

- [x] Verify all disposition `:on` maps are unchanged after operation migration.
- [x] Parse-read both workflow EDN files and fix syntax/formatting issues.
- [x] Confirm neither simplification workflow EDN references `workflow/proof-sync-disposition-routing` or `workflow/validation-capture-disposition-routing` after the same handoff that removes their registrations.

## Slice 6 — Workflow-loader/content-lock updates

- [x] Update `task_218_workflow_definitions_test.clj` to expect `workflow/exact-marker-routing` and exact authored validation/proof marker policy args.
- [x] Update `task_209_workflow_definitions_test.clj` to expect `workflow/exact-marker-routing` and exact authored validation/proof marker policy args for the incidental workflow.
- [x] Update `task_220_workflow_proof_gates_test.clj` to expect `workflow/exact-marker-routing` and exact authored marker policy args for both simplification workflows.
- [x] Keep existing assertions for route topology, terminal-stop routing, prompts, and route-label prompt text unchanged unless the operation arg shape requires a narrow assertion update.
- [x] Run `bb clojure:test:scry --dir components/workflow-loader/test --namespace psi.workflow-loader.task-209-workflow-definitions-test --namespace psi.workflow-loader.task-218-workflow-definitions-test --namespace psi.workflow-loader.task-220-workflow-proof-gates-test` and fix failures.

## Slice 7 — Docs/changelog and verification

- [x] Add a CHANGELOG `[Unreleased]` entry noting the new `workflow/exact-marker-routing` registered operation and removal of `workflow/proof-sync-disposition-routing` / `workflow/validation-capture-disposition-routing`.
- [x] Update `README.md` or `doc/` only if the preflight search found explicit mentions of the old or new built-in workflow operation ids.
- [x] Run `clj-paren-repair` on changed Clojure files if edits disturb formatting or delimiters.
- [x] Run targeted `clj-kondo` on changed Clojure source/test paths: `clj-kondo --lint components/agent-session/src/psi/agent_session/workflow/routing.clj components/agent-session/src/psi/agent_session/workflow/core.clj components/agent-session/test/psi/agent_session/workflow/routing_test.clj components/agent-session/test/psi/agent_session/workflow_delegate_review_step_live_test.clj components/workflow-loader/test/psi/workflow_loader/task_209_workflow_definitions_test.clj components/workflow-loader/test/psi/workflow_loader/task_218_workflow_definitions_test.clj components/workflow-loader/test/psi/workflow_loader/task_220_workflow_proof_gates_test.clj`.
- [x] Run `bb clojure:test:scry --namespace psi.agent-session.workflow.routing-test` for generic routing parser coverage.
- [x] Run `bb clojure:test:scry --namespace psi.agent-session.workflow-delegate-review-step-live-test` for built-in operation registration/invocation coverage.
- [x] Run `bb clojure:test:scry --dir components/workflow-loader/test --namespace psi.workflow-loader.task-209-workflow-definitions-test --namespace psi.workflow-loader.task-218-workflow-definitions-test --namespace psi.workflow-loader.task-220-workflow-proof-gates-test` for workflow-loader/content-lock coverage.
- [x] Run workflow EDN read checks: `bb -e '(require (quote clojure.edn) (quote clojure.java.io)) (doseq [p [".psi/workflows/reduce-architectural-complexity.edn" ".psi/workflows/reduce-incidental-complexity.edn"]] (with-open [r (java.io.PushbackReader. (clojure.java.io/reader p))] (clojure.edn/read r)) (println "read" p))'`.
- [x] Run runtime-boundary cleanup negative assertion: `! grep -R -n -E 'proof-sync-routes|validation-capture-routes|workflow/proof-sync-disposition-routing|workflow/validation-capture-disposition-routing|PROOF_SYNC_ROUTE|VALIDATION_CAPTURE_ROUTE|COVERAGE_REVIEW|VALIDATION_RECAPTURE|BOOKKEEPING_FIXED_POINT|IMPLEMENTATION_REPAIR|TERMINAL_STOP' components/agent-session/src/psi/agent_session/workflow components/workflow-runtime/src components/workflow-loader/src components/deterministic-operation-runtime/src components/deterministic-operation-registry/src`.
- [x] Run authored-policy positive assertion: `grep -R -n -E '"PROOF_SYNC_ROUTE"|"VALIDATION_CAPTURE_ROUTE"|"COVERAGE_REVIEW"|"VALIDATION_RECAPTURE"|"BOOKKEEPING_FIXED_POINT"|"IMPLEMENTATION_REPAIR"|"TERMINAL_STOP"' .psi/workflows/reduce-architectural-complexity.edn .psi/workflows/reduce-incidental-complexity.edn components/workflow-loader/test/psi/workflow_loader/task_209_workflow_definitions_test.clj components/workflow-loader/test/psi/workflow_loader/task_218_workflow_definitions_test.clj components/workflow-loader/test/psi/workflow_loader/task_220_workflow_proof_gates_test.clj`.
- [x] Run `git diff --check`.
- [x] Append a concise implementation note to `implementation.md` with verification commands and results when implementation completes.

## Review follow-ups — plan ambiguity

- [x] PA1: Refine `plan.md`/`steps.md` so specialized parser wrappers and old operation registrations are removed in a compile-safe handoff: either remove `parse-proof-sync-disposition-routing` / `parse-validation-capture-disposition-routing` and their `workflow.core` registrations in the same slice/commit, or explicitly keep temporary wrappers until registration cleanup; do not leave `workflow.core` referencing deleted routing vars between slices.
- [x] PA2: Pin the focused verification commands/namespaces in `plan.md`/`steps.md` instead of saying only "relevant focused Scry suites": name the routing parser namespace, built-in operation registration/invocation namespace, workflow-loader task 209/218/220 namespaces, and the exact EDN read checks for both simplification workflows.
- [x] PA3: Add a concrete final boundary-cleanup verification step that scopes grep/assertions precisely: generic runtime source must not contain `proof-sync-routes`, `validation-capture-routes`, `workflow/proof-sync-disposition-routing`, `workflow/validation-capture-disposition-routing`, `PROOF_SYNC_ROUTE`, `VALIDATION_CAPTURE_ROUTE`, or workflow-owned route labels, while the authored workflow EDNs/content-lock tests still contain the expected marker labels and allowed route vectors.

## Review follow-ups — plan inconsistency

- [x] PI1: Refine `plan.md`/`steps.md` so old operation-id removal and simplification workflow EDN migration are one coherent repository-state handoff: either migrate both `.psi/workflows/reduce-architectural-complexity.edn` and `.psi/workflows/reduce-incidental-complexity.edn` in the same commit that removes `workflow/proof-sync-disposition-routing` / `workflow/validation-capture-disposition-routing`, or keep the old registered operation ids until no checked-in workflow references them. Do not commit an intermediate state where authored workflow EDNs still call removed built-in operation ids.

## Review follow-ups — test review

- [x] TR1: Add a `parse-exact-marker-routing` test for a column-0 line that starts with the marker label but has no marker colon (e.g. `"QUALITY_GATE recommends APPROVE"`), asserting it is treated as ordinary prose (`:missing-route-marker`, not `:malformed-route-marker`). This exercises the `marker-prefix? ∧ ¬marker-attempt?` → `:ordinary` branch and guards against a regression that collapses `marker-attempt?` into `marker-prefix?`.
- [x] TR2 (minor): Broaden invalid marker-label arg coverage (lowercase, digit, space, and blank marker-label) and add `:allowed-routes` nil and list rejection cases, so the `^[A-Z_]+$` marker-label contract and non-vector `:allowed-routes` rule are fully exercised, not just hyphen/non-string and set.
- [x] TS1: Add a `parse-exact-marker-routing` test for a column-0 line whose leading token is a strict superstring of the marker label followed by a marker colon (e.g. `"QUALITY_GATED: APPROVE"` and `"QUALITY_GATE_NOW: APPROVE"`), asserting it is ordinary prose (`:missing-route-marker`, not a candidate). This guards the `marker-attempt?` prefix boundary (char immediately after the label must be `:` or whitespace-before-`:`) against a regression that treats any line containing the label substring plus a later colon as a marker candidate. Distinct from TR1, which covers label + space + no colon.

## Review follow-ups — code shaper

- [x] CS1: Shape `marker-line-classification` in `components/agent-session/src/psi/agent_session/workflow/routing.clj` to bind its repeated derived facts once: hoist the `(str marker-label ": ")` marker-prefix string (currently rebuilt in both the `:missing-space-after-colon` guard and the `raw-route` `subs`) and the `#"^\s+:"` whitespace-before-colon match (currently evaluated in both `marker-attempt?` and the `:whitespace-before-colon` cond branch) into the function's `let`. Single-source each fact, then re-run `bb clojure:test:scry --namespace psi.agent-session.workflow.routing-test` and `clj-kondo` on the file to confirm behaviour is unchanged.
- [x] CS2 (naming consistency): In `marker-line-classification` (`components/agent-session/src/psi/agent_session/workflow/routing.clj`), rename the `marker-prefix?` boolean to a name that reflects its actual meaning (it is `(str/starts-with? trimmed-left marker-label)`, i.e. "line begins with the marker label", not a predicate over the adjacent `marker-prefix` string `"<label>: "`). Suggested: `starts-with-label?`. The current `marker-prefix` / `marker-prefix?` pair collides the `?`-suffix predicate idiom and misleads readers. Update all uses (the `after-marker-label` guard and `marker-attempt?`), keep behaviour unchanged, then re-run `bb clojure:test:scry --namespace psi.agent-session.workflow.routing-test` and `clj-kondo` on the file.
- [ ] CS3 (data-shape consistency): Standardize the three arg-validators feeding `exact-marker-routing-arg-errors` in `components/agent-session/src/psi/agent_session/workflow/routing.clj` to return a vector of errors (`[]`/`[err]`). Currently `invalid-text-error` and `invalid-marker-label-error` return `error | nil` (scalar) while `invalid-allowed-routes-errors` returns `[error ...]` (vector), forcing the aggregator to special-case the scalars via `(keep identity [...])`. Make `invalid-text-error`/`invalid-marker-label-error` return `[]`/`[err]`, then simplify `exact-marker-routing-arg-errors` to a uniform flat `(vec (concat (text-errors args) (marker-label-errors args) (allowed-routes-errors args)))` (renaming as appropriate). Keep accumulated `:details :errors` order and content unchanged. Verify behaviour-preserving: `bb clojure:test:scry --namespace psi.agent-session.workflow.routing-test` and `clj-kondo` on the file.
