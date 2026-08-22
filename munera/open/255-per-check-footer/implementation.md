# Implementation notes

- no architectural review feedback
- ambiguity review added 2 new design steps (global-footer "fallback" wording contradicts Rules/example; empty-string `:footer` behavior unspecified)
- no new inconsistency review feedback (only inconsistency is the global-footer contradiction already tracked in design-steps.md)
- architectural review (2nd pass) added 1 new design step: design omits user-visible artifact obligations (doc/extensions.md config + CHANGELOG [Unreleased] entry) for the new `:footer` config key
- no new ambiguity review feedback (2nd pass): design is fully specified after the prior pass. Borderline non-string `:footer` coercion is below the actionable threshold — config is unvalidated EDN and the `str`+`not-empty` idiom (as used for `:id`) gives a single interpretation, so do not re-raise it.
- no new inconsistency review feedback (2nd pass): design is internally consistent and consistent with `commit_checks.clj` code structure and `doc/extensions.md`. The prior global-footer wording inconsistency was already resolved.

## Addressing the open design step (doc + changelog artifact)

- Doc target: `doc/extensions.md` commit-checks section — add `:footer` to the config-shape prose and the example config (a per-command optional string; empty/absent → no per-section footer). Keep the example consistent with design.md's example.
- Changelog target: `CHANGELOG.md` `[Unreleased]` → `### Added` — user-visible config addition (per-command `:footer` in `.psi/commit-checks.edn`). Entry must precede the code commit per the changelog rule.
- Principle: doc + changelog describe the *user-facing config surface*, not the internal `render-failure-section` threading — keep them at the config/behaviour level, matching how `:prompt-header`/`:max-output-chars` are documented.

## Addressing the design-steps

- design-steps item 1 (global-footer wording): the Rules + Acceptance + example output are the authoritative interpretation (global footer = single end-trailer; a command without `:footer` gets no per-section footer). Only the Design prose sentence "The global footer remains as a fallback for commands that do not specify one." is the outlier to rewrite — do not change the Rules/Acceptance/example.
- design-steps item 2 (empty-string `:footer`): the codebase idiom is `not-empty` — `build-prompt` already uses `(or (not-empty prompt-header) default-prompt-header)` and `render-failure-section` uses `(or (not-empty (str id)) ...)`. Treating an empty-string `:footer` as absent (no per-section footer) is the consistent choice; prefer it over rendering an empty line.
- Files to change: `extensions/commit-checks/src/extensions/commit_checks.clj` (`build-prompt`, `render-failure-section`); tests in `extensions/commit-checks/test/extensions/commit_checks_test.clj`.

## Design follow-up (this pass)

- Both design-steps executed and marked done; design.md is now fully specified (no open ambiguity/inconsistency).
- Item 1: rewrote only the Design prose sentence; Rules/Acceptance/example left untouched (they were already the authoritative reading).
- Item 2: added a Rules bullet + an Acceptance criterion stating empty-string `:footer` is treated as absent (no per-section footer).
- Structural fact for implementation: `run-command!` destructures `{:keys [id cmd timeout-ms]}` and its result map does **not** carry `:footer`, so the per-command `:footer` is currently dropped before `render-failure-section` sees it. The implementer must thread `:footer` from the config command into the failure result (or pass it alongside) — `render-failure-section`'s signature will need to change. Use the `not-empty` idiom so `:footer ""` is treated as absent.

## Architecture review (3rd pass)

- No new actionable architectural-fit feedback. The design is a clean, backward-compatible addition confined to the self-contained commit-checks extension's prompt rendering (`build-prompt` / `render-failure-section`); it touches no state, dispatch, resolvers, or cross-adapter surface, and the optional `:footer` key follows the existing unvalidated-EDN + `not-empty` config idiom.
- The sole architectural-fit obligation — `doc/extensions.md` config-shape/example + `CHANGELOG.md` `[Unreleased]` entry for the new user-visible `:footer` key — is already the open (unchecked) design-steps item; not re-raised.
- Naming clarification for later reviews: the design's `:footer` is a trailing instruction string in the injected commit-checks prompt, unrelated to the app-runtime "footer semantic model" in `doc/architecture.md` (a UI status-bar footer). No conflict; do not conflate.

