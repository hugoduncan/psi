# Steps

- [x] Add focused assistant streaming tests in `psi-streaming-transcript-test.el` for append-only incremental delta, extending cumulative snapshot, tail-churn redraw fallback, divergent merge preservation, and finalization.
- [x] Add focused thinking streaming tests in `psi-streaming-transcript-test.el` for extending cumulative snapshot append, divergent/shrinking redraw fallback, no duplicate thinking content, and archive interaction.
- [x] Add narrow test instrumentation proving append-only assistant/thinking events avoid post-creation full redraw and do not recreate prefix overlays; assistant stream properties apply only to suffix ranges, while thinking append proof records the inserted suffix mutation range unless a thinking content-property wrapper is introduced.
- [x] Run the focused streaming transcript tests and record the expected pre-optimization failure/observation in `implementation.md`.
- [x] Refactor `psi-assistant-render.el` to separate create, append-suffix, and full-redraw paths for assistant live lines.
- [x] Refactor `psi-assistant-render.el` to separate create, append-suffix, and full-redraw paths for thinking live lines.
- [x] Preserve existing assistant merge semantics and thinking cumulative snapshot semantics while routing effective next text through append-vs-redraw selection.
- [x] Ensure stream-time text properties are applied only to newly inserted assistant suffix text on append-only updates.
- [x] Ensure prefix overlays are created on initial create/full redraw only, not on append-only suffix updates.
- [x] Verify finalization, thinking archive, draft anchor, and marker-safety behavior with focused streaming tests.
- [x] Decide whether tool-row, widget-subscription, or projection hotspots need changes; if not, record the no-broadening decision in `implementation.md`.
- [x] If any conditional hotspot is changed, add or update its required focused tests before implementation. (Not applicable: no conditional hotspots changed.)
- [x] Run the mandatory focused Emacs test command and record results in `implementation.md`.
- [x] Run `bb emacs:test` before closing and record results in `implementation.md`.
- [x] Commit the implementation with task notes updated.
- [ ] Add `components/emacs-ui/test/psi-streaming-render-optimization-test.el` to `bb emacs:test` or move its proof tests into a loaded suite, then rerun/record the focused and full Emacs verification counts.
