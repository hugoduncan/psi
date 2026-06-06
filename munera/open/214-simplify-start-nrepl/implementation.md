# Implementation notes

## Design review — architectural fit (ψ)

Reviewed `design.md` for architectural fit against AGENTS.md, META.md, and
doc/architecture.md (fit only; not ambiguity/inconsistency).

Verdict: **fits**. No new actionable architectural-misfit found.

- Direction is a behaviour-preserving local helper extraction (decomplect the
  stdout-suppression interop from server lifecycle / endpoint publication / file
  side effects). Consistent with `compose > monolith`, `simple > complex`, and the
  refactoring/local-change principles.
- State boundary (architecture.md §"State boundary"): nREPL is a *runtime handle*
  whose endpoint is projected into `:state*`. The current code writes the endpoint
  via direct `accessors/set-nrepl-runtime-in!` rather than dispatch — but
  architecture.md explicitly classes this as a remaining direct-mutation pocket
  outside migrated dispatch slices. The design preserves this (behaviour-preserving),
  introducing no new boundary violation; migrating it to dispatch would be scope
  drift (Munera), not a design misfit.
- No new shims/adapters introduced (one-way guideline respected).
- A3 acceptance gate (`gordian gate --fail-on new-cycles,new-high-findings
  --max-new-medium-findings 0`) embeds architectural no-regression into acceptance.
- Phase 0 test net honors `testing-without-mocks` (assert state/outputs; prefer
  real seams over `with-redefs`/`binding`).

## Design review — ambiguity (ψ)

Reviewed `design.md` for ambiguities (not architecture/inconsistency). Two
actionable ambiguities, both in the A1/A2 acceptance unit-identity semantics:

1. **Line in the unit key vs. line drift.** A1 pins the target by
   `(ns, var, arity, line) = (..., 4, 12)`, and A2 defines the touched set
   `T = {u | before(u) != after(u)}` keyed the same way. But the intended
   refactor adds a helper in `nrepl_runtime.clj`, which shifts the `line` of
   `start-nrepl!` (12) and `stop-nrepl!` (40) (confirmed against
   `before-local.json`). With `line` in the key, the same unit appears as a
   removed+added pair across before/after, so neither A1's exact-key lookup nor
   A2's set membership is well defined under line drift. Unspecified: match by
   `(ns, var, arity)` ignoring `line`, pin `start-nrepl!` to line 12, or define a
   line-matching rule.

2. **`before(u)` for newly-created helper units.** Decomplecting moves burden
   into a new named seam (e.g. `start-server-quietly`), a source unit absent from
   `before-local.json`. A2's `before(u) != after(u)` and the `sum_before` term are
   undefined for a unit with no baseline entry. Whether/how the new helper's
   after-burden counts toward `sum_after` (and thus whether net burden is honestly
   captured) is unstated. Unspecified: treat absent `before(u)` as `0` so new
   helpers count in `sum_after`.

Non-issue checked: tests are excluded from the `local` scope
(`before-local.json` `include-tests: false`), so Phase-0 characterization tests do
not enter `T` — no ambiguity there.

## Design review follow-up — ambiguity resolutions (ψ)

Executed both unchecked design-steps added by review pass `c7f7bd4d6`. Resolved in
`design.md`'s "Objective acceptance criteria" preamble (two new convention blocks):

1. **Line-drift unit identity.** A1/A2 now match units by the line-insensitive key
   `(ns, var, arity)`; `line` is demoted to a human-readable selector label only.
   This makes A1's target lookup and A2's `T` membership well defined when the
   helper extraction shifts `start-nrepl!`/`stop-nrepl!` line numbers. A collision
   fallback (re-include `line`) is documented but unused for the target.
2. **Baseline-absent `before(u)`.** New helper units absent from
   `before-local.json` take `before(u) := 0`, so any extracted seam with positive
   burden enters `T` and is charged in `sum_after` — net-burden check (A2) stays
   honest.

No code touched; design-only refinement. Both design-steps marked done.

## Design review — inconsistency (ψ)

Reviewed `design.md` for internal inconsistency and design-vs-artifact mismatch
(not ambiguity/architecture). **No new actionable inconsistency found.**

Verified consistent against referenced artifacts:
- Line numbers (`start-nrepl!` 12–38, `stop-nrepl!` 40, public wrapper 117–122)
  match `nrepl_runtime.clj` / `app_runtime.clj`.
- `lcc-total` `6.015383232244966` matches `before-local.json`; `gap`
  `2.0051277440816553` = lcc-total / cc(3) (exact). Arity 4 / wrapper arity 1 match.
- Test ns `psi.app-runtime-nrepl-test` and both deftest names
  (`nrepl-runtime-eql-reflects-live-start-stop-test`,
  `start-nrepl-redirects-startup-chatter-to-stderr-test`) match the test file;
  consumer `psi.main` calls `app-runtime/start-nrepl!`.
- A3 gate flags (`--baseline`, `--fail-on new-cycles,new-high-findings`,
  `--max-new-medium-findings`) all exist in `bb gordian gate --help`; `--baseline`
  expects a diagnose-style snapshot, matching `before-diagnose.edn`
  (`diagnose --edn`).
- All four rejected higher-gap candidates exist at the cited namespaces
  (`start-tui-runtime!`, `print-help!`, `launcher-main/print-debug-summary!`,
  `adopt-startup-plan-into-session!`).
- Phase 0 behavioural claims (`.nrepl-port` write + `deleteOnExit`, match-port
  deletion in `stop-nrepl!`, stderr notice text, nested `when-let` publication
  using bound port) match the current code.

