# Steps — 161

- [ ] 1. Rewrite `adopt-startup-plan-into-session!` to inline bootstrap responsibilities per target flow
- [ ] 2. Remove `bootstrap-in!` redef from `with-main-bootstrap-stubs` in `app_runtime_test.clj` (no longer called)
- [ ] 3. Remove `bootstrap-in!` redef from tests in `app_runtime_bootstrap_test.clj`
- [ ] 4. Remove `bootstrap-in!` redef from `bootstrap-runtime-session-initial-context-has-single-session` test
- [ ] 5. Remove `bootstrap-in!` redef from `bootstrap-runtime-session-passes-memory-runtime-opts-to-sync` test
- [ ] 6. Remove `bootstrap-in!` redef from `bootstrap-runtime-session-enriches-system-prompt-with-capabilities` test
- [ ] 7. Remove `bootstrap-in!` redef from `bootstrap-runtime-session-wires-nrepl-runtime-atom` test
- [ ] 8. Run all app-runtime tests — verify green
- [ ] 9. Run extension-install-startup tests — verify green
- [ ] 10. Run bootstrap-extension-invariant tests — verify green
- [ ] 11. Run full test suite — verify green
- [ ] 12. Lint clean
- [ ] 13. Verify dispatch counts per design acceptance criteria
