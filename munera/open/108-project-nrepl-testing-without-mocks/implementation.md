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

2026-06-01 — Design ambiguity review

Read design.md plus testing-without-mocks SKILL, the five named test files, `ops_test.clj`, and the source namespaces (`config`, `client`, `started`, `eval`, `commands`, `ops`). Found new actionable ambiguities (added to `design-steps.md`):

- Seam-injection mechanism is unspecified: design mandates "thin wrapper" + forbids `with-redefs` but never states *how* the nullable nREPL-client / process-start seam is supplied at test time. `eval_test.clj` succeeds only because `client-session` is carried in runtime state; `client.clj` resolves `nrepl.core/*` inline via `requiring-resolve` and `started.clj`'s `start-process!` is private, so no analogous injection point exists yet. Implementers could choose incompatible vectors per file.
- `ops_test.clj` scope contradiction: 2026-06-01 re-audit found `with-redefs` on `eval-instance-in!` there and recommended inclusion, but design.md Problem audit and Acceptance still name only five files. In/out-of-scope status is undecided in design.
- `config_test.clj` `resolve-config` target shape undefined: real `read-project-preferences` returns `:version 1` (redef tests omit it), and an already-file-backed `read-project-preferences-test` exists; design does not say whether the merge-precedence proof becomes redundant, moves to a config-source seam, or which of the three preferred options applies to it.
- Acceptance wording "at least one note ... for nREPL and process infrastructure" is ambiguous between one combined note and per-seam strategy notes.
- `commands_test.clj` operational (`eval-op`/`interrupt`) target uses an "or" (real runtime+config vs. split formatting/parsing tests), leaving unclear whether command-layer operational routing must still be proven through real `eval-instance-in!` (eval_test style) or may be reduced to formatting tests.

2026-06-01 — Ambiguity follow-up execution

Resolved all five 2026-06-01 design-ambiguity follow-up items in design.md
(grounded in reading the real source + tests):

1. Seam-injection mechanism — chose runtime-state injection (fn carried in
   `[:runtime-handle <seam-key>]`, seeded at acquisition, real-impl default when
   absent), mirroring the proven `[:runtime-handle :client-session]` idiom in
   `eval_test.clj`. Added a canonical "Seam-injection mechanism" subsection to
   Design guidance. New keys: `:nrepl-connector` (wraps inline
   `requiring-resolve` nrepl.core block in `client.clj`) and `:process-launcher`
   (promotes private `start-process!` to the seam default). Rejected
   passed-argument/options-map: would force `ops`/`commands` to thread a test-only
   param. `attach_test.clj` drives `:nrepl-connector` transitively via
   `attach-instance-in! → connect-instance-in!` (no connect-instance-in! redef).

2. `ops_test.clj` scope — resolved as IN scope. Added to Problem audit, Audit
   summary (redesign), Scope (explicit in-scope rationale), Acceptance file list,
   and execution order. De-mock set is now six files. Same `:client-session` seam
   covers its `eval-instance-in!` redefs.

3. `config_test.clj` `resolve-config` — Option 1 (real file-backed). Decisions:
   user layer not file-mutated (reads ~/.psi); precedence proof uses real shared
   `project.edn` + local `project.local.edn` in temp worktree; `:version 1` is
   irrelevant because `resolve-config` extracts only `:project-nrepl`; merge proof
   NOT redundant with `read-project-preferences-test` (different units, both kept);
   empty case = temp worktree with no config → `{:project-nrepl {}}`.

4. Acceptance note wording — resolved to per-seam: one strategy note for the
   nREPL client seam and one for the process-start seam; a single combined note is
   explicitly insufficient.

5. `commands_test.clj` operational "or" — resolved to REAL operational routing
   through canonical `[:runtime-handle :client-session]` seam (eval_test
   `install-instance!` pattern), not reduction to formatting-only tests. Pure
   formatting/parsing tests stay seam-free.

No blocking reasons; all five items completed at the design level. Implementation
(production seam code + test reshaping) remains for the build phase per the
existing steps.md.

2026-06-01 — Design inconsistency review

Read design.md plus the six named test files, `eval_test.clj`, and the source
namespaces (`client`, `started`, `attach`, `config`, `eval`, `ops`, `runtime`).
Found four actionable inconsistencies (added to `design-steps.md`):

1. `:nrepl-connector` seam contract vs. `client.clj`: design says the seam returns
   `{:transport :client :client-session :session-id}` and that "the inline
   `requiring-resolve` block currently in `client.clj` becomes the default
   implementation." But that block produces only transport/client/session-fn; the
   `:session-id` is derived afterward from `session-fn` metadata with a
   throw-on-missing. The seam contract and the named source region disagree, and
   the missing-session-id throw is unaccounted for.
2. `commands_test.clj` redef list wrong: design Problem audit lists it as redefining
   `resolve-config`, `eval-op`, `interrupt`. The actual file redefines only
   `psi.project-nrepl.ops/eval-op` and `psi.project-nrepl.ops/interrupt` (no
   `resolve-config`; missing-start-command test uses a real temp worktree). The
   2026-06-01 implementation.md re-audit (line 65) repeats the same error.
3. `config_test.clj` precedence claim self-contradicts: design says the reshaped
   `resolve-config` test uses shared `project.edn` + local `project.local.edn` and
   "exercises the same merge precedence the redef test asserted." But that is the
   project-internal shared/local merge (handled inside `read-project-preferences`,
   already covered by `read-project-preferences-test`), not the user-vs-project
   precedence the redef `resolve-config-test` actually proves. This conflicts with
   the design's own claim that the merge proof is NOT redundant with
   `read-project-preferences-test`.
4. `started_test.clj` seam assignment incomplete + overwrite conflict: design says
   started_test must stop redefining both `start-process!` and
   `connect-instance-in!`, but the Seam-injection section only assigns
   `:process-launcher` to it and never states it must also seed `:nrepl-connector`
   (needed because `start-instance-in!` calls `connect-instance-in!` internally).
   Worse, `start-instance-in!` OVERWRITES `:runtime-handle {:process ...}` before
   calling connect, dropping any pre-seeded `:nrepl-connector`/`:client-session` —
   so the stated "seed at acquisition, resolve at call time, production call sites
   unchanged" mechanism cannot work for started-mode without a production change
   (merge instead of overwrite, or seed differently). Design does not acknowledge
   this.