## Ambiguity review (3rd pass)

- No new actionable ambiguity feedback. The design is fully specified after the prior passes. Per-section footer placement is pinned by the example output (footer line directly after the output block, then the existing blank-line section separator) and is consistent with the current `render-failure-section` + `str/join "\n"` structure — single interpretation. Footer applies only to failing checks; "after the output block" means after the (possibly truncated) output; `:footer` is used verbatim (plain string, no templating); per-section and global footers are explicitly separated. Non-string `:footer` coercion remains below the actionable threshold (unvalidated EDN + `str`/`not-empty` idiom).

## Inconsistency review (3rd pass)

- No new actionable inconsistency feedback. design.md is internally consistent (Goal/Design/Config/Prompt-output/Rules/Acceptance all agree on per-section vs. global footer semantics; the prior global-footer "fallback" contradiction was already resolved). Consistent with `commit_checks.clj` structure (`build-prompt` global end-trailer + `render-failure-section` per-section) and with `doc/extensions.md` (the doc's missing `:footer` is the open design-steps doc-update obligation, not a design claim). The design's example `:prompt-header` value differs from the code/doc default string — illustrative only, not a contradiction.

## Slice notes — addressing the open design step (doc + changelog)

- Doc example nuance: the `doc/extensions.md` commit-checks example is anchored to real repo bb tasks (`bb commit-check:rama-cc`, `bb commit-check:file-lengths`, `:id "rama-cc"`/`"file-lengths"`), whereas design.md's example uses generic `lint`/`test`/`format`. "Keep the example consistent with design.md's example" means add `:footer` to the existing repo-specific example entries (e.g. one of them), NOT replace the repo-specific example with design.md's generic one — the doc example documents the actual in-repo config.
- Relevant non-task files: `doc/extensions.md` (commit-checks section, config example ~line 184), `CHANGELOG.md` (`[Unreleased]` → `### Added`), `extensions/commit-checks/src/extensions/commit_checks.clj` (code), `extensions/commit-checks/test/extensions/commit_checks_test.clj` (tests).
- Doc has two surfaces, not just the example: the commit-checks "Behavior" prose bullet list (~line 170) AND the config example. The design-steps "config shape + example" phrasing reads as example-only, but the per-section footer behavior belongs as a new Behavior bullet (e.g. "an optional per-command `:footer` string is appended to that failing command's section") — update both.
- Demonstrate optionality in the example: add `:footer` to only one of the two existing entries (e.g. `file-lengths`), leaving the other without — mirrors the design's optional semantics and the "without `:footer`" acceptance criterion.

- no new ambiguity review feedback (plan/steps pass): plan and steps are unambiguous and consistent with design/code/docs; the open design-steps doc+changelog item is already tracked and addressed by Slice 1, not a new ambiguity
- no new inconsistency review feedback (plan/steps pass): plan.md and steps.md slice order, doc/changelog obligations, and test coverage are mutually consistent and consistent with design.md; the open design-steps doc+changelog item is addressed by Slice 1, not a contradiction

## Slice 1 — user-facing contract

- Added the optional per-command `:footer` contract to both the commit-checks behavior prose and the existing repository-specific example in `doc/extensions.md`; the `rama-cc` command intentionally remains footer-less to demonstrate optionality.
- Added the required `[Unreleased]` `Added` changelog entry. Both surfaces distinguish the command-local footer from the always-present global prompt trailer.
- Verification: re-read the documentation and changelog changes. No Clojure behavior changed in this slice; Slice 2 must establish the executable prompt contract.

## Slices 2–3 — executable contract and mechanism

- `run-command!` now carries configured `:footer` through both regular and timeout result maps; `render-failure-section` appends only a non-empty footer after the (possibly truncated) output. The existing `build-prompt` global trailer remains unchanged.
- Added a handler-level sociable test using real short-lived shell commands and the nullable extension API. It proves command-local footer placement, absence/empty-footer omission, successful-command omission, exactly one global trailer, and the legacy test now asserts the global trailer remains final.
- Verification: `clj-paren-repair extensions/commit-checks/src/extensions/commit_checks.clj extensions/commit-checks/test/extensions/commit_checks_test.clj`; `bb clojure:test:scry --namespace extensions.commit-checks-test` — 11 tests, 65 assertions passed.

## Slice 4 — verification

- Re-ran the focused Scry suite after strengthening the legacy assertion: 11 tests and 66 assertions passed. `clj-kondo --lint extensions/commit-checks/src/extensions/commit_checks.clj extensions/commit-checks/test/extensions/commit_checks_test.clj` completed with 0 errors and 0 warnings.
- Prompt-order review: populated footer is emitted after output; absent and empty footer are omitted; successful configured footer is filtered with its successful command; the global trailer occurs exactly once and is final. Timeout results carry `:footer` through the same result-map path as normal failures.
- Re-read implementation, tests, documentation, changelog, and all design acceptance criteria: coherent. No deviations or dependencies added.

- no new architectural review feedback (design-review first turn)
- no new ambiguity review feedback (design-review second turn)
- no new inconsistency review feedback (design-review third turn)
- Follow-up status: the sole unchecked doc/changelog design step is already delivered in `doc/extensions.md` and `CHANGELOG.md`; reconcile its checklist state rather than duplicating those edits.
- no new inconsistency review feedback (plan-review second turn): the pre-existing stale unchecked doc/changelog design-step is already identified above, while plan, read-only steps, design, implementation notes, and delivered artifacts otherwise agree.
- Design-step follow-up: the remaining item is checklist reconciliation only — preserve the existing `doc/extensions.md` behavior/example distinction and `CHANGELOG.md` `[Unreleased]` entry; do not duplicate or alter those delivered user-facing artifacts.

## Final implementation verification — 2026-08-22

- Re-ran the focused commit-checks Scry suite: 11 tests and 66 assertions passed; `clj-kondo` reported 0 errors and 0 warnings for the changed source and test namespaces.
- Checklist reconciliation is complete: all four implementation slices are marked done. No mechanism, API, dependency, or design changes were needed in this verification pass.

- added 1 step to be addressed
- addressed 1 review step: added truncated-output footer ordering coverage; focused Scry suite passed (12 tests, 77 assertions) and clj-kondo reported 0 errors and 0 warnings.
- added 1 step to be addressed
- addressed 1 review step: restored the no-footer trailing newline and covered newline-less absent/empty footer boundaries; focused Scry suite passed (12 tests, 75 assertions) and clj-kondo reported 0 errors and 0 warnings.
- addressed 1 review step: added timeout-footer ordering coverage; focused Scry suite passed (12 tests, 69 assertions) and clj-kondo reported 0 errors and 0 warnings.
- added 1 step to be addressed
- addressed 1 review step: strengthened exact absent/empty footer section-boundary assertions; focused Scry suite passed (12 tests, 73 assertions) and clj-kondo reported 0 errors and 0 warnings.
- added 1 step to be addressed
- addressed 1 review step: normalized newline-terminated output before a per-command footer and added exact prompt-boundary coverage; focused Scry suite passed (12 tests, 75 assertions) and clj-kondo reported 0 errors and 0 warnings.
- added 1 step to be addressed
- test-shaper review added 1 step to be addressed
- addressed 1 review step: added multi-line per-command footer verbatim ordering coverage; focused Scry suite passed (13 tests, 80 assertions) and clj-kondo reported 0 errors and 0 warnings.
