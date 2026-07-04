- architectural review (design-review turn 1) added 1 new design step: the
  planned recursion-guard atom copies the extension-local `helper-session-ids`
  pattern rather than the ctx-keyed managed-service model documented in
  `ramora/META.md`. Reviewed against AGENTS.md, ramora/META.md, and
  doc/architecture.md; autonomous task-file creation itself (writing
  `munera/open/NNN-slug/design.md` directly, outside `:state*`/dispatch) is
  not flagged — munera task files are git-tracked project artifacts, not
  canonical root state, and the write mechanism is left to planning.
- ambiguity review (design-review turn 2) added 2 new design steps: (1) the
  per-run task-creation cap is stated only as a suggested range ("1–2"), not
  a decided value, despite acceptance criterion 6 requiring a cap; (2) the
  recursion-guard scope ("except the extension's own helper sessions") leaves
  open whether other extensions'/runtime infra helper sessions (e.g.
  entity-resolution helpers) should also be excluded as analysis inputs.
- inconsistency review (design-review turn 3) added 1 new design step: the
  Goal's "create a task for each newly identified issue" contradicts the
  Constraints' per-run cap of 1–2 tasks (AC6). Verified against
  extensions/context-manager source that the design's factual claims (existing
  `session_turn_finished` subscription, `psi.ai.model-selection` helper
  machinery, munera design.md-only task-creation convention, NNN allocation
  rule) all match the current codebase/AGENTS.md — no discrepancies found
  there.

## Design-follow-up completion (design-review batch: turns 1–3)

- Baseline for this follow-up: `545956c44^` (parent of the oldest of the 3
  design-steps-adding commits `545956c44`/`abe5d7066`/`99f29e624`, confirmed
  via `git diff 545956c44^..HEAD -- design-steps.md` showing all 4 items as
  additions with no prior follow-up commit existing).
- All 4 unchecked items were in-scope (added by the batch, still unchecked)
  and were resolved by editing design.md, then checked off in
  design-steps.md:
  1. Recursion-guard state: added an explicit "Recursion-guard state"
     decision requiring planning to choose ctx-keyed-managed-service vs.
     extension-local atom, rather than defaulting silently.
  2. Cap ambiguity: Constraints now states the cap as a decided single
     number (2), removing the "suggest: 1–2" range language.
  3. Helper/infra session scope: Scope-of-sessions decision now explicitly
     excludes other extensions'/runtime's known helper/infra sessions (e.g.
     entity-resolution helpers), not just this analyzer's own helpers.
  4. Goal wording: reworded to "create a Munera task for newly identified
     issues, up to a per-run cap" to match the AC6/Constraints cap.
- Cap value chosen as **2** (upper end of the prior suggested range) —
  future review/planning should treat this as final unless re-opened.

- architectural review (design-review round 2, turn 1) — no new architectural
  review feedback. Re-checked against AGENTS.md, ramora/META.md, and
  doc/architecture.md after the round-1 follow-up edits (ctx-guard decision,
  cap=2, session scope, Goal wording); no new architectural misfit found.
- ambiguity review (design-review round 2, turn 2) added 2 new design steps:
  (1) "recently-closed" duplicate-suppression window is undefined (no time
  bound / count / lookback rule); (2) whether dedup/duplicate-matching runs
  as part of the same bounded helper-session call as friction detection, or
  as a separate helper-session invocation, is unstated — this matters
  because a second helper call would need its own bounding/recursion-guard
  accounting, which the design doesn't currently mention.
- inconsistency review (design-review round 2, turn 3) added 1 new design
  step: the Decisions section's "Scope of sessions" exclusion was broadened
  (round 1 follow-up) to cover other extensions'/runtime's helper/infra
  sessions too, but AC5 and the AC7 test-coverage list still only name the
  analyzer's own-helper-session exclusion/recursion guard — the acceptance
  criteria text lags behind the widened Decisions scope.

## Notes for addressing accumulated design-steps

- All 4 design-steps are design.md edits (wording/decision clarifications),
  not code changes — resolve them by editing design.md itself, then re-check
  coherence with plan.md/steps.md only if those files already exist and
  reference the changed wording.
- Keep edits minimal/localized per `AGENTS.md`'s
  `λreq. λspec. localized_change(...) ∧ ¬broad_restructure(spec)` — don't use
  these steps as an excuse to rewrite unrelated Decisions/Constraints prose.
- Recursion-guard steps (arch step 1, ambiguity step 2) are related: deciding
  the ctx-keyed-vs-atom question may also inform (but should not expand into
  deciding) which other sessions count as "helper/infra" for exclusion
  purposes — keep these as two separate, narrow edits.
