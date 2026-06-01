# 108 — Project nREPL testing without mocks

## Goal

Reshape the extracted `project-nrepl` component tests to follow the repository's `testing-without-mocks` pattern, so component-local tests are primarily sociable, state-based, and free of `with-redefs` seam patching.

## Why

Task `107-project-nrepl-component-extraction` successfully extracted the managed project nREPL subsystem into `components/project-nrepl/`, but the moved component-local test suite preserved several mock-style testing seams.

A focused audit of `components/project-nrepl/test/psi/project_nrepl/` shows that multiple tests currently use `with-redefs` to replace internal functions or external-library calls:

- `config_test.clj`
  - redefines `read-user-config`
  - redefines `read-project-preferences`
- `client_test.clj`
  - redefines `nrepl.core/connect`, `nrepl.core/client`, and `nrepl.core/client-session`
- `attach_test.clj`
  - redefines `psi.project-nrepl.client/connect-instance-in!`
- `started_test.clj`
  - redefines `start-process!`
  - redefines `psi.project-nrepl.client/connect-instance-in!`
- `commands_test.clj`
  - redefines `psi.project-nrepl.ops/eval-op`
  - redefines `psi.project-nrepl.ops/interrupt`
  - (the missing-start-command test uses a real temp worktree, not a
    `resolve-config` redef)
- `ops_test.clj`
  - redefines `psi.project-nrepl.eval/eval-instance-in!` (success and interrupted
    contract proofs)

This conflicts with the repo's testing guidance:

- prefer state-based assertions over interaction assertions
- prefer real collaborators for logic
- use nullables / embedded stubs for infrastructure
- avoid mock-style replacement of collaborators with `with-redefs`

## Problem

The extracted `project-nrepl` test suite currently proves behavior through replaced vars rather than through visible behavior at the component boundary.

This has several costs:

- tests are more coupled to internal call structure than to behavior
- refactoring is harder because tests encode seam topology
- the extracted component does not yet demonstrate the intended nullable-wrapper testing style for infrastructure-heavy code
- the project-nREPL component is now a local pocket of testing style drift

## Intent

Refactor the `project-nrepl` component and its component-local tests so the tests mostly exercise real component behavior with:

- real in-memory session/runtime state
- real temp directories and files for config and `.nrepl-port` surfaces
- nullable or embedded-stub wrappers for nREPL/process infrastructure where real infrastructure is inappropriate for unit tests
- assertions on returned values and updated runtime state instead of patched function calls

This task should leave the component extraction boundary from `107` intact. It is a test-shaping and seam-shaping task, not a boundary-redesign task.

## Audit summary

### Keep largely as-is

- `runtime_test.clj`
  - already mostly state-based and sociable
- `eval_test.clj`
  - already close to the target style because it installs a real in-memory client-session function and asserts on result/state

### Improve

- `config_test.clj`
  - reduce or remove `with-redefs` around config readers
  - prefer file-backed tests or a lower explicit config source seam that can be exercised without var replacement

### Redesign around nullable infrastructure seams

- `client_test.clj`
  - replace direct `nrepl.core/*` redefinitions with a nullable/thin-wrapper approach around connection/client/session establishment
- `attach_test.clj`
  - stop redefining `connect-instance-in!`; instead exercise attach behavior through a nullable client/connect seam and assert on resulting instance state
