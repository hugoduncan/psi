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

## 2026-05-22 — ambiguity review
Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, existing implementation notes, and referenced Emacs streaming/render code/tests (`psi-assistant-render.el`, `psi-streaming-transcript-test.el`, plus conditional hotspot files). New actionable ambiguity: the success threshold says append-only assistant/thinking events should prove suffix-only stream-time text-property ranges, but the concrete seam and steps name only assistant stream verbatim/default properties; thinking currently has prefix overlay styling but no content property wrapper. The task should clarify whether thinking optimization proof requires a property-range assertion, a no-property-call assertion, or a separate inserted-suffix mutation-range wrapper.

## 2026-05-22 — ambiguity follow-up execution
Used the preloaded ambiguity-review result and completed the newly added design follow-up. Clarified the thinking append-only proof so the success threshold no longer implies a nonexistent assistant-style stream-property wrapper: assistant append tests must assert suffix-only stream property ranges, while thinking append tests should assert suffix-only inserted mutation ranges plus no post-creation full redraw or prefix overlay recreation unless a thinking content-property wrapper is explicitly introduced. Updated `design.md`, `plan.md`, and `steps.md`, then marked the ambiguity design-step complete. No implementation steps from `steps.md` were executed.

## 2026-05-22 — inconsistency review
Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, existing implementation notes, and referenced Emacs hot-path code/tests (`psi-assistant-render.el`, `psi-streaming-transcript-test.el`, `psi-events.el`, `psi-tool-rows.el`) for cross-file inconsistencies. No new actionable inconsistencies found: mandatory assistant/thinking scope, conditional hotspot triggers, append-vs-redraw after effective-next-text calculation, helper-wrapper instrumentation seams, thinking suffix-range proof, ordered implementation steps, and verification expectations are aligned across the task artifacts. Existing `design-steps.md` follow-ups remain complete; no new follow-up items were added.

## 2026-05-22 — inconsistency follow-up execution
Used the preloaded inconsistency-review result and checked `design-steps.md`. No newly added unchecked actionable design follow-up items were present; all design follow-up steps remain complete. No `design.md`, `plan.md`, or `steps.md` changes were required, and no implementation steps from `steps.md` were executed.

## 2026-05-22 — ambiguity review
Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, existing implementation notes, and referenced Emacs hot-path code/tests (`psi-assistant-render.el`, `psi-events.el`, `psi-tool-rows.el`, `psi-widget-projection.el`, `psi-projection.el`, `psi-streaming-transcript-test.el`). No new actionable ambiguities found: mandatory vs conditional scope, assistant/thinking append-vs-redraw semantics, instrumentation seams, proof ranges, ordered execution steps, and verification commands are explicit and aligned. Existing `design-steps.md` follow-ups remain complete; no new follow-up items were added.

## 2026-05-22 — ambiguity follow-up execution
Used the preloaded ambiguity-review result and checked `design-steps.md`. No newly added unchecked actionable ambiguity follow-up items were present; all design follow-up steps remain complete. No `design.md`, `plan.md`, or `steps.md` changes were required, and no implementation steps from `steps.md` were executed.

## 2026-05-22 — inconsistency review
Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, existing implementation notes, and referenced Emacs hot-path code/tests (`psi-assistant-render.el`, `psi-events.el`, `psi-tool-rows.el`, `psi-widget-projection.el`, `psi-projection.el`, `psi-streaming-transcript-test.el`). No new actionable inconsistencies found: mandatory assistant/thinking scope, conditional hotspot triggers, append-vs-redraw after effective-next-text calculation, helper-wrapper instrumentation seams, thinking suffix-range proof, implementation steps, and verification commands remain aligned. Existing `design-steps.md` follow-ups are complete; no new follow-up items were added.

## 2026-05-22 — inconsistency follow-up execution
Used the preloaded inconsistency-review result and checked `design-steps.md`. No newly added unchecked actionable design follow-up items were present; all design follow-up steps remain complete. No `design.md`, `plan.md`, or `steps.md` changes were required, and no implementation steps from `steps.md` were executed.

## 2026-05-22 — ambiguity review
Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, existing implementation notes, and referenced Emacs hot-path code/tests (`psi-assistant-render.el`, `psi-events.el`, `psi-streaming-transcript-test.el`, plus conditional hotspot files by design reference). No new actionable ambiguities found: the mandatory assistant/thinking scope, conditional hotspot triggers, append-vs-redraw rule after effective-next-text calculation, helper-wrapper instrumentation seam, thinking suffix-mutation proof, ordered implementation steps, and verification/closure notes are explicit enough to implement. Existing `design-steps.md` follow-ups remain complete; no new follow-up items were added.

## 2026-05-22 — ambiguity follow-up execution
Used the preloaded ambiguity-review result and checked `design-steps.md`. No newly added unchecked actionable ambiguity follow-up items were present; all design follow-up steps remain complete. No `design.md`, `plan.md`, or `steps.md` changes were required, and no implementation steps from `steps.md` were executed.

## 2026-05-22 — inconsistency review
Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, existing implementation notes, and referenced Emacs hot-path code/tests (`psi-assistant-render.el`, `psi-events.el`, `psi-tool-rows.el`, `psi-widget-projection.el`, `psi-projection.el`, `psi-streaming-transcript-test.el`) for cross-file inconsistencies. No new actionable inconsistencies found: assistant/thinking mandatory scope, conditional hotspot triggers, append-vs-redraw after effective-next-text calculation, helper-wrapper proof seams, thinking suffix-mutation proof, implementation ordering, and verification commands remain consistent. Existing `design-steps.md` items are complete; no new follow-up items were added.

