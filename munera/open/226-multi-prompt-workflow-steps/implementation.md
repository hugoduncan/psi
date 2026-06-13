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

## Inconsistency follow-up execution (design) — ψ

Executed C1 by updating design.md (completed; not blocked). Chose **option (a)**:
merge **all three** real review phases (architecture, ambiguity, inconsistency)
into the multi-prompt exemplar step, rather than keeping inconsistency separate.

Rationale for (a) over (b): the live `review-task-design.edn` has three review
phases and its `clarity-status` judge calls `pass-feedback-routing` with three
`*-text` args (`:architecture-text`, `:ambiguity-text`, `:inconsistency-text`).
Merging all three makes the Q8 "exactly the disjunction" claim **genuinely
true** (three prompts → three `*-text` args → exact key-for-key match, no
dropped key), keeps the exemplar faithful to the real three-phase topology, and
fully realizes the Q7 token-efficiency rationale (design + architecture sources
read once and reused for all three reviews, none re-reading separately). Option
(b) would have left inconsistency-review re-reading the sources separately,
undercutting Q7, and required weakening the Q8 equivalence claim.

- **C1 (done).** Updated: Scope bullet (merge all three phases), Q7 (revised
  exemplar = three-phase merge, with the three-phase-topology + equivalence +
  token-efficiency justification), Q8 (three review prompts, three-arg
  `pass-feedback-routing` judge incl. `:inconsistency-text`, equivalence now
  genuine), and the grammar-shape example (added the `inconsistency`
  prompt-group). The merged step's post-drain REPEAT-while-any-`ACTIONABLE_FEEDBACK`
  routing mirrors the live `clarity-status` REPEAT→architecture-review
  (max-iterations) loop. Per-prompt reply addressing (Q11) remains load-bearing
  and unchanged; now carries three prompt-groups instead of two.

## Architectural-fit review (design, pass 2) — ψ

Re-reviewed the current design.md for architectural fit against AGENTS.md
(VSM/replay, workflow-runtime boundary, `λone_way`, `λα. ¬compat(backward)`,
`consistent`), doc/workflows.md (live `review-task-design` topology),
doc/workflow-grammar-concepts.md, and doc/workflow_statechart_canonical.md.
Judged fit only (not ambiguity/inconsistency/correctness). A1–A3 from pass 1 are
resolved. Two **new** actionable misfits found (D1, D2); recorded as unchecked
items in design-steps.md. Not duplicates of A1–A3/B1–B5/C1.

- **D1 — Merged exemplar's "matches the real workflow's three-phase topology"
  claim is architecturally unachievable.** Scope/Q7 rewrite
  `review-task-design.edn` to merge all three review phases into one multi-prompt
  `:session` step. But a multi-prompt step is architecturally *N turns in one
  session → one post-drain judge/route* (Q5, Q8: "exactly one judge + `:on`,
  applied after the queue drains"). The live `review-task-design` topology
  (doc/workflows.md) is per-phase **review→follow-up→review**: each `*-follow-up`
  step *executes the recorded items and mutates design.md/design-steps.md before
  the next phase reviews*. Running architecture, ambiguity, and inconsistency as
  three back-to-back turns in one session reviews all three against the **same
  un-followed-up design**, with no per-phase mutation between them, and the
  design never states where the three `*-follow-up` steps go. The Q8
  `pass-feedback-routing` equivalence is only about the *routing disjunction*,
  not about the lost interleaved-follow-up structure. So the topology-faithful
  claim is an architectural-fit overclaim: the defining structural feature of the
  live topology (follow-up mutation between phases) cannot survive the merge.
- **D2 — Dual single-prompt path/authoring fights `λone_way` and
  `¬compat(backward)`.** Q6/AC-2/B3 deliberately keep the `:contributions`
  single-prompt form as a *separate* execution path and authoring form, *not*
  rewritten as the N=1 degenerate of `:prompts`, justified by "byte-for-byte
  equivalence"/"behaves exactly as today"/"strict superset." This yields two
  authorings for ~one concept (single-turn session step) and two parallel runtime
  paths that must be kept from drifting — fighting `λone_way` (¬ambiguity →
  singular solution / obvious path) and `consistent` (one idiom). Its sole
  architectural justification is preserving prior behaviour, a value the project
  explicitly disclaims (`λα. ¬compat(backward)`). The design weighs neither
  principle. Reconcile: either treat single-prompt as the N=1 degenerate of one
  unified path (singular solution), or justify the dual path on a *non-back-compat*
  architectural basis.

## Architectural-fit follow-up execution (design, pass 2) — ψ

Executed D1, D2 by updating design.md (both completed; neither blocked). Verified
the live topology against `.psi/workflows/review-task-design.edn` +
`doc/workflows.md`: each review phase `:on {"REPEAT" {:goto "<phase>-follow-up"}}`
mutates `design.md`/`design-steps.md` **before** the next phase reviews, and the
terminal `clarity-status` invoke routes via `pass-feedback-routing` over the
three `*-text` outputs. This confirmed D1's misfit (a single N-turn step cannot
interleave follow-up) and D2's misfit (dual single-prompt path justified only by
back-compat).

