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
