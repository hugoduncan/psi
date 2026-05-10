2026-05-07

Task created from post-123/124/125 workflow boundary review.

Creation rationale:
- `psi.workflow-runtime.step-prep` is now the most boundary-sensitive lower workflow-runtime namespace
- it currently mixes workflow step materialization with parent-session/config shaping
- separating those roles should improve local comprehensibility and make future ownership decisions easier without forcing a behavior redesign

Initial boundary notes:
- expected materialization owner: a lower workflow step materialization namespace
- expected config-shaping owner: a distinct lower workflow step session-config namespace
- do not move either role back to `agent-session` by default; decide from the resulting ownership shape

2026-05-07 implementation

Role classification from the former mixed owner:
- materialization owner: `binding-source-value`, `materialize-step-inputs`, `materialize-step-session-conversation`, `split-step-session-conversation`, `step-prompt`
- session-config owner: `resolve-step-session-config`
- materialization-private helpers kept with the materialization owner because they only serve prompt/conversation shaping:
  - `effective-step-def`
  - `text-message`
  - `conversation-message?`
  - `contribution-value->messages`
  - `materialize-session-contribution`
  - `prompt-text-from-message`
- session-config-private helpers kept with the session-config owner because they only serve child-session policy/config shaping:
  - `effective-step-def`
  - `compose-system-prompt`
  - `resolve-step-skills`
  - `resolve-step-tool-defs`
  - `step-meta-for`

Final ownership shape:
- created `psi.workflow-runtime.step-materialization`
- created `psi.workflow-runtime.step-session-config`
- removed `psi.workflow-runtime.step-prep` instead of retaining a façade because all production and proof consumers were rewired directly within this task
- no third helper owner was introduced; the split was already cleanly bi-modal and a third owner would have added indirection without clarifying a distinct top-level role

Consumer rewiring:
- `psi.agent-session.context` callback wiring now points directly to the split owners
- `psi.agent-session.psi-tool-workflow` compatibility backfill now resolves split-owner vars directly
- `psi.agent-session.test-support` callback wiring now points directly to the split owners
- `psi.agent-session.workflow-execution` docstring updated to describe the split lower owners

Proof ownership reshaping:
- materialization proofs now depend on `psi.workflow-runtime.step-materialization`
- session-config proofs now depend on `psi.workflow-runtime.step-session-config`
- renamed workflow-runtime proof files to match their new role owners:
  - `step_materialization_test.clj`
  - `step_session_config_test.clj`
- renamed shared workflow-runtime test support from `psi.workflow-runtime.step-prep-test-support` to `psi.workflow-runtime.step-test-support` to avoid preserving the old mixed-owner name by inertia
- review follow-up removed the remaining duplicate lower-level `agent-session` proof file so authoritative materialization helper proofs live under `workflow-runtime`

Final public vars:
- `psi.workflow-runtime.step-materialization`
  - `binding-source-value` — remains public because runtime-adoption proof sites intentionally consume the canonical binding-source surface directly
  - `materialize-step-inputs` — public authoritative materialization entrypoint
  - `materialize-step-session-conversation` — public authoritative conversation materialization entrypoint
  - `split-step-session-conversation` — public authoritative preload/prompt split entrypoint
  - `step-prompt` — public authoritative prompt derivation entrypoint
- `psi.workflow-runtime.step-session-config`
  - `resolve-step-session-config` — public authoritative child-session config shaping entrypoint
- all remaining helpers in both namespaces are private because they only support those top-level role owners and no external consumer needed direct access after the split

Residual ambiguity / follow-up notes:
- `effective-step-def` is duplicated privately in both new owners. That duplication is intentional for now: it avoids introducing a third helper owner for a tiny lookup and keeps each namespace locally comprehensible.
- review follow-up resolved the earlier public-surface question by making `render-template-contribution` private after code search confirmed there was no external consumer of the step-materialization alias.
- code-shaper follow-up then inlined the now-private wrapper entirely because it added no meaningful local seam once the public-surface question was resolved.

