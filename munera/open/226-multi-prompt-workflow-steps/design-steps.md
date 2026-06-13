# Design follow-up steps

## Architectural-fit review

- [x] A1: Reconcile Q8 routing with the documented decision in
  doc/workflows.md that `review-task-design` routes on **phase outputs** and
  `clarity-status` deliberately does **not** re-read task artifacts. Either keep
  routing on workflow data flow (step result / judge outcome) consistent with
  the VSM replay/event-log ethos, or justify in design.md why filesystem-state
  routing (`open-checklist-items-routing` re-reading `design-steps.md`) is an
  acceptable, replay-safe exception and how it preserves determinism.
- [x] A2: Specify how the new `{:step :prompt :output}` selector integrates into
  the **shared** source-ref grammar/IR (invoke args, contributions, template
  vars, delegated context), including the explicit validation error when
  `:prompt` targets a non-multi-prompt or non-session step (mirror the existing
  "output not exposed by that step type is invalid" rule).
- [x] A3: State that each queued prompt's turn result is recorded in the
  canonical step-result / progression substrate (introspectable + replay-faithful
  per S4), not held only in transient in-loop locals, while still reconciling to
  one pending-actor result / one routing decision (Q5).

## Ambiguity review

- [x] B1: Specify the yielded-value (`:yields`/`:yield`) composition for a
  multi-prompt session step. State (a) the step's yielded value as a whole
  (e.g. text from the final prompt's `:final-llm-reply`) and (b) whether the
  `:prompt` discriminator applies to `{:step s :yield k}` refs or is confined to
  `:output` refs — `doc/workflow-grammar-concepts.md` treats output surfaces and
  yielded values as distinct ref forms.
- [x] B2: Disambiguate per-prompt `:transcript` content: does a prompt-group's
  `:transcript` contain only that prompt's turn slice, or the cumulative
  conversation up to and including that turn? Distinguish it explicitly from the
  step-level `:transcript` ("accumulated across all turns").
- [x] B3: State whether a `:prompts` vector with exactly one entry is valid, and
  if so whether it runs the multi-prompt path (per-prompt addressing available)
  or is rejected in favor of `:contributions` — reconcile with Q6 (no internal
  rewrite of single-prompt to one-element `:prompts`) and AC-1 ("N ≥ 1").
- [x] B4: State the prompt-group `:name` uniqueness rule within a step and the
  validation error on duplicate prompt-group names (the `:prompt p` selector and
  per-prompt records keyed by `:name` presuppose uniqueness).
- [x] B5: Specify the step outcome when a run is cancelled between queued prompts
  (AC-6): whether the judge/`:on` routing runs, what outcome/envelope is
  recorded, and whether already-completed per-prompt turn records remain
  introspectable — mirror the explicitness of the AC-5 intermediate-error path.

## Inconsistency review

- [x] C1: Reconcile the exemplar with the real `review-task-design.edn`. The
  referenced exemplar and `doc/workflows.md` define **three** review phases
  (`architecture → ambiguity → inconsistency → clarity-status`), and the live
  `clarity-status` judge passes **three** `*-text` args to
  `workflow/pass-feedback-routing` (which disjuncts over all of them). The design
  (Scope, Q7, Q8, grammar-shape example at design.md:182–185) merges only
  `architecture-review` + `ambiguity-review` with a **two-arg** judge and Q8
  claims this is "exactly the disjunction `pass-feedback-routing` already
  computes across the unmerged phase outputs" — which is false once
  `:inconsistency-text` is dropped. Fix design.md to either (a) merge all three
  reviews into the multi-prompt step with a three-arg judge, or (b) state
  explicitly that `inconsistency-review` stays a separate step and correct the
  Q8 "exactly the disjunction" claim accordingly; in either case state the fate
  of the `inconsistency-review` phase and reconcile the Q7 token-efficiency
  rationale.

## Architectural-fit review (pass 2)

- [x] D1: Reconcile the merged exemplar's topology claim with the architecture
  of a multi-prompt step. Scope/Q7 say merging architecture/ambiguity/
  inconsistency review into one multi-prompt `:session` step "matches the real
  workflow's three-phase topology," but a multi-prompt step is *N turns → one
  post-drain route* (Q5/Q8) and cannot reproduce the live `review-task-design`
  per-phase **review→follow-up(mutates design.md)→next review** structure
  (doc/workflows.md). Either (a) drop the "matches the live topology" /
  per-phase-faithfulness framing and state explicitly that the merged step reads
  the design once and runs all three reviews against the *same un-followed-up*
  design with follow-up executed only once after the post-drain route (and say
  where the three `*-follow-up` steps go), or (b) keep the phases as separate
  review→follow-up steps and scope the exemplar to a place where back-to-back
  same-session turns are genuinely the right architecture. Make the design state
  the fate of the per-phase follow-up steps under the merge.
- [x] D2: Reconcile the deliberate dual single-prompt path (Q6/AC-2/B3 — keep
  `:contributions` as a separate execution path/authoring form, not the N=1
  degenerate of `:prompts`, justified by byte-for-byte/"behaves exactly as
  today") with `λone_way` (singular solution / obvious path), `consistent` (one
  idiom), and `λα. ¬compat(backward)`. Either justify the dual path on a
  non-back-compat architectural basis, or treat single-prompt as the N=1
  degenerate of one unified `:prompts` path so there is one execution path and
  one obvious authoring for a single-turn session step.

## Ambiguity review (pass 2)

- [x] E1: Specify the mechanism by which the merged exemplar reads its sources
  "once and reused" across prompt-groups, given the step-level `:contributions`
  **xor** `:prompts` precedence leaves a `:prompts` step with no step-level
  shared preload. Decide and state in design.md whether shared context is carried
  solely by the live shared session (first prompt loads sources; later prompts
  rely on conversation memory) or via a step-level shared-contribution/preamble
  field (and if so add it to the Q1 grammar shape). Reconcile with the
  Architecture-alignment "list of prompt strings layered over a shared preload"
  (currently an undecided "or") so the Q7/D1 token-efficiency rationale has a
  concrete realization.
- [x] E2: State the prompt-group **internal** authoring precedence: whether a
  prompt-group uses `:prompt-workflow` **xor** `:contributions` (mirroring the
  step-level xor), and the IR-validation error when both or neither are present.
  The grammar shape shows `:prompt-workflow "..."` "or `:contributions [...]`"
  without a rule.
- [x] E3: Reconcile the deferred "cross-turn workflow data flow" (Scope) with the
  uniform `:prompt` source-ref resolution (Q12). State explicitly whether a
  prompt-group's `:contributions`/template may reference an **earlier sibling
  prompt-group in the same step** via `{:step <self> :prompt p :output k}`, and
  add the corresponding entry to the "Source-ref integration for `:prompt`"
  validation list (e.g. same-step/self `:prompt` refs are invalid in the first
  cut) so the validation enumeration is complete.

## Inconsistency review (pass 2)

- [x] C2: Reconcile the merged exemplar with the surviving `final-summary` step
  of the referenced `review-task-design.edn`. `final-summary` currently consumes
  the three review phases via three contributions
  `{:step "architecture-review" :yield :text}`,
  `{:step "ambiguity-review" :yield :text}`, and
  `{:step "inconsistency-review" :yield :text}`, but the exemplar Scope merges
  those three review steps into one multi-prompt step (eliminating those step
  names), while B1(b) makes per-prompt `:yield` invalid and step-level
  `:yield :text` resolves only to the last prompt's reply. State in design.md how
  `final-summary` obtains all three reviews' text after the merge — i.e. migrate
  its three `{:step "<phase>-review" :yield :text}` contributions to per-prompt
  `{:step "design-review" :prompt "<phase>" :output :final-llm-reply}` refs (the
  only legal per-phase text addressing under B1(b)) — and fold this into the
  in-scope exemplar rewrite description (Scope / Q7 / Q11).

## Architectural-fit review (pass 3 — post-prune)

- [x] F1: Specify the resume/suspend contract for the internal N-prompt queue,
  reconciling the design's synchronous-drain framing ("next prompt submitted only
  after the prior turn finishes; routing once after drain; one post-drain
  `:pending-actor-result`") with the resume/suspend-driven canonical runtime
  (`statechart-runtime`; `psi.workflow-runtime.core` resume; async `ai/generate`
  effect), where N turns mean N suspend points inside one statechart step. State
  that on resume (async turn completion, process restart, replay) the queue-driving
  loop continues at the next **un-run** prompt from the recorded per-turn
  progression (A3) rather than re-submitting completed turns — otherwise a
  completed turn's side-effectful, non-deterministic `ai/generate` effect re-fires,
  violating the VSM `∀change → event → log → replayable` ethos and falsifying the
  design's "replay-faithful" claim. Either commit to resume-from-progression for
  the internal queue (and locate the suspend/resume boundary relative to the single
  statechart step), or explicitly scope mid-queue resume out and state its
  replay-fidelity consequences. Distinct from A3 (records results) and Q5 (one
  post-drain route).

## Ambiguity review (pass 3 — post-prune)

- [ ] G1: Specify, in AC-5 (intermediate-turn error), the fate of per-prompt turn
  records for prompts completed **before** the failing one — whether they are
  retained and introspectable (mirroring AC-6's explicit cancellation statement),
  and whether the failing prompt leaves any partial record — so the `:failed` and
  `:cancelled` abort paths are symmetric on S4 introspectability.
- [ ] G2: Reconcile AC-4 ("the judge may reference per-prompt surfaces") with the
  Source-ref integration validation rule that marks a `:prompt` selector invalid
  when "it targets the same step being assembled (sibling-group ref, forward or
  back)." State explicitly whether a step's own post-drain `:judge` may address
  its prompt-groups via `{:step s :prompt p :output k}`, and if so carve the
  post-drain judge out of the same-step invalid rule (e.g. the judge resolves
  after every turn is recorded, unlike assembly-time contributions/templates) so
  the validation enumeration and AC-4 are unambiguously consistent.
