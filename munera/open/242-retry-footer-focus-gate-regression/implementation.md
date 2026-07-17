# Implementation notes

- architectural review: no architectural-fit feedback — design conforms to the
  RPC focus-gating delivery rule (doc/architecture.md), app-runtime/RPC footer
  ownership boundary, and task-241 no-cross-session-leakage invariant. The
  push-based direct emit (`emit-footer-updated!` via progress-queue) vs the
  `:projection/ui-changed` recompute-at-delivery convergence target is a
  pre-existing pattern; re-architecting it would widen the frozen scope, so it
  was not filed as an actionable misfit for this design.
- ambiguity review added 1 new design step — AC1's unconditional
  "failing-then-passing" focused-session test conflicts with the design's own
  "focused case may be working as intended" branch (a passing-immediately test
  cannot be failing-then-passing).
- inconsistency review: no new inconsistency feedback. Verified the design's
  quoted `focus-allows?` matches actual `components/rpc/src/psi/rpc/events.clj`
  (semantically identical; real code binds `effective-focus` in a `let`). The
  AC1-vs-working-as-intended tension is already captured by the ambiguity pass,
  not re-filed here.
