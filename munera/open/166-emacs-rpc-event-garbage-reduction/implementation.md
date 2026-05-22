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

## 2026-05-22 — ambiguity review
Reviewed `design.md`, placeholder `plan.md`/`steps.md`, `design-steps.md`, existing implementation notes, and referenced Emacs streaming/render code/tests. New actionable ambiguity: the placeholder execution artifacts still say the task is design-only until the design review loop is complete, while all existing design follow-ups are checked; the task does not state the explicit promotion condition/owner for replacing the placeholder `plan.md` and `steps.md` with implementation-ready artifacts.

## 2026-05-22 — ambiguity follow-up execution
Defined the promotion gate and owner in placeholder `plan.md` and `steps.md`: the design-review workflow owns promotion, and promotion occurs only after a full ambiguity-and-inconsistency review cycle adds no new actionable design feedback and all `design-steps.md` items are checked or explicitly blocked in `implementation.md`. Marked the final ambiguity follow-up complete. No implementation steps were executed.

## 2026-05-22 — inconsistency review
Reviewed `design.md`, placeholder `plan.md`/`steps.md`, `design-steps.md`, existing implementation notes, and referenced Emacs RPC/rendering code/tests for cross-file inconsistencies. No new actionable inconsistencies found: mandatory vs conditional hotspot scope, append-vs-redraw behavior, proof requirements, and placeholder promotion gate are consistent across the task artifacts; existing `design-steps.md` items already cover prior follow-up and remain checked.

## 2026-05-22 — inconsistency follow-up execution
Checked the preloaded inconsistency-review result and `design-steps.md`. No newly added unchecked actionable design follow-up items were present, so no design, plan, or step changes were required and no implementation steps were executed.

## 2026-05-22 — implementation planning
Replaced placeholder `plan.md` and `steps.md` after the delegated review cycle completed with no open design follow-ups. The implementation plan narrows the first slice to mandatory assistant/thinking streaming optimization, with tool-row, widget-subscription, and projection work explicitly conditional on touched code or measured need. Verification commands are recorded in `plan.md`; implementation must record actual results here before closure.

## 2026-05-22 — ambiguity review
Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, existing implementation notes, and referenced Emacs streaming/render code/tests. New actionable ambiguity: the plan requires pre-change tests to instrument narrow local helpers for full-redraw/property/overlay counts, but the current code has only monolithic `psi-emacs--set-assistant-line` / `psi-emacs--set-thinking-line`; the task does not specify whether the first implementation slice should introduce stable instrumentation seams before behavior-changing optimization or use brittle advice around primitives such as `delete-region`, `add-text-properties`, and `make-overlay`.

## 2026-05-22 — ambiguity follow-up execution
Defined the pre-optimization instrumentation seam. The committed proof interface should be named helper wrappers in `psi-assistant-render.el`, not primitive-level advice: a full-live-block redraw wrapper, a stream property application wrapper with explicit ranges, and the existing `psi-emacs--apply-prefix-overlay` prefix-overlay wrapper. Updated `design.md` and `plan.md`, then marked the ambiguity follow-up complete in `design-steps.md`. No implementation steps from `steps.md` were executed.

## 2026-05-22 — inconsistency review
Reviewed `design.md`, implementation-ready `plan.md`/`steps.md`, `design-steps.md`, existing implementation notes, and referenced Emacs streaming/render tests/code (`psi-assistant-render.el`, `psi-streaming-transcript-test.el`). No new actionable inconsistencies found: mandatory assistant/thinking scope, conditional hotspot handling, pre-optimization helper instrumentation seam, append-vs-redraw contract, and verification steps are aligned across task artifacts. Existing `design-steps.md` items remain complete; no new follow-up items were added.

## 2026-05-22 — inconsistency follow-up execution
Used the preloaded inconsistency-review result and checked `design-steps.md`. No newly added unchecked actionable design follow-up items were present; all design follow-up steps remain complete. No `design.md`, `plan.md`, or `steps.md` changes were required, and no implementation steps from `steps.md` were executed.

