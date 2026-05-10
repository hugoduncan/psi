2026-05-07

- Oriented on task `120` and confirmed prerequisite task `119-expand-turn-runtime-prepared-turn-boundary` is closed under `munera/closed/119-expand-turn-runtime-prepared-turn-boundary/`.
- Confirmed the surviving higher authoritative namespace family before the rename was:
  - `components/agent-session/src/psi/turn.clj` -> `psi.turn`
  - `components/agent-session/src/psi/turn/handlers.clj` -> `psi.turn.handlers`
- Confirmed lower prepared-turn ownership already remains under `psi.turn-runtime.*`, so the rename can stay narrow and ownership-signaling.
- Confirmed direct production consumers before the rename are:
  - `psi.agent-session.prompt-control`
  - `psi.agent-session.prompt-turn`
  - `psi.agent-session.context`
  - `psi.agent-session.dispatch-handlers.prompt-lifecycle`
- Confirmed direct test consumers before the rename are:
  - `psi.agent-session.test-support`
  - `psi.agent-session.prompt-lifecycle-test`
- Started implementation with the explicit intent to use `clj-surgeon` for the namespace rename and keep the change behavior-preserving.
- Ran `clj-surgeon :op :rename-ns` for both namespace targets. The tool surfaced non-`.clj` references for review but did not rewrite the authoritative source files, so the authoritative file-path + `ns` rename was completed manually in the same narrow task branch.
- Renamed the authoritative higher orchestration files to:
  - `components/agent-session/src/psi/agent_session/turn.clj` -> `psi.agent-session.turn`
  - `components/agent-session/src/psi/agent_session/turn/handlers.clj` -> `psi.agent-session.turn.handlers`
- Updated direct production and test consumers to require the renamed namespaces.
- Updated `with-redefs` callsites in `psi.agent-session.prompt-lifecycle-test` so the focused prompt-control delegation proof targets `psi.agent-session.turn/*` vars directly rather than the retired `psi.turn/*` vars.

2026-05-08 review notes

Review findings
- implementation matches the task design: the surviving higher orchestration family now lives under `psi.agent-session.turn.*`, while lower prepared-turn mechanics remain under `psi.turn-runtime.*`
- authoritative source ownership is now explicit in both file paths and `ns` forms
- direct production and test consumers were updated consistently; no retired higher `psi.turn` / `psi.turn.*` production requires or namespace definitions remain
- no unnecessary compatibility alias namespace was introduced during the rename
- remaining `:psi.turn/*` occurrences are telemetry/query attrs rather than namespace references, so they do not contradict the task boundary

Review conclusion
- no new actionable follow-ups found
- task implementation quality looks good for closure from the implementation-review perspective

2026-05-08 code-shaper review notes

Code-shape findings
- the rename stayed narrow: no new abstraction layer was introduced beyond the already-existing `psi.agent-session.prompt-control` compatibility facade
- ownership/naming is now more locally comprehensible: `psi.agent-session.turn` and `psi.agent-session.turn.handlers` read consistently beside `psi.turn-runtime.*`
- touched consumers use the renamed namespaces consistently, including the `with-redefs` var targets in the prompt lifecycle test
- the renamed namespaces themselves remain simple wrappers/orchestrators over existing lower `turn-runtime` and dispatch-owned seams; the task did not add new control-flow complexity or extra indirection
- `psi.agent-session.prompt-control` is still a pure compatibility wrapper namespace, but that seam pre-existed this task and was not expanded by the rename; retiring or consolidating it would be a separate cleanup rather than a follow-up required by `120`

Code-shaper conclusion
- no code-shape issues introduced by task `120`
- no new actionable shaping follow-ups required for this task

2026-05-08 test review notes

Test findings
- test coverage is well matched to the task scope: the rename-specific proof is the prompt-control delegation test, and higher-level prompt/session lifecycle focused tests still provide consuming-path coverage
- the important brittle spot exposed during implementation was already corrected: `with-redefs` now targets `psi.agent-session.turn/*` vars directly, so the focused delegation proof tracks the new authoritative namespace family rather than a retired alias
- test support wiring also requires the renamed namespace consistently, so helper-backed focused tests load through the new ownership path
- one minor naming cleanup remained in test prose only: `prompt-control-delegates-to-psi-turn-test` mentioned the retired `psi-turn` label even though the test verified delegation to `psi.agent-session.turn`
- that follow-up has now been executed by renaming the test to `prompt-control-delegates-to-agent-session-turn-test`

Test review conclusion
- no test-coverage gap found for the task acceptance criteria
- the one minor test-vocabulary follow-up identified in review has now been completed
