# 204 — Incidental-Complexity Simplification Workflow

## Intent

Give psi a repeatable, autonomous capability to **simplify one aspect of the
system per run** by targeting *incidental* complexity — complexity that comes
from how code is built, not from the problem it solves. Each run selects the
single highest-opportunity unit, opens a Munera task constrained to a
behaviour-preserving refactor, and drives that task through the full
`task-lifecycle` workflow.

Running it repeatedly walks the codebase down its incidental-complexity
gradient, one isolated, reviewable change at a time.

## Why

`gordian complexity` alone ranks by cyclomatic complexity, which surfaces
*essential* complexity (e.g. flat dispatch/registration tables with high CC
that are irreducible). Those are false positives for simplification. The
distinguishing signal for incidental complexity is **comprehension burden the
branching does not explain** — high `gordian local` burden against low/moderate
CC. We validated this discriminator manually: `gap = burden / cc` cleanly
separated braiding/threading/abstraction-oscillation (incidental, refactorable)
from genuine decision logic (essential, leave alone).

## Scope

Two deliverables:

1. **`incidental-complexity-finder` skill** — encodes the methodology for choosing the single
   highest incidental-complexity unit, including the essential-vs-incidental
   judgment guard. Usable both interactively and from the workflow.
2. **A two-step orchestration workflow** — selects a target, creates a
   constrained Munera task, and delegates the task through `task-lifecycle` via
   a thin worktree-resolving wrapper (`task-lifecycle-in-worktree`) so the
   lifecycle runs in the worktree established in step 1.

### In scope (v1)
- Function/executable-unit-level incidental complexity (the `gap` method).
- One target per workflow run; naturally idempotent (each run recomputes from
  live code and picks the next-highest).

### Out of scope (v1 / non-goals)
- Architectural-level simplification (cycles, god-modules, `cross-lens-hidden`
  missing abstractions). That is a different selector and often does not fit a
  single task; it is a later sibling, not this task.
- Modifying or replacing the existing `complexity-reduction-pr` workflow. This
  is a new, distinct capability (different endpoint: full task lifecycle vs.
  quick PR). Sharing the `incidental-complexity-finder` skill between them is a possible later
  follow-up, explicitly not in this task.
- Promoting the `gap` computation into a first-class `gordian` subcommand.
  Worthwhile later; v1 computes it from the two existing lenses.

## Deliverable 1 — Selector skill

A `SKILL.md` (under the project skills root) that, when applied, deterministically
narrows and then judges:

1. Run both lenses in machine form:
   - `bb gordian local --sort total --json` (comprehension burden per unit)
   - `bb gordian complexity --json` (cyclomatic complexity per unit)
2. Join on `(ns, var, arity)` and compute `gap = lcc-total / max(cc, 1)`. This
   join is a **fixed recipe embedded verbatim in the skill** (a canonical
   snippet the agent runs as-is), not ad-hoc code, so selection is reproducible.
   **Unmatched-row rule (inner join on the `local` side):** the join is keyed by
   the `local` lens — only units present in *both* lenses are candidates. A
   `local` unit with **no matching `cc` row is dropped** (excluded from
   candidates), never defaulted to `cc=1`; defaulting to `cc=1` would inflate
   `gap` toward false qualification, so it is explicitly forbidden. `complexity`
   units with no matching `local` row are irrelevant (they carry no `lcc-total`)
   and are likewise absent from the candidate set. `max(cc, 1)` therefore guards
   only the *zero-cc matched* case (a matched unit whose `cc` is reported as 0),
   not the missing-row case. Both lenses emit `ns`/`var`/`arity` and a `units`
   array, so the join is total over the shared key space.
