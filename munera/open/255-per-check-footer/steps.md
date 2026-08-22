# Steps

## Slice 1 — User-facing contract

- [x] Add the optional per-command `:footer` string and its absent/empty behavior to the commit-checks config documentation in `doc/extensions.md`.
- [x] Add representative footer instructions to the existing repository-specific commit-checks example without replacing its real bb task commands.
- [x] Add an `[Unreleased]` `Added` entry to `CHANGELOG.md` for per-command commit-check footers before committing implementation code.
- [x] Verify the documentation and changelog distinguish per-section footers from the always-present global prompt trailer.

## Slice 2 — Executable prompt contract

- [x] Add a handler-level test with multiple failed commands proving each non-empty `:footer` appears after its own output and before the next failure section.
- [x] Add coverage proving a failed command without `:footer` emits no per-section footer.
- [x] Add coverage proving a failed command with `:footer ""` emits no per-section footer or extra footer content.
- [x] Assert the existing global footer appears exactly once after all failure sections when per-command footers are configured.
- [x] Strengthen or retain the existing no-footer failure test to prove legacy combined-prompt behavior remains unchanged.
- [x] Verify successful commands contribute neither failure sections nor configured footers.

## Slice 3 — Minimal mechanism

- [x] Update `run-command!` to retain each command's `:footer` in both normal and timeout result maps.
- [x] Update `render-failure-section` to append a non-empty footer verbatim after the truncated output block.
- [x] Preserve the existing section separator composition and the single global footer at the end of `build-prompt`.
- [x] Run the focused commit-checks tests and fix any behavioral failures with minimal changes.

## Slice 4 — Verification

- [x] Run Clojure lint on the changed commit-checks source and test paths and resolve all new findings.
- [x] Review the generated prompt ordering for populated, absent, empty, mixed-success, and timeout cases against `design.md`.
- [x] Re-read changed source, tests, docs, and changelog to verify they are coherent with every acceptance criterion.
- [x] Record implementation decisions, verification commands, and results in `implementation.md` as work proceeds.

## Implementation review follow-up

- [x] Add a handler-level timeout regression test proving a timed-out command's non-empty `:footer` is rendered after its timeout output and before the global trailer.

## Test review follow-up

- [x] Strengthen `failure-footers-are-rendered-per-failing-command-test` with exact section-boundary assertions showing that absent and empty-string `:footer` values add neither footer text nor an extra blank line before the next failure section.
- [x] Normalize the separator before a non-empty per-command `:footer` so a normally newline-terminated command output is followed immediately by its footer (as in the documented prompt shape), rather than by an unintended blank line; add an exact prompt-boundary assertion for that case.
- [x] Restore and cover the legacy blank separator after a failed command whose output does not end in a newline when it has no `:footer`; the current rendering omits the former per-section trailing newline, so the next failure section or global trailer follows immediately rather than after the documented blank separator.
- [x] Add a handler-level regression test for a non-empty per-command `:footer` with `:max-output-chars` truncation, proving the footer follows the truncated-output marker and still precedes the global trailer.
- [x] Add handler-level coverage for a multi-line non-empty per-command `:footer`, proving its complete text is rendered verbatim after that command's output and before the next section or global trailer.
- [x] Restore the existing blank separator after a failed command with a non-empty per-command `:footer`; rendering currently leaves only one newline before the next failure section or global trailer, contrary to the documented prompt shape. Update the exact boundary tests to protect the separator.

## Documentation review follow-up

- [x] Reconcile `doc/extensions.md`'s commit-checks example with the checked-in `.psi/commit-checks.edn`: the documentation presents the repository-specific `file-lengths` command with a `:footer`, but the actual config has no command footer (and includes additional commands omitted by the snippet). Either add the documented footer to the config or explicitly label/correct the snippet as illustrative so users are not told it is the repository's configuration.
- [ ] Document the optional per-command `:footer` prompt instruction in `ramora/develop/setup-hooks.md`'s commit-checks guidance, including that it is emitted only for its failed check; this AI-facing setup reference currently describes `.psi/commit-checks.edn` but omits the new configuration surface.
