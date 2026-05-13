# Plan

Implement this as one small vertical slice that names the workflow child-session creation seam explicitly, validates it at both sides of the boundary, and adds focused proof for both pure contract and real child-session realization.

## Approach

1. **Extract an explicit workflow child-session contract owner**
   - add a lower workflow-runtime namespace for the child-session create request/result contract
   - define the supported request surface, minimal result surface, and any true seam-local normalization/validation helpers
   - keep it workflow-specific rather than general session creation

2. **Validate at the boundary**
   - validate workflow child-session create opts before lower workflow-owned callers cross into `execution-adapter/create-child-session!`
   - validate incoming opts and returned result in the higher session-owned realization path (`create-workflow-child-session!`) or an equivalent single authoritative realization edge
   - make failures local and descriptive

3. **Strengthen caller-specific proofs**
   - extend attempt-session tests so `create-step-attempt-session!` proves exact forwarding of the supported create surface and workflow-attempt invariants
   - extend judge tests so workflow-created judge sessions prove the same seam usage with judge-specific defaults and preloaded-message semantics

4. **Add at least one real integration proof**
   - run a real workflow session-step path through config shaping → create request → create-child seam → realized child session
   - assert persisted child-session state and runtime readiness rather than only checking that a mock received expected args

## Decisions

- **Canonical seam remains the adapter op**: `psi.workflow-runtime.execution-adapter/create-child-session!` is the one lower boundary for workflow-created sessions.
- **Shared seam, distinct callers**: attempt child sessions and judge child sessions share the seam but retain separate higher-level semantics.
- **Minimal result shape**: keep the return contract small unless implementation finds a specific consumer need.
- **Schema/validation over prose-only contract**: tests should rely on one executable contract owner, not only naming conventions.
- **`model-fallback` stays caller-local**: the child-session creation seam covers persisted child-session creation inputs/outputs, while workflow step model-fallback metadata remains attempt/runtime state carried around the seam and reattached to the returned execution-session map.

## Risks

- **Over-centralizing caller semantics**: moving too much behaviour into the contract owner would blur the difference between workflow attempts and judge sessions.
- **Testing only mocks**: seam proof is weaker if no real child session is created and inspected.
- **Field-surface drift**: if the contract omits a field that is already materially part of the boundary, implementation could create a false simplification. Audit the actual current request surface before freezing the contract.
