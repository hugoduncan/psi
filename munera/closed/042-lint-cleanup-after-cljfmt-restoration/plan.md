Approach:
- Start from the current `bb lint` output and fix the hard errors first.
- Prefer root-cause fixes over lint suppression: make split TUI files self-describing to the linter, correct invalid lint paths, and fix genuinely broken unresolved references.
- Once hard errors are cleared, trim nearby warnings that are low-risk and high-signal, then rerun `bb lint` until green.

Likely steps:
1. Remove or correct invalid lint path entries that make `clj-kondo` fail immediately.
2. Fix TUI extracted-file lint errors by making cross-file references/imports visible to the linter.
3. Fix the current unresolved-symbol reports in test files.
4. Clean up adjacent unused requires/bindings introduced or exposed by the recent refactors.
5. Rerun `bb lint`, iterate on any remaining hard failures, and stop once the repo is green.

Risks / watchpoints:
- accidentally changing TUI runtime behavior while fixing lint visibility
- broad warning cleanup turning into a noisy unrelated refactor
- hiding real issues with suppressions where a direct fix is available
