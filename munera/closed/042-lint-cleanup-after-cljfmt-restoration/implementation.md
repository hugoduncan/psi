2026-04-22
- Task created after task `041` restored formatting enforcement and exposed that `bb lint` was still red.
- Initial blocking problems observed:
  - invalid lint path entry `specs`
  - unresolved symbol/namespace lint errors in extracted TUI files loaded into `psi.tui.app`
  - a small number of blocking test-file unresolved symbol reports
  - substantial additional warning noise from unused requires/bindings
- Execute by fixing hard errors first, then trimming adjacent warnings only as needed to get `bb lint` green.

2026-04-22
- Fixed hard lint errors first:
  - removed invalid `specs` path entries from `deps.edn` `:fmt`/`:lint` surfaces
  - made the split TUI files lint-visible by adding the minimal declarations/imports needed by `clj-kondo`:
    - forward `declare` forms in `components/tui/src/psi/tui/app.clj`
    - `declare input-value` in `app_autocomplete.clj`
    - `declare title-style dim-style` in `app_render.clj`
    - local `import` for `LinkedBlockingQueue` / `TimeUnit` in `app_support.clj`
  - rewrote the two test cases that still triggered unresolved-symbol `dir` reports to allocate temp directories directly in the test body instead of relying on the helper macro shape that `clj-kondo` was not following there
- Verification after those fixes:
  - `bb fmt:check` passes
  - `bb lint` no longer reports any errors
- Remaining state:
  - `bb lint` still exits non-zero because the repository has a large pre-existing warning backlog (`235` warnings)
  - biggest buckets are unused requires, unused bindings, unresolved namespace references, and unused private vars across many existing files
- This task therefore cleared the hard failures and exposed that warning cleanup is itself a larger repo-wide slice than the initial post-`041` expectation.
