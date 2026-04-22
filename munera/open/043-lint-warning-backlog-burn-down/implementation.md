2026-04-22
- Task created after `042` cleared hard lint errors but exposed a large warning backlog.
- Starting point:
  - `bb fmt:check` passes
  - `bb lint` reports `errors: 0`
  - `bb lint` still exits non-zero because warnings remain and the repo treats warnings as failing
- Initial dominant categories:
  - unused requires
  - unused bindings
  - unused private vars
  - unresolved namespace warnings in split TUI files and some tests
- Execute as an area-based warning burn-down rather than a single noisy sweep.

2026-04-22
- TUI slice:
  - removed dead top-level `psi.tui.app` aliases left behind by the split-file extraction (`ui-actions`, duplicate `input-pos`, unused tool style defs)
  - trimmed unused helpers/requires from `app_input_selector_test.clj`, `app_test.clj`, and `app_view_runtime_test.clj`
  - this did not fix the remaining split-file `in-ns` unresolved-namespace warnings; those are still present and appear to need either a lint config approach or a different extraction shape
  - `bb lint` warning count moved from `235` to `213`
