# Implementation notes

## Architectural-fit review (design) — ψ

Reviewed design.md for fit against AGENTS.md (VSM, workflow-runtime boundary,
one-way/replay), doc/architecture.md, and the workflow grammar/runtime docs.
Judged fit only (not ambiguity/inconsistency/correctness). Three actionable
misfits found; recorded as unchecked items in design-steps.md.

- **A1 (primary) — Q8 filesystem-state routing contradicts an existing
  deliberate decision.** doc/workflows.md states the current `review-task-design`
  routes on *phase outputs* and that `clarity-status` "remembers ... from the
  phase outputs rather than re-reading task artifacts after follow-up
  execution." Q8's `workflow/open-checklist-items-routing` re-reads
  `design-steps.md` from disk to route. The workflow-runtime-boundary argument
  (generic op + authored path) is satisfied, but routing on mutable external
  file state makes the routing decision depend on data outside the workflow
  data-flow / event log, which fights the VSM `∀change → event → log →
  replayable` ethos and the judge-outcome contract (normalize a *step result*).
- **A2 — `:prompt` source-ref selector extends the shared data-flow substrate
  without specified validation.** `{:step :prompt :output}` adds an addressing
  dimension to the substrate shared across invoke args, contributions, template
  vars, and delegated context. Design does not state how the IR source-ref
  schema integrates `:prompt` or rejects `:prompt` against non-multi-prompt /
  non-session targets (cf. existing "output not exposed by that step type is
  invalid" rule).
- **A3 — per-prompt turn results must live in the canonical recorded step
  result, not transient loop locals.** `execute-session-step!` today records one
  `:pending-actor-result` envelope. AC-3 needs each prompt's reply addressable
  and S4 needs intermediate turns introspectable/replay-faithful. Design's
  architecture-alignment flags the reconcile need but does not commit to
  recording each turn in the canonical progression/event substrate.

## Architectural-fit follow-up execution (design) — ψ

Executed A1, A2, A3 by updating design.md (all completed; none blocked).

- **A1 (done).** Reconciled Q8 by **dropping** the proposed filesystem-state
  routing (`workflow/open-checklist-items-routing` re-reading `design-steps.md`)
  in favour of routing on the merged step's **per-prompt reply outputs** via the
  existing `workflow/pass-feedback-routing` operation — the same
  workflow-data-flow disjunction the unmerged `review-task-design` phases already
  use. This keeps routing inside the workflow data-flow / event log (replay-safe,
  deterministic), aligns with `doc/workflows.md`'s `clarity-status` "from phase
  outputs rather than re-reading task artifacts" decision and the VSM
  `∀change → event → log → replayable` ethos, and removes the need for any new
  routing operation. Updated Scope bullet, Q7, Q8 (with explicit "why not
  filesystem-state routing" rationale), and Q11. Net effect reverses the earlier
  Q11 consequence: per-prompt reply addressing is now load-bearing for the
  exemplar's routing and stays in the first cut.
- **A2 (done).** Added "Source-ref integration for `:prompt` (Q12)": the
  `{:step s :prompt p :output k}` selector is an optional `:prompt` discriminator
  on the canonical prior-step source ref, resolved uniformly across the shared
  substrate (invoke args, contributions, template vars, delegated context), with
  back-compat that no-`:prompt` refs against a multi-prompt step hit the
  step-level surface. Specified compile-time validation rejecting `:prompt`
  against non-session steps, single-prompt session steps, unknown prompt-groups,
  and structured-output keys — mirroring the existing "output not exposed by that
  step type is invalid" rule in `doc/workflow-grammar-concepts.md`.
- **A3 (done).** Added an Architecture-alignment bullet committing each queued
  prompt's turn result to the canonical step-result/progression substrate (named
  per-prompt records, introspectable per S4, replay-faithful), reconciled with
  Q5: the step still emits one post-drain `:pending-actor-result` for one routing
  decision, carrying the ordered per-prompt records plus the step-level rollup;
  no turn result lives only in an in-loop local.
- Cross-referenced A2/A3 as resolved Q12 in the open-questions list for
  traceability.

## Ambiguity review (design) — ψ

Reviewed design.md for ambiguities (statements admitting >1 interpretation,
undefined terms, unspecified edge behavior). Distinct from the architectural-fit
pass (A1–A3). Five new actionable ambiguities found; recorded as unchecked items
in design-steps.md.

- **B1 — Yielded-value composition is unspecified for a multi-prompt step.**
  `doc/workflow-grammar-concepts.md` distinguishes step-local `:output` surfaces
  from the step's `:yield` tagged-union value and the downstream
  `{:step s :yield k}` ref form. The design specifies per-prompt `:output`
  addressing (`:final-llm-reply`/`:transcript`) but never states (a) what a
  multi-prompt session step's yielded value as a whole is, nor (b) whether the
  `:prompt` discriminator applies to `:yield` refs or only to `:output` refs.
- **B2 — Per-prompt `:transcript` content is ambiguous.** AC-3 contrasts the
  step-level `:transcript` ("accumulated conversation across all turns") with a
  per-prompt `:transcript` ("its own"), but because all prompts share one live
  session, "its own" can mean either just that prompt's turn slice or the
  cumulative conversation up to and including that turn. Not disambiguated.
- **B3 — One-element `:prompts` legality and execution path is unspecified.**
  Q6 forbids empty `:prompts` and keeps single-prompt authoring as the
  `:contributions` form (not internally rewritten to a one-element `:prompts`),
  but does not state whether an author may write `:prompts` with exactly one
  entry, and if so whether it runs the multi-prompt path (per-prompt addressing
  available) or is rejected in favor of `:contributions`. AC-1 ("N ≥ 1")
  implies it is valid; this is left implicit.
- **B4 — Prompt-group `:name` uniqueness within a step is unspecified.** The
  `:prompt p` selector and the per-prompt records ("keyed by prompt `:name`")
  presuppose unique names, but no uniqueness rule or duplicate-name validation
  error is stated.
- **B5 — Step outcome under cancellation between prompts (AC-6) is
  underspecified.** AC-5 states the intermediate-error path (surface failure,
  stop queue, identify failing prompt by name). AC-6 says cancellation stops
  submitting later prompts but does not state the resulting step outcome: whether
  the judge/`:on` routing runs, what envelope/outcome is recorded, and whether
  the partial per-prompt turn records already completed remain introspectable.

## Ambiguity follow-up execution (design) — ψ

Executed B1–B5 by updating design.md (all completed; none blocked). Added a new
"Ambiguity resolutions (B1–B5)" section and tightened the affected ACs/grammar.

- **B1 (done).** Resolved against `doc/workflow-grammar-concepts.md`'s
  output-surface vs yielded-value distinction. (a) A multi-prompt session step
  yields one value as a whole via the unchanged session default — text from the
  step-level `:final-llm-reply` (= last prompt). No per-prompt yielded value;
  per-prompt data is output-surface-only. (b) The `:prompt` discriminator applies
  to `:output` refs only; `{:step s :prompt p :yield k}` is an IR-validation
  error. Updated AC-3.
- **B2 (done).** Per-prompt `:transcript` = that prompt's own turn slice (mirrors
  per-prompt `:final-llm-reply`); step-level `:transcript` = accumulated across
  all turns. Live-session context sharing is independent of how the addressable
  surface is sliced. Updated AC-3.
