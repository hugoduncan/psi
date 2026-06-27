# Steps

## Slice 1 — Metadata and predicate discovery

- [x] Inspect `extensions/auto-session-name/src/extensions/auto_session_name.clj` to identify all turn-finished and checkpoint paths that can increment counts, schedule events, query history, create helpers, set names, or notify.
- [x] Inspect existing auto-session-name tests to identify reusable API stubs/seams for `query-session`, `mutate`, `mutate-session`, notifications, and logs.
- [x] Verify that `query-session` can return session ownership metadata for `:psi.agent-session/parent-session-id`, `:psi.agent-session/workflow-run-id`, `:psi.agent-session/workflow-step-id`, `:psi.agent-session/workflow-attempt-id`, and `:psi.agent-session/workflow-owned?`.
- [x] If any required ownership field is not extension-queryable, add the smallest resolver/API projection needed for that field.
- [x] Add or update focused metadata projection tests if a resolver/API projection is changed.

## Slice 2 — Extension eligibility gate

- [x] Add one extension-local predicate for source-session eligibility that queries the minimal ownership metadata for a session id.
- [x] Make the predicate return false for auto-session-name helper sessions.
- [x] Make the predicate return false when `:parent-session-id` is present.
- [x] Make the predicate return false when any workflow run, step, or attempt ownership id is present.
- [x] Make the predicate return false when `:workflow-owned?` is true.
- [x] Make the predicate return true for root sessions with no workflow ownership metadata and `:workflow-owned?` false.
- [x] Call the predicate from `on-turn-finished` before incrementing turn counts or scheduling checkpoints.
- [x] Call the predicate from `on-checkpoint` before querying entries, querying model context, creating helper child sessions, setting session names, or emitting fallback checkpoint notifications.
- [x] Ensure ineligible checkpoint handling returns without notification/log noise.

## Slice 3 — Regression tests

- [x] Add or update a test proving an eligible top-level session still increments counts and schedules a checkpoint at the configured interval.
- [x] Add a test proving a delegated child session with `:parent-session-id` is ignored on `session_turn_finished` and does not schedule a checkpoint.
- [x] Add a test proving a workflow-owned session with workflow metadata is ignored on `session_turn_finished` and does not schedule a checkpoint.
- [x] Add a test proving a checkpoint for an ineligible delegated/workflow-owned session does not query conversation history.
- [x] Add a test proving a checkpoint for an ineligible delegated/workflow-owned session does not query model context, create helper sessions, run the agent loop, set a session name, close helpers, or notify.
- [x] Add or preserve a test proving auto-session-name helper sessions remain ignored and do not recursively trigger naming work.
- [x] Add or preserve tests proving manual override and stale checkpoint guards still work for eligible top-level sessions.

## Slice 4 — Documentation

- [x] Locate user/developer documentation that describes the auto-session-name extension behavior.
- [x] Update documentation to state that automatic naming applies only to top-level user-interactive sessions.
- [x] Update documentation to explicitly exclude delegated workflow sessions, workflow step sessions, nested workflow sessions, and auto-session-name helper sessions.
- [x] Add a `CHANGELOG.md` entry under `[Unreleased]` if this behavior is user-visible in the project changelog convention.

## Slice 5 — Verification

- [x] Run focused Scry/unit tests for the auto-session-name extension.
- [x] Run focused tests for any resolver/API projection changed outside the extension.
- [x] Run `clj-kondo` on touched Clojure paths.
- [x] Re-read modified files after formatting/tooling to verify the intended content remains coherent.
- [x] Update `implementation.md` with implementation decisions and verification results during the build phase.

## Implementation review follow-up

- [x] Make `eligible-source-session?` conservative when ownership metadata cannot be resolved: an empty/not-found/failed `query-session` result for a source session should be treated as ineligible rather than as a root top-level session, and add a regression test for that no-op path.

## Test review follow-up

- [x] Update the existing positive-path auto-session-name tests so their nullable `query-session` fixtures return authoritative root ownership metadata for `session-ownership-query`; `bb clojure:test:extensions --focus extensions.auto-session-name-test` currently fails 10 pre-existing positive-path tests because the conservative eligibility gate treats their `{}` ownership query fallback as ineligible.
- [x] Make the delegated-child and workflow-owned turn-finished regression fixtures return a complete authoritative ownership map, so the tests prove the parent/workflow fields themselves make an otherwise resolved session ineligible rather than passing because incomplete metadata is conservatively ineligible.
- [x] Make the delegated/workflow checkpoint no-op regression fixture return a complete authoritative ownership map, so the test proves delegated/workflow ownership short-circuits later checkpoint work rather than passing because incomplete metadata is conservatively ineligible.
- [x] Make the helper-session turn-finished regression fixture return complete root ownership metadata for the helper session, so the test proves the helper-session guard itself prevents recursive scheduling rather than passing because ownership metadata is unresolved.
- [x] Add regression coverage proving each workflow ownership id (`:workflow-run-id`, `:workflow-step-id`, and `:workflow-attempt-id`) independently makes an otherwise resolved root session ineligible; current tests only cover all workflow ids together with `:workflow-owned? true`.
- [x] Add regression coverage proving `:workflow-owned? true` independently makes an otherwise resolved root session ineligible; current coverage combines the flag with workflow ids, so it does not isolate the explicit boolean ownership marker required by the design.
- [x] Update `stale-checkpoint-does-not-start-helper-test` so its nullable `query-session` fixture returns authoritative root ownership metadata for the ownership query; it currently returns `{}`, so the checkpoint exits through conservative unresolved-session ineligibility before exercising the eligible-session stale-checkpoint guard required by the design.
- [x] Add checkpoint no-op regression coverage proving `:workflow-owned? true` independently makes an otherwise resolved root session ineligible before history/model/helper/rename/notify work; current checkpoint no-op coverage combines parent id, workflow ids, and `:workflow-owned?`, so it does not isolate the explicit boolean ownership marker at checkpoint time.
- [x] Add checkpoint no-op regression coverage proving each workflow ownership id (`:workflow-run-id`, `:workflow-step-id`, and `:workflow-attempt-id`) independently makes an otherwise resolved root session ineligible before history/model/helper/rename/notify work; current checkpoint coverage only combines all workflow ids with parent id and `:workflow-owned? true`, so checkpoint-time coverage does not isolate the id-specific design requirements.