- The cap-value ambiguity (ambiguity step 1) and the Goal-wording
  inconsistency (inconsistency step 1) both trace back to the same
  Constraints sentence ("suggest: 1–2"); fixing the cap to one concrete
  number in Constraints and then aligning the Goal sentence's wording to it
  resolves both in one coordinated edit rather than two independent ones.
- Relevant non-task files for context (already consulted, no need to re-read
  in full): `ramora/META.md` (managed-service-vs-extension-atom principle),
  `AGENTS.md` (task-creation/design.md-only convention, NNN allocation rule),
  `extensions/context-manager/src/extensions/context_manager.clj` (existing
  `helper-session-ids`/`entity-resolution-helper-session-ids` atoms and
  `session_turn_finished` subscription this design extends).

## Notes for addressing round-2 design-steps (3 open items)

- Baseline: all 3 open items were added in this round-2 batch (ambiguity
  turn-2 x2, inconsistency turn-3 x1); none pre-date it, so all 3 are
  in-scope to resolve together.
- All 3 remain design.md wording/decision edits, not code changes — same
  resolution pattern as round 1 (edit design.md, then check off in
  design-steps.md).
- The two ambiguity items (recently-closed window; whether dedup runs inside
  the same helper-session call as detection or a separate one) are coupled:
  resolving "one helper session, two phases (detect + dedup-match)" vs.
  "two helper sessions" affects how a recently-closed lookback would be
  implemented/tested, so decide the helper-session-shape question first, then
  state the recently-closed window in terms of that shape (e.g. "closed
  within the current recursion-guard/analysis-session lifetime" vs. an
  absolute time bound) — don't fix the window wording before the shape
  decision or it may need re-wording afterward.
- The inconsistency item (AC5/AC7 not reflecting the broadened
  Scope-of-sessions exclusion) is independent of the two ambiguity items —
  it's a pure sync-up of Acceptance criteria wording to already-decided
  Decisions text; resolve by adding the "other extensions'/runtime's known
  helper/infra sessions" phrase to AC5 and to the AC7 test list, mirroring
  the Decisions section's existing phrasing rather than inventing new
  wording.
- Keep edits minimal/localized (same `AGENTS.md` guidance as round 1) — don't
  use these steps as a reason to restate or reorganize the whole Decisions/
  Acceptance criteria sections.
- No new non-task files needed beyond those already listed above for round 1;
  these 3 items are resolvable from design.md content alone.

## Design-follow-up completion (design-review batch: round 2, turns 2–3)

- Segment: commits `1ae167bca` (ambiguity round 2, turn 2) and `3661627c9`
  (inconsistency round 2, turn 3). Baseline: `1ae167bca^` (parent of the
  oldest of the two), confirmed via
  `git diff 1ae167bca^..HEAD -- design-steps.md` showing all 3 items as pure
  additions.
- All 3 unchecked items were in-scope and resolved by editing design.md,
  then checked off in design-steps.md:
  1. Helper-session shape: Dedup decision now states dedup-matching is a
     second phase of the *same* bounded helper session as friction
     detection (not a separate call), so no second recursion-guard/bounding
     accounting is needed.
  2. Recently-closed window: defined (given the shape decision above) as a
     fixed count — the N=20 most-recently-closed tasks by closure order,
     not a time window — chosen so the list stays boundable within the
     single session's existing output-size limit.
  3. AC5/AC7 sync: both now name exclusion of other known helper/infra
     sessions (entity-resolution, other workflow helpers), matching the
     already-broadened Decisions "Scope of sessions" text.
- N=20 is a planning-stage placeholder chosen for concreteness (not derived
  from a stated constraint) — future review/planning may revise this count
  if it proves wrong in practice.

- architectural review (design-review round 3, turn 1) — no new architectural
  review feedback. Re-checked against AGENTS.md, ramora/META.md, and
  doc/architecture.md; the current design.md (round-2 follow-up state:
  ctx-guard-vs-atom deferred as deliberate planning decision, cap=2 fixed,
  helper/infra session scope broadened, dedup as single-helper-session second
  phase, N=20 fixed recently-closed count) remains architecturally consistent
  — confirmed the existing `entity-resolution-helper-session-ids` atom
  pattern in `extensions/context-manager.clj` this design extends already
  performs the same kind of session-id exclusion check the design describes,
  and file-based `munera/open/NNN-slug/design.md` task creation stays outside
  `:state*`/dispatch (git-tracked project artifact, not canonical root
  state), so no dispatch-boundary violation.
