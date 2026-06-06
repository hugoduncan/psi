# Simplify `psi.app-runtime.nrepl-runtime/start-nrepl!`

## Intent

Behaviour-preserving refactor of one incidental-complexity target selected by the
`incidental-complexity-finder` skill. The goal is to reduce local comprehension
burden without changing observable behaviour, meta, or spec.

This task is intentionally authored autonomously: the target, evidence, baselines,
scope, and objective acceptance checks are fixed up front. The lifecycle
design-review loop may refine ambiguity/architecture/inconsistency in place of live
user collaboration, but implementation must remain constrained to this target.

## Target unit

- Namespace: `psi.app-runtime.nrepl-runtime`
- Var: `start-nrepl!`
- Arity: `4`
- File: `components/app-runtime/src/psi/app_runtime/nrepl_runtime.clj`
- Line range: `12`–`38`
- Baseline selector metric key: `(psi.app-runtime.nrepl-runtime, start-nrepl!, 4, 12)`

Call path: the public `psi.app-runtime/start-nrepl!` (1-arity wrapper, lines
117–122 of `app_runtime.clj`) delegates to this arity-4 unit; consumers (e.g.
`psi.main`) and tests reach it through that wrapper.

## Selection evidence

Selected by the incidental-complexity finder using `gap = lcc-total / max(cc, 1)`,
with qualification `lcc-total >= 5.0` and `gap >= 2.0`, joined on
`(ns, var, arity, line)`.

- `lcc-total`: `6.015383232244966`
- `cc`: `3`
- `gap`: `2.0051277440816553`
- Per-dimension burdens:
  - flow: `2.0`
  - state: `4`
  - shape: `6`
  - abstraction: `8`
  - dependency: `21`
  - working-set: peak `11`, avg `10.125`, burden `10.5625`
- Local findings:
  - `conditional-return-shape` medium score `6`: Branches return materially different shapes
  - `abstraction-mix` high score `8`: Three or more abstraction levels appear on the main path
  - `abstraction-oscillation` high score `8`: Abstraction levels alternate repeatedly across adjacent steps
  - `helper-chasing` medium score `21`: Understanding correctness requires chasing several helpers
  - `working-set-overload` high score `10.5625`: Too many facts stay live simultaneously for easy local reasoning

### Why this is incidental, not essential (selection rationale)

The four higher-gap candidates were rejected during selection:

1. `psi.app-runtime/start-tui-runtime!` (gap 6.82) — refactored to completion by
   the immediately-preceding task 213; its residual burden is essential
   composition-root wiring (task-213 reviewers concurred). Re-targeting = churn.
2. `psi.main/print-help!` (gap 5.86) — a flat sequence of `println` literals;
   irreducible/essential output text.
3. `psi.launcher-main/print-debug-summary!` (gap 2.96) — a flat `println` debug
   formatter; essential output.
4. `psi.app-runtime/adopt-startup-plan-into-session!` (gap 2.75) — a startup
   composition root whose documented 11-step single-pass ordering (task 161) *is*
   the essential algorithm; its threading burden is essential wiring.

`start-nrepl!` is the first candidate whose burden is genuinely **incidental** and
decomplectable at root cause: the body **braids three unrelated concerns** on a
near-flat path (cc 3):

- **Java stdout suppression** — saving `System/out`, `binding *out* *err*`,
  installing a stderr `PrintStream`, and restoring in a `finally`, purely to keep
  nREPL's startup chatter off protocol stdout.
- **Server lifecycle + endpoint publication** — starting the server, resetting the
  `nrepl-runtime-atom`, and (conditionally) writing the endpoint into session state.
- **Editor-discovery side effects** — `.nrepl-port` file `spit`/`deleteOnExit` and
  the stderr connection notice.

The `abstraction-mix`/`abstraction-oscillation` (8/8) come directly from
alternating low-level Java interop with high-level session dispatch in one body;
the `conditional-return-shape` reflects the interop-vs-orchestration level shift.
This is braiding, not decision logic — the core incidental-complexity signature.

## Scope / blast radius

- The target unit `start-nrepl!` (arity 4) PLUS the minimal surrounding helpers
  required to decomplect it — primarily extracting the stdout-suppression dance
  into a small named seam (e.g. `start-server-quietly` / `with-stderr-stdout`) so
  the interop concern stops braiding with orchestration.
