# Implementation notes

## 2026-06-01 — Architecture-fit design review (review-task-architecture)

Reviewed `design.md` for architectural fit only (not ambiguity/inconsistency/
correctness), consulting AGENTS.md, META.md, doc/architecture.md, doc/workflows.md,
and the existing `complexity-reduction-pr.edn`, `task-lifecycle.edn`,
`implement-task.edn`, and `gh-issue-implement.edn` precedents.

Overall: the design fits the project architecture well. Skill + two-step
`:session` → `:delegate` workflow are S1 capability-catalog operations matching
established precedent; no atom bypass, no new dispatch path, no shim/adapter.
The autonomy-vs-collaborative-design tension is explicitly and legitimately
resolved (documented-decision exception; `review-task-design` substitutes for
live collaboration and preserves the `gate(plan.md)` invariant because
`task-lifecycle` reviews the generated design before planning). Worktree
inheritance by the `:delegate` step is verified precedent. Storing `before-*`
baselines as unknown files in the task dir conforms to Munera
`unknown_files → preserve`.

One actionable architectural-fit gap (added to design-steps.md):

- **Step-1 → step-2 handoff mechanism is under-specified against the verified
  workflow grammar's data-flow contract.** `task-lifecycle` sub-workflows read
  input via `{:from :workflow-input :path [:input]}` — i.e. they expect a map
  `{:input "munera/open/NNN-slug"}`. The design narrates intent ("emit only the
  task path on a single line", "delegate `{:input <task-path-from-step-1>}`")
  but does not name the grammar-conformant wiring: the verified precedent
  (`gh-issue-implement.edn`) routes a prior `:session` step's text output into a
  delegate input field via
  `:prompt-string {:type :map :fields {:input {:from {:step "<select>" :yield :text}}}}`.
  Leaving the mechanism implicit invites a non-grammatical handoff, conflicting
  with the `one_way` principle and the design's own elevation of this contract
  to "Verified facts". The design should state that step-2 sources `:input`
  from the step-1 yielded text via the established delegate-yield grammar.

## 2026-06-01 — Architecture-fit follow-up executed (step-1 → step-2 handoff)

Resolved the one architecture-fit design-step. Verified the grammar precedent
directly in `.psi/workflows/gh-issue-implement.edn` (its `implement` and
`review` `:delegate` steps wire `:input` via
`:prompt-string {:type :map :fields {:input {:from {:step "<name>" :yield :text}}}}`)
and `.psi/workflows/task-lifecycle.edn` (first sub-workflow reads
`:input {:from :workflow-input :path [:input]}`, confirming the `{:input "…"}`
map shape). Updated `design.md` in three places:

- **Step 2** now names the grammar-conformant wiring explicitly: step-2 sources
  `:input` from step-1's `:yield :text` via the `:map`/`:fields` form. Because
  step-1 emits only the bare task-path line, its text yield *is* the path
  string, so the delegate receives `{:input "munera/open/NNN-slug"}`. Tied to
  the `gh-issue-implement.edn` precedent and the `one_way` principle.
- **Verified facts** gained a "Step→step delegate-yield handoff" entry citing the
  precedent, and the lifecycle-input-contract entry now cites the
  `task-lifecycle.edn` first sub-workflow read site.
- **Acceptance criteria** task-path-handoff bullet now spells out the concrete
  `:prompt-string` wiring rather than referring abstractly to "the verified
  contract".

Subtlety recorded: the precedent's prior step yields a structured Markdown
report and the consumer takes that whole text as `:input`; here step-1 is
constrained to emit *only* the path line, so the same `:yield :text` mechanism
yields exactly the bare path with no extraction step — grammar-identical, just a
narrower payload. No grammar extension needed. design-step checked.

## 2026-06-01 — Design ambiguity review (pass 1)

Reviewed `design.md` for ambiguities only (not architecture/inconsistency/
correctness). Grounded against live `bb gordian local/complexity/diagnose/gate`
output and the gordian SKILL. Confirmed both lenses emit joinable `ns`/`var`/
`arity` and `local` carries `lcc-total`, `complexity` carries `cc` (recipe
inputs exist). Five new actionable ambiguities (A1–A5; none duplicate the prior
architecture-fit handoff follow-up):

- **A1 — join arity/missing-side handling.** The "fixed recipe" joins on
  `(ns, var, arity)` and computes `gap = lcc-total / max(cc, 1)`, but never
  specifies what happens when a unit appears in only one lens (a `local` unit
  with no matching `cc` row, or vice versa). `max(cc,1)` guards zero/missing cc
  but the design does not say whether an unmatched row is dropped, treated as
  `cc=1` (inflating gap toward false qualification), or excluded. A
  "reproducible, embedded verbatim" recipe must define the unmatched-row rule.

- **A2 — "net lcc-total across all touched units" scope undefined.** Acceptance
  requires net burden across "all touched units" to decrease, but "touched" is
  ambiguous: units in touched *files*, units whose *source changed*, or units
  whose *recomputed `lcc-total` changed* (a refactor can shift `dependency`/
  `working-set` burden into untouched callers when `local` is recomputed
  globally). The acceptance is not objectively checkable until "touched units"
  is defined.

- **A3 — gate flags vs. claimed semantics.** Acceptance + Locked decision 4 say
  `bb gordian gate --baseline before-diagnose.edn` "passes (no new cycles, no
  new high/medium findings)." The live `gate` command only *evaluates* checks;
  the gordian SKILL itself recommends `--fail-on new-cycles,new-high-findings`
  to make those *fail* the gate, and medium is governed by
  `--max-new-medium-findings`. The bare command as written does not enforce the
  stated "no new high/medium" semantics. The design must specify the `--fail-on`
  / `--max-new-medium-findings` flags the generated task uses, or weaken the
  claim.

