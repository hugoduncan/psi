# Steps

## Slice 1 — Characterize current delegate-list data paths and failure

- [x] Locate the current `delegate list`, delegate run/start, continue, remove, background-job registry, and canonical workflow run code paths.
- [x] Add or identify a focused test that installs or starts an active same-session delegate workflow background job plus canonical workflow run and proves current `delegate list` omits it or would return an empty active-run section.
- [x] Record in `implementation.md` the authoritative read/write surfaces found for delegate background jobs and canonical workflow runs.

## Slice 2 — Extract delegate-list projection

- [x] Create a pure delegate-list projection function that accepts invoking session id, canonical workflow runs, and background jobs.
- [x] Resolve the projection input-shape ambiguity: specify whether the pure delegate-list projection accepts canonical unqualified background-job maps, namespaced `:psi.background-job/*` query maps, or both, and put normalization on exactly one side of the projection/caller boundary.
- [x] Filter visible jobs to same-session `tool-name = "delegate"`, `job-kind = :workflow`, and `workflow-ext-path = "built-in:workflow"`.
- [x] Ignore same-session jobs with different `tool-name` or non-workflow `job-kind` as outside the workflow-delegate list contract.
- [x] Return an actionable projection error for same-session delegate workflow jobs with missing, blank, or foreign `workflow-ext-path`.
- [x] Return an actionable projection error for non-terminal eligible jobs with missing, nil, non-string, or blank `workflow-id`.
- [x] Hide retained terminal eligible jobs with missing, nil, non-string, or blank `workflow-id` as non-manageable history.
- [x] Join eligible jobs to canonical workflow runs by `workflow-id` / `run-id` and use the canonical run as the management identity.
- [x] Return an actionable projection error when a non-terminal eligible job points at a missing canonical workflow run.
- [x] Hide terminal-only eligible jobs whose canonical workflow run no longer exists.
- [x] De-duplicate eligible jobs by canonical `workflow-id` into at most one row per run.
- [x] Prefer the single non-terminal job as representative when duplicate jobs contain exactly one non-terminal job.
- [x] Return an actionable duplicate-job error when duplicate jobs for a workflow id contain more than one non-terminal job.
- [x] Select the representative terminal job for terminal-only duplicates by newest `completed-at`, then `completed-seq`, then `job-seq`, then lexicographic `job-id`.
- [x] Sort final rows newest-first by representative job `started-at`, then `job-seq`, then `job-id`, then canonical workflow `run-id`.
- [x] Include row fields for canonical run id, workflow definition/source id, canonical workflow status, delegate/background status, and useful timestamps/labels available from either source.

## Slice 3 — Wire projection into delegate list output

- [x] Update `delegate-list` to require the invoking/current session id when projecting visible delegate runs.
- [x] Update `delegate-list` to surface an actionable tool error if the background-job query surface is missing, unavailable, unreadable, or returns no query-shaped result.
- [x] Update `delegate-list` to avoid falling back to global canonical workflow runs when background-job visibility cannot be read.
- [x] Update delegate list rendering to show only projected visible delegate rows in the active-runs section.
- [x] Render canonical workflow status as the primary run/manageability status.
- [x] Render delegate/background status separately when available, including `:timed-out` and wrapper `:completed` for blocked canonical runs.
- [x] Preserve the ordinary empty-list result only when the projection succeeds and no visible delegate rows exist.

## Slice 4 — Make attempt identity and blocked status coherent

- [x] Update delegate background-job `tool-call-id` generation so resumed/continued attempts can create a unique attempt-specific background job when retained history may already exist for the same canonical run.
- [x] Preserve canonical `workflow-id` on delegate background jobs as the workflow run id surfaced by `list`, `continue`, and `remove`.
- [x] Update blocked-run async completion handling so a canonical workflow status `:blocked` marks the delegate wrapper/background job terminal `:completed`.
- [x] Add focused coverage that a blocked canonical run is listed with primary status `:blocked`, delegate/background status `:completed`, and remains continuable/removable.
- [x] Add focused coverage that retained terminal history plus one newer non-terminal attempt for the same workflow id lists as one row using the non-terminal background status.

## Slice 5 — Make remove cleanup coherent

- [x] Identify how `delegate remove` can find same-session delegate background jobs for the target canonical run before removal.
- [x] Update `delegate remove` for active/non-terminal listed runs to cancel, remove, mark terminal, or otherwise hide matching background jobs before/with canonical workflow run deletion.
- [x] Make `delegate remove` fail with an actionable tool error and leave the canonical run intact if active background-job cleanup cannot be completed.
- [x] Add focused coverage that removing an active listed run does not leave a later `delegate list` reporting a non-terminal missing-canonical inconsistency.
- [x] Add focused coverage that retained terminal background history for a removed canonical run is hidden after successful remove.

## Slice 6 — Regression and boundary coverage

- [x] Add same-session visibility coverage proving an active delegated run appears in `delegate list` for the invoking session.
- [x] Add unrelated-session boundary coverage proving delegate jobs from other sessions are not listed.
- [x] Add regression coverage for the observed failure mode: active run exists, `delegate list` active-runs output is not empty.
- [x] Add coverage that ids returned by `delegate list` can be used by `delegate continue` when canonical status supports continuation.
- [x] Add coverage that ids returned by `delegate list` can be used by `delegate remove` when the canonical run exists.
- [x] Add malformed same-session non-terminal delegate job coverage for blank/missing workflow ids and missing canonical runs.
- [x] Add retained terminal malformed workflow-id coverage proving such jobs are hidden rather than errors.
- [x] Add deterministic duplicate terminal selection coverage using `completed-at`, `completed-seq`, `job-seq`, and `job-id` tie-breakers.
- [x] Add deterministic final row ordering coverage using `started-at`, `job-seq`, `job-id`, and `run-id` tie-breakers.

## Slice 7 — Docs and coherence pass

- [x] Update README, `doc/`, or CHANGELOG if delegate list output shape or user-visible behavior changes.
- [x] Run focused delegate/workflow/background-job tests affected by the implementation.
- [x] Run targeted `clj-kondo` over changed Clojure source and tests.
- [x] Record verification commands and results in `implementation.md`.

## Implementation review follow-up

- [x] Make delegate background-job query handling reject nil/non-collection/non-shaped `:psi.agent-session/background-jobs` payloads for both list and remove, surfacing an actionable tool error instead of treating them as an empty job set.
- [x] Make blocked-run `delegate continue` terminalize/clean up its newly started delegate background job and inflight tracking when `psi.workflow/resume-run` returns `:psi.workflow/error` without throwing; add focused coverage for the failed-resume path.