- No unrelated cleanup. `stop-nrepl!`, `active-session-id-in-session-state`, and
  the public `app-runtime/start-nrepl!` wrapper are touched only if strictly
  required to decomplect the target; the net-burden acceptance (A2) keeps this
  honest.
- Constraint: behaviour is identical — meta/spec are unchanged and existing test
  expectations are not weakened. Observable surfaces to preserve: returned server
  map, bound port, `.nrepl-port` file contents, `nrepl-runtime-atom` value,
  session `:nrepl-runtime` publication, and the stderr (not stdout) routing of
  startup chatter and the connection notice.

## Phase 0 — establish a test safety net (gate before any refactor)

A behaviour-preserving refactor is unverifiable without tests that would catch a
behaviour change, so refactoring is gated on sufficient coverage of the target's
observable behaviour, judged against `{nominal, edge, boundary}`.

Existing coverage (`components/app-runtime/test/psi/app_runtime_nrepl_test.clj`,
exercising the target transitively through `#'app-runtime/start-nrepl!`):

- `nrepl-runtime-eql-reflects-live-start-stop-test` — nominal: real server start,
  port reflected into session EQL (`:psi.runtime/nrepl-host|port|endpoint`), and
  cleared after stop.
- `start-nrepl-redirects-startup-chatter-to-stderr-test` — boundary: stdout chatter
  (both `println` and `System/out`) is redirected to stderr.

Coverage assessment to perform in Phase 0, adding **characterization tests** (of
CURRENT observable behaviour; asserting state/outputs, never interactions; per
`testing-without-mocks`) where gaps exist:

- `.nrepl-port` file is written with the bound port and marked `deleteOnExit`
  (and removed by `stop-nrepl!` only when its contents match the running port).
- Session `:nrepl-runtime` publication occurs only when a `ctx` and an
  active session id are present (the nested `when-let` boundaries), and uses the
  bound (random) port, not the requested port.
- The stderr connection notice (`"  nREPL : host:port ..."`) is emitted.
- The second existing test currently uses `with-redefs`/`binding` (interaction-ish
  seams); prefer real seams where practical when strengthening the net.

Gate: these characterization tests, plus all existing tests for the affected area,
must be GREEN against the unmodified code before any refactoring begins. If the
unit cannot be characterized safely, record the finding and either (a) first
introduce a minimal seam to make it testable, or (b) close the task with the
finding (scope drift → close per Munera). No refactor proceeds without a green net.

## Phase 1 — refactor under the green net

Decomplect the target with minimal, local, root-cause changes (not superficial
extraction): separate the stdout-suppression interop concern from server
lifecycle / endpoint publication / file side effects so each level of abstraction
reads on its own, collapsing the abstraction oscillation and shrinking the live
working set.

### Objective acceptance criteria

All Gordian commands below run from the **worktree root** (cwd); baseline paths are
worktree-root-relative to this task directory so they resolve from there.

**Unit-identity convention for A1/A2 (line-drift resolution).** The extraction adds
a helper to `nrepl_runtime.clj`, which shifts the `line` of `start-nrepl!` (12) and
`stop-nrepl!` (40) between `before-local.json` and the after-`local` run. To keep
A1's lookup and A2's set membership well defined under that drift, units are matched
by the **line-insensitive key `(ns, var, arity)`** for all A1/A2 comparisons; the
`line` field is ignored for identity (it remains only as the human-readable selector
label `(..., 4, 12)`). If two units in one run collide on `(ns, var, arity)` (e.g.
two same-arity defs), fall back to including `line` to disambiguate that pair only;
no such collision exists for the target.

**Baseline-absent units (`before(u)` default).** A unit present in the after-`local`
run but absent from `before-local.json` — i.e. a newly-created helper such as the
extracted stdout-suppression seam — is assigned `before(u) := 0`. Consequently any
new helper with `after(u) > 0` satisfies `before(u) != after(u)`, enters `T`, and
its after-burden counts in `sum_after`, so the net-burden check (A2) honestly
charges the refactor for burden moved into new seams.