- **A4 — baseline path vs. worktree cwd.** Baselines are stored in "the task
  directory" (line 98) but the gate at line 174 references the bare relative
  filename `before-diagnose.edn`. Phase 1 runs from the worktree root (cwd),
  where that file does not resolve. How the generated task references the
  baseline path (absolute path, `munera/open/NNN-slug/before-diagnose.edn`, or
  copied into cwd) is unspecified.

- **A5 — before/after comparison mechanism for "decreased".** `before-local.json`
  is captured (line 100) but the acceptance (line 172) only says re-running
  `local --json` shows `lcc-total` "decreased" without naming what baseline
  "decreased" compares against (the stored `before-local.json`, the selector's
  emitted evidence, or a fresh pre-refactor recompute). The objective check is
  underspecified without the named comparison source.

Added A1–A5 as unchecked items to design-steps.md. PASS_STATUS:
ACTIONABLE_FEEDBACK.

## 2026-06-01 — Ambiguity (pass 1) follow-up executed (A1–A5)

Executed the five newly added ambiguity follow-ups (A1–A5) from
`design-steps.md`, all introduced by the pass-1 ambiguity review (commit
`4b28328da`). Grounded every change against live gordian output and CLI help.

- **A1 — join unmatched-row rule.** Confirmed both `local` and `complexity`
  `--json` emit a `units` array carrying `ns`/`var`/`arity` (and `lcc-total` /
  `cc` respectively). Updated Deliverable 1 step 2 to specify an **inner join
  keyed on the `local` side**: a `local` unit with no matching `cc` row is
  **dropped** (never defaulted to `cc=1`, which would inflate `gap`);
  `complexity`-only units are absent (no `lcc-total`). `max(cc, 1)` now
  explicitly guards only the *matched zero-cc* case, not the missing-row case.

- **A2 — "touched units" defined.** Defined "touched units" in the Phase 1
  acceptance as the **metric-derived set** `{u | before(u) ≠ after(u)}` of units
  whose recomputed `lcc-total` changed (not changed files / changed source),
  precisely so globally-recomputed `dependency`/`working-set` shifts into
  untouched callers cannot hide relocated burden. Net check: `Σ after < Σ before`
  over that set.

- **A3 — gate flags.** Verified via `bb gordian gate --help` that `--fail-on`,
  `--max-new-medium-findings`, and `--max-new-high-findings` exist, and the
  gordian SKILL lists gate checks `pc-delta ∧ new-cycles ∧ new-high-findings ∧
  new-medium-findings`. Updated the Phase 1 acceptance and Locked decision 4 to
  run `gate --baseline … --fail-on new-cycles,new-high-findings
  --max-new-medium-findings 0`, since bare `gate --baseline` only *evaluates*
  checks and does not fail on them — making the "no new cycles/high/medium"
  claim actually enforced.

- **A4 — baseline path vs cwd.** Specified that Phase 1 runs from the worktree
  root, so the generated task references baselines by their
  **worktree-root-relative task-dir path** (`munera/open/NNN-slug/before-*.{edn,json}`,
  the task dir being inside the worktree) rather than a bare filename, so
  `gate --baseline` resolves.

- **A5 — comparison source.** Named the stored `before-local.json` (the Step-1
  baseline) as the single authoritative "before" for every "decreased" check,
  excluding selector evidence or fresh recompute.

All A1–A5 are checked in `design-steps.md` (unchecked count 0). The earlier
architecture-fit item predates this pass and remains checked. No `steps.md` /
`plan.md` touched. PASS_STATUS: REVIEW_COMPLETE.

## 2026-06-01 — Design inconsistency review (pass 1)

Reviewed `design.md` for inconsistencies only (not ambiguity/architecture/
correctness). Grounded against live `bb gordian local/complexity/diagnose/gate
--json/--edn/--help`, `.psi/workflows/task-lifecycle.edn`,
`.psi/workflows/gh-issue-implement.edn`, and
`.psi/workflows/implement-task-in-worktree.md`.

