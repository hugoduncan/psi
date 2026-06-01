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

2026-06-01 — Design inconsistency follow-up execution

Resolved all four 2026-06-01 design-inconsistency follow-up items in design.md
(grounded in re-reading client.clj, started.clj, config.clj, commands_test.clj,
config_test.clj, started_test.clj, and shared-config user reader/test).

1. `:nrepl-connector` seam contract — narrowed the seam return shape to
   `{:transport :client :client-session}` (the inline `requiring-resolve` block
   up to binding session-fn). Session-id derivation and the throw-on-missing
   stay in `connect-instance-in!` (interpretation of the returned session fn, not
   the infra boundary). Connected `:runtime-handle` still ends with
   `{:transport :client :client-session :session-id}`; only `:session-id` is
   computed by `connect-instance-in!` from the connector return. Updated the
   Seam-injection `:nrepl-connector` entry.

2. `commands_test.clj` redef list — corrected design Problem audit + Audit-summary
   redesign bullet to list only `eval-op` and `interrupt` (the real redefs); noted
   the missing-start-command test uses a real temp worktree, not a `resolve-config`
   redef. NOTE: the earlier re-audit note at implementation.md line 65 (and the
   Initial audit at line 24) carry the same stale three-symbol list; left as
   historical record (append-only), corrected authoritatively in design.md and
   here.

3. `config_test.clj` precedence — restated the concrete target. The precedence
   `resolve-config` owns (and the redef test proves) is user-vs-project
   (system<user<project), NOT the project-internal shared/local merge already
   covered by `read-project-preferences-test`. Reshaped test rebinds `user.home`
   to a temp dir and writes a real `<tmp-home>/.psi/agent/config.edn` user file
   (exercising the real `read-user-config` reader, no redef) layered against a
   real `<worktree>/.psi/project.edn` project file; asserts project overrides user
   while user-only keys survive. Empty case = no user file + no project file →
   `{:project-nrepl {}}`. The "NOT redundant" claim now holds (different readers,
   different precedence axes).

4. `started_test.clj` seam assignment + overwrite conflict — added a
   "Seam seeding per test file" subsection (started_test seeds BOTH
   `:process-launcher` and `:nrepl-connector`, the latter for the internal
   `connect-instance-in!` call) and a "Started-mode runtime-handle merge"
   subsection requiring `start-instance-in!` to merge process keys into
   `:runtime-handle` rather than overwrite, so seeded seam fns survive acquisition.
   This is the only production behavioural change beyond promoting the two seam
   defaults; behaviour-preserving for real callers. Updated the started_test
   redesign bullet and process-start seam shape to reference both.

No blocking reasons; all four items completed at the design level. Production
seam code + test reshaping remain for the build phase per steps.md.

2026-06-01 — Design ambiguity review (second pass)

Re-read design.md plus the real source (`client.clj`, `attach.clj`, `started.clj`,
`eval.clj`, `runtime.clj`, `config.clj`) and tests (`eval_test`, `attach_test`,
`config_test`). The five first-pass ambiguity items and four inconsistency items
are resolved/checked. Found one NEW actionable ambiguity (added to
`design-steps.md`):