Verification:
- initial split verification:
  - `clj-kondo --lint components/workflow-runtime/src/psi/workflow_runtime/step_materialization.clj components/workflow-runtime/src/psi/workflow_runtime/step_session_config.clj components/agent-session/src/psi/agent_session/context.clj components/agent-session/src/psi/agent_session/psi_tool_workflow.clj components/agent-session/src/psi/agent_session/workflow_execution.clj components/agent-session/test/psi/agent_session/test_support.clj components/workflow-runtime/test/psi/workflow_runtime/step_materialization_test.clj components/workflow-runtime/test/psi/workflow_runtime/step_session_config_test.clj components/workflow-runtime/test/psi/workflow_runtime/ir_runtime_adoption_test.clj components/agent-session/test/psi/agent_session/workflow_step_materialization_test.clj` → clean (`0 errors, 0 warnings`)
  - `clojure -M:test --focus psi.workflow-runtime.step-materialization-test --focus psi.workflow-runtime.step-session-config-test --focus psi.workflow-runtime.ir-runtime-adoption-test --focus psi.agent-session.workflow-step-materialization-test` → green (`13 tests, 39 assertions, 0 failures`)
  - historical note: this initial verification still included the then-existing duplicate `agent-session` lower-proof file
- review follow-up verification:
  - duplicate lower-level `agent-session` helper proof file removed
  - `render-template-contribution` made private in `psi.workflow-runtime.step-materialization`
  - `clj-kondo --lint components/workflow-runtime/src/psi/workflow_runtime/step_materialization.clj components/workflow-runtime/src/psi/workflow_runtime/step_session_config.clj components/workflow-runtime/test/psi/workflow_runtime/step_materialization_test.clj components/workflow-runtime/test/psi/workflow_runtime/step_session_config_test.clj components/workflow-runtime/test/psi/workflow_runtime/ir_runtime_adoption_test.clj components/agent-session/src/psi/agent_session/context.clj components/agent-session/src/psi/agent_session/psi_tool_workflow.clj components/agent-session/src/psi/agent_session/workflow_execution.clj components/agent-session/test/psi/agent_session/test_support.clj` → clean (`0 errors, 0 warnings`)
  - `clojure -M:test --focus psi.workflow-runtime.step-materialization-test --focus psi.workflow-runtime.step-session-config-test --focus psi.workflow-runtime.ir-runtime-adoption-test` → green (`11 tests, 36 assertions, 0 failures`)
- code-shaper follow-up verification:
  - inlined the private `render-template-contribution` wrapper in `psi.workflow-runtime.step-materialization`
- test follow-up verification:
  - added a focused `step-materialization` proof for the non-user-final-message preload branch in `split-step-session-conversation`
  - added a focused `step-session-config` proof for nil `parent-session-id` falling back to the first context session
- test-shaper follow-up verification:
  - reshaped the nil-`parent-session-id` fallback proof to create the second context session through `session/new-session-in!` and assert fallback ordering via `list-context-sessions-fn`, removing the earlier inline low-level session-order mutation

2026-05-07 review note
- review pass: core role split is good and direct-consumer rewiring matches the design
- resolved follow-up 1: removed the duplicate lower-level `agent-session` materialization proof file so authoritative helper proofs live under `workflow-runtime`
- resolved follow-up 2: made `psi.workflow-runtime.step-materialization/render-template-contribution` private after code search confirmed there was no external consumer of the step-materialization alias
- code-shaper note: implementation is now simple, consistent, and locally comprehensible; the last optional micro-cleanup was completed by inlining the former private `render-template-contribution` wrapper in `step-materialization`
- test review note: tests are acceptance-complete and topology-aligned; the brittle low-level setup in the nil-`parent-session-id` fallback proof has been replaced with a more canonical session-creation seam
- test-shaper note: the materialization suite is strong, and the session-config fallback proof is now less storage-coupled and more behavior-focused
- review note: no new implementation defects found; only follow-up is downstream task/doc drift that still names the removed `workflow_step_prep` / `psi.workflow-runtime.step-prep` owner, especially in open task `077`
