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

## 2026-06-01 — Slice 4 built: definition tests

Extended `components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`
(existing ns, no new ns, no production Clojure — per C1 resolution) with three
new deftests:

- `task-lifecycle-in-worktree-test` (via `load-edn-only`): asserts the wrapper
  loads with no errors; three-step `[resolve-worktree lifecycle summary]` shape,
  types `[:session :delegate :session]`; resolve-worktree includes `work-on` +
  `{{input}}` wiring; lifecycle `:delegate :target "task-lifecycle"` with
  `:prompt-string {:type :map :fields {:input {:from {:step "resolve-worktree"
  :yield :text}}}}`; trailing `summary` `:session` present (per P1).
- `reduce-incidental-complexity-test` (via `load-edn-only`): asserts the outer
  workflow loads with no errors; two-step `[select-and-create
  lifecycle-in-worktree]`, types `[:session :delegate]`; step-1 carries `work-on`
  tool + `incidental-complexity-finder` skill; step-2 `:delegate :target
  "task-lifecycle-in-worktree"` with the grammar-conformant `:prompt-string`
  wiring; step-1 prompt emits `worktree_path:`/`munera_task_path:`, the
  early-stop intent (+ "Do NOT create a worktree"), the enforcing gate flags
  `--fail-on new-cycles,new-high-findings --max-new-medium-findings 0`, and both
  baselines (`before-local.json` A5 / `before-diagnose.edn` A3).
- `incidental-complexity-finder-skill-registers-test`: asserts
  `psi.prompt-assets.skills/load-skills-from-dir` discovers the skill (anchored
  on `user.dir`/`.psi/skills`) with a description and zero diagnostics.

Added `[psi.prompt-assets.skills :as skills]` require (prompt-assets already a
workflow-loader dep). The two new `.edn` workflows are verified via the
`load-edn-only` precedent (not `load-edn-with-md-refs`), consistent with D1
(`.edn` form) and the `review-implementation-in-worktree` / `task-lifecycle`
test precedents.

Verification:
- `clojure -M:test --focus psi.workflow-loader.workflow-definitions-test`:
  **14 tests, 192 assertions, 0 failures**.
- `clj-kondo --lint` on the test file: 0 errors, 0 warnings.
- `cljfmt check` on the test file: formatted correctly.

Slice 5 (docs + coherence) remains.

## 2026-06-01 — Slice 5 built: docs + coherence

User-facing docs updated for the new capability:

- `doc/workflows.md`: added an "Incidental-complexity simplification" section
  describing `reduce-incidental-complexity` (the `gap` selection method, the
  `incidental-complexity-finder` skill, the two-step select-and-create →
  lifecycle-in-worktree shape via the `task-lifecycle-in-worktree` wrapper, the
  two-phase behaviour-preserving generated-task contract, the gate flags, and
  the no-push/PR endpoint). `/delegate reduce-incidental-complexity` invocation
  documented.
- `CHANGELOG.md` `[Unreleased] → Added`: entry for the new workflow + skill.

Coherence verified across artifacts:
- Names consistent: `incidental-complexity-finder`, `reduce-incidental-complexity`,
  `task-lifecycle-in-worktree` agree across design.md, SKILL.md, both workflow
  .edn files, doc/workflows.md, and CHANGELOG.
- Thresholds (`lcc-total ≥ 5.0`, `gap ≥ 2.0`) consistent between SKILL.md and
  the workflow step-1 prompt.
- Gate flags (`--fail-on new-cycles,new-high-findings --max-new-medium-findings 0`)
  identical in design.md, reduce-incidental-complexity.edn, and doc/workflows.md.
- Baselines (`before-local.json` / `before-diagnose.edn`) named consistently.

D1 vindicated by docs: `doc/workflows.md` ("Where workflows live") explicitly
lists `implement-task-in-worktree.md` among the deferred-migration `.md`
wrappers that "still begin with legacy EDN workflow maps and are intentionally
tracked … as outstanding migration blockers, not as valid single-step markdown
examples", and states "New authoring should treat `.md` as single-step and
`.edn` as multi-step." Authoring the wrapper as `.edn` is the documented
correct path.

Final verification:
- `clojure -M:test --focus psi.workflow-loader.workflow-definitions-test`:
  **14 tests, 192 assertions, 0 failures**.
- `clj-kondo` / `cljfmt`: clean on the changed Clojure test file (docs are
  markdown — not cljfmt-parseable, expected).

All five slices (1–5) complete. Task implementation done.

## 2026-06-01 — Implementation review (task-implementation-review)

Reviewed the implemented artifacts against `design.md`/`plan.md` for fit,
reusable-pattern adherence, unnecessary abstraction, and structural issues.
Grounded against live runtime (`bb gordian local --json` keys; the join recipe
run end-to-end), `load-workflow-definitions`, the focused definition tests
(**14 tests, 192 assertions, 0 failures**), and the verified precedents
(`review-implementation-in-worktree.edn`, `gh-issue-implement.edn`,
`task-lifecycle.edn`).

Verified sound (no findings):
- **Skill correctness — recipe runs live.** The `incidental-complexity-finder`
  verbatim `jq` join recipe was run against the live repo and produces a
  ranked top-5 (e.g. `psi.app-runtime/start-tui-runtime!/5` gap≈7.03). Every
  key the recipe reads (`ns`/`var`/`arity`/`lcc-total`/`end-line`/`findings`/
  per-dimension burdens) is present in live `bb gordian local --json` output;
  `complexity` units carry `cc`. A1 unmatched-row rule, qualification filter,
  and `max(cc,1)` zero-cc guard are all faithfully encoded.
- **Wrapper mirrors the loadable precedent exactly.** `task-lifecycle-in-worktree.edn`
  is structurally identical to `review-implementation-in-worktree.edn`
  (resolve-worktree `:session`+`work-on` → delegate → summary `:session`),
  with `task-lifecycle` substituted for `review-task-implementation`. No new
  pattern; reuses the verified wrapper shape. D1 (`.edn` over the design's
  `.md`) is correct and runtime-justified — the cited `.md` precedent does not
  load under the live parser; `doc/workflows.md` documents `.edn` as the
  correct multi-step form. `λassert (runtime > docs)` honoured.
- **Outer-step-2 handoff wiring matches `gh-issue-implement.edn`.** Step-2
  `:delegate :target "task-lifecycle-in-worktree"` with
  `:prompt-string {:type :map :fields {:input {:from {:step "select-and-create" :yield :text}}}}`
  and `:context [{:from :workflow-original} {:from {:step "select-and-create" :yield :text}}]`
  is grammar-identical to that workflow's `implement` step. The two-phase
  generated-design contract in step-1's prompt reproduces design's "Generated
  task design" verbatim (gate flags, both baselines, A2/A3/A5, Phase-0 net).
- **No atom bypass / no shim.** S1 capability-catalog artifacts (skill + two
  workflow definitions); no dispatch path, no production Clojure, no adapter.

One actionable finding (added to steps.md):

- **F1 — Early-stop is prompt-only; step-2 still runs on a no-target handoff.**
  The workflow grammar has **no conditional/skip step execution** (verified:
  `compiler.clj` only requires each step carry `:type`; no `:when`/`:skip`).
  So when step-1 early-stops (no qualifying unit → no worktree, no task, a
  handoff with no `worktree_path:`/`munera_task_path:`), step-2's `:delegate`
  to `task-lifecycle-in-worktree` **still executes unconditionally**. The
  wrapper's `resolve-worktree` prompt ("Extract the worktree path and Munera
  task path … Call `work-on` … respond with ONLY the Munera task path") has no
  no-target branch, and the inner `task-lifecycle` delegate would then receive
  an empty/garbage task path. design.md claims "Early stop … drives the
  workflow's early stop", but step-1 cannot stop the workflow — only its own
  emitted text changes. The single-fat-step precedents
  (`complexity-reduction-pr.edn`) sidestep this by never crossing a `:delegate`
  after the no-target decision; this two-step shape reintroduces it. Mitigation
  is prompt-level (the only available mechanism): step-2's prompt and/or the
  wrapper's `resolve-worktree`/`summary` prompts must explicitly handle the
  no-target handoff — detect the absence of `worktree_path:`/`munera_task_path:`
  and short-circuit to a clean "no target this run; nothing to do" report
  rather than calling `work-on`/delegating on empty input. This is the robust,
  `one_way`-consistent fix given no engine-level conditional.

Non-actionable observation (correctly scoped out, recorded for visibility):
- D1 surfaced that `implement-task-in-worktree.md` is a live load **error**
  (the `.md`-begins-with-EDN-map rejection). The implementation correctly
  scoped this out as a pre-existing latent repo bug. No follow-up artifact was
  filed; if desired, a separate task could migrate the remaining `.md` EDN
  wrappers to `.edn`. Out of scope for 204 — not added as a step.

PASS_STATUS: ACTIONABLE_FEEDBACK.

## 2026-06-01 — Implementation review (pass 1) follow-up executed (F1)

Executed the single newly-added unchecked `steps.md` item (F1) from the pass-1
implementation review. The "Contingency" item predates this pass (a non-planned
design-stated fallback, not a review follow-up) and was left untouched.

**Constraint re-confirmed before fixing.** The workflow grammar has no
conditional/skip step execution (compiler only requires each step carry
`:type`), so outer step-2's `:delegate` runs unconditionally; and a `:delegate`
step has no free prompt to branch on — only its `:prompt-string` wiring. The
only available mechanism is therefore prompt-level handling **inside the
wrapper's `:session` steps** (`resolve-worktree`, `summary`), which is exactly
where F1 directed the fix.

**Fix (prompt-level no-target short-circuit), in
`.psi/workflows/task-lifecycle-in-worktree.edn`:**

- `resolve-worktree` prompt: added a no-target branch. If the handoff lacks BOTH
  `worktree_path:` and `munera_task_path:`, it does NOT call `work-on` and yields
  ONLY the sentinel `NO_TARGET`. Otherwise (both present) it behaves as before
  (call `work-on`, yield the bare task path). This stops the empty-input
  `work-on` call the reviewer flagged.
- `summary` prompt: now also sources `resolve-worktree`'s `:yield :text` (added a
  `{:type :source :from {:step "resolve-worktree" :yield :text}}` contribution).
  If that yield is exactly `NO_TARGET`, the summary ignores the `lifecycle` step
  output entirely and reports a clean "no target qualified this run; nothing was
  done" result — no worktree/task/lifecycle — without inspecting nonexistent
  task artifacts. Otherwise it produces the normal lifecycle summary.

Residual (engine-bounded, documented): the `lifecycle` `:delegate` itself still
fires on `NO_TARGET` input because the grammar cannot skip it; its `task-lifecycle`
sub-delegates would read the literal `NO_TARGET` task path and find nothing. The
**user-facing** result is nonetheless correct because the wrapper's terminal
`summary` step is authoritative and overrides that on the `NO_TARGET` signal. A
true skip would require an engine-level conditional (out of scope; no grammar
extension for this task). This is the robust, `one_way`-consistent fix given the
available mechanism, matching F1's prescribed remedy.

**Tests (Slice-4 ns extended, no new ns / no production code):**
`task-lifecycle-in-worktree-test` gained F1 lock assertions — `resolve-worktree`
emits `NO_TARGET` + does not call `work-on` on a no-target handoff; `summary`
detects `NO_TARGET` and sources `resolve-worktree`'s `:yield :text`.

**Docs:** `doc/workflows.md` incidental-complexity section gained a paragraph
explaining the no-conditional-grammar / prompt-level `NO_TARGET` short-circuit,
for coherence with the workflow.

**Verification:**
- `clj-paren-repair .psi/workflows/task-lifecycle-in-worktree.edn`: Success(1)/Failed(0).
- `clojure -M:test --focus psi.workflow-loader.workflow-definitions-test`:
  **14 tests, 196 assertions, 0 failures** (both workflows still load).
- `clj-kondo` + `cljfmt check` on the changed test file: 0 errors, 0 warnings,
  formatted correctly.

F1 checked in steps.md. PASS_STATUS: REVIEW_COMPLETE.

## 2026-06-01 — Implementation review (task-implementation-review, pass 2)

