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

## Slice 2 implementation (task-file creation)

- Extracted the entire post-turn friction analyzer pure-core/task-file
  section out of `extensions/context_manager.clj` into a new
  `extensions.context-manager.friction` namespace
  (`extensions/context-manager/src/extensions/context_manager/friction.clj`).
  Reason: adding slice 2 pushed `context_manager.clj` to 843 lines, over the
  bb.edn `file-length-legacy-max-lines` default ratchet (800); rather than
  adding a legacy-ratchet exception, split into a second namespace (more code
  is still coming in slices 3/4, so this avoids re-hitting the ratchet
  repeatedly). `context_manager.clj` re-exports the friction ns's public fns
  as plain `def` aliases (`build-friction-prompt`, `parse-friction-output`,
  `cap-issues`, `render-friction-design-md`, `allocate-task-id`,
  `next-free-task-id`, `create-friction-task!`, `open-tasks`,
  `recent-closed-tasks`) so existing/slice-1 tests and callers keep using
  `context-manager/...` unchanged. Slice 3's orchestration fn can call either
  the re-exported aliases or `friction/...` directly — no preference yet.
- `allocate-task-id` scans `munera/open/` ∪ `munera/closed/` directory names
  matching `^(\d+)-.+$`, taking `max + 1`, zero-padded to 3 digits (`"001"`
  when nothing exists).
- `create-friction-task!`'s retry-on-collision logic is NOT reachable via
  pre-existing directories alone: `allocate-task-id` always scans the full
  directory listing first, so any directories a test pre-creates are already
  reflected in the computed starting NNN (there's no way to make the initial
  scan "miss" existing dirs). The retry loop only matters for a genuine
  concurrent-writer race (another process creates `NNN-slug` after our scan
  but before our `mkdirs`). To keep this testable without real concurrency,
  the collision/retry/exhaustion algorithm is factored into a pure
  `next-free-task-id [start-n slug taken? max-retries]` (fake `taken?`
  predicate, no filesystem) — `create-friction-task!` calls it with a real
  `.exists`-backed `taken?`. `next-free-task-id` is re-exported too, and is
  the thing slice 2's "retry exhaustion" test actually exercises directly;
  the `create-friction-task!`-level test for that scenario only asserts the
  happy path still works (documented in a test comment — see
  `context_manager_friction_task_files_test.clj`).
