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

## Plan/steps inconsistency review (pass 1) — ψ

Reviewed plan.md + steps.md for internal inconsistencies and contradictions
against each other and the referenced design.md decisions and code touch points.
Distinct from the plan/steps **ambiguity** pass (P1–P4, resolved) and the
design-review passes. First plan-level inconsistency pass. Six new actionable
inconsistencies found (PI1–PI6); recorded as unchecked items in steps.md.
Verified-consistent: docs-cadence per-slice ownership (Slice 1 none / Slice 2
grammar / Slices 3–6 concepts) matches between plan and steps; abort-path
outcomes (Slice 6) match AC-5/AC-6; P-label cross-references (P1–P4) resolve;
Final-verification AC-1..AC-8 list matches design's eight ACs.

- **PI1 — plan Slice 1 says "IR normalizes", contradicting the resolved P1
  ownership decision.** Slice 1 (plan.md:146) reads "IR normalizes
  `:contributions`/`:prompt-workflow` → one unnamed prompt-group", but the P1
  Key-decision, the Touch points split, and steps.md Slice 1 all place the
  authored-form → normalized-queue **transform** in the workflow-loader
  `compiler.clj`, with `ir.clj` (workflow-runtime) owning only the
  schema/validation/surfaces and **not** transforming authored forms. "IR
  normalizes" attributes the transform to the runtime IR — the exact split P1
  resolved against `λ workflow_runtime_boundary`. Reconcile by rewording Slice 1
  to "the compiler (workflow-loader) normalizes … → one unnamed prompt-group"
  consistent with P1 / Touch points / steps Slice 1.
- **PI2 — plan Slice 1 acceptance contradicts plan R4's characterization-test
  done-gate.** Slice 1's acceptance (plan.md:149) is "full existing session-step
  suite green (AC-2)", but R4 (plan.md:138) states the committed asserted-shape
  envelope characterization test — "not merely 'suite green unchanged'" — is the
  Slice-1 done-gate comparand, and steps.md Slice 1's done-gate requires that test
  green **and** the suite green. The Slice-order Slice 1 acceptance line was not
  updated when P3 made the characterization test the comparand. Reconcile by
  citing the characterization test as the Slice-1 done-gate in the Slice-order
  Slice 1 acceptance, matching R4 and steps Slice 1.
- **PI3 — plan Slice 2 omits the step-level `:contributions`/`:prompt-workflow`
  xor `:prompts` validation that steps Slice 2 schedules.** Plan Slice 2
  enumerates IR validation as "empty `:prompts` error, one-element valid,
  duplicate group-name error, group xor error (B3/B4/E2)" — no step-level xor.
  steps.md Slice 2 has a dedicated item "Add step-level precedence validation:
  `:contributions`/`:prompt-workflow` **xor** `:prompts` (both ⇒ IR error)" and a
  "step-xor" test case. The design grammar's step-level precedence is a required
  validation; plan Slice 2's description drops it while steps includes it.
  Reconcile by adding the step-level xor validation to plan Slice 2 (the slice
  that lands `:prompts`).
- **PI4 — plan Approach layering order mis-states the actual slice order.** The
  Approach (plan.md:16) says the path is "unified single-prompt path lands first …
  before N>1, **addressing, abort paths, and resume** are layered on", listing
  abort before resume. The actual Slice order layers addressing (Slice 4) →
  resume (Slice 5) → abort (Slice 6) — resume **before** abort. Reconcile by
  reordering the Approach narrative to "addressing, resume, and abort paths".
- **PI5 — Slice 7 consolidation verify-list omits the abort/cancellation doc
  content Slice 6 introduces.** Slice 7's consolidation item (steps.md:159–162)
  verifies "the `:prompts` form / group-internal xor / per-prompt surfaces /
  `:prompt` source-ref + validation + post-drain-judge carve-out / drain-route /
  resume contract are all present and consistent" — but Slice 6 adds
  abort/cancellation outcomes (`:failed` vs `:cancelled`, retained records,
  routing skipped) to `doc/workflow-grammar-concepts.md`, and that surface is
  absent from the Slice-7 coherence checklist. Reconcile by adding the
  abort/cancellation outcomes to the Slice 7 consolidation verify-list so every
  per-slice doc surface is covered.
- **PI6 — Touch point `statechart.clj` topology-confirmation has no covering
  step.** Plan Touch points (plan.md:95) lists `statechart.clj` — "confirm the
  single acting→(judging)→record-result step topology still holds … no per-prompt
  statechart states", an explicit verification action, but no steps.md checklist
  item schedules it (steps Slice 5 confirms the `statechart_runtime.clj` resume
  branch, a different file/concern). Reconcile by adding a step (Slice 3 or 5)
  confirming the single-step topology / no per-prompt statechart states, or drop
  the touch point if subsumed.

## Plan/steps inconsistency follow-ups (pass 1) executed — ψ

Executed PI1–PI6 (plan/steps-only edits; no code/test/doc/source change needed —
these are task-artifact consistency reconciliations).

- **PI1** — plan.md Slice-order Slice 1 reworded: "IR normalizes …" → "The
  compiler (workflow-loader `compiler.clj`) normalizes … (P1); `ir.clj`
  (workflow-runtime) owns only the normalized-queue schema/validation/surfaces",
  matching the P1 ownership decision, Touch points split, and steps Slice 1.
- **PI2** — plan.md Slice-order Slice 1 acceptance now cites the committed
  asserted-shape envelope characterization test (P3/R4) green-unchanged as the
  Slice-1 done-gate comparand ("not merely 'suite green unchanged'") **and** the
  full session-step suite green (AC-2), matching R4 and steps Slice 1's done-gate.
- **PI3** — plan.md Slice 2 IR-validation enumeration now lists the step-level
  `:contributions`/`:prompt-workflow` **xor** `:prompts` (both ⇒ IR error) ahead
  of the empty/one-element/duplicate-name/group-xor cases, matching steps Slice 2
  and its step-xor test case.
- **PI4** — plan.md Approach layering narrative reordered "addressing, abort
  paths, and resume" → "addressing, resume, and abort paths", matching the actual
  Slice order (addressing=4 → resume=5 → abort=6).
- **PI5** — steps.md Slice 7 consolidation verify-list extended with the
  abort/cancellation outcomes (`:failed` vs `:cancelled`, retained records,
  routing skipped) introduced by Slice 6, so every per-slice author-facing doc
  surface is in the coherence checklist.
- **PI6** — added a Slice 3 steps item confirming the single
  acting→(judging)→record-result statechart topology / no per-prompt statechart
  states (plan Touch point `statechart.clj`), since that touch point had no
  covering step (steps Slice 5 covers the distinct `statechart_runtime.clj`
  resume branch). Kept the touch point rather than dropping it.

## Plan/steps ambiguity review (pass 2) — ψ

Re-reviewed plan.md + steps.md for ambiguities (statements admitting >1
interpretation, undefined terms, unspecified execution/test-observable edge
behaviour), distinct from pass 1 (P1–P4) and the inconsistency pass (PI1–PI6),
all resolved. Four **new** actionable ambiguities found (P5–P8); recorded as
unchecked items in steps.md. Verified-clear: normalization ownership (P1),
docs-cadence (P2), equivalence-baseline artifact (P3), and the Slice 3/5 in-run-
vs-restart boundary (P4) are now explicit; abort-path outcomes match AC-5/AC-6.

- **P5 — Final-turn structured-output gating vs the no-counter
  progression-driven selection rule (Slice 3 / R5).** Slice 3 explicitly forbids
  an in-memory counter for picking the next prompt ("progression-driven, **not**
  an in-memory counter"), yet R5 + the Slice 3 step "Request structured
  `:outputs` on the **final** turn only" require the driver to recognize that a
  selected turn is the *last* one. The plan/steps never state how the driver
  identifies the final group while selecting progression-driven: the static
  ordered IR queue makes "final" = last index (derivable from IR, independent of
  any counter, consistent with the no-counter rule which governs *next-un-run
  selection* only), but this is left implicit. A reader cannot tell whether
  final-turn detection is permitted to use the static queue length/position or
  whether it (illegally) needs the forbidden counter. Reconcile by stating that
  final-turn detection uses the static queue position (the last group in the
  ordered normalized IR queue), orthogonal to the progression-driven *selection*
  of the next un-run prompt.

- **P6 — "progression-state probe" is an undefined test observable (Slice 3
  acceptance).** Both plan.md Slice-order 3 and steps.md Slice 3 assert the
  ordering/selection invariant "via a progression-state probe (not turn count)",
  but neither defines what the probe reads. The acceptance is not executable as
  written: a reader cannot tell what observable the test inspects (the recorded
  per-prompt progression entries under the step's attempt in
  `progression_recording.clj`? a runtime/EQL introspection surface? the
  `:pending-actor-result` records map?). Name the concrete observable the probe
  reads so "assert via a progression-state probe, not turn count" has a definite
  meaning.

- **P7 — "full existing session-step suite" scope is undefined for the Slice-1
  done-gate.** Slice 1's done-gate (plan.md:154, steps.md:35) requires "the full
  existing session-step suite green unchanged" alongside the committed
  characterization test, but the concrete scope of "session-step suite" is never
  defined — only `step_execution_test.clj` is named (for the characterization
  test), while Final verification names three separate component Scry suites
  (workflow-runtime + workflow-loader + workflow-step-materialization). It is
  ambiguous whether the Slice-1 "session-step suite" gate is just
  `step_execution_test.clj`, the whole workflow-runtime suite, or all three
  component suites. Define which test namespaces/suite constitute the
  "session-step suite" whose green-unchanged status gates Slice 1.

- **P8 — The observable for "no `ai/generate` re-fire" / "zero re-fired
  `ai/generate` effects" is unspecified (Slices 3 and 5).** Multiple steps assert
  the idempotency invariant — Slice 3: "never re-fire a completed turn within the
  live run"; Slice 5: "completed turns not re-fired", "replaying the event log
  reproduces the same per-prompt records without re-firing completed
  `ai/generate` effects", "zero re-fired `ai/generate` effects" — but none states
  *how a test observes* that a completed turn's `ai/generate` did not re-fire
  (count of `ai/generate` effects emitted from the dispatch/effect boundary? a
  stubbed generate seam call-count? absence of a second turn record / no
  progression mutation for an already-recorded prompt?). Without a named
  observable the "zero re-fire" acceptance for both slices has no enforceable
  measurement. Name the concrete observable (e.g. emitted-`ai/generate`-effect
  count at the effect boundary, or a generate-seam invocation probe) used to
  assert non-re-fire across Slice 3's live drain and Slice 5's
  restart/replay resume.

## Plan/steps ambiguity follow-ups (pass 2) executed — ψ

Executed P5–P8 (plan/steps-only disambiguation edits; no code/test/doc/source
change needed — these clarify acceptance/test-observable wording).

- **P5 (done).** Stated final-turn detection (structured-output gating) is a
  **static IR/position property** — the last group in the ordered normalized IR
  prompt-queue (`= last index`) — **orthogonal** to the progression-driven
  next-un-run selection. The no-counter rule governs only *which un-run prompt
  runs next* (read from recorded progression); *whether the selected group is the
  last one* is decided by static queue position, needing no counter. Edits:
  plan.md R5 (new "Final-turn detection (P5)" clause) + Slice-order 3; steps.md
  Slice 3 structured-`:outputs` item.
- **P6 (done).** Defined the "progression-state probe" observable: the **recorded
  per-prompt turn-record set under the step's attempt, read back through
  `progression_recording.clj`** (the same substrate the driver consults to pick
  the next un-run prompt). The test reads which prompts already have a recorded
  turn and asserts the next submission is the lowest-position un-run group — not a
  turn count. Edits: plan.md Slice-order 3 acceptance; steps.md Slice 3 runtime-
  tests item.
- **P7 (done).** Scoped the Slice-1 done-gate "session-step suite" to the
  workflow-runtime `step_execution_test.clj` namespace
  (`psi.workflow-runtime.statechart-runtime.step-execution-test`) that houses the
  characterization test — explicitly **distinct from** Final verification's
  broader three-component Scry run (workflow-runtime + workflow-loader +
  workflow-step-materialization). The Slice-1 gate is the focused session-step
  namespace, not the three-suite run. Edits: plan.md Slice-order 1 acceptance;
  steps.md Slice 1 done-gate item.
- **P8 (done).** Named the non-re-fire observable shared by Slice 3 (live drain)
  and Slice 5 (restart/replay resume): the **count of `ai/generate` effects
  emitted at the dispatch/effect boundary** (captured via the test effect seam) —
  exactly one per un-run prompt, **zero** for any prompt with an existing turn
  record — corroborated by **no second turn record / no progression mutation**
  for an already-recorded prompt. Slice 5 additionally asserts it across
  reconstructed/replayed state. Edits: plan.md Slice-order 5 acceptance (new
  "Non-re-fire observable (P8)" clause); steps.md Slice 3 (new non-re-fire assert
  item) + Slice 5 replay-path item.

## Plan/steps inconsistency review (pass 2) — ψ

Re-reviewed plan.md + steps.md for internal inconsistencies and contradictions
against each other and the referenced design.md / code touch points. Distinct
from the plan/steps inconsistency pass 1 (PI1–PI6, all resolved) and the
plan/steps ambiguity passes (P1–P8, resolved). Two **new** actionable
inconsistencies found (PI7, PI8); recorded as unchecked items in steps.md.
Verified-consistent: PI1–PI6 reconciliations hold (Slice 1 compiler-owns-transform
wording, Slice 1 characterization-test done-gate, Slice 2 step-level xor, Approach
layering order, Slice 7 abort-doc verify-list, Slice 3 statechart.clj topology
step); docs-cadence per-slice ownership matches; abort-path outcomes (Slice 6)
match AC-5/AC-6; AC-1..AC-8 coverage list matches design's eight ACs; the
`execute-session-step!` `:else` call site (statechart_runtime.clj:276) and the
named touch-point files exist as referenced.

- **PI7 — statechart_runtime.clj in-run `:else`-branch resume-from-progression
  is a plan Slice-3 touch point with no covering steps item, and the two files
  disagree on which component owns next-un-run selection.** plan.md Touch points
  (plan.md:89) assigns the `statechart_runtime.clj` session (`:else`) branch the
  "resume-from-progression (consult recorded per-prompt progression, continue at
  next un-run prompt), suspend/resume inside the one statechart step" work, and
  plan Slice 3 states "Slice 3 therefore builds that **in-run** suspend/resume
  drain mechanism" (Slice 5 adds "only the process-restart/replay resume case on
  top"). But steps.md Slice 3 has **no** `statechart_runtime.clj` item — its
  next-un-run selection is placed entirely inside `execute-session-step!`
  (step_execution.clj): "Extend the queue driver in `execute-session-step!` …
  select the next un-run prompt from recorded per-prompt progression". The only
  steps `statechart_runtime.clj` item is Slice 5 (steps.md:145), framed as
  "**Confirm** … on **process-restart** re-entry" — verification, restart-scoped,
  not the in-run drain build. So (a) the in-run `:else`-branch
  resume-from-progression work plan Slice 3 says it *builds* has no covering steps
  item in Slice 3, and (b) plan (statechart_runtime.clj `:else` branch owns
  "continue at next un-run prompt") and steps Slice 3 (`execute-session-step!`
  owns next-un-run selection) **contradict** on which file owns the in-run
  next-un-run selection. Reconcile by either adding a Slice 3 steps item building
  the in-run `statechart_runtime.clj` `:else`-branch resume-from-progression
  re-entry (and aligning the file-ownership wording with plan Touch points), or —
  if `execute-session-step!` truly owns next-un-run selection — correcting the
  plan `statechart_runtime.clj` touch point to scope it to the restart/replay
  re-entry only (matching steps Slice 5) so the in-run selection ownership is
  stated once, consistently, in both files.
