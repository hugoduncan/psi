Goal: complete the TUI slash-command autocomplete workflow requested in GitHub issue #24 by making the existing TUI autocomplete behavior visible and discoverable to the user.

Issue provenance:
- GitHub issue: #24
- Title: `tui: Add autocomplete for / commands`
- URL: https://github.com/hugoduncan/psi/issues/24
- Request text: `Would reduce typing and help with discoverability`

Intent:
- satisfy the issue’s user-facing goal in the TUI, not just internal autocomplete state updates
- preserve the existing prompt-input autocomplete semantics already implemented for slash commands
- make slash command discovery visible while keeping keyboard behavior simple and deterministic
- keep the change scoped to TUI rendering and closely related state needed to render the current autocomplete selection

Context:
- the TUI already has prompt autocomplete state and behavior in `psi.tui.app.autocomplete`
- focused tests already prove that:
  - typing `/` opens slash-command autocomplete state
  - slash candidates include built-in, prompt-template, skill, and extension commands
  - Enter/Tab acceptance works
  - Escape closes autocomplete
- the TUI render path currently does not expose the open autocomplete menu to the user
- because the menu is not visible, the existing behavior reduces some typing only incidentally and does not materially help discoverability
- the reusable spec `spec/prompt-input-autocomplete.allium` already defines a visible `AutocompleteMenu` concept and a maximum visible suggestion count of 5

Problem:
- issue #24 asks for slash-command autocomplete as a user-visible TUI capability
- current TUI behavior maintains autocomplete candidates internally but does not render them in the normal prompt view
- this leaves the feature effectively hidden, especially for discoverability, which is one of the explicit reasons given in the issue

Design decision:
- treat this issue as a completion/finalization task for existing TUI autocomplete rather than inventing new autocomplete semantics
- implement a visible autocomplete menu in the TUI prompt view using the existing autocomplete state
- render the same visible menu for any open autocomplete context, while keeping the issue-driven acceptance centered on slash commands
- do not change the existing acceptance/navigation semantics unless a small adjustment is required to make the visible menu correct

In scope:
- render a visible autocomplete menu in the TUI when autocomplete candidates are open
- show slash-command suggestions generated from the existing autocomplete state
- show which suggestion is currently selected
- limit the visible menu to the canonical maximum of 5 suggestions
- place the menu in a stable prompt-adjacent location in the normal TUI view so users can discover available slash commands while typing
- keep existing keyboard behavior working:
  - Up/Down move selection
  - Tab accepts selection
  - Enter accepts selection and submits when slash-command context requires it
  - Escape closes the menu
- add focused TUI-facing proof for rendering and selection visibility

Out of scope:
- redesigning autocomplete matching or ranking rules
- changing the underlying candidate sources for slash commands
- adding rich descriptions for commands that do not already have them
- backend/app-runtime changes
- changing non-TUI adapters
- broad autocomplete UX redesign beyond what is needed to make slash-command autocomplete visible and usable

Required behavior:
1. when the user types `/` at the start of the prompt and slash candidates exist, the TUI renders an autocomplete menu
2. the menu displays candidate command labels derived from the existing autocomplete state
3. the currently selected candidate is visibly distinguished from the others
4. no more than 5 suggestions are shown at once
5. when the selection changes with Up/Down, the rendered selected row changes accordingly
6. accepting a slash-command suggestion continues to use the existing command-acceptance behavior
7. when the menu is closed or there are no candidates, no autocomplete menu is rendered

Rendering constraints:
- the autocomplete menu should be rendered in the normal prompt area near the text input, not hidden in logs or footer state
- rendering may be adapter-specific, but it must be plain-text/ANSI friendly and visually clear in the TUI
- selected-row styling may use a textual marker, ANSI style, or both, but must be testable in stripped output
- the rendered menu must not replace or corrupt the existing input line; it should supplement it
- the menu must work within the existing terminal-width-sensitive TUI render path

Scope boundary for contexts:
- the render implementation may show the menu for slash-command, file-path, and file-reference autocomplete because they already share one state model
- however, task acceptance only requires proving the slash-command behavior requested by issue #24
- if a generic rendering path is used, it must not break existing non-slash autocomplete behavior

Acceptance:
- issue #24’s requested slash-command autocomplete is visibly present in the TUI
- typing `/` in the prompt shows a visible list of slash-command suggestions
- the selected suggestion is visibly indicated
- the menu is limited to 5 visible suggestions
- existing slash autocomplete keyboard acceptance behavior remains correct
- focused tests prove:
  - slash autocomplete menu is rendered when open
  - the selected suggestion is visibly marked
  - visible suggestions are capped at 5
  - changing selection updates the rendered menu
  - closed autocomplete state renders no menu

Why this design is complete and unambiguous:
- it ties the issue request to the missing user-visible behavior rather than internal state alone
- it explicitly preserves existing autocomplete semantics and limits the change to visible TUI completion behavior
- it states where the menu appears, how selection is shown, and what proof is required
- it resolves the main ambiguity in the issue body by defining autocomplete as a visible prompt-time suggestion menu, which is the only interpretation that satisfies both reduced typing and discoverability
