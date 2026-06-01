# 108 — Plan

Rewritten 2026-06-01 to track the stabilised `design.md` (post two-pass design
review). Supersedes the earlier generic-wrapper plan: scope now includes
`ops_test.clj`, the canonical `:runtime-handle` seam-injection mechanism, and
three explicit production changes.

## Approach

Treat this as a test-shaping + minimal-seam-shaping task. Do **not** redesign
the `107` component boundary or change user-visible behavior. Follow the
`testing-without-mocks` skill: sociable/state-based tests, real temp filesystem
surfaces for config, real collaborators for logic, and nullable infrastructure
seams supplied via runtime state.

### Canonical seam-injection mechanism (single, consistent)

All infrastructure seams use one idiom, proven by `eval_test.clj`'s
`[:runtime-handle :client-session]`:

- The seam implementation is a **function value carried in per-instance
  runtime state (`:runtime-handle`)**, seeded at acquisition, resolved at call
  time via `(or (get-in instance [:runtime-handle <seam-key>]) <real-default>)`.
- Tests install deterministic seam fns through the seed — no `with-redefs`, no
  ad-hoc parameters threaded through `ops`/`commands`.

Seam keys:

- `[:runtime-handle :client-session]` — exists; reused unchanged by `eval`/`interrupt`.
- `[:runtime-handle :nrepl-connector]` — **new**. One fn performing
  `connect → client → client-session`, returning
  `{:transport t :client c :client-session s}`. Real default = the inline
  `requiring-resolve` block currently in `client.clj` (through binding
  `transport`/`client-fn`/`session-fn`). **Session-id derivation stays in
  `connect-instance-in!`** (from `session-fn` metadata, retaining the
  throw-on-missing-session-id behavior) — it is not part of the seam contract.
- `[:runtime-handle :process-launcher]` — **new**.
  `(fn [worktree-path command-vector] -> Process-like)`. Real default = the
  current private `start-process!` promoted to the seam default. Process-like
  surface = `isAlive`, `exitValue`, `pid`, `destroy` (the `fake-process` proxy
  shape already in `started_test.clj`).

### Required production changes (3)

1. **Promote two seam defaults** (`:nrepl-connector`, `:process-launcher`) and
   resolve each via `(or (get-in instance [:runtime-handle <seam-key>]) <real-default>)`.
2. **Merge, not overwrite, `:runtime-handle` in `start-instance-in!`** — change
   the current `assoc`/overwrite of `{:process … :pid … :started-at … :launch-id …}`
   to `(update % :runtime-handle merge {...})` so seam fns seeded at acquisition
   survive into the internal `connect-instance-in!`. Behavior-preserving for real
   callers (handle was empty/nil before acquisition completes).
