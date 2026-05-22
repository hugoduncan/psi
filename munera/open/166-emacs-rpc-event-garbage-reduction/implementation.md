# Implementation notes

## 2026-05-22 — ambiguity review
Reviewed `design.md` plus referenced Emacs hot-path code/tests (`psi-events.el`, `psi-assistant-render.el`, `psi-tool-rows.el`, `psi-widget-projection.el`, `psi-projection.el`, streaming/tool/widget/projection tests). `plan.md` and `steps.md` are absent. New actionable ambiguities: missing execution artifacts; no concrete allocation/CPU success threshold; assistant stream payload contract/delta-vs-snapshot fallback examples are underspecified; mandatory vs optional optimization targets across assistant/thinking/tool/widget/projection paths are unclear.

## 2026-05-22 — ambiguity follow-up execution
Completed all newly added ambiguity follow-ups in `design-steps.md`.

- Added placeholder `plan.md` and `steps.md` that explicitly keep the task design-only until review is complete; no implementation steps were executed.
- Added a measurable success threshold to `design.md`: append-only assistant/thinking tests must prove no post-creation full redraw, suffix-only property application, retained prefix overlays, and fallback redraw for non-append cases via helper-call/range-size instrumentation.
- Clarified `assistant/delta` as mixed payload input preserving existing merge semantics, with examples for incremental deltas, extending cumulative snapshots, tail churn, and divergent snapshots.
- Clarified `assistant/thinking-delta` as cumulative snapshots and defined the append-vs-redraw rule over the effective next text.
- Split mandatory scope to assistant/thinking/finalization, with tool rows, widget subscriptions, and projection conditional on touched code or material measurement.

## 2026-05-22 — inconsistency review
Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, existing implementation notes, and referenced Emacs hot-path files/tests for cross-file inconsistency. No new actionable inconsistencies found: `plan.md`/`steps.md` consistently keep the task design-only until review follow-ups are complete, and all prior design follow-up items are already checked off.

## 2026-05-22 — inconsistency follow-up execution
Checked `design-steps.md` after the inconsistency review. No newly added unchecked actionable design follow-up items were present, so no design, plan, or step changes were required and no implementation steps were executed.
