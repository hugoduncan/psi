# Design follow-up steps

## Architectural-fit review

- [ ] A1: Reconcile Q8 routing with the documented decision in
  doc/workflows.md that `review-task-design` routes on **phase outputs** and
  `clarity-status` deliberately does **not** re-read task artifacts. Either keep
  routing on workflow data flow (step result / judge outcome) consistent with
  the VSM replay/event-log ethos, or justify in design.md why filesystem-state
  routing (`open-checklist-items-routing` re-reading `design-steps.md`) is an
  acceptable, replay-safe exception and how it preserves determinism.
- [ ] A2: Specify how the new `{:step :prompt :output}` selector integrates into
  the **shared** source-ref grammar/IR (invoke args, contributions, template
  vars, delegated context), including the explicit validation error when
  `:prompt` targets a non-multi-prompt or non-session step (mirror the existing
  "output not exposed by that step type is invalid" rule).
- [ ] A3: State that each queued prompt's turn result is recorded in the
  canonical step-result / progression substrate (introspectable + replay-faithful
  per S4), not held only in transient in-loop locals, while still reconciling to
  one pending-actor result / one routing decision (Q5).