3. **Add optional `:runtime-handle` seam-seed to the two composite acquisition
   entry points** `attach-instance-in!` and `start-instance-in!`, threaded into
   their `ensure-instance-in!` call as `:runtime-handle`. `start-instance-in!`
   already has a 4th-positional `opts` map → forward `(:runtime-handle opts)`.
   `attach-instance-in!` gains a NEW 4th-positional optional `opts` map
   (`([ctx wt] [ctx wt attach-input] [ctx wt attach-input opts])`) → forward
   `(:runtime-handle opts)`; `attach-input` (3rd map) stays purely domain input
   for `resolve-attach-endpoint` and is NOT overloaded with the seam key.
   `ensure-instance-in!`
   forwards `:runtime-handle` to `build-instance` via its `:as opts` passthrough
   (it is not in `ensure-instance-in!`'s own `:keys`). Real callers seed nothing →
   unchanged runtime behavior; only the signatures gain an optional seed.

These three are the *only* production changes. Everything else is test reshape.

### Per-test reshape targets

- `runtime_test.clj` — **keep** (already state-based/sociable).
- `eval_test.clj` — **keep** (canonical `[:runtime-handle :client-session]` pattern).
- `client_test.clj` — seed `[:runtime-handle :nrepl-connector]` before
  `connect-instance-in!`; assert connected-instance state.
- `attach_test.clj` — seed `[:runtime-handle :nrepl-connector]`; consumed
  transitively via `attach-instance-in! → connect-instance-in!`; assert instance
  state. Stop redefining `connect-instance-in!`.
- `started_test.clj` — seed **both** `[:runtime-handle :process-launcher]` **and**
  `[:runtime-handle :nrepl-connector]` (the latter because `start-instance-in!`
  calls `connect-instance-in!` internally). Depends on production change #2 (merge)
  and #3 (seed param). Stop redefining `start-process!` and `connect-instance-in!`.
  Readiness is **file-backed, not runtime-handle-state-backed**: `wait-for-started-endpoint!`
  reads a real on-disk `.nrepl-port` (`read-dot-nrepl-port-safe`) in the temp
  worktree, so the test writes a real `.nrepl-port` file there to drive endpoint
  discovery — it does not seed readiness through runtime-handle state.
- `config_test.clj` — Option 1 (real file-backed config). Reshape the two
  `resolve-config` tests to drive real readers against real on-disk files at
  **both** scopes: temp `user.home` `<tmp>/.psi/agent/config.edn` (user) and temp
  worktree `<wt>/.psi/project.edn` (project). File content nested under
  `{:agent-session {:project-nrepl {...}}}` (extraction requires it). Prove
  user-vs-project precedence (`system < user < project`); empty case → both
  absent → `{:project-nrepl {}}`. Restore `user.home` in `finally`.
- `commands_test.clj` — stop redefining `eval-op`/`interrupt`. Install a real
  managed instance with in-memory `[:runtime-handle :client-session]`, dispatch
  the real command strings, assert user-facing `{:type :text :message ...}`. The
  eval path runs through `commands → ops/eval-op → eval/eval-instance-in!`; the
  interrupt path runs through `commands → ops/interrupt →
  eval/interrupt-instance-in!` (distinct routing, not eval). For interrupt the
  test must first establish an `[:runtime-handle :active-op]` (in-flight eval or
  seeded), or `interrupt-instance-in!` short-circuits to `:no-active-eval` and
  never reaches the seeded `:client-session`. Pure formatting/parsing tests stay
  seamless.
- `ops_test.clj` — **in scope**. Stop redefining `eval-instance-in!`. Install a
  real managed instance with in-memory `[:runtime-handle :client-session]`, assert
  `eval-op` success/interrupted public contract through real `eval-instance-in!`.

### Documentation

`implementation.md` MUST record a **separate** nullable/wrapper strategy note for
each infrastructure seam — one for `:nrepl-connector`, one for `:process-launcher`.
A single combined note is **not** sufficient (acceptance requirement).

## Risks

- **Started-mode handle merge regression**: changing overwrite→merge could leak
  stale keys if the handle is non-empty pre-acquisition. Mitigation: confirm the
  handle is empty/nil until process keys are set; assert real-caller behavior
  unchanged via focused started tests.
- **Session-id derivation boundary**: keeping session-id derivation in
  `connect-instance-in!` (not the seam) requires the nullable connector's returned
  `:client-session` fn to carry `:nrepl.core/taking-until {:session ...}` metadata,
  or the throw fires. Mitigation: nullable connector seeds that metadata.
- **Composite entry-point signature change**: adding an optional seed to
  `attach-instance-in!`/`start-instance-in!` touches a production signature. Risk
  of accidental behavior change for real callers. Mitigation: optional, default
  absent; verify a consuming-path test if ownership shifts.
- **Config `:agent-session` wrapping**: files written without the
  `{:agent-session {:project-nrepl ...}}` nesting silently resolve to
  `{:project-nrepl {}}`. Mitigation: assert the exact nested shape.
- **Hidden cross-component callers** of the two entry points may need updating.
  Mitigation: grep callers before changing signatures.

## Slice order

Vertical slices, each independently verifiable (real seam + its consuming tests):

1. **Audit + source confirmation** — reconfirm keep/improve/redesign and source
   facts for `client`, `started`, `attach`, `commands`, `config`, `ops`, `eval`.
2. **`:nrepl-connector` seam (production)** — promote default, resolve helper,
   keep session-id derivation in `connect-instance-in!`.
3. **`client_test.clj`** — migrate onto `:nrepl-connector` seed.
4. **Composite seed param (production)** — add optional `:runtime-handle` seed to
   `attach-instance-in!` and `start-instance-in!`, thread into `ensure-instance-in!`.
5. **`attach_test.clj`** — migrate onto transitive `:nrepl-connector` seed.
6. **`:process-launcher` seam + started-mode merge (production)** — promote
   `start-process!` default; change `start-instance-in!` overwrite→merge.
7. **`started_test.clj`** — migrate onto `:process-launcher` + `:nrepl-connector`.
8. **`config_test.clj`** — migrate onto real file-backed user+project config.
9. **`commands_test.clj`** — migrate onto real instance + `:client-session` seam.
10. **`ops_test.clj`** — migrate onto real instance + `:client-session` seam.
11. **Verification** — focused `project-nrepl` component tests + targeted lint;
    one consuming-path check if entry-point ownership shifted.
12. **Document** — record the two per-seam strategy notes in `implementation.md`.