- `started_test.clj`
  - stop redefining `start-process!` and `connect-instance-in!`; instead seed
    both the `:process-launcher` and `:nrepl-connector` seams (the latter because
    `start-instance-in!` calls `connect-instance-in!` internally) so tests can
    drive startup behavior through visible state. Requires the started-mode
    `:runtime-handle` merge production change (see "Started-mode runtime-handle
    merge")
- `commands_test.clj`
  - stop redefining `eval-op` and `interrupt` (the only redefs present); instead use real component behavior with temp config/runtime state plus the canonical `[:runtime-handle :client-session]` seam so command-layer operational routing is proven through real `ops`/`eval` behavior (see "Concrete target for `commands_test.clj` operational routing")
- `ops_test.clj`
  - stop redefining `psi.project-nrepl.eval/eval-instance-in!`; instead install a real managed instance with a deterministic in-memory `[:runtime-handle :client-session]` seam (the `eval_test.clj` pattern) and assert `eval-op`'s public success/interrupted contract through real `eval-instance-in!` behavior

## Scope

In scope:

- audit and reshape tests under `components/project-nrepl/test/psi/project_nrepl/`
- `ops_test.clj` is **in scope**: its `with-redefs` on `eval-instance-in!`
  replaces the same internal eval collaborator this task de-mocks elsewhere, and
  the canonical `[:runtime-handle :client-session]` seam built for
  `eval_test`/`commands_test` covers it directly, so excluding it would leave a
  residual mock pocket in the same namespace family. The six files de-mocked by
  this task are therefore: `config_test.clj`, `client_test.clj`, `attach_test.clj`,
  `started_test.clj`, `commands_test.clj`, and `ops_test.clj`.
- introduce minimal production seams needed to support nullable infrastructure testing
- prefer thin wrappers / embedded stubs over test-only helper layers
- keep tests narrow, state-based, and fast
- preserve current user-visible behavior
- update any higher-level tests only where component-local test ownership becomes clearer
- document the resulting nullable/testing shape in task notes

Out of scope:

- redesigning project nREPL user-facing behavior
- changing the component boundary created by `107`
- broad repo-wide test-style conversion outside the `project-nrepl` slice
- adding broad end-to-end nREPL integration tests unless a very small smoke proof is needed

## Design guidance

### Testing style target

Apply the `testing-without-mocks` skill directly:

- logic and state helpers should use real collaborators
- infrastructure should be wrapped behind thin, production-owned APIs
- wrappers should support nullable behavior or embedded stubs for unit tests
- tests should assert on outputs and state, not collaborator call counts or exact internal routing

### Seam-injection mechanism (canonical)

All infrastructure seams introduced by this task MUST use a single, consistent
injection mechanism: **the seam implementation is supplied as a function value
carried in per-instance runtime state (`runtime-handle`), seeded at instance
acquisition time, and resolved at call time with a real-implementation default
when absent.** This mirrors the already-proven idiom in `eval_test.clj`, where
the nREPL `client-session` function lives under
`[:runtime-handle :client-session]` and `psi.project-nrepl.eval` reads it from
state rather than resolving a var.

Concretely:

- Production code resolves each seam via a small helper of the form
  `(or (get-in instance [:runtime-handle <seam-key>]) <real-default-fn>)`.
- `ensure-instance-in!` / `replace-instance-in!` already accept a
  `:runtime-handle` seed (verified: both forward it into `build-instance`), so a
  test can install a deterministic seam fn into instance state at acquisition.
  The composite acquisition entry points `attach-instance-in!` and
  `start-instance-in!` do **not** currently forward such a seed and must be
  changed to accept an optional `:runtime-handle` seed and thread it into their
  `ensure-instance-in!` call — an explicit production signature change (see
  "Composite acquisition entry-point seed injection").
- Tests install the deterministic seam fn through that seed (no `with-redefs`,
  no passed-through ad-hoc argument that production callers must thread).

Seam keys introduced or reused:

- `[:runtime-handle :client-session]` — already exists; reused unchanged by
  `eval`/`interrupt` paths.
- `[:runtime-handle :nrepl-connector]` — new; a single fn that performs the
  `connect → client → client-session` establishment for `connect-instance-in!`,
  returning `{:transport t :client c :client-session s}`. The real default calls
  `nrepl.core/connect|client|client-session`; concretely, the inline
  `requiring-resolve` block currently in `client.clj` — up to and including
  binding `transport`, `client-fn`, and `session-fn` — becomes the default
  implementation of this seam.

  Session-id derivation stays in `connect-instance-in!`, not in the seam
  default. After invoking the connector, `connect-instance-in!` derives
  `:session-id` from the returned `:client-session` fn's metadata (the existing
  `(-> session-fn meta (get :nrepl.core/taking-until) :session)` lookup) and
  retains the throw-on-missing-session-id behaviour. Rationale: session-id
  derivation is interpretation of the returned session fn, not part of the
  infrastructure boundary the seam wraps; keeping it in `connect-instance-in!`
  lets a nullable connector return a session fn carrying deterministic
  `:nrepl.core/taking-until` metadata (or lets the test seed it) without the
  seam contract having to reproduce the throw. The connected-instance
  `:runtime-handle` still ends up with `{:transport :client :client-session
  :session-id}` exactly as today; only the `:session-id` element is computed by
  `connect-instance-in!` from the connector's return rather than by the seam.
