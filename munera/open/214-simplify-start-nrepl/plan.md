# Plan — Simplify `psi.app-runtime.nrepl-runtime/start-nrepl!`

## Approach

Behaviour-preserving, root-cause decomplection of one incidental-complexity target.
The body of `start-nrepl!` (arity 4) braids three unrelated concerns on a near-flat
path (cc 3). The refactor separates them so each abstraction level reads on its own,
collapsing the `abstraction-mix`/`abstraction-oscillation` (8/8) and shrinking the
live working set.

Two-phase, test-gated:

- **Phase 0 (gate):** Establish a green characterization-test net of the *current*
  observable behaviour BEFORE any production edit. Asserts state/outputs, never
  interactions (`testing-without-mocks`). No refactor proceeds without green.
- **Phase 1 (refactor):** Under the green net, extract the stdout-suppression Java
  interop dance into a small named seam (working name `start-server-quietly`) so the
  interop concern stops braiding with server lifecycle, endpoint publication, and
  `.nrepl-port` file side effects. Optionally lift endpoint-map construction to a
  local to remove the duplicated `{:host :port :endpoint}` literal, only if it
  reduces burden without expanding blast radius.

### Key decisions

- **Seam shape:** `start-server-quietly` takes the requested `port`, performs the
  `System/out` save → `binding *out* *err*` → `setOut(stderr)` → `start-server` →
  `finally` restore, and returns the server map. This isolates *all* Java interop
  for stdout routing into one unit; `start-nrepl!` keeps only orchestration.
- **Identity under line drift:** Adding a helper shifts `start-nrepl!`/`stop-nrepl!`
  line numbers. Per design A1/A2 conventions, units are matched by line-insensitive
  key `(ns, var, arity)`; new helpers take `before(u) := 0`.
- **Preserve observable surfaces:** returned server map, bound (random) port,
  `.nrepl-port` contents + `deleteOnExit`, `nrepl-runtime-atom` value, gated session
  `:nrepl-runtime` publication (nested `when-let`, bound port not requested port),
  and stderr (not stdout) routing of startup chatter + connection notice.
- **No dispatch migration:** the direct `accessors/set-nrepl-runtime-in!` call is an
  accepted remaining direct-mutation pocket (architecture.md); migrating it is scope
  drift, not part of this task.

## Risks

- **R1 — interop seam changes routing.** Mis-extracting the `binding`/`setOut`/
  `finally` order could leak chatter to stdout. Mitigated by the existing stderr
  redirect test plus Phase 0 strengthening.
- **R2 — net-burden (A2) regression.** Extraction moves burden into a new seam that
  is charged in `sum_after` (before = 0). The seam must be small enough that
  `sum_after < sum_before` over `T`. Mitigated by keeping the seam minimal and
  re-checking A1/A2 after the edit; if net burden rises, reconsider the seam
  boundary.
- **R3 — A1 not decreasing.** If orchestration burden does not drop after moving
  interop out, A1 fails. Mitigated by also collapsing the duplicated endpoint-map
  literal into one local.
- **R4 — characterization gap.** `.nrepl-port` write and stderr-notice tests rely on
  real filesystem/stderr seams; flakiness or cwd assumptions could cause false reds.
  Mitigated by mirroring the existing test's tmp-dir + `user.dir` pattern and
  asserting on captured streams / file contents (state, not interactions).
- **R5 — existing test uses `with-redefs`/`binding`.** Prefer real seams when adding
  new characterization tests; do not weaken existing assertions.

## Slice order

1. **Slice 0 — characterization net (Phase 0 gate).** Add characterization tests for
   the four uncovered behaviours, confirm GREEN against unmodified code. Commit.
2. **Slice 1 — extract `start-server-quietly` seam (Phase 1).** Move the stdout-
   suppression interop into the helper; `start-nrepl!` calls it. Keep behaviour
   identical. Re-run net (A4 tests, A1/A2, A3, lint). Commit.
3. **Slice 2 — collapse incidental duplication (Phase 1, optional).** Lift the
   endpoint-map literal to a single local if it lowers burden without widening blast
   radius. Re-run acceptance. Commit. Skip if A1/A2 already satisfied and the change
   would not help.
4. **Slice 3 — acceptance verification + close-out.** Run all of A1–A5, record
   results in implementation.md, confirm minimality (A5). Commit.

Each slice is a vertical, independently-verifiable change. Slice 0 strictly precedes
all production edits (gate). Slice 2 is contingent on A1/A2 outcome after Slice 1.