3. **Qualification filter** — a unit qualifies iff:
   `lcc-total ≥ 5.0` **and** `gap ≥ 2.0`. Rank qualifying units by `gap`.
   If no unit qualifies, there is no target (drives the workflow's early stop).
4. Apply the **judgment guard**: read the **top 5 qualifying units by `gap`** and
   confirm the burden is incidental (braiding, state threading, abstraction
   oscillation, helper-chasing, working-set overload on low/moderate CC) and
   **not** an essential, irreducible algorithm. Choose the first that passes;
   discard essential-complexity false positives. If none of the top 5 pass,
   report no qualifying target.
5. Emit one chosen target with evidence: `ns`, `var`, `arity`, file, line range,
   `lcc-total` with per-dimension burdens, `cc`, `gap`, the `local` findings, and
   a **coverage hint** (whether a sibling test namespace exists and whether any
   test references the target var) so the task knows what test net it faces.

The skill must explicitly state its scope is a **single executable unit** and
must encode the false-positive guard (high CC alone is not a target). The
thresholds (`lcc-total ≥ 5.0`, `gap ≥ 2.0`, top-5 guard depth) are stated
explicitly in the skill and are tunable.

## Deliverable 2 — Workflow

A multi-step `.edn` orchestration workflow that simplifies one aspect end to
end. Required behaviour:

**Step 1 — select + establish target (`:session`)**
- Tools include `read`, `bash`, `edit`, `write`, `work-on`; skills include the
  `incidental-complexity-finder`, `gordian`, and `code-shaper`.
- Refresh base: `git fetch origin master`; treat `origin/master` as the
  authoritative base.
- Apply the `incidental-complexity-finder` skill to choose the single highest incidental-complexity
  unit.
- **Early stop:** if no qualifying unit exists, stop and report — do not create
  a worktree or task.
- Create an isolated worktree via `work-on` based on `origin/master`, described
  from the target (e.g. `simplify <target>`).
- Capture two `before` baselines and store them in the task directory (Munera
  preserves unknown files):
  - `before-local.json` — `bb gordian local --json` (per-unit burden baseline),
  - `before-diagnose.edn` — `bb gordian diagnose --edn` (architectural gate
    baseline for `gordian gate --baseline`).
  - **Path resolution:** these baselines live under the task directory, but
    Phase 1 runs `gordian gate`/`gordian local` from the **worktree root (cwd)**,
    where a bare filename does not resolve. The generated `design.md` therefore
    references each baseline by its **worktree-root-relative task-dir path**
    — `munera/open/NNN-slug/before-diagnose.edn` and
    `munera/open/NNN-slug/before-local.json` — (the task dir is inside the
    worktree, so this path resolves from cwd). Step 1 records the concrete task
    path in the generated task so Phase 1 commands use the resolvable path
    rather than a bare filename.
- Allocate the next task id, create `munera/open/NNN-slug/design.md` for the
  refactor task (see "Generated task design" below).
- Commit the task creation.
- Emit a **structured handoff** as the step output: a small Markdown block
  carrying machine-friendly bullet lines, at minimum:
  - `worktree_path:` — the absolute path of the worktree created via `work-on`,
  - `munera_task_path:` — the `munera/open/NNN-slug` task path.
  This mirrors the `worktree_path:`/`munera_task_path:` handoff blob that
  `gh-issue-implement.edn`'s `design` step emits and that
  `implement-task-in-worktree`'s `resolve-worktree` step consumes. Emitting the
  worktree path explicitly (rather than only the bare task path) is what makes
  cross-`:delegate` worktree continuity work on the **verified** wrapper path
  (see Verified Facts) instead of an unverified silent-inheritance assumption.

**Step 2 — run the lifecycle in the worktree (`:delegate`)**
- Delegate to a thin **worktree-resolving wrapper** around `task-lifecycle`
  (`:target "task-lifecycle-in-worktree"`), structurally identical to the
  verified `implement-task-in-worktree` wrapper but sub-delegating to
  `task-lifecycle` instead of `implement-task`. The wrapper:
  1. `resolve-worktree` (`:session`, tools include `work-on`): extract
     `worktree_path:` and `munera_task_path:` from the step-1 handoff, call
     `work-on` with the extracted worktree path to set the delegated run's
     session worktree, then emit **only** the bare task path on a single line.
  2. `lifecycle` (`:delegate` `:target "task-lifecycle"`): wired
     `:prompt-string {:type :map :fields {:input {:from {:step "resolve-worktree" :yield :text}}}}`,
     so it receives `{:input "munera/open/NNN-slug"}` — the exact map shape every
     `task-lifecycle` sub-workflow reads via `{:from :workflow-input :path [:input]}`.
- **Handoff wiring (grammar-conformant):** step-2 sources its `:input` from
  step-1's yielded handoff text via the verified delegate-yield grammar:
  ```
  :prompt-string {:type :map
                  :fields {:input {:from {:step "<step-1-name>" :yield :text}}}}
  ```
  This routes the whole structured handoff blob into the wrapper's
  `resolve-worktree` step (which reads `{{input}}` and re-establishes the
  worktree), exactly as `gh-issue-implement.edn`'s `implement` delegate routes
  its `design`-step handoff into `implement-task-in-worktree`. Keeping the
  handoff on this one verified data-flow path honours the `one_way` principle.
- **Worktree continuity is established, not assumed:** the wrapper's
  `resolve-worktree` `:session` step **re-calls `work-on`** from the threaded
  `worktree_path:` field before sub-delegating, so the lifecycle runs in
  step-1's worktree by the same proven mechanism as `implement-task-in-worktree`
  — not by relying on a fresh `:delegate` silently inheriting an outer sibling
  step's worktree (see Verified Facts).

The outer workflow stays at two steps (select+create, then delegate to the
worktree-resolving lifecycle wrapper) and ends with a completed, reviewed task
on the local worktree branch — it does **not** push or open a PR (the user
decides on PR). The `task-lifecycle-in-worktree` wrapper is a thin two-step
adapter (resolve-worktree + lifecycle delegate), not additional orchestration
logic. No workflow-level verification step is added: the generated task's
acceptance criteria carry the objective checks, and the lifecycle's own
implement/review steps enforce them.

## Generated task design (the behaviour-preservation contract)

Each task this workflow creates is a **behaviour-preserving refactor** (the
`refactor_minimal_semantics_spec_tests` formalism), executed in two ordered
phases. Its `design.md` must state:

- The target unit and the captured incidental-complexity evidence.
- **Blast radius:** the target unit **plus the minimal surrounding helpers
  required to decomplect it**; no unrelated cleanup. The net-burden acceptance
  below keeps this honest.
- Constraint: **behaviour is identical** — meta/spec are unchanged; existing
  test expectations are not weakened.

**Phase 0 — establish a test safety net (gate before any refactor).**
A behaviour-preserving refactor is unverifiable without tests that would catch a
behaviour change, so refactoring is **gated** on sufficient coverage:
- Assess existing coverage of the target's observable behaviour (tests that
  exercise the unit directly or transitively), judged against
  `{nominal, edge, boundary}` per the project Test formalism.
