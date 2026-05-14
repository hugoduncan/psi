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

- 2026-05-14 ψ implementation:
  - Added canonical render-hook preservation to `psi.tool-registry.defs/normalize-tool-def` for `:render-call-fn` and `:render-result-fn`.
  - Added shared helper owner `components/tool-registry/src/psi/tool_registry/render.clj` for built-in call-header rendering semantics (`bash`, `read`, `edit`, `write`).
  - Projected canonical tool-definition render hooks into interactive UI state via `psi.ui.state/register-tool-def-renderers!` and used that path from tool registration and session active-tool updates.
  - Migrated the in-scope built-in tools in `psi.agent-session.tools` onto the shared render-hook path by attaching `:render-call-fn` to the canonical built-in tool definitions and the derived runtime tool maps.
  - Kept TUI generic fallback behavior intact while reusing shared built-in call-header logic.
  - Added RPC progress-path UI snapshot projection for tool lifecycle events so Emacs can consume shared renderer hooks during live tool-row rendering.
  - Updated Emacs tool-row rendering to prefer shared tool-definition render hooks from projected UI snapshot data while retaining generic fallback summaries.
  - Updated extension docs to teach tool-definition render hooks as the primary display customization path and demote direct imperative `:register-tool-renderer` use to compatibility/advanced status.
  - Added focused tests for canonical hook preservation, registration projection, shared UI projection behavior, TUI extension call/result rendering, and Emacs shared-path call rendering.
  - Verification still pending.

- 2026-05-14 ψ implementation review:
  - Actionable issue: the new RPC/Emacs shared-renderer path is not viable as implemented. `psi.rpc.session.streams` attaches `:ui-snapshot` data to tool lifecycle events, but the RPC transport serializes frames through EDN (`psi.rpc.transport/edn-wire-safe` + `pr-str`), so function-valued `:render-call-fn` / `:render-result-fn` entries cannot cross the boundary as callable hooks. The Emacs implementation currently expects callable functions in `psi-tool-rows.el`, and the focused Emacs proof only passes because it injects local Lisp lambdas directly into test data rather than exercising the real transport shape.
  - Actionable issue: `:session/set-active-tools` projects renderers for the new active tool set but does not remove stale renderer entries for tools that were removed or no longer carry render hooks. That lets interactive UI renderer state drift from the canonical active-tool/tool-definition set, contradicting the chosen design where UI state is a projection/cache rather than an accumulating side registry.
  - Follow-up expectation: rework the Emacs parity path so it uses transport-safe shared display data or an explicit Emacs-local interpretation path, and tighten active-tool renderer projection so canonical removal updates the UI renderer cache as well.

- 2026-05-14 ψ implementation follow-up:
  - Replaced the RPC→Emacs executable-hook path with transport-safe shared call-summary projection:
    - `psi.tool-registry.render/transport-progress-event` now derives canonical `:call-summary` strings for tool lifecycle events.
    - `psi.rpc.session.streams` projects transport-safe event metadata instead of shipping executable renderer fns.
    - `psi.rpc.events/progress-event->rpc-event` now includes `:call-summary` on `tool/executing` and `tool/result` when available.
    - Emacs tool-row rendering now prefers transport-safe `:call-summary` metadata and only tolerates direct local functions as a non-RPC fallback.
  - Tightened canonical renderer projection in UI state:
    - added `clear-tool-renderer!`
    - added `replace-tool-def-renderers!`
    - `:session/set-active-tools` now replaces the projected renderer set instead of only accumulating registrations
  - Updated focused proofs to cover the corrected shape:
    - Emacs tool-row proof now exercises RPC-compatible `:call-summary` event data
    - RPC event/progress tests assert transport-safe call-summary projection
    - active-tool projection tests now assert stale renderer removal on replacement.