Verified consistent (no findings):
- Selector recipe field claims: `local --json` units carry `lcc-total`/`ns`/
  `var`/`arity`/`line`/`end-line`/per-dimension burdens; `complexity --json`
  units carry `cc`/`ns`/`var`/`arity`; both expose a `units` array — join key
  and `gap` inputs all exist as stated.
- `diagnose --edn` → `gate --baseline … --fail-on new-cycles,new-high-findings
  --max-new-medium-findings 0` round-trips and PASSes (exit 0) with the exact
  flags in Phase-1 acceptance + Locked decision 4. Gate check names match.
- Step-1→step-2 delegate-yield handoff: `gh-issue-implement.edn` `:delegate`
  steps and `task-lifecycle.edn` first sub-workflow read site confirm the
  `:prompt-string {:type :map :fields {:input {:from {:step … :yield :text}}}}`
  → `{:from :workflow-input :path [:input]}` chain as described.
- `--sort total` on the selector's `local` call vs bare `local --json` for
  `before-local.json`: NOT an inconsistency — Phase-1 comparison is keyed by
  `(ns, var, arity)`, so sort order is irrelevant to the keyed before/after.
- Naming (`incidental-complexity-finder`, `reduce-incidental-complexity`) is
  consistent across Scope/Deliverables/Locked decisions/Acceptance.

One new actionable inconsistency (added to design-steps.md):

- **I1 — Worktree-inheritance claim contradicts its own cited precedent and
  step-1 handoff.** "Verified facts" states *"a `:delegate` step inherits the
  worktree set by a prior `:session` step's `work-on` call (precedent:
  `implement-task-in-worktree.md`)"* and Step 2 asserts the `task-lifecycle`
  delegate "inherits the worktree established in step 1 (verified behaviour)."
  But the cited precedent shows the OPPOSITE mechanism: `implement-task-in-
  worktree.md` is a *wrapper* whose own `resolve-worktree` `:session` step
  re-extracts a `worktree_path:` field from a structured handoff blob and
  re-calls `work-on` BEFORE delegating to `implement-task` — inheritance happens
  inside the delegated wrapper, driven by an explicit handoff field, not from a
  sibling step in the outer workflow. In `gh-issue-implement.edn` the outer
  workflow reaches that wrapper by passing the `design` step's text yield, which
  carries a `worktree_path:` field. Task 204 instead (a) delegates DIRECTLY to
  `task-lifecycle` (not a worktree-resolving wrapper; its sub-workflows have no
  `resolve-worktree`/`work-on` step and read only `{:input <task-path>}`), and
  (b) constrains step-1 to emit ONLY the bare task path (no `worktree_path:`
  field threaded). So the precedent demonstrates inheritance requires either a
  worktree-resolving wrapper or a `worktree_path:` in the handoff — and the
  design does neither while claiming the inheritance is "verified." The design
  must resolve this: either delegate to a worktree-resolving wrapper (and thread
  `worktree_path:` through step-1's output, contradicting the "emit only the
  task path" constraint), or cite the actual mechanism that makes a fresh
  `:delegate` to `task-lifecycle` run inside step-1's worktree, or weaken the
  "verified behaviour" claim to an open risk. (Note: the prior architecture-fit
  note accepted this as "verified precedent"; this finding corrects that.)

Added I1 as an unchecked item to design-steps.md. PASS_STATUS:
ACTIONABLE_FEEDBACK.

## 2026-06-01 — Inconsistency (pass 1) follow-up executed (I1)

Executed the single newly-added unchecked item (I1) from the pass-1
inconsistency review. (The architecture-fit and A1–A5 items predate this pass
and were already checked.)

**Grounding performed before editing the design:**
- Re-read `gh-issue-implement.edn`: its `implement` step delegates to the
  **`implement-task-in-worktree` wrapper**, NOT directly to `implement-task`.
- Read `implement-task-in-worktree.md`: confirmed the wrapper's first step,
  `resolve-worktree` (`:session`, tools include `work-on`), extracts
  `worktree_path:` from a structured handoff blob and **re-calls `work-on`**
  before sub-delegating to `implement-task`. So worktree continuity is carried
  by (1) an explicit `worktree_path:` handoff field + (2) a worktree-resolving
  wrapper — exactly as the reviewer found; the design's "bare sibling-step
  inheritance, verified behaviour" claim was wrong.
- Read `task-lifecycle.edn`: all five sub-workflows read only
  `{:from :workflow-input :path [:input]}` and none has a `work-on` step, so a
  **direct** `:delegate` to `task-lifecycle` has no worktree-establishing path.
- Read `complexity-reduction-pr.edn`: does select+worktree+refactor+push+PR in a
  single `:session` step — never crosses a `:delegate` boundary post-worktree,
  so it is not a precedent for cross-delegate inheritance either.
- Runtime check: `child-session-state/child-session-base-state*`
  (`child_session_state.clj:131`) unconditionally copies
  `{:worktree-path (:worktree-path parent-sd)}` into child sessions, and
  `:session/create-child` resolves `parent-sd` from the parent session-id. So
  worktree inheritance *is* runtime-supported in principle — but whether a
  *direct* `task-lifecycle` delegate's sub-session chain roots at step-1's
  worktree-establishing session is an untested cross-run-session assumption with
  no workflow precedent.

**Resolution chosen: option (a) — adopt the verified worktree-resolving-wrapper
pattern + correct the false citation.** Picked over (b) "cite the actual direct
mechanism" (unverified, no precedent) and (c) "weaken to open risk" (leaves the
design on an unproven path, violating `one_way`/robustness). The wrapper path is
the only *proven* mechanism, identical to `implement-task-in-worktree` with
`task-lifecycle` substituted for `implement-task`.