- `[:runtime-handle :process-launcher]` — new; a single fn
  `(fn [worktree-path command-vector] -> Process-like)` for `start-instance-in!`.
  The real default is the current private `start-process!` (promoted to be the
  default behind the seam). The launched object only needs the `Process`-shaped
  lifecycle surface already exercised by the `fake-process` proxy in
  `started_test.clj` (`isAlive`, `exitValue`, `pid`, `destroy`).

This mechanism is required identically across `client_test.clj`,
`started_test.clj`, and `attach_test.clj`; `attach_test.clj` drives the
`:nrepl-connector` seam transitively through `attach-instance-in! → connect-instance-in!`
rather than redefining `connect-instance-in!`.

##### Seam seeding per test file

- `client_test.clj` seeds `[:runtime-handle :nrepl-connector]` before
  `connect-instance-in!`.
- `attach_test.clj` seeds `[:runtime-handle :nrepl-connector]`; it is consumed
  transitively via `attach-instance-in! → connect-instance-in!`.
- `started_test.clj` seeds **both** `[:runtime-handle :process-launcher]` **and**
  `[:runtime-handle :nrepl-connector]`. The launcher seam covers
  `start-process!`; the connector seam is also required because
  `start-instance-in!` calls `connect-instance-in!` internally, so dropping the
  `connect-instance-in!` redef means the started-mode test must drive that inner
  call through a seeded connector rather than a real socket.

##### Started-mode runtime-handle merge (required production change)

`start-instance-in!` currently **overwrites** `:runtime-handle` with
`{:process … :pid … :started-at … :launch-id …}` immediately before calling
`connect-instance-in!`. That overwrite would discard any seam fn seeded at
acquisition time (`:process-launcher`, `:nrepl-connector`, `:client-session`),
so the "seed at acquisition, resolve at call time, production call sites
unchanged" mechanism cannot work for started-mode as written.

Required production change: `start-instance-in!` MUST **merge** the
process-handle keys into the existing `:runtime-handle` (`(update %
:runtime-handle merge {:process … :pid … :started-at … :launch-id …})`) instead
of replacing it, so seeded seam fns survive into the internal
`connect-instance-in!` call. With this merge in place:

- a started-mode test seeds `:process-launcher` and `:nrepl-connector` at
  acquisition,
- `start-instance-in!` resolves `:process-launcher` (real default = the promoted
  `start-process!`), merges the process keys, and
- the seeded `:nrepl-connector` is still present when `connect-instance-in!`
  runs.

This merge is one of the two production changes the seam mechanism requires
beyond promoting the two seam defaults (the other is the optional seed parameter
on the composite acquisition entry points — see "Composite acquisition
entry-point seed injection"). It is behaviour-preserving for real callers (who
seed nothing, so the merge adds the same keys the overwrite would have set, and
`:runtime-handle` was empty/`nil` before acquisition completes the process keys).

##### Composite acquisition entry-point seed injection (required production change)

The seam mechanism requires the seam fn to be present in instance
`:runtime-handle` **before** the internal `connect-instance-in!` /
`start-process!` call. For the standalone `connect-instance-in!` test
(`client_test.clj`) this is achievable because the test seeds the instance with a
separate `ensure-instance-in!`/`update-instance-in!` call *before* invoking
`connect-instance-in!` — the same separate-step pattern `eval_test.clj` uses for
`eval-instance-in!`.