- If insufficient, **add characterization tests** capturing *current* observable
  behaviour (state/outputs, never interactions; per `testing-without-mocks`).
  Adding tests that document existing behaviour does not change behaviour or
  spec — it strengthens the proof.
- These tests must be **green against the unmodified code** before any
  refactoring begins.
- If the unit cannot be characterized safely (e.g. an untestable side-effect
  tangle), the task records that finding and either (a) first introduces a
  minimal seam to make it testable, or (b) is closed with the finding (scope
  drift → close per Munera). No refactor proceeds without a green net.

**Phase 1 — refactor under the green net.** Decomplect the target with minimal,
local, root-cause changes (not superficial extraction).

- **Objective acceptance criteria:**
  - **Burden reduction (A5 — named comparison source).** Re-run
    `bb gordian local --json` from the worktree root and compare against the
    stored `munera/open/NNN-slug/before-local.json` captured in Step 1 — that
    file is the single authoritative baseline for every "decreased" check (not
    the selector's emitted evidence, not a fresh pre-refactor recompute). The
    target unit's `lcc-total` (keyed by `(ns, var, arity, line)` — the same
    unique key the selector's join uses; `line` disambiguates same-named
    null-arity `defmethod` units that share `(ns, var, arity)`, e.g. the 51
    `execute-effect!` defmethods that collapse to one key without it)
    **decreased** versus its `before-local.json` value.
  - **Net burden (A2 — "touched units" defined).** "Touched units" means **every
    unit whose recomputed `lcc-total` changed** between `before-local.json` and
    the after-`local` run — i.e. the set is computed from the metric, not from
    the diff/touched files. This deliberately includes callers whose
    `dependency`/`working-set` burden shifts even though their source was not
    edited, because `local` is recomputed globally; scoping to changed *files*
    or changed *source* would let a refactor hide relocated burden in an
    untouched caller. The acceptance is: summing `lcc-total` over this
    metric-derived touched set, the **after total is strictly less than the
    before total**. The check is objective, with each unit `u` identified by
    `(ns, var, arity, line)` (the selector's unique join key, so null-arity
    `defmethod` units do not collapse together): `{u | before(u) ≠ after(u)}`,
    then `Σ after < Σ before`.
  - **Architectural no-regression (A3 — enforcing gate flags).** Run
    `bb gordian gate --baseline munera/open/NNN-slug/before-diagnose.edn
    --fail-on new-cycles,new-high-findings --max-new-medium-findings 0`. The bare
    `gate --baseline` only *evaluates* checks; the `--fail-on` flag is what makes
    new cycles and new high findings **fail** (non-zero exit), and
    `--max-new-medium-findings 0` enforces the "no new medium findings" half of
    the claim. The gate must **pass** (exit 0) with these flags. (Gate check
    names confirmed against the live CLI: `new-cycles`, `new-high-findings`,
    `new-medium-findings`/`--max-new-medium-findings`.)
  - the Phase 0 characterization tests and all existing tests for the affected
    area are **green** (same expectations as before the refactor),
  - the change is minimal, local, and decomplecting.

