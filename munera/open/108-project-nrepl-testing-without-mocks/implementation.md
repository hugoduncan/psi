2026-05-07

Task created as a follow-on quality slice from `107-project-nrepl-component-extraction`.

Creation rationale:
- the component extraction in `107` appears complete and structurally sound
- however, the component-local tests retained several `with-redefs` seam patches that do not match the repository's `testing-without-mocks` guidance
- this follow-on isolates test-shaping work from the already-landed extraction so the boundary stays stable while the tests are improved

Initial audit of `components/project-nrepl/test/psi/project_nrepl/`:
- `runtime_test.clj`
  - keep largely as-is; already sociable/state-based
- `eval_test.clj`
  - keep largely as-is; already close to target style because it uses an in-memory client-session function and asserts on result/state
- `config_test.clj`
  - improve; current `resolve-config` tests redefine config readers
- `client_test.clj`
  - redesign; currently redefines `nrepl.core/connect`, `nrepl.core/client`, and `nrepl.core/client-session`
- `attach_test.clj`
  - redesign; currently redefines `psi.project-nrepl.client/connect-instance-in!`
- `started_test.clj`
  - redesign; currently redefines `start-process!` and `psi.project-nrepl.client/connect-instance-in!`
- `commands_test.clj`
  - redesign; currently redefines `psi.project-nrepl.config/resolve-config`, `psi.project-nrepl.ops/eval-op`, and `psi.project-nrepl.ops/interrupt`

Observed `with-redefs` locations at task creation time:
- `components/project-nrepl/test/psi/project_nrepl/config_test.clj`
- `components/project-nrepl/test/psi/project_nrepl/client_test.clj`
- `components/project-nrepl/test/psi/project_nrepl/attach_test.clj`
- `components/project-nrepl/test/psi/project_nrepl/started_test.clj`
- `components/project-nrepl/test/psi/project_nrepl/commands_test.clj`

Design intent for implementation:
- introduce only the smallest production-owned infrastructure wrappers needed
- prefer wrappers around true infrastructure boundaries rather than wrappers around local business logic
- keep assertions at the component boundary on returned values and updated runtime state
- document any remaining justified exception if one cannot be removed cleanly without a larger redesign
