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

- ambiguity review (design-review round 3, turn 2) added 1 new design step:
  "the session's effective worktree" (Task location) is undefined for
  delegated/workflow sessions, which may run in a different checkout than
  their parent/originating session — the design doesn't say whether task
  creation targets the analyzed session's own worktree or one resolved by
  walking up to an originating/top-level session.

- inconsistency review (design-review round 3, turn 3) added 1 new design
  step: the Dedup decision bounds the closed-tasks list to N=20 for
  output-size reasons but leaves the open-tasks list (checked in the same
  dedup call) unbounded, undermining its own stated boundedness rationale.

## Notes for addressing round-3 design-steps (2 open items)

- Both open items (effective-worktree ambiguity, open-tasks-list bound
  inconsistency) were added in this round-3 batch; neither pre-dates it —
  both are in-scope to resolve together.
- Same resolution pattern as rounds 1–2: these are design.md wording/decision
  edits, not code changes — edit design.md, then check off in
  design-steps.md.
- Keep edits minimal/localized (`AGENTS.md`'s
  `λreq. λspec. localized_change(...) ∧ ¬broad_restructure(spec)`) — resolve
  each in its own Decisions-section sentence rather than restating adjacent
  prose.
- The two items are independent: "effective worktree" resolution (which
  worktree delegated/workflow sessions target) doesn't bear on the
  open-tasks-list bounding question, and vice versa — no shared decision
  blocks either.