- **PI8 — steps.md "Final verification" full three-suite Scry run has no plan
  counterpart, yet plan references "Final verification".** steps.md has a
  dedicated `## Final verification` section (steps.md:197) whose first item runs
  "the full workflow-runtime + workflow-loader + workflow-step-materialization
  Scry suites green". plan.md has no Final-verification slice/section and no step
  scheduling that full three-suite run — its slices stop at Slice 7 ("Docs
  consolidation + changelog + coherence"), and its only Scry guidance is "run the
  relevant Scry suites **per slice**". Yet plan.md:170–172 explicitly contrasts
  the Slice-1 gate with "**Final verification's** broader three-component Scry run
  (workflow-runtime + workflow-loader + workflow-step-materialization) … the full
  three-suite run" — a dangling reference to a "Final verification" element the
  plan never defines as a step. Reconcile by adding a Final-verification entry to
  plan.md (the full three-component Scry run + AC-1..AC-8 coverage confirm)
  mirroring steps.md's section, so the "Final verification" plan references
  resolve to a defined plan step.

## Plan/steps inconsistency follow-ups (pass 2) executed — ψ

Executed PI7–PI8 (plan-only edits; no code/test/doc/source change needed — these
are task-artifact consistency reconciliations). Grounded against the code: in
`statechart_runtime.clj` the session (`:else`) branch (line ~276) is the re-entry
point that calls `step-execution/execute-session-step!` with the prompt; the
queue driver `execute-session-step!` (step_execution.clj) is where next-un-run
selection naturally lives — confirming steps Slice 3's placement.

- **PI7 (done).** Chose the reviewer's option (b): scoped the plan
  `statechart_runtime.clj` Touch point to the **suspend/resume re-entry boundary**
  (the point through which `execute-session-step!` is re-driven on each resume —
  in-run async-turn completion and, in Slice 5, process-restart/replay), and
  stated next-un-run **selection** ownership **once** in the queue driver
  `execute-session-step!` (matching steps Slice 3), removing the contradiction
  where plan Touch points attributed "continue at next un-run prompt" selection to
  the `:else` branch while steps placed it in `execute-session-step!`. The in-run
  drain (Slice 3) therefore needs no next-un-run-selection logic in
  `statechart_runtime.clj`; Slice 5 confirms restart/replay re-entry through this
  branch reconstructs queue position from persisted progression. No new Slice 3
  steps item is required (selection is already covered by the existing Slice 3
  `execute-session-step!` driver item), satisfying `λone_way`. Edit: plan.md Touch
  points `statechart_runtime.clj` bullet.
- **PI8 (done).** Added a `## Final verification` section to plan.md mirroring
  steps.md's section: the full three-component Scry run (workflow-runtime +
  workflow-loader + workflow-step-materialization) green + the AC-1..AC-8 covering
  TraceID coherence check. This resolves the dangling "Final verification"
  references (plan.md Slice-1 done-gate scope contrast) to a defined plan element.
  Edit: new plan.md `## Final verification` section after the docs-cadence note.

## Plan/steps ambiguity review (pass 3) — ψ

Third plan-level ambiguity pass over plan.md + steps.md (statements admitting >1
interpretation, undefined terms, unspecified edge behaviour), distinct from
ambiguity passes 1–2 (P1–P8, all resolved), inconsistency passes 1–2 (PI1–PI8,
resolved), and the design-review passes. Three **new** actionable ambiguities
found (P9–P11); recorded as unchecked items in steps.md. Verified-clear: the
P1–P8 disambiguations hold (normalization owner, docs cadence, equivalence
baseline, Slice-3/5 boundary, final-turn detection, progression probe observable,
Slice-1 gate scope, non-re-fire observable); per-prompt-record keyed-by-`:name`
named-only is unambiguous; the `:prompt`-validation invalid-case enumeration
(Slice 4) is exhaustive and clear; structured-`:outputs` final-turn gating (P5)
is well-defined.

- **P9 — "the relevant Scry suite per slice" is undefined for Slices 2–6.** Both
  the steps.md header (steps.md:5) and the plan.md change_chain note (plan.md:277)
  instruct "run the relevant Scry suite(s) per slice", but only Slice 1's gating
  suite is scoped (P7: workflow-runtime `step_execution_test.clj`). For Slices 2–6
  the "relevant Scry suite" is named nowhere, and the work spans **two
  components**: Slice 2 edits both workflow-loader `compiler.clj` *and*
  workflow-runtime `ir.clj` (and Slices 3–6 touch workflow-step-materialization +
  progression-recording too), so a reader cannot tell whether "the relevant Scry
  suite" for a given slice means the workflow-runtime suite, the workflow-loader
  suite, the workflow-step-materialization suite, or some per-slice subset. This
  leaves each non-Slice-1 done-gate's pass/green comparand ambiguous. (P7 fixed
  only Slice 1 and explicitly contrasts it with "each slice's relevant per-slice
  suite" without ever naming those per-slice suites.) Resolve by stating, per
  slice (or as a per-component rule), which component Scry suite(s) must be green
  to gate Slices 2–6, distinct from Slice 1's focused gate and Final
  verification's three-suite run.