- **B3 (done).** Empty `:prompts` ⇒ IR error; one-element `:prompts` ⇒ valid
  (AC-1 N≥1), runs the multi-prompt path with per-prompt addressing, not rejected
  in favour of `:contributions` and not rewritten. `:contributions` single-prompt
  form and one-element `:prompts` are two distinct legal authorings. Updated
  grammar precedence note.
- **B4 (done).** Prompt-group `:name` unique within a step; duplicate ⇒ IR error.
  Uniqueness is per-step ((step-name, prompt-name) is the handle); names may
  repeat across steps. Updated grammar precedence note.
- **B5 (done).** Cancellation between prompts is a run-level stop ⇒ terminal
  `:cancelled` outcome (distinct from AC-5 `:failed`), judge/`:on` routing
  skipped, completed per-prompt turn records retained + introspectable, in-flight
  turn aborted per existing cancellation contract (matches `:cancelled` terminal
  status in `doc/workflows.md`). Updated AC-6.

## Inconsistency review (design) — ψ

Reviewed design.md for internal inconsistencies and contradictions against the
referenced artifacts (`review-task-design.edn`, `doc/workflows.md`,
`doc/workflow-grammar-concepts.md`, `workflow/pass-feedback-routing`). One new
actionable inconsistency found; recorded as an unchecked item in design-steps.md.
Verified-consistent: B1 yield default vs grammar-concepts (`session ⇒ :final-llm-reply`),
`:outputs` step-level structured-output field name, and B5's `:cancelled`
terminal-status reference (both present in `doc/workflows.md`).

- **C1 — Exemplar merges only 2 of the 3 real review phases; Q8 equivalence
  claim is false.** The referenced exemplar `review-task-design.edn` and
  `doc/workflows.md` both define **three** review phases (`architecture →
  ambiguity → inconsistency → clarity-status`), and the actual `clarity-status`
  judge calls `workflow/pass-feedback-routing` with **three** `*-text` args
  (`:architecture-text`, `:ambiguity-text`, `:inconsistency-text`);
  `pass-feedback-routing` computes its REPEAT/DONE disjunction over **all**
  passed `*-text` keys. The design's exemplar (Scope bullet, Q7, Q8, and the
  grammar-shape example at design.md:182–185) merges **only**
  `architecture-review` + `ambiguity-review` into a two-prompt step with a
  **two-arg** judge (`{:architecture-text … :ambiguity-text …}`), and Q8 asserts
  this is "**exactly** the disjunction `pass-feedback-routing` already computes
  across the unmerged `review-task-design` phase outputs." That equivalence is
  false: dropping `:inconsistency-text` changes the disjunction. The design never
  states what becomes of the `inconsistency-review` phase (kept as a separate
  step? merged as a third prompt? dropped?), and the Q7 token-efficiency
  rationale ("design + architecture sources read once and reused") is undercut if
  inconsistency-review still re-reads the sources separately. Reconcile by either
  (a) merging all three reviews into the multi-prompt step with a three-arg
  judge, or (b) explicitly stating that inconsistency-review remains a separate
  step and correcting the Q8 "exactly the disjunction" claim to reflect a
  two-prompt-merge-plus-separate-inconsistency topology.
