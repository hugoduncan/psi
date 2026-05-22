# Steps

- [ ] Add focused assistant streaming tests in `psi-streaming-transcript-test.el` for append-only incremental delta, extending cumulative snapshot, tail-churn redraw fallback, divergent merge preservation, and finalization.
- [ ] Add focused thinking streaming tests in `psi-streaming-transcript-test.el` for extending cumulative snapshot append, divergent/shrinking redraw fallback, no duplicate thinking content, and archive interaction.
- [ ] Add narrow test instrumentation proving append-only assistant/thinking events avoid post-creation full redraw and do not recreate prefix overlays; assistant stream properties apply only to suffix ranges, while thinking append proof records the inserted suffix mutation range unless a thinking content-property wrapper is introduced.
- [ ] Run the focused streaming transcript tests and record the expected pre-optimization failure/observation in `implementation.md`.
- [ ] Refactor `psi-assistant-render.el` to separate create, append-suffix, and full-redraw paths for assistant live lines.
- [ ] Refactor `psi-assistant-render.el` to separate create, append-suffix, and full-redraw paths for thinking live lines.
- [ ] Preserve existing assistant merge semantics and thinking cumulative snapshot semantics while routing effective next text through append-vs-redraw selection.
- [ ] Ensure stream-time text properties are applied only to newly inserted assistant suffix text on append-only updates.
- [ ] Ensure prefix overlays are created on initial create/full redraw only, not on append-only suffix updates.
- [ ] Verify finalization, thinking archive, draft anchor, and marker-safety behavior with focused streaming tests.
- [ ] Decide whether tool-row, widget-subscription, or projection hotspots need changes; if not, record the no-broadening decision in `implementation.md`.
- [ ] If any conditional hotspot is changed, add or update its required focused tests before implementation.
- [ ] Run the mandatory focused Emacs test command and record results in `implementation.md`.
- [ ] Run `bb emacs:test` before closing and record results in `implementation.md`.
- [ ] Commit the implementation with task notes updated.
