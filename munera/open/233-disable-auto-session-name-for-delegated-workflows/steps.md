# Steps

## Slice 1 — Metadata and predicate discovery

- [ ] Inspect `extensions/auto-session-name/src/extensions/auto_session_name.clj` to identify all turn-finished and checkpoint paths that can increment counts, schedule events, query history, create helpers, set names, or notify.
- [ ] Inspect existing auto-session-name tests to identify reusable API stubs/seams for `query-session`, `mutate`, `mutate-session`, notifications, and logs.
- [ ] Verify that `query-session` can return session ownership metadata for `:psi.agent-session/parent-session-id`, `:psi.agent-session/workflow-run-id`, `:psi.agent-session/workflow-step-id`, `:psi.agent-session/workflow-attempt-id`, and `:psi.agent-session/workflow-owned?`.
- [ ] If any required ownership field is not extension-queryable, add the smallest resolver/API projection needed for that field.
- [ ] Add or update focused metadata projection tests if a resolver/API projection is changed.

## Slice 2 — Extension eligibility gate

- [ ] Add one extension-local predicate for source-session eligibility that queries the minimal ownership metadata for a session id.
- [ ] Make the predicate return false for auto-session-name helper sessions.
- [ ] Make the predicate return false when `:parent-session-id` is present.
- [ ] Make the predicate return false when any workflow run, step, or attempt ownership id is present.
- [ ] Make the predicate return false when `:workflow-owned?` is true.
- [ ] Make the predicate return true for root sessions with no workflow ownership metadata and `:workflow-owned?` false.
- [ ] Call the predicate from `on-turn-finished` before incrementing turn counts or scheduling checkpoints.
- [ ] Call the predicate from `on-checkpoint` before querying entries, querying model context, creating helper child sessions, setting session names, or emitting fallback checkpoint notifications.
- [ ] Ensure ineligible checkpoint handling returns without notification/log noise.

## Slice 3 — Regression tests

- [ ] Add or update a test proving an eligible top-level session still increments counts and schedules a checkpoint at the configured interval.
- [ ] Add a test proving a delegated child session with `:parent-session-id` is ignored on `session_turn_finished` and does not schedule a checkpoint.
- [ ] Add a test proving a workflow-owned session with workflow metadata is ignored on `session_turn_finished` and does not schedule a checkpoint.
- [ ] Add a test proving a checkpoint for an ineligible delegated/workflow-owned session does not query conversation history.
- [ ] Add a test proving a checkpoint for an ineligible delegated/workflow-owned session does not query model context, create helper sessions, run the agent loop, set a session name, close helpers, or notify.
- [ ] Add or preserve a test proving auto-session-name helper sessions remain ignored and do not recursively trigger naming work.
- [ ] Add or preserve tests proving manual override and stale checkpoint guards still work for eligible top-level sessions.

## Slice 4 — Documentation

- [ ] Locate user/developer documentation that describes the auto-session-name extension behavior.
- [ ] Update documentation to state that automatic naming applies only to top-level user-interactive sessions.
- [ ] Update documentation to explicitly exclude delegated workflow sessions, workflow step sessions, nested workflow sessions, and auto-session-name helper sessions.
- [ ] Add a `CHANGELOG.md` entry under `[Unreleased]` if this behavior is user-visible in the project changelog convention.

## Slice 5 — Verification

- [ ] Run focused Scry/unit tests for the auto-session-name extension.
- [ ] Run focused tests for any resolver/API projection changed outside the extension.
- [ ] Run `clj-kondo` on touched Clojure paths.
- [ ] Re-read modified files after formatting/tooling to verify the intended content remains coherent.
- [ ] Update `implementation.md` with implementation decisions and verification results during the build phase.
