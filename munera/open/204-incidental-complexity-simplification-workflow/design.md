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
   constrained Munera task, and delegates to `task-lifecycle`.

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
- Allocate the next task id, create `munera/open/NNN-slug/design.md` for the
  refactor task (see "Generated task design" below).
- Commit the task creation.
- Emit **only** the Munera task path (e.g. `munera/open/NNN-slug`) on a single
  line as the step output.

**Step 2 — run the lifecycle (`:delegate`)**
- Delegate to `task-lifecycle` (`:target "task-lifecycle"`).
- **Handoff wiring (grammar-conformant):** step-2 sources its `:input` from
  step-1's yielded text via the verified delegate-yield grammar:
  ```
  :prompt-string {:type :map
                  :fields {:input {:from {:step "<step-1-name>" :yield :text}}}}
  ```
  Because step-1 emits **only** the Munera task path on a single line as its
  step output (see Step 1), step-1's `:yield :text` *is* the bare task-path
  string. This routes `{:input "munera/open/NNN-slug"}` into the delegate — the
  exact map shape every `task-lifecycle` sub-workflow reads via
  `{:from :workflow-input :path [:input]}`. This mirrors the verified
  `gh-issue-implement.edn` precedent, whose `implement`/`review` `:delegate`
  steps wire `:input` from a prior step's `:yield :text` with this identical
  `:prompt-string {:type :map :fields {:input {:from {:step … :yield :text}}}}`
  form. Naming this mechanism keeps the handoff on the one verified data-flow
  path (the `one_way` principle) rather than an implicit, non-grammatical
  contract.
- The delegate **inherits the worktree** established in step 1 (verified
  behaviour; see Verified Facts).

The workflow stays at two steps and ends with a completed, reviewed task on the
local worktree branch — it does **not** push or open a PR (the user decides on
PR). No workflow-level verification step is added: the generated task's
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
  - re-running `bb gordian local --json`, the target unit's `lcc-total`
    **decreased**, and **net `lcc-total` across all touched units decreased**
    (guards against relocating burden into a new helper),
  - `bb gordian gate --baseline before-diagnose.edn` passes (no new cycles, no
    new high/medium findings),
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

## Verified facts (grounding)

- **Lifecycle input contract:** every `task-lifecycle` sub-workflow takes
  `{:input "munera/open/NNN-slug"}` — a bare Munera task path string. Confirmed
  in `task-lifecycle.edn`: the first sub-workflow reads
  `:input {:from :workflow-input :path [:input]}`.
- **Step→step delegate-yield handoff:** a `:delegate` step sources a
  `:prompt-string` `:map` field from a prior step's text output via
  `:fields {:input {:from {:step "<name>" :yield :text}}}`. Verified precedent:
  `gh-issue-implement.edn`'s `implement` and `review` `:delegate` steps wire
  `:input` from a prior step's `:yield :text` with exactly this form. Since
  step-1 here emits only the task-path line, its `:yield :text` is the bare path
  string, producing the `{:input "munera/open/NNN-slug"}` map `task-lifecycle`
  expects.
- **Worktree ownership:** neither `task-lifecycle` nor `implement-task` creates
  a worktree; the caller establishes it, and a `:delegate` step inherits the
  worktree set by a prior `:session` step's `work-on` call (precedent:
  `implement-task-in-worktree.md`).

## Acceptance criteria (this task)

- The `incidental-complexity-finder` skill exists, documents the `gap` method and the false-positive
  guard, is scoped to a single unit, and produces a target + evidence when run
  against this repository.
- The workflow exists under the project workflow root, parses and loads, follows
  the verified grammar (`:session` + `:delegate`), and matches the two-step
  shape above including the early-stop-on-no-target behaviour.
- The workflow's step-1 output and step-2 input wiring conform to the verified
  task-path handoff contract: step-1 yields only the bare task-path line, and
  step-2's `:delegate` sources `:input` via
  `:prompt-string {:type :map :fields {:input {:from {:step "<step-1-name>" :yield :text}}}}`,
  producing the `{:input "munera/open/NNN-slug"}` map every `task-lifecycle`
  sub-workflow reads.
- Generated tasks carry the two-phase behaviour-preserving contract: a Phase 0
  test-coverage gate (characterization tests + green net before refactoring) and
  Phase 1 refactor with the `local`-lens + `gate` + green-tests acceptance.
- Workflow/skill authoring is verified against the relevant parser/compiler/
  definition tests; docs are updated where the new capability is user-visible.

## Open questions

- Step-1 granularity: selection + worktree + task-creation in one `:session`
  step is coherent but fat. Keep as one step unless it proves unwieldy, in which
  case split selection from task-creation (accepting added inter-step data flow).