Non-issue checked: design's gloss that the existing stderr-redirect test shows
"both println and System/out redirected to stderr" — the test asserts both absent
from stdout and println present in stderr; the redirect mechanism
(`binding *out* *err*` + `System/setOut`) does route both to stderr, so the
behavioural description is accurate (a test-strength nuance, not a design
contradiction; Phase 0 already plans real-seam strengthening). No follow-up added.

## Plan/steps review — ambiguity (ψ)

Reviewed `plan.md` + `steps.md` for ambiguities (not architecture/inconsistency).
Two new actionable ambiguities, both distinct from the design-level A1/A2 notes above:

1. **Slice 2 skip criterion "with margin" undefined.** Steps Slice 2 says skip "if
   A1 (target lcc-total decreased) and A2 (net burden) already pass with margin",
   while plan says skip "if A1/A2 already satisfied and the change would not help".
   "With margin" introduces an unspecified buffer above a bare pass, so the
   skip-vs-perform decision for the contingent slice is not deterministic.
   Unspecified: is a strict decrease (A1) + `sum_after < sum_before` (A2) sufficient
   to skip, or is a defined margin required.

2. **`start-server-quietly` signature / `requiring-resolve` placement.** Steps Slice
   1 lists the helper as taking only `port` yet writes `(start-server :port port)`
   as if `start-server` were already bound. Unspecified whether
   `(requiring-resolve 'nrepl.server/start-server)` moves into the seam (helper takes
   only `port`) or the resolved fn is passed in (helper takes `start-server` + `port`).
   This determines where the `requiring-resolve` dependency burden lands and thus how
   A2's per-unit net-burden is charged.

Non-issue checked: `bb clojure:test:scry --namespace psi.app-runtime-nrepl-test`
matches the real ns (`psi.app-runtime-nrepl-test`) and the task's
`*command-line-args*` passthrough — no ambiguity.

## Plan/steps review follow-up — resolutions (ψ)

Executed the two ambiguity follow-up items added by the preceding review pass.
Both were plan/steps-artifact clarifications; no production code/tests touched.

1. **Slice 2 skip criterion — RESOLVED.** Replaced undefined "pass with margin"
   (steps) and "already satisfied … would not help" (plan) with one deterministic
   rule: SKIP Slice 2 iff BOTH (A1) target `start-nrepl!` lcc-total strictly
   decreased vs baseline `6.015383232244966` AND (A2) `sum_{T} after < sum_{T}
   before` over the changed-unit set `T`; otherwise (any failure or exact equality)
   PERFORM. A bare strict pass on both is sufficient — no extra margin buffer.
   Edited: steps.md Slice 2 first item, plan.md Slice-order item 3.

2. **`start-server-quietly` signature — RESOLVED.** Fixed arg list to `[port]`
   (single arg); the helper performs `(requiring-resolve 'nrepl.server/start-server)`
   internally. Rationale: the seam then owns ALL nrepl-start mechanism (resolution +
   stdout suppression), so `start-nrepl!` retains zero nrepl interop, maximally
   helping A1 (the nrepl-resolution dependency burden leaves the target unit).
   A2 accounting fixed: the `requiring-resolve` burden is charged to the seam
   (member of `T`, `before := 0`); since `sum_{T}` is invariant to which `T`-member
   holds a line, this placement is A2-neutral while improving A1.
   Edited: steps.md Slice 1 first item, plan.md "Seam shape" key decision.

## Plan/steps review — inconsistency (ψ)

Reviewed `plan.md` + `steps.md` for internal inconsistency and plan↔steps↔design↔code
mismatch (not ambiguity/architecture). **No new actionable inconsistency found.**

Verified mutually consistent:
- Slice 2 skip criterion is now uniform across plan (Slice-order 3), steps (Slice 2
  first item), design (acceptance preamble), and the prior follow-up resolutions:
  SKIP iff A1 strict-decrease AND A2 `sum_{T} after < sum_{T} before`; else PERFORM.
  No residual "with margin"/"already satisfied" divergence.
- Seam signature uniform everywhere: `start-server-quietly [port]`, `requiring-resolve`
  internal, `before := 0`, A2-neutral / A1-improving. Plan, steps, design agree.
- Baseline `lcc-total 6.015383232244966` (line 12, arity 4) matches `before-local.json`
  and is quoted identically in plan, steps, design.
- Line numbers match code: `start-nrepl!` 12–38, `stop-nrepl!` 40, wrapper
  `psi.app-runtime/start-nrepl!` 117–122 (`app_runtime.clj`).
- Acceptance commands (A1 `gordian local --json`, A2 `T`/sum rule, A3
  `gordian gate --baseline … --fail-on … --max-new-medium-findings 0`, A4
  `clojure:test:scry` + `clojure:test:unit` + `lint`) are identical between design and
  steps Slice 3; no flag/path drift.
- Phase-0 characterization items (steps' four tests: `.nrepl-port` write+deleteOnExit,
  `stop-nrepl!` match-port deletion, bound-port session publication, stderr notice)
  cover exactly the design Phase-0 bullets and the "four uncovered behaviours" the plan
  cites — no count mismatch.
- Test ns `psi.app-runtime-nrepl-test` + both existing deftest names match the test
  file; design's note that the existing test uses `with-redefs`/`binding` matches code
  (lines 59/68).

Non-issue checked: steps Slice 3 close-out says "remove the task's entry from
`munera/plan.md`", but task 214 has no entry there (it is unordered-open per Munera).
Removal is a conditional no-op under Munera semantics — not an inconsistency, no
follow-up added.
