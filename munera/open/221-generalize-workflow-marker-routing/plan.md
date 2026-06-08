# Plan — Generalize workflow marker routing

## Approach

Replace the two workflow-specific deterministic routing operations with one generic exact-marker routing operation while preserving the simplification workflow topology and route labels.

Key decisions:

- Runtime code owns only a parameterized exact-marker parser and the registered `workflow/exact-marker-routing` operation.
- Authored workflow EDNs own marker labels (`PROOF_SYNC_ROUTE`, `VALIDATION_CAPTURE_ROUTE`) and allowed route vectors.
- Invalid operation args are validated before parsing and return the exact `:invalid-route-marker-args` diagnostic shape from `design.md`, accumulating all arg errors.
- Marker candidate classification happens across the whole reply before result selection; any multi-candidate reply returns `:ambiguous-route-marker` with all candidate lines and per-candidate classifications.
- The old specialized operation ids are removed without compatibility aliases.
- Removal sequencing is compile-safe: Slice 2 may make the generic parser public and parameterized, but the old proof-sync / validation-capture wrappers and route constants remain as temporary delegators until Slice 4. Slice 4 must add `workflow/exact-marker-routing` and remove the two old registrations, wrappers, and constants in the same code edit/commit so `workflow.core` never references deleted routing vars.
- Behaviour proof comes from generic parser/operation tests plus workflow-loader/content-lock tests proving the authored EDNs now supply the workflow-specific policy.

## Risks

- **Resolved-arg boundary drift:** workflow source EDN uses `{:from ...}` arg references, while the registered operation receives resolved strings at runtime. Tests should distinguish authored EDN shape from live invocation args.
- **Candidate classification regressions:** malformed marker attempts must not be mistaken for prose, but prose merely mentioning a marker label must remain ignored.
- **Ambiguity diagnostics:** existing duplicate-marker tests only checked candidate lines; the new contract also requires complete candidate classifications for mixed candidate cases.
- **Operation surface breakage:** `/operations`, `/operation`, and `psi-tool operation` expose registered operation ids, so registration tests and CHANGELOG must be updated together with removal of old ids.
- **Workflow content-lock churn:** tasks 209, 218, and 220 tests all lock the old operation ids and must be updated without changing workflow topology.

## Slice order

1. **Preflight and source mapping** — inspect current routing code, operation registration, simplification workflow disposition steps, tests, docs, and CHANGELOG references.
2. **Generic exact-marker parser** — make `parse-exact-marker-routing` public and parameterized; add explicit arg validation; implement full candidate classification, precedence, and diagnostics. Keep `proof-sync-routes`, `validation-capture-routes`, `parse-proof-sync-disposition-routing`, and `parse-validation-capture-disposition-routing` as temporary compile-safe delegators until Slice 4; do not remove them while `workflow.core` still registers the old operation ids.
3. **Parser test net** — replace proof-sync/validation-specific parser tests with arbitrary marker/route parser tests covering valid, missing, duplicate, malformed, unsupported, prose, whitespace, trailing text, invalid args, and mixed-candidate precedence.
4. **Operation registration cleanup** — in one code edit/commit, register `workflow/exact-marker-routing`, update the handler to pass resolved args directly to `routing/parse-exact-marker-routing`, and remove `workflow/proof-sync-disposition-routing`, `workflow/validation-capture-disposition-routing`, `proof-sync-routes`, `validation-capture-routes`, `parse-proof-sync-disposition-routing`, and `parse-validation-capture-disposition-routing`. Update built-in operation smoke tests for registration, valid invocation, invalid-arg invocation, and old-id absence.
5. **Workflow EDN migration** — update `reduce-architectural-complexity.edn` and `reduce-incidental-complexity.edn` disposition steps to invoke the generic operation with workflow-owned `:marker-label` and `:allowed-routes` args while preserving `:on` topology.
6. **Workflow-loader/content-lock updates** — update task 209, 218, and 220 workflow-loader tests to assert the generic operation id and exact authored marker policy args for both simplification workflows.
7. **Docs/changelog and verification** — add the required CHANGELOG entry, update docs only if explicit operation ids are mentioned, then run the pinned verification commands below, targeted clj-kondo, paren repair/format checks, the runtime-boundary cleanup check, and `git diff --check`.

## Pinned verification commands

- Routing parser namespace: `bb clojure:test:scry --namespace psi.agent-session.workflow.routing-test`
- Built-in operation registration/invocation namespace: `bb clojure:test:scry --namespace psi.agent-session.workflow-delegate-review-step-live-test`
- Workflow-loader/content-lock namespaces: `bb clojure:test:scry --dir components/workflow-loader/test --namespace psi.workflow-loader.task-209-workflow-definitions-test --namespace psi.workflow-loader.task-218-workflow-definitions-test --namespace psi.workflow-loader.task-220-workflow-proof-gates-test`
- Workflow EDN read check:

  ```sh
  bb -e '(require (quote clojure.edn) (quote clojure.java.io)) (doseq [p [".psi/workflows/reduce-architectural-complexity.edn" ".psi/workflows/reduce-incidental-complexity.edn"]] (with-open [r (java.io.PushbackReader. (clojure.java.io/reader p))] (clojure.edn/read r)) (println "read" p))'
  ```

- Targeted clj-kondo after implementation edits:

  ```sh
  clj-kondo --lint components/agent-session/src/psi/agent_session/workflow/routing.clj components/agent-session/src/psi/agent_session/workflow/core.clj components/agent-session/test/psi/agent_session/workflow/routing_test.clj components/agent-session/test/psi/agent_session/workflow_delegate_review_step_live_test.clj components/workflow-loader/test/psi/workflow_loader/task_209_workflow_definitions_test.clj components/workflow-loader/test/psi/workflow_loader/task_218_workflow_definitions_test.clj components/workflow-loader/test/psi/workflow_loader/task_220_workflow_proof_gates_test.clj
  ```

## Runtime-boundary cleanup check

After Slice 7, run the negative source-scope assertion below. It must return no matches from generic runtime source:

```sh
! grep -R -n -E 'proof-sync-routes|validation-capture-routes|workflow/proof-sync-disposition-routing|workflow/validation-capture-disposition-routing|PROOF_SYNC_ROUTE|VALIDATION_CAPTURE_ROUTE|COVERAGE_REVIEW|VALIDATION_RECAPTURE|BOOKKEEPING_FIXED_POINT|IMPLEMENTATION_REPAIR|TERMINAL_STOP' components/agent-session/src/psi/agent_session/workflow components/workflow-runtime/src components/workflow-loader/src components/deterministic-operation-runtime/src components/deterministic-operation-registry/src
```

Then run the positive authored-policy assertion below. It must find the expected marker labels and route vectors only in authored workflow EDNs/content-lock tests:

```sh
grep -R -n -E '"PROOF_SYNC_ROUTE"|"VALIDATION_CAPTURE_ROUTE"|"COVERAGE_REVIEW"|"VALIDATION_RECAPTURE"|"BOOKKEEPING_FIXED_POINT"|"IMPLEMENTATION_REPAIR"|"TERMINAL_STOP"' .psi/workflows/reduce-architectural-complexity.edn .psi/workflows/reduce-incidental-complexity.edn components/workflow-loader/test/psi/workflow_loader/task_209_workflow_definitions_test.clj components/workflow-loader/test/psi/workflow_loader/task_218_workflow_definitions_test.clj components/workflow-loader/test/psi/workflow_loader/task_220_workflow_proof_gates_test.clj
```
