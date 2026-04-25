Approach:
- treat this as a narrow TUI rendering completion task over already-existing autocomplete state and behavior
- implement one prompt-adjacent render helper for the open autocomplete menu
- keep the rendering generic over the existing autocomplete state model, but prove slash-command behavior specifically
- prefer minimal code movement: render from current state, preserve existing update logic

Implementation plan:
1. Identify the render seam
   - inspect `components/tui/src/psi/tui/app/render.clj` around the input rendering path
   - render the autocomplete menu only in the normal prompt view, not selector/dialog modes

2. Add prompt autocomplete menu rendering
   - read `[:prompt-input-state :autocomplete]`
   - render nothing when there are no candidates
   - otherwise render up to 5 visible candidates
   - visibly distinguish the selected row in stripped output
   - place the menu adjacent to the prompt input without replacing the input line

3. Add focused TUI-facing proof
   - add render tests for:
     - open slash autocomplete renders visible suggestions
     - selected suggestion is visibly marked
     - rendered suggestions are capped at 5
     - moving selection changes the rendered selected row
     - closed autocomplete state renders no menu

4. Run focused verification
   - run the affected TUI test namespaces first
   - record commands and outcomes in `implementation.md`

Risks / decisions:
- keep the marker/styling simple so tests can assert behavior on ANSI-stripped output
- avoid introducing scrolling/paging behavior for the menu in this slice; capping visible rows is sufficient for the issue
- avoid changing autocomplete navigation semantics unless rendering exposes a concrete correctness gap

5. Add tmux integration proof (follow-on to unit rendering slice)
   - add `send-text!` to harness: sends literal characters without pressing Enter (uses `-l` flag, no trailing Enter key)
   - add `send-key!` to harness: sends a named tmux key (e.g. "Down", "Escape") without literal text
   - add `run-slash-autocomplete-scenario!` to harness:
     - boot → ready marker
     - `send-text! "/"` → wait for `"Suggestions"` marker
     - assert `"▸ "` selected-row marker is present
     - `send-key! "Down"` → wait for `"▸ "` still present (selection moved)
     - `send-key! "Escape"` → brief pause → `send-line! "/quit"` → wait for java exit
   - refactor existing basic-scenario test assertion into shared `assert-scenario-result` helper
   - add `^:integration` `tui-tmux-slash-autocomplete-scenario-test` using the new scenario
   - run via `bb clojure:test:integration` with tmux available; record outcome in `implementation.md`

   Risks:
   - `send-text! "/"` races with TUI startup completion; the ready-marker wait before typing mitigates this
   - Escape behaviour depends on TUI statechart wiring; if Escape triggers session interrupt rather than autocomplete dismiss the scenario will diverge — check render path before running live
