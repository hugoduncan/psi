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
- materialization owner: `binding-source-value`, `render-template-contribution`, `materialize-step-inputs`, `materialize-step-session-conversation`, `split-step-session-conversation`, `step-prompt`
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
- the remaining `agent-session` proof file was likewise renamed to `workflow_step_materialization_test.clj`

Final public vars:
- `psi.workflow-runtime.step-materialization`
  - `binding-source-value` — remains public because runtime-adoption proof sites intentionally consume the canonical binding-source surface directly
  - `render-template-contribution` — remains public as the authoritative lower exposure of source-resolution-backed template rendering already intentionally consumed through step-preparation surfaces
  - `materialize-step-inputs` — public authoritative materialization entrypoint
  - `materialize-step-session-conversation` — public authoritative conversation materialization entrypoint
  - `split-step-session-conversation` — public authoritative preload/prompt split entrypoint
  - `step-prompt` — public authoritative prompt derivation entrypoint
- `psi.workflow-runtime.step-session-config`
  - `resolve-step-session-config` — public authoritative child-session config shaping entrypoint
- all remaining helpers in both namespaces are private because they only support those top-level role owners and no external consumer needed direct access after the split

Residual ambiguity / follow-up notes:
- `effective-step-def` is duplicated privately in both new owners. That duplication is intentional for now: it avoids introducing a third helper owner for a tiny lookup and keeps each namespace locally comprehensible.
- `render-template-contribution` remains public though it is narrower than the top-level entrypoints; this preserves the current intentionally consumed lower exposure while keeping materialization-authority in one place.

Verification:
- `clj-kondo --lint components/workflow-runtime/src/psi/workflow_runtime/step_materialization.clj components/workflow-runtime/src/psi/workflow_runtime/step_session_config.clj components/agent-session/src/psi/agent_session/context.clj components/agent-session/src/psi/agent_session/psi_tool_workflow.clj components/agent-session/src/psi/agent_session/workflow_execution.clj components/agent-session/test/psi/agent_session/test_support.clj components/workflow-runtime/test/psi/workflow_runtime/step_materialization_test.clj components/workflow-runtime/test/psi/workflow_runtime/step_session_config_test.clj components/workflow-runtime/test/psi/workflow_runtime/ir_runtime_adoption_test.clj components/agent-session/test/psi/agent_session/workflow_step_materialization_test.clj` → clean (`0 errors, 0 warnings`)
- `clojure -M:test --focus psi.workflow-runtime.step-materialization-test --focus psi.workflow-runtime.step-session-config-test --focus psi.workflow-runtime.ir-runtime-adoption-test --focus psi.agent-session.workflow-step-materialization-test` → green (`13 tests, 39 assertions, 0 failures`)
