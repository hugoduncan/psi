# Steps

- [ ] Inventory current workflow create/execute/resume paths and record where delegating-session identity is preserved vs lost.
- [ ] Make workflow session-config resolution use the explicit delegating session as the authoritative inheritance source when available.
- [ ] Preserve explicit workflow-authored override precedence over inherited delegating-session preferences.
- [ ] Keep only a narrow compatibility fallback for cases with no authoritative delegating session id.
- [ ] Add focused tests for the two-session motivating case, explicit workflow override precedence, and any remaining nil-parent compatibility path.
- [ ] Verify workflow-owned child sessions inherit the delegating session’s model and adjacent preferences rather than an unrelated context/default session.