Second implementation-review pass over the now-complete artifacts (Slices 1–5 +
F1 resolved). Re-grounded against live runtime: focused definition tests
(**14 tests, 196 assertions, 0 failures**), `load-workflow-definitions`
(both workflows register cleanly), the skill `jq` join recipe run end-to-end
against live `bb gordian local --sort total --json` + `bb gordian complexity
--json`, and the verified precedents (`review-implementation-in-worktree.edn`,
`gh-issue-implement.edn`).

Re-confirmed sound (no new findings):
- **Wrapper + outer wiring faithful to precedent.** `task-lifecycle-in-worktree.edn`
  is structurally identical to `review-implementation-in-worktree.edn`
  (resolve-worktree `:session`+`work-on` → `lifecycle` `:delegate
  :target "task-lifecycle"` → `summary` `:session`), and the outer step-2
  `:delegate :target "task-lifecycle-in-worktree"` + `:prompt-string {:type :map
  :fields {:input {:from {:step "select-and-create" :yield :text}}}}` is
  grammar-identical to `gh-issue-implement.edn`'s `implement` delegate into
  `implement-task-in-worktree`. The wrapper's `resolve-worktree` template reads
  `{:from :workflow-input :path [:input]}` — the map shape the outer delegate
  supplies. F1's `NO_TARGET` short-circuit is present in both `resolve-worktree`
  and `summary`.
- **Skill recipe runs live** and produces a ranked top-5 (e.g.
  `psi.app-runtime/start-tui-runtime!/5` gap≈7.03). A1 unmatched-row rule,
  qualification filter, and `max(cc,1)` zero-cc guard are faithfully encoded.

One new actionable finding (added to steps.md):

- **F2 — Join key `(ns, var, arity)` is non-unique when `arity` is `null`,
  silently corrupting the cc/gap for those units and breaking the recipe's
  "reproducible, embedded verbatim" guarantee.** The skill's fixed `jq` recipe
  builds `$ccmap` via `from_entries` keyed on
  `(.ns + "/" + .var + "/" + (.arity|tostring))`. Live data shows this key is
  **not unique**: every `defmethod`-style unit is emitted with `arity: null`, so
  e.g. all **51** `psi.agent-session.dispatch-effects/execute-effect!`
  defmethods collapse to the single key
  `psi.agent-session.dispatch-effects/execute-effect!/null` — on **both** the
  `local` and `complexity` sides (verified: 51 null-arity units each side,
  collapsing to 1 distinct key). Consequences:
  - `from_entries` over the cc units keeps only the **last** of the 51 distinct
    cc values for that key (jq last-wins), discarding the other 50. **Which** cc
    survives depends on cc-unit emission order — so `cc`, and therefore `gap`,
    for a null-arity unit is **non-deterministic with respect to source/emit
    order**, directly contradicting the skill's "fixed recipe … so selection is
    reproducible" claim and the A1 grounding ("the join is total over the shared
    key space").
  - Every one of the 51 local `execute-effect!/null` rows then joins to that one
    arbitrary surviving cc, so all share an identical (and likely wrong) `gap`.
  - **Not currently latent-only by luck:** today no null-arity unit qualifies
    (`lcc-total ≥ 5.0 ∧ gap ≥ 2.0`) — verified the qualifying set contains no
    `arity == null` unit — so the live top-5 is unaffected. But the recipe is the
    skill's central reproducibility artifact and the workflow runs it on every
    invocation against evolving code; a future high-burden null-arity defmethod
    would be mis-ranked or falsely qualified/disqualified with a non-reproducible
    `gap`.
  - Mitigation options for the skill recipe: (a) **exclude `null`-arity units**
    from candidacy explicitly (`select(.arity != null)`), documenting that
    arity-aggregated defmethod units are out of scope for unit-level selection
    (consistent with the single-`(ns,var,arity)`-unit scope); or (b) **group cc
    by key and reduce** (e.g. `max`/`sum` cc per key) so the join is total and
    order-independent rather than last-wins; or (c) re-key on something unique
    (e.g. include `line`) if per-method granularity is wanted. Whichever is
    chosen, the recipe must stop relying on last-wins `from_entries` over a
    non-unique key, and the A1 "total over the shared key space" wording should
    be corrected to acknowledge null-arity key collisions. The fix is confined to
    the skill's recipe + its A1/A4 prose; no workflow/test change is forced
    beyond optionally asserting the recipe is deterministic.

Non-actionable observations (no follow-up):
- D1 (the `.md`→`.edn` wrapper-form deviation) remains correct and
  runtime-justified; `implement-task-in-worktree.md` is still a live load error
  but is a pre-existing repo bug, correctly scoped out of 204.
- F1's documented residual (the inner `lifecycle` `:delegate` still fires on a
  `NO_TARGET` input because the grammar has no skip) is engine-bounded and
  correctly mitigated at the authoritative terminal `summary` step; no new
  action.

PASS_STATUS: ACTIONABLE_FEEDBACK.

## 2026-06-01 — Implementation review (pass 2) follow-up executed (F2)

Executed the single newly-added unchecked `steps.md` item (F2) from the pass-2
implementation review. (The "Contingency" item predates this pass — a
non-planned design-stated fallback, not a review follow-up — and was left
untouched.)

**Bug confirmed live before fixing.** Ran `bb gordian complexity --json` and
`bb gordian local --json`:
- 51 `psi.agent-session.dispatch-effects/execute-effect!` defmethods all emit
  `arity: null` on **both** lenses, collapsing to one key
  `…/execute-effect!/null` (verified: `group_by` → `{k: …/execute-effect!/null,
  n: 51}`).
- The old recipe's `from_entries` over that non-unique key is **last-wins**:
  every null-arity `execute-effect!` unit inherited a single `cc` value
  (live: all `cc = 1`), so `cc`/`gap` for any null-arity unit was
  **non-deterministic w.r.t. emit order** — contradicting the SKILL's "fixed
  recipe … reproducible" claim and the A1 "join is total" framing.
- Currently no null-arity unit qualifies (`lcc-total ≥ 5.0` over null-arity
  units = ∅), so the live top-5 was unaffected — but a future high-burden
  null-arity defmethod would be mis-ranked.

**Fix chosen: option (c) — re-key on something unique.** Picked over (a) "exclude
null-arity" (would silently drop legitimate `defmethod` units from unit-level
scope — data loss) and (b) "group cc by key and reduce" (order-independent but
still **aggregates** the 51 distinct defmethods into one bogus unit — wrong
granularity). Both lenses already carry `line` (the unit's start line);
`(ns, var, arity, line)` is **fully unique** on both sides (verified: 0 dups
across 3526 cc units / 3520 local units, `line` always present). Adding `@line`
to the join key makes `from_entries` lossless — each defmethod gets its own
correct `cc` regardless of emit order.

**Verification of determinism:**
- OLD recipe: all 51 `execute-effect!` units → `distinct cc = [1]` (last-wins).
- NEW recipe: each defmethod → `distinct cc = [1..8]` (own correct cc).
- NEW recipe top-5 is **identical** to the documented expected result
  (`start-tui-runtime!/5` gap≈7.03, `print-help!/0` gap≈5.86, …) — no regression
  for arity-bearing units.
- Extracted the verbatim recipe from SKILL.md and ran it through `jq`
  end-to-end: parses (inline `#` comments are valid jq) and emits the correct
  ranked top-5.

**Edits (confined to the skill recipe + its A1 prose, per F2 scope):**
`.psi/skills/incidental-complexity-finder/SKILL.md`:
- Step-2 recipe: `$ccmap`/`gap_key` keys now append `+ "@" + (.line|tostring)`;
  added an inline comment on the `$ccmap` line.
- Step-1 field-shape sentence: notes both lenses carry `line`, used for the
  unique key; the selector-only `--sort` note now cites the
  `(ns, var, arity, line)`-keyed join.
- Step-3 (A1): added a "Why `line` is part of the key (A1 determinism)"
  paragraph explaining the null-arity collision, the last-wins hazard, and how
  `@line` restores a total, deterministic join over the
  `(ns, var, arity, line)` key space — correcting the prior "total over the
  shared key space" framing that implicitly assumed `(ns, var, arity)`
  uniqueness. (No literal "A4 prose" / "total over the shared key space" string
  existed in SKILL.md; F2's reference was the reviewer's paraphrase of the A1
  rule, now corrected.)

Left `(ns, var, arity)` intact where it denotes the **unit's logical identity**
(lambda line 4 `join(ns,var,arity)`; Scope "a `(ns, var, arity)`") — `line` is a
join-key disambiguator, not part of a unit's logical identity. The SKILL prose
makes the distinction explicit.

**Doc coherence:** `doc/workflows.md` join-key mention updated
`(ns, var, arity)` → `(ns, var, arity, line)` with a parenthetical on why
(`line` disambiguates same-named null-arity defmethods → deterministic join).

**No workflow/test change forced** (F2: optional). The recipe is markdown, not
executed by tests; the skill-registration definition test already covers
loadability. Verified the skill still loads and both workflows still register:
- `clojure -M:test --focus psi.workflow-loader.workflow-definitions-test`:
  **14 tests, 196 assertions, 0 failures**.
- `clj-kondo --lint` on the (unchanged) definition test: 0 errors, 0 warnings.
  (SKILL.md / doc/workflows.md are markdown — not cljfmt/clj-kondo-parseable.)

F2 checked in steps.md. PASS_STATUS: REVIEW_COMPLETE.

## 2026-06-01 — Implementation review (pass 3) — task-implementation-review

Reviewed against `design.md`/`plan.md` and the live artifacts
(`.psi/skills/incidental-complexity-finder/SKILL.md`,
`.psi/workflows/task-lifecycle-in-worktree.edn`,
`.psi/workflows/reduce-incidental-complexity.edn`). Verified live:
- The selector recipe runs end-to-end (`bb gordian local/complexity --json` →
  `jq`) and emits the documented top-5 (`start-tui-runtime!/5` gap≈7.03,
  `print-help!/0` gap≈5.86, …); inline `#` comments are valid jq.
- Both workflows load: `workflow-definitions-test` = 14 tests, 196 assertions,
  0 failures.
- F1 (no-target short-circuit) and F2 (selector join re-keyed on
  `(ns, var, arity, line)`) are both genuinely resolved in the artifacts.

**Matches-design / architecture / abstraction:** good. Outer 2-step + 3-step
wrapper match the design; `task-lifecycle-in-worktree` reuses the established
`review-implementation-in-worktree` wrapper pattern (resolve-worktree → delegate
→ summary) — reuse, not a new abstraction. `:thinking-level :high` is established
grammar (8 sibling workflows). No atom bypass, no shim/adapter, no unnecessary
abstraction.

**One actionable coherence gap (F3 — added to steps.md):**

- **F3 — F2's `(ns, var, arity, line)` uniqueness fix did not propagate to the
  generated design contract's A5/A2 keys.** F2 established (and verified live)
  that `(ns, var, arity)` is **non-unique** for null-arity `defmethod` units
  (all 51 `psi.agent-session.dispatch-effects/execute-effect!` defmethods
  collapse to one key) and re-keyed the **selector** recipe on
  `(ns, var, arity, line)` to make `from_entries` lossless/deterministic. But
  the *generated* design contract embedded in
  `reduce-incidental-complexity.edn` (and mirrored in `design.md` line 217)
  still describes the Phase-1 **A5** "target `lcc-total` decreased … (keyed by
  `(ns, var, arity)`)" lookup and the **A2** touched-set identity
  `{u | before(u) != after(u)}` on the same non-unique `(ns, var, arity)` key.
  For a null-arity-defmethod target, the A5 before/after lookup and the A2
  per-unit identity collapse across all 51 defmethods — the exact collision F2
  fixed upstream, left unfixed in the acceptance contract the selector feeds.
  Live-guarded today only by the threshold (max null-arity `lcc-total` = 4.89 <
  5.0, so no null-arity unit currently qualifies), but the threshold is
  explicitly **tunable** (design) and a future high-burden defmethod would
  qualify and then be mis-compared. Fix: propagate F2's keying decision into the
  generated-contract A5/A2 wording in `reduce-incidental-complexity.edn` (and
  the matching `design.md` A5 line) — either key A5/A2 on
  `(ns, var, arity, line)` to match the selector, or state explicitly that
  null-arity units are out of unit-level acceptance scope. Scope is the
  generated-design prose + `design.md`; the definition tests don't assert the
  generated-contract key text, so no test change is forced (optionally assert
  the key string for coherence).

