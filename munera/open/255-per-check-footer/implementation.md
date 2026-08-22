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