**design.md edits (coherence-propagated across all affected sections):**
- **Verified facts → "Worktree ownership"**: rewritten to describe the actual
  verified mechanism (wrapper + `work-on` re-call + threaded `worktree_path:`),
  explicitly correcting the prior false "bare sibling-step inheritance" claim;
  added a runtime note that child sessions do copy `:worktree-path` but that a
  direct delegate relying on it is unverified.
- **Verified facts → "Step→step delegate-yield handoff"**: updated to the
  through-wrapper handoff (step-2 routes the whole handoff blob into the
  wrapper's `resolve-worktree`, which re-yields the bare path to the inner
  `lifecycle` delegate).
- **Step 1**: now emits a *structured handoff* (`worktree_path:` +
  `munera_task_path:`) instead of "only the bare task path".
- **Step 2**: now delegates to a thin `task-lifecycle-in-worktree` wrapper
  (resolve-worktree `:session`+`work-on` → `lifecycle` `:delegate`
  `:target "task-lifecycle"`); worktree continuity is *established* by the
  `work-on` re-call, not assumed.
- **Scope Deliverable 2** + the "stays at two steps" paragraph: reflect the
  wrapper.
- **Acceptance criteria**: handoff-conformance criterion rewritten to the
  worktree-resolving contract; added a criterion that the
  `task-lifecycle-in-worktree` wrapper exists and parses (mirroring
  `implement-task-in-worktree`).
- **Locked decisions**: added decision 11 recording the wrapper-pattern choice
  and the rejected direct-inheritance alternative (explicitly "Resolves I1").

Net new artifact introduced by this resolution: a `task-lifecycle-in-worktree`
wrapper workflow (thin, two-step, structurally identical to the existing
verified `implement-task-in-worktree`). I1 checked in design-steps.md
(unchecked count 0). No `steps.md` / `plan.md` touched. PASS_STATUS:
REVIEW_COMPLETE.

## 2026-06-01 — Plan/steps ambiguity review (pass 1)

Reviewed `plan.md` and `steps.md` for ambiguities only (not the already-locked
design, and not architecture/inconsistency/correctness). Grounded against the
verified `implement-task-in-worktree.md` (3-step: resolve-worktree → implement →
summary), `task-lifecycle.edn` (5 sub-workflows, each reads
`{:from :workflow-input :path [:input]}`), and the design's Deliverable-2 +
"Generated task design" sections. Four new actionable plan/steps ambiguities
(P1–P4; none duplicate the prior design-level architecture/A1–A5/I1 follow-ups):

- **P1 — wrapper `summary` step decision has no deciding criterion.** Slice 2
  (plan grammar anchors + steps "(Decision) … record whether a trailing
  `summary` `:session` step is added … or deliberately omitted") leaves the
  add-vs-omit choice to the builder with **no rule**. The verified precedent
  `implement-task-in-worktree.md` *has* a third `summary` step, and outer step-2
  is the workflow's terminal step — so whether the outer workflow needs a
  user-facing terminal summary is the actual deciding factor, but neither
  plan.md nor steps.md states it. Two reasonable interpretations remain open.

- **P2 — `before-local.json` capture (`local --json`) vs selector
  `local --sort total --json` not reconciled.** steps.md line 8 verifies/uses
  `bb gordian local --sort total --json`; line 75 captures the authoritative
  `before-local.json` with bare `bb gordian local --json` (no `--sort`). The
  steps never state these are intentionally different invocations nor that sort
  is irrelevant to the `(ns,var,arity)`-keyed before/after (the design's
  inconsistency review concluded it is, but that conclusion is not carried into
  steps). A builder cannot tell whether `--sort total` must match for the
  baseline to be valid.

- **P3 — task-id allocation scan root undefined for the `origin/master`
  worktree.** Slice 3 step "allocate next task id, create
  `munera/open/NNN-slug/design.md`" runs after a `work-on` worktree based on
  `origin/master`, while the workflow itself executes from the current checkout
  (which already has 204 + other open tasks). The Munera `alloc → max(NNN over
  open/ ∪ closed/) + 1` rule needs a defined scan root; steps don't say whether
  NNN is allocated by scanning the worktree's `open/ ∪ closed/` (post
  `origin/master`) or the outer checkout — a real collision risk.