PASS_STATUS: ACTIONABLE_FEEDBACK.

## 2026-06-01 — Review follow-up (pass 3) — F3 resolution

F3 RESOLVED. Propagated F2's `(ns, var, arity, line)` unique-key decision from
the **selector** recipe into the **generated design contract's** acceptance keys
so a future high-burden null-arity `defmethod` target is not mis-compared (the
51 `psi.agent-session.dispatch-effects/execute-effect!` defmethods all share
`(ns, var, arity) = …/execute-effect!/null`; only `line` disambiguates them).

Edited the generated-contract prose in two coherent places:

- `.psi/workflows/reduce-incidental-complexity.edn` (step-7 generated `design.md`
  instructions):
  - **A5** — "target `lcc-total` (keyed by `(ns, var, arity)`) decreased" →
    keyed by `(ns, var, arity, line)`, with an inline note that this is the same
    unique key the selector's join uses and why (`line` disambiguates same-named
    null-arity defmethods).
  - **A2** — touched-set identity `{u | before(u) != after(u)}` now states each
    unit `u` is identified by `(ns, var, arity, line)` (selector's unique join
    key, so null-arity defmethods don't collapse).
  - **P2 note** — "Phase-1 before/after comparison is keyed by `(ns, var, arity)`"
    → `(ns, var, arity, line)` for coherence with A5/A2.
- `munera/open/204-…/design.md` (Phase-1 acceptance, mirroring the EDN):
  - A5 line 217 and A2 touched-set line ~228 updated identically.

Chose option "key A5/A2 on `(ns, var, arity, line)` to match the selector" over
"declare null-arity units out of acceptance scope" — keeping selector and
acceptance keys identical avoids a second key-space rule and matches the SKILL
A1 framing (`@line`-disambiguated unique join key). Unit *logical identity*
`(ns, var, arity)` left intact where it denotes identity, not the comparison key
(consistent with the pass-2 SKILL distinction).

Scope per F3: generated-design prose + `design.md` only. No test change forced
(definition tests don't assert the generated-contract key text);
`doc/workflows.md`'s `(ns, var, arity, line)` join-key mention (from F2) already
coheres — it describes the selector join, not A5/A2, so no further doc edit.

Verification:
- `clj-paren-repair .psi/workflows/reduce-incidental-complexity.edn`: Success
  (1/0) — EDN still parses.
- `clojure -M:test --focus psi.workflow-loader.workflow-definitions-test`:
  **14 tests, 196 assertions, 0 failures** — both workflows still load with the
  edited prompt string.
- No `.clj`/`.cljc` source changed (EDN + markdown only), so no clj-kondo delta.

F3 checked in steps.md. PASS_STATUS: REVIEW_COMPLETE.

## Implementation review (pass 4 — task-implementation-review skill)

Reviewed against the `task-implementation-review` skill (design-match,
architecture-fit, new-vs-reusable patterns, unnecessary abstraction, structural
issues). Verified live: all three artifacts present and loadable; focused
definition tests **14 tests / 196 assertions / 0 failures**; `clj-kondo` clean
on the test ns; the skill's `jq` recipe runs and yields the documented top-5
(`start-tui-runtime!/5` gap≈7.03 … `start-nrepl!/4` gap≈2.01). Confirmed the F2
`@line` key is unique on **both** lenses (3520/3526 units, 0 dups) and loses **no**
local rows in the join (3520/3520 matched on both the `@line` and bare-triple
keys), so the determinism fix is sound and join-lossless. Confirmed the D1
deviation is correct: the loader (`parser.clj:162`) rejects `.md` bodies that
begin with an EDN map, so `implement-task-in-worktree.md` indeed cannot load and
mirroring the loadable `review-implementation-in-worktree.edn` (3-step
resolve→delegate→summary) is the right precedent. F3's `(ns, var, arity, line)`
keying is coherent across the workflow's generated contract (A5/A2) and
`design.md` lines 217/232. `rg` (skill coverage-hint) is available.

- **F4 (new, actionable — design/skill coherence).** `design.md` line 62
  (Deliverable 1, **selector** procedure step 2) still describes the selector
  join as "**Join on `(ns, var, arity)`**" — the pre-F2 key. F2 changed the
  *implemented* skill recipe to join on `(ns, var, arity, line)` (the `@line`
  key) specifically to make null-arity `defmethod` units deterministic, and F3
  propagated that key into the A5/A2 **acceptance** prose (design lines 217/232)
  — but the **selector join description** at line 62 was not updated to match.
  This leaves the design's own description of the selector's join key
  inconsistent with the skill it specifies (SKILL.md §2/§3 join on
  `(ns, var, arity, line)`). Threshold-guarded today (no null-arity unit reaches
  `lcc-total ≥ 5.0`), so behaviour is unaffected, but it is a residual
  source-of-truth coherence gap between `design.md` (spec/intent) and the
  `incidental-complexity-finder` SKILL.md (mechanism). Fix: update `design.md`
  step 2 to read "Join on `(ns, var, arity, line)`" (with a one-clause note that
  `line` disambiguates same-named null-arity `defmethod` units, mirroring the
  A5/A2 lines and the SKILL §3 rationale). `design.md` prose only; no
  workflow/skill/test change forced — the skill recipe is already correct.

### F4 resolution (pass-4 follow-up executed)

Updated `design.md` Deliverable-1 selector procedure **step 2** (line 62) from
"Join on `(ns, var, arity)`" to "Join on `(ns, var, arity, line)`", with a
parenthetical noting that `line` disambiguates same-named null-arity `defmethod`
units sharing `(ns, var, arity)` (the 51 `execute-effect!` defmethods that
collapse to one key without it), and cross-referencing the A5/A2 acceptance and
SKILL.md §2/§3. The selector join (line 62) and the A5/A2 acceptance keys
(lines 220, 235) now share one key string — the design's selector description is
coherent with the `incidental-complexity-finder` SKILL.md recipe it specifies
(SKILL §2 `@line` `$ccmap`/join key, §3 uniqueness rationale).

Scope held to `design.md` prose, exactly as F4 specified: no
workflow/skill/test change (the SKILL recipe already keys on `@line`; F2/F3
already landed). Verified the SKILL §2/§3 cross-reference is accurate
(SKILL.md lines 13/44–47/57–60/83 key on `(ns, var, arity, line)` / `@line`).
The remaining bare `(ns, var, arity)` mentions in `design.md` (lines 64, 222)
are deliberately retained: they denote a unit's **logical identity** (the
collision case that `line` resolves), consistent with the pass-2 SKILL §3
identity-vs-join-key distinction — they are not selector/acceptance keys and
must not change. design(spec)↔SKILL(mechanism) source-of-truth coherence gap
closed. No `.clj` touched → no clj-kondo/cljfmt/test delta.

## Implementation review (pass 5 — task-implementation-review skill)

Reviewed against the `task-implementation-review` skill (design-match,
architecture-fit, new-vs-reusable patterns, unnecessary abstraction, structural
issues). Independently re-verified the live state and the prior passes' fixes:

- **All three artifacts present, load, lint clean.** `clj-kondo --lint .psi` →
  0 errors / 0 warnings. Focused definition tests **14 tests / 196 assertions /
  0 failures**. Both `reduce-incidental-complexity` and
  `task-lifecycle-in-worktree` register (per Slice-4 assertions).
- **Skill recipe runs live and reproduces the documented top-5** (`start-tui-
  runtime!/5` gap≈7.03, `print-help!/0` 5.86, `print-debug-summary!/1` 2.96,
  `adopt-startup-plan-into-session!/5` 2.75, `start-nrepl!/4` 2.01) — selection
  produces a target.
- **F2 determinism claim independently confirmed.** `@line` join key is fully
  unique on **both** lenses (0 duplicate keys across 3520 local / 3526 cc
  units), and the inner join is **lossless** (0 of 3520 local rows dropped). The
  reproducibility guarantee holds; `from_entries` cannot last-wins-collapse.
- **D1 deviation correct.** Wrapper mirrors the loadable
  `review-implementation-in-worktree.edn` (3-step resolve→delegate→summary)
  rather than the non-loading `implement-task-in-worktree.md`; verified the
  wrapper is byte-structurally the same shape with `task-lifecycle` substituted.
- **Design-match / architecture-fit:** S1 capability-catalog artifacts only (no
  production Clojure); no atom bypass, no shim/adapter, `one_way`-conformant
  delegate-yield handoff. Generated two-phase contract (Phase 0 char-test gate +
  Phase 1 A5/A2/A3 acceptance) reproduced verbatim in step-7 and coheres with
  `design.md`. F1 no-target short-circuit, F3/F4 `(ns,var,arity,line)` keying all
  present and coherent across SKILL ↔ workflow ↔ design ↔ doc.
- **No unnecessary abstraction / structural issues:** thin 3-step wrapper, no
  extra orchestration; `:thinking-level :high` and `rg` (coverage-hint) both
  have precedent / are available.

No new actionable issues found. design/plan/implementation reviews (architecture
+ A1–A5 + I1 + C1–C3 + F1–F4) are all resolved.

PASS_STATUS: REVIEW_COMPLETE.

## 2026-06-01 — Test review (task-test-review skill, pass 1)

Reviewed the task's tests against the `task-test-review` skill:
`well_formed(tests) ∧ ∀b∈behaviour(design). ∃t. covers(t,b) ∧ ∀d∈infra_deps.
injectable(d) ∧ nullable(d) ∧ ¬mock(d) ∧ ¬stub(d)`. The task added no production
Clojure and no new test ns; it **extended** `workflow_definitions_test.clj` with
three new deftests — `task-lifecycle-in-worktree-test`,
`reduce-incidental-complexity-test`, and
`incidental-complexity-finder-skill-registers-test`. Ran the focused suite live:
**14 tests, 196 assertions, 0 failures**.

Verified sound (no findings):
- **well_formed.** All three new deftests pass, are structured with named
  `testing` blocks and descriptive failure messages, and assert on **state/
  outputs** (parsed workflow definitions from a real `load-workflow-definitions`
  over real files copied to a temp dir; real `load-skills-from-dir` output) —
  never on interactions. Consistent with the project Test formalism.
- **¬mock / ¬stub of logic-under-test.** No mocks or stubs of the parser/
  compiler/skill-loader. The only `with-redefs` (`global-workflow-dirs`→`[]`,
  `project-workflow-dir`→temp dir) is the **pre-existing shared `with-workflow-dir`
  harness** isolating the filesystem scan root from `~/.psi` — environment
  isolation (nullable-style), not stubbing the code under test; every other
  deftest in the ns uses it. Criterion satisfied.
- **Structural-shape coverage is strong.** The wrapper three-step shape
  (resolve-worktree `:session`+`work-on` → `lifecycle` `:delegate
  :target "task-lifecycle"` → `summary`), the outer two-step shape, the
  `:prompt-string {:type :map :fields {:input {:from {:step … :yield :text}}}}`
  handoff wiring, the F1 `NO_TARGET` short-circuit (both session steps), the
  early-stop intent, the gate flags, and both baseline filenames are all locked
  with exact-value assertions — these design-acceptance behaviours are covered.

Two actionable **coverage** findings (added to steps.md — net-new; no prior pass
was a *test* review):

- **TR1 — the `incidental-complexity-finder` skill's core behaviour is
  untested.** `incidental-complexity-finder-skill-registers-test` asserts only
  discovery + non-empty `:description` + zero diagnostics. The design's *first*
  acceptance criterion (the skill "documents the `gap` method and the
  false-positive guard, is scoped to a single unit") and every substantive
  Deliverable-1 behaviour — `gap = lcc-total / max(cc,1)`, the
  `lcc-total ≥ 5.0 ∧ gap ≥ 2.0` thresholds, the top-5 judgment guard, the
  single-unit scope, the A1 drop-unmatched rule, and especially the F2
  `(ns, var, arity, line)`/`@line` determinism fix — have **zero** covering
  assertion. A skill with valid frontmatter but an empty/paraphrased/pre-F2
  recipe body would pass green. F2's own resolution flagged this ("optionally
  assert recipe determinism") and deferred it; per `∀b. ∃t. covers(t,b)` it is
  an open coverage gap. Fix: lock the skill's threshold/formula/scope/A1/`@line`
  substrings (mirroring the workflow tests' prompt-substring anchors), ideally
  plus an executable determinism check that the embedded `jq` recipe is lossless
  over null-arity input.

- **TR2 — the generated two-phase contract is under-covered.**
  `reduce-incidental-complexity-test` locks the gate flags + baseline filenames
  but not the contract's substantive shape: the Phase-0 characterization-test
  gate ("green net before refactor"), the behaviour-identical constraint, and
  the F3-re-keyed A5/A2 `(ns, var, arity, line)` acceptance key. F3 explicitly
  noted "no test change forced (optionally assert the key string for
  coherence)", so a regression of that key back to `(ns, var, arity)` in the
  generated contract passes green. The design acceptance "Generated tasks carry
  the two-phase behaviour-preserving contract: a Phase 0 test-coverage gate …
  and Phase 1 refactor with the `local`-lens + `gate` + green-tests acceptance"
  is only partially covered. Fix: add prompt-substring assertions for the
  Phase-0 gate, the behaviour-identical constraint, and the
  `(ns, var, arity, line)` A5/A2 key.

Both findings are coverage gaps (the design acceptance behaviours exist; tests
do not yet cover them). Neither is a well-formedness or mock/stub defect — the
existing assertions are sound; they are simply incomplete against the design's
skill-content and generated-contract behaviours. Scope of both fixes: extend the
existing `workflow_definitions_test.clj` ns (no new ns / no production Clojure,
per C1).

PASS_STATUS: ACTIONABLE_FEEDBACK.

## Test review pass 1 — TR1/TR2 resolution

Executed the two test-review follow-ups (skill + generated-contract coverage
gaps) in `components/workflow-loader/test/.../workflow_definitions_test.clj`
(same ns, no new ns / no production Clojure, honouring C1).

- **TR1 resolved** — added two deftests. `incidental-complexity-finder-skill-
  content-lock-test` reads the loaded skill's `:file-path` SKILL.md (the skill
  map carries `:file-path`, not a `:body`, so the body is slurped) and locks the
  design Deliverable-1 behaviours by substring: `gap = lcc-total / max(cc, 1)`,
  the `5.0`/`2.0` thresholds, the single-unit scope + "High cc alone is not a
  target" guard (matched whitespace-tolerantly — the phrase wraps a newline in
  SKILL.md), the A1 drop / never-default-cc=1 rule, and the F2
  `(ns, var, arity, line)`/`@line` key. `incidental-complexity-finder-recipe-
  determinism-test` extracts the embedded `jq` recipe (regex on the `jq -n…`
  fenced block), rewrites its `/tmp/icf-*.json` paths to temp fixtures, and runs
  it over two same-`(ns,var,arity)` null-arity units differing only by `line`;
  asserts both survive with their OWN cc (3 and 4), i.e. `from_entries` is
  lossless — the exact F2 property a `(ns,var,arity)` key would collapse
  last-wins. Guarded with a jq-absent fallback that asserts the recipe keys on
  `@line` twice (cc-map build + local gap_key). This lands the "optionally
  assert recipe determinism" deferred by F2/F3.