The composite acquisition entry points `attach-instance-in!` and
`start-instance-in!` have **no such separate step**: each calls
`ensure-instance-in!` and then the internal connect/launch within a single
top-level call, and neither currently forwards a seam seed to
`ensure-instance-in!` (attach's third arg is `attach-input`; started's fourth
`opts` only feeds `wait-for-started-endpoint!` timeout/poll options). A test
therefore has no point at which to install the seam between acquisition and the
internal infra call.

Verified source facts:

- `ensure-instance-in!` forwards `:runtime-handle` into `build-instance` through
  its `opts` passthrough — it destructures only
  `{:keys [worktree-path acquisition-mode endpoint command-vector] :as opts}`, so
  `:runtime-handle` is not in its own `:keys`; it reaches `build-instance` via the
  `:as opts` value, and `build-instance` is the function that destructures
  `:runtime-handle`. The registry layer can therefore carry a seeded seam fn from
  acquisition; it is the two composite entry points that do not forward one.
- `connect-instance-in!` already `merge`s into the existing `:runtime-handle`, so
  a seam fn seeded at acquisition survives the connect update.
- `start-instance-in!` currently overwrites `:runtime-handle` (see "Started-mode
  runtime-handle merge"), which must change to a merge for any seeded seam fn to
  survive into the internal `connect-instance-in!`.

Required production change (in addition to the started-mode merge above): both
composite entry points MUST accept an explicit, optional seam-seed and thread it
into `ensure-instance-in!` as `:runtime-handle`:

- `attach-instance-in!` gains an optional trailing `:runtime-handle` seed
  (passed alongside or within its opts) that it forwards into the
  `ensure-instance-in!` call (`{:worktree-path … :acquisition-mode :attached
  :endpoint … :runtime-handle <seed>}`). The seeded `:nrepl-connector` then
  survives into the internal `connect-instance-in!`.
- `start-instance-in!` forwards a `:runtime-handle` seed from its `opts` map into
  the `ensure-instance-in!` call (`{:worktree-path … :acquisition-mode :started
  :command-vector … :runtime-handle <seed>}`). Combined with the started-mode
  merge, the seeded `:process-launcher` is present when `start-process!` resolves
  and the seeded `:nrepl-connector` is present when the internal
  `connect-instance-in!` runs.

This is an **explicit, acknowledged production signature/behaviour change** to
these two entry points, listed here as required. The earlier mechanism phrasing
"production call sites unchanged" is narrowed to its accurate meaning: **real
callers that seed nothing observe unchanged behaviour** (the optional seed is
absent, so the same real-default seams apply and the same `:runtime-handle` keys
result). The signatures themselves do change to accept the optional seed; only
the runtime behaviour for real callers is preserved. The complete set of required
production changes for the seam mechanism is therefore:

1. promote the two seam defaults (`:nrepl-connector`, `:process-launcher`) and
   resolve each via `(or (get-in instance [:runtime-handle <seam-key>])
   <real-default>)`,
2. merge (not overwrite) `:runtime-handle` in `start-instance-in!`, and
3. add the optional `:runtime-handle` seed parameter to `attach-instance-in!` and
   `start-instance-in!`, threaded into their `ensure-instance-in!` calls.

Rationale for choosing runtime-state injection over a passed argument or options
map: it is the only mechanism already present and proven in this component
(`eval_test.clj`), it keeps runtime behaviour unchanged for real callers
(seam defaults apply automatically when no seed is supplied), and it avoids
threading test-only parameters through `ops`/`commands` callers. A
passed-argument or options-map mechanism was rejected because it would force every
intermediate caller (`ops`, `commands`) to thread a seam parameter that only tests
supply. The optional seed added to the two acquisition entry points is the minimal
surface needed for tests to install seams at the single acquisition point those
composite paths expose.

### Preferred seam shapes

#### nREPL client seam

Introduce one thin wrapper (`:nrepl-connector`, see Seam-injection mechanism)
around the external `nrepl.core` operations used by `psi.project-nrepl.client`.

Target properties:

- production path still calls real `nrepl.core` (as the seam default)
- nullable path provides deterministic transport/client/session behavior via the
  `[:runtime-handle :nrepl-connector]` seed
- tests assert on connected instance state and any visible request/response data
  rather than on direct function replacement

#### process-start seam

Introduce one thin wrapper (`:process-launcher`, see Seam-injection mechanism)
around process startup used by `psi.project-nrepl.started`.

Target properties:

- production path still launches a real process (as the seam default)
- nullable path provides a fake `Process`-shaped object with visible lifecycle
  state via the `[:runtime-handle :process-launcher]` seed
- started-mode tests can drive `.nrepl-port` appearance and readiness through
  state rather than var replacement
- the started-mode path also seeds `[:runtime-handle :nrepl-connector]` (consumed
  by the internal `connect-instance-in!` call) and depends on the started-mode
  `:runtime-handle` merge production change so seeded seam fns survive acquisition
  (see "Started-mode runtime-handle merge")

#### config source seam

Prefer one of these in order:

1. real file-backed config tests using the existing on-disk config format
2. a small explicit config-source seam passed as data or context
3. only if strictly necessary, a very narrow production-owned wrapper with a nullable implementation

Avoid simply moving existing `with-redefs` one layer lower.

##### Concrete target for `config_test.clj` `resolve-config`

Option 1 (real file-backed config) applies. The two `resolve-config` tests that
currently redefine `read-user-config` and `read-project-preferences` are
reshaped to drive `resolve-config` through real on-disk config files at **both**
scopes — a real user config under a temp `user.home` and a real project config
in a temp worktree — so the user-vs-project precedence `resolve-config` owns is
proven through the real readers (see Decisions below), distinct from the
project-internal shared/local merge proven by the already-file-backed
`read-project-preferences-test`.

Decisions:

- The precedence `resolve-config` actually owns — and the precedence the redef
  `resolve-config-test` actually proves — is **user-vs-project** (`system < user
  < project`): the redef test seeds a user-scope `:attach {:host "localhost"
  :port 7888}` and a project-scope `:attach {:port 9999}`, then asserts the
  merged result keeps the user's `:host` while the project's `:port` wins. This
  is **not** the project-internal shared/local merge (handled inside
  `read-project-preferences` and already proven by `read-project-preferences-test`);
  the reshaped test must therefore layer a real **user** config file against a
  real **project** config file, not two project-layer files.
- The user-scope reader (`read-user-config` → `user-config/read-config`) resolves
  its path through `user-config/user-config-file`, which derives
  `~/.psi/agent/config.edn` from the `user.home` system property. A unit test
  must not read or mutate the developer's real home directory, so the reshaped
  test temporarily rebinds the `user.home` system property to a temp directory,
  writes a real `<tmp-home>/.psi/agent/config.edn` user config there, and restores
  the original `user.home` in a `finally`. This exercises the **real**
  `read-user-config` reader (no redef) against a deterministic on-disk user file.
- **On-disk content MUST be nested under `[:agent-session :project-nrepl]`.**
  `resolve-config` extracts each scope's project-nrepl config via
  `(:project-nrepl (shared-resolution/agent-session-map (read-... )))`, and
  `agent-session-map` returns `(:agent-session cfg)`. So a file written literally
  as `{:project-nrepl {...}}` resolves to `{:project-nrepl {}}` (extraction
  misses). The reshaped test therefore writes file content as
  `{:agent-session {:project-nrepl {...}}}` at both scopes — matching the shape
  the current redef maps return. Concretely: the user file is
  `{:agent-session {:project-nrepl {:attach {:host "localhost" :port 7888}}}}`
  and the project file is
  `{:agent-session {:project-nrepl {:attach {:port 9999}}}}`. Note the real user
  reader (`user-config/read-config`) `merge`s its `default-config`
  `{:version 1 :agent-session {}}` over the file map (file values win for
  `:agent-session`), so the user file need not carry `:version` for the test to
  work; the merged `:agent-session` still carries the file's `:project-nrepl`.
- The merge-precedence proof writes a real user config (lower precedence) under
  the temp `user.home` and a real project config (higher precedence) in the temp
  worktree (`<worktree>/.psi/project.edn`), then asserts `resolve-config` returns
  the deep-merged `:project-nrepl` map showing project values overriding user
  values while user-only keys survive — exactly the layering the redef test
  asserted, now driven by real readers and real files.
- `resolve-config` strips the `:version 1` key by design: it extracts only
  `(:project-nrepl (agent-session-map ...))`, so the real `:version 1` key the
  redef tests omitted is irrelevant to `resolve-config`'s output and the
  file-backed test asserts only the merged `:project-nrepl` map.
- The merge-precedence proof is **not** made redundant by the existing
  `read-project-preferences-test`: that test proves the project-config reader's
  shared/local merge (one layer, two files inside one worktree), whereas the
  reshaped `resolve-config` test proves `resolve-config`'s own user-vs-project
  layering (two distinct readers/scopes) and `:project-nrepl` extraction. Both
  proofs are retained; they cover different units and different precedence axes.
- The "returns empty project-nrepl config" case is reshaped to a temp `user.home`
  with no user config file and a temp worktree with no project config files,
  asserting `resolve-config` returns `{:project-nrepl {}}` from `system-defaults`.

#### command tests

Prefer split-by-boundary tests:

- formatting/parsing tests that use real values and no seam patching
- operational tests that use real runtime/config state plus nullable infrastructure wrappers
- keep any broader routing proof above the component boundary in `agent-session`

##### Concrete target for `commands_test.clj` operational routing

The earlier "or" is resolved in favour of **real operational routing through the
canonical seam**, not reduction to formatting/parsing-only tests.

Decisions:

- The `/project-repl eval` and `/project-repl interrupt` dispatch tests currently
  redefine `psi.project-nrepl.ops/eval-op` and `psi.project-nrepl.ops/interrupt`.
  These are reshaped to install a real managed instance in runtime state (the
  `eval_test.clj` `install-instance!` pattern: a real in-memory `client-session`
  fn seeded under `[:runtime-handle :client-session]`) and then dispatch the real
  command string. The command flows through real `commands → ops → eval` code so
  the operational routing of `eval-op`/`interrupt` is proven end-to-end within the
  component, with assertions on the user-facing `{:type :text :message ...}` result.
- This makes command-layer operational routing genuinely covered (the redef
  version proved only that `commands` calls some `ops` fn, not that the routing
  produces correct results from real op behavior).
- Pure formatting/parsing concerns that do not need a live instance (status
  formatting, missing-start-command messaging, command-string parsing) remain as
  separate real-value tests with no seam at all — they already are.
- The single in-memory `client-session` seam reused here is the same canonical
  `[:runtime-handle :client-session]` seam from `eval_test.clj`; no command-layer
  seam parameter is introduced.

## Acceptance

- component-local `project-nrepl` tests no longer rely on `with-redefs` for the current mock-style seams in `config_test.clj`, `client_test.clj`, `attach_test.clj`, `started_test.clj`, `commands_test.clj`, and `ops_test.clj`
- any new production seams are thin, component-owned, and justified by infrastructure boundaries rather than test convenience
- `runtime_test.clj` and `eval_test.clj` remain state-based and green
- focused `project-nrepl` component tests are green after the reshaping
- `implementation.md` records the final nullable/wrapper strategy with a separate documented strategy note for each infrastructure seam — one for the nREPL client seam (`:nrepl-connector`) and one for the process-start seam (`:process-launcher`); a single combined note is **not** sufficient
- behavior remains unchanged at the component boundary

## Suggested execution order

1. classify each component-local test as keep / improve / redesign
2. shape the smallest production-owned infrastructure wrappers needed for nREPL connection and process startup
3. migrate `client_test.clj`
4. migrate `attach_test.clj`
5. migrate `started_test.clj`
6. migrate `config_test.clj`
7. migrate `commands_test.clj`
8. migrate `ops_test.clj`
9. re-run focused component verification
10. record the resulting testing strategy and any remaining justified exceptions

## Related work

- `105-agent-session-component-extraction-map` is the umbrella extraction map
- `107-project-nrepl-component-extraction` established the `project-nrepl` component boundary
- this task is a follow-on quality slice that aligns the extracted component with the repo's testing-without-mocks standard
