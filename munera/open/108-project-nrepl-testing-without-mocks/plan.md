Approach:
- treat this as a test-shaping task with minimal production seam shaping, not as a behavior change or component-boundary redesign
- follow the `testing-without-mocks` skill: prefer sociable/state-based tests, real temp filesystem surfaces, and thin nullable wrappers for infrastructure
- remove `with-redefs` from the component-local `project-nrepl` tests by replacing mock-style var patching with explicit production-owned seams for nREPL connection and process startup only where needed

Initial audit classification:
- keep:
  - `runtime_test.clj`
  - most of `eval_test.clj`
- improve:
  - `config_test.clj`
- redesign around nullable infrastructure seams:
  - `client_test.clj`
  - `attach_test.clj`
  - `started_test.clj`
  - `commands_test.clj`

Planned seam strategy:
1. inspect `psi.project-nrepl.client`, `psi.project-nrepl.started`, `psi.project-nrepl.attach`, `psi.project-nrepl.commands`, and `psi.project-nrepl.config` for the smallest production-owned wrappers needed
2. prefer one obvious wrapper per infrastructure boundary:
   - nREPL connect/client/session wrapper for external `nrepl.core`
   - process-launch wrapper for `ProcessBuilder` / process startup
3. keep wrapper APIs expressed in the component's own domain terms and support deterministic nullable behavior in tests
4. avoid creating test-only abstraction layers or simply relocating `with-redefs` one level down

Execution sequence:
1. review current source seams in the `psi.project-nrepl.*` implementation namespaces
2. define minimal nullable-wrapper shape for nREPL infrastructure
3. define minimal nullable-wrapper shape for process startup infrastructure
4. refactor `client_test.clj` to use the new wrapper/nullables
5. refactor `attach_test.clj` to use real attach behavior over nullable connect infrastructure
6. refactor `started_test.clj` to use real started behavior over nullable process/connect infrastructure
7. refactor `config_test.clj` away from reader var patching if feasible via real file-backed config or explicit config-source seam
8. refactor `commands_test.clj` to use real command behavior plus runtime/config state and nullable infra wrappers
9. re-run focused `project-nrepl` tests
10. record final strategy and any justified remaining exception in `implementation.md`

Verification intent:
- focused component tests under `components/project-nrepl/test/psi/project_nrepl/` should remain green
- if command-test ownership changes, keep one explicit higher-level routing proof above the component boundary and verify it separately only if needed