- **P4 — task-creation commit location (worktree vs outer checkout)
  unspecified.** Slice 3 "Commit the task creation" item does not state the
  commit happens *inside* the `work-on` worktree branch (off `origin/master`).
  Combined with P3, it is ambiguous whether task-dir creation + commit occur in
  the worktree (so `munera_task_path:` resolves there for the delegated
  lifecycle) or in the outer checkout before handoff. The handoff's
  `munera_task_path:` only resolves for step-2's `resolve-worktree`/`work-on` if
  the task dir was committed on the worktree branch — steps must say so.

Added P1–P4 as unchecked follow-up items to steps.md. PASS_STATUS:
ACTIONABLE_FEEDBACK.

## 2026-06-01 — Plan/steps ambiguity (pass 1) follow-up executed (P1–P4)

Executed the four newly-added unchecked `steps.md` follow-up items (P1–P4) from
the pass-1 plan/steps ambiguity review. All four are plan/steps **specification**
resolutions — the slices they touch are not yet built (no Slice 1–5 items
checked), so no code/test/doc files outside the task artifacts were changed.
Slices 1–5 themselves predate this review pass and were left untouched.

- **P1 — wrapper `summary` step.** Resolved **add** (three-step wrapper). Deciding
  criterion = outer step-2 (the delegate into `task-lifecycle-in-worktree`) is the
  `reduce-incidental-complexity` workflow's **terminal** step, so the workflow
  needs a user-facing terminal summary; the wrapper's `summary` step is where it
  is produced — exactly why `implement-task-in-worktree.md` keeps its third step.
  Verified `implement-task-in-worktree.md` is in fact a **three-step** wrapper
  (resolve-worktree → implement → summary), and corrected plan.md's grammar-anchor
  bullet which had mischaracterized it as "two-step". Propagated: steps.md Slice-2
  creation item (+ explicit `summary` step item), Slice-4 wrapper-shape assertion
  (two-step → three-step), plan.md Slice 2 + Slice 4 descriptions + the
  grammar-anchor note. The design's "thin two-step adapter" framing is recorded as
  superseded by P1 (design.md left untouched — design review already
  REVIEW_COMPLETE; superseding note lives in plan/steps).

- **P2 — two `local` invocations.** Resolved by annotating both sites in steps.md:
  the `--sort total` call (line ~8) is selector-only ranking display; the
  `before-local.json` capture uses **bare** `bb gordian local --json` (no
  `--sort`). Because the Phase-1 before/after comparison is keyed by
  `(ns, var, arity)`, sort order is irrelevant to baseline validity — the two
  invocations are intentionally different and the baseline is valid regardless of
  sort. Carries the design inconsistency-review conclusion into steps.

- **P3 — task-id allocation scan root.** Resolved on the Slice-3
  task-id-allocation step: NNN is allocated by scanning the **worktree's**
  `munera/open/ ∪ munera/closed/` (the `origin/master`-based checkout where
  `work-on` is already active), per Munera `alloc → max(NNN over open/ ∪ closed/)
  + 1` — **not** the outer checkout — avoiding collision with the outer checkout's
  open tasks (e.g. 204 itself).

- **P4 — task-creation commit location.** Resolved on the Slice-3 commit step: the
  task dir is created **and committed on the `work-on` worktree branch** (off
  `origin/master`). `munera_task_path:` resolves for step-2's
  `resolve-worktree`/`work-on` only because allocation, dir creation, and commit
  all happen inside the worktree branch (not the outer checkout).

P1–P4 checked in steps.md. PASS_STATUS: REVIEW_COMPLETE. No code/test/doc outside
task artifacts touched (slices unbuilt). design.md untouched (design review
complete; supersession recorded in plan/steps).

## 2026-06-01 — Plan/steps inconsistency review (pass 1)

Reviewed `plan.md` and `steps.md` for inconsistencies **across the task files**
only (not the already-locked design, not architecture/ambiguity/correctness).
Grounded against `implement-task-in-worktree.md` (confirmed live: three-step
wrapper resolve-worktree → implement → summary, `summary` tools `["read" "bash"]`),
`components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`
(exists), and `doc/workflows.md` + `CHANGELOG.md` (both exist). No prior
plan/steps **inconsistency** pass exists (prior plan/steps pass was ambiguity
P1–P4), so these are net-new.

