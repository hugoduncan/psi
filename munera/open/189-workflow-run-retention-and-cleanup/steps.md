# Steps

## Slice 1 — retention configuration and canonical helpers

- [x] Locate the canonical workflow-run terminal transition seam and confirm the authoritative terminal transition time surface used for newest-first retention ordering.
- [x] Add effective retention-count lookup from `[:config :completed-workflow-run-retention-count]` with default `1` when absent.
- [x] Add negative-retention validation so configured values below `0` are rejected before cleanup runs.
- [x] Add helper(s) to identify retained terminal workflow runs for one originating session using statuses `:completed`, `:failed`, and `:cancelled` only.
- [x] Add helper(s) to order retained terminal workflow runs newest-to-oldest by terminal transition time, using canonical workflow run creation order as the deterministic `:finished-at` tie-breaker, and split them into kept vs removed runs according to the effective retention count.

## Slice 2 — cleanup execution on terminal transition

- [x] Wire retention cleanup into the shared workflow-run terminal transition path so it runs whenever a workflow run newly enters a retained terminal status.
- [x] Ensure retention cleanup is grouped by originating agent session and never considers workflow runs from other parent sessions as candidates.
- [x] Remove only older retained terminal workflow runs beyond the configured retention count, leaving the newest retained runs intact.
- [x] Ensure non-terminal workflow runs (`:pending`, `:running`, `:blocked`) are never selected or removed by retention cleanup.

## Slice 3 — linked workflow-owned session subtree cleanup

- [x] Add helper(s) to derive the authoritative linked session-root set for a removed workflow run as the deduplicated union of non-nil attempt `:execution-session-id` and `:judge-session-id` values.
- [x] Add or align a canonical workflow-run linked-session read projection derived from that same authoritative deduplicated execution-plus-judge id set.
- [x] For each removed workflow run, tree-close each linked root session only when that root still exists and is marked `:workflow-owned? true`.
- [x] Ensure linked-root cleanup skips missing, already-closed, duplicate, or non-workflow-owned session ids without broadening deletion scope.
- [x] Verify cleanup never closes the originating parent session or sessions belonging to retained or non-terminal workflow runs.

## Slice 4 — focused proof and expectation updates

- [x] Add a focused test proving the default retention case keeps only the newest retained terminal run after a second retained terminal run completes for the same originating session.
- [x] Add a focused test proving the removed older retained run's linked workflow-owned session tree or trees are also removed in the default retention case.
- [x] Add a focused test proving cleanup removes multiple linked execution/judge workflow-owned session roots recorded on the same removed run.
- [x] Add a focused test proving explicit retention `2` keeps the two newest retained terminal runs for one originating session.
- [x] Add a focused test proving equal `:finished-at` retained terminal runs are ordered deterministically by canonical workflow run creation order, with later-created runs retained ahead of earlier-created runs.
- [x] Add a focused test proving explicit retention `0` removes a newly terminal retained run immediately and also removes its linked workflow-owned session trees.
- [x] Add a focused test proving non-terminal runs remain present even when retained terminal runs already exceed the retention count.
- [x] Add a focused test proving retention cleanup is isolated per originating agent session.
- [x] Add a focused test proving negative configured retention counts are rejected.
- [x] Add or update focused workflow resolver/introspection tests proving the canonical linked-session projection includes both execution and judge linked ids, deduplicated.
- [x] Update any affected workflow introspection or listing tests whose current expectations assume historical retained terminal runs or workflow-owned child sessions remain indefinitely.
- [x] Add focused mutation-level retention tests for canonical execute/resume/cancel terminalization paths so task proof shows retention cleanup is triggered from the public mutation seam, not only via direct `workflow-run-retention/apply-retention-cleanup!` helper calls.
- [x] Add focused proof that the public `list-workflow-runs` mutation surface reflects retention cleanup after execute/resume/cancel terminalization, excluding removed older retained runs and preserving the newest retained run(s).
- [x] Update user-facing workflow docs (`README.md` and/or `doc/workflows.md`) to describe automatic retained-terminal workflow-run cleanup, linked workflow-owned session cleanup, and the new retention configuration surface/behavior.
- [x] Add a `CHANGELOG.md` Unreleased entry for workflow-run retention and cleanup because the task changes user-visible workflow listing/introspection behavior.