### Autonomy note
AGENTS.md normally expects task design to be refined collaboratively with the
user before planning. This workflow is autonomous; that is acceptable **here
specifically** because a simplification design is objective and narrow (fixed
target + preserve behaviour + objective acceptance), and `task-lifecycle`'s
`review-task-design` loop automatically iterates ambiguity/architecture/
inconsistency in place of live user collaboration.

## Locked decisions

1. Selection is a **skill** (judgment-bearing, reusable), not an inlined bash
   one-liner; it uses the `gap = burden/cc` method plus the essential-vs-
   incidental guard, with the join embedded as a fixed recipe.
2. Qualification threshold: `lcc-total ≥ 5.0 ∧ gap ≥ 2.0`; judgment guard reads
   the top 5 by `gap`. Thresholds are explicit and tunable. A real early-stop
   exists when nothing qualifies.
3. The generated `design.md` is a two-phase behaviour-preserving contract:
   Phase 0 establishes a green test safety net (characterization tests if
   coverage is insufficient) and **gates** all refactoring; Phase 1 refactors.
4. Objective acceptance uses the **`local` lens before/after** for the target +
   net-across-touched-units burden reduction (not `gordian compare`, which is
   architectural), plus `gordian gate` for no-regression and green tests.
   "Before" is always the stored `before-local.json` baseline; "touched units"
   is the metric-derived set of units whose `lcc-total` changed. The gate is run
   with `--fail-on new-cycles,new-high-findings --max-new-medium-findings 0` so
   the "no new cycles / no new high/medium findings" claim is actually enforced
   (bare `gate --baseline` only evaluates, it does not fail on these).
5. v1 scope is function-level incidental complexity only; architectural is a
   later sibling.
6. Autonomy is acceptable for this task class (objective, narrow design);
   `review-task-design` substitutes for live collaboration.
7. Endpoint is a completed, reviewed task on a local worktree branch — no
   push/PR in v1.
8. Build a new workflow; do not extend `complexity-reduction-pr`. Sharing
   `incidental-complexity-finder` later is a possible follow-up, not part of
   this task.
9. No persistent skip-list in v1: repeated runs rely on the judgment guard to
   avoid re-picking an essential unit. Explicit v1 limitation.
10. Workflow name: `reduce-incidental-complexity` (tunable).
11. **Cross-`:delegate` worktree continuity uses the verified
    worktree-resolving-wrapper pattern, not bare sibling-step inheritance.**
    Step-1 emits a structured handoff carrying `worktree_path:` +
    `munera_task_path:`; step-2 delegates to a thin `task-lifecycle-in-worktree`
    wrapper (resolve-worktree `:session` re-calls `work-on`, then sub-delegates
    to `task-lifecycle`), structurally identical to `implement-task-in-worktree`.
    Chosen over a direct `task-lifecycle` delegate relying on session
    `:worktree-path` inheritance, which — though the runtime copies the parent
    worktree into child sessions — has no workflow precedent for a *direct*
    delegate and would be an unverified assumption. (Resolves design-review I1.)

## Verified facts (grounding)

- **Lifecycle input contract:** every `task-lifecycle` sub-workflow takes
  `{:input "munera/open/NNN-slug"}` — a bare Munera task path string. Confirmed
  in `task-lifecycle.edn`: the first sub-workflow reads
  `:input {:from :workflow-input :path [:input]}`.
