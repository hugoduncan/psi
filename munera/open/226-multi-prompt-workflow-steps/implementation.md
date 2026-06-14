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

## Inconsistency review (design, pass 2) — ψ

Re-reviewed design.md for internal inconsistencies and contradictions against the
referenced artifacts (`review-task-design.edn`, `doc/workflows.md`,
`doc/workflow-grammar-concepts.md`). A1–A3/B1–B5/C1/D1–D2/E1–E3 are resolved. One
**new** actionable inconsistency found (C2); recorded as an unchecked item in
design-steps.md. Verified-consistent: Q8's three `*-text` judge args now match the
live `clarity-status` `pass-feedback-routing` args key-for-key
(`:architecture-text`/`:ambiguity-text`/`:inconsistency-text`); the E2 grammar
comment (`:prompt-workflow XOR :contributions`); B1's session-step `:yield :text`
default matches `doc/workflow-grammar-concepts.md` ("session step ⇒ yields … from
the `:final-llm-reply` output surface").

- **C2 — Merged exemplar breaks the surviving `final-summary` step's per-review
  `:yield :text` contributions; unreconciled with B1(b).** The referenced
  `review-task-design.edn`'s `final-summary` step pulls the three review phases
  via three separate contributions `{:step "architecture-review" :yield :text}`,
  `{:step "ambiguity-review" :yield :text}`,
  `{:step "inconsistency-review" :yield :text}`. The exemplar Scope merges those
  three review **steps** into one multi-prompt step, so those step names cease to
  exist. But design.md's own B1(b) makes per-prompt `:yield`
  (`{:step s :prompt p :yield k}`) **invalid**, and step-level `:yield :text`
  resolves to only the **last** prompt's reply (inconsistency), per AC-3/B1 and
  `doc/workflow-grammar-concepts.md`. So `final-summary` cannot recover the
  architecture/ambiguity review text via `:yield` after the merge. The design
  never states how `final-summary` migrates — it only notes per-prompt `:output`
  addressing is "useful for `final-summary`" (Q11) — and does not reconcile the
  exemplar's elimination of the three review steps with `final-summary`'s
  dependence on their `:yield :text` and the B1(b) "no per-prompt `:yield`" rule.
  Reconcile by stating in design.md that the merged exemplar's `final-summary`
  contributions migrate from three `{:step "<phase>-review" :yield :text}` refs to
  per-prompt `{:step "design-review" :prompt "<phase>" :output :final-llm-reply}`
  refs (the only legal way to address per-phase text under B1(b)), as part of the
  in-scope exemplar rewrite.

## Inconsistency follow-up execution (design, pass 2) — ψ

Executed C2 by updating design.md (completed; not blocked). Verified the live
`final-summary` step in `.psi/workflows/review-task-design.edn`: it consumes the
three review phases via three `{:step "<phase>-review" :yield :text}`
contributions, which the exemplar merge eliminates (those step names cease to
exist).

- **C2 (done).** Stated that the merged exemplar's `final-summary` step migrates
  its three per-phase contributions from `{:step "<phase>-review" :yield :text}`
  to per-prompt `{:step "design-review" :prompt "<phase>" :output
  :final-llm-reply}` refs — the only legal per-phase text addressing once the
  three review steps merge (per-prompt `:yield` is invalid under B1(b); the
  merged step-level `:yield :text` resolves only to the last prompt's reply).
  This reuses the per-prompt reply addressing already load-bearing for the merged
  step's post-drain routing (Q8), introducing no new surface. Updated: Scope
  bullet, Q7, Q11, and added an "Inconsistency resolutions (C2, pass 2)" section.
  Updated the Status line to record pass-2 C2 executed.

## Design restructure — prune + exemplar split (ψ)

After two full design-review passes the design.md had grown 8 KB → 44 KB (737
lines) and each pass was still surfacing ~1 inconsistency, driven largely by the
embedded `review-task-design.edn` exemplar (C1/C2/D1 were all exemplar
consequences). The task-lifecycle delegate run also failed rather than
terminating cleanly. Per `small ≡ one_intent` and to let the core capability
freeze:

- **Pruned design.md** back to a tight capability-only design (~270 lines). All
  genuinely-decided content (Q1–Q6, Q9–Q12, B1–B5, D2, E1–E3) is folded into the
  Scope / Acceptance criteria / Grammar shape / Source-ref / Architecture /
  Resolved-decisions sections directly, rather than kept as a review-pass log
  (that history lives in git: see commits 6529a0bd8..f8a37d16f).
- **Split the exemplar into task 227** (`227-review-task-design-multi-prompt-exemplar`),
  which now owns the review-task-design merge and all its topology/routing
  decisions (Q7, Q8, D1, C1, C2). 227 depends on 226.
- The earlier design-steps.md follow-ups (A1–A3, B1–B5, C1–C2, D1–D2, E1–E3) are
  all resolved/incorporated; that file is left as the historical record of the
  pre-prune review and is superseded by the rewritten design.md.

226 is now single-intent (the capability) and ready for a fresh planning pass.

## Architectural-fit review (design, pass 3 — post-prune) — ψ

Re-reviewed the **pruned** capability-only design.md for architectural fit
against AGENTS.md (VSM `∀change → event → log → replayable`, workflow-runtime
boundary, `λone_way`, `λα. ¬compat(backward)`), doc/workflow_statechart_canonical.md
(resume/suspend-driven runtime: `statechart-runtime`, `psi.workflow-runtime.core`
resume/cancellation), and doc/workflow-grammar-concepts.md (shared data-flow
substrate, output surfaces, `:yields`). A1–A3/B1–B5/C1–C2/D1–D2/E1–E3 are
resolved or moved to task 227 with the exemplar. One **new** actionable misfit
(F1); recorded as an unchecked item in design-steps.md. Verified-fit:
`:contributions` xor `:prompts` → one unified queue path (D2); `:prompt`
source-ref on the shared substrate with fail-fast IR validation (A2/E3); shared
sources via live session, no `:preload` field (E1, matches grammar-concepts
"preload subsumed by ordered contributions"); per-prompt records in the canonical
progression substrate (A3); generic queue mechanism vs authored prompts
(workflow-runtime boundary).

- **F1 — synchronous-drain framing vs the resume/suspend-driven runtime; the
  resume-mid-queue contract is unspecified and load-bearing for the claimed S4
  replay-fidelity.** The design models the N-prompt queue as a synchronous drain
  ("the next is submitted only after the prior turn finishes; routing/judging runs
  once, after the queue drains") emitting **one** post-drain
  `:pending-actor-result`. But the canonical runtime is resume/suspend-driven
  (`statechart-runtime`; `psi.workflow-runtime.core` exposes run resume; AI
  generation is an async effect that suspends the run and resumes on completion),
  so an internal N-turn loop introduces **N suspend points inside one statechart
  step**. A3 commits each turn's *result* to the progression substrate, but the
  design never states the **resume contract across the queue**: on resume (async
  turn completion, process restart, replay) the queue-driving loop must continue
  at the next **un-run** prompt from recorded progression rather than re-submit
  already-completed turns. Re-submitting a completed turn re-fires the
  side-effectful, non-deterministic `ai/generate` effect — directly violating the
  VSM `∀change → event → log → replayable` ethos and falsifying the design's own
  "replay-faithful" claim. This is distinct from A3 (which only says results are
  *recorded*, not *consumed to resume*) and from Q5 (one post-drain route). The
  design must either state the resume-from-progression contract for the internal
  queue (and where the suspend/resume boundary sits relative to the single
  statechart step) or scope mid-queue resume out explicitly with its replay-fidelity
  consequences stated.

## Architectural-fit follow-up execution (design, pass 3 — post-prune) — ψ

Executed F1 by updating design.md (completed; not blocked). Grounded against
`doc/workflow_statechart_canonical.md`: `psi.workflow-runtime.statechart-runtime`
(resume/suspend runtime), `psi.workflow-runtime.core` run resume +
`resume-and-execute-run!`, and `psi.workflow-runtime.progression-recording` (the
canonical record/update substrate A3 already writes per-turn results into).

- **F1 (done).** Committed to **resume-from-progression** for the internal queue
  (rejected scoping mid-queue resume out — it would falsify the replay-faithful
  claim). Stated: `ai/generate` is an async effect that suspends the run, so N
  prompts = **N suspend points inside the one statechart step**; the
  "synchronous drain" is logical ordering, not a blocking loop. On every resume
  (async completion, process restart, replay) the queue-driving loop reads
  recorded per-prompt progression and **continues at the next un-run prompt**,
  never re-submitting a prompt with an existing turn record — so a completed
  turn's non-deterministic `ai/generate` never re-fires (upholds VSM
  `∀change → event → log → replayable`). Located the suspend/resume boundary
  **inside** the single statechart step (resume re-enters the step, consults
  progression, does not restart the queue); post-drain route (Q5) reached only
  once every prompt has a recorded turn. Distinguished F1 from A3 (records) and
  Q5 (one route) — F1 is the consume-to-resume rule tying them to the async
  runtime.
- Edits: Intent (added the logical-ordering / N-suspend-cycle clarification),
  Architecture alignment (new "Resume/suspend contract (F1)" bullet), and
  Acceptance criteria (new AC-7 resume-from-progression idempotency; docs/tests
  bumped to AC-8 with a mid-queue-resume test added to the coverage list). No
  existing AC-1..6 references renumbered.

## Ambiguity review (design, pass 3 — post-prune) — ψ

Re-reviewed the **pruned** capability-only design.md for ambiguities (statements
admitting >1 interpretation, undefined terms, unspecified edge behaviour),
distinct from the architectural-fit pass-3 (F1) and the earlier B1–B5/E1–E3
ambiguity passes. A1–A3/B1–B5/C1–C2/D1–D2/E1–E3/F1 are resolved or moved to task
227. Two **new** actionable ambiguities found (G1, G2); recorded as unchecked
items in design-steps.md. Verified-clear: B5 (cancellation outcome) and AC-7
(resume-from-progression) are now explicit; one-element `:prompts` legality (B3),
per-prompt `:transcript` slicing (B2), and yielded-value composition (B1) are
stated in the pruned design.

- **G1 — AC-5 (intermediate-error path) is silent on already-completed
  per-prompt turn records.** AC-6 explicitly states that on cancellation the
  "completed per-prompt turn records [are] retained and introspectable." AC-5
  (intermediate turn error) states only "queue stops, step `:failed` with payload
  naming the failing prompt, routing skipped" — it does **not** state whether the
  turn records of prompts that completed *before* the failing one are retained and
  introspectable, nor whether the failing prompt has any partial record. The two
  abort paths (AC-5 error / AC-6 cancellation) are asymmetric on a load-bearing
  S4-introspection point: a reader cannot tell whether `:failed` discards prior
  per-prompt records or keeps them like `:cancelled` does.
- **G2 — Judge per-prompt references vs the "same step being assembled" invalid
  rule.** AC-4 says routing runs once after the drain "over the post-drain step
  result; the judge may reference per-prompt surfaces." But the Source-ref
  integration validation list marks a `:prompt` selector **invalid** when "it
  targets the **same step being assembled** (sibling-group ref, forward or back) —
  the deferred cross-turn data flow." The step's own `:judge` is part of step `s`
  and would address its prompt-groups via `{:step s :prompt p :output k}` — i.e. a
  same-step `:prompt` ref. The design never states whether the post-drain judge is
  the *permitted* post-drain case (all turns recorded; not an assembly-time
  forward/back sibling ref) or is caught by the same-step invalid rule. AC-4
  (judge may reference per-prompt surfaces) and the validation enumeration
  (same-step `:prompt` refs invalid) are reconcilable but not reconciled: it is
  ambiguous whether a step's judge may use `:prompt` refs to its own groups, and
  if so why that escapes the same-step prohibition (e.g. judge resolves
  post-drain, unlike assembly-time contributions/templates).

## Ambiguity follow-up execution (design, pass 3 — post-prune) — ψ

Executed G1, G2 by updating design.md (both completed; neither blocked).

- **G1 (done).** Made AC-5 symmetric with AC-6 on S4 introspectability. Stated
  that per-prompt turn records for prompts completed **before** the failing one
  are retained and introspectable (mirroring AC-6's cancellation path), and that
  the failing prompt leaves **no** completed turn record — it is identified only
  by the `:failed` payload, which carries the error. Resolves the prior
  asymmetry where a reader could not tell whether `:failed` discards prior
  per-prompt records or keeps them like `:cancelled`. Edit: AC-5.
- **G2 (done).** Reconciled AC-4 ("judge may reference per-prompt surfaces") with
  the Source-ref "same step being assembled" invalid rule by carving out the
  post-drain judge as the **permitted** same-step `:prompt` case. Rationale: the
  judge resolves **after the drain**, once every prompt's turn record exists (A3),
  so `{:step s :prompt p :output k}` has a present, deterministic value — unlike
  an assembly-time contribution/template that would reference a sibling turn not
  yet run. Edits: AC-4 (states the judge's `:prompt` refs are the permitted
  same-step case), and a new "Post-drain judge exception" paragraph in the
  Source-ref integration section carving the judge out of the same-step invalid
  rule. The remaining same-step prohibition continues to apply to assembly-time
  contributions/templates (the deferred cross-turn data flow, E3).

## Inconsistency review (design, pass 3 — post-prune) — ψ

Re-reviewed the pruned design.md for internal inconsistencies and against the
referenced artifacts (`doc/workflow-grammar.md`, `doc/workflow-grammar-concepts.md`,
`step_execution.clj` raw-outputs, `materialize-/split-step-session-conversation`).
A1–A3/B1–B5/C1–C2/D1–D2/E1–E3/F1/G1–G2 are resolved or moved to task 227. One
**new** actionable inconsistency found (C3); recorded as an unchecked item in
design-steps.md. Verified-consistent: per-prompt text surfaces
(`:final-llm-reply`/`:transcript`) match `doc/workflow-grammar-concepts.md`'s
session output surfaces (line 269; `:text` is a `:yield` field, not a step-local
surface — correctly excluded); structured-output `:outputs` field name and the
"at most one entry / final-turn granularity" claim match `doc/workflow-grammar.md`
(219); the Problem-statement description of `split-step-session-conversation`
(last user message → prompt, prior messages preload) matches the code;
`execute-with-ranked-fallback!` and `execute-session-step!`/`execute-actor-turn!`/
`execute-session-turn!` exist as named; F1's resume/suspend substrate references
resolve.

- **C3 — Architecture-alignment "per-prompt turn records keyed by `:name`"
  contradicts the unnamed `:contributions` group for the N=1 degenerate.** The
  Architecture-alignment bullet (design.md:202) states the single post-drain
  `:pending-actor-result` "carries ordered per-prompt turn records (keyed by
  `:name`, …) plus the step-level rollup," and presents this as a general
  property of the **one unified queue path** (single-prompt = N=1 degenerate). But
  Concepts (design.md:65, "named when authored under `:prompts`") and Grammar
  Step-level precedence (design.md:136–137, "`:contributions` → one **unnamed**
  group (step-level surfaces only); `:prompts` → **named** groups") establish that
  the `:contributions` form yields an **unnamed** group with no `:name`. For the
  N=1 `:contributions` case the keyed-by-`:name` per-prompt record therefore
  cannot be keyed, yet the unified-path framing says the result *carries* per-prompt
  records. The design never states whether the unnamed group produces **no**
  addressable per-prompt record (only the step-level rollup) or appears in the
  records map under some synthetic key — leaving the canonical result shape
  contradictory for the degenerate path it foregrounds. Reconcile by stating in
  the Architecture-alignment bullet that named (`:prompts`) groups contribute
  per-prompt records keyed by `:name` while the unnamed (`:contributions`) group
  contributes only the step-level rollup (no per-prompt addressable record), so
  "keyed by `:name`" applies only where a name exists.

## Inconsistency follow-up execution (design, pass 3 — post-prune) — ψ

Executed C3 by updating design.md (completed; not blocked).

- **C3 (done).** Reconciled the Architecture-alignment per-turn-records bullet
  with the unnamed `:contributions` group. Restated the bullet so the single
  post-drain `:pending-actor-result` carries the step-level rollup **plus**
  per-prompt records keyed by `:name` **only for named (`:prompts`) groups**; the
  unnamed `:contributions` group (incl. the N=1 single-prompt degenerate) has no
  `:name`, so it contributes **only** the step-level rollup and **no** addressable
  per-prompt record. "Keyed by `:name`" now explicitly applies only where a name
  exists, so the unified-path result shape is consistent for the degenerate case —
  resolving the contradiction with Concepts (named only under `:prompts`) and
  Grammar Step-level precedence (`:contributions` → unnamed group, step-level
  surfaces only). Edit: Architecture-alignment "Per-turn results are recorded"
  bullet.

## Plan/steps ambiguity review (pass 1) — ψ

Reviewed plan.md + steps.md for ambiguities (statements admitting >1
interpretation, undefined terms, unspecified execution edge behaviour),
distinct from the design-review passes (A1–A3/B1–B5/C1–C3/D1–D2/E1–E3/F1/G1–G2,
all resolved or moved to 227). First plan-level pass. Four new actionable
ambiguities found (P1–P4); recorded as unchecked items in steps.md.
Verified-clear: per-prompt records keyed-by-`:name`-for-named-only (Slice 3 step
matches design C3); abort-path outcomes (Slice 6) match AC-5/AC-6; touch points
ground against real code (`ir.clj` `ref-errors`/`semantic-errors`/
`step-output-surfaces`, `compiler.clj` markdown-session/`:prompt-workflow`
compile, `step_execution.clj`/`statechart_runtime.clj` session branch).

- **P1 — Normalization ownership undecided (`compiler.clj` +/or `ir.clj`).**
  Slice 1's IR-normalization step writes "Normalize … at IR/compile time
  (`compiler.clj` +/or `ir.clj`)", and the Touch points list both files. But the
  two live in different components (`compiler.clj` = workflow-loader;
  `ir.clj` = workflow-runtime) and the workflow-runtime boundary
  (`λ workflow_runtime_boundary`) makes the loader-vs-runtime split load-bearing.
  The plan never decides which component owns the `:contributions`/`:prompt-workflow`
  → unnamed-group queue normalization (and, in Slice 2, `:prompts` → named-group
  normalization), so a reader cannot tell whether normalization is authored-form
  compilation (loader) or runtime IR shaping (runtime). `λone_way` wants a
  singular owner.
- **P2 — Author-facing grammar docs: per-slice spec vs the dedicated Slice 7.**
  The Slice-order closing note says "Each slice follows the change_chain: update
  spec (grammar docs/examples as needed) → tests → code → … → docs", implying
  every slice touches `doc/workflow-grammar*.md`. But Slice 7 is a dedicated
  "Docs" slice that writes the `:prompts` form, per-prompt surfaces, `:prompt`
  source-ref, drain/route, and resume contract. It is ambiguous whether the
  author-facing grammar docs are updated incrementally per slice (as the
  change_chain "spec") or deferred wholesale to Slice 7 — and which "spec" each
  earlier slice updates if grammar docs wait. Affects every slice's done-gate.
- **P3 — Slice-1 equivalence "baseline" artifact/mechanism unspecified.** Slice 1
  step 1 says "capture the existing `execute-session-step!` envelope shape … under
  a green run of `…step_execution_test.clj` as the equivalence baseline", and R4
  says "treat any diff as a defect". But the baseline's concrete form is undefined:
  a committed characterization/golden snapshot test, an asserted-shape test, or an
  informal recorded note. Without a concrete committed reference, "any diff is a
  defect" has no enforceable comparand, and the Slice-1 done-gate ("full existing
  session-step suite green unchanged") may not actually pin the envelope shape the
  N=1 path must reproduce.
- **P4 — Slice 3 "sequential N-turn drain" vs Slice 5 "resume-from-progression":
  what each slice independently delivers is ambiguous.** Each turn is an async
  `ai/generate` effect that suspends the run (design F1: "N prompts = N suspend
  points inside the one statechart step"), so advancing from prompt _n_ to _n+1_
  already requires a suspend/resume re-entry that consults recorded progression to
  pick the next un-run prompt. Slice 3 ("run prompt _n+1_ after prompt _n_
  completes … emit one post-drain result") presupposes that re-entry, yet the
  resume-from-progression contract (consult progression, continue at next un-run
  prompt, no `ai/generate` re-fire) is deferred to Slice 5. It is unspecified
  whether Slice 3 already builds the in-run suspend/resume drain (leaving Slice 5
  to add only the process-restart/replay case) or whether Slice 3's drain is
  unrealizable without Slice 5 (mis-drawn slice boundary). The two slices'
  independent acceptance and the shared suspend mechanism are not disambiguated.

## Plan/steps ambiguity follow-up execution (pass 1) — ψ

Executed P1–P4 by updating plan.md + steps.md (all completed; none blocked). No
code/test/doc changes were required — these are plan-disambiguation follow-ups.

- **P1 (done).** Decided a single normalization owner per `λ
  workflow_runtime_boundary`/`λone_way`: the **workflow-loader `compiler.clj`**
  owns the authored-form → normalized-prompt-queue **transform**
  (`:contributions`/`:prompt-workflow` → unnamed group; `:prompts` → named
  groups), resolving each prompt body exactly as the existing single-prompt
  `:prompt-workflow`/markdown lowering does; **workflow-runtime `ir.clj`** owns
  only the normalized-queue **schema + semantic validation + output surfaces** and
  consumes normalized IR (does not transform authored forms). Replaced
  "`compiler.clj` +/or `ir.clj`" in Slice 1. Updated plan.md Key-decisions (new
  P1 bullet) + Touch points (explicit ownership split) and steps.md Slice 1.
- **P2 (done).** Chose **incremental per-slice** author-facing grammar docs (per
  change_chain "update spec per change" + `coherence`), reframing Slice 7 as a
  consolidation + changelog + TraceID coherence slice rather than the sole
  doc-authoring slice. Stated per-slice doc ownership: Slice 1 = none (internal
  unification, no author-visible surface); Slice 2 = `:prompts` form/precedence in
  `workflow-grammar.md`; Slices 3–6 = drain/records, per-prompt surfaces +
  `:prompt` source-ref, resume contract, abort outcomes in
  `workflow-grammar-concepts.md`. Added a "docs cadence (P2)" note to the
  Slice-order section, reworded the change_chain closing note, reframed Slice 7,
  and added per-slice Docs items to steps.md Slices 2–6.
- **P3 (done).** Named the Slice-1 equivalence-baseline artifact: a committed
  **asserted-shape characterization test** (not a full-content golden snapshot)
  pinning the single-prompt `execute-session-step!` `:pending-actor-result`
  envelope keys, added in/alongside `step_execution_test.clj`, **committed first**
  and green against pre-refactor code; the unified-path refactor must keep it green
  unchanged. Made this test (not "suite green unchanged") the Slice-1 done-gate
  comparand for R4. Updated plan.md R4 and steps.md Slice 1 (characterization step
  + done-gate step).
- **P4 (done).** Disambiguated the Slice 3 / Slice 5 boundary: **Slice 3 builds
  the in-run suspend/resume drain** (each turn is an async `ai/generate` suspend
  per design F1, so advancing _n_→_n+1_ already requires progression-consulting
  re-entry; the driver selects the next un-run prompt from recorded progression,
  not a counter). **Slice 5 adds only the process-restart/replay resume case** and
  its idempotency proof. Gave each slice an independently testable acceptance
  (Slice 3: live-run progression-probe ordering/drain; Slice 5: resume
  reconstructed from persisted progression / replayed log runs only un-run prompts
  with zero `ai/generate` re-fire). Updated plan.md Slice-order 3 & 5 + R1 and
  steps.md Slice 3 & Slice 5 headers/steps.