- **A1 — burden reduction (named comparison source).** Re-run
  `bb gordian local --json` from the worktree root and compare against the stored
  `munera/open/214-simplify-start-nrepl/before-local.json` captured in this task —
  that file is the single authoritative baseline for every "decreased" check (not
  the selector's emitted evidence, not a fresh pre-refactor recompute). The target
  unit's `lcc-total`, matched by the line-insensitive key `(ns, var, arity)` =
  `(psi.app-runtime.nrepl-runtime, start-nrepl!, 4)` (selector label line `12`),
  **decreased** versus its `before-local.json` value of `6.015383232244966`.

- **A2 — no relocated complexity (REVISED; see "A2 redefinition" below).** Each
  extracted seam is genuinely simpler than the unit it was carved out of:
  `for-all s in (after-units \ before-units): after(s) < after(target)`. Concretely,
  every newly-created helper's per-unit `lcc-total` is strictly less than the
  target's post-refactor `lcc-total`. This rejects superficial/inverted extraction
  (moving a bigger tangle into a new unit) while not penalising genuine
  decomplection. Units are identified by the line-insensitive key `(ns, var, arity)`
  with baseline-absent units taking `before(u) := 0` (per the conventions above).

  **A2 redefinition (rationale — supersedes the original net-sum formulation).**
  The original A2 required `sum_{u in T} after(u) < sum_{u in T} before(u)` over the
  metric-touched set `T = {u | before(u) != after(u)}`. That formulation is
  **provably unsatisfiable by any behaviour-preserving decomplection of this target**
  and was a category error:
  - Gordian's per-unit per-dimension transform is `log1p-over-scale` (concave,
    `f(0)=0`), hence sub-additive: `f(b1)+f(b2) >= f(b1+b2)`. Extracting a helper
    splits one unit's raw burden across two units, so the *summed normalized* burden
    rises even when raw burden is conserved (verified empirically: seam-only, the
    Pareto-optimum, nets `+0.3565`; the seam's 7 raw deps normalize to `0.773`,
    nearly the target's `0.887` dependency reduction, purely from concavity).
  - The task's selection rationale and Phase-1 approach *prescribe extraction* (the
    `abstraction-mix`/`abstraction-oscillation` braiding is in-body; the only
    behaviour-preserving way to unbraid is to move the interop out). So the original
    A2 structurally *forbids the refactor the task selects for*.
  - Comprehension burden is **local** (per-unit) — a reader understands one unit at a
    time. Summing normalized per-unit burdens across target+seam double-counts
    decomplection's benefit as a cost. The genuine guard A2 was meant to provide
    ("don't just relocate complexity into a new seam") is correctly expressed as
    "the seam is simpler than the residual target" (above), not as a net-sum bound.
  The genuine intent — reduce the *target* unit's local comprehension burden — is
  captured by A1 (which passes strongly, −7.7%); the no-architectural-regression
  guard is captured by A3.

- **A3 — architectural no-regression (enforcing gate).** Run:
  ```
  bb gordian gate --baseline munera/open/214-simplify-start-nrepl/before-diagnose.edn \
     --fail-on new-cycles,new-high-findings --max-new-medium-findings 0
  ```
  The bare `gate --baseline` only EVALUATES checks; `--fail-on` makes new cycles
  and new high findings FAIL (non-zero exit), and `--max-new-medium-findings 0`
  enforces the "no new medium findings" half. The gate must PASS (exit 0) with
  these flags.

- **A4 — tests green.** The Phase 0 characterization tests and all existing tests
  for the affected area are GREEN (same expectations as before the refactor):
  `bb clojure:test:scry --namespace psi.app-runtime-nrepl-test` and the broader
  `bb clojure:test:unit`, plus `bb lint`.

- **A5 — minimality.** The change is minimal, local, and decomplecting — no
  unrelated cleanup; touched helpers stay within the blast radius above.

## Baselines captured

- `munera/open/214-simplify-start-nrepl/before-local.json` — `bb gordian local --json`
  (bare, no `--sort`): per-unit comprehension-burden baseline for A1/A2.
- `munera/open/214-simplify-start-nrepl/before-diagnose.edn` — `bb gordian diagnose --edn`:
  architectural gate baseline for `gordian gate --baseline` (A3).

## Autonomy note

This generated task's design is intentionally authored autonomously (objective,
narrow: fixed target + preserve behaviour + objective acceptance). The
review-task-design loop iterates ambiguity/architecture/inconsistency in place of
live user collaboration.
