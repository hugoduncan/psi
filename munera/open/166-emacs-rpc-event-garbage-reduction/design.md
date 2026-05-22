# 166 — Emacs RPC event garbage reduction

## Intent
Reduce garbage and CPU churn caused by `psi-emacs--handle-rpc-event` and its hot-path callees during streamed assistant, thinking, tool, projection, and widget events, while preserving visible Emacs frontend behavior.

## Problem
`psi-emacs--handle-rpc-event` currently routes high-frequency RPC stream events through handlers that often rebuild cumulative UI state instead of applying small deltas. The likely worst path is assistant/thinking streaming: each event may merge increasingly large strings, delete and reinsert the whole live transcript block, reapply text properties over the whole block, recreate prefix overlays, and then scan widget subscriptions. Tool-output and projection events have similar whole-block redraw behavior when payloads are large or frequent.

This creates allocation proportional to the accumulated response/output size per event rather than proportional to the new change. Long answers, reasoning streams, or expanded tool output can therefore cause O(n²)-style allocation and visible Emacs GC pauses.

## Scope
- Optimize the Emacs frontend event hot paths reached from `psi-emacs--handle-rpc-event`.
- Cover every changed behavior with focused ERT tests before or alongside implementation.
- Preserve current transcript rendering semantics for assistant deltas, cumulative assistant snapshots, thinking snapshots, tool rows, projection blocks, and widget event subscriptions.
- Prefer incremental updates where the incoming event extends existing rendered content.
- Avoid reapplying text properties, overlays, and buffer deletion/insertion to unchanged text.
- Keep fallback behavior for non-extension/rewrite cases where the incoming payload is not a simple append.
- Measure or instrument allocation-sensitive paths enough to prove the optimization target is exercised by tests.

## Out of scope
- Changing the RPC wire protocol unless the optimization cannot be made safely on the frontend alone.
- Broad replacement of EDN parsing or process-filter framing.
- Redesigning the Emacs transcript buffer model.
- Changing user-visible transcript formatting, markdown finalization, thinking display, tool row content, projection content, or widget behavior.
- Backend streaming accumulator changes unless a small frontend-preserving adjustment is clearly required and covered by tests.

## Acceptance
1. Focused tests cover the current assistant streaming behavior before optimization:
   - incremental delta append,
   - cumulative snapshot replacement/extension,
   - divergent snapshot fallback,
   - finalization after optimized streaming.
2. Focused tests cover thinking streaming behavior before optimization:
   - cumulative thinking snapshot replacement/extension,
   - no duplicate rendered thinking content,
   - archive/finalization interaction remains unchanged.
3. Focused tests cover tool-row update behavior for any optimized tool path:
   - collapsed mode remains header-only,
   - expanded mode still renders accumulated/output text,
   - large output updates do not corrupt neighboring assistant/tool markers.
4. Focused tests cover widget subscription dispatch if it is changed:
   - subscribed specs still receive matching events,
   - nonmatching high-frequency events do not trigger subscription application,
   - vector and list subscription shapes remain supported.
5. Focused tests cover projection/footer updates if projection rendering is changed:
   - projection block remains deterministic,
   - footer/session-tree/status updates remain visible,
   - transcript/input boundary markers remain correct.
6. The optimized assistant/thinking hot path applies append-only updates without deleting and reinserting the entire live block when the new payload is a pure extension.
7. The optimized hot path does not reapply text properties or recreate prefix overlays across unchanged assistant/thinking content on each append-only delta.
8. Existing fallback behavior remains for non-append updates: when a payload cannot be represented as a safe append, the frontend may still perform the current full redraw path.
9. Focused Emacs tests pass.
10. The implementation notes identify which suspected garbage sources were fixed, which were measured but left unchanged, and why.

## Design constraints
- Optimize only behavior that has focused test coverage.
- Preserve one obvious rendering rule: append when safe, redraw when necessary.
- Keep correctness above allocation reduction; marker/range corruption is worse than extra garbage.
- Avoid hidden compatibility shims. If a data contract is changed, make the contract explicit in code and tests.
- Prefer small local helpers that make append-vs-redraw decisions obvious.

## Initial hotspot inventory
Likely sources to inspect and cover before changing:

1. `components/emacs-ui/psi-events.el`
   - `psi-emacs--handle-rpc-event`
   - per-event routing and unconditional `psi-widget-projection-handle-event` call.
2. `components/emacs-ui/psi-assistant-render.el`
   - `psi-emacs--assistant-delta`
   - `psi-emacs--assistant-thinking-delta`
   - `psi-emacs--set-assistant-line`
   - `psi-emacs--set-thinking-line`
   - stream verbatim property application and prefix overlay handling.
3. `components/emacs-ui/psi-tool-rows.el`
   - `psi-emacs--upsert-tool-row`
   - expanded output rendering and ANSI/text-property handling.
4. `components/emacs-ui/psi-widget-projection.el`
   - `psi-widget-projection-handle-event`
   - subscription lookup/scanning for every event.
5. `components/emacs-ui/psi-projection.el`
   - `psi-emacs--upsert-projection-block` if frequent redraws are observed.
6. `components/emacs-ui/psi-rpc.el`
   - process-filter framing and EDN parse conversion are known allocation sources, but should remain out of scope unless frontend rendering fixes are insufficient.

## Proof requirements
Minimum focused test targets:

- `components/emacs-ui/test/psi-streaming-transcript-test.el`
  - assistant delta append/snapshot/fallback behavior,
  - thinking snapshot behavior,
  - marker/range safety around tools.
- `components/emacs-ui/test/psi-tool-output-mode-test.el`
  - tool-row rendering behavior if tool row code changes.
- `components/emacs-ui/test/psi-widget-projection-events-test.el`
  - widget subscription dispatch behavior if subscription lookup changes.
- `components/emacs-ui/test/psi-session-tree-test.el` or adjacent projection tests
  - projection/footer/session-tree behavior if projection code changes.

Suggested verification command:

```sh
./components/emacs-ui/run-tests.sh \
  components/emacs-ui/test/psi-streaming-transcript-test.el \
  components/emacs-ui/test/psi-tool-output-mode-test.el \
  components/emacs-ui/test/psi-widget-projection-events-test.el \
  components/emacs-ui/test/psi-session-tree-test.el
```

If the local Emacs test runner uses a different invocation, record the exact focused command in `implementation.md` before closing the task.

## Implementation shaping notes
- Add tests that can distinguish append-only update from full redraw without relying on brittle wall-clock timing. Prefer instrumentation of narrow helper calls, marker stability assertions, buffer content equality, and counters around delete/reinsert/property application helpers.
- A likely safe shape is to split assistant/thinking rendering into:
  - append-only update for extension payloads,
  - full redraw for replacement/divergent payloads,
  - finalization path that still handles markdown/font-lock after streaming ends.
- For assistant deltas, avoid double cumulative merge work where possible. The backend already emits accumulated text for `:text-delta`; frontend should either trust that contract or make suffix detection cheap and explicit.
- For thinking deltas, the backend contract is cumulative snapshots. The frontend can append only the new suffix when the snapshot extends the rendered thinking text, and redraw only when the snapshot diverges.
- Prefix overlays should be created once per live block where possible and retained across append-only updates.
- Stream-time verbatim/default face properties should be applied only to newly inserted suffix text where possible.
- Widget subscriptions can be indexed by event name if tests show the unconditional scan is material.
- Tool-row expanded output may need a separate optimization task if it requires broader output virtualization; do not broaden this task beyond covered local changes.