## 2026-05-22 — ambiguity review
Reviewed `design.md`, implementation-ready `plan.md`/`steps.md`, `design-steps.md`, existing implementation notes, and referenced Emacs streaming/render code/tests (`psi-assistant-render.el`, `psi-streaming-transcript-test.el`, plus conditional hotspot test files by name). No new actionable ambiguities found: the task now has explicit mandatory vs conditional scope, append-vs-redraw contracts, helper-wrapper instrumentation seam, execution steps, verification commands, and closure-note requirements. Existing `design-steps.md` items remain complete; no new follow-up items were added.

## 2026-05-22 — ambiguity follow-up execution
Used the preloaded ambiguity-review result and checked `design-steps.md`. No newly added unchecked actionable ambiguity follow-up items were present; all design follow-up steps remain complete. No `design.md`, `plan.md`, or `steps.md` changes were required, and no implementation steps from `steps.md` were executed.

## 2026-05-22 — inconsistency review
Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, existing implementation notes, and referenced assistant streaming code/tests (`psi-assistant-render.el`, `psi-streaming-transcript-test.el`, `psi-events.el`) for cross-file inconsistencies. New actionable inconsistency: `design.md` acceptance still names "divergent snapshot fallback" for assistant streaming, while the later assistant payload contract, `plan.md`, and `steps.md` require preserving existing divergent assistant semantics by merging/appending (`"Hello"` + `"Goodbye"` → `"HelloGoodbye"`) after computing the effective next text. The acceptance wording should be corrected so tests do not implement a redraw/replacement fallback for divergent assistant payloads.

## 2026-05-22 — inconsistency follow-up execution
Used the preloaded inconsistency-review result and completed the newly added design follow-up. Updated `design.md` acceptance wording so divergent assistant payloads require existing merge preservation after effective-next-text calculation, not redraw/replacement fallback. Marked the design-step complete. No implementation steps from `steps.md` were executed.

## 2026-05-22 — ambiguity review
Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, existing implementation notes, and referenced assistant streaming/render code/tests (`psi-assistant-render.el`, `psi-streaming-transcript-test.el`). No new actionable ambiguities found: the artifacts now clearly define mandatory assistant/thinking scope, conditional hotspot triggers, append-vs-redraw semantics after effective-next-text calculation, named helper instrumentation seams, ordered implementation steps, and focused/full verification expectations. Existing `design-steps.md` items remain complete; no new follow-up items were added.

## 2026-05-22 — ambiguity follow-up execution
Used the preloaded ambiguity-review result and checked `design-steps.md`. No newly added unchecked actionable ambiguity follow-up items were present; all design follow-up steps remain complete. No `design.md`, `plan.md`, or `steps.md` changes were required, and no implementation steps from `steps.md` were executed.

## 2026-05-22 — inconsistency review
Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, existing implementation notes, and referenced assistant streaming/render code/tests (`psi-assistant-render.el`, `psi-streaming-transcript-test.el`, `psi-events.el`) for cross-file inconsistencies. New actionable inconsistency: `design.md` implementation shaping notes still say the split renderer should use "full redraw for replacement/divergent payloads", which conflicts with the assistant payload contract, `plan.md`, and `steps.md` requiring divergent assistant payloads to preserve existing merge/append semantics after effective-next-text calculation. The shaping note should distinguish assistant divergent payloads from thinking divergent snapshots or otherwise state redraw applies only after effective-next-text is non-append.

## 2026-05-22 — inconsistency follow-up execution
Used the preloaded inconsistency-review result and completed the newly added design follow-up. Updated the implementation shaping notes in `design.md` so append-vs-redraw is decided after effective-next-text calculation: divergent assistant payloads preserve existing merge semantics before path selection, redraw applies only when the effective next text is non-append, and thinking divergent/shrinking snapshots still use redraw fallback. Marked the design-step complete. No implementation steps from `steps.md` were executed.
