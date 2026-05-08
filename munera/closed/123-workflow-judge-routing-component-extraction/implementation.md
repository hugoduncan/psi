2026-05-08

Implemented the extraction as a narrow below-dispatch component split.

Final namespace/boundary split:
- new lower component: `components/workflow-judge/`
- authoritative pure namespace: `psi.workflow-judge`
- remaining higher impure namespace: `psi.agent-session.workflow-judge`

What moved downward:
- message projection helpers and `project-messages`
- `match-signal`
- `resolve-goto-target`
- `check-iteration-limit`
- `evaluate-routing`

What remained above:
- persistence reads for actor messages
- judge child-session creation
- prompt submission
- retry feedback injection / retry loop
- `execute-judge!`

Consumer rewiring:
- `psi.agent-session.workflow-source-resolution` now depends directly on `psi.workflow-judge` for projection semantics
- `psi.agent-session.workflow-judge` now depends downward on `psi.workflow-judge` for pure projection/routing logic while retaining execution ownership
- root + agent-session deps now include `psi/workflow-judge`

Test split:
- pure projection/routing tests moved to `components/workflow-judge/test/psi/workflow_judge_test.clj`
- impure execution tests remained in `components/agent-session/test/psi/agent_session/workflow_judge_test.clj`
- higher statechart/workflow integration proofs remain above the boundary unchanged

Contract notes:
- projection semantics preserved exactly from the prior owner
- routing result shapes preserved exactly
- step-id contract preserved as existing string step ids in `step-order` / routing-table targets; no ambiguity forced a redesign during extraction, so no contract change was made

Non-shim note:
- the remaining `psi.agent-session.workflow-judge` namespace is not a compatibility facade; it is now the authoritative owner only of impure judge-session execution/orchestration

Verification:
- focused tests: `22 tests, 98 assertions, 0 failures`
- lint: `0 errors, 0 warnings`

Code-shaper review:
- review passed; extraction shape is simple, consistent, and robust
- one minor follow-up noted: reorder forms in `components/workflow-judge/src/psi/workflow_judge.clj` so projection helpers/functions and routing helpers/functions are grouped contiguously for stronger local comprehensibility

Namespace-order cleanup:
- reordered `psi.workflow-judge` so projection helpers/functions are contiguous and routing helpers/functions are contiguous
- focused verification after cleanup: `3 tests, 16 assertions, 0 failures`
- lint after cleanup: `0 errors, 0 warnings`

Test review:
- test review passed overall; pure/impure boundary ownership is proved in the right places
- one actionable follow-up noted: add an explicit pure-component regression proving that `:tool-output false` drops messages whose content becomes empty after tool-block stripping
- one optional follow-up noted: add a consumer-level proof for `workflow-source-resolution` applying `:projection` through the new lower owner

Test-follow-up execution:
- added a pure `psi.workflow-judge-test` regression proving `:tool-output false` drops assistant/tool messages emptied by tool-block stripping
- extended `psi.agent-session.workflow-source-resolution-test` to prove both `:projection :full` passthrough and the lower-owner projection path that drops emptied messages
- focused verification after the test follow-up: `8 tests, 15 assertions, 0 failures`
- lint after the test follow-up: `0 errors, 0 warnings`

Test-shaper review:
- review passed; the tests now prove the intended extraction invariants with good boundary alignment
- two minor follow-ups noted: split the dense `project-messages-tail-tool-output-false-test` into narrower behavior-focused tests, and optionally extract a tiny transcript-run helper in `workflow_source_resolution_test.clj` to reduce repeated setup

Test-shaper follow-up execution:
- split the dense pure projection test into four narrower behavior-focused tests covering preserved non-tool text, stripped tool blocks, dropped emptied messages, and `tool-output true` preservation
- added a tiny `run-with-transcript` helper in `workflow_source_resolution_test.clj` to remove repeated transcript-run setup while keeping test intent explicit
- focused verification after the test-shaper follow-up: `8 tests, 15 assertions, 0 failures`
- lint after the test-shaper follow-up: `0 errors, 0 warnings`
