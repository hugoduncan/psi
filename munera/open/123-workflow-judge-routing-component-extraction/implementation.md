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
