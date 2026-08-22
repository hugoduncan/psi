# Plan

## Approach

Implement the optional per-command `:footer` as data carried with each command result and rendered only inside that failing command's section. Preserve the existing combined-prompt structure and its single global end-trailer.

Key decisions:

- Thread `:footer` through `run-command!` into its result map so filtering failures does not lose the command-local configuration.
- In `render-failure-section`, append the footer after the possibly truncated output only when `(not-empty footer)` is truthy. Use the footer verbatim; do not interpolate it or replace the global trailer.
- Prove the full prompt behavior through the extension's existing handler-level tests: populated footer placement, absent footer, empty footer, global trailer position, and unchanged no-footer behavior.
- Document `:footer` on the user-facing commit-checks config surface and add an `[Unreleased]` changelog entry before committing the implementation.

## Risks

- `run-command!` currently drops unknown command keys, so changing only rendering would silently omit configured footers.
- Newline composition can accidentally add a blank per-section footer, collapse the separator between sections, or move/duplicate the global trailer. Exact prompt-order assertions should guard these boundaries.
- A footer must remain associated only with its own failed command; successful commands are filtered out and must contribute neither a section nor a footer.
- The documentation example describes real repository checks, so it should gain representative `:footer` values without being replaced by the design's generic illustrative commands.
- Non-string footer validation is outside this task: the stable design defines `:footer` as a plain string and only specifies absent and empty-string handling.

No known blockers.

## Slice order

1. **User-facing contract** — update `doc/extensions.md` with the optional per-command config key and behavior, update its repository-specific example, and add the required `[Unreleased]` changelog entry.
2. **Executable prompt contract** — extend commit-checks tests to cover populated, absent, and empty per-command footers; association with failing sections; preservation and final placement of the global trailer; and the legacy no-footer case.
3. **Minimal mechanism** — preserve `:footer` in command results and conditionally render it after each failed command's output without changing the global trailer.
4. **Verification** — run focused commit-checks tests, lint the changed Clojure paths, and review the resulting prompt composition and documentation against every acceptance criterion.
