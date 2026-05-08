2026-05-07
- Reviewed and tightened `design.md` before implementation planning.
- Resolved the main design ambiguities directly in the task design:
  - chose one canonical effect family: `:persist/session-journal-io` with `:op :append-entry` and `:op :flush-journal`
  - made `:flushed?` transition explicit: only successful flush execution may mark the session flushed
  - brought lifecycle-driven persistence writes, especially fork child-session flushes, into scope
  - made production-policy explicit: dispatch/effect flows must stop using `append-entry-in!` and `persist-journal-in!` as the authoritative IO-performing production seam
  - made failure semantics explicit: in-memory append remains authoritative; failed persistence IO does not roll back memory or advance flush-state
  - strengthened acceptance/testing around effect-layer suppression and flush-state correctness
- Re-read current code to anchor the plan against the actual production seams.
  - confirmed `components/agent-session/src/psi/agent_session/dispatch_effects.clj` currently routes `:persist/journal-append-entry` through `persist/append-entry-in!`, which both appends in memory and triggers direct persistence IO via `persist-journal-in!`
  - confirmed `components/agent-session/src/psi/agent_session/session_lifecycle.clj` fork flow directly calls `journal-store/flush-journal!` to create/write child persisted session files with lineage metadata
  - confirmed `components/session-persistence/src/psi/session_persistence/core.clj` currently mixes pure ownership with direct wrappers and production IO-bearing helpers (`append-entry-in!`, `persist-journal-in!`, `persist-entry!`, `persist-state-entry!`)
- Wrote `plan.md` and `steps.md` so the task now has the canonical munera execution surfaces required before implementation.

2026-05-07 — Code reconnaissance refined the design further.
- Confirmed the task is best framed as a lower-boundary refinement, not a broad caller-facing event redesign.
  - `:session/append-journal-entry` already exists as the stable append event surface
  - prompt-lifecycle handler registration already returns `:persist/journal-append-entry` effects beneath that event
  - the real problem is that the current append executor still calls `persist/append-entry-in!`, which performs combined memory mutation + policy + IO
- This sharpened the recommended target architecture:
  - append memory mutation should move into handler `:root-state-update`
  - lower `session-persistence` should shape a pure append/flush/no-op request from entries + flush-state + materialized metadata
  - explicit `:persist/session-journal-io` should become the only production file-write effect seam
  - the IO executor should perform file writes and apply the post-success flush-state update for flush operations only
- Confirmed that the current append tests will need migration because at least one focused test currently stubs `persist/persist-journal-in!` as the production append boundary; after the refactor, the boundary should instead be the explicit IO effect execution seam.
- Confirmed that fork persistence should remain in scope because `fork-session-in!` currently performs a direct `journal-store/flush-journal!` write outside the dispatch effect boundary.

2026-05-07 — Refined the task steps around the concrete code split.
- Captured the exact lower pure helper split to target in `session-persistence`:
  - production root-state append helper
  - post-success `mark-flushed-root-update`
  - pure append/flush/no-op request shaping helper
- Captured the intended handler/executor ownership split explicitly:
  - handler owns append state mutation and effect declaration
  - executor owns file IO and post-success flush-state update
- Captured the current production seams to retire from the authoritative write path:
  - `append-entry-in!`
  - `persist-journal-in!`
  - related combined helper variants
- Captured test migration requirements so the refined boundary is proven at the new explicit IO seam rather than through stubs on legacy combined helpers.

2026-05-07 — Ambiguity review tightened the operational design.
- Clarified that `persistence-io-request` should be computed from a locally derived would-be post-append `entries` value in the handler, not from post-apply state reads.
- Clarified the meaning of `:flushed?` for this task: it is an initial-full-flush mode flag, not a precise guarantee that every later incremental append succeeded.
- Clarified that retained `:persist/journal-append-*` effects, if any, are compatibility/convenience surfaces only and must no longer be the canonical production file-write seam.
- Clarified that fork persistence should cross the same boundary through a dispatch-owned event/effect path declaring `:persist/session-journal-io`, not through direct lifecycle calls to `execute-effect!`.
- Clarified that executor-side direct state update after successful flush is the accepted first-cut mechanism, using the lower-owned `mark-flushed-root-update` helper.

Implementation guidance for the first cut:
- Prefer the smallest architectural move that actually changes the ownership boundary:
  - introduce a pure request-shaping helper in `session-persistence`
  - move journal append memory mutation into the handler result
  - wire production append/fork flows to explicit `:persist/session-journal-io` execution
  - move the successful-flush state transition to the IO effect boundary
- Avoid broad append-event vocabulary churn unless a minimal compatibility shim is needed.
- If compatibility helpers remain after the first cut, record clearly whether they are compatibility-only, test-only, or still intentionally public but non-authoritative.
- The most important regression risk is silent drift between actual disk writes and in-memory `:flushed?`; keep tests centered on that boundary.