- For "effective worktree": resolving it need only state the resolution rule
  (e.g. "the analyzed session's own worktree, not resolved through parent
  sessions" or the reverse) — no new mechanism needs designing, since
  worktree resolution presumably already exists for delegated/workflow
  sessions elsewhere in the runtime (check `app-runtime`/`agent-session`
  worktree resolvers before assuming this needs new design surface).
- For the open-tasks bound: the simplest resolution consistent with the
  existing N=20 closed-list precedent is stating an analogous fixed count
  for open tasks (or explicitly noting open-task count is expected to stay
  small and doesn't need bounding in v1) — avoid inventing a different
  bounding mechanism (e.g. time-window) than the one already chosen for
  closed tasks, to keep the two dedup-list bounding rules symmetric.
- Relevant non-task files already consulted for this round (no need to
  re-read in full): `doc/architecture.md` (adapter/app-runtime ownership,
  no worktree-resolution detail found there), `ramora/META.md`
  (managed-service principle, unrelated to these two items),
  `extensions/context-manager/src/extensions/context_manager.clj`
  (existing helper-session-id atoms, session_turn_finished subscription).

## Design-follow-up completion (design-review batch: round 3, turns 2–3)

- Segment: commits `85b96ce61` (ambiguity round 3, turn 2) and `63ec1df55`
  (inconsistency round 3, turn 3). Baseline: `85b96ce61^` (parent of the
  oldest of the two), confirmed via
  `git diff 85b96ce61^..HEAD -- design-steps.md` showing both items as pure
  additions with no intervening follow-up commit.
- Both unchecked items were in-scope and resolved by editing design.md, then
  checked off in design-steps.md:
  1. Effective worktree: "Task location" decision now states it means the
     analyzed session's own worktree (the checkout it is actually running
     in), even for delegated/workflow sessions — v1 does not walk up to an
     originating/top-level session's worktree.
  2. Open-tasks dedup bound: Dedup decision now states the open-tasks side
     of the list is unbounded in v1 (all open tasks passed), on the
     assumption open-task count stays small enough for the helper session's
     output-size limit; revisit with a bound later if that assumption
     breaks. This chose the "no bound needed in v1" resolution rather than a
     symmetric fixed-count bound, since open-task volume is expected to
     differ in growth pattern from the closed-task history.

- architectural review (design-review round 4, turn 1) — no new architectural
  review feedback. Re-checked against AGENTS.md, ramora/META.md, and
  doc/architecture.md; design.md is unchanged since the round-3 follow-up
  (ctx-guard deferred as deliberate decision, cap=2, effective worktree
  defined, dedup single-helper-session phase, N=20 closed bound, open-tasks
  unbounded rationale) and remains architecturally consistent — no new gap.

- ambiguity review (design-review round 4, turn 2) — no new ambiguity
  feedback. Reused design.md/context from turn 1 (unchanged since round-3
  follow-up); remaining deferred items (exact dedup-matching mechanism,
  ctx-keyed-vs-atom recursion-guard choice) are already explicitly marked as
  planning-stage decisions in design.md's Open questions / Decisions, not
  unresolved ambiguity — no new unclear wording found.

- inconsistency review (design-review round 4, turn 3) — no new
  inconsistency feedback. Reused design.md/context from turns 1–2 (unchanged
  since round-3 follow-up). Checked AC6's generic "capped per analysis run"
  wording against Constraints' concrete "at most 2" figure — not treated as
  a new inconsistency, since AC6/AC7 intentionally refer to whatever value
  Constraints defines rather than restating it, matching the resolution
  already accepted in round 1 (inconsistency turn 3) for the Goal-wording
  fix; no other Decisions/Constraints/AC/Open-questions mismatch found.

## Round 4 outcome (all three review turns — no design-steps added)

- design-steps.md has zero unchecked items after round 4: architectural,
  ambiguity, and inconsistency turns all found design.md unchanged and
  fully consistent since the round-3 follow-up commits. There is no
  design-steps follow-up task to perform for this round — design.md needs
  no further edits before planning/implementation proceeds.
- If a future review round does add new unchecked items, follow the same
  pattern used for rounds 1–3 (see the "Design-follow-up completion"
  entries above): resolve each by editing design.md directly (these have
  all been wording/decision clarifications, not code changes), then check
  the item off in design-steps.md, confirming the batch's baseline commit
  via `git diff <parent-of-oldest-adding-commit>..HEAD -- design-steps.md`
  before starting.
- Remaining explicitly-deferred (not ambiguous) planning-stage decisions to
  carry forward, already stated in design.md itself: (1) ctx-keyed-managed-
  service vs. extension-local `defonce` atom for the recursion-guard state
  (`ramora/META.md` process-scoped-managed-service principle applies), and
  (2) exact duplicate-matching mechanism (model-judged vs. slug similarity).
  Neither needs a design-steps entry — both are already framed in
  design.md's Decisions/Open-questions as planning's job, not design's.
- No new non-task files were read this round beyond what's already listed
  in earlier "Notes for addressing ... design-steps" sections
  (`ramora/META.md`, `AGENTS.md`, `doc/architecture.md`,
  `extensions/context-manager/src/extensions/context_manager.clj`).

- ambiguity review (plan-review session, turn 1) — no ambiguity review
  feedback. Read plan.md/steps.md (steps.md treated read-only) against
  design.md and current `extensions/context_manager.clj` (existing
  `entity-resolution-helper-session-ids`, `default-run-helper`,
  `default-select-model`, `render-history-excerpt`); plan's stated deviations
  from literal reuse (e.g. "bash-tool-less" helper vs. `default-run-helper`'s
  hardcoded `:tool-ids ["bash"]`) are self-consistent adaptations of the
  existing pattern, not unstated ambiguity — no new design-steps added.

- inconsistency review (plan-review session, turn 2) — no inconsistency
  review feedback. Cross-checked plan.md ↔ steps.md ↔ design.md using
  context already loaded from turn 1 (no new re-reads needed): slice
  titles/order, collaborator names (`:select-model`/`:run-helper`/
  `:fetch-history`/`:list-tasks`/`:create-task!`/`:session-info`), the
  `friction-helper-session-ids` atom name, cap=2, and the 120s wall-clock
  budget all match consistently across plan.md and steps.md, and the
  session-exclusion guard in both correctly maps onto design.md's
  own-helper-vs-other-known-helper/infra distinction. No new design-steps
  added.

## Plan-review batch (turns 1–2): no design-steps follow-up needed

- This batch (ambiguity turn 1, inconsistency turn 2) added zero unchecked
  items to design-steps.md — plan.md/steps.md remain fully consistent with
  design.md as-is. There is no follow-up task to perform before
  implementation proceeds; skip the usual "resolve → check off" cycle for
  this batch.
- If a later plan-review round (or the plan review itself is re-run after a
  plan.md/steps.md edit) does add unchecked items, the prior
  design-review resolution pattern applies analogously but targets
  plan.md/steps.md instead of design.md: edit the plan/steps artifact that
  the item concerns, then check the item off in design-steps.md, confirming
  the batch's baseline commit via
  `git diff <parent-of-oldest-adding-commit>..HEAD -- design-steps.md`
  first (same convention used for all prior design-review batches above).
- No new non-task files were needed for this plan-review batch beyond what
  was already read for the design-review rounds (listed in earlier "Notes
  for addressing ... design-steps" sections); `extensions/context-manager/
  src/extensions/context_manager.clj` was re-consulted (not re-read in full)
  only to confirm `default-run-helper`'s hardcoded `:tool-ids ["bash"]`
  doesn't conflict with plan's "bash-tool-less" helper variant.

## Slice 1 implementation (pure core)

- `render-history-excerpt` generalized to 3-arity
  `([history] [history turn-count char-cap])`; 1-arg call preserves prior
  entity-resolution behaviour exactly (`nil` turn-count = no tail
  truncation, `max-history-chars` cap). Friction analyzer will call the
  2-extra-arg form with `friction-history-turn-count` (4) and
  `max-history-chars` — no separate char-cap constant introduced for
  friction; reusing the existing one per plan step 6 ("adapting" not
  duplicating).
- Added under a new "Post-turn tooling-friction analyzer (task 239)"
  section, mirroring the entity-resolution section's pure-core/
  parsing/rendering/orchestration layout: `friction-history-turn-count`
  (4), `render-task-list`, `build-friction-prompt`,
  `parse-friction-output`, `cap-issues`, `render-friction-design-md`.
  `cap-issues` takes its cap as a parameter (no module-level cap
  constant needed yet); the design.md-decided cap value (2) and the
  closed-task dedup-list bound (N=20) will be introduced as constants in
  slices 2/3 where they're first referenced (avoids an unused-private-var
  lint warning for constants with no caller yet).
- `parse-friction-output` block-scanning: an `ISSUE:` line starts a block
  that greedily consumes following lines until the next `ISSUE:` or
  `DUPLICATE:` line (or end of input); this lets `FRICTION/EVIDENCE/
  SUGGESTION:` lines appear in any order within a block (matched via
  `some` + prefix-strip) and tolerates blank lines between fields.
  `parse-friction-block` requires all four of
  slug/title/friction/evidence/suggestion non-blank or the whole block is
  dropped (fail-safe, no partial-issue task).
- `DUPLICATE: <slug> ~ <existing-id>` lines are collected independently of
  ISSUE blocks (order-preserving relative position not required by
  callers — orchestration in slice 3 will just log each duplicate).
- `cap-issues` is a pure, order-preserving `take`/`drop` split — no
  ranking/scoring, matching plan step 8 ("take first 2 parsed issues").
- `render-friction-design-md` produces design.md content only (no
  plan.md/steps.md — that's slice 2's `create-friction-task!` job); the
  auto-generated marker text explicitly names "the context-manager
  post-turn tooling-friction analyzer (task 239)" per design's Constraints
  requirement.
- New test ns:
  `extensions/context-manager/test/extensions/context_manager_friction_parsing_test.clj`
  (mirrors `context_manager_rendering_test.clj` naming style). Full
  `bb test --focus extensions` run: 317 tests, 1256 assertions, 0
  failures (confirms `render-history-excerpt` generalization didn't
  regress existing entity-resolution rendering tests). `clj-kondo` clean
  except two expected "unused private var" warnings for
  `friction-task-cap`/`friction-recent-closed-limit`, which slice 2/3
  will consume.