- **Step→step delegate-yield handoff:** a `:delegate` step sources a
  `:prompt-string` `:map` field from a prior step's text output via
  `:fields {:input {:from {:step "<name>" :yield :text}}}`. Verified precedent:
  `gh-issue-implement.edn`'s `implement` and `review` `:delegate` steps wire
  `:input` from a prior step's `:yield :text` with exactly this form. Here the
  outer step-2 routes step-1's whole structured-handoff `:yield :text` into the
  `task-lifecycle-in-worktree` wrapper; the wrapper's `resolve-worktree`
  `:session` step extracts the bare task path and re-yields it, and the
  wrapper's inner `lifecycle` delegate wires that bare path into `{:input "…"}`
  for `task-lifecycle` — identical to how `implement-task-in-worktree`'s
  `resolve-worktree` → `implement` delegate produces `{:input <task-path>}` for
  `implement-task`.
- **Worktree ownership (and the verified handoff mechanism):** neither
  `task-lifecycle` nor any of its sub-workflows (`review-task-design`,
  `create-task-plan`, `review-task-plan`, `implement-task`,
  `review-task-implementation`) creates a worktree or contains a `work-on`
  step; each sub-workflow reads only `{:input <task-path>}`. So a `:delegate`
  step that targets `task-lifecycle` **directly** has no mechanism to establish
  step-1's worktree. The verified precedent for crossing a `:delegate` boundary
  into a prior step's worktree is **not** bare sibling-step inheritance: in
  `gh-issue-implement.edn` the outer `implement` step delegates to the
  **`implement-task-in-worktree` wrapper**, whose own first `:session` step
  (`resolve-worktree`, tools include `work-on`) re-extracts a `worktree_path:`
  field from a structured handoff blob and **re-calls `work-on`** to set the
  delegated run's session worktree *before* it sub-delegates to `implement-task`.
  Worktree continuity is therefore carried by (1) an explicit `worktree_path:`
  field threaded through the handoff text and (2) a worktree-resolving wrapper
  that re-calls `work-on` — not by a fresh `:delegate` silently inheriting an
  outer sibling step's worktree. This task adopts that same verified pattern (see
  Step 1 / Step 2): step-1 emits a structured handoff carrying both
  `worktree_path:` and `munera_task_path:`, and step-2 delegates to a thin
  worktree-resolving wrapper around `task-lifecycle`.
  (Runtime note: child sessions do inherit `:worktree-path` from their parent
  session data — `child-session-state` copies `(:worktree-path parent-sd)` —
  but relying on that for a *direct* `task-lifecycle` delegate is an unverified
  cross-run-session assumption with no workflow precedent; the wrapper pattern
  above is the proven path and is used instead.)

## Acceptance criteria (this task)

- The `incidental-complexity-finder` skill exists, documents the `gap` method and the false-positive
  guard, is scoped to a single unit, and produces a target + evidence when run
  against this repository.
- The workflow exists under the project workflow root, parses and loads, follows
  the verified grammar (`:session` + `:delegate`), and matches the two-step
  shape above including the early-stop-on-no-target behaviour.
- The workflow's step-1 output and step-2 input wiring conform to the verified
  worktree-resolving handoff contract: step-1 yields a structured handoff blob
  carrying `worktree_path:` and `munera_task_path:`; step-2's `:delegate`
  targets the `task-lifecycle-in-worktree` wrapper and sources `:input` via
  `:prompt-string {:type :map :fields {:input {:from {:step "<step-1-name>" :yield :text}}}}`;
  the wrapper's `resolve-worktree` `:session` step re-calls `work-on` from the
  threaded `worktree_path:` and emits the bare task path, and the wrapper's inner
  `lifecycle` delegate routes `{:input "munera/open/NNN-slug"}` into
  `task-lifecycle` — the map shape every sub-workflow reads. (This mirrors
  `implement-task-in-worktree` exactly, with `task-lifecycle` substituted for
  `implement-task`.)
- The `task-lifecycle-in-worktree` wrapper workflow exists, parses and loads, and
  is structurally a two-step `resolve-worktree`(`:session`,+`work-on`) →
  `lifecycle`(`:delegate` `:target "task-lifecycle"`) adapter — verified against
  the workflow definition/parse tests, mirroring `implement-task-in-worktree`.
- Generated tasks carry the two-phase behaviour-preserving contract: a Phase 0
  test-coverage gate (characterization tests + green net before refactoring) and
  Phase 1 refactor with the `local`-lens + `gate` + green-tests acceptance.
- Workflow/skill authoring is verified against the relevant parser/compiler/
  definition tests; docs are updated where the new capability is user-visible.

## Open questions

- Step-1 granularity: selection + worktree + task-creation in one `:session`
  step is coherent but fat. Keep as one step unless it proves unwieldy, in which
  case split selection from task-creation (accepting added inter-step data flow).