- **D1 (done).** Chose **option (a)**: keep the three-phase merge but reframe it
  honestly as a **deliberate topology redesign**, not a faithful refactor.
  Withdrew the "matches the live three-phase topology / faithful" framing as an
  architectural-fit overclaim. Stated the fate of the per-phase follow-up steps:
  the three `*-follow-up` steps **collapse into one `design`-profile follow-up
  step** placed after the merged review step's post-drain route, executing items
  accumulated across all three reviews; the merged step reads the design once and
  reviews all three aspects against the **same un-followed-up** design in one
  shared session. Net trade: interleaved per-phase follow-up →
  batch-review-then-follow-up, sanctioned by `λα. ¬compat(backward)`. Clarified
  the Q8 `pass-feedback-routing` equivalence is about the **routing disjunction
  only**, not the lost interleaved-follow-up structure. Rejected option (b)
  (separate phases + different exemplar) — would unwind the Q7/Q8/Q11 investment.
  Updated: Scope bullet, Q7, Q8, and added a D1 resolution subsection.
- **D2 (done).** Combined **both** reviewer options: **unified the runtime path**
  *and* **re-justified the authoring distinction on a non-back-compat basis**.
  Both `:contributions` and `:prompts` normalize at IR time into the same
  internal **prompt-queue** representation; the runtime drives **one** queue, and
  single-prompt is the genuine **N=1 degenerate** (no separate path → no drift →
  satisfies `λone_way` at the mechanism level). The two authoring surfaces remain
  but are now justified by **per-prompt addressing capability** (unnamed vs named
  prompt-group), a forward-looking axis — not back-compat; behaviour preservation
  is a *consequence* of N=1. Rejected forcing every single-turn step into
  `:prompts [{:name …}]` as ceremony harming terseness. Updated: AC-2, grammar
  precedence note, Q6, B3, the Architecture-alignment materialization bullet, and
  added a D2 resolution subsection.
- Updated Status line to record pass-2 D1–D2 executed.

## Ambiguity review (design, pass 2) — ψ

Re-reviewed design.md for ambiguities (statements admitting >1 interpretation,
undefined terms, unspecified edge behaviour), distinct from the architectural-fit
pass-2 (D1–D2) and the earlier B1–B5/C1 passes. A1–A3/B1–B5/C1/D1–D2 are
resolved. Three **new** actionable ambiguities found (E1–E3); recorded as
unchecked items in design-steps.md. Verified-clear: B5 already states routing is
skipped on both the AC-5 error and AC-6 cancellation paths.