- **P10 — final-/last-turn error abort semantics vs "intermediate-turn error"
  (Slice 6) is unspecified.** Slice 6 / AC-5 specify only the **intermediate**
  turn error path ("Intermediate-turn error ⇒ stop queue, step `:failed` naming
  the failing prompt, routing skipped"). The behaviour when the **final** prompt's
  turn errors in an N>1 queue is undefined: "intermediate" excludes the last
  prompt, so it is ambiguous whether a last-turn error follows the same
  `:failed`-naming-the-prompt abort (routing skipped, prior records retained) or
  falls through to the pre-existing single-prompt failure route (which AC-2 says
  N=1 preserves "exactly as today"). For N=1 the only turn is also the final turn,
  compounding the ambiguity (is the single-prompt failure path the "intermediate"
  abort path or the legacy path?). Resolve by stating in Slice 6 (and reconciling
  with AC-5/AC-2) whether a final/last-turn error produces the same `:failed`
  abort outcome as an intermediate-turn error, and whether the N=1 single-turn
  failure follows that unified abort path or the legacy single-prompt route.

- **P11 — "shared sources" is an undefined term in Slice 3.** steps.md Slice 3
  ("first group loads shared sources; later groups rely on live-session memory,
  E1") and plan.md ("the first group loads shared sources on turn 1") use "shared
  sources" without definition, while the design explicitly forbids any step-level
  shared `:contributions`/preamble/`:preload`. A reader cannot tell whether
  "shared sources" denotes (a) the first group's own materialized body/system
  content (skills/tools/system prompt) loaded at session start, which later groups
  see via conversation memory, or (b) some step-level shared material the grammar
  prohibits. Resolve by defining "shared sources" precisely in Slice 3 (e.g. "the
  session's first-turn materialized conversation — first group body plus
  session-level system/skill/tool content — which persists in the live child
  session for later groups"), so the no-step-level-preamble rule and the
  "shared sources" wording do not appear to contradict.

## Plan/steps ambiguity follow-up execution (pass 3) — ψ

Executed P9–P11 by updating plan.md + steps.md (all completed; none blocked). No
code/test/doc changes were required — these are plan-disambiguation follow-ups.

- **P9 (done).** Defined per-slice Scry gating as a **per-component rule**: each
  slice's gate is the Scry suite of **every component it edits**. Slice 1 = the
  focused `step_execution_test.clj` namespace only (P7); Slice 2 = workflow-loader
  + workflow-runtime (edits `compiler.clj` + `ir.clj`); Slice 3 = workflow-runtime
  + workflow-step-materialization (edits `step_execution`/`statechart_runtime`/
  `progression_recording`/`statechart` + per-group `core.clj` materialization);
  Slices 4–6 = workflow-runtime. Stated this is distinct from Slice 1's focused
  namespace gate and Final verification's full three-component run, with a "grows
  to touch a new component → add its suite" rule. Added a "Per-slice Scry gating
  (P9)" note to plan.md (after the docs-cadence note) and a header note to
  steps.md.
- **P10 (done).** Specified final/last-turn + N=1 error abort, reconciled with
  AC-5/AC-2: a turn error at **any** position (incl. the last prompt of an N>1
  queue) follows the **same** `:failed` abort as the intermediate case — the drain
  never completes, so the post-drain route is not reached (routing skipped), prior
  records retained, failing prompt leaves no record. AC-5's "intermediate" = "any
  non-completing turn", not "strictly not-last". The N=1 degenerate takes the same
  unified `:failed` abort with no prompt name in the payload, byte-equivalent to
  today's single-prompt failure route (preserves AC-2 N=1 equivalence; no separate
  single-prompt failure path). Added a Slice 6 steps item and extended plan.md
  Slice 6.
- **P11 (done).** Defined "shared sources" (design E1): the **first turn's
  materialized conversation** — first prompt-group body materialized **with** the
  session-level system/skill/tool content — submitted on turn 1 and persisting in
  the live child session, seen by later groups via conversation memory. **Not** a
  step-level shared `:contributions`/preamble/`:preload` (forbidden by the
  step-level xor; no `:preload` field), so "first group loads shared sources" and
  the no-step-level-preamble rule do not contradict. Added the definition to
  steps.md Slice 3 preamble and the plan.md "Turn boundary unchanged" key
  decision.

## Plan/steps inconsistency review (pass 3) — ψ

Re-reviewed plan.md + steps.md for internal inconsistencies and contradictions
against each other and the design.md decisions / code touch points. Distinct from
inconsistency passes 1–2 (PI1–PI8, all resolved) and ambiguity passes 1–3
(P1–P11, resolved). Two **new** actionable inconsistencies found (PI9, PI10);
recorded as unchecked items in steps.md. Verified-consistent: PI1–PI8
reconciliations hold; per-slice Scry gating wording matches between plan P9 note
and steps header for Slices 2 (workflow-loader + workflow-runtime) and 4–6
(workflow-runtime); docs-cadence per-slice ownership matches; abort outcomes
(Slice 6) match AC-5/AC-6; AC-1..AC-8 coverage list matches between Final-
verification sections; P9 final-turn/no-counter and P11 shared-sources definitions
agree across files.

- **PI9 — Slice 1's per-component Scry gate (P9) omits two of the three
  components Slice 1 actually edits, and plan P9 mis-attributes the
  `core.clj` per-group materialization edit to Slice 3.** steps.md Slice 1 edits
  **three** components: workflow-runtime (`ir.clj` queue schema + `step_execution.clj`
  refactor), workflow-loader (`compiler.clj` single-prompt → unnamed-group
  normalization, steps.md:29), and workflow-step-materialization (`core.clj`
  per-group materialization entry point, steps.md:34). But the P9 per-component
  rule — "each slice's gate is the Scry suite of **every component it edits**"
  (plan.md:295) — scopes Slice 1's gate to the workflow-runtime
  `step_execution_test.clj` namespace **only** (plan.md:302, steps.md:10). So
  Slice 1's `compiler.clj` (workflow-loader) and `core.clj`
  (workflow-step-materialization) edits are not gated by their own suites,
  contradicting the per-component rationale ("the slice's commit must be green
  across all of them"): a Slice-1 break in either suite would escape the focused
  gate. Compounding this, plan P9 lists "the per-group materialization in
  `core.clj`" among **Slice 3's** edits (plan.md:309) and gates
  workflow-step-materialization for **Slice 3**, even though steps.md adds that
  `core.clj` entry point in **Slice 1** and steps Slice 3 has **no** `core.clj`
  item — so the workflow-step-materialization suite is gated at the wrong slice
  (Slice 3, which does not edit `core.clj`) and ungated at the slice that does
  (Slice 1). Reconcile by either (a) attributing the `core.clj` edit +
  workflow-step-materialization gating (and the workflow-loader gating for the
  `compiler.clj` edit) to **Slice 1** in plan P9 + steps header, and removing the
  `core.clj` attribution from Slice 3; or (b) explicitly justifying why Slice 1's
  `compiler.clj`/`core.clj` edits are gate-exempt despite the per-component rule,
  and moving the `core.clj` materialization edit to the slice P9 claims owns it.

- **PI10 — plan P9 "edits" enumerations list confirm-only / no-edit touch points
  as edits.** plan P9 Slice 3 says it "edits
  `step_execution.clj`/`statechart_runtime.clj`/`progression_recording.clj`/`statechart.clj`"
  (plan.md:306–309), but the plan Touch points scope `statechart_runtime.clj` for
  Slice 3 to "The in-run drain (Slice 3) needs **no** next-un-run-selection logic
  here; Slice 5 confirms …" (no Slice-3 edit, per the PI7 resolution) and
  `statechart.clj` to "**confirm** the single … topology still holds … no
  per-prompt statechart states" (a verification, not an edit), and steps Slice 3
  has **no** `statechart_runtime.clj` item and only a *confirm* item for
  `statechart.clj`. Listing both as Slice-3 "edits" contradicts the touch points
  and steps Slice 3. (Gating consequence is nil — both live in workflow-runtime,
  already gated — but the edited-files attribution is internally inconsistent and
  could mislead the per-component rule's application.) Reconcile by rewording the
  Slice-3 P9 enumeration to separate edited files (`step_execution.clj`,
  `progression_recording.clj`) from confirm-only touch points
  (`statechart.clj`, and `statechart_runtime.clj` as the re-entry boundary), so
  "edits" names only files the slice changes.

## Plan/steps inconsistency follow-ups (pass 3) executed — ψ

Executed PI9–PI10 (plan/steps-only edits; no code/test/doc/source change needed —
these are task-artifact consistency reconciliations of the P9 Scry-gating
enumeration against the files each slice actually edits).

- **PI9 (done).** Chose reviewer **option (a)**: attributed the `compiler.clj`
  (workflow-loader) and `core.clj` (workflow-step-materialization) edits **and
  their suite gating** to **Slice 1** in plan P9 + steps header, and **removed**
  the `core.clj`/workflow-step-materialization attribution from **Slice 3** (steps
  adds the per-group `core.clj` entry point in Slice 1; Slice 3 has no `core.clj`
  item). Slice 1's gate is now the **three edited components' Scry suites**
  (workflow-runtime + workflow-loader + workflow-step-materialization), with the
  focused `step_execution_test.clj` namespace kept as the specific
  **N=1-equivalence done-gate comparand** (P7) within the workflow-runtime suite
  — **not** a narrower substitute for the per-component green requirement. Rejected
  option (b) (gate-exempt Slice 1 + relocate `core.clj`): it would either leave
  Slice 1's loader/materialization edits ungated by their own suites or move an
  edit steps already places in Slice 1. Reconciled the dependent P7 wording in
  three places that previously said the Slice-1 gate was the focused namespace
  "only / not the three-suite run": plan Slice-order 1 acceptance, plan Final-
  verification section, and steps Slice-1 done-gate — restating the distinction
  from Final verification as the latter's **AC-1..AC-8 coverage confirmation**,
  not a different suite set. Edits: plan.md P9 intro + Slice 1 bullet,
  Slice-order 1 acceptance (P7 scope), Final-verification section; steps.md P9
  header note + Slice-1 done-gate item.
- **PI10 (done).** Reworded the plan P9 **Slice 3** "edits" enumeration to list
  only genuinely-edited files — `step_execution.clj` and
  `progression_recording.clj` — and to mark `statechart.clj` as **confirm-only**
  (single-step-topology verification, no edit) and `statechart_runtime.clj` as
  the suspend/resume **re-entry boundary** needing no Slice-3 edit (Touch
  points / PI7). Removed `statechart_runtime.clj`/`statechart.clj`/`core.clj`
  from the Slice-3 "edits" list so "edits" names only files the slice changes.
  Gating consequence is nil (both confirm-only files live in workflow-runtime,
  already gated), but the attribution is now internally consistent with the Touch
  points and steps Slice 3. Edit: plan.md P9 Slice 3 bullet (shared with PI9).

## Plan/steps ambiguity review (pass 4) — ψ

Fourth plan-level ambiguity pass over plan.md + steps.md (statements admitting >1
interpretation, undefined terms, unspecified execution edge behaviour), distinct
from ambiguity passes 1–3 (P1–P11, all resolved), inconsistency passes 1–3
(PI1–PI10, resolved), and the design-review passes. **One** new actionable
ambiguity found (P12); recorded as an unchecked item in steps.md.
Verified-clear (re-checked, no new ambiguity): the P9 per-component Scry gating,
P10 final-/last-turn + N=1 error abort, P11 "shared sources" definition, P5
final-turn detection, P6 progression-state probe observable, P8 non-re-fire
observable, and the Slice-4 `:prompt`-validation invalid-case enumeration all
hold; "lowest-position un-run group" selection is well-defined; per-prompt
records keyed-by-`:name` named-only is unambiguous (pass-1/pass-3 judgment
stands).

- **P12 — in-flight (mid-turn) cancellation outcome + record disposition is
  unspecified, asymmetric with AC-5's explicit failing-prompt rule.** AC-5 (and
  the P10 follow-up) make the failure path explicit: a turn error at any position
  ⇒ `:failed`, and the failing prompt "leaves **no** completed turn record"
  (design.md:98). The cancellation path is specified only for cancellation
  **between** prompts (AC-6 / design.md:101–103: terminal `:cancelled`, routing
  skipped, **completed** per-prompt records retained), yet steps.md Slice 6 adds
  "**in-flight turn aborted** per existing cancellation contract (AC-6/B5)" —
  introducing the case where cancellation arrives **while a prompt's turn is in
  flight** (mid tool-loop), which design AC-6 ("between prompts") does not cover.
  Two things are left ambiguous for that mid-turn case: (a) whether a cancellation
  arriving mid-turn still produces the same terminal `:cancelled` outcome as an
  inter-prompt cancellation (vs some other disposition), and (b) whether the
  interrupted in-flight prompt leaves **no** record — symmetric with AC-5's
  failing prompt — or a partial record. AC-6/plan/steps assert only that
  *completed* records are retained and give no parallel "leaves no record"
  statement for the interrupted prompt (unlike AC-5). A reader cannot tell whether
  the in-flight-cancelled prompt is treated like AC-5's failing prompt (no record,
  identified only by the terminal outcome) or differently. Resolve by stating in
  Slice 6 (and reconciling with AC-6/AC-5) that a cancellation arriving mid-turn
  yields the same terminal `:cancelled` outcome as an inter-prompt cancellation,
  and that the interrupted in-flight prompt leaves **no** completed turn record
  (only prompts completed *before* cancellation are retained + introspectable) —
  i.e. make the cancellation path's in-flight-record disposition as explicit as
  AC-5 makes the failure path's.

## Plan/steps ambiguity follow-up execution (pass 4) — ψ

Executed P12 by updating plan.md + steps.md (completed; not blocked). No
code/test/doc changes required — a plan-disambiguation follow-up, consistent
with P1–P11. Design AC-5 (failure: failing prompt "leaves no record") and AC-6
(inter-prompt cancellation: terminal `:cancelled`, completed records retained)
already stand; P12 only resolves the previously-undefined **mid-turn**
cancellation case introduced by steps Slice 6's "in-flight turn aborted per
existing cancellation contract", reconciling it with AC-6/AC-5 in the plan/steps
without re-opening the design.

- **P12 (done).** Stated in plan.md Slice 6 and steps.md Slice 6 that a
  cancellation arriving **while a prompt's turn is in flight** yields the **same**
  terminal `:cancelled` outcome (routing skipped) as an inter-prompt
  cancellation — one cancellation outcome regardless of mid-turn vs between-turn
  timing — and that the interrupted in-flight prompt leaves **no** completed turn
  record (symmetric with AC-5's failing prompt "leaves no record"), so only
  prompts that completed **before** the cancel are retained + introspectable.
  Made the cancellation path's in-flight-record disposition as explicit as AC-5
  makes the failure path's. Edits: plan.md Slice-order Slice 6 (added the P12
  in-flight-cancellation clause); steps.md Slice 6 (expanded the inter-prompt
  cancellation context, added a dedicated in-flight-cancellation record-
  disposition item, and extended the Slice-6 runtime-tests item to cover the
  mid-turn cancellation case).

## Plan/steps inconsistency review (pass 4) — ψ

Re-reviewed plan.md + steps.md for internal inconsistencies / contradictions
against each other and the referenced design.md ACs / code touch points.
Distinct from inconsistency passes 1–3 (PI1–PI10, all resolved) and ambiguity
passes 1–4 (P1–P12, resolved). **One** new actionable inconsistency found
(PI11); recorded as an unchecked item in steps.md. Verified-consistent
(re-checked, no new inconsistency): PI1–PI10 reconciliations still hold; the P9
per-component Scry-gating wording matches between plan P9 note and steps header
for Slice 1 (three components), Slice 2 (workflow-loader + workflow-runtime),
Slice 3 (workflow-runtime; `core.clj` in Slice 1 per PI9/PI10), Slice 4 and
Slice 6 (workflow-runtime); plan P9 Slice 3 "edits" enumeration now lists only
`step_execution.clj` + `progression_recording.clj` with `statechart.clj` /
`statechart_runtime.clj` confirm-only (PI10 holds); docs-cadence per-slice
ownership matches (Slice 1 none / Slice 2 grammar / Slices 3–6 concepts /
Slice 7 consolidation); abort + cancellation outcomes (Slice 6) match
AC-5/AC-6 incl. P10/P12 reconciliations; the `:prompt`-validation invalid-case
enumeration (Slice 4) matches design's Source-ref section; Final-verification
AC-1..AC-8 coverage list matches design's eight ACs across plan + steps.

- **PI11 — plan P9 Slice 5 lists confirm-only / read-only files as the slice's
  gating work-files, inconsistent with steps Slice 5 and with PI10's same-file
  resolution for Slice 3.** plan.md P9 ("Per-slice Scry gating") Slice 5 reads
  "**Slice 5** — **workflow-runtime** Scry suite (resume/replay re-entry in
  `statechart_runtime.clj` + `progression_recording.clj`)", framing both files
  as where Slice 5 does its work — parallel to the "(edits …)" parentheticals
  for Slices 2/4/6. But (a) steps.md Slice 5 has **no** production-edit item:
  every item is "**Confirm** the `statechart_runtime.clj` … resume path
  reconstructs …", a runtime test, "**Verify** replay path …", docs, and the
  commit — and its preamble states "the suspend/resume boundary inside the
  single statechart step is **unchanged from Slice 3**", i.e. confirm + read +
  test, no edit; (b) plan Touch points scope `statechart_runtime.clj` as "the
  suspend/resume **re-entry boundary** … needing **no** Slice-3 edit … Slice 5
  confirms process-restart/replay re-entry through this branch reconstructs queue
  position **purely from persisted progression**" — confirm-only; and the
  per-prompt recording added to `progression_recording.clj` lands in **Slice 3**
  (read-only in Slice 5). (c) PI10 already reworded the **Slice 3** P9
  enumeration to mark this same `statechart_runtime.clj` as the confirm-only /
  no-edit re-entry boundary, yet the Slice 5 enumeration still lists it (and the
  Slice-3-owned `progression_recording.clj`) as plain work-files — so the same
  file is "confirm-only, no edit" in plan P9 Slice 3 but a plain Slice-5
  work-file in plan P9 Slice 5, while steps Slice 5 treats both as
  confirm/read-only. Gating consequence is nil (both live in workflow-runtime,
  already the Slice-5 gate), but the edited-files attribution is internally
  inconsistent — the same defect PI10 fixed for Slice 3, left unfixed for Slice
  5. Reconcile by rewording plan P9 Slice 5 to separate any genuinely-edited
  Slice-5 file from confirm-only/read re-entry files (mark `statechart_runtime.clj`
  as the confirm-only re-entry boundary and `progression_recording.clj` as
  read-only — per-prompt recording added in Slice 3), consistent with steps
  Slice 5 (confirm/verify/test only, boundary unchanged from Slice 3) and PI10's
  Slice-3 treatment; if Slice 5 genuinely edits no production code, state that
  its gate is the workflow-runtime suite for the added resume/replay tests.

## Plan/steps inconsistency follow-up execution (pass 4) — ψ

Executed PI11 by updating plan.md (completed; not blocked). No code/test/doc
changes required — a plan-attribution consistency follow-up, consistent with
PI10's same-file resolution for Slice 3. Gating consequence is nil (Slice 5
already gates on the workflow-runtime suite); PI11 only fixes the internally
inconsistent edited-files attribution.

- **PI11 (done).** Reworded plan.md P9 ("Per-slice Scry gating") **Slice 5** so
  it no longer frames `statechart_runtime.clj` + `progression_recording.clj` as
  the slice's gating work-files (parallel to the "(edits …)" parentheticals of
  Slices 2/4/6). It now states Slice 5 **edits no production code**:
  `statechart_runtime.clj` is the **confirm-only** suspend/resume re-entry
  boundary (unchanged from Slice 3, Touch points / PI7/PI10) and
  `progression_recording.clj` is **read-only** (its per-prompt recording lands in
  Slice 3); the Slice-5 gate is therefore the workflow-runtime Scry suite for the
  added resume/replay tests (runtime test + replay-path verification), not any
  production-file edit. This matches steps Slice 5 (all confirm/verify/test, no
  production-edit item; "boundary unchanged from Slice 3") and PI10's Slice-3
  treatment of the same `statechart_runtime.clj`. Edit: plan.md P9 Slice 5 bullet.

## Plan/steps ambiguity review (pass 5) — ψ

Re-reviewed plan.md + steps.md for ambiguities against design.md ACs and the
current `execute-session-step!` code. Distinct from ambiguity passes 1–4
(P1–P12, resolved) and inconsistency passes 1–4 (PI1–PI11, resolved). **One**
new actionable ambiguity found (P13); recorded as an unchecked item in steps.md
(new "ambiguity, pass 5" section). Verified-clear (re-checked, no new
ambiguity): P11's "shared sources" reconciles with the persistent child-session
+ per-group `materialize-step-session-conversation` (system/skill/tool content
is established at session creation on turn 1, not re-embedded by later groups);
per-prompt `:transcript`/`:final-llm-reply` turn-local surfaces map onto the
existing per-turn `{:transcript [assistant-message] :final-llm-reply
assistant-text}` shape (turn-local already; step-level accumulation is the new
part); P5 final-turn detection (static IR position) and P8 non-re-fire observable
remain unambiguous.

