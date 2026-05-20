# Steps — 161

- [x] 1. Rewrite `adopt-startup-plan-into-session!` to inline bootstrap responsibilities per target flow
- [x] 2. Remove `bootstrap-in!` redef from `with-main-bootstrap-stubs` in `app_runtime_test.clj` (no longer called)
- [x] 3. Remove `bootstrap-in!` redef from tests in `app_runtime_bootstrap_test.clj`
- [x] 4. Remove `bootstrap-in!` redef from `bootstrap-runtime-session-initial-context-has-single-session` test
- [x] 5. Remove `bootstrap-in!` redef from `bootstrap-runtime-session-passes-memory-runtime-opts-to-sync` test
- [x] 6. Remove `bootstrap-in!` redef from `bootstrap-runtime-session-enriches-system-prompt-with-capabilities` test
- [x] 7. Remove `bootstrap-in!` redef from `bootstrap-runtime-session-wires-nrepl-runtime-atom` test
- [x] 8. Run all app-runtime tests — verify green (301/301)
- [x] 9. Run extension-install-startup tests — verify green
- [x] 10. Run bootstrap-extension-invariant tests — verify green
- [x] 11. Run full test suite — verify green (301/301)
- [x] 12. Lint clean
- [x] 13. Verify dispatch counts per design acceptance criteria
  - `:session/bootstrap-prompt-state` — 1 (developer-prompt seeding)
  - `:session/set-system-prompt` — 1 (single prompt build via persist-system-prompt!)
  - `:session/set-active-tools` — 1 (after build-opts stored)
  - `:session/refresh-system-prompt` — 1 (side-effect of set-active-tools; equivalent prompt)
  - `:session/set-startup-bootstrap-summary` — 1 (complete summary)
