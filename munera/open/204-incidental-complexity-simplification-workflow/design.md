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

1. **A selector skill** — encodes the methodology for choosing the single
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
  quick PR). Sharing the selector skill between them is a possible later
  follow-up, explicitly not in this task.
- Promoting the `gap` computation into a first-class `gordian` subcommand.
  Worthwhile later; v1 computes it from the two existing lenses.

## Deliverable 1 — Selector skill

A `SKILL.md` (under the project skills root) that, when applied, deterministically
narrows and then judges:

1. Run both lenses in machine form:
   - `bb gordian local --sort total --json` (comprehension burden per unit)
   - `bb gordian complexity --json` (cyclomatic complexity per unit)
2. Join on `(ns, var, arity)` and compute `gap = lcc-total / max(cc, 1)`.
3. Rank candidates by `gap` within the high-burden cohort.
4. Apply the **judgment guard**: read the top candidates and confirm the burden
   is incidental (braiding, state threading, abstraction oscillation,
   helper-chasing, working-set overload on low/moderate CC) and **not** an
   essential, irreducible algorithm. Discard essential-complexity false
   positives.
5. Emit one chosen target with evidence: `ns`, `var`, `arity`, file, line range,
   `lcc-total` with per-dimension burdens, `cc`, `gap`, and the `local` findings.

The skill must explicitly state its scope is a **single executable unit** and
must encode the false-positive guard (high CC alone is not a target).

## Deliverable 2 — Workflow

A multi-step `.edn` orchestration workflow that simplifies one aspect end to
end. Required behaviour:

**Step 1 — select + establish target (`:session`)**
- Tools include `read`, `bash`, `edit`, `write`, `work-on`; skills include the
  selector skill, `gordian`, and `code-shaper`.
- Refresh base: `git fetch origin master`; treat `origin/master` as the
  authoritative base.
- Apply the selector skill to choose the single highest incidental-complexity
  unit.
- **Early stop:** if no qualifying unit exists, stop and report — do not create
  a worktree or task.
- Create an isolated worktree via `work-on` based on `origin/master`, described
  from the target (e.g. `simplify <target>`).
- Capture a `before` gordian snapshot of the target for later comparison and
  store it within the task directory (Munera preserves unknown files).
- Allocate the next task id, create `munera/open/NNN-slug/design.md` for the
  refactor task (see "Generated task design" below).
- Commit the task creation.
- Emit **only** the Munera task path (e.g. `munera/open/NNN-slug`) on a single
  line as the step output.

**Step 2 — run the lifecycle (`:delegate`)**
- Delegate to `task-lifecycle` with `{:input <task-path-from-step-1>}`.
- The delegate **inherits the worktree** established in step 1 (verified
  behaviour; see Verified Facts).

The workflow stays at two steps. No workflow-level verification step is added:
the generated task's acceptance criteria carry the objective checks, and the
lifecycle's own implement/review steps enforce them.

## Generated task design (the behaviour-preservation contract)

Each task this workflow creates is a **behaviour-preserving refactor** (the
`refactor_minimal_semantics_spec_tests` formalism). Its `design.md` must state:

- The target unit and the captured incidental-complexity evidence.
- Constraint: **behaviour is identical** — meta/spec are unchanged; existing
  tests must continue to pass unchanged.
- **Objective acceptance criteria:**
  - `gordian compare before.edn after.edn` shows reduced burden for the target,
  - `gordian gate` passes (no new cycles, no new high/medium findings),
  - all existing tests for the affected area are green,
  - the change is minimal, local, and root-cause (decomplecting), not
    superficial extraction.

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
   incidental guard.
2. The generated `design.md` centres on a behaviour-preservation contract with
   objective `gordian compare`/`gate` + green-tests acceptance, baked in so the
   lifecycle enforces it.
3. v1 scope is function-level incidental complexity only; architectural is a
   later sibling.
4. Autonomy is acceptable for this task class (objective, narrow design);
   `review-task-design` substitutes for live collaboration.
5. Build a new workflow; do not extend `complexity-reduction-pr`. Sharing the
   selector skill later is a possible follow-up, not part of this task.

## Verified facts (grounding)

- **Lifecycle input contract:** every `task-lifecycle` sub-workflow takes
  `{:input "munera/open/NNN-slug"}` — a bare Munera task path string.
- **Worktree ownership:** neither `task-lifecycle` nor `implement-task` creates
  a worktree; the caller establishes it, and a `:delegate` step inherits the
  worktree set by a prior `:session` step's `work-on` call (precedent:
  `implement-task-in-worktree.md`).

## Acceptance criteria (this task)

- The selector skill exists, documents the `gap` method and the false-positive
  guard, is scoped to a single unit, and produces a target + evidence when run
  against this repository.
- The workflow exists under the project workflow root, parses and loads, follows
  the verified grammar (`:session` + `:delegate`), and matches the two-step
  shape above including the early-stop-on-no-target behaviour.
- The workflow's step-1 output and step-2 input wiring conform to the verified
  task-path handoff contract.
- Generated tasks carry the behaviour-preservation contract and objective
  acceptance criteria described above.
- Workflow/skill authoring is verified against the relevant parser/compiler/
  definition tests; docs are updated where the new capability is user-visible.

## Open questions

- Step-1 granularity: selection + worktree + task-creation in one `:session`
  step is coherent but fat. Keep as one step unless it proves unwieldy, in which
  case split selection from task-creation (accepting added inter-step data flow).
