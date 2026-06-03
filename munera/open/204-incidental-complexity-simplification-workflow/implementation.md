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

## 2026-06-02 — Test review (pass 11) follow-up executed (TR13)

Executed the single newly-added unchecked `steps.md` item (TR13) from the
pass-11 test-shaper review. (The trailing Contingency item predates this pass
and is conditional — "only if Slice 3 step-1 proves unwieldy", which it did not
— so it was left untouched.)

**Grounding before locking.** Read the live
`.psi/workflows/reduce-incidental-complexity.edn` `lifecycle-in-worktree`
delegate step and confirmed its `:context` is exactly
`[{:type :source :from :workflow-original}
  {:type :source :from {:step "select-and-create" :yield :text}}]`.

**Fix (test-only; same ns, no production/EDN change).** Added a `testing` block
to `reduce-incidental-complexity-test` immediately after the existing
`:type`/`:target`/`:prompt-string` delegate assertions — "lifecycle-in-worktree
:delegate :context propagates workflow-original + the select-and-create handoff
yield (TR13)" — asserting `(:context delegate-step)` equals the full two-source
vector, mirroring `task-lifecycle-test`'s `:context` lock. A regress dropping the
`{:step "select-and-create" :yield :text}` source — stripping the step-1
structured handoff from the delegated wrapper's context (the companion to the
`:prompt-string` `:input` wiring, part of the verified cross-`:delegate`
worktree-continuity mechanism, Locked decision 11) — now fails green.

**File-length guard.** The added assertion + comment initially pushed
`workflow_definitions_test.clj` to 802 lines (over the 800 `components/` guard);
trimmed the TR13 explanatory comment to land at **799 lines** (< 800).

