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

2026-05-13

Actionable missing-config behaviour check:
- checked the real `project-repl start` path in a temp worktree with no project-nREPL config, rather than patching `ops/start`
- current behaviour is intentionally non-throwing at the operation boundary: `psi.project-nrepl.ops/start` returns `{:status :missing-start-command ...}`
- strengthened that payload to carry actionable fields directly:
  - `:phase :config`
  - `:message` with exact config key, searched file locations, and example EDN
  - `:hint` summarising the fix
  - `:example-config` as structured data
- reshaped the command-layer message formatter to use the operation payload's canonical `:message` instead of duplicating the string template
- added focused proofs for both surfaces:
  - `psi.project-nrepl.ops-test` now exercises a real temp worktree with no config and asserts the structured actionable payload
  - `psi.project-nrepl.commands-test` now exercises `/project-repl start` in a real temp worktree and asserts the user-facing actionable message
  - `psi.agent-session.tools-test` now exercises `psi-tool` `project-repl start` in a real temp worktree and asserts the structured missing-config payload

2026-06-01

Re-audit before execution (orientation pass; working tree clean, no task work committed yet):

Confirmed `with-redefs` footprint in `components/project-nrepl/test/psi/project_nrepl/` and the exact symbols each test replaces:

- `config_test.clj` (2) — `read-user-config`, `read-project-preferences`, `resolve-config`
- `client_test.clj` (1) — `nrepl.core/connect`, `nrepl.core/client`, `nrepl.core/client-session`
- `attach_test.clj` (2) — `psi.project-nrepl.client/connect-instance-in!`
- `started_test.clj` (2) — `start-process!`, `connect-instance-in!`
- `commands_test.clj` (2) — `psi.project-nrepl.config/resolve-config`, `psi.project-nrepl.ops/eval-op`, `psi.project-nrepl.ops/interrupt`
- `ops_test.clj` (2) — `psi.project-nrepl.eval/eval-instance-in!` (×2)

Scope discrepancy to resolve before/while executing:
- design.md names FIVE files for de-mocking (config, client, attach, started, commands) but `ops_test.clj` also carries two `with-redefs` that replace an internal collaborator (`psi.project-nrepl.eval/eval-instance-in!`) to return canned `:success` / `:interrupted` op results. This is the same mock-style seam-patching the task targets.
- The earlier 2026-05-13 note already reshaped `ops-test` actionable-payload proofs but left these `eval-instance-in!` redefs in place.
- Decision needed: either (a) extend scope to include `ops_test.clj` (it shares the `eval`/process infra seam this task is introducing), or (b) explicitly mark it out of scope in design.md. Recommendation: include it — the nullable `eval`/client seam built for `client_test`/`commands_test` should naturally cover `ops_test`'s `eval-instance-in!` patching, so excluding it would leave a residual mock pocket in the same namespace family.

Source namespaces available to seam: `components/project-nrepl/src/psi/project_nrepl/{attach,client,commands,config,eval,ops,runtime,started}.clj`.

Prior exploration status: the 2026-05-13 actionable-config work is described in this file but the working tree is clean on branch `testing-without-mocks`, so none of the de-mocking refactor has landed; all `steps.md` items remain unchecked.
