# Simplify `psi.app-runtime/start-tui-runtime!`

## Intent

Behaviour-preserving refactor of one incidental-complexity target selected by the `incidental-complexity-finder` skill. The goal is to reduce local comprehension burden without changing observable behaviour, meta, or spec.

This task is intentionally authored autonomously: the target, evidence, baselines, scope, and objective acceptance checks are fixed up front; lifecycle design review may refine ambiguity/architecture/inconsistency in place, but implementation must remain constrained to this target.

## Target unit

- Namespace: `psi.app-runtime`
- Var: `start-tui-runtime!`
- Arity: `5`
- File: `components/app-runtime/src/psi/app_runtime.clj`
- Line range: `603`–`705`
- Baseline selector metric key: `(psi.app-runtime, start-tui-runtime!, 5, 603)`

## Selection evidence

Selected by the incidental-complexity finder using `gap = lcc-total / max(cc, 1)`, with qualification `lcc-total >= 5.0` and `gap >= 2.0`, joined on `(ns, var, arity, line)`.

- `lcc-total`: `7.031652915638373`
- `cc`: `1`
- `gap`: `7.031652915638373`
- Per-dimension burdens:
  - flow: `0.0`
  - state: `5`
  - shape: `3.0`
  - abstraction: `9`
  - dependency: `42`
  - working-set: peak `28`, avg `27.5`, burden `36.25`
- Local findings:
  - `mutable-state-tracking` high score `5`: Explicit mutable state updates or tracked cells must be carried locally
  - `temporal-coupling` medium score `5`: Later meaning depends on earlier updates or effect timing
  - `shape-churn` high score `3.0`: Value shape changes repeatedly along the local path
  - `abstraction-mix` high score `9`: Three or more abstraction levels appear on the main path
  - `abstraction-oscillation` high score `9`: Abstraction levels alternate repeatedly across adjacent steps
  - `helper-chasing` medium score `42`: Understanding correctness requires chasing several helpers
  - `working-set-overload` high score `36.25`: Too many facts stay live simultaneously for easy local reasoning

Judgment guard: this is incidental rather than essential complexity. Cyclomatic complexity is `1`, so the burden is not irreducible decision logic. The function braids runtime context creation, nullable execution-mode installation, session bootstrapping, focus state, UI provider lifetime, session-navigation callbacks, command options, wiring dependency assembly, TUI option assembly, and provider cleanup. The likely root-cause simplification is to make coherent local data shapes and lifecycle boundaries explicit, not to superficially extract arbitrary line ranges.

Coverage hint: sibling test namespace exists at `components/app-runtime/test/psi/app_runtime_test.clj`; it references `start-tui-runtime!` directly in multiple tests, including TUI UI provider install/clear behaviour, exceptional cleanup, `/new` session targeting, startup/session-root flows, and nullable deterministic execution-mode behaviour. Phase 0 must assess whether these cover the target's observable behaviour sufficiently across nominal, edge, and boundary cases before refactoring.

## Baselines

The task directory is `munera/open/211-simplify-start-tui-runtime`.

Authoritative baselines captured from the current worktree root before any refactor:

- Local comprehension burden baseline: `munera/open/211-simplify-start-tui-runtime/before-local.json`
  - Captured with: `bb gordian local --json`
- Architectural diagnose baseline: `munera/open/211-simplify-start-tui-runtime/before-diagnose.edn`
  - Captured with: `bb gordian diagnose --edn`

Phase 1 commands must reference these worktree-root-relative paths, not bare filenames, because `gordian gate` and `gordian local` run from the worktree root.

## Blast radius

Allowed blast radius: the target unit `psi.app-runtime/start-tui-runtime!` plus the minimal surrounding helpers required to decomplect it.

No unrelated cleanup. No broad restructuring. No behavioural changes. The net-burden acceptance below is the objective guard against shifting burden into callers or neighbouring helpers.

## Behaviour-preserving constraint

This task is a behaviour-preserving refactor under the project formalism:

`refactor_minimal_semantics_spec_tests`

Meta and spec are unchanged. Existing test expectations must not be weakened. Any added tests characterize current observable behaviour; they do not redefine intended behaviour.

## Phase 0 — establish the test safety net

A behaviour-preserving refactor is unverifiable without tests that would catch a behaviour change. Refactoring is gated on sufficient coverage.

