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
