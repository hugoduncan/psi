# Implementation notes

- no architectural review feedback
- ambiguity review added 2 new design steps (global-footer "fallback" wording contradicts Rules/example; empty-string `:footer` behavior unspecified)
- no new inconsistency review feedback (only inconsistency is the global-footer contradiction already tracked in design-steps.md)

## Addressing the design-steps

- design-steps item 1 (global-footer wording): the Rules + Acceptance + example output are the authoritative interpretation (global footer = single end-trailer; a command without `:footer` gets no per-section footer). Only the Design prose sentence "The global footer remains as a fallback for commands that do not specify one." is the outlier to rewrite — do not change the Rules/Acceptance/example.
- design-steps item 2 (empty-string `:footer`): the codebase idiom is `not-empty` — `build-prompt` already uses `(or (not-empty prompt-header) default-prompt-header)` and `render-failure-section` uses `(or (not-empty (str id)) ...)`. Treating an empty-string `:footer` as absent (no per-section footer) is the consistent choice; prefer it over rendering an empty line.
- Files to change: `extensions/commit-checks/src/extensions/commit_checks.clj` (`build-prompt`, `render-failure-section`); tests in `extensions/commit-checks/test/extensions/commit_checks_test.clj`.