Verified consistent (no findings):
- Wrapper = three-step, outer = two-step: agreed across plan + steps (no
  two/three-step drift between the files).
- Slice order (skill → wrapper → outer → tests → docs) and dependency-first
  build order: identical in plan Approach/Slice-order and steps headings.
- Step-1 tools `["read" "bash" "edit" "write" "work-on"]` + skills
  `["incidental-complexity-finder" "gordian" "code-shaper"]`, resolve-worktree
  tools `["read" "bash" "work-on"]`, delegate-yield wiring form, gate flags
  (`--fail-on new-cycles,new-high-findings --max-new-medium-findings 0`),
  handoff fields (`worktree_path:` + `munera_task_path:`), wrapper `.md` / outer
  `.edn` file forms, SKILL frontmatter (`name`/`description`/`lambda`): all
  consistent plan↔steps and against design.

Three new actionable inconsistencies (added to steps.md):

- **C1 — Plan Approach "not new test code in `components/`" contradicts plan R4
  + Slice 4 (plan & steps), which add new `components/` test assertions.**
  Plan Approach (line ~9–11): *"Verification is by the existing
  workflow-loader/parse/definition tests plus live loadability, **not new test
  code in `components/`**."* But plan R4 (line ~103) plans *"New assertions for
  the two workflows + skill registration ... slot into the existing test ns"*,
  and **both** plan Slice 4 and steps.md Slice 4 instruct *Extend
  `…/workflow_definitions_test.clj`: assert `reduce-incidental-complexity` and
  `task-lifecycle-in-worktree` parse/load … assert outer two-step shape …
  assert wrapper three-step shape … assert skill registration*. New assertions
  about the two new artifacts **are** new test code in `components/`. A builder
  cannot tell whether Slice 4 should add `components/` test assertions or rely
  only on existing tests + live loadability. Reconcile: either soften the
  Approach line (e.g. "no new production Clojure / no new test **namespace** in
  `components/` — new assertions extend the existing definition-test ns") or
  drop the `components/` test assertions from Slice 4 in favour of pure live
  loadability. The Approach statement and Slice 4 / R4 must agree.

- **C2 — steps.md Slice 2 has two unchecked items both instructing "Add the
  `summary` step".** Slice 2 contains a dedicated *"Add `summary` step (`:type
  :session`, per resolved P1)…"* item **and** a separate *"(Decision —
  resolved, see P1) **Add** a trailing `summary` `:session` step …"* item — two
  unchecked checklist boxes for the same single build action. Post-P1, the
  decision-placeholder item should have become rationale prose (or be merged
  into the dedicated `summary` item), not remain a duplicate actionable
  checkbox. Collapse the two into one `summary`-authoring item (keep the P1
  rationale as a sub-note), so the checklist instructs the step's creation once.

- **C3 — plan.md never names `before-diagnose.edn`; its R3 "reproduce verbatim"
  inventory omits it while the gate it references requires it.** steps.md
  Slice 3 captures **both** baselines (`before-local.json` *and*
  `before-diagnose.edn`) and the Phase-1 gate command depends on
  `before-diagnose.edn`. plan.md mentions only `before-local.json` (lines ~41,
  ~98); R3's list of contract elements to "reproduce verbatim in the … generated
  instructions" names baseline-path resolution, gate flags, and
  `before-local.json`/touched-units — but omits `before-diagnose.edn`, the very
  baseline the gate flags consume. Add `before-diagnose.edn` to plan.md (its
  capture + R3's verbatim-reproduction inventory) so the plan's baseline set
  matches steps.md and the gate acceptance it cites.

Added C1–C3 as unchecked follow-up items to steps.md. No plan.md / design.md /
code / test / doc edits in this pass (review only; resolution deferred to the
follow-up items). PASS_STATUS: ACTIONABLE_FEEDBACK.

## 2026-06-01 — Plan/steps inconsistency (pass 1) follow-up executed (C1–C3)

Executed the three newly-added unchecked `steps.md` follow-up items (C1–C3) from
the pass-1 plan/steps inconsistency review. All three are plan/steps
**specification** reconciliations — the slices they touch are still unbuilt (no
Slice 1–5 items checked), so no code/test/doc files outside the task artifacts
were changed. Slices 1–5 and the C-predating items were left untouched.

- **C1 — Approach vs R4/Slice 4 `components/` test-code contradiction.** Resolved
  by **softening the plan Approach line** (kept the Slice-4 assertions rather than
  dropping them, since R4 + both Slice-4 listings already plan them and the
  assertions are the cheapest objective load/shape guard). Approach now states "no
  new production Clojure and no new test **namespace** in `components/` — the
  Slice-4 assertions for the two workflows + skill registration **extend the
  existing** `workflow_definitions_test.clj` ns, they do not add a new ns or any
  production code." Approach, R4, and Slice 4 (plan + steps) now agree: assertions
  are added, but only as extensions of the existing definition-test ns.

- **C2 — duplicate Slice-2 `summary`-step checkboxes.** Collapsed the dedicated
  "Add `summary` step…" item and the "(Decision — resolved, see P1) **Add** a
  trailing `summary` step…" item into a single "Add a trailing `summary` step…"
  checkbox, keeping the P1 rationale as a sub-note. The checklist now instructs the
  `summary` step's creation exactly once.

- **C3 — plan.md omits `before-diagnose.edn`.** Added `before-diagnose.edn` to
  plan.md in two places: (1) the Key-decisions two-phase-contract bullet now names
  both baselines (`before-local.json` *and* `before-diagnose.edn`, captured in the
  task dir during step-1) and references `gordian gate --baseline
  before-diagnose.edn`; (2) R3's verbatim-reproduction inventory now lists both
  baselines, tagged by the acceptance each feeds (`before-local.json` → A5 lcc
  decrease; `before-diagnose.edn` → A3 gate `--baseline` source). plan.md's
  baseline set now matches steps.md Slice 3 and the gate acceptance it cites.

C1–C3 checked in steps.md. No code/test/doc outside task artifacts touched
(slices unbuilt). design.md untouched (design review complete). PASS_STATUS:
REVIEW_COMPLETE.

## 2026-06-01 — Slice 1 built: incidental-complexity-finder skill

Authored `.psi/skills/incidental-complexity-finder/SKILL.md`. Frontmatter
(`name`/`description`/`lambda`) mirrors sibling skills; body encodes the full
selection methodology per design Deliverable 1:

- **Scope**: single executable unit only; explicit false-positive guard ("high
  cc alone is not a target — essential decision logic").
- **gap rationale**: `gap = lcc-total / max(cc, 1)` discriminates incidental
  (high burden / low-moderate cc) from essential (high cc) complexity.
- **Fixed verbatim join recipe**: a `jq -n --slurpfile loc … --slurpfile cc …`
  snippet, **developed and tested live against this repo** before embedding.
  Inner-joins on the `local` side keyed on `(ns,var,arity)`, computes `gap`,
  applies the `lcc-total ≥ 5.0 ∧ gap ≥ 2.0` filter, ranks by `gap`, prints the
  top-5 with full per-dimension-burden + `findings` evidence.
- **A1 unmatched-row rule**: `select($ccmap[.gap_key] != null)` drops local rows
  with no cc match (never defaults `cc=1`); `max(cc,1)` guards only matched
  zero-cc. Documented explicitly.
- **Qualification filter + tunable thresholds** stated explicitly.
- **Judgment guard** (top-5, incidental categories vs essential algorithm).
- **Evidence emission** incl. coverage hint (sibling `*_test.clj` + var grep).

Live verification:
- Recipe run against `bb gordian local --sort total --json` + `bb gordian
  complexity --json` produced a ranked candidate list — top units e.g.
  `psi.app-runtime/start-tui-runtime!/5` (lcc≈7.03, cc=1, gap≈7.03),
  `psi.main/print-help!/0` (gap≈5.86). Selector produces a target.
- `psi.prompt-assets.skills/load-skills-from-dir ".psi/skills"` returns the skill
  with **zero diagnostics**; name="incidental-complexity-finder", description
  (386 chars) + lambda parsed. Skill registers/loads cleanly.

Verified field shapes (P2/A1 grounding, current run): `local` units carry
`ns`/`var`/`arity`/`lcc-total` + per-dimension burdens (`flow-burden`,
`state-burden`, `shape-burden`, `abstraction-burden`, `dependency-burden`,
`working-set`) + `findings`/`file`/`line`/`end-line`; `complexity` units carry
`cc`. Recipe inputs all present as designed.

No deviations from design. Slices 2–5 remain.

## 2026-06-01 — Slice 2 built: task-lifecycle-in-worktree wrapper (+ D1 deviation)

Authored `.psi/workflows/task-lifecycle-in-worktree.edn` — a three-step wrapper
(`resolve-worktree` `:session`+`work-on` → `lifecycle` `:delegate`
`:target "task-lifecycle"` → `summary` `:session`), structurally identical to the
loadable `review-implementation-in-worktree.edn` precedent.

### D1 — DEVIATION from design/plan: `.edn`, not `.md`-with-EDN-body

design.md + plan.md + steps.md all specified authoring the wrapper as
`.psi/workflows/task-lifecycle-in-worktree.**md**`, mirroring
`implement-task-in-worktree.md` (`.md` with an EDN body + `name`/`description`
frontmatter), and cited that file as a "verified" loadable wrapper.

**That premise is false against the live loader.** Findings (grounded in the
running code, not docs):

- `psi.workflow-loader.parser/parse-markdown-workflow-file` explicitly
  **rejects** any `.md` body that begins with `{` —
  `body-starts-with-edn-map?` → error *"Markdown workflow body must not begin
  with an EDN workflow definition block"*. The `.md` form is reserved for
  single-step prompt workflows (its body becomes prompt contributions).
- Running `load-workflow-definitions "."` over the real `.psi/workflows` dir,
  `implement-task-in-worktree.md` **is itself an error entry** with exactly that
  message — i.e. the cited precedent does not load. (Pre-existing latent repo
  bug; out of scope for this task — noted for a possible follow-up.)
- The actual loadable multi-step-wrapper precedent is
  `review-implementation-in-worktree.**edn**` — a multi-step `.edn` map with
  top-level `:name`/`:description`, covered green by `load-edn-only` in
  `workflow_definitions_test.clj`. It is the same three-step
  resolve-worktree → delegate → summary shape the design wants.

**Resolution:** author the wrapper as `.edn` mirroring
`review-implementation-in-worktree.edn`. This satisfies every *substantive*
design/plan/steps requirement (three-step shape; `resolve-worktree`
`:session`+`work-on` extracting `worktree_path:`/task-path and re-calling
`work-on`; `lifecycle` `:delegate :target "task-lifecycle"` with
`:prompt-string {:type :map :fields {:input {:from {:step "resolve-worktree"
:yield :text}}}}`; trailing `summary` `:session`). Only the *file form* changes
(`.edn` vs `.md`), forced by the loader contract. Per `one_way` /
`λassert (runtime > docs)`, the runtime parser is authoritative over the design's
file-form assumption.

Slice 4 definition tests will assert the wrapper via `load-edn-only` (the
review-implementation-in-worktree pattern), not the `.md` `load-edn-with-md-refs`
path.

Live verification: `clj-paren-repair` Success(1)/Failed(0);
`load-workflow-definitions "."` registers `task-lifecycle-in-worktree` with steps
`[resolve-worktree lifecycle summary]`, types `[:session :delegate :session]`,
lifecycle `:target "task-lifecycle"`, prompt-string
`{:type :map :fields {:input {:from {:step "resolve-worktree" :yield :text}}}}`,
resolve-worktree tools `["read" "bash" "work-on"]`; no load errors for it.

Implication for Slice 3: the outer `reduce-incidental-complexity.edn` step-2
delegate `:target` remains `"task-lifecycle-in-worktree"` (name unchanged); only
the wrapper's backing file extension changed. No other slice content is affected.

## 2026-06-01 — Slice 3 built: reduce-incidental-complexity outer workflow

Authored `.psi/workflows/reduce-incidental-complexity.edn` — the two-step outer
orchestration:

- **step-1 `select-and-create` (`:session`)**: tools `["read" "bash" "edit"
  "write" "work-on"]`, skills `["incidental-complexity-finder" "gordian"
  "code-shaper"]`, `:thinking-level :high`. Prompt (lifted from design Step 1 +
  the "Generated task design" section verbatim for the contract): git fetch
  origin master → apply `incidental-complexity-finder` → early-stop on no target
  → `work-on` worktree off origin/master → allocate NNN from the WORKTREE's
  open/closed (P3) → create `munera/open/NNN-slug/design.md` embedding the full
  two-phase behaviour-preserving contract (Phase 0 char-test gate; Phase 1 A5
  `before-local.json` lcc decrease, A2 metric-derived touched-set net burden, A3
  `gordian gate --baseline … --fail-on new-cycles,new-high-findings
  --max-new-medium-findings 0`, green tests, minimal/local) → capture
  `before-local.json` (bare `local --json`) + `before-diagnose.edn` into the task
  dir with worktree-root-relative baseline paths (A4) → commit on the worktree
  branch (P4) → emit `## Handoff Data` with `worktree_path:` + `munera_task_path:`
  (gh-issue-implement design-step idiom).
- **step-2 `lifecycle-in-worktree` (`:delegate`)**: `:target
  "task-lifecycle-in-worktree"`, `:prompt-string {:type :map :fields {:input
  {:from {:step "select-and-create" :yield :text}}}}` — routes the whole step-1
  handoff blob into the wrapper's resolve-worktree, exactly the verified
  delegate-yield grammar. No push/PR, no workflow-level verification step.

Live verification (`load-workflow-definitions "."`): registers
`reduce-incidental-complexity`, zero errors; step names/types/tools/skills,
delegate target, prompt-string wiring, handoff fields, early-stop, gate flags,
and both baselines all confirmed present (programmatic asserts in
/tmp/verify_outer.clj).

No deviation in Slice 3 beyond D1 (the delegate target name
`task-lifecycle-in-worktree` is unchanged; only that wrapper's backing file is
`.edn`). Slices 4 (definition tests) + 5 (docs) remain.
