# Implementation notes

- architectural review: no architectural-fit feedback — design conforms to the
  RPC focus-gating delivery rule (doc/architecture.md), app-runtime/RPC footer
  ownership boundary, and task-241 no-cross-session-leakage invariant. The
  push-based direct emit (`emit-footer-updated!` via progress-queue) vs the
  `:projection/ui-changed` recompute-at-delivery convergence target is a
  pre-existing pattern; re-architecting it would widen the frozen scope, so it
  was not filed as an actionable misfit for this design.