- **TR2 resolved** — extended `reduce-incidental-complexity-test` with three
  `testing` blocks over step-1's generated-contract prompt: the Phase-0
  characterization-test green-net gate, the behaviour-identical / meta-spec-
  unchanged constraint, and the F3 A5/A2 `(ns, var, arity, line)` keys (both the
  A5 "keyed by" and A2 "identified by" occurrences), so an F3 regress fails
  green.

Verification: focused `psi.workflow-loader.workflow-definitions-test` green
(16 tests, 218 assertions, 0 failures); `clj-kondo --lint` on the test file:
0 errors, 0 warnings. No production/skill/workflow/doc change required — both
follow-ups were pure coverage additions against existing design behaviours.

## 2026-06-01 — Test review pass 2 (task-test-review)

Re-read design.md (Deliverable-1 acceptance), SKILL.md, both workflow `.edn`s,
and the full `workflow_definitions_test.clj` (16 tests, 218 assertions — re-run
green). Criterion: `∀b ∈ behaviour(design). ∃t. covers(t,b)` and
`¬mock ∧ ¬stub` infra deps.

Infra-dep / well-formedness: clean. Tests are state/content assertions over
loaded definitions + slurped SKILL.md + a real `jq` subprocess (with a
structural fallback) — no `with-redefs`, no mocks/stubs, no interaction
assertions. The determinism test is exemplary (executable lock over a real
recipe).

One new actionable coverage gap (TR3): TR1's content-lock test deliberately
enumerated the gap method, thresholds, single-unit scope, the high-cc-alone
guard string, the A1 drop rule, and the F2 `@line` key — but **omitted two
named Deliverable-1 behaviours**:

1. **Judgment guard (step 5 — top-5 essential-vs-incidental)** — the design's
   *core discriminator* (Locked decisions 1/2/9; the entire "Why gap" rationale).
   No test asserts the SKILL.md encodes "read the top 5 qualifying units by
   `gap`", the incidental-burden signal list, the essential-complexity rejection,
   "choose the first that passes", or "if none of the top 5 pass, report no
   target". A SKILL.md that dropped step 5 entirely (leaving only the mechanical
   gap/threshold recipe — i.e. degenerating to the `gordian complexity` ranking
   the skill exists *not* to be) would pass `content-lock` green. The
   high-cc-alone guard string that *is* locked is the *rationale*, not the
   *procedure*; the top-5 judgment procedure is the acceptance "false-positive
   guard" mechanism and is uncovered.

2. **Evidence + coverage-hint emission (step 6)** — the design's first
   acceptance is "produces a target **+ evidence**", and Deliverable-1 step 5/6
   names the coverage hint (sibling test ns exists? any test references the
   target var?) as a required emitted field. No test asserts SKILL.md instructs
   emitting the per-dimension evidence or the coverage hint. A regress dropping
   the coverage-hint emission passes green.

Fix: extend `incidental-complexity-finder-skill-content-lock-test` (same ns, no
new ns, per C1) with substring locks for (a) the top-5 judgment guard ("top 5
qualifying units by `gap`", the essential-rejection, "Choose the first … that
passes", "none of the top 5 pass") and (b) the coverage-hint evidence emission
("coverage hint", sibling-test-ns / references-the-target-var wording). SKILL.md
already carries all these strings (lines 78, 121–139, 152). Test-only change.

PASS_STATUS: ACTIONABLE_FEEDBACK.

## 2026-06-02 — Test review pass 2 follow-up executed (TR3)

Executed the single newly-added unchecked `steps.md` item (TR3) from the pass-2
test review. Test-only change; no production Clojure, no workflow/skill/design
change (SKILL.md already carries every locked string).

NS note (deviation from the original "same ns, no new ns per C1" framing): the
`incidental-complexity-finder` content-lock + determinism tests had already been
**split** out of `workflow_definitions_test.clj` into
`components/workflow-loader/test/psi/workflow_loader/incidental_complexity_finder_skill_test.clj`
(an in-flight uncommitted change preceding this follow-up). The combined file was
~873 lines — over the 800-line `components/` file-length guard
(`bb commit-check:file-lengths`). The split is the mechanically-forced way to
keep both test clusters; C1's intent (no **production** Clojure, extend the
existing definition-test surface rather than introduce new behaviour) is
preserved. I added TR3's assertions to the content-lock test in its new home.

Added two `testing` blocks to `incidental-complexity-finder-skill-content-lock-test`:

1. **step-5 top-5 judgment guard** — substring locks on:
   - "top 5 qualifying units by `gap`" (read the top 5),
   - "Reject as **essential**" (essential-complexity rejection),
   - "Choose the first unit (highest `gap`) that passes the guard",
   - "If none of the top 5 pass" (no-target when all top-5 are essential).
   A SKILL.md dropping step 5 — degenerating to the `gordian complexity` ranking
   the skill exists *not* to be — now fails green. The previously-locked
   high-cc-alone string is the *rationale*; this locks the *procedure*.