- Seam seed-injection point for composite acquisition entry points is
  unspecified. `attach-instance-in!` and `start-instance-in!` each call
  `ensure-instance-in!` (no seam seed) immediately followed by the internal
  `connect-instance-in!` / `start-process!`, with no interleaving point and no
  seam param in their current signatures (attach's 3rd arg is `attach-input`;
  started's 4th `opts` only feeds `wait-for-started-endpoint!`). The cited
  `eval_test.clj` idiom works only because `eval` is a separate post-seed call;
  attach/started have no separate step. The design's mechanism bullet asserts
  these entry points "accept an optional `:runtime-handle` seed" yet also claims
  "production call sites unchanged" and lists only the runtime-handle merge as a
  required production change — adding a seed param to these two entry points is
  itself a production signature change that is unlisted and contradicts the
  unchanged-call-sites claim. Verified `ensure-instance-in!`/`build-instance`
  already accept `:runtime-handle`, but neither composite caller forwards one.

No production/test code changed in this review pass.

2026-06-01 — Ambiguity follow-up execution (second pass)

Resolved the one new second-pass ambiguity item (composite-entry-point
seed-injection point) in design.md, grounded in re-reading attach.clj,
started.clj, runtime.clj, client.clj, and eval_test.clj.

Resolution:
- Added "Composite acquisition entry-point seed injection (required production
  change)" subsection. attach-instance-in! and start-instance-in! have no
  separate post-acquisition step where a test could install a seam (unlike
  eval-instance-in! / connect-instance-in!, which are invoked separately after
  the test's own ensure/update seed). Therefore both composite entry points MUST
  accept an explicit optional :runtime-handle seed and thread it into their
  ensure-instance-in! call.
- Verified ensure-instance-in! and build-instance already accept/forward
  :runtime-handle; only the two composite entry points fail to forward one.
- Verified connect-instance-in! already merges into :runtime-handle, so a seeded
  connector survives connect (after the seam change makes connect read it).
- Narrowed the contradictory "production call sites unchanged" claim: signatures
  DO change (new optional seed param); only runtime behaviour for real callers
  (who seed nothing) is preserved. Listed the complete set of THREE required
  production changes: (1) promote two seam defaults + resolve via or-default,
  (2) merge-not-overwrite :runtime-handle in start-instance-in!, (3) optional
  :runtime-handle seed param on attach/start entry points threaded into
  ensure-instance-in!.
- Reconciled the prior mechanism bullet ("accept an optional :runtime-handle
  seed") and the started-mode-merge "only production change" claim with the new
  third change to remove internal contradictions.

No blocking reasons; item completed at the design level. Production seam code +
test reshaping remain for the build phase per steps.md.

2026-06-01 — Design inconsistency review (second pass)

Re-read design.md plus source (`config.clj`, `runtime.clj`, `client.clj`,
`started.clj`, `attach.clj`, shared-config `user`/`project`/`resolution`) and
`config_test.clj`. Prior four inconsistency items + two-pass ambiguity items are
resolved. Found TWO NEW actionable inconsistencies (added to `design-steps.md`):

1. `config_test.clj` concrete target omits `:agent-session` wrapping. `resolve-config`
   extracts config via `(:project-nrepl (shared-resolution/agent-session-map
   (read-user-config)))` and the same for project — i.e. it reads `[:agent-session
   :project-nrepl]`. The current redef test correctly returns `{:agent-session
   {:project-nrepl {...}}}` maps. But the design's concrete-target Decisions describe
   writing "a real `<tmp-home>/.psi/agent/config.edn` user config" and "a real project
   config in the temp worktree (`<worktree>/.psi/project.edn`)" and characterise the
   seeded values as "user-scope `:attach {:host "localhost" :port 7888}`" / "project-scope
   `:attach {:port 9999}`" — never stating the on-disk content MUST be nested under
   `:agent-session :project-nrepl`. An implementer writing `{:project-nrepl {...}}` files
   literally would get `{:project-nrepl {}}` from `resolve-config` (extraction misses).
   Design must state the file content is `{:agent-session {:project-nrepl {...}}}` (and that
   the user reader merges `default-config` `{:version 1 :agent-session {}}`).

2. `ensure-instance-in!` "destructures `:runtime-handle`" claim is inaccurate against source.
   The "Composite acquisition entry-point seed injection" section and its Verified-source-facts
   bullet state "`ensure-instance-in!` already destructures and forwards `:runtime-handle`
   into `build-instance`." Source: `ensure-instance-in!` destructures only `{:keys
   [worktree-path acquisition-mode endpoint command-vector] :as opts}` — `:runtime-handle`
   is NOT in its `:keys`. Forwarding happens only via the `:as opts` passthrough to
   `build-instance` (which does destructure `:runtime-handle`). The functional claim
   (a seeded `:runtime-handle` reaches `build-instance`) holds; the word "destructures" is
   wrong and could mislead. Restate as "`ensure-instance-in!` forwards `:runtime-handle`
   through its `opts` passthrough; only `build-instance` destructures it."

No production/test code changed in this review pass.

2026-06-01 — Inconsistency follow-up execution (second pass)

Resolved both 2026-06-01 second-pass design-inconsistency follow-up items in
design.md, grounded in re-reading config.clj, shared-config resolution.clj +
user.clj, and runtime.clj.

1. `config_test.clj` `:agent-session` wrapping — verified `resolve-config`
   extracts via `(:project-nrepl (shared-resolution/agent-session-map
   (read-...)))` and `agent-session-map` returns `(:agent-session cfg)`, so a
   file written as `{:project-nrepl {...}}` resolves to `{:project-nrepl {}}`.
   Added an explicit Decision to the "Concrete target for `config_test.clj`
   `resolve-config`" section: on-disk content MUST be nested under
   `{:agent-session {:project-nrepl {...}}}` at both scopes, with the concrete
   user file `{:agent-session {:project-nrepl {:attach {:host "localhost" :port
   7888}}}}` and project file `{:agent-session {:project-nrepl {:attach {:port
   9999}}}}`. Noted the real user reader `merge`s `default-config`
   `{:version 1 :agent-session {}}` over the file (file `:agent-session` wins),
   so the user file need not carry `:version`.

2. `ensure-instance-in!` "destructures" claim — verified source:
   `ensure-instance-in!` destructures only `{:keys [worktree-path
   acquisition-mode endpoint command-vector] :as opts}`; `:runtime-handle` is NOT
   in its `:keys`. It reaches `build-instance` via the `:as opts` passthrough, and
   `build-instance` is the function that destructures `:runtime-handle`. Restated
   the Verified-source-facts bullet in "Composite acquisition entry-point seed
   injection" to say `ensure-instance-in!` forwards `:runtime-handle` through its
   `opts` passthrough (not destructures), matching the named source. The
   functional claim (a seeded `:runtime-handle` reaches `build-instance`) is
   unchanged and remains correct.

No blocking reasons; both items completed at the design level. Production seam
code + test reshaping remain for the build phase per steps.md.

2026-06-01 — Plan/steps ambiguity review

Reviewed plan.md + steps.md (not design) against the real source (`started.clj`,
`attach.clj`, `client.clj`, `commands.clj`, `ops.clj`, `eval.clj`, `eval_test.clj`).
Prior review notes all target design; this is the first plan/steps pass. Found
four NEW actionable plan/steps ambiguities (added to steps.md as follow-up items):

1. Slice 4 seed-param placement undetermined. Plan/steps Slice 4 says add an
   optional `:runtime-handle` seam-seed to `attach-instance-in!` but not HOW it
   attaches to its current `([ctx wt] [ctx wt attach-input])` arities — new arity?
   merged into the existing `attach-input` 3rd-positional map? a new opts map?
   Design's "passed alongside or within its opts" is not concretized in the
   actionable step, so an implementer cannot deterministically place it.
   (`start-instance-in!`'s 4th `opts` map already exists, so its seed source is
   unambiguous; only `attach-instance-in!` is underspecified.)

2. Slice 9 omits session-state→worktree binding. `dispatch-project-nrepl-command`
   derives worktree via `(ss/session-worktree-path-in ctx session-id)`, then looks
   up the managed instance at that worktree. Slice 9 says "install a real managed
   instance" + "dispatch real `/project-repl eval`/`interrupt`" but never states
   the test must seed session-state so the dispatch `session-id` maps to the
   instance's worktree-path. Without that mapping the instance lookup misses and
   the command never reaches real `ops → eval`.

3. Slices 9 & 10 leave the `:interrupted` seam-fn response shape unspecified.
   eval-op/eval-instance-in! derive `:interrupted` from `summarize-response` over
   the `client-session` fn's returned nREPL responses (status `"interrupted"`),
   NOT from a canned op result. `eval_test.clj` has a `:success` eval template but
   NO `:interrupted` eval template (its interrupt test exercises
   `interrupt-instance-in!`, a different fn). Steps say "assert ... interrupted
   contract through real `eval-instance-in!`" without stating what the in-memory
   `client-session` fn must return to yield `:interrupted`.

4. Slice 7 "drive `.nrepl-port` appearance / readiness through state" is
   misleading. `wait-for-started-endpoint!` reads a REAL on-disk `.nrepl-port`
   file (`read-dot-nrepl-port-safe`) in the temp worktree; readiness is file-backed,
   not runtime-handle-state-backed. "Through state" conflates the seam runtime
   state with the file mechanism and could lead an implementer to seed readiness
   via runtime-handle instead of writing a real `.nrepl-port` file. Plan's
   started_test reshape bullet carries the same wording.

2026-06-01 — Plan/steps ambiguity follow-up execution

Resolved all four 2026-06-01 plan/steps-ambiguity follow-up items (added by the
preceding plan/steps ambiguity-review pass), grounded in re-reading the real
source (`attach.clj`, `started.clj`, `commands.clj`, `eval.clj`, `ops.clj`,
`eval_test.clj`). These are plan/steps clarifications — no production or test
code changed.

1. Slice 4 attach seed placement — verified `attach-instance-in!` arities are
   `([ctx wt] [ctx wt attach-input])` and the 3rd `attach-input` map feeds
   `resolve-attach-endpoint` (domain input). Chose a NEW 4th-positional optional
   `opts` map (`[ctx wt attach-input opts]`), forwarding `(:runtime-handle opts)`
   into `ensure-instance-in!`, symmetric with `start-instance-in!`'s existing
   `opts`. Rejected overloading `attach-input` (conflates domain input with a
   test-only seam carrier). Concretized in steps.md Slice 4 + plan.md
   production-change #3.

2. Slice 9 session-state→worktree binding — verified
   `dispatch-project-nrepl-command` derives worktree via
   `(ss/session-worktree-path-in ctx session-id)` then looks up the instance at
   that worktree. Added an explicit step: the test must register the same
   `session-id → worktree-path` mapping as where the instance is installed, or
   the dispatch lookup misses and the command never reaches real `ops → eval`.

3. Slices 9 & 10 `:interrupted` response shape — verified `summarize-response`
   derives `:interrupted` from nREPL status `"interrupted"` in the
   `client-session` fn's returned response seq (via `nrepl.core/combine-responses`),
   not from a canned op result. `eval_test.clj` has only a `:success` template
   (`[{:value "3" :status #{"done"}}]`) and its interrupt test exercises a
   different fn (`interrupt-instance-in!`). Specified the in-memory
   `client-session` fn must return `[{:status #{"interrupted"}}]` (or
   `#{"done" "interrupted"}`) to drive the `:interrupted` path through real
   `eval-instance-in!`; added to both Slice 9 and Slice 10 steps.

4. Slice 7 file-backed readiness — verified `wait-for-started-endpoint!` →
   `read-dot-nrepl-port-safe` reads a REAL on-disk `.nrepl-port` in the temp
   worktree (file-backed), not runtime-handle state. Rewrote the Slice 7
   readiness step: the test writes a real `.nrepl-port` file in the temp worktree
   to drive endpoint discovery; do NOT seed readiness via runtime-handle state.
   Also fixed the matching wording in plan.md's started_test reshape bullet.

No blocking reasons; all four items completed at the plan/steps level. Production
seam code + test reshaping remain for the build phase per steps.md (Slices 1–12).

2026-06-01 — Plan/steps inconsistency review

Reviewed plan.md + steps.md against the real source (`commands.clj`, `ops.clj`,
`eval.clj`, `started.clj`, `runtime.clj`, `client.clj`, `config.clj`) plus the
six in-scope test files. Prior plan/steps note was an ambiguity pass; this is the
first plan/steps inconsistency pass. Found ONE NEW actionable inconsistency (added
to steps.md):

1. Slice 9 conflates the `/project-repl interrupt` command path with the eval
   path and omits the `active-op` precondition. Steps Slice 9 says to dispatch
   real `/project-repl eval` AND `/project-repl interrupt` "through real
   `commands → ops → eval`" and that the seeded `[:runtime-handle :client-session]`
   fn returning `[{:status #{"interrupted"}}]` drives "the `:interrupted` path."
   But source shows `/project-repl interrupt` routes via `ops/interrupt →
   eval/interrupt-instance-in!` (NOT `eval-op`/`eval-instance-in!`), and
   `interrupt-instance-in!` first checks `(get-in instance [:runtime-handle
   :active-op])` — with no active-op it short-circuits to `{:status :unavailable
   :reason :no-active-eval}` and never calls the seeded `client-session`. So a
   command-layer interrupt test that installs an instance and dispatches
   `/project-repl interrupt` WITHOUT first seeding/triggering an `:active-op`
   returns `:unavailable`, never reaching the prescribed `[{:status
   #{"interrupted"}}]` response, and the stated interrupt assertion cannot pass.
   The prior 2026-06-01 plan/steps ambiguity note (item 3) addressed only the
   eval-op interrupted-response template; it did not state the interrupt command
   routes through `interrupt-instance-in!` nor that the test must establish an
   `:active-op` (e.g. an in-flight eval, or seeding `[:runtime-handle :active-op]`)
   before interrupting. Slice 9 must (a) correct the routing wording (interrupt →
   `ops/interrupt → interrupt-instance-in!`, not `→ eval`), and (b) state that the
   interrupt test must establish an `:active-op` so `interrupt-instance-in!`
   reaches the seeded `client-session {:op "interrupt" ...}` call.

No production/test code changed in this review pass.

2026-06-01 — Plan/steps inconsistency follow-up executed

Resolved the single Slice 9 inconsistency follow-up. Verified against real
source (`ops.clj` lines 124–133, `eval.clj` lines 124–152) that `/project-repl
interrupt` routes `ops/interrupt → eval/interrupt-instance-in!` (not
`eval-op`/`eval-instance-in!`) and that `interrupt-instance-in!` short-circuits
to `{:status :unavailable :reason :no-active-eval}` when `[:runtime-handle
:active-op]` is absent, only invoking the seeded `client-session {:op
"interrupt" …}` once an `:active-op` exists.

Changes:
- steps.md Slice 9: split the single eval+interrupt dispatch step into two —
  (a) eval through `ops/eval-op → eval/eval-instance-in!`, and (b) interrupt
  through `ops/interrupt → eval/interrupt-instance-in!` with the explicit
  `:active-op` precondition (in-flight eval or seeded `[:runtime-handle
  :active-op]`) required before the `[{:status #{"interrupted"}}]` response can
  drive the assertion.
- plan.md `commands_test.clj` bullet: corrected the eval-only `commands → ops →
  eval` wording to name both distinct routes and state the `:active-op`
  precondition for interrupt.

No production/test code changed.

2026-06-01 — Implementation build (Slices 1–12)

Executed the full de-mocking refactor. Production changes (the three the design
required) plus six test-file reshapes. Six in-scope files now carry zero
`with-redefs`; `runtime_test.clj` and `eval_test.clj` were untouched and remain
green.

Production changes landed:
1. `client.clj`: promoted the inline `requiring-resolve` nrepl.core block to
   `real-nrepl-connector` (takes `{:host :port}`, returns
   `{:transport :client :client-session}`); `connect-instance-in!` resolves
   `(or (get-in instance [:runtime-handle :nrepl-connector]) real-nrepl-connector)`.
   Session-id derivation + throw-on-missing stay in `connect-instance-in!`.
2. `started.clj`: promoted private `start-process!` to `real-process-launcher`
   behind `[:runtime-handle :process-launcher]`; changed `start-instance-in!` to
   `(update :runtime-handle merge {...})` (was overwrite) so seeded seam fns
   survive into the internal `connect-instance-in!`.
3. `attach.clj` + `started.clj`: added optional `:runtime-handle` seam seed —
   `attach-instance-in!` gained a new 4th-positional `opts` arity
   (`[ctx wt attach-input opts]`); `start-instance-in!` forwards
   `(:runtime-handle opts)` from its existing `opts`. Both thread the seed into
   `ensure-instance-in!`. Real callers seed nothing → unchanged behaviour
   (verified by the unchanged attach/started/install tests).

Deviations from the initial design: none of substance. One discovered
real-behavior fact recorded in `ops_test.clj`: `eval-instance-in!`'s result map
omits `:ns`, so the public `eval-op` payload's `:ns` is always nil — the prior
mocked `ops_test` fabricated a `:ns "user"` value that real eval never produces.
The reshaped test asserts the real `:ns nil` contract. Timing instants are
asserted as present (real `now`) rather than fixed instants.

### Seam strategy note — nREPL client seam (`:nrepl-connector`)

- Boundary: the external `nrepl.core` `connect → client → client-session`
  establishment. This is true infrastructure (a real socket + nREPL protocol),
  appropriate to wrap.
- Mechanism: a function value carried in per-instance runtime state under
  `[:runtime-handle :nrepl-connector]`, seeded at acquisition, resolved at call
  time in `connect-instance-in!` via `(or seeded real-nrepl-connector)`.
- Contract: connector takes `{:host :port}`, returns
  `{:transport :client :client-session}`. Session-id derivation is deliberately
  NOT part of the seam — it stays in `connect-instance-in!` (interpretation of
  the returned session fn's `:nrepl.core/taking-until {:session ...}` metadata),
  retaining the throw-on-missing-session-id behaviour. A nullable connector
  returns a `client-session` fn carrying that metadata so the derivation
  succeeds deterministically.
- Production default: `real-nrepl-connector` (the promoted inline block) still
  performs the real nREPL establishment, so the production path is unchanged.
- Test consumption: `client_test.clj` seeds it before a standalone
  `connect-instance-in!`; `attach_test.clj` seeds it via the attach `opts` seed,
  consumed transitively through `attach-instance-in! → connect-instance-in!`;
  `started_test.clj` seeds it via the start `opts` seed, consumed by the internal
  `connect-instance-in!` after the runtime-handle merge.
- Nullable behaviour: deterministic in-memory transport/client/session-fn; tests
  assert on resulting instance `:runtime-handle` state
  (`:transport :client :client-session :session-id`) and lifecycle flags, never
  on var replacement or call counts.

### Seam strategy note — process-start seam (`:process-launcher`)

- Boundary: launching the configured start command as an OS process via
  `ProcessBuilder`. This is true infrastructure (a real subprocess), appropriate
  to wrap.
- Mechanism: a function value carried in per-instance runtime state under
  `[:runtime-handle :process-launcher]`, seeded at acquisition through the
  `start-instance-in!` `opts` seed, resolved at call time via
  `(or seeded real-process-launcher)`.
- Contract: launcher takes `(worktree-path command-vector)` and returns a
  `Process`-shaped object. The nullable surface only needs `isAlive`,
  `exitValue`, `pid`, `destroy` (the `fake-process` proxy in `started_test.clj`).
- Production default: `real-process-launcher` (the promoted private
  `start-process!`) still launches a real subprocess, so the production path is
  unchanged.
- Readiness is **file-backed, not seam-state-backed**: `start-instance-in!`'s
  readiness still flows through `wait-for-started-endpoint! → read-dot-nrepl-port`
  reading a real on-disk `.nrepl-port` in the temp worktree. The started test's
  seeded launcher writes a real `.nrepl-port` file to drive endpoint discovery;
  readiness is never injected through runtime-handle state.
- Required companion change: `start-instance-in!` merges (no longer overwrites)
  the process handle keys into `:runtime-handle`, so a co-seeded
  `:nrepl-connector` (needed by the internal `connect-instance-in!`) survives
  acquisition. Behaviour-preserving for real callers (the handle is nil/empty
  until process keys are set).

### Remaining justified exceptions

None. All six in-scope files are `with-redefs`-free. The two seams wrap genuine
infrastructure boundaries (nREPL socket establishment, OS process launch); no
test-only helper layer was introduced beyond the per-file `install-instance!`
seeding helpers that mirror the already-proven `eval_test.clj` idiom.

2026-06-01 — Implementation review (task-implementation-review)

Reviewed the landed implementation against design/plan and the
testing-without-mocks standard. Verified empirically:
- focused component tests green: 25 tests, 154 assertions, 0 failures
- targeted clj-kondo lint (`components/project-nrepl/src` + `/test`): 0 errors, 0 warnings
- consuming-path `project-nrepl-extension-install-test`: 1 test, 5 assertions, 0 failures
- zero `with-redefs` remain in the six in-scope test files
- `eval_test.clj` / `runtime_test.clj` untouched (last touched by extraction #72)
- the three production changes match the design exactly (`real-nrepl-connector`
  + or-default resolve; `start-instance-in!` overwrite→merge; optional
  `:runtime-handle` seed on `attach-instance-in!`/`start-instance-in!`)
- production callers in `ops.clj` still use the original arities → behaviour
  preserved for real callers

Acceptance criteria: all met. Implementation matches design, follows the
established `[:runtime-handle :client-session]` seam architecture, introduces no
shims/adapters, and the two seams wrap genuine infrastructure boundaries (no
unnecessary abstraction).

Actionable findings (quality, not acceptance blockers):

1. Test-helper duplication extended by this task. `install-instance!` is now
   defined in three files (`eval_test`, `commands_test`, `ops_test`) with
   identical bodies; `temp-dir` and `delete-tree!` are each duplicated across
   four files (`attach`/`config`/`ops`/`started`_test); `session-fn-with-id`
   across two (`attach`/`started`_test); `make-ctx` across six. No component-local
   test-support namespace exists. This task added several of these copies. A small
   `psi.project-nrepl.test-support` namespace consolidating `make-ctx`,
   `install-instance!`, `temp-dir`, `delete-tree!`, and `session-fn-with-id` would
   remove the drift risk (e.g. `install-instance!`'s seeded `:runtime-handle`
   shape must stay identical across copies) and align with the consistency
   guideline. Non-blocking: tests are green and the duplication is conventional
   test isolation, but it is the one structural quality issue introduced/extended
   here.

2. Minor — `client_test.clj` `connect-instance-in-test` asserts
   `(= [{:host "127.0.0.1" :port 7888}] @calls*)`, tracking the connector's
   captured input. This is borderline against `¬assert(interactions(test))`: it is
   an input-value-at-boundary check rather than a call-count assertion, and the
   connected-instance state assertions already prove the connector result was
   used. Consider dropping the `calls*` atom (the state assertions suffice) or
   keep it only if endpoint-passthrough is the specific behaviour under test.

2026-06-01 — Implementation-review follow-up execution (both items complete)

Item 1 — shared test-support consolidation. Added
`components/project-nrepl/test/psi/project_nrepl/test_support.clj`
(`psi.project-nrepl.test-support`) exposing `make-ctx`, `install-instance!`,
`temp-dir`, `delete-tree!`, `session-fn-with-id`. All test files now `:refer`
the shared helpers and dropped their local copies:
- eval_test: make-ctx, install-instance!
- commands_test: install-instance!
- ops_test: make-ctx, install-instance!, temp-dir, delete-tree!
- attach_test: make-ctx, temp-dir, delete-tree!, session-fn-with-id
- started_test: make-ctx, temp-dir, delete-tree!, session-fn-with-id (kept its
  own `fake-process` proxy — single-use, not shared)
- config_test: temp-dir, delete-tree! (kept its own config-file writers +
  capture-stderr — single-use)
- runtime_test: make-ctx (the 6th copy; folded in for completeness)

`temp-dir` is uniformly `[prefix]`-taking (config_test already used that shape);
per-file prefixes preserved, so the bare `(temp-dir)` call sites in
ops/attach/started were given their existing prefix string. The shared
`install-instance!` carries the single canonical seeded `:runtime-handle`
`{:client-session … :session-id "nrepl-session-1"}` shape, eliminating the
drift risk across the three former copies. `test_support.clj` does not match
the `.*-test$` ns-pattern, so Kaocha will not collect it as a test namespace.

Item 2 — client_test `@calls*` interaction assertion. Removed the `calls*` atom
and the `(= [{:host … :port …}] @calls*)` assertion from
`connect-instance-in-test`; the connector now ignores its `_endpoint` arg. The
existing connected-instance state assertions (`:transport`, `:client`,
`:client-session`, `:session-id` all sourced from the seeded connector result)
already prove the connector was invoked and its result threaded into the
managed instance, so the input-capture check was redundant and the only
interaction-style assertion left in the in-scope files. Net assertion count
drops by 1 (154 → 153).

Verification: focused project-nrepl suite (eval/commands/ops/attach/started/
config/client/runtime) — 25 tests, 153 assertions, 0 failures; targeted
clj-kondo over `components/project-nrepl/test` (+ src) — 0 errors, 0 warnings.

2026-06-01 — Implementation review (second pass, task-implementation-review)

Fresh review pass after the consolidation/interaction-assertion follow-ups
landed (35ddb3f). Re-verified empirically:
- focused project-nrepl suite (8 namespaces) green: 25 tests, 153 assertions, 0 failures
- targeted clj-kondo (`components/project-nrepl/{src,test}`): 0 errors, 0 warnings
- zero `with-redefs` in the test tree (git grep → no matches)
- three production changes match design exactly: `client.clj` `real-nrepl-connector`
  + `(or (get-in … :nrepl-connector) real-nrepl-connector)`; `started.clj`
  `real-process-launcher` + `(update :runtime-handle merge {…})`; `attach.clj`
  4th-positional `opts` arity threading `(:runtime-handle opts)` into
  `ensure-instance-in!`
Acceptance criteria: all met. No new acceptance blockers.

New actionable findings (quality, not acceptance blockers) — the prior
consolidation follow-up claimed "all seven test files now :refer the shared
helpers", but that completeness does not hold empirically; residual hand-rolled
equivalents remain:

1. `client_test.clj` `connect-instance-in-test` inlines its own `session-fn`
   `(with-meta (fn [_] nil) {(keyword "nrepl.core" "taking-until") {:session
   "nrepl-session-1"}})` instead of using the shared
   `psi.project-nrepl.test-support/session-fn-with-id`. This is exactly the
   metadata-shape drift the consolidation aimed to eliminate (the shared helper
   already encodes that `:nrepl.core/taking-until` shape). `started_test.clj`
   uses the shared helper; `client_test.clj` does not. Low-risk but inconsistent.

2. Pre-existing (non-reshaped) test bodies still hand-roll temp-dir create +
   recursive delete despite the shared `temp-dir`/`delete-tree!` now existing:
   - `config_test.clj` `read-project-preferences-test` (×2 dirs) and
     `read-dot-nrepl-port-test` use `(io/file (System/getProperty "java.io.tmpdir")
     (str "…-" (UUID/randomUUID)))` + inline `(doseq [f (reverse (file-seq dir))]
     (.delete f))`.
   - `commands_test.clj` missing-start-command test uses
     `(java.nio.file.Files/createTempDirectory …)` + the same inline `doseq` delete.
   The reshaped cases in those files already use the shared helpers, so the same
   file mixes both idioms — a within-file consistency gap. Non-blocking
   (these tests are green and were out of the de-mock reshape set), but folding
   them onto `temp-dir`/`delete-tree!` would complete the consolidation the prior
   note asserted was already complete.

## 2026-06-01 — Second-pass implementation-review follow-ups executed

Both newly-added unchecked second-pass review items completed:

1. `client_test.clj` `connect-instance-in-test` now `:refer`s
   `session-fn-with-id` and binds `session-fn (session-fn-with-id
   "nrepl-session-1")`; the inline `with-meta`/`:nrepl.core/taking-until`
   copy is removed. A grep confirms no inline copies of that metadata shape
   remain in any test file — the metadata-shape drift is eliminated.

2. Hand-rolled temp-dir create/delete folded onto the shared
   `temp-dir`/`delete-tree!` helpers in the previously-untouched test bodies:
   - `config_test.clj` `read-project-preferences-test` (×3 testing blocks) and
     `read-dot-nrepl-port-test` (×2 testing blocks) now bind
     `dir (temp-dir "prefix-")` and clean up via `(delete-tree! dir)`.
     `temp-dir` returns a path string, consumed directly by the
     config readers; on-disk fixtures still built with `(io/file dir ...)`.
   - `commands_test.clj` missing-start-command test now uses
     `(temp-dir "psi-project-nrepl-commands-")` + `(delete-tree! worktree-path)`;
     its now-unused `[clojure.java.io :as io]` require was dropped.
   Each of the two files now uses one temp-dir idiom (the shared helpers).

Verification: focused (config/client/commands) → 11 tests, 64 assertions, 0
failures; full project-nrepl suite (8 ns) → 25 tests, 153 assertions, 0
failures; `clj-kondo --lint components/project-nrepl/test` → 0 errors, 0
warnings. No remaining second-pass review exceptions.

2026-06-01 — Implementation review (third pass, task-implementation-review)

Re-verified empirically: focused project-nrepl suite (8 ns) — 25 tests, 153
assertions, 0 failures; `clj-kondo --lint components/project-nrepl/{src,test}` —
0 errors, 0 warnings; zero `with-redefs` in the test tree (git grep → none);
working tree clean. The three production seams in `client.clj`
(`real-nrepl-connector` + or-default resolve, session-id derivation retained in
`connect-instance-in!`) and `started.clj` (`real-process-launcher`,
`(update :runtime-handle merge {...})`) plus the `attach.clj` 4th-positional
`opts` arity threading `(:runtime-handle opts)` match the design exactly. All
acceptance criteria met; no acceptance blockers.

New actionable finding (quality/consistency, not an acceptance blocker):

1. The canonical `eval_test.clj` — the reference pattern every reshaped file was
   pointed at, and which the prior follow-up cited approvingly while removing the
   identical `@calls*` assertion from `client_test.clj` — itself still carries
   interaction-style assertions on the seam's captured messages:
   `(is (= "eval" (:op (first @calls*))))` (line 30) and
   `(is (= "interrupt" (:op (first @calls*))))` / `(is (= "eval-123"
   (:interrupt-id (first @calls*))))` (lines 84–85). These assert *what was sent
   to the collaborator* (`¬assert(interactions(test))`), the exact class of
   assertion the second-pass follow-up removed from `client_test.clj` as
   "the only interaction-style assertion left in the in-scope files." That claim
   held only because `eval_test.clj` was out of the de-mock reshape set; the
   reference idiom the task propagates still violates the standard. The behaviour
   each assertion actually proves is already covered by state/result assertions in
   the same test: the eval `:op "eval"` is implied by the `:success`/`:value "3"`
   result; the interrupt `:op "interrupt"` and `:interrupt-id "eval-123"` are
   already asserted as state via `(:interrupted-op-id result)` (line 81) and
   `(:last-interrupt instance)` (line 86). Dropping the `@calls*` op/interrupt-id
   assertions (the `swap! conj` capture can stay only if a returned value depends
   on the message) would make the canonical pattern consistent with the standard
   this task enforced elsewhere. `eval_test.clj` was nominally out of scope, so
   this is a follow-on consistency item rather than a regression introduced here.

## 2026-06-01 — Third-pass implementation-review follow-up executed

Removed the interaction-style `@calls*` assertions from the canonical
`eval_test.clj` (the reference pattern the six reshaped files were pointed at):

- `eval-instance-in-test` success block: dropped `(is (= "eval" (:op (first
  @calls*))))`.
- `interrupt-instance-in-test` active-op block: dropped `(is (= "interrupt"
  (:op (first @calls*))))` and `(is (= "eval-123" (:interrupt-id (first
  @calls*))))`.

The `calls*` atom and its `(swap! calls* conj msg)` capture were dead once those
assertions were removed — the seeded `client-session` fn's return value uses only
`(:id msg)` (an input→output echo that needs no external capture), so I dropped
the `calls*` atom entirely from both test blocks rather than leaving an unused
capture. The canonical reference pattern now carries zero interaction-style
assertions, matching `¬assert(interactions(test))` — the same standard the task
enforced across the six in-scope files and that the 2nd-pass follow-up applied to
`client_test.clj`.

Behaviour previously implied by the dropped assertions remains proven by
state/result assertions in the same tests:
- eval `:op "eval"` → the `:success` status + `:value "3"` result (a real eval
  through `eval-instance-in!`),
- interrupt `:op "interrupt"` / `:interrupt-id "eval-123"` → `(:interrupted-op-id
  result)` and `(= result (:last-interrupt instance))`.

Verification: focused project-nrepl suite (eval/commands/ops/attach/started/
config/client/runtime) → 25 tests, 150 assertions, 0 failures (down 3 from 153 —
exactly the three removed interaction assertions); `clj-kondo --lint` over the
file → 0 errors, 0 warnings. No remaining interaction-style assertions in any
project-nrepl test file.

2026-06-01 — Implementation review (fourth pass, task-implementation-review)

Fresh independent review after the third-pass interaction-assertion follow-up
landed (09f27106b, working tree clean). Re-verified empirically myself (not
relying on prior recorded counts):
- focused project-nrepl suite (8 ns: client/attach/started/config/commands/ops/
  eval/runtime) — 25 tests, 150 assertions, 0 failures
- `clj-kondo --lint components/project-nrepl/{src,test}` — 0 errors, 0 warnings
- zero `with-redefs` in the test tree (`grep -rn` → no matches)
- zero `calls*`/`@calls` interaction-capture atoms in any test file (including
  the canonical `eval_test.clj`)
- zero inline `with-meta`/`:nrepl.core/taking-until` metadata-shape copies
  (all derive the session fn via the shared `session-fn-with-id`)

Production parity with design (re-read source): the three required changes are
present and exact —
- `client.clj`: `real-nrepl-connector` ({:host :port} → {:transport :client
  :client-session}); `connect-instance-in!` resolves via
  `(or (get-in instance [:runtime-handle :nrepl-connector]) real-nrepl-connector)`;
  session-id derivation + throw-on-missing retained in `connect-instance-in!`.
- `started.clj`: `real-process-launcher` (promoted private `start-process!`);
  `start-instance-in!` uses `(update :runtime-handle merge {...})` (not
  overwrite); resolves launcher via `(or (get-in … :process-launcher)
  real-process-launcher)`.
- `attach.clj`/`started.clj`: optional `:runtime-handle` seam seed threaded into
  `ensure-instance-in!` (`attach-instance-in!` 4th-positional `opts` arity;
  `start-instance-in!` `(:runtime-handle opts)`).

Acceptance criteria — all met:
- six in-scope files `with-redefs`-free ✓
- new seams thin, component-owned, justified by real infra boundaries ✓
- `runtime_test`/`eval_test` state-based and green ✓
- focused component tests green ✓
- two SEPARATE per-seam strategy notes (`:nrepl-connector`, `:process-launcher`)
  present in this file ✓
- component-boundary behaviour preserved (real callers in `ops.clj` use original
  arities; `started_test` failure-case seeds only `:process-launcher`, correctly,
  since failure precedes the internal connect) ✓

Quality (testing-without-mocks standard): tests are sociable/state-based,
assert on returned values + runtime-handle state, shared `test-support`
consolidates the seeded `:runtime-handle` shape, no shims/adapters introduced.

No new actionable findings. The three prior-pass follow-ups are genuinely
landed (verified, not just recorded). Review complete.

2026-06-01 — Test review (task-test-review)

First test-focused review pass (skill criteria: well_formed ∧ behaviour
coverage ∧ infra deps injectable/nullable/¬mock/¬stub). The four prior passes
were implementation reviews; none examined behaviour-coverage explicitly.

Re-verified empirically myself:
- focused project-nrepl suite (8 ns): 25 tests, 150 assertions, 0 failures
- zero `with-redefs` (git grep → none); zero interaction-capture atoms
- all infra seams are injectable/nullable via `[:runtime-handle <seam-key>]`
  (`:nrepl-connector`, `:process-launcher`, `:client-session`) with embedded
  stubs (fake-process proxy, in-memory client-session/connector fns) — not
  mocks/stubs-as-redefs. Criterion 3 ✓.
- tests are sociable, state/result-based, well isolated (temp dirs cleaned in
  `finally`, `user.home` restored in `finally`). Criterion 1 ✓.

One actionable behaviour-coverage gap (criterion 2):

1. The missing-session-id throw in `connect-instance-in!` is uncovered.
   `connect-instance-in!` retains a throw-on-missing-session-id branch
   (`"… client session did not expose a session id"`) — design names this as a
   deliberately-retained behaviour and the `:nrepl-connector` seam is shaped
   specifically so this derivation lives in `connect-instance-in!` (not the
   seam). The de-mocking work newly makes this path trivially testable through
   the seam (seed a `:nrepl-connector` whose returned `:client-session` fn
   carries NO `:nrepl.core/taking-until` metadata → the throw fires), yet no
   test exercises it. The pre-redef `client_test.clj` did not cover it either,
   so this is not a regression — but it is a covering-test gap for a
   design-retained behaviour that this task made cheaply testable via the same
   seam the migrated `client_test`/`attach_test`/`started_test` already use.
   `attach_test` covers a connector that THROWS (`attach-boom`) and the happy
   path; neither covers the connector returning a session fn without the
   session-id metadata. Adding one `client_test.clj` case (seed a connector
   returning a metadata-less session-fn, assert the
   `did not expose a session id` `ExceptionInfo`) closes it with the existing
   seam mechanism — no new seam needed.

## Test-review follow-up resolved (2026-06-01)

The missing-session-id covering gap above is now closed.
`client_test.clj` gained `connect-instance-in-missing-session-id-test`: seeds a
`[:runtime-handle :nrepl-connector]` returning `:client-session (fn [_] nil)`
(no `:nrepl.core/taking-until` metadata) through the existing connector-seed
setup, then asserts `connect-instance-in!` throws `clojure.lang.ExceptionInfo`
`#"did not expose a session id"` (`thrown-with-msg?`). State/throw assertion
only — no interaction capture. Reused the existing `ensure-instance-in!` +
`update-instance-in!` seed pattern; no new seam introduced. `client_test`:
3 tests, 14 assertions, 0 failures (up from 2/12). Focused project-nrepl suite
(8 ns) all green; `clj-kondo` 0/0.

2026-06-01 — Test review (task-test-review, 2nd pass)

Second independent test-focused review pass (skill criteria: well_formed ∧
behaviour coverage ∧ infra deps injectable/nullable/¬mock/¬stub). Re-verified
empirically: focused project-nrepl suite (8 ns) → 26 tests, 151 assertions,
0 failures; `git grep with-redefs components/project-nrepl/test` → none.

- Criterion 1 (well-formed) ✓ — sociable, state/result-based, isolated (temp
  dirs cleaned in `finally`, `user.home` restored in `finally`), no interaction
  assertions, no leftover capture atoms.
- Criterion 3 (infra deps) ✓ — every infra dep injected via
  `[:runtime-handle <seam-key>]` (`:nrepl-connector`, `:process-launcher`,
  `:client-session`) with embedded stubs (fake-process `Process` proxy,
  in-memory connector/client-session fns). No mocks, no `with-redefs`, no
  stub-via-redef.
- Criterion 2 (behaviour coverage) ✓ for the design's named behaviours. The
  one design-named retained behaviour previously flagged (missing-session-id
  throw in `connect-instance-in!`) is now covered by
  `connect-instance-in-missing-session-id-test`. Each seam's nullable path is
  exercised (connector happy/throw/missing-session-id; launcher
  happy/throw/file-backed-readiness; client-session eval-ok/interrupted/
  unavailable). Started-mode runtime-handle merge survival is proven by
  `start-instance-in-test` (seeded `:nrepl-connector` survives into the internal
  `connect-instance-in!`, session-id derived). The user-vs-project config
  precedence is proven file-backed.

Considered but NOT raised as actionable (scope-bounded judgment):
`connect-instance-in!` retains two PRE-EXISTING guard branches — "instance not
found" and "requires discovered host/port before connect" — that no test
exercises. These are infrastructure guards that pre-date this task, are not
behaviours the de-mocking design names or changed, and reach into
acquisition-precondition territory rather than the seam contract this task
introduced. Unlike the missing-session-id throw (a design-named retained
behaviour the seam shaping deliberately preserved), these guards are outside
this task's behaviour set; covering them would be scope expansion beyond the
de-mocking intent. No new follow-up step added. No actionable test-quality
issue found this pass.

2026-06-01 — Test review (test-shaper skill)

First review pass through the `test-shaper` lens (clarity ∧ signal ∧ robustness
∧ economy ∧ determinism), distinct from the prior task-test-review passes which
focused on well-formedness, behaviour coverage, and ¬mock seam injection. Read
all eight test files and `started.clj` source. Most criteria hold strongly:
sociable/state-based, single-concern, isolated (temp dirs + `user.home`
restored in `finally`), no interaction assertions, economical coverage, shared
`test-support` compresses ceremony without hiding intent.

One actionable robustness/determinism gap (skill: `λ deterministic(tests)` →
`control(time)` ∧ `control(concurrency)` ∧ `¬flaky`):

1. `started_test.clj` `wait-for-started-endpoint-test` (happy case) and
   `start-instance-in-test` drive `.nrepl-port` readiness through a racing
   background thread: `(future (Thread/sleep 100|50) (spit … ".nrepl-port" …))`
   against the polling `wait-for-started-endpoint!` (`:timeout-ms 1000`). This
   couples the test to real wall-clock timing and thread scheduling — a slow or
   contended CI host can miss the window, making the tests flaky, and every
   green run pays the sleep latency. The race is also UNNECESSARY:
   `wait-for-started-endpoint!` (started.clj:52–53) checks
   `read-dot-nrepl-port-safe` on the FIRST loop iteration and returns
   immediately if the file exists, so the port file can be written
   deterministically BEFORE the wait/launch rather than from a sleeping future.
   Fixes: (a) `wait-for-started-endpoint-test` — `spit` the `.nrepl-port` file
   synchronously before calling `wait-for-started-endpoint!` (drop the
   `future`/`Thread/sleep`); the first poll finds it. (b) `start-instance-in-test`
   — make the seeded `launcher` write `.nrepl-port` SYNCHRONOUSLY before
   returning the fake process (drop its `future`/`Thread/sleep`); the launcher is
   called synchronously before `wait-for-started-endpoint!`, so the subsequent
   poll finds the file on its first iteration. Both keep the file-backed-readiness
   signal intact (still proving discovery from a real on-disk `.nrepl-port`) while
   removing the time/concurrency dependency and the per-run sleep. Keep tests
   green + lint clean.

The `process-exited?` failure case (`fail when process exits before port
discovery`) is already deterministic (process reports `isAlive=false`
immediately, no file write) and needs no change.

2026-06-01 — Test review follow-up executed (test-shaper determinism gap)

Removed the wall-clock/thread-scheduling dependency from `started_test.clj`
readiness tests (the lone unchecked test-shaper follow-up):

- `wait-for-started-endpoint-test` (happy case): now `spit`s `.nrepl-port`
  synchronously before invoking `wait-for-started-endpoint!`; dropped the
  `(future (Thread/sleep 100) (spit …))` background race.
- `start-instance-in-test`: the seeded `launcher` now writes `.nrepl-port`
  synchronously before returning the fake process; dropped its
  `(future (Thread/sleep 50) (spit …))`.

Both rely on `wait-for-started-endpoint!` (started.clj:52–53) checking
`read-dot-nrepl-port-safe` on the FIRST loop iteration — the launcher runs
synchronously before the wait, so the first poll finds the file. The
file-backed-readiness signal is preserved (endpoint still discovered from a
real on-disk `.nrepl-port`). The `process-exited?` failure case was left
unchanged (already deterministic). `io/file` still used (no unused require).

Result: no wall-clock/thread-scheduling dependency, no per-run sleep latency.
Verified: `started_test` 2 tests/12 assertions/0 failures; full focused
project-nrepl suite (8 ns) 26 tests/151 assertions/0 failures (unchanged);
`clj-kondo --lint started_test.clj` → 0 errors / 0 warnings.

## Test-shaper review (2026-06-01, second test-shaper pass)

Re-read all eight `project-nrepl` `*_test.clj` files plus `test_support.clj`
against the test-shaper lens (simple ∧ consistent ∧ robust ∧ economical). The
suite is in strong shape: zero `with-redefs`, zero interaction-capture atoms,
state-based assertions throughout, shared `test-support` helpers (consistent
seam/temp-dir idioms), and the prior pass already removed the
wall-clock/thread-scheduling race from the readiness tests (deterministic).

One new actionable `single_concern` issue found:

- `config_test.clj` `read-dot-nrepl-port-test` "fails when .nrepl-port is
  missing or invalid" packs TWO distinct boundary contracts into one `testing`
  block: (1) absent `.nrepl-port` throws, then it mutates the same dir
  (`spit` invalid content) and asserts (2) malformed `.nrepl-port` throws.
  This violates `single_concern` (one boundary contract per test) and the
  intervening `spit` creates intra-test ordering coupling — a failure in either
  branch surfaces under one test name, weakening `meaningful_failures`. The two
  failure modes are independent boundary contracts and should be separate
  `testing` blocks (each with its own fresh temp dir), matching the
  one-assertion-cluster-per-concern style the rest of the file already uses
  (e.g. `read-project-preferences-test`'s three separate blocks).

No other new actionable test-shaping feedback. The pre-existing
`absolute-directory-path-test` "accepts existing absolute directory" relies on
the real cwd (`user.dir`) existing rather than an explicit temp dir, but it is
a pre-existing pure-config test outside this task's de-mock reshape set;
raising it would be scope expansion, so it is deliberately NOT added as a
follow-up.

## Test-shaper follow-up (2026-06-01, second test-shaper pass) — single-concern split

Split `config_test.clj` `read-dot-nrepl-port-test`'s "fails when .nrepl-port is
missing or invalid" block into two single-concern `testing` blocks:

- "fails when .nrepl-port is absent" — fresh `(temp-dir "psi-project-nrepl-")`,
  assert `read-dot-nrepl-port` throws `ExceptionInfo` on the bare empty dir,
  `delete-tree!` in `finally`.
- "fails when .nrepl-port content is malformed" — fresh `temp-dir`,
  `(spit (io/file dir ".nrepl-port") "not-a-port")`, assert throws
  `ExceptionInfo`, `delete-tree!` in `finally`.

Removes the prior intra-test `spit`-then-reassert ordering coupling (the old
single block asserted on the absent file, then mutated the dir mid-test and
reasserted). Each boundary contract now has its own fixture lifecycle and a
meaningful failure name, matching `read-project-preferences-test`'s
one-concern-per-block style. Assertion count unchanged (2 `thrown?` total).

Verified: `config_test` 7 tests/32 assertions/0 failures; full focused
project-nrepl suite (8 ns) 26 tests/151 assertions/0 failures (unchanged);
`clj-kondo --lint config_test.clj` 0 errors/0 warnings.

## Test-shaper review (2026-06-01, third test-shaper pass)

Independent test-shaper pass (clarity ∧ signal ∧ robustness ∧ economy ∧
determinism) re-reading all eight `project-nrepl` `*_test.clj` files plus
`test_support.clj`. The suite remains strong: zero `with-redefs`, zero
interaction-capture atoms, state/result-based assertions, deterministic (the
prior pass removed the readiness wall-clock/thread race), single-concern
`testing` blocks, and shared `test-support` helpers compress ceremony without
hiding intent. Focused `client_test`+`config_test` re-run: 10 tests, 46
assertions, 0 failures.

One new actionable economy/consistency finding (`λ economical` minimal
incidental variation; `λ helpers_that_compress(ceremony)`):

1. `client_test.clj`'s two `connect-instance-in!` tests
   (`connect-instance-in-test` and `connect-instance-in-missing-session-id-test`)
   each repeat the full connector-seed ceremony verbatim: an
   `ensure-instance-in!` (same `:worktree-path`/`:acquisition-mode
   :attached`/`:endpoint {:host "127.0.0.1" :port 7888 :port-source :explicit}`
   map) followed by an `update-instance-in!` that `assoc-in`s the test's
   `connector` under `[:runtime-handle :nrepl-connector]`. This is the same
   per-file seam-seeding duplication the earlier consolidation targeted for
   `install-instance!`/`session-fn-with-id`/`temp-dir`/`delete-tree!`, but the
   connector-seed two-step was never folded into `test-support`. The two copies
   couple both tests to the literal endpoint map and the exact
   `[:runtime-handle :nrepl-connector]` seed path; a drift in either (e.g. the
   endpoint shape or the seam key) would have to be edited in two places. A
   shared `seed-connector!` helper in `psi.project-nrepl.test-support` (mirroring
   `install-instance!`: `(seed-connector! ctx worktree connector)` →
   `ensure-instance-in!` + `update-instance-in!` seeding
   `[:runtime-handle :nrepl-connector]`) would single-source the seeded-connector
   shape, compress the ceremony in both tests, and align `client_test.clj` with
   the rest of the consolidated suite. Each test would keep its own `connector`
   fn (its distinct behaviour: happy vs. metadata-less session fn) and its own
   assertions — only the install ceremony is shared. Non-blocking: tests are
   green and the duplication is small (2 sites), but it is the one remaining
   un-consolidated seam-seeding idiom in the test tree.

Considered but NOT raised as actionable (scope/precedent): the pervasive
`worktree (System/getProperty "user.dir")` instance-key idiom (a pre-existing
ambient-cwd coupling inherited from before the de-mock work, used uniformly and
not introduced by this task) and `disconnect-instance-in-test`'s `closed*` atom
(records that the real `Closeable` transport's `.close` fired — disconnect's
sole observable effect on an opaque object, defensible as a real-effect state
check rather than a call-count interaction assertion; seen and accepted by the
prior task-test-review passes). Raising either would be scope expansion.

## seed-connector! consolidation (2026-06-01, third test-shaper follow-up)

Implemented the `seed-connector!` consolidation described in the prior note.
Added `psi.project-nrepl.test-support/seed-connector!`:
`(seed-connector! ctx worktree-path connector)` → `ensure-instance-in!`
(attached, endpoint `{:host "127.0.0.1" :port 7888 :port-source :explicit}`) +
`update-instance-in!` that `assoc-in`s `connector` under
`[:runtime-handle :nrepl-connector]`. Mirrors `install-instance!`.

`client_test.clj`: both `connect-instance-in-test` and
`connect-instance-in-missing-session-id-test` now `:refer` and call
`seed-connector!`, dropping the duplicated ensure+update ceremony and the
inline endpoint map. Each test retains its own `connector` fn (happy vs.
metadata-less session fn) and assertions. `disconnect-instance-in-test` keeps
its direct `project-nrepl-runtime` usage — its setup is a proxy-`Closeable`
runtime-handle, not connector seeding, so it is not a `seed-connector!` site.
The `project-nrepl-runtime` require stays (still used by
`disconnect-instance-in-test`).

The seeded `[:runtime-handle :nrepl-connector]` shape and the endpoint map are
now single-sourced — the last un-consolidated seam-seeding idiom in the test
tree is gone. Focused project-nrepl suite (8 ns): 26 tests / 151 assertions /
0 failures (unchanged). `clj-kondo` 0/0 on `client_test.clj` +
`test_support.clj`.

## Test-shaper review (2026-06-01, fourth test-shaper pass)

Independent test-shaper pass (clarity ∧ signal ∧ robustness ∧ economy ∧
determinism) re-reading all eight `project-nrepl` `*_test.clj` files plus
`test_support.clj`. The suite remains strong: zero `with-redefs`, zero
interaction-capture atoms, state/result-based, deterministic (readiness race
removed in an earlier pass), single-concern blocks, shared `test-support`
compresses ceremony without hiding intent.

One new actionable `minimal_incidental_setup` / `meaningful_failures` finding:

1. `ops_test.clj` `eval-op-test` seeds a misleading `:ns "user"` in the
   in-memory `client-session` response in BOTH the success block (line 37) and
   the interrupted block (line 59), then each block asserts `(is (nil? (:ns
   result)))`. Verified against source why the assertion is correct:
   `summarize-response` (eval.clj:40) DOES carry `:ns (:ns combined)` and
   `nrepl.core/combine-responses` DOES preserve `:ns "user"` — but
   `eval-instance-in!`'s built `result` map (eval.clj ~78) lists
   `:status :value :out :err :summary` and **omits `:ns`**, so `eval-op`'s
   `(:ns result)` is `nil` regardless of the response's `:ns`. The seeded
   `:ns "user"` therefore feeds nothing the test asserts on — it is dead,
   misleading incidental setup that implies `:ns` flows through when it is
   dropped one layer below the assertion. It weakens `meaningful_failures`: a
   reader sees `:ns "user"` seeded and `:ns` asserted nil and cannot tell
   whether the nil is the contract or a setup bug. (The `:err "Interrupted"`
   seeded alongside in the interrupted block IS asserted — `(= "Interrupted"
   (:err result))` — so that one is real, not incidental.) Fix: drop `:ns
   "user"` from both seeded responses (the `:status`/`:value`/`:err` keys
   already drive every assertion); keep the `(nil? (:ns result))` assertion and
   its explanatory comment, which document the real drop-`:ns` contract without
   needing a seeded `:ns` to "prove" it. The `:ns nil` contract is best proven
   by NOT seeding `:ns` at all and still observing nil. Non-blocking: tests are
   green and the assertion is correct, but the seeded `:ns` is the one remaining
   piece of misleading incidental setup in the in-scope files.

Considered but NOT raised: the `commands_test.clj` interrupt-ok response seeds
`:status #{"done" "interrupted"}` (both statuses) where `#{"interrupted"}` alone
would drive the `:interrupted` summary — but `#{"done" "interrupted"}` is a
realistic nREPL interrupt-completion status set (interrupt and done both arrive),
so it is representative-case fidelity rather than misleading setup; not raised.

## ops_test `:ns` incidental-setup removal (2026-06-01, executed)

Executed the fourth test-shaper follow-up. Dropped `:ns "user"` from both the
success and interrupted seeded `client-session` responses in
`ops_test.clj/eval-op-test`. Kept both `(is (nil? (:ns result)))` assertions —
the drop-`:ns` contract is now proven by NOT seeding `:ns` and still observing
nil (stronger signal than seeding then asserting nil). Updated the success-block
comment to drop the now-inaccurate "prior mock fabricated :ns" framing. The
interrupted block's `:err "Interrupted"` seed stays (asserted). `ops_test` 2/17
green; full focused project-nrepl suite (8 ns) 26 tests/151 assertions/0 —
assertion count unchanged (only dead setup removed); `clj-kondo` 0/0.

## Test-shaper review (2026-06-01, fifth test-shaper pass)

Independent test-shaper pass (clarity ∧ signal ∧ robustness ∧ economy ∧
determinism) re-reading all eight `project-nrepl` `*_test.clj` files plus
`test_support.clj`. Suite remains strong: zero `with-redefs`, zero
interaction-capture atoms, state/result-based, deterministic (readiness race
removed earlier), single-concern blocks, shared `test-support` consolidates
`make-ctx`/`install-instance!`/`seed-connector!`/`session-fn-with-id`/`temp-dir`/
`delete-tree!`.

One new actionable economy/consistency finding (`consistent(test_abstractions)`
∧ `minimal(incidental_variation)` ∧ `helpers_that_compress(ceremony)`):

1. The happy nullable-connector VALUE is constructed verbatim at three sites —
   `attach_test.clj` `attach-instance-in-test` (l.33), `client_test.clj`
   `connect-instance-in-test` (l.15), `started_test.clj` `start-instance-in-test`
   (l.65): `(fn [_endpoint] {:transport {:transport :fake} :client (fn ([] nil)
   ([_] nil)) :client-session (session-fn-with-id "nrepl-session-1")})`. Prior
   consolidation passes shared the connector *seeding* ceremony (`seed-connector!`)
   and the session fn (`session-fn-with-id`) but never the connector return-map
   construction, so the deterministic transport/client/session shape is
   triplicated; a drift in the nullable transport/client shape or the
   `{:transport :client :client-session}` connector contract must be edited in
   three places. A `test-support` helper returning the canonical happy connector
   (session-id-parameterised) would single-source it. The distinct-behaviour
   connectors stay inline (their behaviour IS the intent): the metadata-less
   `:client-session (fn [_] nil)` connector (client_test l.35) and the throwing
   connector (attach_test l.52). Follow-up step added.

Considered but NOT raised (scope/precedent, consistent with prior passes): the
pervasive `worktree (System/getProperty "user.dir")` ambient-cwd instance key
(pre-existing, uniform, not introduced by this task) and
`disconnect-instance-in-test`'s `closed*` atom (real-effect state check on the
opaque Closeable transport, accepted by prior passes). Raising either is scope
expansion.

## Test-shaper fifth-pass follow-up executed (2026-06-01)

Executed the single newly-added unchecked steps.md item: consolidated the
triplicated happy nullable-connector construction into
`psi.project-nrepl.test-support/fake-connector`.

- Added `fake-connector` (`([])`/`([session-id])`, default `"nrepl-session-1"`)
  to `test_support.clj`. It builds the canonical happy connector return map
  once and closes over it, returning `(fn [_endpoint] handle)` where
  `handle = {:transport {:transport :fake}
             :client (fn ([] nil) ([_] nil))
             :client-session (session-fn-with-id session-id)}`. The map being
  constant per call lets `client_test` invoke the connector once to recover the
  exact `:transport`/`:client`/`:client-session` objects for its equality
  assertions (same objects flow through the `connect-instance-in!` seam — no
  behaviour change).
- Folded the three identical happy connectors onto the helper:
  `client_test/connect-instance-in-test`,
  `attach_test/attach-instance-in-test`,
  `started_test/start-instance-in-test`. Each now `:refer`s `fake-connector`.
  `session-fn-with-id` is no longer `:refer`-ed by these three files (folded
  into the helper).
- Left the two distinct-behaviour connectors inline (their shape IS the test
  intent, not ceremony): `client_test`'s metadata-less `(fn [_] nil)`
  session-fn connector (missing-session-id throw) and `attach_test`'s throwing
  connector (attach-failure projection).

Verification: focused project-nrepl suite (8 ns) → 26 tests / 151 assertions /
0 failures (count unchanged — pure helper extraction, no assertions
added/removed); `clj-kondo --lint` on `test_support.clj` + the three test
files → 0 errors / 0 warnings.

## Test-shaper review (2026-06-01, sixth test-shaper pass)

Independent test-shaper pass. Verified green baseline: focused project-nrepl
suite (8 ns) → 26 tests / 151 assertions / 0 failures; `clj-kondo --lint`
src+test → 0/0; zero `with-redefs`; the only interaction-style atom is
`disconnect-instance-in-test`'s `closed*` real-effect Closeable check (accepted
by prior passes — it asserts a real `.close` side effect on the opaque
transport, not a collaborator call count).

One new actionable economy/consistency finding
(`helpers_that_compress(ceremony)` ∧ `consistent(test_abstractions)` ∧
`minimal(incidental_variation)`):

1. `commands_test.clj` repeats the session-with-resolvable-worktree construction
   ceremony verbatim at SIX sites (lines 11–12, 19–20, 27–28, 42–43, 59–60,
   79–80): `(test-support/create-test-session {:persist? false :session-defaults
   {:worktree-path <wt>}})`, identical except the `:worktree-path` value. This
   is the one session-construction idiom never folded into `test-support`: prior
   passes consolidated the no-session-id ctx (`make-ctx`), the instance install
   (`install-instance!`), the connector seed (`seed-connector!`), the session fn
   (`session-fn-with-id`), the connector value (`fake-connector`), and the
   temp-dir lifecycle (`temp-dir`/`delete-tree!`) — but `commands_test` needs a
   ctx WITH a session-id resolving (via `ss/session-worktree-path-in`) to a
   specific worktree, which `make-ctx` (no session-id) cannot provide, so it
   open-codes the full `:persist?`/`:session-defaults` map six times. A drift in
   the session-construction shape (e.g. a new required `:session-defaults` key,
   or a `:persist?` default change) must be edited at six call sites. Add a
   `test-support` helper (e.g. `(session-ctx-at worktree-path)` returning
   `[ctx session-id]`) single-sourcing the `{:persist? false :session-defaults
   {:worktree-path …}}` shape, and have the six `commands_test` sites call it.
   This is symmetric with the established `make-ctx`/`install-instance!`/
   `seed-connector!`/`fake-connector` consolidation pattern; `make-ctx` itself
   can be re-expressed in terms of the new helper (discard the session-id) to
   keep one session-construction source. `create-test-session` is called
   directly ONLY in `commands_test` (×6) and inside `make-ctx` (verified via
   `git grep`), so the helper genuinely single-sources the idiom. Follow-up step
   added.

Considered but NOT raised (scope/precedent, consistent with prior passes): the
pervasive `worktree (System/getProperty "user.dir")` ambient-cwd instance key
(pre-existing, uniform, not introduced by this task), and
`disconnect-instance-in-test`'s `closed*` real-effect atom + hand-rolled
ensure+update setup (distinct Closeable-proxy intent, not connector-seeding;
accepted by prior passes). Raising either is scope expansion.

## Sixth test-shaper follow-up execution (2026-06-01)

Executed the single newly-added unchecked `steps.md` item from the sixth
test-shaper review pass: consolidated the triplicated... (no, sextupled)
session-with-resolvable-worktree construction ceremony in `commands_test.clj`.

Added `psi.project-nrepl.test-support/session-ctx-at`:

    (defn session-ctx-at [worktree-path]
      (test-support/create-test-session
       {:persist? false
        :session-defaults {:worktree-path worktree-path}}))

returning `[ctx session-id]` and single-sourcing the `{:persist? false
:session-defaults {:worktree-path …}}` shape. The six `commands_test` sites
(status-format, dispatch-status, missing-start-command, eval-route,
interrupt-route, interrupt-no-active-eval) now `:refer` and call
`(session-ctx-at <wt>)`. Each site keeps its own `<wt>` (`user.dir` for the
instance-resolving tests; a fresh `temp-dir` for the missing-start-command
test) and its own assertions — only the session-construction map is shared.

The agent-session `test-support` alias in `commands_test` was dropped: after the
substitution no `test-support/` reference remained (all six were
`create-test-session`). `session-ctx-at` is now `:refer`-ed from
`psi.project-nrepl.test-support` alongside `delete-tree!`/`install-instance!`/
`temp-dir`.

`make-ctx` re-expressed in terms of `session-ctx-at` (passing
`(System/getProperty "user.dir")`, discarding the returned session-id), so
`create-test-session` is now called directly only inside `session-ctx-at` —
one source owns project-nrepl test session construction. Note: `make-ctx`
previously passed only `{:persist? false}` (no `:session-defaults`); adding a
`:worktree-path` default is harmless because `make-ctx`'s callers
(eval/ops/runtime/attach/started/client tests) never read the session-id and
install instances at their own explicit worktree-path.

Verification: focused project-nrepl suite (8 ns) 26 tests / 151 assertions / 0
failures (unchanged — pure helper extraction, no behaviour change); `clj-kondo`
0/0 on `test_support.clj` + `commands_test.clj`; `clj-paren-repair` clean on both.
