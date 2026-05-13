# Plan

Implement this as one workflow-scoped vertical slice that rehomes the control surface without reworking lower logprob collection.

## Approach

1. **Inventory the current logprob control shape**
   - identify every current owner of `:logprobs-enabled`, `:top-logprobs`, and `/logprobs`
   - decide whether workflow `:logprobs` maps onto existing persisted `:logprobs-enabled` or whether the persisted key should be renamed coherently

2. **Extend workflow session config**
   - add `:logprobs` and `:top-logprobs` to workflow IR/session config validation
   - extend workflow target-IR/session shaping and `resolve-step-session-config`
   - define default semantics for omitted fields
   - explicit rule: authored `:top-logprobs` is dropped whenever resolved `:logprobs` is false or absent; when enabled with no authored top-N, lower request projection keeps task 140's default top-N of 3

3. **Propagate through workflow child-session creation**
   - widen the workflow child-session creation control surfaces used in task 141’s `:response-mode` propagation path
   - cover every hop on that path: workflow target IR/session spec → `resolve-step-session-config` → workflow attempts → execution adapter/context create-child seam → `:session/create-child` handler → child-session base state
   - persist resolved logprob controls on workflow-owned child sessions
   - do not widen the public non-workflow `psi.extension/create-child-session` mutation surface in this task

4. **Preserve lower request shaping**
   - update `session->request-options` and any adjacent seams so persisted workflow child-session settings reach request building
   - keep request construction, telemetry, journal append, and projection behaviour unchanged except for any necessary key-shape rename

5. **Remove the general command surface**
   - delete `/logprobs` command parsing/dispatch/help/autocomplete
   - remove any now-dead general session mutation or command helper that existed only for slash-command control

6. **Proof**
   - workflow config validation + propagation
   - child-session persistence
   - request-option projection from workflow-owned child session settings
   - command-surface removal regression

## Risks

- Task 140 may have spread `:logprobs-enabled` through more surfaces than just command dispatch and request projection; inventory first to avoid half-renames.
- Removing `/logprobs` may leave dead help/autocomplete/mutation surfaces unless all command-adjacent owners are enumerated.
- If persisted-key renaming broadens the slice too much, prefer a narrow workflow `:logprobs` → persisted `:logprobs-enabled` mapping, but keep only one canonical persisted enabled-state key.
