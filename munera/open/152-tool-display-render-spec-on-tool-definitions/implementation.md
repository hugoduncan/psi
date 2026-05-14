- 2026-05-14 ψ ambiguity review:
  - Actionable ambiguity: task directory is missing required `plan.md` and `steps.md`, so the intended implementation approach and execution checklist are not reviewable yet.
  - Actionable ambiguity: the design requires extension registration alone to drive shared display behavior, but it does not disambiguate the canonical owner/projection path for non-serializable render hooks (`tool-registry` vs UI state vs runtime projection), especially since EQL snapshots currently strip renderer fns while interactive projections preserve them.
  - Actionable ambiguity: acceptance 3 says built-in display should no longer depend on frontend hardcoded name branches "for the cases covered by this task", but the covered built-ins/tool rows are not enumerated beyond a minimum list, leaving the migration boundary unclear.
  - Actionable ambiguity: acceptance 7 permits deferring custom result rendering, but the design does not state whether the chosen minimal slice may omit `:render-result-fn` entirely or must still carry/result-project a dormant result contract.

- 2026-05-14 ψ ambiguity follow-up execution:
  - Added missing `plan.md` and `steps.md` so the task now satisfies Munera required task artifacts before implementation.
  - Refined `design.md` to choose the render-hook shape explicitly and record the deferred declarative-display alternative.
  - Named the canonical owner/projection path: hook fns live on the runtime registered tool definition, are projected into interactive UI-state tool renderers for live frontend execution, and remain stripped from EQL/UI snapshots.
  - Enumerated the exact built-in migration boundary for this task: `bash`, `read`, `edit`, and `write` call-header rendering only, with existing `read`/`edit` line-range semantics preserved.
  - Clarified result-rendering scope: the canonical registered contract includes both `:render-call-fn` and `:render-result-fn`, but built-in migration obligations in this task remain call-header-only while extension result rendering must be supported by the shared path.
  - No blockers encountered; all newly added ambiguity design-steps completed without executing `steps.md` implementation items.

- 2026-05-14 ψ inconsistency review:
  - Actionable inconsistency: `design.md` makes the runtime tool registry entry the canonical owner of `:render-call-fn`/`:render-result-fn`, but `plan.md` and `steps.md` stop at projecting hooks into UI state and never require updating canonical tool-definition normalization/registration surfaces (for example `psi.tool-registry.defs/normalize-tool-def`), so the planned mechanism does not yet match the chosen owner.
  - Actionable inconsistency: `design.md` requires built-in parity for both TUI and Emacs through the shared mechanism, but `steps.md` has only one combined proof item and `plan.md` names no Emacs-specific implementation/proof work even though current renderer consumption appears TUI-owned (`components/tui/src/psi/tui/tool_render.clj`) and extension docs still describe imperative UI registration; the task files therefore overstate Emacs parity without a corresponding plan step.
  - Actionable inconsistency: `design.md` says extension-facing documentation should demote imperative `:register-tool-renderer` registration to an advanced/compatibility escape hatch, but `plan.md` and `steps.md` only say to "prefer" the new path and do not require reconciling existing docs that currently teach imperative UI registration as the primary customization mechanism (`doc/extensions.md`).

- 2026-05-14 ψ inconsistency follow-up execution:
  - Updated `plan.md` decision sequence to require canonical normalization/registration work, not only UI-state projection.
  - Updated `steps.md` to require the concrete `normalize-tool-def` / registration-backfill wiring for `:render-call-fn` and `:render-result-fn`.
  - Added explicit Emacs shared-path parity implementation/proof work to `plan.md` and `steps.md` rather than leaving parity implicit.
  - Tightened documentation follow-up in `steps.md` and `plan.md` so `doc/extensions.md` must be rewritten to present tool-definition display hooks as the primary path, with imperative `:register-tool-renderer` reduced to compatibility/advanced usage.
  - No blockers encountered; all newly added inconsistency design-steps completed without executing `steps.md` implementation items.
