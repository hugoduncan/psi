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

- [ ] B1: Specify the yielded-value (`:yields`/`:yield`) composition for a
  multi-prompt session step. State (a) the step's yielded value as a whole
  (e.g. text from the final prompt's `:final-llm-reply`) and (b) whether the
  `:prompt` discriminator applies to `{:step s :yield k}` refs or is confined to
  `:output` refs — `doc/workflow-grammar-concepts.md` treats output surfaces and
  yielded values as distinct ref forms.
- [ ] B2: Disambiguate per-prompt `:transcript` content: does a prompt-group's
  `:transcript` contain only that prompt's turn slice, or the cumulative
  conversation up to and including that turn? Distinguish it explicitly from the
  step-level `:transcript` ("accumulated across all turns").
- [ ] B3: State whether a `:prompts` vector with exactly one entry is valid, and
  if so whether it runs the multi-prompt path (per-prompt addressing available)
  or is rejected in favor of `:contributions` — reconcile with Q6 (no internal
  rewrite of single-prompt to one-element `:prompts`) and AC-1 ("N ≥ 1").
- [ ] B4: State the prompt-group `:name` uniqueness rule within a step and the
  validation error on duplicate prompt-group names (the `:prompt p` selector and
  per-prompt records keyed by `:name` presuppose uniqueness).
- [ ] B5: Specify the step outcome when a run is cancelled between queued prompts
  (AC-6): whether the judge/`:on` routing runs, what outcome/envelope is
  recorded, and whether already-completed per-prompt turn records remain
  introspectable — mirror the explicitness of the AC-5 intermediate-error path.
