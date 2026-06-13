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