- **P13 — the structured-output `:blocked` outcome is unspecified across the
  multi-prompt drain (plan Slice 3/Slice 6, AC-3/AC-5).** `execute-session-step!`
  has a **third** non-success step outcome besides `:error`/`:failed` and the new
  `:cancelled`: `:actor/blocked` (`record-actor-pending! … :blocked …`), raised
  in three current cases — (i) `(false? (:ok? request-result))` (the structured-
  output *request* is itself invalid, a static check done **before** the turn
  today), (ii) `:unsupported-structured-output` (the resolved model cannot do
  structured output), and (iii) `:invalid-structured-output` (the final reply
  fails structured-output validation). Slice 3 says "request structured
  `:outputs` on the **final** turn only" (P5) and Slice 6 "Abort paths" enumerates
  only `:failed` (turn error, any position incl. last, P10) and `:cancelled`
  (inter-/in-flight, P12) — **`:blocked` is named nowhere** in design/plan/steps.
  Two sub-questions are left genuinely two-way for an implementer:
  - **(a) When is structured-output viability checked in the drain?** Today the
    upfront `(:ok? request-result)` static check and the structured request gate
    run **before** the single turn. With "structured on the final turn only",
    does the static request-validity / blocked check (case i) still run **upfront
    before turn 1** (fail-fast, no turns wasted), or is it deferred to the final
    turn so an invalid structured-output spec only blocks after N−1 turns already
    ran? The plan does not say.
  - **(b) What is the multi-prompt `:blocked` disposition?** When the **final**
    turn yields `:unsupported-structured-output` or `:invalid-structured-output`
    (cases ii/iii) after N−1 prior turns completed, what is the step outcome and
    record/routing disposition? By analogy with AC-5/AC-6 one would expect:
    terminal `:blocked` (distinct from `:failed`/`:cancelled`), routing skipped,
    prior completed per-prompt records retained + introspectable, the final
    prompt leaving no successful turn record — but none of this is stated, and
    Slice 6's "Abort paths" omits `:blocked` entirely, so a reader cannot tell
    whether `:blocked` retains prior records like `:failed`/`:cancelled` or
    discards them, nor whether it skips routing. Resolve by (a) stating where the
    upfront structured-request-validity check runs (recommended: before turn 1,
    fail-fast) and (b) adding the `:blocked` outcome to Slice 6's abort-path
    enumeration with explicit retained-records + routing-skipped disposition
    symmetric to AC-5/AC-6, and a covering test.

### P13 resolved — structured-output `:blocked` drain disposition

Executed the pass-5 ambiguity follow-up P13. Specified the `:blocked` outcome
across the multi-prompt drain in plan.md (new R6 risk + Slice 3 viability-gate
note + Slice 6 abort-path paragraph + Slice 6 doc-cadence line) and steps.md
(Slice 6 enumeration item + runtime-test item + docs item + Slice 7
consolidation verify-list). Resolution (grounded in `execute-session-step!`,
`step_execution.clj`):

- **(a) timing** — the static structured-output **request-validity** gate
  (`(false? (:ok? request-result))`, case i) runs **upfront before turn 1**:
  it depends only on the step's `:outputs` spec (static / IR-derivable,
  turn-independent), matching today's pre-turn cond branch; fail-fast with zero
  turns run and zero per-prompt records. The turn-dependent reasons (ii)
  `:unsupported-structured-output` and (iii) `:invalid-structured-output` can
  only arise on the **final** turn, since structured `:outputs` is requested on
  the final turn only (P5).
- **(b) disposition** — a final-turn block after N−1 turns ran ⇒ terminal
  `:blocked` (distinct from `:failed`/`:cancelled`), routing skipped (no
  successful post-drain result), prior N−1 completed per-prompt records retained
  + introspectable (symmetric AC-5/AC-6), blocking final prompt leaves no
  completed turn record (symmetric AC-5 failing-prompt / P12 interrupted-in-
  flight). N=1 degenerate ⇒ byte-equivalent to today's single-prompt blocked
  path (no name, zero records), preserving AC-2.

Plan/steps-only refinement (no code/test/doc edits — Slice 6 is unstarted; the
`:blocked` covering test is scheduled as a Slice 6 steps item). P13 ticked.

## Plan/steps inconsistency review (pass 5) — ψ

Re-reviewed plan.md + steps.md for internal inconsistencies against each other,
design.md, and the touch-point code. Distinct from inconsistency passes 1–4
(PI1–PI11, resolved) and ambiguity passes 1–5 (P1–P13, resolved). **One** new
actionable inconsistency found (PI12); recorded as an unchecked item in
steps.md. It was **introduced by the P13 (ambiguity pass 5) resolution**, which
post-dates the last inconsistency pass's explicit verification that "abort
outcomes (Slice 6) match AC-5/AC-6; AC-1..AC-8 coverage list matches between
Final-verification sections" (implementation.md pass-4 note) — that check is now
stale. Verified-consistent (re-checked, no new inconsistency): PI1–PI11 hold;
per-slice Scry gating wording matches between plan P9 and steps header; P10
final-turn error folds under AC-5's relabeled "any non-completing turn" and P12
in-flight cancellation folds under AC-6's `:cancelled` — both remain traceable;
docs-cadence per-slice ownership matches; P11 shared-sources and P5
final-turn/no-counter definitions agree across files.