- `recent-closed-tasks` git-ordering: `git log --format=%H --name-only
  --diff-filter=A -- munera/closed/`, most-recent commit first (git's
  default `log` order), taking the first `munera/closed/<id>/...` path
  component per commit, `distinct` (keeps first/most-recent occurrence).
  Falls back to `sort` descending by directory name when git exits non-zero
  or the parsed id list is empty (covers both "not a git repo" and "no
  closed tasks yet" cases uniformly).
- `task-title` (shared by `open-tasks`/`recent-closed-tasks`) reads the
  first `# ` heading line of a task's `design.md` for its title, falling
  back to the task id itself when `design.md` is missing or has no heading.
- New test file:
  `extensions/context-manager/test/extensions/context_manager_friction_task_files_test.clj`.
  `bb test --focus extensions`: 322 tests, 1275 assertions, 0 failures
  (up from 317/1256 after slice 1). `clj-kondo` clean on both source files
  and the new test file. `clj-paren-repair` clean (no reformatting needed).
- Full-repo `bb test` still shows pre-existing, unrelated failures in
  `psi.turn-runtime.accumulator-test`/`psi.turn-runtime.core-test`
  (statecharts working-memory/turn-data errors) — unrelated to
  context-manager/friction code, not introduced by this slice (confirmed by
  file/namespace scope; these failures are outside `extensions/`).

## Slice 3 implementation (orchestration)

- Recursion-guard state decision (plan.md decision 1, confirmed at
  implementation): extension-local `defonce friction-helper-session-ids`
  atom, not a ctx-keyed managed service. Rationale recorded in-code: the
  extension API map exposes no ctx to key a managed service on, and both
  pre-existing guards (`helper-session-ids`,
  `entity-resolution-helper-session-ids`) already use the atom pattern.
  Migrating all three atoms to a ctx-keyed managed service (per
  `ramora/META.md`'s process-scoped-managed-service model) is recorded here
  as a candidate follow-up task, not done in this task.
- `friction-analysis` orchestration added directly in
  `extensions/context_manager.clj` (not the `friction` ns) — it needs
  `default-select-model`, `helper-wall-clock-ms`, `blank?`,
  `entity-resolution-helper-session-ids`, and the friction pure-core
  re-exports, all already local to this ns; placed immediately after
  `default-run-helper` (before `entity-resolution-augmentation`) so its
  `default-select-model` reference resolves at compile time.
- `known-helper-session?` combines three signals: membership in
  `friction-helper-session-ids` (own helpers), membership in
  `entity-resolution-helper-session-ids` (task-238 helpers), and
  `known-helper-session-names` (`#{"entity-resolution" "friction-analysis"}`)
  matched against the injected `:session-info` collaborator's
  `:session-name` — the name-based check is the "known helper/infra
  session" backstop design.md's Scope-of-sessions decision calls for
  (e.g. would also catch an entity-resolution helper session this
  extension's own atom didn't happen to have tracked, such as after a
  process restart).
- `default-friction-run-helper` mirrors `default-run-helper`'s
  future-owns-teardown timeout handling exactly, but requests a **no-tools**
  child session (`:tool-ids []`, empty `:tool-names`) per plan.md decision 9
  — the friction helper only reasons over the prompt-embedded excerpt +
  task list, no bash needed.
- `friction-analysis`'s collaborator seam:
  `:select-model`/`:run-helper`/`:fetch-history`/`:session-info`/
  `:list-tasks`/`:create-task!`, matching plan.md decision 10 exactly. Every
  collaborator call (including `session-info`, called before the
  known-helper-session guard) is wrapped in its own `try/catch Throwable`,
  and the whole orchestration body is additionally wrapped in an outer
  `try/catch Throwable` that logs and returns `{:status :no-op :diagnostic
  "error"}` — belt-and-braces so no combination of throwing collaborators
  can escape to the (not-yet-wired) fire-and-forget caller.
- Return shape (new, not in design.md/plan.md verbatim — chosen for
  testability): `{:status :no-op :diagnostic ..}` for every guard/failure
  exit, or `{:status :success :created-task-ids [..] :duplicate-diagnostics
  [{:slug :existing-id} ..] :dropped-count n}` on completion (including the
  zero-issues case, which is `:success` with an empty `:created-task-ids`,
  not `:no-op` — a clean "nothing to report" run is not a failure path).
  The event-subscription wiring (slice 4) will discard this value; it
  exists purely so tests can assert on outcomes without a real model/
  sessions.
- Per-run cap (`friction-task-cap` = 2, a new private constant in this
  slice) is applied via the existing `friction/cap-issues`; dropped count is
  logged as a diagnostic string, not returned as data beyond
  `:dropped-count`.
- Gotcha for future slices: manually inserting a large multi-`try`/`cond`/
  `let` form via a single text edit is easy to get paren-count wrong on
  (this slice needed one extra `)`, caught via a small ad-hoc Python
  paren-balance script since `clj-paren-repair` silently *mis-repaired* an
  unbalanced two-namespace-move edit into different-but-still-broken code
  earlier in this slice, rather than surfacing the imbalance — verify new
  large orchestration-shaped edits by loading the ns under `clojure -M:test
  --focus <ns>` directly, not just by trusting a clean `clj-paren-repair`
  run).
- New test file:
  `extensions/context-manager/test/extensions/context_manager_friction_analysis_test.clj`,
  covering all AC7 cases plus the all-collaborators-throw case. `bb test`-
  equivalent direct run (`clojure -M:test --focus extensions`): 332 tests,
  1297 assertions, 0 failures (up from 322/1275 after slice 2).
  `clj-kondo --lint` clean on all context-manager src + test files.

## Slice 4 implementation (wiring & real collaborators)

- EQL attributes confirmed against `psi.agent-session.resolvers.session`:
  `:psi.agent-session/message-history` (raw agent-core messages, via
  `query-session`) for history; `:psi.agent-session/worktree-path` for
  effective worktree (task location); `:psi.agent-session/session-name`
  for the known-helper-session-name backstop. No dedicated "helper-flag"
  attribute exists — the design's accepted fallback (atom membership +
  session-name matching, already built in slice 3's
  `known-helper-session?`) is what's actually wired; no new fallback logic
  needed for slice 4.
- `default-fetch-history` queries `:psi.agent-session/message-history` and
  converts each raw message to `{:role :snippet}` via a new
  `friction/message-snippet` (joins `:content` entries where
  `:type :text`) — a deliberately minimal, dependency-free extraction (does
  *not* pull in `psi.agent-session.message-text`, which is not on this
  extension's classpath), then reuses the existing
  `render-history-excerpt` with `friction/friction-history-turn-count`
  (now public, was private) and the existing `max-history-chars` cap —
  exactly per plan.md decision 6 ("adapting", not duplicating).
- `default-session-info` queries `:psi.agent-session/worktree-path` +
  `:psi.agent-session/session-name` and shapes them via a new
  `friction/session-info-of` into the `{:worktree-root .. :session-name
  ..}` collaborator contract `friction-analysis` already expected from
  slice 3.
- `:list-tasks`/`:create-task!` default collaborators are now thin inline
  fns built from the existing slice-2 `open-tasks`/`recent-closed-tasks`/
  `create-friction-task!` re-exports — no new wrapper `defn`s were kept
  (removed after being added, to stay under the file-length ratchet; see
  below).
- `:select-model`/`:run-helper` real defaults were already implemented in
  slice 3 (`default-select-model`, `default-friction-run-helper`) — slice 4
  only had to wire them as `friction-analysis`'s actual defaults, which
  slice 3 already did.
- Fire-and-forget wiring: `init`'s `session_turn_finished` handler now
  spawns `(future (try (friction-analysis api payload) (catch Throwable
  ...)))` after its existing synchronous `on-turn-finished` log call. The
  future body has its own outer `catch Throwable` as a second line of
  defence beyond `friction-analysis`'s own internal outer catch — belt and
  braces so no exception shape can ever reach the turn/dispatch path.
  Handler still always returns `nil` (unchanged contract).
- Manifest/permissions: confirmed no change needed. `.psi/extensions.edn`'s
  `psi/context-manager {}` entry declares no `:allowed-events` restriction,
  and neither did the pre-existing entity-resolution augmenter (which
  already uses the same `create-child-session`/`run-agent-loop-in-session`/
  `close-session` mutation ops) — task 239 adds no new manifest surface.
- File-length ratchet: adding slice 4's real collaborators pushed
  `context_manager.clj` to 815 lines (ratchet: 800). Resolved by moving the
  two purely-mechanical extraction helpers (`message-snippet`,
  `session-info-of`) into the `friction` ns (where the rest of task 239's
  pure/support code already lives) and inlining the two trivial
  `:list-tasks`/`:create-task!` default-collaborator wrappers directly at
  their one call site instead of as separate `defn-`s — no behaviour
  change, `context_manager.clj` back to 797 lines.
- Pre-existing handler tests (`context_manager_test.clj`) started failing
  once the fire-and-forget future was wired in: they asserted
  `(last (:log-lines @state))` immediately after a synchronous handler
  call, which raced against the new concurrent friction-analysis future's
  own async diagnostic log line (using the same nullable-api `state`).
  Fixed by changing those assertions from exact-last-line equality to
  `(some #{expected-line} (:log-lines @state))` membership checks — this
  preserves each test's actual intent (verify the synchronous
  on-turn-finished log format) without being racy against the new
  concurrent logging. This is the general pattern any *future* extension
  of `session_turn_finished`'s synchronous logging should also follow if
  it adds another async side effect sharing the same log sink.
- New integration-style test:
  `extensions/context-manager/test/extensions/context_manager_friction_wiring_test.clj`
  — injects a 200ms-sleeping `:query-fn` into a real `create-nullable-extension-api`,
  confirms the `session_turn_finished` handler returns in well under the
  sleep duration (non-blocking proof), then polls (up to ~2s) for the
  friction-analysis future's own async diagnostic log line to appear
  (proof the analysis actually ran on its own thread). Uses the nullable
  API's real (undoctored) `:query-session` default behaviour (returns
  effectively-empty maps for unqueried attributes) rather than injected
  collaborators, since this test is specifically about the *wiring*
  (real collaborators + fire-and-forget), not `friction-analysis`'s
  internal logic (already fully covered by slice 3's collaborator-injected
  tests).
- `bb test`-equivalent direct run (`clojure -M:test --focus extensions`):
  333 tests, 1300 assertions, 0 failures (up from 332/1297 after slice 3).
  `clj-kondo --lint` clean on all context-manager src + test files.
  `clj-paren-repair` clean.