1. Assess existing coverage of `start-tui-runtime!` observable behaviour. Include tests that exercise it directly or transitively. Judge coverage against `{nominal, edge, boundary}` per the project Test formalism.
2. If coverage is insufficient, add characterization tests capturing CURRENT observable behaviour.
   - Assert state and outputs, never interactions.
   - Follow `testing-without-mocks`: use real logic dependencies; only nullable infrastructure seams are acceptable.
   - Do not weaken or delete existing expectations.
3. The Phase 0 tests must be GREEN against the unmodified code before any refactoring begins.
4. If the unit cannot be characterized safely, for example because an untestable side-effect tangle prevents a meaningful net, record that finding and either:
   - first introduce a minimal seam to make it testable, or
   - close the task with the finding because scope drift requires closure per Munera.

No refactor proceeds without a green net.

## Phase 1 — refactor under the green net

Decomplect the target with minimal, local, root-cause changes. Prefer making coherent lifecycle/data-shape boundaries explicit over superficial extraction.

Likely decomposition directions to consider, subject to Phase 0 coverage and review:

- separate the TUI runtime bootstrap context from TUI callback/wiring assembly,
- reduce live binding pressure by grouping stable dependencies into one coherent map only when that map is a real local concept,
- keep provider install/clear lifetime obvious and structurally protected,
- keep focus/session-navigation semantics unchanged, especially `/new` targeting the currently focused session.

Do not introduce compatibility shims or adapters. Do not move responsibility across architectural boundaries unless the design review explicitly confirms that the boundary already owns the responsibility.

## Acceptance criteria

### A0 — green characterization gate

Before refactoring, Phase 0 records the assessed safety net. If new characterization tests are needed, they are green against the unmodified code before production refactoring starts.

### A1 — behaviour preservation

All existing affected-area tests and all Phase 0 characterization tests are green after the refactor. Expectations are the same as before the refactor.

### A2 — net burden decreases over metric-derived touched units

Re-run from the worktree root:

```sh
bb gordian local --json > /tmp/after-local.json
```

Compare `/tmp/after-local.json` against the stored authoritative baseline `munera/open/211-simplify-start-tui-runtime/before-local.json`.

The selector's full metric key is `(ns, var, arity, line)`. For before/after comparison, reconcile rows as follows so harmless line movement during a refactor does not create an artificial delete/add pair:

1. Define a logical unit key as `(file, ns, var, arity)`.
2. Pair before/after rows by logical unit key only when that logical key has exactly one row in the baseline and exactly one row in the after run. For such paired rows, the baseline `line` remains provenance, and the after row may have a different `line`.
3. Any row not paired by logical key is compared by the selector's full metric key `(ns, var, arity, line)`. This preserves line-based disambiguation for duplicate logical units, including null-arity method-style units elsewhere in the codebase.
4. Added rows have `before.lcc-total = 0`; deleted rows have `after.lcc-total = 0`.

Define the metric-derived touched set as every reconciled row whose recomputed `lcc-total` changed between the baseline and the after run:

`{u | before(u) != after(u)}`

This set is computed from the metric, not from diffed files. It deliberately includes callers whose dependency or working-set burden changes even if their source was not edited, and it includes added or deleted helpers through the zero-on-missing rule.

Acceptance: summing `lcc-total` over this metric-derived touched set, the after total is strictly less than the before total:

`sum(after.lcc-total over touched) < sum(before.lcc-total over touched)`

### A3 — architectural no-regression gate passes

Run from the worktree root:

```sh
bb gordian gate --baseline munera/open/211-simplify-start-tui-runtime/before-diagnose.edn --fail-on new-cycles,new-high-findings --max-new-medium-findings 0
```

The command must pass with exit code `0`. The `--fail-on` flag is required; bare `gate --baseline` only evaluates checks and does not enforce this acceptance.

### A4 — target unit burden decreases

Using the same after `bb gordian local --json` run and the same authoritative `munera/open/211-simplify-start-tui-runtime/before-local.json` baseline, the target unit has lower `lcc-total` after the refactor than before.

The baseline target is the row keyed by `(psi.app-runtime, start-tui-runtime!, 5, 603)`. The `603` line component is baseline provenance from the selector's join key; implementation does not need to preserve that line number. The after target is re-identified by the unique logical unit key `(components/app-runtime/src/psi/app_runtime.clj, psi.app-runtime, start-tui-runtime!, 5)` in the after run. A4 fails if the after run has no such row or more than one such row, because the target would no longer have one executable comparison identity.

### A5 — minimal local decomplecting change

The final diff is limited to the target unit and the minimal surrounding helpers required to decomplect it. The implementation explains why any helper touched is in the blast radius. No unrelated cleanup, formatting-only churn, or broad restructure is included.