**Verification:**
- `clj-paren-repair workflow_definitions_test.clj`: Success(1)/Failed(0).
- `clojure -M:test --focus psi.workflow-loader.workflow-definitions-test`:
  **13 tests, 211 assertions, 0 failures** (+3 over pass-9's 208).
- `clj-kondo --lint` on the test file: 0 errors, 0 warnings.

TR13 checked in steps.md. PASS_STATUS: REVIEW_COMPLETE.

## Test review pass 12 (test-shaper)

TR14 — `task-lifecycle-in-worktree-test`'s `lifecycle` `:delegate` coverage
asserts only `:type`, `:target`, and `:prompt-string`; it never locks the
delegate's `:context`. The wrapper's `lifecycle` step carries
`:context [{:type :source :from :workflow-original}]` (EDN line 17) —
deliberately **only** `:workflow-original`, NOT the `resolve-worktree` handoff
yield, because inner `task-lifecycle` reads the task path solely via
`:prompt-string {:input …}`; re-injecting the raw handoff/worktree-path blob as
a context source would pollute the lifecycle's context. This is the exact
TR13-class symmetry: `task-lifecycle-test` explicitly locks every delegate's
`:context` ("no prior-step yield"), and TR13 added the same lock for the OUTER
`reduce-incidental-complexity` delegate — but the WRAPPER's inner lifecycle
delegate `:context` was left uncovered. Per test-shaper `meaningful_failures` +
`behavior_focused` (the context-propagation shape is observable and
design-significant), a regress adding `{:step "resolve-worktree" :yield :text}`
to the wrapper lifecycle `:context` (re-injecting the handoff into the lifecycle
context) or dropping `:workflow-original` passes every existing test green.
Fix: add a `testing` block to `task-lifecycle-in-worktree-test` asserting the
`lifecycle` delegate's `:context` equals
`[{:type :source :from :workflow-original}]`, mirroring `task-lifecycle-test`'s
`:context` lock and TR13's outer-delegate lock. Test-only, no production/EDN
change; `workflow_definitions_test.clj` is 799 lines — keep the edit under the
800 `components/` guard (trim the existing verbose TR-comment headroom if
needed). See steps.md "Test review follow-ups (review pass 12 — test-shaper)".

### TR14 resolution

Added a `testing` block to `task-lifecycle-in-worktree-test` (same ns,
test-only, no production/EDN change):

```clojure
(testing "lifecycle :delegate :context is only :workflow-original (no prior-step yield) (TR14)"
  (is (= [{:type :source :from :workflow-original}]
         (:context lifecycle-step))))
```

`lifecycle-step` was already bound in the test's `let`. Verified against the
live EDN before locking: the wrapper `lifecycle` step's `:context` is exactly
`[{:type :source, :from :workflow-original}]` — only `:workflow-original`, NOT
the `resolve-worktree` `:yield :text` handoff (inner `task-lifecycle` reads the
task path solely via `:prompt-string {:input …}`; re-injecting the handoff blob
would pollute the lifecycle context). The block mirrors `task-lifecycle-test`'s
`:context` lock and TR13's outer-delegate `:context` lock. A regress adding
`{:step "resolve-worktree" :yield :text}` to the wrapper lifecycle `:context`,
or dropping `:workflow-original`, now fails green.

File-length guard: the new block pushed `workflow_definitions_test.clj` from 799
to 810 lines (over the 800 `components/` guard). Trimmed verbose prose headroom
in the TR7, TR10, and TR13 explanatory comment blocks (no assertion changes) →
file now **797 lines** (`bb commit-check:file-lengths` clean).

Verification:
- `task-lifecycle-in-worktree-test` (focused): 1 test, **26 assertions**, 0
  failures (+1 over pass-11's 25).
- `psi.workflow-loader.workflow-definitions-test` (full ns): **13 tests, 212
  assertions, 0 failures** (+1 over pass-11's 211).
- `clj-kondo --lint` on the test file: 0 errors, 0 warnings.
- `clj-paren-repair` on the test file: Success.
- `bb commit-check:file-lengths`: clean (797 < 800).

TR14 checked in steps.md. PASS_STATUS: REVIEW_COMPLETE.

## Test review pass 13 (test-shaper)

TR15 — `reduce-incidental-complexity-test` never locked the `select-and-create`
`:session` step's `{{input}}` → `:workflow-input` wiring. Step 1 carries
`:vars {"input" {:from :workflow-input}}` and the prompt ends `Input:\n{{input}}`
— the workflow's entry-point data-flow contract (top-level workflow input
reaches the selection step's prompt). The sibling `task-lifecycle-in-worktree-test`
already locks the analogous wiring for the wrapper's first step via
`(step-has-input-var-wired? resolve-step)`, but the outer step's input wiring
was uncovered: a regress dropping `:vars`/the `{{input}}` template (or mis-wiring
`input` to a non-`:workflow-input` source) passed every existing
`reduce-incidental-complexity-test` assertion green. Per test-shaper
`behavior_focused` (entry-point input flow is observable and design-significant)
+ `meaningful_failures` (the regress fails silently) + `consistent` (the sibling
wrapper test already locks the analogous wiring), this is a coverage gap. See
steps.md "Test review follow-ups (review pass 13 — test-shaper)".

### TR15 resolution

Added a `testing` block to `reduce-incidental-complexity-test` (same ns,
test-only, no production/EDN change):

```clojure
(testing "select-and-create wires {{input}} to the bare :workflow-input (TR15)"
  (let [tmpl (first (filter #(= :template (:type %))
                            (:contributions select-step)))]
    (is (.contains (:text tmpl) "{{input}}")
        "select-and-create prompt references the {{input}} template var")
    (is (= {:from :workflow-input}
           (get-in tmpl [:vars "input"]))
        "select-and-create wires input to the bare top-level :workflow-input (no :path)")))
```

**Discovery (shape asymmetry).** The outer `select-and-create` step wires
`input` to the **bare** top-level `:workflow-input` — `{:from :workflow-input}`,
**no** `:path`. This is deliberately distinct from the wrapper steps
(`resolve-worktree`, and the inner `lifecycle` `:prompt-string`), which use
`{:from :workflow-input :path [:input]}` to select the `:input` **field** of a
delegated `:map` input. Because of this, the shared `step-has-input-var-wired?`
helper (which requires the `:path [:input]` field-selecting shape) could NOT be
reused — a first attempt using it failed (`(not (step-has-input-var-wired? …))`),
confirming the asymmetry. The block therefore asserts the actual bare shape
directly. A regress dropping `:vars`/the `{{input}}` template, or mis-wiring to a
non-`:workflow-input` source, now fails green.

File-length guard: the new block + comment pushed `workflow_definitions_test.clj`
from 797 to 814 lines (over the 800 `components/` guard). Trimmed verbose prose
headroom in the TR7, TR8, TR10, TR11, TR13, TR14, and TR2 explanatory comment
blocks (no assertion changes) → file now exactly **800 lines**
(`bb commit-check:file-lengths` clean).

Verification:
- `psi.workflow-loader.workflow-definitions-test` +
  `psi.workflow-loader.incidental-complexity-finder-skill-test` (focused):
  **18 tests, 261 assertions, 0 failures** (+2 over pass-12's 259).
- `clj-kondo --lint` on the test file: 0 errors, 0 warnings.
- `clj-paren-repair` on the test file: Success.
- `bb commit-check:file-lengths`: clean (800, not > 800).

TR15 checked in steps.md. PASS_STATUS: ACTIONABLE_FEEDBACK.

---

## Follow-up execution pass after test-shaper review pass 13 (TR15)

Scanned `steps.md` for newly added actionable unchecked items added by the
preceding review pass (test-shaper pass 13 — TR15, committed `b489b3a2c`).

**Determination: no actionable follow-up items to execute.**

- TR15 (the only item the preceding review pass added) is already fully resolved
  and committed (`b489b3a2c`) — its checkbox is `[x]` with a complete RESOLUTION
  note. No new unchecked items were added after it.
- The single remaining unchecked `- [ ]` in `steps.md` (the "Split step-1
  selection from task-creation into two `:session` steps" item) lives under
  **`## Contingency (non-planned; only if Slice 3 step-1 proves unwieldy)`**. It
  is **out of scope** for this pass because it is:
  - explicitly **non-planned** (section header) and **conditional** — gated on
    "only if Slice 3 step-1 proves unwieldy"; that trigger has not fired
    (Slice 3 step-1 was built as a single `:session` step, loads clean, and is
    locked by tests — never proved unwieldy), and
  - **predates the preceding review pass** (it is original-design contingency
    text, not a review-pass-added item), so per the execution rule "do not
    execute items that predate the preceding review pass" it must not be run.

Working tree clean before this pass (branch `simplifier`); no code/test/doc
change required. Focused suite state unchanged from TR15: 18 tests, 261
assertions, 0 failures; `clj-kondo` 0 findings; file-length guard clean (800).

PASS_STATUS: NO_ACTIONABLE_FOLLOW_UPS.

## 2026-06-01 — Implementation review (independent pass, task-implementation-review skill)

◈ Independent review applying `task-implementation-review`
(`matches(code,design) ∧ follows(code,architecture) ∧ ¬redundant-pattern ∧
¬unnecessary-abstraction ∧ ¬structural-perf-issue`). Verified against live
runtime, code, and CI gates — not just artifacts.

Confirmed sound (no new issue):
- **design↔code match:** SKILL.md encodes `gap` method, `lcc-total ≥ 5.0 ∧
  gap ≥ 2.0`, top-5 guard, `(ns,var,arity,line)` join, evidence + coverage-hint.
  `reduce-incidental-complexity.edn` = two-step shape; `task-lifecycle-in-worktree.edn`
  = three-step P1 wrapper mirroring the loadable `review-implementation-in-worktree.edn`.
- **pattern reuse, not invention:** wrapper is structurally identical to
  `review-implementation-in-worktree.edn` (resolve-worktree→delegate→summary);
  no new abstraction introduced. D1 (`.edn` over design's `.md`) is correctly
  grounded — `.md`-with-EDN-body does not load (`parser.clj:162`).
- **grammar fit:** only `:session`/`:delegate` step types exist (confirmed in
  `target_ir_compiler.clj` — no conditional/skip mechanism), so the F1
  prompt-level `NO_TARGET` short-circuit is the correct grammar-bounded workaround,
  not a flaggable flaw. Summary step is authoritative on the sentinel.
- **tests:** focused suite green (18 tests, 261 assertions, 0 failures);
  `clj-kondo` 0 findings. Tests lock the design's substantive acceptance
  (handoff fields, gate flags `--fail-on new-cycles,new-high-findings
  --max-new-medium-findings 0`, both baselines, two-phase contract, F3 key,
  no-push, NO_TARGET, TR13/14/15 `:context` wiring).
- **docs coherent:** `doc/workflows.md` + `CHANGELOG [Unreleased]` document the
  workflow + skill consistently with design.

One new actionable structural issue (R5-class maintainability, not previously flagged):
- **R6 — shared `workflow_definitions_test.clj` sits exactly at the 800-line
  CI file-length guard.** Task 204's TR13/14/15 commits grew it to the hard limit
  (the two new `deftest`s span lines ~595–800, ≈206 lines). `commit-check:file-lengths`
  passes now but the **next** addition to this shared ns will fail the gate — a
  structural fragility this task introduced. R4 chose to extend the existing ns
  (reasonable, avoids harness drift), but the consequence is unaddressed. A
  dedicated sibling ns already exists (`incidental_complexity_finder_skill_test.clj`);
  the 204 workflow-definition `deftest`s could move into their own ns to relieve
  boundary pressure with no harness drift. Prior pass observed "file-length guard
  clean (800)" but did not flag the boundary as actionable.

PASS_STATUS: ACTIONABLE_FEEDBACK.

---

## R6 — file-length boundary relief via sibling-ns extraction (independent impl-review follow-up)

ITEM: R6 (Implementation review follow-ups — independent pass, task-implementation-review).
The shared `components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`
had grown to **exactly 800** lines (the hard `bb commit-check:file-lengths`
limit for `components/`). Task 204's two workflow-definition deftests
accounted for the headroom-eating tail; TR15 already had to trim TR-comment
prose to fit, so the next addition would have failed the gate.

ACTION: extracted the two task-204 deftests into a new sibling ns
`psi.workflow-loader.task-204-workflow-definitions-test`
(`task_204_workflow_definitions_test.clj`), following the existing
`incidental_complexity_finder_skill_test.clj` split precedent.

- Moved verbatim: `task-lifecycle-in-worktree-test` (Slice 2) and
  `reduce-incidental-complexity-test` (Slice 3), with all `testing` blocks,
  assertions, and TR2/TR7/TR8/TR10/TR11/TR13/TR14/TR15/F1/F3 lock comments.
- New ns carries only the loader-fixture **subset** the two tests use:
  `slurp-workflow-file`, `with-workflow-dir`, `load-edn-only`,
  `input-var-wired?`, `step-has-input-var-wired?`, `step-template-text`.
  (Duplicated, not shared — same approach as the skill-test sibling, which
  shares no fixtures; keeps each file independently loadable, no harness drift.)
- Left in the original ns: `load-edn-with-md-refs`, `pass-status-judge-from-step`,
  `constant-routing-judge`, `constant-routing-step` — still referenced by the
  11 remaining definitions tests (verified each remaining private helper has
  ≥1 caller; no dead-code warnings).

VERIFICATION:
- `workflow_definitions_test.clj`: 800 → **593** lines (full headroom restored).
- `task_204_workflow_definitions_test.clj`: **277** lines.
- `clj-paren-repair`: Success on both files (no changes needed).
- `clj-kondo --lint` both files: errors 0, warnings 0.
- Focused unit suite (`--focus` both nss): **13 tests, 214 assertions, 0
  failures** — the two moved tests retain all assertions; the original ns's 11
  tests are unaffected.
- `bb commit-check:file-lengths`: clean (exit 0; both files < 800).

No production code, EDN workflow, skill, meta, spec, or doc change — this is a
test-file split for CI-boundary relief only. Behaviour and coverage identical.

## 2026-06-01 — Implementation review (independent pass, task-implementation-review skill)

◈ Independent review applying `task-implementation-review`
(`matches(code,design) ∧ follows(architecture) ∧ ¬redundant-pattern ∧
¬unnecessary-abstraction ∧ ¬structural-perf-issue`). Verified against live CLI,
the loadable wrapper precedent, and CI gates — not just artifacts.

Confirmed sound (no new actionable issue):
- **design↔artifact match.** SKILL.md encodes the `gap = lcc-total / max(cc,1)`
  method, `(ns,var,arity,line)` inner-join recipe (with the A1 line-uniqueness
  rationale), `lcc-total ≥ 5.0 ∧ gap ≥ 2.0` filter, top-5 judgment guard,
  evidence + coverage-hint, single-unit scope. `reduce-incidental-complexity.edn`
  = two-step select+create / delegate shape with early-stop; the generated-design
  template embeds the two-phase contract + both baselines + gate flags verbatim.
- **verified recipes still accurate (R5 re-check).** Live CLI confirms
  `gordian gate` accepts `--fail-on new-cycles,new-high-findings` and
  `--max-new-medium-findings`; `gordian local --json` emits `lcc-total` + the six
  per-dimension burdens. The design/template text matches the live tool — no drift.
- **pattern reuse, not invention.** `task-lifecycle-in-worktree.edn` is
  structurally identical to the loadable `review-implementation-in-worktree.edn`
  (resolve-worktree `:session`+`work-on` → `:delegate` → `summary`), with
  `task-lifecycle` substituted for `review-task-implementation` and a sound
  F1 NO_TARGET short-circuit added. No unnecessary abstraction.
- **handoff wiring is the verified pattern.** The outer `lifecycle-in-worktree`
  `:delegate` combines `:prompt-string {:input {:from {:step … :yield :text}}}`
  with a `:context` carrying the step-1 handoff `:yield :text` — exactly the dual
  wiring `gh-issue-implement.edn`'s delegate steps use (verified live). Not a
  deviation from `one_way`.
- **CI green.** Focused suites pass (task-204 + skill-test deftests run within the
  green full suite); `clj-kondo` 0/0 on both EDN workflows; `commit-check:file-lengths`
  clean after R6 split (workflow_definitions 593, task_204 277). Docs +
  `CHANGELOG [Unreleased]` document the workflow + skill coherently with design.
- **prior actionable items closed.** D1 (.edn over .md — grounded in
  `parser.clj:162`), F1, TR13/14/15 `:context` locks, and the R6 file-length
  boundary split (committed `f9f1c5128`, 800→593) are all resolved.

One minor process-hygiene note (non-implementation, recorded not flagged as code
feedback): the R6 file-length follow-up was executed and committed but tracked
only as an implementation.md worklog entry, not as an explicit `- [x]` item in
steps.md's review-follow-ups section. Bookkeeping only — the work is done and
verified; left as a steps.md follow-up below for completeness.

PASS_STATUS: ACTIONABLE_FEEDBACK.

## 2026-06-01 — IR1 (bookkeeping) follow-up executed

Executed the single newly-added unchecked `steps.md` item (IR1) from the
preceding independent task-implementation-review pass. IR1 is pure bookkeeping:
the R6 file-length extraction (commit `f9f1c5128`, shared
`workflow_definitions_test.clj` 800 → 593, sibling
`task_204_workflow_definitions_test.clj` 277) was recorded only as an
implementation.md worklog entry, never as an explicit checked `- [x]` step.

Resolution: in steps.md, marked IR1 itself checked and added an explicit checked
`R6` step under a new "R6 — extract task-204 workflow-definition deftests into a
sibling ns" sub-heading, recording the extraction, the line counts, the
`f9f1c5128` commit, and the file-length verification — so the steps checklist
now reflects the committed work.

Re-verified the committed R6 state (no code/test/EDN change in this pass):
- `wc -l`: `workflow_definitions_test.clj` 593, `task_204_workflow_definitions_test.clj` 277.
- `bb commit-check:file-lengths`: exit 0 (both < 800 `components/` guard).

The Contingency item (split step-1 selection from task-creation) predates this
review pass — it is a non-planned design fallback, never triggered (step-1 is
not unwieldy), so it was deliberately left unchecked per the "do not execute
items that predate the preceding review pass" instruction.

Pure steps.md/implementation.md bookkeeping; no code, test, EDN, or doc change.
PASS_STATUS: REVIEW_COMPLETE.

## 2026-06-01 — Implementation review (independent confirming pass, task-implementation-review skill)

◈ Independent re-review applying `task-implementation-review` (matches(code,design)
∧ follows(architecture) ∧ ¬redundant-pattern ∧ ¬unnecessary-abstraction
∧ ¬structural-perf-issue). Verified live against artifacts, tests, lint, and
file-length gate — not the worklog alone.

Confirmed sound (no new actionable issue):
- **design↔artifact match.** SKILL.md encodes the `gap = lcc-total / max(cc,1)`
  method, the `(ns,var,arity,line)` `@line` inner-join recipe (A1 determinism
  rationale), `lcc-total ≥ 5.0 ∧ gap ≥ 2.0` filter, `sort_by(-.gap)` + `.[0:5]`
  top-5 guard, evidence + coverage-hint, single-unit scope. The frontmatter
  lambda keys on `join(ns,var,arity,line)` (F5). `reduce-incidental-complexity.edn`
  = two-step select+create / delegate with early-stop; the generated-design
  template embeds the two-phase contract, both baselines, the worktree-relative
  gate `--baseline` path, and the `--fail-on …` flags verbatim; A5/A2 key on
  `(ns,var,arity,line)` (F3). `design.md` selector step 2 keys on the same (F4);
  Verified-facts names `review-implementation-in-worktree.edn` as the loadable
  precedent (F6).
- **data-flow shapes consistent.** Outer step-2 `:prompt-string {:type :map
  :fields {:input {:from {:step "select-and-create" :yield :text}}}}` → `{:input
  "<handoff>"}`; wrapper `resolve-worktree` reads `{:from :workflow-input :path
  [:input]}` to extract it. Outer `:delegate :context` carries workflow-original
  + select-and-create yield; wrapper `lifecycle :context` is workflow-original
  only (TR13/TR14). Wiring is the verified pattern; no `one_way` violation.
- **pattern reuse, not invention.** Wrapper is structurally identical to the
  loadable `review-implementation-in-worktree.edn` (resolve-worktree
  `:session`+`work-on` → `:delegate` → `summary`), with `task-lifecycle`
  substituted and a sound F1 NO_TARGET short-circuit. No unnecessary abstraction;
  no structural performance issue (data-only artifacts).
- **early-stop is sound.** Grammar has no conditional/skip; step-2 always runs.
  Step-1 early-stop emits a free-form no-target report (no `worktree_path:`/
  `munera_task_path:` lines), so the wrapper's `resolve-worktree` NO_TARGET branch
  (no `work-on`) and `summary` NO_TARGET branch (no task inspection) both engage
  correctly (F1).
- **CI green (live).** Focused suite — `task-204-workflow-definitions-test` +
  `incidental-complexity-finder-skill-test` + `workflow-definitions-test` —
  **18 tests, 261 assertions, 0 failures**. `clj-kondo --lint` on both EDN
  workflows + all three task-204 test nss: **0 errors, 0 warnings**.
  `bb commit-check:file-lengths` clean (workflow_definitions 593,
  task_204 277, skill-test 340 — all < 800).
- **docs coherent.** `doc/workflows.md` (§Incidental-complexity simplification)
  and `CHANGELOG [Unreleased] → Added` describe the two-step workflow, the
  three-step wrapper, the `(ns,var,arity,line)` join, thresholds, baselines,
  the no-push/PR endpoint, and the NO_TARGET prompt-level handling — all matching
  the live artifacts.
- **prior actionable items all closed.** F1–F6, TR1–TR15, R6 (file-length split,
  `f9f1c5128`), and IR1 (steps.md bookkeeping) are resolved and verified; the
  preceding independent pass already reached REVIEW_COMPLETE after IR1.

No new actionable implementation issues found. The only unchecked steps.md item
is the pre-existing non-planned Contingency (split step-1 selection from
task-creation) — never triggered (step-1 is not unwieldy), correctly left
unchecked; not an outstanding implementation defect.

PASS_STATUS: REVIEW_COMPLETE.

## Test review (task-test-review skill — independent pass)

Applied `task-test-review` (well_formed ∧ ∀b∈design.covers ∧ ¬mock ∧ ¬stub) to
the three task-204 test nss: `task_204_workflow_definitions_test`,
`incidental_complexity_finder_skill_test`, and the shared
`workflow_definitions_test`. Focused run: **7 tests, 102 assertions, 0
failures** (the two task-204-specific nss).

Confirmed sound (no new actionable test issue):

- **well_formed.** Tests are behavior/state/output-asserting with meaningful
  failure messages; each lock carries an explicit regression rationale
  (TR1–TR15, F1–F6) naming the green-passing regress it would catch. No
  interaction assertions.
- **behaviour coverage (∀b ∈ design.acceptance).** Every design acceptance
  criterion has a covering assertion:
  - Skill (Deliverable 1): registers + content-lock (gap method, thresholds,
    single-unit scope, A1 drop rule, F2 (ns,var,arity,line) key, top-5 guard,
    coverage hint) + **executable** recipe (join determinism/losslessness +
    order-independence, qualification filter, A1 drop, gap-descending ranking,
    top-5 cap).
  - Wrapper (`task-lifecycle-in-worktree`): 3-step resolve→lifecycle→summary
    shape/types, work-on tool + {{input}} wiring, lifecycle :delegate target +
    :prompt-string + :context (TR14), NO_TARGET short-circuit (F1), positive-
    path work-on re-call (TR7), summary positive/negative terminal contracts
    (TR10).
  - Outer (`reduce-incidental-complexity`): 2-step shape, work-on + skill,
    bare-:workflow-input wiring (TR15), delegate target/:prompt-string/:context
    (TR13), handoff fields, early-stop, gate flags + both baselines, worktree-
    relative baseline paths (TR11), Phase-0 gate, behaviour-identical
    constraint, A5/A2 (ns,var,arity,line) keys (F3), no-push/PR endpoint (TR8).
- **¬mock ∧ ¬stub.** No mocking of logic. The sole `with-redefs`
  (`with-workflow-dir`) injects loader directory **config** (a filesystem path),
  i.e. infrastructure dependency injection, not a logic mock/stub. Recipe tests
  run real `jq`/`bash` over synthetic JSON fixtures with graceful jq-absent
  structural fallbacks; skill tests load the real SKILL.md.

Deliberate (not a gap): the recipe is exercised with **synthetic** unit-JSON
fixtures rather than a live-repo `bb gordian` run. This is the correct robust
choice — a live-repo assertion would be non-deterministic and brittle as the
codebase's complexity profile shifts, and the synthetic fixtures fully exercise
every recipe behaviour (join/determinism/filter/drop/ranking/cap). The design's
"produces a target … against this repository" is a one-time authoring
validation, not a CI regression target.

No new actionable test issues. The only unchecked steps.md item is the
pre-existing non-planned Contingency (split step-1), correctly left unchecked.

PASS_STATUS: REVIEW_COMPLETE.

## Test review pass 14 (test-shaper)

Applied `test-shaper` (economical ∧ behavior_focused ∧ meaningful_failures —
specifically *cover_by(boundaries)* and *one_test_per_distinct_behavior*) to the
two task-204 test nss. The workflow-definition + skill content/determinism/
filter/drop/ranking locks (TR1–TR15, F1–F6) are strong and mock-free. Two
recipe behaviours that SKILL.md §3/§4 name as distinct contracts are encoded in
the embedded jq recipe but **never exercised executably** — only the
determinism/filter/drop/ranking tests touch the recipe, and all feed `cc ≥ 1`
with ≥1 survivor, so each gap below passes every existing test green:

### TR16 — `max(cc, 1)` matched-zero-cc guard is unexercised

The recipe computes `gap: (.["lcc-total"] / ([$ccmap[.gap_key], 1] | max))`.
SKILL §3 names this a distinct A1 behaviour: "`max(cc, 1)` guards **only** the
*matched zero-cc* case (a matched unit whose `cc` is reported as 0)." Every
executable recipe test (`…-determinism-test`, `…-filter-and-drop-test`,
`…-ranking-and-cap-test`) feeds `cc ≥ 1`, so the `| max` is never load-bearing.
A regress dropping `| max` (so a matched cc=0 unit divides by zero → jq error or
`gap: null`, dropping a genuine qualifying unit) passes all tests green.
Verified live: with `max`, a matched `lcc 30.0 / cc 0` unit yields `gap 30` and
survives; the boundary is real and observable. Per test-shaper
`cover_by(boundaries)` + `meaningful_failures`, add an executable lock feeding a
matched cc=0 unit and asserting it survives with `gap = lcc-total` (the cc=0
boundary), with a jq-absent structural fallback on the `| max` fragment (mirrors
the existing TR12 fallbacks).

### TR17 — empty qualification (the early-stop driver) is content-locked only

Design Locked decision 2: "A real early-stop exists when nothing qualifies." The
recipe's empty-result emission (`[]` when zero units pass the filter) is the
machine signal that drives the workflow's early stop, but it is locked only as
SKILL prose (TR3: "report no qualifying target") — no executable test asserts
the recipe emits `[]` when the qualification filter removes every candidate. The
filter-and-drop test always leaves ≥1 survivor. A regress where the filter or
`.[0:5]` mis-handles the empty case (e.g. emits a stray object, or errors on an
empty array) passes green. Verified live: a sole `lcc 4.0` (sub-threshold) unit
yields `[]`. Per test-shaper `cover_by(boundaries)` + `behavior_focused`, add an
executable lock feeding only sub-threshold/unmatched units and asserting the
recipe emits an empty result (the no-target / early-stop boundary).

Both are test-only additions to
`incidental_complexity_finder_skill_test.clj` (no production/EDN/skill change).
Mind the `components/` 800-line guard when adding.

PASS_STATUS: ACTIONABLE_FEEDBACK.

## Pass 14 — test-review follow-up execution (TR16, TR17)

Executed both review-pass-14 follow-ups; test-only additions to
`components/workflow-loader/test/psi/workflow_loader/incidental_complexity_finder_skill_test.clj`
(no production / EDN / skill change).

### TR16 — max(cc, 1) matched-zero-cc guard (executable lock)

Added sibling deftest `incidental-complexity-finder-recipe-max-cc-guard-test`.
Feeds a single matched unit (`zero/cc`, lcc 30.0, **cc 0**) and asserts it
survives the join + qualification filter with `gap` = 30 — `max(0,1)=1` divides
lcc by 1, not 0. Verified live (`/tmp` recipe run): the cc-0 unit emits
`gap: 30` and qualifies; dropping `| max` would yield `gap: null` → fails
`gap >= 2.0` → dropped. jq-absent fallback locks the recipe fragment
`[$ccmap[.gap_key], 1] | max` (mirrors TR12 fallbacks).

### TR17 — empty-qualification early-stop boundary (executable lock)

Added sibling deftest
`incidental-complexity-finder-recipe-empty-qualification-test`. Feeds two
non-qualifying units — a sub-threshold matched unit (`sub/threshold`, lcc 4.0
< 5.0) and an unmatched `local` row (`unmatched/row`, lcc 30.0, no cc → A1
drop) — and asserts the recipe emits `[]` (`(= "[]" (str/trim out))` + no
surviving `"var":`). Verified live: that input yields `[]`. No recipe fragment
*uniquely* guards the empty case beyond the qualification filter (already
structurally locked in the filter-and-drop fallback), so this behaviour is
jq-required; the jq-absent fallback re-asserts the qualification-filter
fragment so a regress is still caught structurally.

### Verification

- Focused skill-test suite green: 7 tests, 55 assertions, 0 failures
  (+2 tests / +8 assertions over pass-13's 5/47).
- `clj-paren-repair` Success; `clj-kondo` 0 findings (errors 0, warnings 0).
- skill-test file 409 lines (< 800 `components/` guard);
  `bb commit-check:file-lengths` exit 0.

## Test review pass 15 (test-shaper)

Applied `test-shaper` (cover_by(boundaries) ∧ meaningful_failures ∧
behavior_focused) to the two task-204 test namespaces
(`task_204_workflow_definitions_test.clj` + `incidental_complexity_finder_skill_test.clj`).
Focused suite green: 9 tests, 110 assertions, 0 failures (jq present). Coverage
after TR1–TR17/F1–F6 is broad and mock-free (executable recipe locks, prompt/
context handoff locks, NO_TARGET short-circuit, two-phase contract). Two
remaining gaps where a green-passing regress survives:

### TR18 — qualification filter `>=` boundary is never exercised at the exact threshold
The recipe's qualification filter `select(.["lcc-total"] >= 5.0 and .gap >= 2.0)`
is exercised only well above (lcc 30 / gap 7.5) and well below (gap 1.5, lcc 4.0)
the thresholds (`incidental-complexity-finder-recipe-filter-and-drop-test`). No
input sits **exactly** at `lcc-total = 5.0` or `gap = 2.0`, so the inclusive
boundary is unproven: a regress `>=` → `>` (strict, dropping the boundary unit)
passes every existing test green. Per test-shaper `cover_by(boundaries)`, the
inclusive `>=` edge is a named, tunable threshold (Locked decision 2) and a
real, observable boundary — it should be pinned at the exact value.

### TR19 — wrapper `summary` NO_TARGET branch locks only the sentinel, not its substantive contract
`task-lifecycle-in-worktree-test`'s "summary prompt detects NO_TARGET" asserts
only `.contains "NO_TARGET"` + that summary sources the `resolve-worktree` yield.
The prompt's substantive NO_TARGET contract — **ignore the `lifecycle` step
output entirely**, report that **no worktree was created, no task was created,
and no lifecycle ran**, and **do not inspect or invent task artifacts** — is
unlocked. A regress that still detected the sentinel but then inspected/invented
task artifacts (or reported lifecycle outcomes) on a no-target run would pass
green. This is the symmetric companion to TR10 (which locks the positive-path
terminal contract) and TR7's NO_TARGET locks on `resolve-worktree`; the
no-target *summary* contract has no equivalent lock. Per test-shaper
`meaningful_failures`, lock the substantive NO_TARGET summary contract substrings.

Both are test-only additions (no production change); the artifacts (skill +
workflows) are correct as-is — these only strengthen the regression net.

## Pass-15 test-review resolutions (TR18 / TR19)

### TR18 — inclusive `>=` boundary lock (RESOLVED)
Added `incidental-complexity-finder-recipe-boundary-inclusivity-test` to
`incidental_complexity_finder_skill_test.clj` (test-only; same ns, no new ns /
no production Clojure). Feeds two units on the exact thresholds via the existing
`run-jq-recipe` + `named-{local,cc}-unit-json` harness:
- `edge/gapedge` — lcc 10.0, cc 5 → gap **exactly 2.0** (gap boundary)
- `edge/lccedge` — lcc 5.0, cc 1 → lcc **exactly 5.0**, gap 5.0 (lcc boundary)
Both must survive the qualification filter; a `>=`→`>` (strict) regress drops
either boundary unit. jq-absent fallback re-asserts the
`select(.["lcc-total"] >= 5.0 and .gap >= 2.0)` fragment (mirrors TR12/16/17),
so the regress fails green whether or not jq is installed. Skill-test file 442
lines (< 800).

### TR19 — substantive NO_TARGET summary contract lock (RESOLVED)
Added a "summary prompt reports the substantive NO_TARGET contract (TR19)"
`testing` block to `task-lifecycle-in-worktree-test` in
`task_204_workflow_definitions_test.clj` (test-only; same ns, no production/EDN
change). Asserts three `summary-text` substrings already present verbatim in the
EDN summary template: `ignore the \`lifecycle\` step output entirely`,
`no worktree was created, no task was created, and no lifecycle ran`, and
`Do not inspect or invent task artifacts`. Symmetric companion to TR10's
positive-path terminal contract lock. task-204 definitions file 292 lines
(< 800).

### Verification (pass-15)
Focused suite (both task-204 test nss):
`clojure -M:test --config-file tests.edn --focus
psi.workflow-loader.incidental-complexity-finder-skill-test --focus
psi.workflow-loader.task-204-workflow-definitions-test` → **10 tests, 117
assertions, 0 failures**. `clj-kondo` 0 findings on both files;
`clj-paren-repair` Success on both; `bb commit-check:file-lengths` exit 0
(442 + 292 both < 800). Both additions are test-only — the skill recipe and
wrapper EDN are unchanged (already correct); these strengthen the regression net
only.

## Test review (pass 16 — test-shaper)

### TR20 — redundant fixture-builder pair in the skill-test ns (ACTIONABLE)
test-shaper `consistent(fixtures)` + `economical(minimal redundant)`:
`incidental_complexity_finder_skill_test.clj` carries **two** fixture-builder
abstractions for the *same* unit-JSON shape:

- `local-unit-json [line lcc-total]` / `cc-unit-json [line cc]` — hard-code
  `ns "x"` / `var "f"` / `file "x.clj"`; used **only** by the determinism test
  (lines 166–169).
- `named-local-unit-json [unit-ns unit-var line lcc-total]` /
  `named-cc-unit-json [unit-ns unit-var line cc]` — parameterize `ns`/`var`
  (file = `<ns>.clj`); used by every other recipe test (filter/drop, ranking/cap,
  max-cc, empty-qualification, boundary).

The `named-*` builders are a **strict superset**: the plain builders are exactly
`(named-local-unit-json "x" "f" line lcc-total)` / `(named-cc-unit-json "x" "f"
line cc)` (for `ns "x"`, `file "x.clj"` is identical). Maintaining two builders
for one shape is incidental variation — a future change to the unit JSON shape
must be threaded through both, and a reader must learn two near-identical
helpers. Per test-shaper `helpers_that_compress(ceremony) ∧ ¬helpers_that_hide`,
collapse to the parameterized pair.

Fix (test-only, no production/skill/EDN change): delete `local-unit-json` /
`cc-unit-json` and rewrite the four determinism-test call sites (lines 166–169)
to the `named-*` builders with `"x" "f"` — e.g.
`(named-local-unit-json "x" "f" 10 "30.0")`. The determinism test's behaviour is
unchanged (same JSON for `ns "x"`). Verify the focused skill-test +
task-204 suite green, `clj-kondo` 0, `clj-paren-repair` Success,
`bb commit-check:file-lengths` clean.

No other actionable test-shaper feedback: the suite is otherwise strong —
single-concern deftests, explicit arrange/act/assert, jq-absent structural
fallbacks for every executable recipe behaviour (determinism/filter/drop/ranking/
cap/max-cc/boundary), behaviour-focused prompt/EDN-shape locks for both
workflows, and the design-acceptance coverage net (TR1–TR19) is comprehensive.

## Test review pass 16 — TR20 resolution (test-shaper follow-up execution)

Executed TR20 (the sole newly added unchecked review-pass-16 follow-up).

- Deleted `local-unit-json` / `cc-unit-json` from
  `incidental_complexity_finder_skill_test.clj`.
- Rewrote the four determinism-test call sites (the `line-10`/`line-40`
  `local`/`cc` fixtures) to the parameterized superset builders
  `(named-local-unit-json "x" "f" …)` / `(named-cc-unit-json "x" "f" …)`.
  Byte-identical JSON for `ns "x"`/`var "f"`/`file "x.clj"`, so the determinism
  test's behaviour and assertions are unchanged — a pure structural collapse.
- Trimmed the now-obsolete TR6 reference in the `named-local-unit-json` docstring
  (the builders now serve every recipe test, not just filter/drop).

Verification (test-only; no production/skill/EDN change):
- focused suite (`incidental-complexity-finder-skill-test` +
  `task-204-workflow-definitions-test`): 10 tests, 117 assertions, 0 failures —
  identical to pass-15, confirming the refactor preserved behaviour.
- `clj-kondo` 0 errors / 0 warnings (no unused-var warning ⇒ both deleted
  builders confirmed gone, `named-*` still referenced).
- `clj-paren-repair` Success.
- skill-test file 429 lines (< 800); `bb commit-check:file-lengths` exit 0.

One fixture-builder pair now serves every recipe test (test-shaper
`consistent(fixtures)` + `economical`): a future unit-JSON shape change threads
through one pair; a reader learns one helper.

## Test review (pass 17 — test-shaper)

Re-applied `test-shaper` to the two task-204 test namespaces after the pass-16
TR20 collapse. Focused suite green: 10 tests, 117 assertions, 0 failures (jq
present). The coverage net is broad and mock-free — executable recipe locks
(join losslessness, order-independence, filter, A1 drop, ranking, top-5 cap,
max(cc,1) zero-cc guard, empty-qualification, `>=` boundary inclusivity), each
with a jq-absent structural fallback; prompt/`:context` handoff locks and the
NO_TARGET / two-phase / no-push contracts for both workflows. One genuine
`cover_by(invariants)` gap remains:

### TR21 — the recipe's emitted-evidence projection is entirely unlocked
The skill's `gap` recipe ends with a final `map({...})` projection (SKILL.md
§ join recipe, the last map) that re-emits the chosen target's evidence:
`ns`, `var`, `arity`, `file`, `line`, `end_line`, `lcc_total`, the six
per-dimension burdens (`flow_burden`, `state_burden`, `shape_burden`,
`abstraction_burden`, `dependency_burden`, `working_set`), `findings`, `cc`,
and `gap`. This projection **is** the design's named step-5 acceptance ("emit
one chosen target with evidence: `ns`, `var`, `arity`, file, line range,
`lcc-total` with per-dimension burdens, `cc`, `gap`, the `local` findings, and
a coverage hint") — the workflow's step-1 prompt consumes exactly these fields
to build the generated task's incidental-complexity evidence block.

No test exercises this projection. Every executable recipe test asserts only
`ns`/`var`/`line`/`cc`/`gap` survival (and the synthetic `named-*-unit-json`
fixtures *supply* the burden fields but nothing asserts they reappear in
output); there is no structural (jq-absent) lock on the projection map either.
A regress that drops a projected field (e.g. omits `end_line`, `findings`, or a
burden dimension) or mis-renames one (`flow_burden` → `flow-burden`, a key the
prompt would not read) passes every existing test green, silently degrading the
evidence the generated task is built from. Per test-shaper
`cover_by(invariants)` + `behavior_focused` (the projection is the observable
contract output, not an implementation detail), the emitted-evidence shape
should be pinned.

Fix (test-only, no production/skill/EDN change — the recipe is correct as-is):
add a projection-contract test to `incidental_complexity_finder_skill_test.clj`
reusing the existing `run-jq-recipe` + `named-*-unit-json` harness. Feed one
qualifying matched unit and assert the surviving object carries every projected
evidence key with its expected value (`end_line`, `lcc_total`, the six
`*_burden`/`working_set` dimensions, `findings`, `cc`, `gap`). Add the
mirroring jq-absent structural fallback locking the projection-map key names
(`flow_burden:`, `state_burden:`, …, `end_line:`, `lcc_total:`) verbatim in the
recipe, matching the established TR12/16/17/18 fallback convention.

No other actionable test-shaper feedback: the suite is otherwise strong —
single-concern deftests, explicit arrange/act/assert, mock-free, jq-absent
fallbacks for every recipe branch, behaviour-focused prompt/EDN-shape locks for
both workflows, and the design-acceptance net (TR1–TR20, F1–F6) is comprehensive.

### TR21 follow-up execution (test review pass 17 — test-shaper) — RESOLVED

Executed the sole newly-added actionable item (the trailing Contingency step-1
split predates every review pass and is conditional/untriggered). Test-only — no
production/skill/EDN change; the recipe is correct as-is.

Added `incidental-complexity-finder-recipe-projection-contract-test` to
`incidental_complexity_finder_skill_test.clj`. Grounded against the live
SKILL.md projection (`.psi/skills/incidental-complexity-finder/SKILL.md`,
recipe tail):

```
| map({ns, var, arity,
       file, line, end_line: .["end-line"],
       lcc_total: .["lcc-total"],
       flow_burden: .["flow-burden"], state_burden: .["state-burden"],
       shape_burden: .["shape-burden"], abstraction_burden: .["abstraction-burden"],
       dependency_burden: .["dependency-burden"], working_set: .["working-set"],
       findings,
       cc, gap})
```

**Fixture shape discovery.** The existing `named-local-unit-json` hard-codes
every burden dimension to `1` and `working-set` to `1`, so a projection test
reusing it could not distinguish which output key maps to which source
dimension — a `flow_burden`/`state_burden` swap regress would pass. Added a
dedicated `evidence-local-unit-json` builder giving the six burden dimensions
DISTINCT values (`flow-burden` 11 … `working-set` 16) plus two `findings`
entries, so the dash→underscore rename mapping is pinned per dimension. Kept it
as a separate, purpose-named builder (not folded onto the `named-*` pair) — the
TR20 collapse argued one builder per *shape*, and this is the same JSON shape;
but the distinct-burden values are the test's whole point, so it reads as a
focused fixture rather than incidental variation. (If a future pass deems it
collapsible, `evidence-local-unit-json` could take six burden args.)

**jq-present branch:** feed one qualifying matched unit (lcc 30.0, cc 4 → gap
7.5) and assert every projected key survives with its expected value — identity
(`ns`=proj, `arity`=null, `file`=proj.clj, `line`=10), the renamed `end_line`=42,
`lcc_total`=30, `flow_burden`=11 … `working_set`=16, both `findings` entries,
`cc` present, `gap`=7.5. Verified the live recipe emits exactly this (ran the
recipe by hand against the distinct-burden fixture before writing the asserts).

**jq-absent fallback** (per the TR12/16/17/18 convention): lock each projected
key name verbatim — the eight bare-shorthand keys (`ns var arity file line
findings cc gap`) present in the recipe, and the eight renamed fields as
`<underscore>: .["<dash>"]` (e.g. `end_line: .["end-line"]`,
`flow_burden: .["flow-burden"]`). A dropped or mis-renamed field now fails green
whether or not jq is installed.

**Verification.** Focused ns green: **9 tests, 78 assertions, 0 failures** (+1
test over pass-16's 8 deftests). Full `bb clojure:test:unit` suite green.
`clj-kondo` 0 findings. `clj-paren-repair` Success. skill-test file **524 lines**
(< 800 `components/` guard); `bb commit-check:file-lengths` exit 0.

PASS_STATUS: REVIEW_COMPLETE (TR21 was the sole newly-added actionable item;
this follow-up closes it).

🔁 PATTERN (continues 204): a recipe's final `map({...})` projection is the
observable contract output (the evidence the generated task is built from), yet
the recipe coverage tests asserted only `ns/var/line/cc/gap` survival — the
rename-and-re-emit projection went uncovered until a pass re-derived
"∀ acceptance field. ∃ test. asserts it survives". The fixtures *supplied* the
burden fields, which is exactly what made the gap invisible: data present in
input ≠ data asserted in output.
🔁 PATTERN (continues): a projection-rename test needs a fixture with DISTINCT
per-field values; reusing a fixture that hard-codes a shared sentinel (here all
burdens = 1) cannot catch a field-swap regress.

## 2026-06-01 — Test review pass 18 (test-shaper, independent)

Applied `test-shaper` fresh to the full task-204 test surface:
`task_204_workflow_definitions_test.clj` (292 lines, 2 deftests) and
`incidental_complexity_finder_skill_test.clj` (524 lines, 9 deftests). Re-ran
both ns + full suite: green. `clj-kondo --lint` both files: 0 errors, 0 warnings.
File-length guard: 292 / 524 (< 800).

Criteria assessed — `simple ∧ consistent ∧ robust ∧ economical`:

- **simple** ✓ — single-concern `testing` blocks; explicit arrange/act/assert;
  minimal incidental setup via the shared `named-*-unit-json` / `run-jq-recipe`
  helpers (compress ceremony, do not hide intent).
- **consistent** ✓ — uniform TRn naming, jq-availability guard + structural
  fallback pattern repeated identically (TR12/16/17/18/21), consistent
  state/output assertion style. TR20 already collapsed the redundant
  fixture-builder pair.
- **robust** ✓ — deterministic: IO controlled via per-call temp fixtures with a
  `jq --version` guard and structural fallback when jq is absent
  (`control(io) ∧ ¬flaky`). Behavior-focused: every assertion is over slurped
  SKILL.md content, loaded workflow definitions, or recipe `:out`/`:exit` — no
  `with-redefs`, no mocks/stubs, no interaction assertions
  (`testing-without-mocks` honoured). Failure messages explain the contract
  violated.
- **economical** ✓ — each TR closes a distinct regress that previously passed
  green (documented per pass); boundary inclusivity (TR18), order-independence
  (TR4), empty-qualification early-stop (TR17), zero-cc guard (TR16), top-5 cap
  (TR9), projection rename (TR21) are all covered by representative cases, not a
  case explosion.

Behaviour↔acceptance audit (`∀b ∈ design-acceptance. ∃t. covers(t,b)`): every
named Deliverable-1/2 behaviour is bound to either an executable recipe test or
a content/definition content-lock. The only design behaviour that is
deliberately prose-only is the **step-6 coverage hint** (a manual `rg -l
'deftest|<var>'` judgment step, not part of the deterministic jq recipe, so it
cannot be locked against the recipe) and the **step-5 top-5 essential-vs-
incidental judgment guard** (judgment-bearing prose, not executable) — both are
correctly TR3 content-locked rather than over-specified with a brittle
executable assertion. No new mock/stub/interaction smell, no redundant or flaky
test, no incidental-variation drift.

**Finding: no new actionable test-shaping issue.** Seventeen prior passes
(TR1–TR21) have driven the surface to high quality across all four dimensions;
a fresh pass re-derives the same coverage with no uncovered regress remaining.

PASS_STATUS: REVIEW_COMPLETE

---

## Docs review (review-task-docs skill) — pass 1

Scope: user-facing docs only (`README.md`, `doc/`, `CHANGELOG.md`); task
artifacts/tests/internal excluded per skill scope.

Checklist verdict:

1. **New behaviours reflected** ✅ — `doc/workflows.md` §"Incidental-complexity
   simplification" (l.599) documents the `reduce-incidental-complexity` workflow:
   `/delegate` invocation, the `gap = lcc-total / max(cc,1)` selection method,
   the two-step shape, the `task-lifecycle-in-worktree` wrapper, NO_TARGET
   early-stop, and the Phase-0/Phase-1 behaviour-preserving contract.
2. **Removed behaviours** ✅ — none (purely additive task); no stale refs.
3. **Changelog** ✅ — `[Unreleased] → Added` carries an accurate, user-facing
   `reduce-incidental-complexity` entry naming the `incidental-complexity-finder`
   skill, the gap formula, early-stop, and no-push/PR endpoint. Footer
   `[Unreleased]:` link present (bb-managed).
4. **Examples accurate** ✅ — `/delegate reduce-incidental-complexity` matches the
   registered workflow name.
5. **Consistency** ✅ — names, thresholds, and flags cross-checked against the
   live implementation:
   - thresholds `lcc-total ≥ 5.0 ∧ gap ≥ 2.0` ≡ SKILL.md §recipe + outer
     workflow prompt;
   - join key `(ns,var,arity,line)` ≡ SKILL jq recipe;
   - gate flags `--fail-on new-cycles,new-high-findings --max-new-medium-findings 0`
     ≡ outer-workflow generated-design contract;
   - NO_TARGET sentinel ≡ wrapper `resolve-worktree`/`summary` steps;
   - three-step wrapper (`resolve-worktree → task-lifecycle → summary`) ≡
     `task-lifecycle-in-worktree.edn`.
   - Wrapper file is `.edn` (not `.md`); docs reference it by name only, no
     extension claimed → no inconsistency.
   - No exhaustive workflow enumeration in §"User-facing workflow commands"
     (examples-only) and no TOC → nothing else to update.

**Finding: no new actionable docs issue.** User docs are accurate, complete, and
consistent with meta/spec/code. No follow-up steps added.

PASS_STATUS: REVIEW_COMPLETE

## code-shaper review (pass 1)

Applied `code-shaper` (`simple ∧ consistent ∧ robust → shape`) to the task's
deliverables: the SKILL.md `gap` jq recipe, the two EDN workflows
(`reduce-incidental-complexity.edn`, `task-lifecycle-in-worktree.edn`), and the
two task-204 test namespaces.

**Production deliverables — clean.** The jq recipe is a single canonical
artifact (single responsibility, locally comprehensible). Both EDN workflows are
consistent with their sibling precedents (`gh-issue-implement.edn`,
`review-implementation-in-worktree.edn`): consistent step shapes, handoff
wiring, and `:context`/`:prompt-string` idioms. No actionable production issues.

**Test harness — incidental duplication (actionable).** The recipe-test cluster
in `incidental_complexity_finder_skill_test.clj` has accreted two repeated,
incidental fixtures across its 7 recipe `deftest`s — a direct
`consistent(idioms)` + `locally_comprehensible` shape defect (each call site
forces the reader to re-decode the literal, and a change must thread through all
sites). These were introduced incidentally: TR12 propagated the jq-availability
guard to every test when adding the structural-fallback branches, and no pass
since has factored the duplication.

- **CS1 — `jq`-availability guard literal duplicated 7×.** The predicate
  `(try (zero? (:exit (shell/sh "jq" "--version"))) (catch Exception _ false))`
  appears verbatim at lines 158/213/278/342/375/409/462. Its *meaning* ("is jq
  available?") is buried in a try/catch literal; the intent is illegible at the
  call sites and a change (e.g. caching, a different probe) must edit 7 places.
  Fix: extract a named `jq-available?` predicate (`defn-`, memoised or plain),
  and call it at each `if` site. Mechanical, behaviour-identical.

- **CS2 — recipe-test `let` preamble duplicated 7×.** Every recipe `deftest`
  opens with the same
  `(let [{:keys [skill]} (incidental-complexity-finder-skill)
         body (slurp (io/file (:file-path skill)))
         recipe (extract-jq-recipe body)] (is (some? recipe) …) …)`
  shape (8 `body`-slurps, 7 `recipe`-extracts). This is a repeated
  setup idiom that obscures each test's distinct payload. Fix: extract a single
  `(defn- skill-recipe [] …)` helper returning the recipe (it can carry the
  `(some? recipe)` floor assertion or callers keep it), collapsing the preamble
  to one line per test. Behaviour-identical; reduces the working set a reader
  must hold per test.

Both are test-only (no production/skill/EDN change), keep all assertions
identical, and must leave the focused skill-test + task-204 suite green,
`clj-kondo` 0, `clj-paren-repair` Success, and `bb commit-check:file-lengths`
clean (the file is 524 lines; the extraction reduces it).

PASS_STATUS: ACTIONABLE_FEEDBACK

---

## code-shaper review pass 1 — CS1/CS2 resolution (follow-up execution)

Executed the two code-shaper follow-ups against
`components/workflow-loader/test/psi/workflow_loader/incidental_complexity_finder_skill_test.clj`.
Both test-only, behaviour-identical — no production/skill/EDN change.

- **CS1 — extracted `jq-available?`.** Added
  `(defn- jq-available? [] (try (zero? (:exit (shell/sh "jq" "--version"))) (catch Exception _ false)))`
  and replaced all 7 `(if (try …))` recipe-guard call sites with
  `(if (jq-available?))`. The guard's intent is now named at every call site.

- **CS2 — extracted `skill-recipe`.** Added
  `(defn- skill-recipe [] (let [{:keys [skill]} (incidental-complexity-finder-skill)] (extract-jq-recipe (slurp (io/file (:file-path skill))))))`
  and collapsed all 7 recipe `deftest` preambles to `(let [recipe (skill-recipe)] …)`.
  The determinism/ranking/projection tests retain their additional per-test
  bindings (fixtures, `gap-of`, `gap-key`). The content-lock test, which uses
  `body` directly rather than the recipe, is intentionally left on its
  `{:keys [skill]}`/`body` preamble. Net: eliminated 7 body-slurps + 7
  recipe-extracts (one body-slurp remains in the content-lock test).

VERIFICATION: focused skill-test ns green
(`clojure -M:test --focus psi.workflow-loader.incidental-complexity-finder-skill-test`:
**9 tests, 78 assertions, 0 failures** — identical to pass-17's TR21 baseline,
confirming a pure refactor); `clj-kondo` 0 findings; `clj-paren-repair` Success;
`bb commit-check:file-lengths` exit 0; file 524 lines (< 800).

PASS_STATUS: FOLLOW_UPS_EXECUTED

---

## task-implementation-review (independent pass, 2026-06-03)

Applied `task-implementation-review` (matches-design ∧ follows-architecture ∧
flag new-pattern-where-reusable ∧ flag unnecessary-abstraction ∧ flag
structural-perf) to the three shipped artifacts (`incidental-complexity-finder`
SKILL.md, `task-lifecycle-in-worktree.edn`, `reduce-incidental-complexity.edn`)
+ the two task-204 test namespaces + docs.

**Verdict: clean on code/architecture, one coherence defect in a task artifact.**

- **Code↔design — matches.** The `.edn` wrapper mirrors the loadable
  `review-implementation-in-worktree.edn` precedent (D1-corrected design);
  outer step-1 tools/skills/handoff fields and step-2 `:delegate`/`:prompt-string`
  wiring conform to the design's verified contract; skill encodes the
  `gap`/qualification/top-5-guard/(ns,var,arity,line) recipe. No invented
  pattern (reuses the verified wrapper shape), no unnecessary abstraction, no
  structural-perf concern (workflow-as-data; no production Clojure).
- **Tests — green.** Focused `task-204-workflow-definitions-test` +
  `incidental-complexity-finder-skill-test`: 11 tests, 136 assertions, 0
  failures.
- **Docs — accurate.** `doc/workflows.md` §"Incidental-complexity
  simplification" + CHANGELOG `[Unreleased]` describe the shipped two-step
  workflow, `.edn` three-step wrapper, NO_TARGET short-circuit, and two-phase
  contract faithfully.

**IR-A (actionable, minor) — plan.md stale on the wrapper file-form.** D1
forced the wrapper from the planned `.md`-with-EDN-body to `.edn` (the `.md`
form does not load; documented in implementation.md and corrected in design.md's
F6/D1 note). design.md and the shipped artifact agree on `.edn`, but **plan.md
was never updated**: "Artifact locations" still reads
`.psi/workflows/task-lifecycle-in-worktree.md` … `.md`-with-EDN-body form,
mirroring it` (lines ~63–64), and the "Verified grammar anchors" / "Approach"
still cite `implement-task-in-worktree.md` as the loadable precedent without the
D1 correction (lines ~40,46,72,140). Per Munera (`plan.md changes ↔ approach
changes`) the approach changed; plan.md should reflect the `.edn` wrapper +
`review-implementation-in-worktree.edn` precedent so a future reader is not
misled into the non-loadable form. Low-cost doc fix; no code change.

PASS_STATUS: ACTIONABLE_FEEDBACK

## 2026-06-03 — Implementation review (independent pass) follow-up executed (IR-A)

Executed the single newly-added unchecked `steps.md` item (IR-A) from the
independent task-implementation-review pass. (The trailing Contingency step-1
split predates every pass and is conditional/untriggered — left unchecked.)

IR-A = plan(spec)↔shipped-artifact coherence gap: plan.md still described the
`task-lifecycle-in-worktree` wrapper as a `.md`-with-EDN-body file
"mirroring `implement-task-in-worktree.md`", contradicting the shipped
`.psi/workflows/task-lifecycle-in-worktree.edn` (D1 deviation — the live
`workflow-loader` parser rejects any `.md` body that begins with an EDN map
[`parser.clj:162`, `body-starts-with-edn-map?`]; `implement-task-in-worktree.md`
itself does not load; the loadable precedent is
`review-implementation-in-worktree.edn`). design.md (F6/D1 note) and this
implementation.md (Slice-2 D1 entry) already carried the correction; only plan.md
was stale (per Munera `plan.md changes ↔ approach changes`).

Reconciled plan.md in five places (plan-only; no code/test/EDN change):

1. **Artifact locations** — wrapper path `.md` → `.edn` (multi-step `.edn` map,
   sibling to the loadable `review-implementation-in-worktree.edn`), with an
   explicit D1 deviation note citing the live rejection.
2. **Key decisions → Worktree continuity** — "structurally identical to the
   verified `implement-task-in-worktree`" → "structurally identical to the
   loadable `review-implementation-in-worktree.edn` (the `.edn` realisation of the
   intended `implement-task-in-worktree` shape — see D1)".
3. **Grammar-conformant handoff wiring** precedent — `implement-task-in-worktree.md`
   → loadable `review-implementation-in-worktree.edn`.
4. **Verified grammar anchors** — replaced the `implement-task-in-worktree.md`
   anchor block with a `review-implementation-in-worktree.edn` block noting the
   `.md` form does not load.
5. **Slice 2** — `.md`-with-EDN wrapper → `.edn` wrapper mirroring the loadable
   `review-implementation-in-worktree.edn`.

All remaining `implement-task-in-worktree` mentions in plan.md are now inside
D1-correction context (named as the intended-but-non-loadable shape). Verified
the shipped artifact is `.psi/workflows/task-lifecycle-in-worktree.edn`.
design ↔ plan ↔ implementation now coherent on the wrapper file form.

Doc-only (plan.md) — no code/test/skill/EDN change. IR-A checked in steps.md.
PASS_STATUS: REVIEW_COMPLETE.

## task-implementation-review (independent pass, 2026-06-03, confirming)

Re-applied `task-implementation-review` to the three shipped artifacts
(`incidental-complexity-finder/SKILL.md`, `task-lifecycle-in-worktree.edn`,
`reduce-incidental-complexity.edn`) + the two task-204 test namespaces + docs,
after the IR-A plan.md reconciliation.

**Verdict: REVIEW_COMPLETE — no new actionable issues.**

- **Code↔design — matches.** Outer workflow is the verified two-step shape
  (`select-and-create` `:session` with `incidental-complexity-finder`/`gordian`/
  `code-shaper` skills + early-stop + two-phase generated-design contract +
  `worktree_path:`/`munera_task_path:` handoff; `lifecycle-in-worktree`
  `:delegate` → `task-lifecycle-in-worktree` with the grammar-conformant
  `:prompt-string {:type :map :fields {:input {:from {:step "select-and-create"
  :yield :text}}}}` and the `[workflow-original + select-and-create yield]`
  `:context`). Wrapper is the three-step `resolve-worktree`(`:session`,+work-on)
  → `lifecycle`(`:delegate` `:target "task-lifecycle"`) → `summary`(`:session`)
  adapter mirroring the loadable `review-implementation-in-worktree.edn`, plus
  the NO_TARGET short-circuit for the early-stop handoff. Skill encodes the
  `gap = lcc-total / max(cc,1)` recipe, the inner-join-on-local-side drop rule,
  the `(ns,var,arity,line)` determinism key, the `lcc-total ≥ 5.0 ∧ gap ≥ 2.0`
  filter, and the top-5 essential-vs-incidental guard.
- **Architecture — follows.** Pure capability-catalog artifacts (skill +
  `.edn` workflows); no production Clojure; reuses the verified wrapper pattern
  (no invented pattern, no unnecessary abstraction, no structural-perf concern).
- **Grounding — live.** `bb gordian` confirms `local`/`complexity`/`gate`/
  `diagnose` subcommands + `--json`/`--edn` exist, so the embedded verbatim
  recipes/commands resolve.
- **Tests — green** via the real loader (`load-workflow-definitions` over the
  live `.psi/workflows` files): 11 tests, 136 assertions, 0 failures. Both
  task-204 test files < 800 (`292`, `524`).
- **Docs — accurate.** `doc/workflows.md` §"Incidental-complexity
  simplification" + CHANGELOG `[Unreleased]` describe the two-step workflow,
  three-step `.edn` wrapper, NO_TARGET behaviour, and two-phase contract
  faithfully.
- **Coherence — clean.** plan.md's IR-A reconciliation holds: the only remaining
  `implement-task-in-worktree.md` mention (Artifact locations) is inside an
  explicit **D1 deviation** note; design/plan/implementation/artifact all agree
  on the `.edn` wrapper form.

Only unchecked steps.md item is the pre-existing, untriggered Contingency
(split step-1 selection from task-creation) — conditional, not actionable. No
follow-up items added.

PASS_STATUS: REVIEW_COMPLETE

## task-test-review (independent pass, 2026-06-03)

Applied `task-test-review` to the two task-204 test namespaces
(`task_204_workflow_definitions_test.clj`,
`incidental_complexity_finder_skill_test.clj`) against the design behaviours.

**Verdict: ACTIONABLE_FEEDBACK — one minor coverage gap.**

- **Green.** Focused run: 11 tests, 136 assertions, 0 failures.
- **Well-formed.** Tests assert state/outputs (loader-definition shapes, recipe
  JSON outputs, prompt substrings), not interactions. The recipe tests drive the
  *real* embedded `jq` recipe over synthetic fixtures (no mocks/stubs of logic);
  the jq-absent fallback degrades to structural recipe-fragment locks. The only
  redefinition is the inherited loader fixture's `with-redefs` of
  `loader/global-workflow-dirs`/`project-workflow-dir` to point at a temp dir —
  configuration injection (nulling the global/project dir scan), not logic
  mocking; consistent with the sibling `workflow-definitions-test` fixture. No
  change warranted.
- **Coverage — broad and deep.** TR1–TR21 lock the skill recipe (gap method,
  qualification filter + inclusive boundary, A1 drop, `(ns,var,arity,line)`
  determinism, ranking, top-5 cap, max(cc,1) guard, empty-qualification early
  stop, evidence projection) and both workflows (two/three-step shapes,
  `:delegate` targets, `:prompt-string`/`:context` wiring, handoff fields,
  early-stop, gate flags + worktree-relative baselines, two-phase contract,
  no-push/PR endpoint).

**TT-A (actionable, minor) — step-1 `:skills` membership only partly locked.**
The design (Deliverable 2, Step 1) names **three** required step-1 skills:
`incidental-complexity-finder`, `gordian`, and `code-shaper`. The shipped
`reduce-incidental-complexity.edn` declares all three
(`:skills ["incidental-complexity-finder" "gordian" "code-shaper"]`), but
`reduce-incidental-complexity-test` asserts only
`(some #{"incidental-complexity-finder"} (:skills select-step))`. A regress
dropping `gordian` or `code-shaper` — the skills that back the selection
methodology and refactor-shaping framing — passes the suite green. Per
`∀b ∈ behaviour(design). ∃t. covers(t,b)`, lock all three named skills.
(Only the build-time manual verification at steps.md line ~152 mentions all
three; no regression test anchors them.)

PASS_STATUS: ACTIONABLE_FEEDBACK

---

### TT-A resolution (test-review pass 18 follow-up — task-test-review)

Executed TT-A. Extended the existing `select-and-create` skill assertion in
`task_204_workflow_definitions_test.clj`'s `reduce-incidental-complexity-test`
(`testing` block renamed "… + all three design-named skills") with two new
assertions — `(some #{"gordian"} (:skills select-step))` and
`(some #{"code-shaper"} (:skills select-step))` — alongside the existing
`incidental-complexity-finder` lock. Grounded against the shipped EDN, which
declares `:skills ["incidental-complexity-finder" "gordian" "code-shaper"]`.

Test-only — no production/skill/EDN change. A regress dropping `gordian` or
`code-shaper` from step-1's `:skills` now fails green.

Verification: focused `psi.workflow-loader.task-204-workflow-definitions-test`
green (2 tests, 60 assertions, 0 failures — +2 over the prior 58); `clj-kondo`
0 findings; `clj-paren-repair` Success; task-204 definitions file 301 lines
(< 800); `bb commit-check:file-lengths` exit 0.

PASS_STATUS: REVIEW_COMPLETE

---

## 2026-06-03 — Test review pass 19 (task-test-review, independent)

Applied `task-test-review` (`well_formed ∧ ∀b∈behaviour(design).∃t.covers(t,b) ∧
infra_deps injectable/nullable/¬mock`). Tests are well-formed; infra deps are
clean — real loader/skill-loader over temp dirs, real `jq` with a jq-availability
guard + structural fallback, no mocks/stubs (`with-redefs` only redirects the
workflow-dir config to a real temp dir, not behaviour). Coverage is otherwise
strong after passes 1–18.

Three **named design Step-1 / Phase-1 behaviours embedded verbatim in the
shipped `reduce-incidental-complexity.edn` step-1 prompt are NOT locked by any
test** (`∃b∈behaviour(design). ¬∃t.covers(t,b)`); each is a substring lock of
the same kind the existing tests already use for the gate flags / baselines:

- **TT-B — base-refresh.** Design Step 1 first bullet: "Refresh base:
  `git fetch origin master`; treat `origin/master` as the authoritative base"
  (+ "Base the worktree on `origin/master`"). The EDN emits all three, but
  `reduce-incidental-complexity-test` asserts no `git fetch origin master` /
  `origin/master` substring. A regress dropping the base-refresh or rebasing the
  worktree off stale local `master` passes green.
- **TT-C — baseline *capture commands*.** Design Step 1 names the capture
  invocations `bb gordian local --json` (before-local.json) and
  `bb gordian diagnose --edn` (before-diagnose.edn). The test asserts only the
  output *filenames* (`before-local.json` / `before-diagnose.edn`); the
  generating commands are unlocked. A regress to e.g.
  `bb gordian local --sort total --json` (the selector call, NOT a valid baseline
  per the EDN's own "bare, NO `--sort`" note) or a wrong diagnose flag passes
  green.
- **TT-D — A5/A2 direction-of-change.** The substantive Phase-1 acceptance is
  *directional*: A5 "the target unit's `lcc-total` … **decreased** versus its
  `before-local.json` value" and A2 "the **after total is strictly less than the
  before total**". The test (`F3 lock`) locks only the `(ns, var, arity, line)`
  *key*, not the direction. A paraphrase/regress weakening "decreased" →
  "changed" or "strictly less" → "not greater" passes green while gutting the
  acceptance.

Added TT-B/TT-C/TT-D as unchecked follow-ups in steps.md. All test-only
substring locks on the shipped EDN; no production/skill/EDN change.

PASS_STATUS: ACTIONABLE_FEEDBACK

## 2026-06-03 — Test review pass 19 follow-up executed (TT-B / TT-C / TT-D)

Executed the three newly-added unchecked follow-ups from review pass 19. All
test-only substring locks on the shipped `reduce-incidental-complexity.edn`
step-1 prompt — no production/skill/EDN change. Each verified present in the EDN
before locking, so all three are regression guards (not behaviour changes).

Added to `reduce-incidental-complexity-test`
(`components/workflow-loader/test/psi/workflow_loader/task_204_workflow_definitions_test.clj`):

- **TT-B** — "locks the origin/master base-refresh" block: `git fetch origin
  master`, "Treat `origin/master` as the authoritative base", "Base the worktree
  on `origin/master`" (3 asserts).
- **TT-C** — "locks the baseline capture commands" block: `bb gordian local
  --json` and `bb gordian diagnose --edn` (the generating commands, distinct from
  the already-locked output filenames) (2 asserts).
- **TT-D** — "locks the A5/A2 direction-of-change" block: "decreased versus its
  `before-local.json` value" (A5) and "after total is strictly less than the
  before total" (A2), companion to the existing F3 key lock (2 asserts).

Verification: focused task-204 ns green (2 tests, 67 assertions, 0 failures —
+7 over pass-18's 60); `clj-kondo` 0 findings; `clj-paren-repair` Success; file
337 lines (< 800); `bb commit-check:file-lengths` exit 0. steps.md TT-B/TT-C/TT-D
ticked.

PASS_STATUS: REVIEW_COMPLETE

## 2026-06-03 — Test review pass 20 (task-test-review, independent)

Applied `task-test-review` (`well_formed ∧ ∀b∈behaviour(design).∃t.covers(t,b) ∧
infra_deps injectable/nullable/¬mock`) fresh to the two task-204 test namespaces.
Tests are well-formed and green (11 tests, 145 assertions, 0 failures across
`task-204-workflow-definitions-test` + `incidental-complexity-finder-skill-test`).
Infra deps are clean — real `workflow-loader`/`skills` loaders over temp dirs,
real `jq` with an availability guard + structural fallback, no mocks/stubs
(`with-redefs` only redirects the workflow-dir config to a real temp dir, not
behaviour). Coverage is otherwise strong after passes 1–19 (gate flags,
baselines + capture commands, worktree-relative paths, A5/A2 key + direction,
two-phase contract, no-push/PR, base-refresh, the full skill recipe surface, the
wrapper `:context` locks).

One **named design early-stop behaviour embedded verbatim in the shipped
`reduce-incidental-complexity.edn` step-1 prompt is NOT fully locked**
(`∃b∈behaviour(design). ¬∃t.covers(t,b)`):

- **TT-E — early-stop "no task" half.** Design Deliverable 2, Step 1
  **Early stop** bullet: "if no qualifying unit exists, stop and report — do not
  create a worktree **or task**". The early stop has TWO suppressed effects: no
  worktree AND no task. The EDN step-3 emits both `Do NOT create a worktree` and
  `Do NOT create a task`, but `reduce-incidental-complexity-test`'s early-stop
  block asserts only `.contains "Do NOT create a worktree"` (plus the
  `no unit qualif` sentinel). The task half is unlocked: a regress dropping
  `Do NOT create a task.` — letting the workflow create an orphan task dir on a
  no-target run while still skipping the worktree — passes every existing test
  green. This is the same TR13/TR14-class symmetry gap (one half of a two-part
  contract locked, the sibling half left uncovered) and the same substring-lock
  kind TT-B/C/D already use. Fix: extend the existing
  "select-and-create prompt encodes the early-stop-on-no-target intent" block
  with an assert that `select-text` contains `Do NOT create a task`. Test-only
  substring lock on the shipped EDN; no production/skill/EDN change; file is 337
  lines (< 800 `components/` guard).

Added TT-E as an unchecked follow-up in steps.md.

PASS_STATUS: ACTIONABLE_FEEDBACK

---

## Review pass 20 follow-up — TT-E resolution (task-test-review)

TT-E (early-stop task half) RESOLVED. Extended the existing
"select-and-create prompt encodes the early-stop-on-no-target intent" `testing`
block in `reduce-incidental-complexity-test`
(`components/workflow-loader/test/psi/workflow_loader/task_204_workflow_definitions_test.clj`,
same ns — test-only, no production/skill/EDN change) with one assertion:
`(is (.contains select-text "Do NOT create a task") …)`.

- Design Deliverable 2 Step 1 Early stop: "do not create a worktree **or task**".
- Shipped `reduce-incidental-complexity.edn` step-3 emits BOTH
  `Do NOT create a worktree.` and `Do NOT create a task.` — the test previously
  locked only the worktree half, leaving the task half uncovered (a regress
  dropping `Do NOT create a task.` → orphan task dir on a no-target run would
  pass green). TR13/TR14-class symmetry gap (one half of a two-part contract
  locked, sibling half left) closed.

Verification:
- `clojure -M:test --focus psi.workflow-loader.task-204-workflow-definitions-test`
  → 2 tests, 68 assertions, 0 failures (+1 over pass-19's 67).
- `clj-kondo` 0 findings; `clj-paren-repair` Success.
- `bb commit-check:file-lengths` exit 0 (file 339 lines < 800).

PASS_STATUS: NO_ACTIONABLE_FEEDBACK (TT-E was the only newly added unchecked item;
the Contingency item predates and is non-planned/out of scope.)

## 2026-06-03 — Test review pass 21 (task-test-review, independent)

Applied `task-test-review` (`well_formed ∧ ∀b∈behaviour(design).∃t.covers(t,b) ∧
infra_deps injectable/nullable/¬mock`) fresh to the two task-204 test namespaces.
Tests are well-formed and green (11 tests, 146 assertions, 0 failures across
`task-204-workflow-definitions-test` + `incidental-complexity-finder-skill-test`).
Infra deps clean — real `workflow-loader`/`skills` loaders over temp dirs, real
`jq` with an availability guard + structural fallback; `with-redefs` only
redirects workflow-dir config to a real temp dir, not behaviour; no mocks/stubs.
Coverage is otherwise strong after passes 1–20.

One **named Deliverable-1 behaviour is NOT locked** (`∃b∈behaviour(design).
¬∃t.covers(t,b)`):

- **TT-F — the selector's two-lens invocation commands are unlocked.** Design
  Deliverable 1, step 1 ("Run both lenses in machine form") names the two data
  source commands: `bb gordian local --sort total --json` and
  `bb gordian complexity --json`. SKILL.md §1 ("Run both lenses in machine form")
  emits both verbatim — they produce the `/tmp/icf-local.json` /
  `/tmp/icf-cc.json` inputs the embedded jq recipe consumes. The
  `incidental-complexity-finder-skill-content-lock-test` locks the gap method,
  thresholds, scope, the `(ns, var, arity, line)`/`@line` join key, the top-5
  guard, and evidence/coverage-hint emission — but NOT these two lens commands.
  The recipe-execution tests rewrite those temp paths, so they never exercise the
  producing commands either. A regress (wrong subcommand, dropped `--json`,
  `complexity` → `complexity --sort`, or losing the selector-vs-baseline
  `--sort total`/bare distinction the design draws in A5/A2) passes every
  existing test green while breaking the recipe's inputs. This is the exact
  symmetry the workflow test's TT-C already enforces for the *baseline* capture
  commands (`bb gordian local --json` / `bb gordian diagnose --edn`); the
  *selector* lens commands deserve the same lock. The only current mention of
  `local --sort total` in the tests is a TT-C *comment* explaining the forbidden
  baseline, not an assertion. Fix: extend
  `incidental-complexity-finder-skill-content-lock-test` with a `testing` block
  asserting `body` contains `bb gordian local --sort total --json` and
  `bb gordian complexity --json`. Test-only substring lock on the shipped
  SKILL.md; no production/skill/EDN change; skill test ns is well under the
  `components/` length guard.

Added TT-F as an unchecked follow-up in steps.md.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Review pass 21 follow-up — TT-F resolution (task-test-review)

TT-F was the only newly-added actionable item from pass 21 (the trailing
Contingency step-1 split predates every pass and is conditional/untriggered, so
it is excluded). Test-only — no production/skill/EDN change; SKILL.md §1 already
emits both lens commands verbatim.

Extended `incidental-complexity-finder-skill-content-lock-test`
(`components/workflow-loader/test/psi/workflow_loader/incidental_complexity_finder_skill_test.clj`)
with a new `testing` block "encodes the step-1 two-lens invocation commands
(TT-F)" asserting `body` contains:
- `bb gordian local --sort total --json` — the selector lens command, including
  the `--sort total` display flag and `--json`
- `bb gordian complexity --json` — the cc lens command

Both strings grounded against the shipped SKILL.md before locking, so each is a
regression guard. Companion to the workflow test's TT-C *baseline*
capture-command lock — this closes the symmetry where the recipe-execution tests
rewrite temp paths and never exercise the producing commands, leaving the two
input-producing lens commands uncovered. A regress (wrong subcommand, dropped
`--json`, or losing the `--sort total`/bare selector-vs-baseline distinction the
design draws in A5/A2) now fails green.

Verification: focused `incidental-complexity-finder-skill-test` green
(9 tests, 80 assertions, 0 failures — +2 over pass-17's 78); `clj-kondo` 0
findings; `clj-paren-repair` Success; file 537 lines (< 800);
`bb commit-check:file-lengths` exit 0. steps.md TT-F ticked.

PASS_STATUS: REVIEW_COMPLETE

## Test review pass 22 (task-test-review)

Reviewed all task-204 test namespaces against design behaviour
(`task_204_workflow_definitions_test.clj`,
`incidental_complexity_finder_skill_test.clj`). Tests are well-formed,
state/output-asserting, dependency-free (no mocks/stubs; jq gated with a
structural fallback). Coverage is dense (TR1–TR21, TT-A–TT-F). One new
actionable gap found.

- **TT-G (actionable, minor) — A2 "touched units = metric-derived set" is
  unlocked.** Locked decision 4 and the design's "Net burden (A2)" paragraph
  make the *defining* A2 discriminator that "touched units" is the
  **metric-derived** set (every unit whose recomputed `lcc-total` changed),
  **not** the diff/touched-files set — with an explicit rationale ("scoping to
  changed files or changed source would let a refactor hide relocated burden in
  an untouched caller"). The shipped step-1 prompt carries this verbatim
  (`the set is computed from the metric, not from the diff/touched files`), but
  the only A2 locks are F3 (`identified by (ns, var, arity, line)` key) and TT-D
  (direction: `after total is strictly less than the before total`). Neither
  anchors the metric-vs-file derivation. A paraphrase weakening the touched-set
  to "units whose source/files changed" — which defeats the whole point of the
  global-recompute net check — passes every existing test green. Companion to
  TT-D (direction) the same way TT-C/TT-F lock the surrounding A2/A5 plumbing.

Added TT-G as an unchecked follow-up in steps.md.

PASS_STATUS: ACTIONABLE_FEEDBACK

## 2026-06-03 — Test review pass 22 follow-up TT-G executed

Executed the sole newly-added actionable item from review pass 22 (the trailing
Contingency step-1 split predates every pass and is conditional/untriggered).

TT-G — Locked the A2 "touched units = metric-derived set" discriminator. Added a
"select-and-create prompt locks the metric-derived touched-set discriminator
(TT-G)" `testing` block to the F3/TT-D cluster in
`reduce-incidental-complexity-test`
(`components/workflow-loader/test/psi/workflow_loader/task_204_workflow_definitions_test.clj`),
asserting `select-text` contains
`the set is computed from the metric, not from the diff/touched files`. Verified
the substring present verbatim in the shipped
`.psi/workflows/reduce-incidental-complexity.edn` step-1 prompt before locking,
so this is a regression guard. F3 locks only the `(ns, var, arity, line)` join
key and TT-D only the strictly-less direction; TT-G anchors the metric-vs-file
*derivation* (Locked decision 4 / the design's "Net burden (A2)" paragraph), so
a paraphrase to "units whose source/files changed" — which would defeat the
global-recompute net check by letting a refactor hide relocated burden in an
unedited caller — now fails green. Companion to TT-D (direction) and TT-C/TT-F
(surrounding A2/A5 plumbing).

Test-only — no production/skill/EDN change; assertions otherwise untouched.
Focused task-204 ns green (2 tests, 69 assertions, 0 failures — +1 over
pass-21's 68); `clj-kondo` 0 findings; `clj-paren-repair` Success; file 351
lines (< 800); `bb commit-check:file-lengths` exit 0. steps.md TT-G ticked.

PASS_STATUS: REVIEW_COMPLETE

## Test review (pass 23 — task-test-review)

Reviewed task-204 test coverage against design behaviour. Baseline green: 11
tests, 149 assertions, 0 failures (`task-204-workflow-definitions-test` +
`incidental-complexity-finder-skill-test`). Infra deps are real, not mocked —
the skill recipe tests exercise the live `jq` CLI via `clojure.java.shell` over
temp-file fixtures (jq-absent structural fallback), and the loader/skill tests
use the real `load-workflow-definitions` / `load-skills-from-dir`. No
mock/stub/interaction assertions; all assertions are state/output. Skill
Deliverable-1 behaviours are exhaustively locked (gap method, thresholds, A1
drop, @line determinism+order-independence, filter/drop, ranking/cap, max(cc,1),
empty-qualification, boundary inclusivity, projection contract, two-lens
commands). Workflow/wrapper grammar + prompt contracts are densely locked
(TT-A..G, TR2/7/8/10/11/13/14/15/19, F1/F3).

ONE new actionable gap found (TT-H, below): the step-1 prompt's **worktree-scoped
task creation** — the explicit P3 resolution (allocate the task id by scanning
the WORKTREE's `open/ ∪ closed/`, "not the outer checkout's", so ids do not
collide; and commit the created task ON THE WORKTREE BRANCH) — is a substantive
correctness behaviour encoded verbatim in the shipped EDN but anchored by no
test. A regress reverting to outer-checkout-scoped allocation (reintroducing P3)
or committing on the wrong branch passes every existing test green. Same
TT-class symmetry gap as TT-B/TT-G (a prompt-encoded, design-resolved correctness
behaviour left unlocked). Follow-up added to steps.md.

PASS_STATUS: ACTIONABLE_FEEDBACK

---

## Test review pass 23 follow-up — TT-H resolution (worktree-scoped task creation lock)

Executed the single newly-added actionable follow-up (TT-H) from review pass 23.

Test-only change: extended `reduce-incidental-complexity-test` in
`components/workflow-loader/test/psi/workflow_loader/task_204_workflow_definitions_test.clj`
with a "select-and-create prompt locks the worktree-scoped task creation (TT-H)"
`testing` block appended to the TT-G/TT-D/F3 cluster (same task-204 ns; no
production/skill/EDN change). Three `is` substring asserts on `select-text`
(step-1 prompt), each verified present verbatim in the shipped
`.psi/workflows/reduce-incidental-complexity.edn` before locking:

1. `scanning the WORKTREE's` — step-5 worktree-scoped NNN allocation (P3).
2. `so the id does not collide with the outer checkout's open tasks` — the P3
   collision-avoidance rationale.
3. `Commit the task creation ON THE WORKTREE BRANCH` — step-8 commit-on-branch
   so the emitted `munera_task_path:` resolves under the delegated `work-on`.

A regress to outer-checkout-scoped id allocation (reintroducing P3) or committing
on the wrong branch now fails green — closing the same TT-class symmetry gap as
TT-B/TT-G (a prompt-encoded, design-resolved correctness behaviour left unlocked).

Verification: `clj-paren-repair` Success; `clj-kondo` 0 findings; focused
`clojure -M:test --focus psi.workflow-loader.task-204-workflow-definitions-test`
green (2 tests, 72 assertions, 0 failures — +3 over pass-22's 69); test ns 369
lines (< 800 `components/` guard).

## task-implementation-review (independent pass, 2026-06-03, post-TT-H)

Re-applied `task-implementation-review` (matches-design ∧ follows-architecture ∧
flag new-pattern-where-reusable ∧ flag unnecessary-abstraction ∧ flag
structural-perf) to the three shipped artifacts (`incidental-complexity-finder/
SKILL.md`, `task-lifecycle-in-worktree.edn`, `reduce-incidental-complexity.edn`)
+ the two task-204 test namespaces + docs, after the TT-F/TT-G/TT-H test-review
passes (all test-only) landed.

**Verdict: REVIEW_COMPLETE — no new actionable implementation issues.**

- **Code↔design — matches.** Outer workflow is the verified two-step shape
  (`select-and-create` `:session`, skills `incidental-complexity-finder`/
  `gordian`/`code-shaper`, `git fetch origin master` base refresh, early-stop,
  worktree-scoped NNN allocation + commit-on-branch, two `before` baselines,
  two-phase generated-design contract, `worktree_path:`/`munera_task_path:`
  handoff; `lifecycle-in-worktree` `:delegate` → `task-lifecycle-in-worktree`
  with grammar-conformant `:prompt-string` `{:input {:from {:step
  "select-and-create" :yield :text}}}`). Wrapper is the three-step
  `resolve-worktree`(`:session`,+`work-on`) → `lifecycle`(`:delegate`
  `:target "task-lifecycle"`) → `summary`(`:session`) adapter mirroring the
  loadable `review-implementation-in-worktree.edn`, with the F1 `NO_TARGET`
  short-circuit in both session steps. Skill encodes the `gap = lcc-total /
  max(cc,1)` recipe, inner-join-on-local-side drop rule, `(ns,var,arity,line)`
  determinism key, `lcc-total ≥ 5.0 ∧ gap ≥ 2.0` filter, and the top-5 guard.
- **Architecture — follows.** Pure capability-catalog (S1) artifacts (skill +
  `.edn` workflows); no production Clojure; reuses the verified wrapper pattern
  — no invented pattern, no unnecessary abstraction, no structural-perf concern.
- **Known limitation — accepted, not new.** F1 (step-2 `:delegate` fires
  unconditionally on a no-target handoff because the grammar has no
  conditional/skip) is documented, mitigated by the prompt-level `NO_TARGET`
  short-circuit (resolve-worktree skips `work-on`, summary overrides), and
  engine-bounded — not an actionable code defect.
- **Tests — green** via the real loader (`load-workflow-definitions` over the
  live `.psi/workflows`): focused `task-204-workflow-definitions-test` +
  `incidental-complexity-finder-skill-test` → 11 tests, 152 assertions, 0
  failures. `clj-kondo` 0 findings on both `.edn` workflows.
- **Docs — accurate.** `doc/workflows.md` §"Incidental-complexity
  simplification" + CHANGELOG `[Unreleased] → Added` describe the two-step
  workflow, three-step `.edn` wrapper, NO_TARGET early-stop, and two-phase
  contract faithfully.
- **Coherence — clean.** design ↔ plan ↔ implementation ↔ artifact agree
  (IR-A plan.md `.edn`-wrapper reconciliation holds). Only unchecked steps.md
  item is the pre-existing, untriggered Contingency (split step-1) — conditional,
  not actionable.

No follow-up items added.

PASS_STATUS: REVIEW_COMPLETE

## 2026-06-03 — Test review (task-test-review, pass 24)

Re-reviewed the task's tests against `∀b ∈ behaviour(design). ∃t. covers(t,b)`,
well-formedness, and the no-mocks/nullable-infra criterion. Tests run green via
the real loader + real `jq` (synthetic fixtures, graceful jq-absent structural
fallback): focused `task-204-workflow-definitions-test` +
`incidental-complexity-finder-skill-test` → 11 tests, 152 assertions, 0 failures.

Infra-deps clean: the loader is exercised through `load-workflow-definitions`
over temp/real `.psi/workflows` (test-config injection via `with-redefs` on the
dir-resolvers — not behavioural mocking); recipe tests shell out to real `jq`
and assert state/outputs, never interactions. No mocks/stubs. Well-formed.

Coverage is exhaustive for the workflow shapes, handoff wiring, NO_TARGET
short-circuit, recipe determinism/filter/drop/ranking/cap/max-guard/empty/
boundary/projection, base-refresh (TT-B), baseline commands (TT-C), A5/A2
direction (TT-D)/key (F3)/metric-set (TT-G), worktree-scoped creation (TT-H),
no-push/PR (TR8), skills (TT-A), and the Phase-0 char-test gate (TR2).

One new actionable gap (added to steps.md):

- **TT-I — the generated-design contract's "Blast radius" constraint and
  Phase-0 hard gate are unlocked.** The step-7 generated `design.md` contract in
  `reduce-incidental-complexity.edn` is a named design behaviour ("Generated
  tasks carry the two-phase behaviour-preserving contract"). TR2 locked the
  Phase-0 characterization-test gate, the behaviour-identical constraint, and
  (with F3) the A5/A2 key — but **two further named clauses of that same
  contract carry no assertion**: (1) the **Blast radius** constraint ("the
  target unit PLUS the minimal surrounding helpers required to decomplect it;
  no unrelated cleanup" — the scope fence keeping the refactor honest against
  the net-burden acceptance), and (2) the Phase-0 **hard gate + untestable-tangle
  escape hatch** ("If the unit cannot be characterized safely … (a) … a minimal
  seam … or (b) is closed with the finding (scope drift → close per Munera). No
  refactor proceeds without a green net."). Both strings are present verbatim in
  the shipped EDN (`grep` confirms 1 each) and absent from
  `task_204_workflow_definitions_test.clj` (`grep` confirms 0 each). A regress
  dropping the blast-radius fence (admitting unrelated cleanup that inflates the
  diff while still passing the net-burden check via relocation) or the
  untestable-tangle/green-net hard gate (letting a refactor proceed on an
  uncharacterized unit without the prescribed seam-or-close decision) passes
  every existing test green — the same sub-clause-lock standard TR2/TT-D/TT-G
  applied to the other contract clauses, left unapplied here. Per
  `∀b∈behaviour(design).∃t.covers(t,b)`, these are uncovered named contract
  behaviours. Fix: extend `reduce-incidental-complexity-test`'s TR2 contract
  cluster (same task-204 ns, test-only — no production/skill/EDN change) with
  `select-text` substring locks for "Blast radius: the target unit PLUS the
  minimal surrounding helpers required to decomplect it; no unrelated cleanup",
  "No refactor proceeds without a green net", and the untestable-tangle handling
  ("cannot be characterized safely" + "scope drift -> close per Munera"). Run
  focused task-204 ns + `clj-kondo`; keep under the 800-line `components/` guard.

PASS_STATUS: ACTIONABLE_FEEDBACK.

## 2026-06-03 — Test review (pass 24) follow-up executed (TT-I)

Executed the single newly-added unchecked `steps.md` item (TT-I) from the pass-24
task-test-review. (The line-898 "Contingency" item predates this pass — a
non-planned design-stated fallback, not a review follow-up — and was left
untouched; no other unchecked items exist.)

**Grounding before editing:** verified all four target substrings are present
verbatim in the shipped `.psi/workflows/reduce-incidental-complexity.edn` step-1
prompt (`grep -c` → 1 each): the Blast-radius scope fence, "No refactor proceeds
without a green net", "cannot be characterized safely", and "scope drift -> close
per Munera". Confirmed they were absent from the test ns before this change.

**Fix (test-only; no production/skill/EDN change), in
`components/workflow-loader/test/psi/workflow_loader/task_204_workflow_definitions_test.clj`:**
Extended `reduce-incidental-complexity-test`'s TR2 contract cluster with two new
`testing` blocks immediately after the F3 A5/A2 key lock —
(1) "select-and-create prompt locks the Blast-radius scope fence (TT-I)": one
`select-text` substring lock on the full blast-radius clause ("the target unit
PLUS the minimal surrounding helpers required to decomplect it; no unrelated
cleanup"); (2) "select-and-create prompt locks the Phase-0 hard gate +
untestable-tangle handling (TT-I)": three substring locks — "cannot be
characterized safely", "scope drift -> close per Munera", "No refactor proceeds
without a green net". This applies the same sub-clause-lock standard TR2 (Phase-0
gate + behaviour-identical) / TT-D (A5/A2 direction) / TT-G (metric-derived
touched set) already use for sibling clauses of the same generated-design
contract. A regress admitting unrelated cleanup (dropping the blast-radius fence)
or letting a refactor proceed on an uncharacterized unit without a green net (the
untestable-tangle/hard-gate escape hatch) now fails green.

**Verification:**
- `clj-paren-repair` on the test file: Success(1)/Failed(0).
- `clojure -M:test --focus psi.workflow-loader.task-204-workflow-definitions-test`:
  **2 tests, 76 assertions, 0 failures** (+4 over pass-23's 72).
- `clj-kondo --lint` on the test file: errors 0, warnings 0.
- `bb commit-check:file-lengths`: exit 0 (file 386 lines < 800).

TT-I checked in steps.md. PASS_STATUS: REVIEW_COMPLETE.

## Test review pass 25 (task-test-review) — TT-J

**Finding (ACTIONABLE):** `reduce-incidental-complexity-test` under-covers the
step-1 **tool set**. Design Deliverable 2, Step 1 first bullet names five step-1
tools verbatim: "Tools include `read`, `bash`, `edit`, `write`, `work-on`". The
shipped `.psi/workflows/reduce-incidental-complexity.edn` step-1 declares all
five (`:tools ["read" "bash" "edit" "write" "work-on"]`), but the test asserts
only `(some #{"work-on"} (:tools select-step))`. The other four are uncovered:
a regress dropping `bash` (which runs `git fetch` + `bb gordian
local/complexity` selection + baseline capture), `edit`/`write` (the generated
`design.md` task creation), or `read` passes every existing test green while
breaking step-1. This is the exact symmetric gap TT-A closed for the *skills*
half of the same bullet (TT-A locked all three named skills after the test
locked only one) — the *tools* half of the same enumeration was left at one of
five. Per the task-test-review criterion `∀b∈behaviour(design).∃t.covers(t,b)`,
the step-1 tool set is a named design behaviour and is under-covered.

**Recorded as steps.md TT-J (unchecked).** Test-only substring/membership lock;
no production/skill/EDN change anticipated.

## Test review pass 25 follow-up execution — TT-J resolved

**Change (test-only):** Extended `reduce-incidental-complexity-test`'s
select-and-create `testing` block in
`components/workflow-loader/test/psi/workflow_loader/task_204_workflow_definitions_test.clj`
with `:tools` membership asserts for `"read"`, `"bash"`, `"edit"`, and
`"write"` alongside the pre-existing `"work-on"` lock — locking all five
design-named step-1 tools (Deliverable 2, Step 1 first bullet) instead of one.
Renamed the block to "…all five design-named tools…" and added a TT-J comment
documenting the symmetric-with-TT-A rationale. No production/skill/EDN change.

**Verification:**
- `clj-paren-repair` on the test file: Success(1)/Failed(0).
- `clojure -M:test:kaocha --focus psi.workflow-loader.task-204-workflow-definitions-test`:
  **2 tests, 80 assertions, 0 failures** (+4 over pass-24's 76).
- `clj-kondo --lint` on the test file: errors 0, warnings 0.
- `bb commit-check:file-lengths`: clean (file 400 lines < 800).

TT-J checked in steps.md.

## Test review pass 26 (task-test-review) — TT-K

**Finding (ACTIONABLE):** the task-204 delegate **reference chain is never
proven to resolve** — only the target *strings* are asserted. Both isolated
tests use `load-edn-only` (one EDN into an empty temp dir):

- `reduce-incidental-complexity-test` asserts
  `(= "task-lifecycle-in-worktree" (:target delegate-step))` (string equality)
  but loads only `reduce-incidental-complexity.edn`, so its delegate target
  `task-lifecycle-in-worktree` is **not** in that run's `definitions`.
- `task-lifecycle-in-worktree-test` asserts
  `(= "task-lifecycle" (:target lifecycle-step))` but loads only the wrapper,
  so `task-lifecycle` is **not** in its `definitions`.

The loader does not validate delegate targets at load time (confirmed: no
target-resolution check in `compiler.clj`/`core.clj`; the isolated
`(empty? errors)` assertions pass with dangling targets), so the string-equality
asserts give no resolution guarantee. The design acceptance explicitly requires
the outer workflow "parses and loads, follows the verified grammar … **references
resolve**" and the wrapper "exists, parses and loads"; the *references-resolve*
half is uncovered. A regress renaming the wrapper file/`:name` (e.g.
`task-lifecycle-in-worktree` → a typo) while the outer keeps the old `:target`
string would break the live delegate chain yet pass every existing test green.

There is an established same-component precedent for closing exactly this gap:
`review-workflow-set-loads-together-test` (`workflow_definitions_test.clj`)
co-loads the whole `review-*` delegate set and asserts each member registers.
The task-204 chain (`reduce-incidental-complexity` → `task-lifecycle-in-worktree`
→ `task-lifecycle`) has no analogous co-load test, and none of the isolated
tests asserts target ∈ `definitions`.

**Recorded as steps.md TT-K (unchecked).** Test-only; add a co-load test (mirror
`review-workflow-set-loads-together-test`) that loads the three task-204 chain
EDNs together, asserts `(empty? errors)` + all register, and asserts each
task-204 delegate `:target` is a key in the combined `definitions` (the
references-resolve check the isolated string-equality asserts cannot provide).
No production/skill/EDN change anticipated.

### Pass 26 (test review — task-test-review) — TT-K RESOLVED

Added `task-204-workflow-set-loads-together-test` to
`task_204_workflow_definitions_test.clj` (same ns, test-only — no
production/skill/EDN change), mirroring `review-workflow-set-loads-together-test`.

The test co-loads the task-204 delegate set via `with-workflow-dir` +
`slurp-workflow-file` —
`reduce-incidental-complexity.edn`, `task-lifecycle-in-worktree.edn`, and
`task-lifecycle.edn` — asserts `(empty? errors)` and that all three register,
then walks each task-204 delegate step and asserts its `:target` is a key in the
combined `definitions`:

- outer `reduce-incidental-complexity` step `lifecycle-in-worktree` →
  `task-lifecycle-in-worktree` (∈ definitions);
- wrapper `task-lifecycle-in-worktree` step `lifecycle` → `task-lifecycle`
  (∈ definitions).

The loader does not validate delegate targets at load time (no resolution check
in `compiler.clj`/`core.clj`), so the isolated string-equality asserts gave no
resolution guarantee. The references-resolve half of the design acceptance
("parse and load … references resolve") is now covered: a regress renaming the
wrapper file/`:name` while an upstream `:target` string stays stale breaks the
live chain and now fails green.

Verification: `clojure -M:test --focus
psi.workflow-loader.task-204-workflow-definitions-test` → 3 tests, 88
assertions, 0 failures (+1 test/+8 assertions over pass-25's 2/80);
`clj-kondo --lint` 0 findings (errors 0, warnings 0); `clj-paren-repair` Success;
`bb commit-check:file-lengths` exit 0; test file 445 lines (< 800).

## Test review pass 27 (task-test-review) — TT-L

**Finding (ACTIONABLE):** the design's **first acceptance criterion** — the
`incidental-complexity-finder` skill "produces a target + evidence **when run
against this repository**" (also Deliverable 1 step 5 + Locked decision 1) — has
**no executable test**. Every recipe test in
`incidental_complexity_finder_skill_test.clj` runs the embedded jq recipe over
**synthetic** `{"units":[…]}` inputs (`run-jq-recipe` spits hand-built JSON), and
TT-F locks only that SKILL.md §1 *names* `bb gordian local --sort total --json` /
`bb gordian complexity --json` as **prose substrings**. The TT-F step itself
records the residual gap verbatim: "the recipe-execution tests rewrite the temp
paths and **never exercise the producing commands**." So nothing proves the
recipe consumes the *real* lens output shape.

The recipe assumes each lens emits a top-level `.units` array whose elements
carry `ns`/`var`/`arity`/`line`/`lcc-total`/per-dimension-burdens/`findings`
(local) and `ns`/`var`/`arity`/`line`/`cc` (complexity). I verified this holds
against the live CLI today — `bb gordian local --json` and
`bb gordian complexity --json` both expose `.units`, and the end-to-end recipe
ranks the live top-5 (e.g. `psi.app-runtime/start-tui-runtime!` gap ≈ 7.03 down
to `…/start-nrepl!` gap ≈ 2.01). But that grounding lives only in design prose
(Verified facts: "Both lenses emit … a units array") and a one-off manual check;
**no test guards it**. If a future `gordian` change reshaped the JSON (wrapped
`units`, renamed a burden field, changed the `findings`/`line` shape), every
synthetic recipe test would stay green while the shipped skill silently produced
nothing or a malformed target in production. Per testing-without-mocks this is
the classic **Narrow Integration Test** gap: the recipe is a wrapper over the
external `bb gordian` system, and no focused test exercises it against the real
system. Per the task-test-review criterion `∀b∈behaviour(design).∃t.covers(t,b)`,
this named acceptance behaviour is uncovered.

**Recorded as steps.md TT-L (unchecked).** Test-only (no production/skill/EDN
change): add a focused narrow integration test that runs the real
`bb gordian local --sort total --json` + `bb gordian complexity --json` against
this repo, pipes their actual output through the SKILL.md recipe (reuse
`skill-recipe` + the temp-path rewrite in `run-jq-recipe`, sourcing the two real
lens outputs instead of synthetic units), and asserts the recipe (a) runs cleanly
(exit 0) and (b) emits a structurally-valid result — a JSON array (possibly `[]`),
each element carrying the projected evidence keys (`ns`/`var`/`gap`/`cc`/
`lcc_total`/`findings`). Assert **structure, not a specific target** (the live
target drifts as code changes, so a specific-unit assertion would be flaky).
Gate on `bb`/`jq` availability (mirror the existing `jq-available?` fallback) and
keep it isolated from the fast path if it proves slow. This closes the
real-lens-shape assumption the synthetic tests build on and the design's first
acceptance criterion.

PASS_STATUS: ACTIONABLE_FEEDBACK