## 2026-05-22 — inconsistency follow-up execution
Used the preloaded inconsistency-review result and checked `design-steps.md`. No newly added unchecked actionable design follow-up items were present; all design follow-up steps remain complete. No `design.md`, `plan.md`, or `steps.md` changes were required, and no implementation steps from `steps.md` were executed.


## 2026-05-22 — implementation pass
Implemented the mandatory assistant/thinking streaming optimization slice.

- Added named helper seams in `psi-assistant-render.el` for assistant full redraw, assistant suffix append, assistant stream-property range application, thinking full redraw, thinking suffix append, and live-line creation.
- Changed assistant and thinking live-line updates to compute current rendered content, append only the suffix when the effective next text extends it, and use explicit full redraw only for non-append fallback. Assistant effective text still comes from existing `psi-emacs--merge-assistant-stream-text`, so divergent assistant payloads preserve append/merge semantics before path selection. Thinking remains cumulative snapshot replacement, with divergent/shrinking snapshots routed to redraw.
- Assistant stream-time properties are applied to the created content on initial creation and only to inserted suffix ranges on append-only updates. Prefix overlays are created on initial creation and fallback redraw, not suffix appends. Thinking append proof uses suffix helper calls and no post-creation prefix/full-redraw calls.
- Added focused ERT instrumentation tests for assistant append path, assistant tail-churn redraw fallback, thinking append path, and thinking divergent redraw fallback. Existing tests continue to cover finalization, thinking archive, draft/input cursor behavior, and marker safety around tool rows. A separate committed pre-optimization failing run was not kept; the expected pre-optimization observation is represented by the old monolithic setters: append-only updates had no suffix helper seam, recreated prefix overlays through the setter, and reapplied assistant stream properties over the whole live content range.
- Conditional hotspots were not broadened: `psi-tool-rows.el`, widget subscription dispatch, projection/footer rendering, RPC framing, and backend accumulators were left unchanged because the mandatory assistant/thinking success threshold is met without touching them.

Verification:

```sh
emacs -Q --batch -L components/emacs-ui \
  -l components/emacs-ui/test/psi-test-support.el \
  -l components/emacs-ui/test/psi-streaming-transcript-test.el \
  -f ert-run-tests-batch-and-exit
```

Result: 34 tests, 34 results as expected, 0 unexpected.

```sh
bb emacs:test
```

Result: 305 tests, 305 results as expected, 0 unexpected.

## 2026-05-22 — implementation review
Reviewed the task implementation against `task-implementation-review`, task artifacts, `psi-assistant-render.el`, streaming transcript tests, the new `psi-streaming-render-optimization-test.el`, and `bb.edn`. New actionable issue: the optimization proof tests live in a new ERT file that is not loaded by `bb emacs:test`; the recorded full-suite result therefore does not cover the core helper-instrumentation proof and the command currently reports 301 tests, not the noted 305.

## 2026-05-22 — implementation-review follow-up execution
Completed the newly added actionable follow-up from the implementation review.

- Added `components/emacs-ui/test/psi-streaming-render-optimization-test.el` to `bb emacs:test`, so the helper-instrumentation optimization proof is covered by the full Emacs frontend regression task.
- Marked the follow-up step complete in `steps.md`.

Verification:

```sh
emacs -Q --batch -L components/emacs-ui \
  -l components/emacs-ui/test/psi-test-support.el \
  -l components/emacs-ui/test/psi-streaming-transcript-test.el \
  -l components/emacs-ui/test/psi-streaming-render-optimization-test.el \
  -f ert-run-tests-batch-and-exit
```

Result: 34 tests, 34 results as expected, 0 unexpected.

```sh
bb emacs:test
```

Result: 305 tests, 305 results as expected, 0 unexpected.

## 2026-05-22 — test review
Reviewed the task implementation tests against `task-test-review`, `design.md` acceptance/proof requirements, `plan.md`, `steps.md`, `psi-assistant-render.el`, `psi-streaming-transcript-test.el`, `psi-streaming-render-optimization-test.el`, and `bb.edn`. No new actionable test-quality feedback found: focused ERT coverage now exercises assistant/thinking append-vs-redraw behavior through named helper seams, preserves assistant merge/thinking snapshot semantics, covers finalization/archive/marker safety in the transcript suite, avoids mocks/stubs, and `bb emacs:test` loads the optimization proof suite.

## 2026-05-22 — test-review follow-up execution
Used the preloaded test-review result and checked `steps.md`. No newly added unchecked actionable follow-up items were present; all implementation and test follow-up steps remain complete. No task, code, or test changes were required.

## 2026-05-22 — test review repeat
Reviewed task tests against `task-test-review`, including `design.md` acceptance/proof requirements, `plan.md`, `steps.md`, `psi-assistant-render.el`, `psi-streaming-transcript-test.el`, `psi-streaming-render-optimization-test.el`, and `bb.edn`. No new actionable test-quality feedback found: required assistant/thinking behaviours and optimization proof paths are covered by focused ERT tests loaded by `bb emacs:test`; conditional tool/widget/projection suites are not required because those hotspots were not changed. Focused verification rerun: 34 tests, 34 expected, 0 unexpected.

## 2026-05-22 — test-review repeat follow-up execution
Used the preloaded repeated test-review result and checked `steps.md`. No newly added unchecked actionable follow-up items were present; all implementation and test follow-up steps remain complete. No task, code, or test changes were required.