2. **step-6 evidence + coverage-hint emission** — substring locks on:
   - "coverage hint",
   - "sibling test namespace exists for the target" (sibling-ns hint),
   - "any test references the target `var`" (var-reference hint).
   A regress dropping the coverage-hint emission (the design's "produces a target
   + evidence" acceptance, with coverage hint a named emitted field) now fails
   green.

Verification (focused):
- skill-test ns: `clojure -M:test:dev --focus
  psi.workflow-loader.incidental-complexity-finder-skill-test` →
  3 tests, 27 assertions, 0 failures (content-lock now reports the two new
  `testing` blocks: step-5 guard + step-6 coverage hint).
- definitions ns: `--focus psi.workflow-loader.workflow-definitions-test` →
  13 tests, 198 assertions, 0 failures (split intact).
- combined: 16 tests, 225 assertions, 0 failures.
- `clj-kondo --lint` over both test files: 0 errors, 0 warnings.
- `bb commit-check:file-lengths`: clean (both files under 800 lines —
  skill-test 156, definitions 717).
- `clj-paren-repair` on the skill-test file: Success.

No design/spec/SKILL/workflow change needed — the design Deliverable-1
behaviours TR3 covers were already encoded in SKILL.md; this closes the
`∀b ∈ behaviour(design). ∃t. covers(t,b)` gap for the step-5 judgment guard and
step-6 coverage-hint emission. Coherence across design ↔ SKILL ↔ workflows ↔
docs unaffected (test-only).

PASS_STATUS: RESOLVED.

## 2026-06-01 — Test review pass 3 (task-test-review)

Reviewed implementation tests against the task-test-review criterion
`∀b ∈ behaviour(design). ∃t. covers(t,b)` + well-formed + nullable-not-mock.
Tests are the two workflow-loader namespaces:
`incidental-complexity-finder-skill-test` (4 tests) and
`workflow-definitions-test` (`task-lifecycle-in-worktree-test` +
`reduce-incidental-complexity-test`). Focused suite green:
16 tests, 225 assertions, 0 failures.

Coverage of the design acceptance criteria is now broad — TR1/TR2/TR3 closed the
major gaps (gap method, thresholds, single-unit scope, A1 drop rule, F2 `@line`
key, step-5 judgment guard, step-6 coverage hint, generated two-phase contract,
F3 A5/A2 key, two/three-step shapes, handoff/early-stop, NO_TARGET
short-circuit). No mocks/stubs; assertions are on parsed definition state and
SKILL.md content (state/outputs, not interactions). Two residual actionable
items:

- **TR4 — determinism test proves losslessness, not order-independence.** The F2
  fix's *core claim* (and the SKILL/A1 wording it locks) is that the join is
  **non-deterministic w.r.t. emit order** under the pre-F2 key and deterministic
  under `(ns, var, arity, line)`. `incidental-complexity-finder-recipe-determinism-test`
  runs the embedded `jq` recipe over a single fixed emit order and asserts both
  null-arity units survive with their own `cc` — this proves *losslessness* (a
  necessary precondition) but **not order-independence**: a recipe that happened
  to be order-sensitive but lossless for this one ordering would still pass
  green. The behaviour the test names ("not collapsed last-wins") is precisely
  the order-dependent one; covering it requires running the recipe a second time
  with the two units' emit order reversed (in both `local` and `cc` inputs) and
  asserting identical output (same `cc` per `line`). Test-only; same ns.

- **TR5 — stale/incorrect fixture comment in the determinism test.** The inline
  comment `;; line 10 unit (cc 3) and line 40 unit (cc 6/lcc 60) both survive`
  in `incidental-complexity-finder-recipe-determinism-test` mis-states the
  line-40 fixture: the fixture sets `cc:4` (and the assertion correctly checks
  `cc=4`), not `cc 6`. The "cc 6" annotation is stale and contradicts both the
  fixture JSON and the very assertion two lines below it, undermining the test's
  readability/signal (test-shaper: comments must not mislead). Fix the comment to
  `(cc 4)`. Test-only; same ns; no assertion change.

PASS_STATUS: ACTIONABLE_FEEDBACK

## 2026-06-01 — Test review pass 4 follow-up execution (TR4 + TR5)

Executed the two actionable items added by test-review pass 3. Both are
test-only and confined to
`components/workflow-loader/test/psi/workflow_loader/incidental_complexity_finder_skill_test.clj`
(same ns, no new ns / no production Clojure, per C1).

- **TR4 (order-independence lock).** Refactored the determinism test for
  caller-controlled emit order: extracted `local-unit-json` / `cc-unit-json`
  fixture builders and `run-jq-recipe` (rewrites the recipe's hard-coded
  `/tmp/icf-{local,cc}.json` paths to per-call temp fixtures, returns
  `{:exit :out :err}`). Split the deftest body into two `testing` blocks:
  (1) the prior **losslessness** assertions (each null-arity unit keeps its own
  cc), and (2) a new **order-independence** block that runs the recipe twice —
  forward `[line-10 line-40]` and with both `local` and `cc` inputs' emit order
  reversed `[line-40 line-10]` — asserting byte-identical `:out`. This locks the
  F2/A1 core claim: under the pre-F2 `(ns, var, arity)` key `from_entries` is
  last-wins, so reversing emit order swaps which cc each unit inherits and the
  outputs would diverge; the `@line` key makes forward == reversed. The
  jq-absent structural fallback (`@line` keyed on both `$ccmap` build and `$loc`
  gap_key) is retained unchanged.

- **TR5 (stale fixture comment).** Corrected the inline comment from
  `;; line 10 unit (cc 3) and line 40 unit (cc 6/lcc 60) both survive` to
  `;; line 10 unit (cc 3) and line 40 unit (cc 4) both survive`, matching the
  fixture JSON (`cc:4`) and the `cc=4` assertion immediately below. Comment-only;
  no assertion or fixture change.

Verification:
- `clj-paren-repair` on the test file: Success.
- Focused suite `--focus psi.workflow-loader.incidental-complexity-finder-skill-test
  --focus psi.workflow-loader.workflow-definitions-test`: **16 tests, 228
  assertions, 0 failures** (+3 assertions over pass 3's 225, from the new
  order-independence block).
- `clj-kondo --lint` on the test file: 0 errors, 0 warnings.
- File length: 186 lines (< 800 `components/` guard).

No design/spec/SKILL/workflow/doc change — both items are test-coverage and
comment fixes; the SKILL recipe and generated-contract keying are already
correct (F2/F3/F4). Coherence unaffected (test-only).

PASS_STATUS: RESOLVED.

## Test review (pass 4)

Reviewed test quality per `task-test-review` (well-formedness, behaviour
coverage of design, no mocks/stubs). Suite re-run green: **16 tests, 228
assertions, 0 failures**. No mocks/stubs of domain logic — the lone
`with-redefs` (workflow-loader test fixture) redirects the loader's
global/project workflow *directories* to a temp dir (infra isolation), and all
assertions are over loaded-definition state and SKILL.md/prompt text, never
interactions (`testing-without-mocks` honoured). TR1–TR5 closed the previously
identified gaps; the content-lock and determinism tests are well-formed and
high-signal.

One residual actionable coverage gap (TR6):

- **TR6 — The selector recipe's qualification filter (`lcc-total ≥ 5.0 ∧ gap ≥
  2.0`) and the A1 unmatched-row drop rule (never default `cc = 1`) are locked
  only as SKILL.md *prose substrings*, never *executed*.** The
  `incidental-complexity-finder-recipe-determinism-test` already runs the
  embedded `jq` recipe via the `run-jq-recipe` harness, but both its fixture
  units qualify and both have matching `cc` rows — so the filter and drop
  branches of the recipe are never exercised. A regression that broke the
  filter (e.g. a `>=`→`>` typo, a swapped threshold) or the drop semantics
  (defaulting an unmatched `local` row to `cc = 1` instead of dropping it —
  exactly what A1 forbids, because it would inflate `gap` toward false
  qualification) would pass the suite green. Per the skill criterion `∀b ∈
  behaviour(design). ∃t. covers(t,b)`, two named Deliverable-1 behaviours — the
  qualification filter (which also *drives the workflow's early-stop*) and the
  A1 inner-join-drop — have no covering executable assertion. The harness makes
  this near-free to close.

---

## Test review pass 4 follow-up — TR6 resolution (executable filter/drop cover)

Closed the one residual coverage gap. Test-only; same skill-test ns
(`incidental_complexity_finder_skill_test.clj`); no new ns, no production
Clojure (honours C1).

**What was added.** A sibling deftest
`incidental-complexity-finder-recipe-filter-and-drop-test` that *executes* the
SKILL.md-embedded `jq` recipe (reusing the existing `run-jq-recipe` harness and
the `extract-jq-recipe` extractor) over inputs that hit the two previously
prose-only branches, plus two parameterized fixture builders
(`named-local-unit-json` / `named-cc-unit-json`) so each unit is individually
identifiable in the recipe output (the pre-existing `local-unit-json` /
`cc-unit-json` are hard-coded to `x/f` and unsuitable for distinguishing
multiple units).

- **(a) Qualification filter** (`lcc-total ≥ 5.0 ∧ gap ≥ 2.0`). Three matched
  units fed:
  - `keep/qual` — lcc 30.0, cc 4 → gap 7.5 → **qualifies** (survives);
  - `drop/lowgap` — lcc 30.0, cc 20 → gap 1.5 → fails `gap ≥ 2.0` (excluded);
  - `drop/lowlcc` — lcc 4.0, cc 1 → gap 4.0 but lcc < 5.0 → fails `lcc-total ≥
    5.0` (excluded).
  Asserts only `qual` appears in the output. A `>=`→`>` threshold typo or a
  swapped threshold flips a boundary unit and fails this block. The filter is
  also the behaviour that **drives the outer workflow's early-stop** (no
  qualifier → no target), so it is doubly load-bearing.

- **(b) A1 unmatched-row drop rule.** A matched qualifying unit
  (`matched/present`, lcc 30, cc 4 → gap 7.5) and an unmatched `local` row
  (`unmatched/absent`, lcc 30, **no cc row**) fed. Asserts `present` survives
  and `absent` is **absent**. The fixture is chosen so the assertion is sharp:
  were A1 violated (the unmatched row defaulted to `cc = 1` instead of dropped),
  `absent` would compute gap = 30.0/1 = 30.0, qualify, and appear — so its
  absence *proves* the inner-join drop, not merely a coincidental filter-out.

Both blocks are gated on jq availability (mirroring the determinism test); the
jq-absent path is unchanged (the determinism test already structurally locks the
`@line` key).

**Verification.**
- Live spot-check of the recipe over the (b) fixture: output is the single
  `keep/qual`-style matched unit; the gap-30 unmatched candidate is dropped
  (confirming the drop is not a default-to-cc=1).
- `clj-paren-repair` Success.
- Focused suite green: skill ns 4 tests / 38 assertions (was 3/30 at pass 4),
  definitions ns 13 tests / 198 assertions → 17 tests / 236 assertions, 0
  failures.
- `clj-kondo` 0 findings; file 251 lines (< 800 `components/` guard).

---

## Test review pass 5 — TR7 (wrapper positive-path worktree-continuity uncovered)

Applied `task-test-review` (`∀b ∈ behaviour(design). ∃t. covers(t,b)`). Tests
are well-formed; infra deps use real `jq`/real file loaders (no mocks/stubs).
TR1–TR6 are resolved and the focused suite is green (17 tests, 236 assertions).
One residual coverage gap remains.

**TR7 — the `task-lifecycle-in-worktree` wrapper's *positive* (target-present)
path is unlocked.** `task-lifecycle-in-worktree-test` thoroughly locks the
NO_TARGET (negative) branch — that `work-on` is NOT called and the NO_TARGET
sentinel is emitted on a no-target handoff (F1) — and locks that the `work-on`
tool is present and `{{input}}` is wired. But it never asserts the
target-present branch of the `resolve-worktree` prompt: that on a handoff
carrying both fields it **calls `work-on` with the extracted worktree path** to
set the session worktree and then **yields ONLY the bare Munera task path**.
That instruction *is* the verified worktree-continuity mechanism the design
chose over bare sibling-step inheritance (Locked decision 11 / Verified Facts:
"the wrapper's `resolve-worktree` re-calls `work-on` … before sub-delegating").
A regress that dropped the positive-path "call `work-on` with the extracted
worktree path, then respond with ONLY the Munera task path" instruction (keeping
the NO_TARGET branch and the `work-on` tool entry intact) would pass the suite
green — yet it would silently break the design's central cross-`:delegate`
continuity claim. Per the test-review criterion, this named design behaviour has
no covering assertion. Fix: extend `task-lifecycle-in-worktree-test` with
substring locks on `resolve-step`'s template text for the positive-path
instruction — that it calls `work-on` with the extracted worktree path and
responds with only the Munera task path. Test-only; same ns; no production
change.

## 2026-06-01 — Test review (pass 5) follow-up executed (TR7)

Executed the single newly-added unchecked `steps.md` item (TR7) from the pass-5
test review. The "Contingency" item predates this pass (a non-planned,
design-stated fallback gated on Slice-3 step-1 proving unwieldy — it did not)
and was left untouched.

**Grounding before editing.** Re-read `.psi/workflows/task-lifecycle-in-worktree.edn`
`resolve-worktree` template text and confirmed the positive-path phrasing:
"Otherwise (both fields present): call `work-on` with the extracted worktree
path to set the session worktree, then respond with ONLY the Munera task path
(e.g. `munera/open/003-foo`) on a single line — nothing else." Verified the
phrase **"on a single line"** occurs exactly once in the file and **only** on
the positive path — the NO_TARGET branch uses "this exact single line" — so a
positive-path lock keyed on it cannot accidentally match the negative branch.

**Fix (test-only, same ns, no production change), in
`components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`:**
Added a TR7 `testing` block to `task-lifecycle-in-worktree-test` locking the
positive (target-present) branch of `resolve-worktree`'s template text with
three case-insensitive regex asserts:
1. `call \`work-on\` with the extracted worktree path` — the work-on re-call
   that establishes cross-`:delegate` worktree continuity (Locked decision 11 /
   Verified Facts: the wrapper re-calls `work-on` before sub-delegating).
2. `respond with ONLY the Munera task path` — the bare-path yield.
3. `on a single line` — the single-line yield constraint (positive-path-only
   phrase; disambiguates from the NO_TARGET branch).

A regress dropping the positive-path instruction (while keeping the NO_TARGET
branch + the `work-on` tool entry) now fails green — closing the uncovered
design behaviour (the design's central worktree-continuity mechanism) per
`∀b ∈ behaviour(design). ∃t. covers(t,b)`.

**Verification:**
- `clojure -M:test --focus psi.workflow-loader.workflow-definitions-test`:
  **13 tests, 201 assertions, 0 failures** (+3 over pass-5's 198).
- `clj-kondo --lint` on the test file: 0 errors, 0 warnings.
- `cljfmt check` on the test file: formatted correctly.
- File length 734 lines (< 800-line `components/` guard).

TR7 checked in steps.md. No production/skill/workflow/doc change (the wrapper
prompt already carries the positive-path instruction; this pass only adds its
covering assertion). PASS_STATUS: REVIEW_COMPLETE.

## Test review (pass 6)

Applied the `task-test-review` skill criteria
(`well_formed(tests) ∧ ∀b ∈ behaviour(design). ∃t. covers(t,b) ∧
∀d ∈ infra_deps. injectable ∧ nullable ∧ ¬mock ∧ ¬stub`) against the live test
suite (`workflow-definitions-test` + `incidental-complexity-finder-skill-test`,
17 tests / 239 assertions, all green).

- **Well-formed:** ✅ tests load + run green; one deftest per artifact with
  named `testing` blocks anchored on prompt/SKILL substrings.
- **Infra deps:** ✅ the only infra dep is `jq`, used as a **real** dependency
  via `clojure.java.shell` over real temp-file fixtures (no mock/stub), with a
  jq-absent structural fallback. No mocks/stubs anywhere. Conforms to the
  project no-mocks formalism.
- **Behaviour coverage:** largely complete after TR1–TR7 (gap method,
  thresholds, single-unit scope, A1 drop, F2 `@line` determinism +
  order-independence, judgment guard, coverage hint, generated two-phase
  contract incl. F3 A5/A2 key, wrapper 3-step shape + NO_TARGET + positive-path
  worktree-continuity, outer 2-step shape + handoff fields + early-stop + gate
  flags + baselines). **One uncovered named design behaviour found:**

### TR8 — the no-push/PR endpoint is unlocked

The design's *distinguishing* endpoint behaviour — Locked decision 7
("Endpoint is a completed, reviewed task on a local worktree branch — no
push/PR") and Locked decision 8 (the whole reason this is a *new* workflow vs
`complexity-reduction-pr`: "different endpoint: full task lifecycle vs. quick
PR") — is encoded as an explicit step-1 execution constraint in
`reduce-incidental-complexity.edn` ("Do NOT push or open a PR; this workflow
ends with a completed, reviewed task on the local worktree branch …") but is
**not locked by any test assertion**. `reduce-incidental-complexity-test`
covers handoff fields, early-stop, gate flags, baselines, and the generated
two-phase contract, yet a regress adding a push/PR step or instruction to step-1
(silently turning this into a `complexity-reduction-pr` clone and erasing the
design's reason for existing) would pass every existing test green. Per
`∀b ∈ behaviour(design). ∃t. covers(t,b)`, this is an uncovered design
acceptance behaviour ("ends with a completed, reviewed task on the local
worktree branch — it does **not** push or open a PR"). The string is already
present in the prompt, so it is a trivial substring lock — extend
`reduce-incidental-complexity-test` (same ns, test-only, no production change).

### TR8 resolution (review pass 6 follow-up)

Added a new `testing` block to `reduce-incidental-complexity-test`
("select-and-create prompt locks the no-push/PR endpoint constraint (TR8)") in
`components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`
(same ns, test-only, no production change). Two prompt-substring asserts on
step-1's template text: (1) `"Do NOT push or open a PR"` — the explicit
execution constraint; (2) `"ends with a completed, reviewed task on the local
worktree branch"` — the local-worktree-branch endpoint (Locked decisions 7 &
8). A regress adding a push/PR step to step-1 — turning this into a
`complexity-reduction-pr` clone and erasing the workflow's distinguishing
endpoint — now fails green. Both strings were already present verbatim in
step-1's prompt, so no production change was needed.

Verification: `clj-paren-repair` Success; `clj-kondo` 0 findings; focused
definitions suite green — 13 tests, 203 assertions, 0 failures (+2 assertions
over pass-5's 201, matching the two new asserts); file 746 lines (< 800-line
`components/` guard).

## 2026-06-02 — Implementation review (pass 5) — task-implementation-review

Reviewed the implemented artifacts against `design.md`/`plan.md` and the
architecture (`review_task_implementation`: code↔design match, architecture fit,
unnecessary-abstraction / new-vs-existing-pattern / structural-performance
flags). Re-read the live `.psi/skills/incidental-complexity-finder/SKILL.md`,
`.psi/workflows/task-lifecycle-in-worktree.edn`,
`.psi/workflows/reduce-incidental-complexity.edn`, both workflow-definition test
files, and `doc/workflows.md`.

**Largely sound.** The two workflows + skill match the design after the F1–F4 /
TR1–TR8 chain: outer 2-step (select-and-create `:session` + lifecycle-in-worktree
`:delegate`), wrapper 3-step (resolve-worktree + lifecycle + summary), F1
NO_TARGET short-circuit on both wrapper branches, the F2 `@line` join-key fixed
recipe, the F3/F4 `(ns, var, arity, line)` acceptance + selector-procedure keys,
the two-phase generated contract verbatim from design, and the no-push/PR
endpoint. Focused suite green (17 tests, 241 assertions, 0 failures). No new
unnecessary abstraction, no new-vs-existing-pattern divergence (mirrors the
loadable `review-implementation-in-worktree.edn` precedent), no structural
performance concern.

### F5 — SKILL.md frontmatter lambda still states the pre-F2 join key

One residual design(spec)↔SKILL(mechanism) coherence gap remains — the **same
class** F4 closed at `design.md` line 62, left un-propagated to the skill's own
frontmatter. SKILL.md line 4:

```
lambda: "λcode. … → join(ns,var,arity) → gap=burden/cc → …"
```

The `join(ns,var,arity)` token describes the **join operation** in the skill's
pipeline lambda — exactly the operation F2 re-keyed onto `(ns, var, arity, line)`
(the `@line` recipe) and F3/F4 propagated through the A5/A2 acceptance and the
design selector-procedure step 2. The skill **body** §2/§3 (lines 47, 83–96) and
the `jq` recipe correctly key on `(ns, var, arity, line)`; the doc and design
selector-join wording were corrected (F4); only the frontmatter lambda — the
skill's one-line behavioural summary, the first thing a reader/loader sees — was
left on the stale key.

F2's resolution note (implementation.md ~line 963) deliberately left this,
classifying lambda line 4's `join(ns,var,arity)` as the **unit's logical
identity** (alongside the Scope "a `(ns, var, arity)`" mention). That
classification is wrong for the lambda specifically: the Scope sentence *does*
denote logical identity (a unit *is* a `(ns, var, arity)`), but the lambda token
is `join(...)` — a description of the **join step**, not the unit's identity. F4
applied exactly this distinction when it fixed `design.md` line 62 ("Join on
`(ns, var, arity, line)`") while deliberately leaving the bare `(ns, var, arity)`
identity mentions at design lines 64/222. By F4's own rule, the lambda's
`join(...)` is a join-key statement and should read `join(ns,var,arity,line)`.

Impact: threshold-guarded today (no null-arity unit reaches `lcc-total ≥ 5.0`),
so behaviour is unaffected — but the design's first acceptance criterion is that
the skill "documents the `gap` method" correctly, and the lambda is the skill's
canonical mechanism summary; a reader trusting the lambda would believe the join
is non-deterministic over null-arity `defmethod` units (the precise bug F2
fixed). No test locks the lambda (the content-lock test asserts body strings
only), so it can drift unnoticed.

Fix (frontmatter-only, no behaviour change): update SKILL.md line 4
`join(ns,var,arity)` → `join(ns,var,arity,line)` to match the body recipe and the
F4-corrected design selector procedure. Leave the Scope "a `(ns, var, arity)`"
mention intact (it denotes logical identity, the same call F4 made at design
lines 64/222). Optionally extend the content-lock test with the lambda join-key
substring so it cannot regress. Skill markdown only; no workflow/test change
forced.

PASS_STATUS: ACTIONABLE_FEEDBACK.

---

## Implementation review pass 5 — F5 resolution (frontmatter lambda join-key)

RESOLVED. Updated SKILL.md line 4: the frontmatter `lambda`'s join token
`join(ns,var,arity)` → `join(ns,var,arity,line)`, matching the body §2/§3 + `jq`
recipe, the F3 A5/A2 acceptance keys, and the F4-corrected `design.md` selector
procedure (line 62). Applied F4's distinction: the lambda token is a **join-key
statement** (`join(...)`), so it tracks the unique key; the Scope "a
`(ns, var, arity)`" sentence (logical identity) is left intact, as F4 left the
bare-identity mentions at design lines 64/222.

Also added the optional regression lock noted in F5: extended
`incidental-complexity-finder-skill-content-lock-test` (same skill-test ns,
test-only, no production change) with a new `testing` block
"frontmatter lambda join key matches the F2 (ns, var, arity, line) key (F5)" —
(1) asserts the body contains `join(ns,var,arity,line)`, and (2) asserts the
body does **not** contain `join(ns,var,arity)` (the pre-F2 token), so a regress
of the lambda back to the stale key fails green.

Behaviour unchanged (frontmatter prose only; recipe already keyed on `@line`).
Focused suite green (incidental-complexity-finder-skill-test: 4 tests, 40
assertions, 0 failures — +2 over pass-4's 38); `clj-kondo` 0 findings; skill-test
file 259 lines (< 800).

PASS_STATUS: NO_ACTIONABLE_FEEDBACK (F5 resolved).

---

## Implementation review pass 6 (task-implementation-review skill)

Independent review against design acceptance + architecture. Grounded in
runtime truth: focused suite green (17 tests, 243 assertions, 0 failures);
`clj-kondo --lint .psi` 0 findings; both new workflow `.edn` files parse
clean (`clj-paren-repair` "No changes needed"); all three artifacts
(`incidental-complexity-finder` skill, `task-lifecycle-in-worktree`,
`reduce-incidental-complexity`) load with zero errors. Skill scope/guard/keys,
wrapper positive+NO_TARGET branches, outer early-stop/baselines/gate-flags/
no-push, and docs (workflows.md + CHANGELOG) all verified coherent. The D1
deviation (wrapper authored as `.edn`, mirroring `review-implementation-in-worktree.edn`,
not `.md`) is **independently re-verified correct**: `parse-workflow-file`
routes `.md` → `parse-markdown-workflow-file` (single-step), which rejects any
body that begins with an EDN map (`parser.clj:162`), and
`implement-task-in-worktree.md`'s body *does* begin with `{:terminal-contract …}`.

One new actionable coherence gap (F6) — design(spec)↔implementation, F4/F5-class.

### F6 — design "Verified facts" still names the non-loading `implement-task-in-worktree` as the *verified/proven* wrapper precedent

`design.md` repeatedly grounds the chosen worktree-continuity mechanism on
`implement-task-in-worktree` as the **"verified"** wrapper the new wrapper is
**"structurally identical to"** and built on the **"verified wrapper path"** /
**"proven path"** (lines 138–139, 144, 168–170, 295, 316, 328, 362, 367, and the
whole `## Verified facts (grounding)` worktree-ownership paragraph at lines
327–333). But D1 discovered during implementation — and this review
re-confirmed against the live loader — that `implement-task-in-worktree.md`
**does not load**: its body begins with an EDN map, which
`parse-markdown-workflow-file` rejects (`parser.clj:162`). So the artifact the
design calls "verified" and "proven" is in fact broken; the real loadable
precedent used was `review-implementation-in-worktree.edn` (recorded only in
implementation.md / steps.md D1, never propagated back into the design spec).

Per the project coherence invariant (`source_of_truth ≡ … ∪ spec`;
`¬source_of_truth(code)`), the spec is authoritative and currently asserts a
false grounding fact — the same stale-claim-in-one-artifact class F4/F5 fixed
(stale join key) but here a stale *precedent claim*. The chosen **mechanism**
(handoff-threaded `worktree_path:` + a `resolve-worktree` `:session` step that
re-calls `work-on` before sub-delegating) is still valid and *is* demonstrated
by a loadable precedent — but that precedent is `review-implementation-in-worktree.edn`,
not `implement-task-in-worktree`. Threshold-harmless (behaviour is correct and
tested), but a future reader trusting the design's "Verified facts" would cite a
broken file as the proven pattern, and the design↔implementation grounding is
internally contradictory.

Fix (design prose only; no workflow/skill/test change forced — the wrapper is
already correct): reconcile `design.md`'s "Verified facts (grounding)" +
Step-1/Step-2 wrapper references so the **verified/loadable** precedent is named
as `review-implementation-in-worktree.edn` (the `.edn` 3-step
`resolve-worktree → delegate → summary` wrapper that actually loads), demoting
`implement-task-in-worktree` to "the *intended* shape, which does not currently
load under the loader (`.md` body begins with an EDN map — see D1), hence the
wrapper is authored as `.edn` mirroring the loadable
`review-implementation-in-worktree.edn`." Keep the mechanism description (handoff
field + `work-on` re-call) — only the *named verified precedent* is wrong.
Cross-reference D1. Optionally lock the design's named precedent for coherence,
but no test change is forced.

PASS_STATUS: ACTIONABLE_FEEDBACK.

---

## Pass 6 — F6 resolution (follow-up execution)

F6 executed (design prose only; no workflow/skill/test change). Reconciled
`design.md` so the verified/loadable wrapper precedent is named as
**`review-implementation-in-worktree.edn`**, demoting `implement-task-in-worktree`
to the *intended shape* that does not currently load.

Live (static) re-confirmation of D1's grounding:
- `.psi/workflows/implement-task-in-worktree.md` body begins with
  `{:terminal-contract {:handoff ...} :steps [...]}` — an EDN map — so
  `parse-markdown-workflow-file` rejects it via `body-starts-with-edn-map?`
  ("Markdown workflow body must not begin with an EDN workflow definition block",
  `components/workflow-loader/src/psi/workflow_loader/parser.clj:162`).
- `.psi/workflows/review-implementation-in-worktree.edn` is a loadable `.edn`
  3-step wrapper: `resolve-worktree` (`:session`, tools `["read" "bash"
  "work-on"]`, re-calls `work-on` from a `worktree_path:` handoff) →
  `review` (`:delegate` `:target "review-task-implementation"`,
  `:prompt-string {:type :map :fields {:input {:from {:step "resolve-worktree"
  :yield :text}}}}`) → `summary` (`:session`). This is the mechanism the new
  wrapper mirrors.
  (No nREPL is attached in this worktree; confirmation is from the live file
  bodies + the parser source, which is conclusive for the load-vs-reject claim.)

Edits to `design.md`:
- Added an **F6/D1 precedent note** at the head of `## Verified facts
  (grounding)` naming the loadable precedent, citing the parser rejection, and
  redirecting all later "structurally identical to / verified
  `implement-task-in-worktree`" phrasing to the loadable
  `review-implementation-in-worktree.edn`.
- Reconciled each load-bearing inline reference (Step-1 handoff consumer, Step-2
  wrapper framing, handoff wiring, worktree-continuity mechanism, the
  delegate-yield fact, the worktree-ownership fact, Locked decision 11, and both
  acceptance-criteria mentions) to name `review-implementation-in-worktree.edn`
  with an "`.edn` realisation of the intended shape; see F6/D1 precedent note"
  cross-ref. The only bare `implement-task-in-worktree` mentions left are inside
  the precedent note itself (intentional).
- Latent coherence fix surfaced by F6: the acceptance criterion described the
  wrapper as "two-step"; the resolved P1 design makes it three steps
  (resolve-worktree → lifecycle → summary), matching the loadable 3-step
  precedent — aligned to "three-step".

Mechanism prose unchanged (handoff `worktree_path:` + `resolve-worktree`
re-calling `work-on`) — it was already correct and is demonstrated by the
loadable precedent. `doc/workflows.md`'s single `implement-task-in-worktree.md`
mention is a plain workflow-listing line (not a 204 precedent claim), so it
remains coherent; no doc/workflow/skill/test change made. design(spec) ↔
implementation grounding contradiction closed. `steps.md` F6 item checked.

## Implementation review — pass 7 (task-implementation-review skill)

◈ Independent review pass applying `task-implementation-review`
(`matches(code,design) ∧ follows(code,architecture) ∧ ¬redundant-pattern ∧
¬unnecessary-abstraction ∧ ¬structural-perf-issue`). Verified against live
runtime + code, not just artifacts:

- **Loadability + tests:** both workflows load; focused suite green
  (17 tests, 243 assertions, 0 failures); `clj-kondo` 0 findings on the two
  changed test nss.
- **design↔code match:** SKILL.md encodes the `gap` method, `lcc-total ≥ 5.0 ∧
  gap ≥ 2.0` filter, top-5 guard, evidence + coverage-hint, and the
  `(ns, var, arity, line)` join (F2–F5 all landed). `reduce-incidental-complexity.edn`
  matches the two-step shape; `task-lifecycle-in-worktree.edn` matches the
  three-step P1 wrapper shape; generated two-phase contract is verbatim.
- **Live grounding re-confirmed:** `bb gordian gate --help` shows `--fail-on`,
  `--max-new-medium-findings`, `--max-new-high-findings` (A3 flags real);
  `bb gordian local --json` units carry `lcc-total`/`findings`/`line`/`end-line`
  (skill recipe field references real).
- **Architecture fit (worktree continuity):** confirmed at code level —
  `child_session_state.clj` copies `(:worktree-path parent-sd)` into the child
  session, so the wrapper's `resolve-worktree` `:session` `work-on` call sets the
  wrapper-session worktree which the inner `lifecycle` `:delegate` child inherits.
  The verified wrapper pattern (re-call `work-on`, not bare sibling inheritance)
  is sound and honours `one_way`.
- **Coherence:** `doc/workflows.md` + `CHANGELOG.md [Unreleased]` document the
  workflow + skill; join key, thresholds, gate flags, handoff fields, no-push/PR
  endpoint all consistent across design/skill/workflows/docs.

Found **no new actionable issues**. The only unchecked steps.md item is the
explicitly-non-planned step-1-split contingency (gated on "only if step-1 proves
unwieldy" — it did not). The six implementation + eight test review follow-ups
(F1–F6, TR1–TR8) are all resolved. Implementation quality: simple, consistent,
robust; behaviour-preserving contract objective and enforced. REVIEW_COMPLETE.

## Test review (pass 7)

Applied the `task-test-review` skill criterion
`∀b ∈ behaviour(design). ∃t. covers(t,b)` plus the well-formed / nullable-deps
checks. Re-read design.md, SKILL.md, both workflow `.edn`s, and the two test
nss (`incidental_complexity_finder_skill_test.clj`,
`workflow_definitions_test.clj`). Focused suite green
(17 tests, 243 assertions, 0 failures); `clj-kondo` 0 findings.

Test quality is strong: no mocks, no `with-redefs`, no interaction assertions;
the executable skill tests drive the real embedded `jq` recipe via a
caller-controlled `run-jq-recipe` harness and assert state/outputs only. TR1–TR8
already lock the gap method, thresholds, single-unit scope, A1 drop (prose +
executable), F2 `@line` determinism (lossless + order-independent), F5 lambda
key, the step-5/step-6 SKILL behaviours, the filter, the wrapper's positive +
NO_TARGET branches, the generated two-phase contract, A5/A2 keys, and the
no-push/PR endpoint.

### TR9 — the recipe's gap-descending ranking and top-5 cap are unexercised

`incidental-complexity-finder-recipe-*-test` exercise the recipe's join,
determinism, filter, and drop branches, but **no executable test feeds more than
the ≤3 units those branches need**, so two named Deliverable-1 behaviours encoded
**only** in the `jq` recipe are never asserted:

- **Gap-descending ranking** (`sort_by(-.gap)`): design step 4 / Locked decision 2
  — "Rank qualifying units by `gap`" (descending); step 5 reads the top units "by
  `gap`". The recipe's `sort_by(-.gap)` is the mechanism. No test feeds qualifying
  units in non-gap order and asserts the output is gap-descending. A regress to
  `sort_by(.gap)` (ascending) — which would make the workflow pick the *lowest*-gap
  unit and the judgment guard read the wrong five — passes every test green.
- **Top-5 cap** (`.[0:5]`): design step 4 / Locked decision 2 — the guard reads
  "the **top 5** qualifying units by `gap`"; the recipe slices `.[0:5]`. No test
  feeds >5 qualifying units and asserts exactly 5 survive. A regress dropping the
  slice (or `.[0:10]`) — emitting an unbounded/wrong candidate set the guard then
  over-reads — passes every test green. The TR3 content-lock asserts the SKILL
  *prose* "top 5 qualifying units by `gap`", but the prose and the recipe slice
  can drift independently (the recipe is the executed mechanism).

Per `∀b ∈ behaviour(design). ∃t. covers(t,b)`, both are uncovered design
acceptance behaviours of the executed recipe. Fix: extend
`incidental-complexity-finder-recipe-filter-and-drop-test` (or a sibling deftest
in the same skill-test ns; test-only, no new ns / no production code), reusing
`run-jq-recipe` + `named-{local,cc}-unit-json`: (a) a **ranking** assertion —
feed ≥3 qualifying units whose input emit order differs from their gap order, and
assert the output `gap` values appear in strictly descending order; (b) a
**top-5 cap** assertion — feed >5 qualifying units and assert exactly 5 survive.
Keep under the 800-line `components/` file guard.

#### TR9 resolution

Added a sibling deftest `incidental-complexity-finder-recipe-ranking-and-cap-test`
in the same skill-test ns (`incidental_complexity_finder_skill_test.clj`;
test-only, no new ns / no production Clojure), reusing the existing
`run-jq-recipe` harness and the `named-{local,cc}-unit-json` fixture builders.

- **(a) gap-descending ranking** (`sort_by(-.gap)`): feeds three qualifying
  units whose input **emit order** (lowmid, top, mid) deliberately differs from
  their **gap order** — `top` lcc 100/cc 4 → gap 25.0, `mid` lcc 60/cc 4 → gap
  15.0, `lowmid` lcc 30/cc 4 → gap 7.5. Asserts (1) the serialized output `gap`
  sequence `[top mid lowmid]` is strictly descending (`= gaps (reverse (sort
  gaps))`) and (2) the highest-gap unit `top` precedes the lowest `lowmid` in the
  output string (positional, not just set membership). A regress to
  `sort_by(.gap)` (ascending) would emit lowmid<mid<top and fail both.
- **(b) top-5 cap** (`.[0:5]`): feeds 7 qualifying units (all lcc 50/cc 4 → gap
  12.5, above threshold, distinguished by var/line) and asserts exactly 5 survive
  (`(count (re-seq #"\"ns\":\s*\"cap\"" out)) = 5`). A regress dropping the slice
  (or widening to `.[0:10]`) emits all 7 and fails.

Both blocks gated on jq availability (matching the existing
filter/drop/determinism tests). Verified live the recipe serializes `"gap": 25`
(integer when whole) / `"var": "top"` (space after colon) — the `gap-of` regex
and `indexOf` positional check match that exact form.

Focused suite green: skill-test 5 tests / 47 assertions (+7 over pass-6's 40);
definitions 13 tests / 203 assertions; `clj-kondo` 0 findings; skill-test file
322 lines (< 800). Test-only change — no production/workflow/skill/doc change
(the recipe already encodes both behaviours correctly; TR9 only adds the
executable cover).

PASS_STATUS: RESOLVED.

## Test review (pass 8)

Reviewed implementation tests against `task-test-review` (well-formed ∧ ∀b∈behaviour(design).∃t.covers(t,b) ∧ infra-deps injectable/nullable, ¬mock/¬stub).

Test surface examined:
- `incidental_complexity_finder_skill_test.clj` — registration, content-lock
  (gap method, 5.0/2.0 thresholds, single-unit scope, high-cc guard, A1 drop
  rule, F2 `(ns,var,arity,line)`/`@line` key, F5 frontmatter lambda key, step-5
  top-5 judgment guard, step-6 evidence+coverage-hint), plus executable
  `run-jq-recipe` coverage: determinism (losslessness + order-independence/TR4),
  filter+drop (TR6), ranking+cap (TR9).
- `workflow_definitions_test.clj` — `task-lifecycle-in-worktree` three-step
  shape, work-on tool, `{{input}}` wiring, lifecycle delegate target/prompt-map,
  summary presence + resolve-worktree source, NO_TARGET short-circuit (F1),
  positive worktree-continuity path (TR7); `reduce-incidental-complexity`
  two-step shape, handoff fields, early-stop, gate flags + both baselines,
  Phase-0 gate + behaviour-identical constraint + A5/A2 `(ns,var,arity,line)`
  keys (TR2/F3), no-push/PR endpoint (TR8).

Findings: **none actionable.** Coverage maps onto every named design
Deliverable-1/2 behaviour (∀b.∃t holds). Executable recipe tests exercise the
real `jq` recipe over synthetic fixtures (no paraphrase drift). Infra dependency
is the workflow loader, injected via temp-dir `with-redefs` of
`global-workflow-dirs`/`project-workflow-dir` (configuration injection, not a
logic mock); skills/recipe tests use the real loader + real `jq`. Assertions are
on parsed state / recipe output only — no interaction assertions, no mocks/stubs
of logic, consistent with the project Test formalism. Suite green: 18 tests, 250
assertions, 0 failures; no mocks/stubs.

The prior seven test-review passes (TR1–TR9) already resolved the gaps a fresh
review would raise (skill behaviour cover, generated-contract cover, recipe
determinism/order-independence/filter/drop/ranking/cap, wrapper positive path,
no-push/PR endpoint). No new follow-ups added.

PASS_STATUS: REVIEW_COMPLETE.

## Test review follow-ups (review pass 8)

- TR10 — `task-lifecycle-in-worktree-test`'s `summary`-step coverage locks only
  the NO_TARGET (negative) branch: it asserts the prompt `.contains "NO_TARGET"`
  and that `summary` sources `resolve-worktree :yield :text`. It does NOT lock
  the summary's **positive (target-present) terminal contract** — the symmetric
  gap TR7 fixed for `resolve-worktree`. The `summary` template carries the
  workflow's user-facing terminal behaviour: on a real `munera/...` path it must
  independently inspect the task's artifacts and report whether the lifecycle
  ran cleanly (design → plan → implement → review), what work completed, which
  artifacts updated, and whether the task was closed/open. It also sources the
  `lifecycle` step's `:yield :text` so it can report lifecycle outcomes. None of
  these positive-path strings, nor the `lifecycle`-output sourcing, is asserted.
  A regress dropping the positive-path summary contract (or the `lifecycle`
  contribution), while keeping the NO_TARGET branch, passes every existing test
  green — yet the workflow's terminal user-facing report would be gutted. Per
  `∀b ∈ behaviour(design). ∃t. covers(t,b)` (design acceptance: the wrapper is a
  three-step `… → summary(:session)` adapter producing the terminal user-facing
  result; Locked decision 7's "completed, reviewed task" endpoint is what the
  summary reports), this is an uncovered terminal behaviour. Fix: extend
  `task-lifecycle-in-worktree-test`'s `summary` coverage (same ns, test-only, no
  production change) with (a) a substring lock on the positive-path
  design→plan→implement→review report contract, and (b) an assertion that
  `summary` sources the `lifecycle` step `:yield :text` contribution. Run
  focused suite + `clj-kondo`.


### Pass 8 — test-review TR10 resolution

- TR10 — RESOLVED. Extended `task-lifecycle-in-worktree-test`'s `summary`
  coverage (in `workflow_definitions_test.clj`, same ns; test-only, no
  production change) with two new `testing` blocks symmetric to TR7's
  `resolve-worktree` positive-path lock:
  - `"summary prompt reports the positive-path lifecycle terminal contract
    (TR10)"` — asserts four positive-branch substrings on the `summary` template
    text: `"independently inspect that specific task"` (independent artifact
    inspection on a target-present run), `"completed cleanly (design → plan →
    implement → review)"` (the lifecycle-clean report), `"task artifact files
    updated"` (artifacts-updated report), and
    `"closed (moved to munera/closed/) or remains open"` (the closed/open
    endpoint, Locked decision 7).
  - `"summary sources the lifecycle step :yield :text … (TR10)"` — asserts the
    `{:step "lifecycle" :yield :text}` source contribution is present on the
    `summary` step, so a regress dropping the `lifecycle` output sourcing (which
    the positive-path report depends on) fails green.
  A regress dropping the positive-path contract while keeping the NO_TARGET
  branch + the resolve-worktree sourcing now fails green. All asserted strings
  already exist verbatim in the EDN `summary` template — no production change.
  Focused definitions suite green: 13 tests, 208 assertions, 0 failures (+5 over
  pass-7's 203). `clj-paren-repair` Success; `clj-kondo` 0 findings;
  `workflow_definitions_test.clj` 772 lines (< 800 `components/` guard).

### Pass 9 — test-shaper review (independent)

Applied `test-shaper` to the 204 tests (`workflow_definitions_test.clj` +
`incidental_complexity_finder_skill_test.clj`). Focused suite green: 18 tests,
255 assertions, 0 failures. Coverage is strong and economical — TR1–TR10
collectively lock the skill recipe (join losslessness/order-independence/
filter/drop/ranking/cap), the SKILL prose behaviours, the wrapper three-step
shape + NO_TARGET + positive-path worktree-continuity + summary terminal
contract, and the outer two-step shape + handoff fields + early-stop + gate
flags + Phase-0 gate + behaviour-identical constraint + (ns,var,arity,line)
keys + no-push/PR endpoint.

One actionable gap (TR11): the **A3 baseline-path-resolution** behaviour is
named (design "Generated task design" + R3) with an explicit failure mode —
the gate `--baseline` must reference the **worktree-root-relative task-dir
path** (`munera/open/NNN-slug/before-diagnose.edn`), NOT a bare filename, "where
a bare filename does not resolve" from cwd. The current
`reduce-incidental-complexity-test` asserts only the `--fail-on
new-cycles,new-high-findings --max-new-medium-findings 0` flag tail; a regress
changing `--baseline munera/open/NNN-slug/before-diagnose.edn` to a bare
`--baseline before-diagnose.edn` (the exact R3-warned bug) passes green. The
same class applies to the A5 `before-local.json` comparison, locked only as a
bare filename substring, not its worktree-relative read path. test-shaper
`economical` (cover named acceptance) + `meaningful_failures`: a named design
behaviour with a called-out failure mode should have a test that fails on the
regress.

---

## Pass 9 — test-review TR11 resolution (baseline-path resolution lock)

Added one `testing` block to `reduce-incidental-complexity-test` in
`components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`
(same ns, test-only, no production change), placed immediately after the existing
"embeds the enforcing gate flags + both baselines" block:

  "select-and-create prompt resolves A3/A5 baselines by worktree-relative path (TR11)"
  - asserts step-1 prompt `.contains` `--baseline munera/open/NNN-slug/before-diagnose.edn`
    — the full worktree-root-relative A3 gate baseline path (not the bare
    `--baseline before-diagnose.edn` R3 warned against). The pre-existing block
    locked only the `--fail-on …` tail, so this regress passed green before.
  - asserts step-1 prompt `.contains` `` the stored `munera/open/NNN-slug/before-local.json` ``
    — the worktree-relative A5 comparison read path, not the bare
    `before-local.json` filename the earlier block locks.

Both substrings already present verbatim in step-1's prompt (verified live before
asserting); no production/EDN change. The existing bare-filename locks
(`before-local.json` / `before-diagnose.edn`) are retained — TR11 anchors the
*path resolution* (the R3-warned failure mode) the bare-filename locks did not.

Verification: `bb clojure:test:unit` → ✅ All tests passed (full unit suite green,
including the extended `reduce-incidental-complexity-test`). `clj-kondo --lint`
on the test file → 0 errors, 0 warnings. File length 787 lines (< 800
`components/` guard). Marked TR11 `[x]` in steps.md with resolution note.

## Pass 10 — test-shaper review (independent)

Applied `test-shaper` (robustness ∧ meaningful_failures ∧ deterministic ∧
economical) to the 204 tests (`workflow_definitions_test.clj` +
`incidental_complexity_finder_skill_test.clj`). Focused suite green: 18 tests,
257 assertions, 0 failures (jq present). TR1–TR11 coverage is strong, sociable,
state/output-asserting, no mocks. One actionable gap.

### TR12 — two recipe tests silently pass with zero behavioural assertions when jq is absent

The three executable recipe tests gate on `jq` availability, but only the
*determinism* test (`incidental-complexity-finder-recipe-determinism-test`)
degrades gracefully: its `if/do/else` has a jq-absent **structural fallback**
(`testing "jq unavailable — determinism asserted structurally on the recipe
key"`) that asserts the recipe keys on `@line` on both the `$ccmap` build and the
`$loc` gap_key — a real assertion in the jq-absent environment.

The other two recipe tests use a bare `(when jq-available …)` with **no else
branch**:
- `incidental-complexity-finder-recipe-filter-and-drop-test`
- `incidental-complexity-finder-recipe-ranking-and-cap-test`

When `jq` is absent (e.g. a CI runner without jq), the entire `when` body is
skipped and each `deftest` runs only its single pre-`when` `(is (some? recipe) …)`
floor assertion — the filter/drop and ranking/cap **behaviours are not asserted
at all**, yet both tests report green. This violates `meaningful_failures`
(a regress to the recipe's `>= → >` threshold, the A1 unmatched-row drop, the
`sort_by(-.gap)` ranking, or the `.[0:5]` cap would pass green in any
jq-less environment) and `deterministic` (coverage silently varies with the
environment). The determinism test already shows the correct shape; the
asymmetry is the defect.

Minimal fix (mirror the determinism test): give each of the two `when`-gated
recipe tests a jq-absent fallback that either (a) structurally locks the recipe
fragment the behaviour depends on (the `>= 5.0`/`>= 2.0` filter predicate and the
A1 drop = inner-join shape for filter-and-drop; the `sort_by(-.gap)` +
`.[0:5]` fragments for ranking-and-cap), or (b) explicitly fails/marks the test
inconclusive rather than passing vacuously. Prefer (a) for parity with the
determinism test's structural fallback. No production/skill/EDN change — this is
test robustness only, and the skill-test file is 322 lines (well under the 800
`components/` guard), so the fallbacks fit without a split.

PASS_STATUS: ACTIONABLE_FEEDBACK.

---

## Follow-up execution — pass 10 test-review TR12 (jq-absent recipe fallback)

✅ TR12 resolved (test-only; no production/skill/EDN change).

Converted both `when`-gated recipe tests in
`components/workflow-loader/test/.../incidental_complexity_finder_skill_test.clj`
from a bare `(when jq-available …)` (no else → vacuous green when jq absent) to
the determinism test's `(if jq-available (do …) (testing …structural fallback…))`
shape:

- `incidental-complexity-finder-recipe-filter-and-drop-test` — jq-absent fallback
  asserts the recipe carries `select(.["lcc-total"] >= 5.0 and .gap >= 2.0)`
  (qualification filter) and `select($ccmap[.gap_key] != null)` (A1 inner-join /
  unmatched-local drop).
- `incidental-complexity-finder-recipe-ranking-and-cap-test` — jq-absent fallback
  asserts `sort_by(-.gap)` (gap-descending, not ascending) and `.[0:5]` (top-5 cap).

All four fragments verified present verbatim in the live SKILL.md jq recipe, so the
exact regresses TR12 names (>= → > threshold typo, A1 drop-rule removal,
`sort_by(.gap)` ascending, dropped/widened slice) now fail green whether or not jq
is installed — closing the `meaningful_failures` + `deterministic` gap.

Verification:
- `clj-paren-repair` on the test file: Success 1 / Failed 0.
- `clj-kondo --lint` on the test file: 0 errors, 0 warnings.
- Focused recipe tests (filter-and-drop, ranking-and-cap, determinism) green via
  `bb clojure:test:unit` (jq present path; jq 1.8.1 on PATH). One unrelated
  pre-existing failure in `psi.turn-runtime.response-mode-test`
  (`execute-prepared-request-retry-after-header-drives-delay-test`, a retry-after
  timing test) — out of scope for TR12.
- Test file 340 lines (< 800 `components/` guard).

PASS_STATUS: FOLLOW_UP_COMPLETE.

---

## Pass 11 — test-shaper review (independent)

Applied `test-shaper` (behavior_focused ∧ meaningful_failures ∧ economical) to
the 204 tests (`workflow_definitions_test.clj` +
`incidental_complexity_finder_skill_test.clj`). Focused suites green: skill ns +
workflow-definitions ns both ✅ (jq 1.8.1 on PATH). 3 pre-existing unrelated
failures in `psi.turn-runtime.response-mode-test` (retry/timing) — out of scope.
TR1–TR12 coverage is strong, sociable, state/output-asserting, no mocks. One
actionable gap.

### TR13 — the outer delegate step's `:context` handoff propagation is unlocked

`reduce-incidental-complexity.edn`'s `lifecycle-in-worktree` `:delegate` step
carries a two-element `:context` vector:
`[{:type :source :from :workflow-original}
  {:type :source :from {:step "select-and-create" :yield :text}}]`.
The second source — the `select-and-create` `:yield :text` — is what propagates
the step-1 structured handoff blob into the delegated wrapper's context, the
companion to the `:prompt-string` `:input` wiring and part of the verified
cross-`:delegate` worktree-continuity mechanism (Locked decision 11 / Verified
Facts). `task-lifecycle-test` precisely locks its workflow's `:context`
(`"every step carries only :workflow-original context (no prior-step yield)"`),
but `reduce-incidental-complexity-test` locks only `:type`/`:target`/
`:prompt-string` of this delegate — its `:context` is asserted nowhere.

A regress dropping the `{:step "select-and-create" :yield :text}` context source
(keeping `:prompt-string`) would strip the handoff from the delegated run's
context yet pass every existing test green. This violates `behavior_focused`
(the context-propagation behaviour is observable and design-significant but
unasserted) and `meaningful_failures` (the exact continuity-breaking regress
fails silently). The asymmetry with `task-lifecycle-test`'s explicit `:context`
lock is the tell.

Minimal fix (mirror `task-lifecycle-test`'s `:context` assertion shape): add a
`testing` block to `reduce-incidental-complexity-test` locking the
`lifecycle-in-worktree` delegate's `:context` equals
`[{:type :source :from :workflow-original}
  {:type :source :from {:step "select-and-create" :yield :text}}]`, so dropping
the handoff context source fails green. Test-only, no production/EDN change;
`workflow_definitions_test.clj` is 787 lines (< 800 `components/` guard) — a
small added block fits; verify the file stays under 800 after editing.

PASS_STATUS: ACTIONABLE_FEEDBACK.
