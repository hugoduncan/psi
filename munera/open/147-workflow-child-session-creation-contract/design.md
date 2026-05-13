Goal: Make workflow-owned child-session creation an explicit, well-defined, and well-tested contract, so workflow runtime and agent-session can evolve independently without silent drift in how workflow steps and judge phases create sessions.

## Why

Workflow-created sessions currently cross a named seam at `psi.workflow-runtime.execution-adapter/create-child-session!`, but the contract is still implicit and split across several owners:
- `psi.workflow-step-session-config.core/resolve-step-session-config` shapes step child-session config
- `psi.workflow-runtime.attempts/create-step-attempt-session!` constructs workflow attempt child-session requests
- `psi.agent-session.workflow-judge/execute-judge!` constructs judge child-session requests
- `psi.agent-session.context/create-workflow-child-session!` realizes the request into a real child session through `:session/create-child`

That arrangement works, but the seam is not yet authoritative in one place. The supported request fields, invariants, and expected result shape are inferred from callers and integration behavior rather than stated as a contract. This creates three risks:
1. workflow step sessions and judge sessions can drift from each other while both believe they are using the same creation seam
2. lower workflow-runtime code can accidentally depend on higher session-implementation details that are not actually guaranteed
3. refactors at the session-creation layer can silently break workflow execution without a focused contract test catching the change at the boundary

## Scope

This task defines and proves the workflow child-session creation seam. It does not redesign general session creation.

In scope:
- define an explicit contract for workflow child-session creation at the named seam `psi.workflow-runtime.execution-adapter/create-child-session!`
- cover both workflow step execution sessions and workflow-created judge sessions under that seam
- make the request shape, result shape, and behavioural invariants explicit in one authoritative owner
- validate the contract at the seam or immediately adjacent owners so malformed requests/results fail clearly
- add focused tests for:
  - pure contract validation
  - workflow attempt child-session request forwarding and invariants
  - judge child-session request forwarding and invariants
  - at least one integration path proving authored workflow child-session config materializes into a real runtime-ready child session through the seam

Out of scope:
- redesigning ordinary non-workflow session creation
- merging judge session creation into workflow attempt creation
- changing workflow model-selection semantics, prompt materialization semantics, or transport execution semantics beyond what is required to preserve the child-session creation contract
- broad adapter redesign beyond the minimum needed to make the contract authoritative and testable
- inventing a richer returned handle shape unless implementation finds a concrete consumer need

## Authoritative seam

The canonical lower creation seam for workflow-created sessions is:
- `psi.workflow-runtime.execution-adapter/create-child-session!`

Current higher-level callers remain distinct:
- workflow step execution sessions: `psi.workflow-runtime.attempts/create-step-attempt-session!`
- workflow judge sessions: `psi.agent-session.workflow-judge/execute-judge!`

Delegate steps are explicitly not a third direct session-creation path at this seam. A delegate step creates a nested workflow run; any child sessions inside that delegated run still use the same workflow child-session seam indirectly through their own session steps and judge phases.

## Desired behaviour

### Contract ownership

There is one explicit contract owner for workflow child-session creation, ideally a lower workflow-runtime namespace dedicated to this boundary.

That owner defines:
- request shape
- result shape
- behavioural invariants expected across the boundary
- any normalization rules that are truly part of the seam rather than caller-specific semantics

### Request contract

Workflow child-session creation requests must support the current workflow-owned session behaviours that already cross the seam, including:
- identity / naming
  - `:child-session-id`
  - `:session-name`
- optional session behaviour/config
  - `:system-prompt`
  - `:prompt-mode`
  - `:response-mode`
  - `:tool-defs`
  - `:thinking-level`
  - `:model`
  - `:skills`
  - `:developer-prompt`
  - `:developer-prompt-source`
  - `:preloaded-messages`
  - `:cache-breakpoints`
  - `:prompt-component-selection`
  - `:logprobs`
  - `:top-logprobs`

