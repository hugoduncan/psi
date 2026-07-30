# Plan

## Approach

Implement the change as a narrow eligibility gate inside the `auto-session-name` extension. Keep the decision in one local predicate, backed by the existing extension-safe session query surface for session ownership metadata. Apply the predicate in both entry points that can cause naming work:

1. `session_turn_finished` handling, before incrementing per-session turn counts or scheduling checkpoints.
2. checkpoint handling, before querying history, selecting helper models, creating helper sessions, applying names, or emitting fallback notifications.

Use existing session metadata rather than introducing a new interactivity projection unless implementation proves the current query surface cannot expose the needed fields. A source session is eligible only when it is not an auto-session-name helper, has no parent session, has no workflow run/step/attempt metadata, and is not marked workflow-owned. Ineligible checkpoint events should silently no-op.

Preserve existing top-level behavior, stale-checkpoint behavior, manual override behavior, helper-session recursion guard, title inference, and helper cleanup semantics.

Update docs to state that automatic naming applies only to top-level user-interactive sessions and excludes delegated/workflow sessions.

## Key decisions

- Keep eligibility as a single extension-local predicate such as `eligible-source-session?` / `top-level-interactive-session?`.
- Query the smallest session metadata set required: parent session id, workflow run id, workflow step id, workflow attempt id, and workflow-owned flag.
- Do not reach directly into the global atom from the extension.
- Treat query failures or missing authoritative metadata conservatively where possible: avoid scheduling/running naming for sessions that cannot be proven eligible.
- Make ineligible checkpoints silent to avoid workflow/delegate UI noise.

## Risks

- The existing extension query API may not currently project all needed ownership metadata; if so, add the smallest resolver/API projection rather than coupling the extension to workflow internals.
- Eligibility must not accidentally exclude legitimate root user-interactive sessions, including scheduled top-level sessions.
- Tests must avoid model calls and should use existing extension seams for mutation/query observation.
- There is an existing uncommitted `implementation.md` change in this task directory; preserve it and do not include it in the planning commit.

## Slice order

1. **Metadata and predicate discovery** — verify which ownership fields are available through `query-session`; add the minimal projection only if required.
2. **Extension eligibility gate** — add the single eligibility predicate and apply it to turn-finished and checkpoint paths.
3. **Regression tests** — cover eligible top-level behavior, delegated/workflow child turn-finished no-op, delegated/workflow checkpoint no-op, helper-session ignored behavior, and preservation of stale/manual override guards.
4. **Documentation** — update user/developer documentation for top-level-only automatic naming and delegated workflow exclusion.
5. **Verification** — run focused auto-session-name tests and lint for touched paths, then broaden if metadata projection code is changed outside the extension.
