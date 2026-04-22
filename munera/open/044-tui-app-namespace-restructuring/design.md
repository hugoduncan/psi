Goal: remove the `in-ns` + `load` split-file pattern from the TUI app and replace it with ordinary namespace structure that is lint-clean, locally comprehensible, and preserves current TUI behavior.

Context:
- task `043` reduced the lint backlog to a small remaining set
- the dominant remaining warning cluster is the TUI app split across `app.clj`, `app_autocomplete.clj`, `app_support.clj`, `app_update_helpers.clj`, and `app_render.clj`
- those files currently rely on `in-ns 'psi.tui.app` and `load` to act as one shared namespace
- this pattern blocks straightforward linting, obscures true module boundaries, and makes local reasoning harder because symbol resolution depends on load order instead of explicit requires
- the user has explicitly decided that this pattern should be removed rather than papered over with lint config

Problem:
- the TUI app’s current structure depends on cross-file hidden namespace state rather than explicit interfaces
- `clj-kondo` reports unresolved symbols and namespace-shape issues because the extracted files are not real namespaces
- the current pattern makes the TUI harder to change safely: ownership of helpers, rendering, input handling, and update logic is implicit and spread across files
- the project’s architectural guidance favors obvious paths and locally comprehensible code; `in-ns` violates both

Intent:
- restructure the TUI app into explicit namespaces with clear responsibilities and explicit requires
- preserve runtime behavior and existing tests while improving lintability and architectural clarity
- leave the TUI app in a shape where future extraction or extension does not depend on shared hidden namespace state

Scope:
- replace `in-ns` + `load` usage in the TUI app split files with ordinary `ns` forms and explicit dependencies
- decide and implement the target namespace boundaries for:
  - shared state/helpers
  - autocomplete
  - support/init/dialog handling
  - update logic
  - rendering/view logic
- update `app.clj` to become either a thin public composition module or the main owning namespace over smaller helpers
- update tests and any call sites to use the new namespace structure
- ensure lint and relevant tests pass after restructuring

Non-goals:
- redesigning TUI behavior, interaction model, or UI copy for reasons unrelated to namespace structure
- broad terminal UI feature work unrelated to removing `in-ns`
- changing adapter/runtime contracts outside what is necessary to support the namespace restructuring
- adding lint suppressions/config exceptions as the primary fix

Minimum concepts:
- explicit namespace ownership
- acyclic dependencies
- small public composition surface
- private implementation helpers behind clear module boundaries
- behavior-preserving extraction

Architectural constraints to follow:
- prefer one-way dependencies and obvious ownership
- keep code locally comprehensible
- avoid cycles between render/update/support modules
- preserve current TUI entry surface (`make-init`, `make-update`, `view`, `start!`) unless a smaller equivalent public surface is clearly better
- prefer addition/extraction over modification-by-entanglement

Possible target shapes:
1. Thin `psi.tui.app` facade over dedicated modules
   - `psi.tui.app.state`
   - `psi.tui.app.autocomplete`
   - `psi.tui.app.support`
   - `psi.tui.app.update`
   - `psi.tui.app.render`
   - `psi.tui.app` only composes public entry points
   - strongest local clarity; likely best long-term shape

2. Keep `psi.tui.app` as owner and extract helper namespaces with explicit imports
   - fewer public namespace changes
   - lower migration surface
   - still acceptable if dependency direction stays clean

3. Collapse back to one file
   - simplest lint fix mechanically
   - loses the intentional decomposition already present
   - not preferred unless explicit namespace split proves disproportionately costly

Preferred shape:
- prefer a thin `psi.tui.app` composition namespace with explicit helper namespaces, provided this can be done without cycles
- if cycle pressure appears, bias toward a smaller number of explicit namespaces rather than recreating implicit coupling in more files

Acceptance:
- no TUI app source file uses `in-ns` to participate in shared implementation state
- `app.clj` no longer uses `load` to assemble private implementation files
- TUI app source is lint-clean under ordinary namespace analysis
- relevant TUI tests pass
- `bb lint` warning count is reduced accordingly, ideally to zero once the remaining non-TUI warning is handled separately
- the resulting namespace structure makes symbol ownership and dependencies explicit

Notes:
- this task is structurally adjacent to `043` but should remain separate because it is architectural reshaping rather than simple warning trimming
- the remaining `psi_tool.clj` underscore namespace warning is not part of this task unless the implementation naturally touches that surface, which is unlikely
