# Plan

Implement this as a narrow workflow-inheritance correction, not a general session-defaulting redesign.

## Approach

1. **Inventory the authoritative parent-session path**
   - trace how workflow execution entrypoints receive and pass `parent-session-id`
   - identify any create/execute/resume path that can lose the delegating session identity and force `resolve-step-session-config` into the first-context-session fallback

2. **Make the delegating session authoritative**
   - preserve the delegating session id through workflow execution/statechart/session-config resolution
   - ensure workflow child-session session-config resolution prefers that explicit delegating session over any context-session discovery fallback

3. **Preserve precedence semantics**
   - keep explicit workflow-authored step/session overrides winning over inherited values
   - keep existing workflow meta fallback only after delegating-session inheritance, where that fallback is already part of the current design

4. **Keep compatibility fallback narrow**
   - retain first-context-session fallback only for cases where no authoritative parent session id exists at all
   - avoid broad changes to unrelated session creation surfaces

5. **Proof**
   - two-session motivating case: workflow executes from session B while session A exists in context with a different model; child session inherits B
   - explicit workflow model override still wins over the delegating session model
   - any existing nil-parent compatibility path remains coherent

## Task surfaces

- `design-steps.md` is reserved for actionable design/ambiguity-review follow-up items.
- `steps.md` remains the implementation execution checklist; do not mix design-review follow-ups into it.
- `implementation.md` records terse notes when a design-step or implementation step is completed, clarified, or blocked.

## Risks

- workflow execution may have multiple entrypoints (`/delegate`, workflow mutations, psi-tool workflow ops) and only some may currently preserve parent-session-id consistently
- current fallback behaviour may be relied on implicitly in tests that construct workflow runs without a true delegating session; preserve a compatibility fallback for that case
- preference precedence is currently split between workflow step config and workflow-file-meta; tightening parent authority must not accidentally change explicit override ordering