- **PI12 — the P13 `:blocked` terminal outcome (Slice 6) has no design AC and no
  Final-verification AC-1..AC-8 coverage entry, so the TraceID/coherence gate
  cannot trace its scheduled covering test.** P13 added a **third** terminal
  non-success outcome — `:blocked` (`:actor/blocked`) — to plan R6 + Slice 6
  abort-path paragraph and to steps Slice 6 (enumeration item + a dedicated
  `:blocked` runtime-test item) + Slice 7 verify-list. But:
  - **design.md** still enumerates only **two** queue non-success outcomes:
    AC-5 (`:failed`) and AC-6 (`:cancelled`). There is **no** design AC for the
    `:blocked` terminal outcome (its routing-skipped / prior-records-retained /
    blocking-prompt-leaves-no-record disposition), even though plan/steps now
    treat it as a first-class terminal outcome with its own test.
  - the **Final verification** AC-1..AC-8 coverage list (plan.md:409,
    steps.md:276) still enumerates only the original seven test areas + docs
    ("ordering, drain, N=1 equivalence, per-prompt addressing + validation,
    intermediate-failure abort, inter-prompt cancellation,
    resume-from-progression idempotency, docs") — **no `:blocked` entry**. The
    Slice 6 `:blocked` covering test therefore traces to **no AC**, so the
    AC-1..AC-8 TraceID/coherence gate (plan Slice 7 / Final verification; steps
    Slice 7 / Final verification) would pass green **without** verifying the
    scheduled `:blocked` test exists.
  This is the same defect-shape PI9/PI10/PI11 fixed for other surfaces
  (a behaviour/test scheduled in the slices but unaccounted for in the
  coverage/attribution checks), left re-introduced by P13. Reconcile by **either**
  (a) adding a design AC for the `:blocked` outcome (or extending AC-5 to cover
  the third structured-output-block terminal disposition) **and** adding a
  `:blocked` entry to the Final-verification AC-1..AC-8 coverage enumeration in
  **both** plan.md and steps.md, **or** (b) if `:blocked` is intentionally a
  preservation-only behaviour with no new AC, stating explicitly in plan/steps
  that the Slice 6 `:blocked` test is traced under an existing AC (e.g. AC-3
  structured-output / AC-5 abort) and naming it in the Final-verification
  coverage parenthetical so the TraceID check accounts for it.

## Plan/steps inconsistency follow-up execution (pass 5) — ψ

Executed PI12 by updating plan.md + steps.md (completed; not blocked). No
code/test/doc changes were required — this is a plan/coverage-traceability
follow-up.

- **PI12 (done).** Chose **option (b)** — `:blocked` is preservation-only, no new
  AC — over option (a) (adding/extending a design AC). Two reasons: (1) design.md
  is read-only context for this follow-up pass, and (2) `:blocked` is genuinely
  preservation-only — it is an **existing** `execute-session-step!` non-success
  outcome (`:actor/blocked`) **preserved across the drain**, not a new behaviour
  this task introduces, so design.md's deliberate AC-5/AC-6 enumeration of only
  `:failed`/`:cancelled` stays accurate. Traced the Slice 6 `:blocked` covering
  test under **two existing ACs**: **AC-3** governs the structured-output
  viability that raises `:blocked` (declared step-level structured output applies
  to the final turn), and **AC-5** governs its drain disposition (routing skipped,
  prior N−1 completed per-prompt records retained + introspectable, blocking final
  prompt leaves no record — a final-turn block is an instance of AC-5's "any
  non-completing turn" per P10). Edits: (i) plan.md R6 — added an "AC home (PI12)
  — preservation-only, no new AC" paragraph stating `:blocked` traces to AC-3 +
  AC-5; (ii) plan.md Final-verification AC-1..AC-8 coverage bullet — named the
  `:blocked` terminal outcome in the intermediate-failure-abort parenthetical
  (preservation-only, traced under AC-3/AC-5); (iii) steps.md Final-verification
  confirm item — the matching parenthetical. Now the AC-1..AC-8 TraceID/coherence
  gate accounts for the scheduled Slice 6 `:blocked` test without inflating the AC
  set — closing the same defect-shape PI9/PI10/PI11 fixed for other surfaces.

## Slice 1 — unified single-prompt queue path (implementation begins) — ψ

First concrete implementation pass (all prior commits were design/plan review).
Established the Slice-1 equivalence baseline and the additive normalized
prompt-queue seams; the deeper `execute-session-step!` queue-driver refactor +
runtime rewiring remain.

- **Characterization baseline (P3) committed first.** Added
  `single-prompt-session-step-envelope-characterization-test` to
  `statechart_runtime/step_execution_test.clj` pinning the asserted SHAPE of the
  single-prompt `:pending-actor-result` envelope (text step:
  `:kind`/`:step-id`/`:attempt-id`/`:outcome` + `:final-llm-reply`/`:text`/
  `:transcript`/`:session-id`/`:logprobs` + `:actor/done`; structured step: the
  declared `:classification` key valid + `{:decision :pass}`). Committed green
  against pre-refactor code (16 assertions). This is the Slice-1 done-gate
  comparand; any change to the asserted shape is a defect (R4).

- **Normalized prompt-queue representation (ir.clj).** Added
  `prompt-group-schema` (`{:name? :contributions}`), `prompt-queue-schema`
  (`[:vector {:min 1} …]`), `valid-prompt-queue?`, and the derivation
  `session-step-prompt-queue` (canonical session IR step → ordered queue;
  `:contributions` → one UNNAMED group = N=1 degenerate; authored `:prompts` →
  named groups verbatim, forward-compat for Slice 2). Pure/additive; ir.clj owns
  the normalized-queue schema + derivation per the workflow-runtime boundary.

- **Per-group materialization entry point (workflow-step-materialization core.clj).**
  Extracted the shared single-turn primitive `materialize-contributions-conversation`
  (whole-step `materialize-step-session-conversation` now delegates to it,
  byte-identical) and added `materialize-prompt-group-conversation` (materialize
  one prompt-group's `:contributions`). The runtime composes
  `ir/session-step-prompt-queue` + `materialize-prompt-group-conversation` +
  `split-step-session-conversation`; the length-1 unnamed-group queue reproduces
  today's single-prompt conversation (proven by the new core test).

- **DEVIATION from plan P1 (normalization owner).** plan.md/steps.md/Touch points
  attribute the authored-form → normalized-queue transform to **workflow-loader
  `compiler.clj`**. In the actual code, workflow-loader `compiler.clj` only lowers
  `.md`/`.edn` files + `:prompt-workflow` markdown into target-authored
  `:contributions`; the transform that nests session config under `:session` and
  produces canonical normalized IR is **workflow-runtime `target_ir_compiler.clj`**
  (`compile-step` `:session` branch). So Slice 2's `:prompts` → named-group
  compilation belongs in `target_ir_compiler.clj` (+ `ir.clj` schema/validation),
  not `compiler.clj`. For the N=1 degenerate no compiler change is needed: the
  length-1 queue is derived from the existing canonical `:session :contributions`
  by `ir/session-step-prompt-queue`. (workflow-step-materialization cannot depend
  on workflow-runtime — circular — so the queue *derivation* stays in ir.clj and
  the runtime composes; core.clj owns only per-group conversation materialization.)

- **Verification.** New tests green: ir-test (6 tests / 112 assertions incl. the
  two new prompt-queue tests), materialization core-test (8 tests / 16 assertions
  incl. the new per-group test, via kaocha — note the bb `clojure:test:scry` task
  runs `-M:test-paths` which omits `components/workflow-step-materialization/test`,
  so that suite is run via `clojure -M:test --focus`), step-execution-test (12
  tests / 83 assertions, characterization green), target-ir-compiler-test (9/31),
  workflow-loader compiler-test + compiler-target-authoring-test (4/54). clj-kondo
  clean across all edits.

- **Remaining for Slice 1:** refactor `execute-session-step!` to drive a length-1
  internal queue (consuming `ir/session-step-prompt-queue` +
  `materialize-prompt-group-conversation`) producing the identical envelope, and
  wire the queue composition at the `statechart_runtime.clj` `:step/enter`
  materialization site — keeping the characterization test green unchanged. Then
  Slices 2–7 per plan.

## Slice 1 — unified single-prompt queue path wired at the materialization site (complete) — ψ

Completed the remaining Slice-1 work: the `:step/enter` session branch now drives
the unified internal prompt-queue for the N=1 degenerate, producing byte-identical
behaviour.

- **Queue wiring at `:step/enter` (statechart_runtime.clj).** Replaced the direct
  `((:materialize-workflow-step-session-conversation-fn ctx) workflow-run step-id)`
  call with the unified queue path: derive `prompt-queue` via
  `workflow-ir/session-step-prompt-queue step-def` (added `[psi.workflow-runtime.ir
  :as workflow-ir]` to requires), then materialize the single (length-1) group via
  the new injected `:materialize-workflow-prompt-group-conversation-fn`
  (`materialize-prompt-group-conversation`) over `(first prompt-queue)`, then split
  as before. Single-prompt `:contributions` now flows through the queue derivation
  rather than a direct contributions read.

- **ctx wiring.** Added `:materialize-workflow-prompt-group-conversation-fn`
  (→ `workflow-step-materialization/materialize-prompt-group-conversation`) to the
  production ctx (`agent-session/context.clj`) and the test ctx
  (`agent-session/test_support.clj`).

- **DEVIATION (queue-driver location), reconciling the plan's "refactor
  `execute-session-step!` to drive a length-1 internal queue".** The queue
  *driving* for the N=1 degenerate is realized at the **materialization site**
  (`:step/enter`), not inside `execute-session-step!`. Reason: materialization is
  upstream of `execute-session-step!` (the latter receives an already-split
  `prompt` and executes exactly one turn). For N=1 there is no loop to add inside
  `execute-session-step!` — the unified-path change is *which seam materializes the
  prompt* (the queue derivation + per-group entry point). The envelope-producing
  body of `execute-session-step!` is unchanged, so the characterization comparand
  (P3) stays green unchanged. The genuine N>1 in-run drive loop (advancing prompt
  _n_→_n+1_ across suspend/resume, consulting recorded progression) lands in
  Slice 3, where the deeper `execute-session-step!`/`:step/enter` restructure
  belongs.

- **Verification (per-component Slice-1 gate, P9/PI9).**
  - workflow-runtime: `step-execution-test` + `ir-test` 18 tests / 195 assertions
    green — the P3 characterization comparand unchanged.
  - workflow-runtime + workflow-loader full suites: 166/167 tests green; the **sole**
    failure is `workflow-loader.workflow-definitions-test/task-lifecycle-test`
    (16 assertions), confirmed **pre-existing and unrelated** by stashing the Slice-1
    changes and re-running (identical 16-assertion failure) — it is a stale
    `task-lifecycle.edn` step-structure assertion (expects 7 steps; the live
    definition has 9), not a task-226 regression.
  - agent-session end-to-end `:step/enter` exercise (real materialize fn in ctx):
    `workflow-session-integration-test` + `workflow-execution-test` +
    `workflow-statechart-runtime-test` + `workflow-execution-resume-test`
    29 tests / 142 assertions green — proves the queue wiring is behaviour-preserving
    through the full session-step path.
  - workflow-step-materialization `core-test` 8 tests / 16 assertions green.
  - clj-kondo clean across all edits.

- **Slice 1 done.** AC-2 N=1 equivalence holds as a consequence of the unified
  queue path. Next: Slice 2 — `:prompts` grammar + IR normalization (named groups)
  in `ir.clj` + `target_ir_compiler.clj` (per the Slice-1 deviation, the `:prompts`
  → named-group compilation belongs in `target_ir_compiler.clj`, not
  workflow-loader `compiler.clj`).

## Slice 2 — `:prompts` grammar + IR normalization (named groups) — ψ

Landed the authored `:prompts` multi-prompt session form end-to-end (schema +
validation + both compile stages + docs + tests). All three suites green
(`ir-test` 7/125, `target-ir-compiler-test` 10/37, `compiler-test` 4/56); the
sole `workflow-loader` suite failure is the pre-existing
`workflow-definitions-test/task-lifecycle-test` (16 fails, asserts a stale
7-step `task-lifecycle.edn` topology vs the live 9-step gated topology) —
confirmed identical with these changes stashed, unrelated to 226. clj-kondo
clean.

- **Schema (ir.clj).** `session-spec-schema` `:contributions` made optional and
  `:prompts` (= `prompt-queue-schema`, `[:vector {:min 1} prompt-group-schema]`)
  added; the step-level `:contributions` xor `:prompts` rule is enforced
  **semantically** (`session-prompt-queue-errors`), keeping the schema permissive
  so each authored form validates structurally. Empty `:prompts` is rejected
  **structurally** by `{:min 1}` (matches the "empty ⇒ error" requirement without
  a redundant semantic check).
- **Semantic validation (ir.clj).** New `session-prompt-queue-errors` wired into
  `semantic-errors`: `:session-contributions-and-prompts` (both present),
  `:session-without-prompt-source` (neither), `:unnamed-prompt-group` (a
  `:prompts` group missing `:name`), `:duplicate-prompt-group-name` (name
  collision within one step). `step-source-refs` `:session` branch now also
  collects refs from `:prompts` group contributions so prior-step refs inside
  groups are validated (the `:prompt` discriminator + per-prompt surfaces remain
  Slice 4).
- **Authored → canonical IR (target_ir_compiler.clj).** `compile-step` `:session`
  branch emits `:session :prompts` (via new `compile-prompt-group`) when the
  authored step carries `:prompts`, else the existing `:contributions` path. A
  `:prompts` step carries **no** step-level `:contributions` in canonical IR;
  `ir/session-step-prompt-queue` already returns the named groups verbatim.
- **Group `:prompt-workflow` resolution + xor (workflow-loader compiler.clj).**
  **Deviation note (extends the Slice-1 P1 deviation):** P1/plan assign the
  authored-form → normalized-queue transform to workflow-loader `compiler.clj`,
  but Slice 1 recorded that the config-nesting transform actually lives in
  workflow-runtime `target_ir_compiler.clj`. Slice 2 follows that same split:
  workflow-loader `compiler.clj` owns only the **file-resolution** half it
  uniquely can do — `compile-prompts-step`/`compile-prompt-group` resolve each
  group's relative `:prompt-workflow` `.md` into group `:contributions` (reusing
  `read-prompt-workflow`/`markdown-body->contribution`) and enforce the
  group-internal `:prompt-workflow` xor `:contributions` rule + the step-level
  `:prompts`-excludes-step-level-prompt-source rule (these involve
  `:prompt-workflow`, which only exists pre-resolution at the loader). The
  canonical IR emission of `:session :prompts` stays in
  `target_ir_compiler.clj`, consistent with where Slice 1 placed the session
  config-nesting transform.
- **Per-prompt session config out of scope.** A group's `:prompt-workflow`
  markdown contributes only its **body**; the markdown's own frontmatter session
  config is ignored for groups (session config is per-step/shared per design
  scope), unlike the step-level `:prompt-workflow` path which merges markdown
  session config.

## Slice 3 (in progress) — per-prompt progression substrate — ψ

Landed the foundational, progression-driven substrate that the in-run N-turn
drain consumes (Slice 3 item 2 + the next-un-run selector item 1 depends on),
ahead of wiring the driver loop. Pure, unit-tested, zero integration risk to the
existing synchronous single-turn path.

- **`progression_recording.clj` additions.**
  - `record-prompt-group-turn` — appends one completed per-prompt turn record
    `{:index i :name group-name :outputs {...} :recorded-at …}` to the latest
    attempt's `:prompt-group-turns`. **Idempotent on `:index`**: a turn whose
    index already has a record is not re-appended and the original is not
    clobbered — this is the recording side of the F1 resume non-re-fire invariant.
    Named groups only; the unnamed N=1 degenerate records no per-prompt record
    (design C3) — the driver simply won't call this for the unnamed group.
  - `prompt-group-turn-records` / `recorded-prompt-group-indices` — the
    **progression-state probe** observable (P6): read which prompts already have a
    recorded turn under the step's attempt.
  - `next-un-run-prompt-group` — the **progression-driven** next-un-run selector
    (P5/F1): given the ordered prompt-queue and recorded indices, returns the
    lowest static-position group with **no** recorded turn as
    `{:index i :group g :final? (= i last)}`, or nil when drained. `:final?` is the
    static IR-position property (P5 final-turn detection) the driver uses to gate
    structured `:outputs` to the final turn — orthogonal to the no-counter
    selection (selection reads recorded indices; finality compares position to
    queue length).

- **DEVIATION (read path).** `latest-attempt` in this ns returns the **attempts
  vector** (a pre-existing `some->>`-thread quirk: the index becomes a get-in
  default, so the path-present read yields the whole vector; existing
  `(:attempt-id (latest-attempt …))` call sites silently get nil). Rather than
  change that shared fn (terminal-outcome/history attempt-id metadata would shift
  from nil to real ids — out of task scope), added a private `latest-attempt-map`
  (index + get-in) for the new readers.

- **Verification.** `progression-recording-test` 9 tests / 46 assertions green
  (added `prompt-group-turn-record-substrate-test` covering empty/append/order/
  idempotency, and `next-un-run-prompt-group-test` covering first/lowest-un-run/
  drained-nil/length-1-final). Focused workflow-runtime suites
  (progression-recording + ir + step-execution + target-ir-compiler) 38/291 green;
  clj-kondo clean.

- **Remaining for Slice 3:** the in-run drain **driver** (item 1) — restructure
  the `:step/enter` session branch / `execute-session-step!` to loop N turns
  against the same child session, calling `next-un-run-prompt-group` each
  re-entry, materializing each group via
  `materialize-prompt-group-conversation` + split, recording each named turn via
  `record-prompt-group-turn`, requesting structured `:outputs` only when
  `:final?`, and emitting **one** post-drain `:pending-actor-result` (step-level
  rollup + per-prompt records). Open design question to resolve there: injecting a
  later group's split `:preloaded-messages` into the **live** child session (the
  turn primitive `execute-actor-turn!` submits only a prompt string; first group
  preloads at session creation, later groups currently only submit a prompt).
  Then per-prompt surfaces (Slice 4), resume/replay (Slice 5), abort paths
  (Slice 6), docs (Slice 7).

## Slice 3 (driver) — in-run N-turn drain — ψ

Landed the in-run drain driver `drive-session-prompt-queue!`, completing Slice 3.

- **Per-turn primitive extracted.** Pulled the turn-execution + outcome
  classification out of `execute-session-step!` into a private
  `execute-session-turn-outcome` returning a disposition map
  (`:cancelled`/`:failed`/`:blocked`/`:ok` with `:branch :error|:success`).
  `execute-session-step!` (the unnamed N=1 degenerate) now calls it and maps the
  disposition to the same `record-actor-pending!`/cancel control flow as before —
  the `:branch` flag preserves the original two-place stopped? recheck so the N=1
  envelope is byte-identical (step-execution-test 12/83 unchanged; agent-session
  workflow-execution/statechart/resume 27/131 green).
- **Driver.** `drive-session-prompt-queue!` loops the shared primitive: reads
  `next-un-run-prompt-group` from live `state*` each iteration (progression-driven
  selection, no in-memory counter), runs group 0 with the pre-split `:step/enter`
  prompt and later groups with a materialize+split closure against the live
  session, records each named turn through `update-state-if-live!` +
  `record-prompt-group-turn`, requests structured `:outputs` only on the
  `:final?` group, and on drain emits one post-drain `:pending-actor-result`
  (`post-drain-envelope`: final-turn rollup + accumulated `:transcript` + ordered
  `:prompt-group-outputs`). The upfront structured request-validity gate (P13a)
  runs before turn 1.
- **Wiring.** `:step/enter` `:else` branch routes `(some :name prompt-queue)` →
  driver; else → `execute-session-step!` (single turn). Selection ownership stays
  in the driver (PI7); the `:else` branch is just the re-entry/dispatch site.
- **Non-final structured surfaces fix.** `step-output-surfaces` over a step that
  declares structured `:outputs` threw on non-final turns (the structured key is
  not produced until the final turn). Fixed by resolving surfaces against a
  `surface-step-def` that dissocs the structured key when it is not being bound
  this turn.
- **Tests.** Four new `drive-session-prompt-queue!` tests (step-execution-test
  16/107 green): author-order N-turn drain + introspectable records + one
  post-drain event; progression-driven resume skipping a pre-recorded prompt
  (zero re-fire); structured output on the final turn only; upfront
  structured-request block with zero turns/records.
- **Deviations.** See steps.md "Slice 3 deviations": separate driver fn sharing
  one turn primitive; synchronous in-run loop (execute-actor-turn! is synchronous)
  consulting progression each iteration (Slice 5 adds restart/replay re-entry);
  later-group multi-message preloaded-messages not re-injected (submit split
  prompt only); abort dispositions wired but full Slice-6 semantics deferred.

## Slice 4 — Per-prompt output surfaces + `:prompt` source-ref + validation — ψ

Landed the `:prompt` discriminator end-to-end: schema, uniform resolution,
fail-fast IR validation with the post-drain judge carve-out, tests, docs. All
workflow-runtime + workflow-step-materialization Scry suites green (149 tests /
735 assertions); clj-kondo clean.

- **Schema (`ir.clj`).** Added optional `:prompt` (a group name, `:string`) to
  `step-output-ref-schema`. It is `:output`-only by construction: `:prompt` is
  not on `step-yield-ref-schema`, and `step-output-ref-schema` requires
  `:output`, so a `:prompt`+`:yield` ref is **structurally** impossible
  (`unreachable > forbidden`) — no dedicated semantic error.
- **Validation (`ir.clj`).** New `prompt-ref-errors` rejects a `:prompt` ref to a
  non-session step (`:prompt-ref-non-session-step`), a single-prompt step with no
  named groups (`:prompt-ref-single-prompt-step`), an unknown group
  (`:prompt-ref-unknown-group`), a non-text key (`:prompt-ref-non-text-surface`,
  text surfaces = `#{:final-llm-reply :transcript}`), and a same-step sibling-group
  ref (`:prompt-ref-same-step`) — except the post-drain judge. To make the
  carve-out **precise**, `step-source-refs` was split into `step-body-source-refs`
  (validated with the assembly-time same-step prohibition, `judge? false`) and
  `step-judge-source-refs` (`judge? true`, same-step `:prompt` permitted);
  `ref-errors` gained the `judge?` param and dispatches `:prompt` refs to
  `prompt-ref-errors`. `format-semantic-error` gained the five new cases.
- **Resolution (`workflow-step-materialization/source_resolution.clj`).**
  `resolve-source-ref` gained one `:prompt` clause (ordered **before** the
  step-level `:output` clause, since both carry `:output`): it looks up the named
  group in `accepted-result[:outputs][:prompt-group-outputs]` (the Slice-3
  post-drain envelope shape) and resolves the text key via
  `step-output-value nil {:outputs turn-local} k`. All shared call sites (invoke
  args, contributions, template vars, delegated context) inherit it through this
  one substrate fn — no per-call-site code.
- **Surfaces unchanged.** `step-output-surfaces`/`step-output-value` needed **no**
  per-prompt change: step-level surfaces are already produced by Slice 3's
  `post-drain-envelope`, and per-prompt resolution reuses `step-output-value` over
  the turn-local outputs map.
- **Tests.** `ir-test/prompt-source-ref-validation-test` (valid prior-group ref,
  back-compat no-`:prompt`, unknown group, non-text key, single-prompt,
  non-session, same-step-in-contribution rejected, judge carve-out accepted);
  `source-resolution-test/resolve-prompt-discriminated-per-prompt-surface-test`
  (no-`:prompt` → step-level last/accumulated; `:prompt` → that group's turn-local
  surface).

## Slice 5 — Resume-from-progression across process-restart/replay — ψ

Confirm + test slice; **no production code** (matches plan PI11). Proves the
queue driver reconstructs position purely from persisted progression and never
re-fires a recorded turn.

- **Confirm (code inspection).** `drive-session-prompt-queue!` reads
  `next-un-run-prompt-group` from `(get-in @(:state* ctx) (run-path run-id))`
  **each loop iteration** — selection is driven by the persisted canonical atom,
  not an in-memory counter. The `:step/enter` `:else` branch only re-invokes the
  driver (PI7); next-un-run ownership stays in the driver. So a fresh
  post-restart process consults only persisted per-prompt progression.

- **Architecture finding (synchronous drain).** Because `execute-actor-turn!` is
  **synchronous** here (the Slice-3 R1 fallback), the whole N-turn drain runs
  inside one `:step/enter` action; the statechart does **not** suspend mid-drain.
  `:workflow/resume` transitions only from `:blocked` → `:running` (a new
  attempt), so there is no statechart path that re-enters a half-drained step.
  The realized resume/idempotency mechanism is therefore the driver's
  per-iteration progression re-read (already exercised in-run by Slice 3), and
  event-log replay reconstructs state via the dispatch layer's effect-suppressing
  replay rather than by re-running turns. Slice 5's tests exercise that mechanism
  against a freshly reconstructed `state*`/`ctx` (no in-memory loop state carried
  across the "restart").

- **Known caveat (non-occurring path).** `post-drain-envelope` builds
  `:prompt-group-outputs`/`:transcript`/`:final-llm-reply` from the **current
  invocation's** loop accumulator, not from persisted records. After a *partial*
  resume the envelope would reflect only the resuming invocation's turns. This is
  unreachable under the current synchronous architecture (the drain is atomic
  within one `:step/enter`; no statechart mid-drain restart exists), so no
  speculative production code was added (`λone_way` / simplicity). If a future
  slice makes turns async-suspending (true F1 N-suspend-points), the envelope
  would need to reconstruct from persisted `prompt-group-turn-records` — recorded
  here as a deferred follow-up tied to that (currently absent) async path.

- **Tests (P4/P8/AC-7).** Added to
  `step_execution_drive_prompt_queue_test.clj`:
  - `drive-session-prompt-queue-reconstructs-position-from-persisted-progression-test`
    — fresh state*/ctx with indices 0+1 recorded; only index 2 fires (1 turn-call
    = 1 `ai/generate`; zero for the recorded prompts); prior records retained
    verbatim (no duplicate append); `:actor/done` reached.
  - `drive-session-prompt-queue-replay-fully-recorded-fires-zero-turns-test`
    — fully-recorded reconstructed state*; **0** turn-calls (zero re-fire), no
    progression mutation, drains straight to `:actor/done`.
  P8 observable = the `:workflow-execute-actor-turn-fn` seam call-count.

- **Docs.** Added the "Resume and idempotency" subsection to
  `doc/workflow-grammar.md`.

- **Verification.** drive-prompt-queue suite 6 tests / 33 assertions green;
  clj-kondo clean.

## Slice 6 — Abort paths — ψ

Test + docs slice. **Deviation:** the abort dispositions were **already wired**
in the Slice-3 driver (`drive-session-prompt-queue!`), so Slice 6 added no
production code — only the covering tests and author-facing docs.

- **Already-correct driver mechanism.** The driver's per-turn `case` maps each
  `execute-session-turn-outcome` disposition: `:failed` records an `:actor/failed`
  pending result annotated with `:failed-prompt {:index :name}`; `:cancelled`
  enqueues `:workflow/cancel`; `:blocked` records `:actor/blocked`. Prior records
  are retained because per-prompt records are persisted incrementally through
  `record-turn-fn` and no abort branch removes them; the aborting prompt leaves no
  record because `record-turn-fn` is only called on the `:ok` branch. The N=1
  degenerate (`execute-session-step!`) takes the same dispositions without a
  prompt name (P10/AC-2).

- **Synchronous-model note (P12).** With the synchronous turn primitive,
  "in-flight" and "inter-prompt" cancellation collapse: the turn runs at the seam,
  then `execute-session-turn-outcome` checks `stopped?` first and returns
  `:cancelled` before recording — so the interrupted prompt leaves no record and
  prompts completed before are retained, exactly as P12 requires.

- **Tests.** New `step_execution_drive_prompt_queue_abort_test.clj` (4 tests / 19
  assertions): intermediate-turn `:failed` naming the prompt + index-0 retained;
  final/last-turn `:failed` (P10) + indices 0/1 retained; inter-prompt/in-flight
  `:cancelled` + index-0 retained + no post-drain result; final-turn
  `:unsupported-structured-output` `:blocked` after N−1 turns + index-0 retained +
  final prompt no record. The P13a upfront invalid-request block is covered by
  the Slice-3 `...-blocks-upfront-on-invalid-structured-request-test`.

- **Docs.** "Abort, cancellation, and blocked outcomes" subsection in
  `doc/workflow-grammar.md`.

- **Verification.** abort suite 4 tests / 19 assertions green; clj-kondo clean.

## Slice 7 — Docs consolidation + changelog + coherence — ψ

Final consolidation slice; no production code.

- **Docs cross-linking.** Multi-prompt author docs are split: `workflow-grammar.md`
  owns the `:prompts` form, precedence/validation, drain-route, resume-and-
  idempotency, and abort/cancel/blocked subsections; `workflow-grammar-concepts.md`
  owns the `:prompt` source-ref + validation + post-drain-judge carve-out. Added a
  cross-link from grammar.md's per-prompt-record bullet to concepts.md's
  *Per-prompt output surfaces*; concepts.md already links back to "the grammar
  reference". All required surfaces present and consistent.

- **CHANGELOG.** `[Unreleased] Added` entry for the multi-prompt `:session` step
  capability (ordered `:prompts` queue, per-prompt addressing, drain/route,
  resume idempotency, abort outcomes).

- **Coherence / TraceID (AC-1..AC-8).** Each AC has a covering test (enumerated in
  steps.md Final verification). `:blocked` is preservation-only, traced under
  AC-3 + AC-5 (PI12) — no separate AC.

- **Three-component verification.** workflow-runtime 129/709 green;
  workflow-step-materialization 26/54 green; workflow-loader 53 green with the
  sole `task-lifecycle-test` failure **confirmed pre-existing and unrelated**
  (fails identically — 4 passed / 16 failed — at session-start commit cf3f43d9f,
  verified in a throwaway base worktree; touches `task-lifecycle.edn` structure,
  not multi-prompt). clj-kondo clean across the edited components.

## Implementation review (task-implementation-review) — ψ, pass 1

Reviewed code+tests+docs against design/plan. Build quality is high: the
`execute-session-turn-outcome` extraction gives one shared per-turn primitive
(N=1 degenerate + N>1 driver), IR validation split (`prompt-ref-errors`,
`ir-error-formatting` extraction) is clean, clj-kondo clean, focused suites green
(drive-prompt-queue 6 + abort 4 + ir-prompts + progression-recording = 21 tests
/125 assertions; step-execution/ir/target-ir/compiler 32/288; materialization
kaocha 25/50). Two actionable findings + one minor observation (see follow-ups):

- **R-1 (coherence, spec↔code). design.md asserts the async F1 suspend/resume
  contract as realized, but the drain is synchronous.** `execute-actor-turn!` is
  synchronous, so the whole N-turn drain runs inside one `:step/enter` action; the
  statechart never suspends mid-drain and `:workflow/resume` fires only from
  `:blocked` (Slice-5 finding). Consequently AC-7's "process restart, replay"
  resume path is a documented **non-occurring path** — its covering tests exercise
  only synthetically reconstructed `state*`, not a real runtime path, and the
  `post-drain-envelope` "known caveat" (envelope built from the live loop
  accumulator, not persisted records) is latent. The divergence is captured only
  here in implementation.md; design.md (source of truth) still presents the async
  mechanism + AC-7 restart/replay as realized, violating the coherence /
  source_of_truth ethos. Reconcile design.md to mark the suspend/resume contract
  as not-yet-realized (synchronous drain) and AC-7 resume as a structural
  progression guard validated via reconstructed state, not an occurring path.

- **R-2 (latent correctness). Later prompt-groups silently drop multi-message
  `:contributions`.** The `:step/enter` `next-group-prompt-fn` keeps only
  `:prompt` from the split and discards `:preloaded-messages`, whereas group 0
  honours both. A later group authored with multi-message `:contributions`
  (>1 materialized message — permitted by the group-internal grammar) silently
  loses every non-final message. There is no IR validation rejecting it, no test,
  and no author-doc warning (doc/workflow-grammar.md is silent). Slice-3 deviation
  #3 notes it as "revisit if needed" but it is reachable from authored grammar
  today. Add a guard (reject multi-message later groups) or handle them, and/or
  document the limitation + add a covering test.

- **R-3 (minor, likely pre-existing). The focused `:test-paths` alias omits
  `components/workflow-step-materialization/test`**, so the scry focused runner
  cannot load the task-226 materialization tests (`core-test`/`source-resolution-test`).
  CI's kaocha `:test` alias DOES include the dir (deps.edn:303) and the tests pass
  there, so coverage holds; but the focused/scry path many slices gate on cannot
  run them. Confirm pre-existing and add materialization/test to `:test-paths` (or
  note as out-of-scope).

## Implementation-review follow-up execution (pass 1) — ψ

Executed R-1, R-2, R-3 (all completed; none blocked). No design behaviour
change — R-1 is a spec-coherence reconciliation, R-2 a documentation +
test-coverage close of an existing limitation, R-3 a test-tooling fix.

- **R-1 (done).** Reconciled design.md with the synchronous-drain reality.
  Intent gains a "Realized vs. target" note; the F1 Architecture-alignment bullet
  is marked "TARGET, not yet realized (synchronous drain)"; AC-7 reworded as a
  structural progression guard validated against a reconstructed `state*` rather
  than an occurring async restart. Recorded the blocker for any future async F1:
  `post-drain-envelope` accumulates the transcript / per-prompt records in the
  **current invocation's** loop locals, which a true cross-restart async resume
  would have to reconstruct from recorded progression instead. No code change —
  the code already matched this reality; design.md was the drifting artifact.

- **R-2 (done, option c).** Documented + test-covered the later-group
  single-submission limitation rather than (a) IR validation or (b) mid-session
  preload re-injection. Rationale: (a) the multi-message condition is only known
  at runtime materialization (a single contribution can expand to multiple
  messages), so a static IR guard would have false negatives — `unreachable >
  forbidden` is not achievable here; (b) mid-session preload re-injection was the
  deliberately-deferred Slice-3 deviation 3 and a disproportionate change (no
  authored multi-message later group exists; the common `:prompt-workflow` form
  is single-message). Extracted the inline `:step/enter` later-group lambda into
  a named `step-execution/later-group-turn-prompt` whose docstring states the
  limitation, wired the production path through it (no behaviour change — same
  `(:prompt (split (materialize …)))`), documented it in `doc/workflow-grammar.md`
  ("Later-group single-submission limitation"), and added two covering tests over
  the real `split-step-session-conversation` (single-message → that message;
  multi-message → only the final message, preloaded dropped).

- **R-3 (done).** Added `components/workflow-step-materialization/test` to the
  `:test-paths` alias (deps.edn). The focused scry runner is
  `clojure -M:test-paths -m scry.cli` (bb.edn:176); the materialization tests were
  off its classpath (only on CI kaocha's `:test` alias). Verified both
  `core-test` + `source-resolution-test` now load and pass under the focused
  runner (25 tests / 50 assertions). In-scope: these are task-226's own
  materialization tests.

Validation: focused drive-prompt-queue suite 8/35 green (incl. 2 new R-2 tests);
full workflow-runtime suite 131 tests / 711 assertions green;
workflow-step-materialization 25/50 green under the focused runner;
`clj-kondo` + `clj-paren-repair` clean on all edited Clojure files.

## Implementation review (task-implementation-review) — ψ, pass 2

Re-reviewed code+tests+docs against the (now R-1-reconciled) design. Build
quality remains high: focused suites green (drive-prompt-queue + abort +
ir-prompts + progression-recording = 23 tests / 127 assertions); IR `:prompt`
source-ref validation (`prompt-ref-errors`) and runtime resolution
(`resolve-source-ref` per-prompt branch) are clean and symmetric; the
synchronous-drain reality now matches design.md after pass-1 R-1. One new
actionable coherence finding; R-1/R-2/R-3 confirmed done.

- **R-4 (coherence, doc↔spec; R-1 propagation gap). `doc/workflow-grammar.md`
  "Resume and idempotency" still presents async/process-restart/event-log-replay
  mid-drain re-entry as a realized runtime path.** R-1 reconciled design.md to
  mark the async suspend/resume + process-restart/replay resume as **TARGET, not
  yet realized** (synchronous drain; AC-7 idempotency validated against a
  *reconstructed* `state*`, not an occurring runtime restart). But the user-facing
  doc section (authored in Slice 5/7, untouched by R-1's doc edit, which only added
  the Later-group section) still reads: "On every re-entry of the step — an
  ordinary in-run advance, a process restart, or an event-log replay — the driver
  reads which group indices already have a recorded turn …", presenting those
  re-entries as occurring. Since the drain is synchronous within one `:step/enter`
  action (no mid-drain suspend), process-restart/replay mid-drain re-entry is a
  **non-occurring** path — the same overclaim R-1 removed from design.md. Per the
  change_chain `update(doc, reflect(meta spec code))` + coherence ethos, the doc
  must carry the same caveat: describe the realized guarantee as the structural
  progression-reconstruction guard (recorded records, never a counter) and qualify
  the restart/replay framing as the not-yet-realized async target (synchronous
  drain today), so user docs do not assert a capability the reconciled spec marks
  unrealized. Reword `doc/workflow-grammar.md` "Resume and idempotency"
  accordingly. (Verify `doc/workflow-grammar-concepts.md` *Per-prompt output
  surfaces* carries no parallel overclaim.)

## Implementation-review follow-up execution (pass 2) — ψ

- **R-4 DONE.** Reworded `doc/workflow-grammar.md` "Resume and idempotency" to
  match the R-1-reconciled design.md. (a) The realized guarantee is now stated as
  the **structural progression guard** — the queue-driving loop re-reads recorded
  per-prompt turn records on **every** iteration to pick the lowest un-run group,
  never an in-memory counter; idempotency is validated by re-driving against a
  **reconstructed** queue state. (b) Added a *Realized vs. target* block that
  qualifies the synchronous drain (whole queue drains inside one step action, no
  mid-drain suspend) and demotes async turn-completion resume / process restart /
  event-log replay mid-drain re-entry to the **not-yet-realized F1 target** the
  synchronous drain stands in for — no longer presented as an occurring runtime
  path. Removed the prior "On every re-entry of the step — an ordinary in-run
  advance, a process restart, or an event-log replay — the driver reads …" framing
  that asserted the unrealized capability. Verified
  `doc/workflow-grammar-concepts.md` *Per-prompt output surfaces* carries **no**
  parallel restart/replay overclaim (it speaks only of assembly-time vs.
  post-drain resolution, which is accurate) — no edit needed there. Docs-only
  change; no code/test impact.

## Implementation review (task-implementation-review) — ψ, pass 3

Re-reviewed code+tests+docs against the (R-1..R-4-reconciled) design. Build
quality remains high: clj-kondo clean on `components/workflow-runtime/src`;
focused suites green — drive-prompt-queue + abort + ir-prompts +
progression-recording = 11 tests / 73 assertions; ir + target-ir-compiler +
step-execution + workflow-step-materialization = 28 tests / 232 assertions. IR
`:prompt` source-ref validation (`prompt-ref-errors`) and runtime resolution
(`source-resolution/resolve-source-ref` per-prompt branch) are symmetric and
clean; the post-drain-envelope / progression substrate split is coherent. R-1..R-4
confirmed done. **One new actionable coherence finding (R-5).**

- **R-5 (coherence, spec↔code; "one unified runtime path" overclaim).** design.md
  asserts a **single** unified runtime queue path with single-prompt as the N=1
  degenerate "**not a separately maintained path**" (AC-2, design.md:92-93), "so
  the runtime drives **one** queue path" (design.md:156), "One unified queue path …
  single-prompt = N=1 degenerate (`λone_way`, no drift)" (design.md:219). The code
  unifies only the **per-turn primitive** (`execute-session-turn-outcome`): at
  runtime `statechart_runtime.clj` dispatches on `(some :name prompt-queue)` to
  **two** separately-maintained driver functions —
  `step-execution/drive-session-prompt-queue!` (named `:prompts`) **vs.**
  `step-execution/execute-session-step!` (unnamed N=1 `:contributions`). The N=1
  path does **not** drive through the progression-based drain
  (`next-un-run-prompt-group`) at all — it runs `prompt` directly — and it
  **duplicates** the disposition→`record-actor-pending!` control flow (the
  `:cancelled`/`:failed`/`:blocked`/`:ok` case + structured-request gate) that the
  drain loop body also carries. So a change to disposition/record semantics (as
  P13's `:blocked` addition was) must be made in **two** places — the exact drift
  the "one path / no drift" design language claims is precluded. This split is
  partly **forced**: the unnamed degenerate intentionally records **no** per-prompt
  turn record (design C3), so it cannot advance through the progression-driven
  drain loop (which selects by recorded indices) without an infinite loop. Resolve
  by **either** (a) reconciling the design language (AC-2 / Grammar
  "drives one queue path" / Architecture "no drift") to state that the unification
  is at the **turn-primitive** level (`execute-session-turn-outcome`), while the
  N=1 degenerate uses a distinct thin driver (`execute-session-step!`) **because**
  the unnamed group records no progression record and so cannot drive the
  progression-based drain — i.e. acknowledge the two drivers + shared primitive,
  the same spec↔code reconciliation R-1 performed — **or** (b) unify the two
  drivers so the N=1 unnamed case also flows through `drive-session-prompt-queue!`
  (e.g. a one-shot/unnamed mode that records no per-prompt record yet still
  terminates), removing the duplicated disposition-handling control flow.

## Implementation-review follow-up execution (pass 3) — ψ

- **R-5 (done via option (a) — spec↔code reconciliation, the R-1 path).** Chose
  (a) over (b). (b) — routing the N=1 unnamed case through
  `drive-session-prompt-queue!` — would **break AC-2**: the drain emits
  `post-drain-envelope`, which wraps outputs in `:prompt-group-outputs` + an
  accumulated `:transcript`, altering the byte-identical single-prompt
  `:pending-actor-result` envelope pinned by the Slice-1 characterization test.
  It is also structurally blocked: the unnamed group records **no** per-prompt
  progression record (design C3), so it cannot advance through the
  `next-un-run-prompt-group` progression-driven selection without special-casing.
  The genuine unification is at the **turn primitive** (`execute-session-turn-outcome`),
  which both drivers already share (Slice-3 deviation: "one turn path"). So the
  honest reconciliation is to state the unification at that level and acknowledge
  the two thin drivers differ only in disposition orchestration. Edits (design.md
  only; no code/test/doc change — the code already realizes the reconciled claim):
  AC-2 (unification at the shared per-turn primitive; N=1 keeps a distinct thin
  driver, with both structural reasons — no progression record + byte-identical
  envelope); Grammar Step-level precedence ("drives one queue path" → "drive the
  same shared per-turn primitive"); Architecture-alignment "One unified queue
  path" bullet → "One unified turn primitive (not one driver function)", naming
  the two drivers, the duplicated disposition→`record-actor-pending!` `case`, and
  why collapsing them is not pursued (AC-2 envelope). Verified
  `doc/workflow-grammar.md` already frames this at the mechanism level ("share one
  internal prompt-queue mechanism"), carrying no "one driver" / "no drift"
  overclaim — no doc edit needed.

## Implementation review (task-implementation-review) — ψ, pass 4

Re-reviewed code+tests+docs against the (R-1..R-5-reconciled) design. Build
quality remains high: clj-kondo clean on `step_execution.clj`; the
`execute-session-turn-outcome` shared primitive, `prompt-ref-errors` validation,
and `resolve-source-ref` per-prompt branch (shape matches the Slice-3
`:prompt-group-outputs` `{:index :name :outputs}` records) are coherent and
symmetric. R-1..R-5 confirmed done. **One new actionable finding (R-6).**

- **R-6 (dead code + latent drift surface — `execute-actor-step!`).**
  `step_execution.clj:443` defines a public `execute-actor-step!` whose `:else`
  (session) branch calls **only** `execute-session-step!` (the single-turn N=1
  path) — it has **no** knowledge of the named multi-prompt
  `drive-session-prompt-queue!` dispatch. It is **never referenced** anywhere in
  the repo (`grep -rn execute-actor-step` finds only the def + a stale
  `target/classes` copy); the live session dispatch lives inline in
  `statechart_runtime.clj` (`(some :name prompt-queue)` → driver vs.
  `execute-session-step!`). So `execute-actor-step!` is dead code, **and** it is a
  second, divergent session-dispatch site that — directly contradicting this
  task's "one path / no drift" thesis — would silently run multi-prompt `:prompts`
  steps as a single turn (dropping every later prompt) if it were ever wired in.
  It predates task 226 (commit 2949310eb), but task 226 introduced the
  multi-prompt dispatch that this stale function now diverges from, so it is the
  task's drift surface to close. Resolve by either removing the unused
  `execute-actor-step!` (and its `:else` session branch) or, if a public
  step-dispatch entry point is genuinely wanted, routing its session branch
  through the same `(some :name prompt-queue)` dispatch the live path uses so the
  two cannot drift. (No test covers `execute-actor-step!`, consistent with it
  being dead.)

### Implementation-review follow-up execution (pass 4)

- **R-6 resolved via option (a) — deleted `execute-actor-step!`.** Confirmed dead
  first: `grep` across `.clj`/`.cljc`/`.cljs` (excluding stale `target/classes`)
  found only the definition — zero callers, zero tests; `git log -S` showed it was
  introduced unused by the component-extraction commit (#72), never wired. Deleted
  the whole `defn` (was `step_execution.clj:443`, end of file). Chose (a) over (b):
  (b) — routing its session branch through the live `(some :name prompt-queue)`
  dispatch — would resurrect a public step-dispatch entry point nothing calls,
  re-creating the very second dispatch site whose latent drift R-6 flags; deleting
  dead code is the λone_way move (no `addition > modification` tradeoff applies to
  code with no callers). The three helpers it referenced
  (`invoke-step-runtime-result`, `apply-invoke-step-result`,
  `execute-session-step!`) remain live via the inlined dispatch in
  `statechart_runtime.clj`, so the deletion removes only the divergent wrapper, not
  any reachable behaviour. Net: a single live session-dispatch site
  (`statechart_runtime.clj` `:step/enter`) now exists, closing the "one path / no
  drift" gap. `clj-kondo` clean; workflow-runtime suite 131 tests / 711 assertions
  green.

## Implementation review (task-implementation-review) — ψ, pass 5

Scope: full re-review of the landed implementation against design + plan +
architecture. Verified green/clean before judging: workflow-runtime 131/711,
workflow-step-materialization 26/54, abort suite 4/19, `clj-kondo` clean on
`step_execution.clj` + `progression_recording.clj`.

Confirmed resolved (no re-flag): R-1 design-coherence (synchronous-drain note),
R-5 two-driver reconciliation (turn-primitive unification), R-6 dead
`execute-actor-step!` deletion (single live dispatch site), R-2 later-group
single-submission limitation (documented + tested). Code matches design;
workflow-runtime boundary respected (generic queue mechanism, no authored
policy); IR `:prompt`-ref validation (`prompt-ref-errors`) is clear, complete,
and carve-out-correct for the post-drain judge; no unnecessary abstractions; the
O(n) per-iteration progression re-read is the deliberate no-counter design and
negligible for realistic queue sizes — not a structural-performance issue.

New actionable finding:

- **R-7 — `drive-session-prompt-queue!` lacks a per-iteration pre-turn
  cancellation checkpoint.** The drain loop selects the next un-run prompt and
  immediately runs its *full* turn (`execute-session-turn-outcome` →
  `execute-actor-turn!`) before any `stopped?` check; the only cancellation
  observation is *after* the turn completes (the post-turn `(stopped?)` inside
  `execute-session-turn-outcome`, CHECK A), and the common non-fallback
  `execute-actor-turn!` path is not even passed `stopped?`. So a cancellation
  arriving strictly *between* prompts still fires the next prompt's
  `ai/generate` (and its whole tool loop) to completion before the run stops —
  wasteful and side-effectful for an already-cancelled workflow, and asymmetric
  with the N=1 `execute-session-step!`, which checks `stopped?` *before* its
  turn at the `cond` head. The loop's per-iteration entry is the natural
  cooperative-cancellation checkpoint (225-lineage cooperative checkpoints across
  session-step execution) and is currently missing. AC-6's "queue stops" /
  "routing skipped" still holds observably, but the design's P12 "in-flight turn
  aborted" is not realized for the between-prompts case — the next turn runs in
  full rather than being skipped. Fix: add a `(stopped?)` checkpoint at the top
  of each loop iteration (before `next-un-run-prompt-group` selection / turn-prompt
  construction / turn execution) that enqueues `:workflow/cancel` and exits the
  loop, so a cancellation observed between turns stops the queue without firing
  an extra turn. Cover with a test asserting a cancellation observed between
  turns enqueues `:workflow/cancel` with **zero** additional turn-fn /
  `ai/generate` invocations and no post-drain `:pending-actor-result`.

Minor coherence nit:

- **R-8 — design.md `## Status` is stale.** It still reads "Design complete;
  ready for planning," but the task is implemented and through four
  implementation-review passes. plan.md (authoritative orchestration) already
  records "Implementation complete," so this is low-severity, but the design
  Status line would mislead a future reader landing on design.md first. Update it
  to reflect implementation-complete / under implementation review.

## Implementation-review follow-up execution (pass 5 — R-7, R-8)

- **R-7 (between-prompt cancellation checkpoint) — DONE.** Wrapped the
  `drive-session-prompt-queue!` drain `loop` body in a top-of-iteration
  `(if (stopped?) (queue/enqueue-event! … :workflow/cancel {}) <select+run>)`
  checkpoint. It runs **before** `next-un-run-prompt-group` selection,
  turn-prompt construction, and `execute-session-turn-outcome`, so a
  cancellation arriving between prompts stops the queue without firing the next
  prompt's turn — symmetric with the N=1 `execute-session-step!` pre-turn
  `stopped?` check, and realizing P12's between-prompts "queue stops". The
  pre-existing top-level cond `(stopped?)` branch (before turn 1) is unchanged;
  the new check covers iterations ≥ 2. Covering test
  `drive-session-prompt-queue-between-prompt-cancellation-checkpoint-test`: with
  `stopped? = (seq (recorded-indices …))` the cancel becomes observable only
  after prompt 0's record lands (strictly between prompts 0 and 1), so the
  checkpoint catches it at the top of iteration 2 and the turn-fn fires **once**
  — without R-7 it would fire twice (prompt 1's turn ran before the old post-turn
  `:ok` check). Asserts: `turn-calls = 1`, `:workflow/cancel` enqueued, no
  `:actor/done` (routing skipped), no `:pending-actor-result`, index-0 record
  retained. `clj-kondo` clean; full workflow-runtime suite 132 tests / 716
  assertions green (was 131/711; +1 test / +5 assertions = the new test).

- **R-8 (stale design.md Status) — DONE.** `## Status` updated from "Design
  complete; ready for planning." to "Implementation complete; under
  implementation review.", matching plan.md's authoritative state. The
  capability-only scope note and the 227-dependency note are preserved verbatim.
  Docs-only coherence fix.

## Implementation review (task-implementation-review) — ψ, pass 6

Full fresh re-review of the landed implementation against design + plan +
architecture + docs. Verified green/clean before judging: focused suites
(drive-prompt-queue 8 + abort 5 + progression-recording + ir-prompts) =
24 tests / 132 assertions green; `clj-kondo` clean on `step_execution.clj`,
`progression_recording.clj`, `ir.clj`.

Confirmed resolved, no re-flag: R-1 (synchronous-drain design coherence), R-2
(later-group single-submission limitation — `later-group-turn-prompt` docstring +
doc + 2 tests), R-3 (`:test-paths` materialization dir), R-4 (doc resume/idempotency
reconciliation), R-5 (two-driver / turn-primitive unification reconciliation), R-6
(dead `execute-actor-step!` deleted — single live dispatch site), R-7 (between-prompt
pre-turn cancel checkpoint), R-8 (design Status line).

Assessed and judged sound:
- **Matches design / architecture.** Single live session-dispatch site
  (`statechart_runtime.clj` `:step/enter` `(some :name prompt-queue)`) → two thin
  drivers (`drive-session-prompt-queue!` / `execute-session-step!`) over the one
  shared per-turn primitive `execute-session-turn-outcome`; the workflow-runtime
  boundary is respected (generic queue mechanism; concrete prompts are authored).
- **Per-prompt substrate coherent.** `progression_recording` record/select shape
  (`{:index :name :outputs}` under `:prompt-group-turns`) matches the drain's
  `post-drain-envelope` `:prompt-group-outputs` and the `source_resolution`
  per-prompt resolution branch; idempotent-on-`:index` recording upholds the
  resume non-re-fire invariant.
- **IR `:prompt`-ref validation complete and carve-out-correct** — non-session,
  single-prompt, unknown-group, non-text-surface, same-step sibling-group rejected;
  post-drain judge carve-out admitted; each rejection + the carve-out is tested.
- **AC-1..AC-8 each covered** across ir-prompts (grammar + ref validation),
  drive-prompt-queue (ordering, resume-skip, reconstructed-position, replay-zero,
  final-turn-only structured), abort (intermediate/final error naming prompt,
  inter-prompt + between-prompt cancellation, structured `:blocked`), and
  source-resolution (per-prompt addressing) suites.
- No unnecessary abstraction; no reusable-pattern duplication; the O(n)
  per-iteration progression re-read is the deliberate no-counter design and is
  negligible at realistic queue sizes — not a structural-performance issue.
- Docs (`doc/workflow-grammar.md` `:prompts` + later-group limitation + resume
  idempotency; concepts) and CHANGELOG `[Unreleased]` entry present and accurate.

No new actionable findings. Implementation review converges — REVIEW_COMPLETE.

## Test review (task-test-review skill)

Applied `λ review_tests`: well-formed ∧ behaviour-coverage(design ACs) ∧
infra-deps(injectable ∧ nullable ∧ ¬mock ∧ ¬stub). Tests are largely exemplary —
the drive-prompt-queue, abort, progression-recording, ir-prompts, and
source-resolution suites assert on **state/outputs** (never interactions), drive
**real** functions (real `split-step-session-conversation`, real `create-run` +
source resolution, real progression substrate), and inject the **nullable**
`:workflow-execute-actor-turn-fn` ctx seam rather than stubbing. Two actionable
gaps found:

- **T-1 (¬stub / nullable-seam): Slice-1 characterization test stubs via
  `with-redefs` where the injectable nullable seam exists.**
  `single-prompt-session-step-envelope-characterization-test`
  (`step_execution_test.clj`, task-introduced commit `fceaff3b6`) monkey-patches
  `turn-execution/execute-actor-turn!` with `with-redefs`, while the project's own
  nullable boundary seam `:workflow-execute-actor-turn-fn` on `ctx` exists
  (`turn_execution_contract.clj`) and is used cleanly by the sibling
  `drive-session-prompt-queue!` tests. The skill requires infra deps be
  `injectable ∧ nullable ∧ ¬stub`; `with-redefs` is a stub. Convert the
  characterization test to inject the turn fn via `ctx`
  (`{:workflow-execute-actor-turn-fn (fn …)}`), dropping `with-redefs`. (Pre-existing
  `execute-session-step!` tests in the same ns share the stub pattern; out of
  task scope — fix the task-introduced characterization test only.)

- **T-2 (behaviour-coverage gap): no covering test for B1(b) — a `:prompt` on a
  `:yield` ref is invalid.** AC-3 / Slice-4 enumerate `:prompt` as `:output`-only,
  never `:yield`; `prompt-source-ref-validation-test` exercises every **other**
  invalid `:prompt` case (non-session, single-prompt, unknown-group,
  non-text-surface, same-step) + the judge carve-out + back-compat, but **not**
  the `:yield` case. The rule is enforced structurally (`:prompt` lives only on
  `step-output-ref-schema`, not `step-yield-ref-schema`), so a schema regression
  unifying/loosening those shapes would go uncaught. Add a test asserting a
  `{:step s :prompt p :yield k}` source-ref is rejected (structural-errors),
  completing the AC-3 invalid-case coverage.

## Test-review follow-up execution (pass 1)

- **T-1 (DONE).** Converted both `testing` blocks of
  `single-prompt-session-step-envelope-characterization-test`
  (`step_execution_test.clj`) from `with-redefs` on
  `turn-execution/execute-actor-turn!` to injecting the nullable
  `:workflow-execute-actor-turn-fn` ctx seam (passed as the first
  `execute-session-step!` arg). Asserted envelope shape unchanged (R4 comparand
  preserved). Scoped to the task-introduced characterization test only; the
  pre-existing `execute-session-step!` with-redefs tests in the same ns were left
  untouched, so the `turn-execution` alias require remains in use. 14 tests / 113
  assertions green (step-execution-test + ir-prompts-test).

- **T-2 (DONE + code-correction).** Writing the test surfaced that the Slice-4
  deviation's claim — "the `:yield`+`:prompt` invalid case is enforced
  **structurally** … cannot pass `source-ref-schema`" — was **false**:
  `step-output-ref-schema` and `step-yield-ref-schema` were **open** malli maps,
  so `{:step s :prompt p :yield k}` validated against `step-yield-ref-schema`
  (the extra `:prompt` key is allowed by an open map) and was only rejected
  *semantically* as `:prompt-ref-non-text-surface` (because `validate-prompt-source-ref`
  reads a nil `:output` key). Empirically confirmed via the project classpath
  before editing.
  - **Resolution (chose code-fix over test-of-reality):** closed both ref map
    schemas with `{:closed true}`, making the Slice-4 `unreachable > forbidden`
    claim true. The `:prompt`+`:yield` ref now fails `step-output-ref-schema`
    (missing required `:output`) **and** the closed `step-yield-ref-schema`
    (extra `:prompt`), so `source-ref-schema`'s `:or` rejects it structurally:
    `{:valid? false :structural-errors <data> :semantic-errors []}`. Verified
    valid refs still pass (`{:step :output}`, `{:step :prompt :output}`,
    `{:step :yield}`). Rejected the alternative (assert the accidental semantic
    `:prompt-ref-non-text-surface` rejection) because it would canonicalize a
    misleading error mechanism and leave the documented structural invariant
    unenforced (a schema regression unifying the ref shapes would go uncaught) —
    closing the schemas realizes `impossible_invalid_states` / `enforceable
    invariants`.
  - **Test:** added `prompt-source-ref-validation-test` case "a `:prompt`
    discriminator on a `:yield` ref is structurally rejected (B1(b))" asserting
    `false? :valid?` ∧ `some? :structural-errors` ∧ `[] :semantic-errors`.
  - **Docs:** `doc/workflow-grammar-concepts.md` now states the `:prompt`+`:yield`
    case is structurally rejected (matches neither ref shape), not a semantic
    carve-out.
  - **Verification:** full workflow-runtime suite 132 tests / 719 assertions
    green; `clj-kondo` clean on all edited files.

## Test review (task-test-review skill) — pass 2

Re-applied `λ review_tests` (well-formed ∧ behaviour-coverage(design ACs) ∧
infra-deps injectable/nullable/¬mock/¬stub) across the task test suite after the
pass-1 T-1/T-2 fixes. Infra-deps are clean: the drive/abort suites inject the
nullable `:workflow-execute-actor-turn-fn` ctx seam (¬stub), assert on
state/outputs only (event-queue, working-memory, recorded progression — never
interactions), and drive **real** functions (`record-prompt-group-turn`,
`next-un-run-prompt-group`, `split-step-session-conversation`, `create-run` +
source resolution). Two **new** actionable gaps found (TR-3, TR-4); recorded as
unchecked items in steps.md.

- **TR-3 (behaviour-coverage gap): no runtime test asserts the N=1
  unnamed/`:contributions` envelope omits `:prompt-group-outputs` (design C3).**
  Design C3 makes it load-bearing that the unnamed group "contributes **only** the
  step-level rollup and **no** addressable per-prompt record" — the unified-path
  result-shape consistency for the degenerate. `session-step-prompt-queue-derivation-test`
  pins the *IR* derivation (group has no `:name`), and
  `single-prompt-session-step-envelope-characterization-test` pins the *presence*
  of step-level keys (`:final-llm-reply`/`:text`/`:transcript`), but **nothing**
  asserts the *absence* of `:prompt-group-outputs` from the N=1
  `execute-session-step!` envelope. A refactor that leaked an (empty or populated)
  `:prompt-group-outputs` into the degenerate envelope would go uncaught,
  silently violating C3 / the named-only addressing contract. Add an assertion
  (`(is (not (contains? outputs :prompt-group-outputs)))`) to the characterization
  test's text/structured blocks (or a focused test), completing AC-2/AC-3 N=1
  no-per-prompt-record coverage.

- **TR-4 (robustness / shape-drift): drive + abort test fixtures hand-roll the
  canonical run/attempt `state*` instead of deriving it from the real
  constructors.** `running-attempt-state*` / `recorded-turns-state*` (in
  `step_execution_drive_prompt_queue_test.clj` +
  `step_execution_drive_prompt_queue_abort_test.clj`) embed the
  `{:workflows {:runs {… :step-runs {… :attempts [{:status :running}]}}}}` shape
  as a literal, while the production `record-prompt-group-turn` /
  `next-un-run-prompt-group` readers navigate it via `latest-attempt` /
  `latest-attempt-map`. The sibling `progression_recording_test` already builds
  its state from the canonical `create-run` + `append-attempt-to-run` +
  `start-latest-attempt` constructors. If the canonical attempt/run shape evolves,
  the hand-rolled fixtures won't track it: the drive/abort tests would keep
  passing against a stale shape while production navigation breaks — the fixtures
  decouple the tests from the real shape they purport to exercise. Derive the
  running-attempt `state*` from the real constructors (mirroring
  `base-state-with-run`) so the drive-queue tests are coupled to the canonical
  run/attempt shape and cannot silently drift. (The reconstructed-state Slice-5
  cases legitimately model a post-restart reload; even there, building the base
  via the constructors and then layering the recorded `:prompt-group-turns`
  preserves the restart semantics while tracking the canonical shape.)

## Test-review follow-up execution (pass 2)

Executed the two newly-added test-review-pass-2 items (TR-3, TR-4). Both are
test-only (TR-3 adds an assertion; TR-4 re-bases fixtures); no production code
changed.

- **TR-3 — DONE.** Added `(is (not (contains? outputs :prompt-group-outputs)))`
  to **both** `testing` blocks of
  `single-prompt-session-step-envelope-characterization-test`
  (`step_execution_test.clj`) — the text block (over the bound `outputs`) and the
  structured block (over `(:outputs payload)`). Now a refactor leaking an
  (empty or populated) `:prompt-group-outputs` into the N=1 unnamed
  `:contributions` `execute-session-step!` envelope is caught, completing the
  AC-2/AC-3 C3 named-only-addressing coverage (absence was previously asserted
  nowhere).

- **TR-4 — DONE.** Re-based the hand-rolled `running-attempt-state*` /
  `recorded-turns-state*` fixtures (in both
  `step_execution_drive_prompt_queue_test.clj` and
  `step_execution_drive_prompt_queue_abort_test.clj`) onto canonical
  constructors. Added two shared builders to the existing workflow-runtime test
  support ns `step_test_support.clj` (the natural home; both files already sit in
  that component's test tree) rather than duplicating the canonical-construction
  logic in each file (consistency / λone_way):
  - `canonical-running-run-state` — `register-definition` +
    `create-run` + `append-attempt-to-run` + `start-latest-attempt` (mirrors
    `progression_recording_test/base-state-with-run`), parameterized by
    `run-id`/`step-id` over a minimal single-session-step definition.
  - `canonical-recorded-run-state` — the running state plus per-prompt `records`
    recorded canonically via `record-prompt-group-turn` (so reconstructed
    restart/replay fixtures carry the real `:prompt-group-turns` shape +
    `:recorded-at`, not a literal). The Slice-5 replay test already dissocs
    `:recorded-at` before comparison, so canonical recording is transparent.
  Each file's thin `running-attempt-state*` / `recorded-turns-state*` wrappers now
  atom-wrap these shared builders, leaving every call site unchanged. The
  drive/abort tests are now coupled to the canonical run/attempt shape the
  production `latest-attempt`-based readers navigate, so a canonical-shape change
  can no longer leave them green against a stale literal while production breaks.

  **Verification.** `clj-kondo` clean; `clj-paren-repair` no changes. Focused
  run (step-execution-test + drive-prompt-queue-test + abort-test +
  progression-recording-test) 34 tests / 190 assertions green; full
  workflow-runtime suite 132 tests / 721 assertions green (+2 assertions from
  TR-3, no regressions from the shared-fixture re-base).

## Test review (task-test-review skill) — pass 3

Re-applied `λ review_tests` (well-formed ∧ behaviour-coverage(design ACs) ∧
infra-deps injectable/nullable/¬mock/¬stub) after the pass-1/2 fixes
(T-1/T-2/TR-3/TR-4). Infra-deps remain clean: the drive/abort suites inject the
nullable `:workflow-execute-actor-turn-fn` + record-turn-fn ctx seams (¬stub),
assert on state/outputs only (event-queue, working-memory, recorded
progression — never interactions), and drive **real** functions
(`split-step-session-conversation`, `record-prompt-group-turn`,
`next-un-run-prompt-group`, `create-run` + source resolution). AC-1..AC-8 each
have covering tests (TraceID in steps.md Final verification confirmed).
**One new actionable gap found (TR-5); recorded as an unchecked item in steps.md.**

- **TR-5 (robustness / shape-drift — residual TR-4 instance):
  `drive-session-prompt-queue-resume-skips-recorded-prompts-test` still
  hand-rolls a literal `state*`.** TR-4 re-based the `running-attempt-state*` /
  `recorded-turns-state*` fixture helpers onto the canonical
  `canonical-running-run-state` / `canonical-recorded-run-state` constructors so
  the drive/abort tests track the real run/attempt shape the production
  `latest-attempt`-based readers navigate. But
  `drive-session-prompt-queue-resume-skips-recorded-prompts-test` (in
  `step_execution_drive_prompt_queue_test.clj`) does **not** use
  `recorded-turns-state*` — it inlines its own
  `(atom {:workflows {:runs {… :step-runs {… :attempts [{… :prompt-group-turns
  [{:index 0 …}]}]}}}})` literal with a pre-recorded index-0 turn. This is the
  **exact** shape-drift defect TR-4 fixed, in a call site that escaped the
  re-base: if the canonical attempt/run shape evolves, this resume test keeps
  passing against a stale literal while production navigation breaks. The
  `recorded-turns-state*` helper already exists and takes precisely
  `[run-id step-id records]` — the test can build its single-record state via
  `(recorded-turns-state* run-id step-id [{:index 0 :name "architecture" :outputs
  {:final-llm-reply "prior"}}])`, dropping the literal. Test-only; no production
  change.

**Verified-acceptable (not raised):** the workflow-step-materialization
`resolve-prompt-discriminated-per-prompt-surface-test`
(`source_resolution_test.clj`) consumes a literal `multi-prompt-accepted-result`
envelope modelling the producer's `post-drain-envelope` shape. This is a focused
**consumer-side** resolution unit test in a **different component**; coupling it
to the workflow-runtime producer would cross the component boundary, so the
representative literal is the idiomatic isolation choice (the producer-side
`drive-session-prompt-queue-runs-named-turns-in-order-test` independently pins
the `:prompt-group-outputs` shape on the producer side).
