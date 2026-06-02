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
