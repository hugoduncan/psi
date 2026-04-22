2026-04-22
- Task created after task `041` restored formatting enforcement and exposed that `bb lint` was still red.
- Initial blocking problems observed:
  - invalid lint path entry `specs`
  - unresolved symbol/namespace lint errors in extracted TUI files loaded into `psi.tui.app`
  - a small number of blocking test-file unresolved symbol reports
  - substantial additional warning noise from unused requires/bindings
- Execute by fixing hard errors first, then trimming adjacent warnings only as needed to get `bb lint` green.
