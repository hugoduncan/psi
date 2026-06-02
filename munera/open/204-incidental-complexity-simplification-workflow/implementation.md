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