+ Explicitly out of seam scope:
+ - `:model-fallback`
+
+ `:model-fallback` is currently workflow step execution metadata produced by
+ `psi.workflow-step-session-config.core/resolve-step-session-config` and carried
+ by `psi.workflow-runtime.statechart-runtime` into
+ `psi.workflow-runtime.attempts/create-step-attempt-session!`. It does not cross
+ `execution-adapter/create-child-session!`, is not part of the persisted created
+ child-session state in `psi.agent-session.context/create-workflow-child-session!`,
+ and is instead reattached caller-locally onto the returned `:execution-session`
+ map so later step execution can drive fallback retries. The authoritative seam
+ field list for child-session creation therefore excludes `:model-fallback`
+ intentionally; this task must preserve the existing fallback behaviour while
+ keeping that metadata outside the create-child contract.
- optional workflow linkage
  - `:workflow-run-id`
  - `:workflow-step-id`
  - `:workflow-attempt-id`
  - `:workflow-owned?`

This list is authoritative for the seam after implementation. If implementation discovers one or two additional fields are already part of the true public boundary, the task should include them explicitly rather than leaving them implicit.

### Result contract

The create-child seam should keep a minimal stable result unless there is a strong reason to widen it.

Current expected minimum result:
- `{:psi.agent-session/session-id <child-session-id>}`

Callers may inspect the created child session via the existing adapter/session surfaces after creation rather than requiring a richer result payload.

### Behavioural invariants

At minimum, the seam must guarantee:
1. exactly one child session is created for the requested `:child-session-id`
2. the created child session is linked to the supplied parent session
3. workflow linkage fields survive creation unchanged when supplied
4. requested child-session behaviour fields survive creation in persisted child-session state when supplied
5. preloaded messages become the child session’s initial conversation/runtime state when supplied
6. the child session is runtime-ready after creation, not merely persisted state without initialized runtime handles
7. failures for malformed create requests or malformed create results are local and clear rather than surfacing later as unrelated workflow runtime errors

### Caller-specific semantics

The contract is shared, but caller-specific semantics still belong to the callers:
- `create-step-attempt-session!` owns one-attempt-one-execution-session semantics and workflow attempt linkage requirements
- `execute-judge!` owns judge defaults such as session naming, projected preload messages, and judge-specific session behaviour choices

This task should preserve that split rather than over-centralizing caller behaviour.

## Architectural intent

The preferred shape is:
- a lower contract namespace in workflow-runtime owns the seam schema/validation
- lower workflow-owned callers validate before crossing the seam
- the higher session-owned implementation validates incoming requests and outgoing results at realization time
- focused tests prove both the pure contract and the real integration path

This keeps the seam explicit without changing who owns session realization.

## Constraints

- keep the named seam `psi.workflow-runtime.execution-adapter/create-child-session!` as the canonical workflow child-session boundary
- preserve the distinction between workflow attempt child sessions and judge child sessions
- avoid broadening the contract to unrelated session-creation use cases
- do not widen the adapter result shape without a concrete need
- prefer local validation failures at the seam over delayed failures elsewhere in workflow execution
- keep the contract close to runtime truth: tests should inspect real created child-session state where valuable rather than only mocking the seam

## Acceptance

- there is one authoritative contract owner for workflow child-session creation
- the contract explicitly defines the create request shape and minimal result shape
- the contract is validated at least once on the lower caller side and once on the higher realization side, or implementation documents a tighter equivalent validation placement
- `create-step-attempt-session!` has focused proof that it forwards the supported request surface correctly and preserves workflow attempt invariants
- judge child-session creation has focused proof that it uses the same seam with explicit judge-owned request semantics
- at least one integration test proves a workflow-authored session step creates a real child session through the seam with expected persisted state and runtime initialization
- malformed request/result failures are clear and attributable to the workflow child-session contract boundary
- existing workflow behaviour remains unchanged except for clearer validation and contract ownership

## Likely owners

This task likely touches:
- `components/workflow-runtime/src/psi/workflow_runtime/execution_adapter.clj`
- `components/workflow-runtime/src/psi/workflow_runtime/attempts.clj`
- new lower contract namespace under `components/workflow-runtime/src/psi/workflow_runtime/`
- `components/agent-session/src/psi/agent_session/context.clj`
- `components/agent-session/src/psi/agent_session/workflow_judge.clj`
- relevant tests under:
  - `components/workflow-runtime/test/psi/workflow_runtime/`
  - `components/agent-session/test/psi/agent_session/`

## Explicit non-goals

- generalizing all session creation behind this workflow-specific contract
- adding new workflow features such as extra child-session config fields unless they are already part of the real seam
- redesigning delegate workflow run creation
- changing prompt execution or model fallback behaviour except where a contract test must assert the child session is runtime-ready
