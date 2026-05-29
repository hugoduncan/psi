# Steps

## Slice 1 — retention configuration and canonical helpers

- [ ] Locate the canonical workflow-run terminal transition seam and confirm the authoritative terminal transition time surface used for newest-first retention ordering.
- [ ] Add effective retention-count lookup from `[:config :completed-workflow-run-retention-count]` with default `1` when absent.
- [ ] Add negative-retention validation so configured values below `0` are rejected before cleanup runs.
- [ ] Add helper(s) to identify retained terminal workflow runs for one originating session using statuses `:completed`, `:failed`, and `:cancelled` only.
- [ ] Add helper(s) to order retained terminal workflow runs newest-to-oldest by terminal transition time and split them into kept vs removed runs according to the effective retention count.

## Slice 2 — cleanup execution on terminal transition

- [ ] Wire retention cleanup into the shared workflow-run terminal transition path so it runs whenever a workflow run newly enters a retained terminal status.
- [ ] Ensure retention cleanup is grouped by originating agent session and never considers workflow runs from other parent sessions as candidates.
- [ ] Remove only older retained terminal workflow runs beyond the configured retention count, leaving the newest retained runs intact.
- [ ] Ensure non-terminal workflow runs (`:pending`, `:running`, `:blocked`) are never selected or removed by retention cleanup.

## Slice 3 — linked workflow-owned session subtree cleanup

- [ ] Add helper(s) to derive the authoritative linked session-root set for a removed workflow run as the deduplicated union of non-nil attempt `:execution-session-id` and `:judge-session-id` values.
- [ ] For each removed workflow run, tree-close each linked root session only when that root still exists and is marked `:workflow-owned? true`.
- [ ] Ensure linked-root cleanup skips missing, already-closed, duplicate, or non-workflow-owned session ids without broadening deletion scope.
- [ ] Verify cleanup never closes the originating parent session or sessions belonging to retained or non-terminal workflow runs.

## Slice 4 — focused proof and expectation updates

- [ ] Add a focused test proving the default retention case keeps only the newest retained terminal run after a second retained terminal run completes for the same originating session.
- [ ] Add a focused test proving the removed older retained run's linked workflow-owned session tree or trees are also removed in the default retention case.
- [ ] Add a focused test proving cleanup removes multiple linked execution/judge workflow-owned session roots recorded on the same removed run.
- [ ] Add a focused test proving explicit retention `2` keeps the two newest retained terminal runs for one originating session.
- [ ] Add a focused test proving explicit retention `0` removes a newly terminal retained run immediately and also removes its linked workflow-owned session trees.
- [ ] Add a focused test proving non-terminal runs remain present even when retained terminal runs already exceed the retention count.
- [ ] Add a focused test proving retention cleanup is isolated per originating agent session.
- [ ] Add a focused test proving negative configured retention counts are rejected.
- [ ] Update any affected workflow introspection or listing tests whose current expectations assume historical retained terminal runs or workflow-owned child sessions remain indefinitely.