- **E1 — "Sources read once and reused" has no specified mechanism under
  `:contributions` xor `:prompts`.** The Q7/D1 token-efficiency rationale ("design
  + architecture sources read once and reused for all three reviews") and the
  Architecture-alignment "list of prompt strings layered over a **shared
  preload**" (design.md:155–156, presented as an undecided "or") imply a
  step-level shared preamble distinct from per-prompt prompts. But the step-level
  precedence rule is `:contributions` **xor** `:prompts`, so a `:prompts` step
  carries **no** step-level `:contributions` to act as a shared preload. The
  design never states how shared source material is loaded once and shared across
  prompt-groups (relied-on live-session context from prompt 1? a step-level
  shared-contribution field the grammar shape omits?). The mechanism is
  load-bearing for the exemplar's stated rationale yet unspecified.
- **E2 — Prompt-group internal authoring precedence is unspecified.** The grammar
  shape shows a prompt-group authored via `:prompt-workflow "..."` "or
  `:contributions [...]`" (design.md:197), but no rule states whether
  `:prompt-workflow` **xor** `:contributions` holds *within* a prompt-group
  (mirroring the step-level xor), nor what an IR error is when both/neither are
  present. The within-group authoring contract is undefined.
- **E3 — Same-step / sibling-prompt-group `:prompt` source-ref legality is
  ambiguous.** Scope defers "cross-turn workflow data flow" — no source-ref
  injecting a prior same-step turn's reply into a later prompt's template — yet
  the Q12 source-ref integration says the `:prompt` selector resolves **uniformly**
  across "session `:contributions` source items" and "template `:vars`". The
  enumerated invalid `:prompt` cases (non-session, single-prompt, unknown group
  `p`, structured key `k`) do **not** address whether a prompt-group's
  contributions/template may reference an **earlier sibling prompt-group in the
  same step** via `{:step <self> :prompt p :output k}`. Scope implies invalid;
  uniform-resolution implies it resolves. Unreconciled.

## Ambiguity follow-up execution (design, pass 2) — ψ

Executed E1, E2, E3 by updating design.md (all completed; none blocked).
Grounded E1 against `doc/workflow-grammar-concepts.md` ("Session construction":
the grammar deliberately avoids a canonical `:preload` field and subsumes
preload into ordered `:contributions`), which decided against adding a new
step-level shared-preload field.

- **E1 (done).** Shared sources are carried by the **live shared child session**,
  not a step-level shared-preload field. Withdrew the undecided "list of prompt
  strings layered over a shared preload" alternative (`:contributions` xor
  `:prompts` already forbids step-level `:contributions` on a `:prompts` step;
  grammar avoids `:preload`). Mechanism: the **first** prompt-group loads sources
  once on turn 1; later prompt-groups run against the same live session and see
  the loaded sources via conversation memory — the concrete realization of the
  Q7/D1 "sources read once and reused" rationale. Updated the
  Architecture-alignment materialization bullet (added a "Shared source material"
  bullet) and added the E1 resolution subsection.
- **E2 (done).** Prompt-group **internal** authoring precedence: `:prompt-workflow`
  **xor** `:contributions` (mirrors the step-level xor); both ⇒ IR error,
  neither ⇒ IR error (no prompt body). Reported fail-fast at load/IR-normalization
  time. Updated the grammar-shape comment (`:prompt-workflow XOR :contributions`),
  added a "Prompt-group internal authoring precedence (E2)" rule to the grammar
  precedence note, and added the E2 resolution subsection.
- **E3 (done).** Same-step / sibling-prompt-group `:prompt` refs
  (`{:step <self> :prompt p :output k}`, forward or back) are **invalid in the
  first cut** — that is exactly the cross-turn workflow data flow Scope defers.
  Reconciled the Q12 "uniform resolution" wording: uniform across the substrate
  means *for refs to prior steps*; a self/same-step `:prompt` ref has no value at
  assembly time. Cross-turn context is still available to the model via the
  shared live session (E1); only workflow-level template injection of a sibling's
  reply is withheld. Added the case to the "Source-ref integration for `:prompt`"
  validation enumeration and added the E3 resolution subsection.
- Updated Status line to record pass-2 E1–E3 executed.
