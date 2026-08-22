# Steps

## Slice 1 — User-facing contract

- [x] Add the optional per-command `:footer` string and its absent/empty behavior to the commit-checks config documentation in `doc/extensions.md`.
- [x] Add representative footer instructions to the existing repository-specific commit-checks example without replacing its real bb task commands.
- [x] Add an `[Unreleased]` `Added` entry to `CHANGELOG.md` for per-command commit-check footers before committing implementation code.
- [x] Verify the documentation and changelog distinguish per-section footers from the always-present global prompt trailer.

## Slice 2 — Executable prompt contract

- [ ] Add a handler-level test with multiple failed commands proving each non-empty `:footer` appears after its own output and before the next failure section.
- [ ] Add coverage proving a failed command without `:footer` emits no per-section footer.
- [ ] Add coverage proving a failed command with `:footer ""` emits no per-section footer or extra footer content.
- [ ] Assert the existing global footer appears exactly once after all failure sections when per-command footers are configured.
- [ ] Strengthen or retain the existing no-footer failure test to prove legacy combined-prompt behavior remains unchanged.
- [ ] Verify successful commands contribute neither failure sections nor configured footers.

## Slice 3 — Minimal mechanism

- [ ] Update `run-command!` to retain each command's `:footer` in both normal and timeout result maps.
- [ ] Update `render-failure-section` to append a non-empty footer verbatim after the truncated output block.
- [ ] Preserve the existing section separator composition and the single global footer at the end of `build-prompt`.
- [ ] Run the focused commit-checks tests and fix any behavioral failures with minimal changes.

## Slice 4 — Verification

- [ ] Run Clojure lint on the changed commit-checks source and test paths and resolve all new findings.
- [ ] Review the generated prompt ordering for populated, absent, empty, mixed-success, and timeout cases against `design.md`.
- [ ] Re-read changed source, tests, docs, and changelog to verify they are coherent with every acceptance criterion.
- [ ] Record implementation decisions, verification commands, and results in `implementation.md` as work proceeds.
