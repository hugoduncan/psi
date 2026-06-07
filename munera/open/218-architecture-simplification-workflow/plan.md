# 218 — Plan

Derived from the stable `design.md` after architecture-fit, ambiguity, and
inconsistency design follow-ups were resolved (`design-steps.md` unchecked count
0). The design is complete enough to plan: it fixes the workflow name, Gordian
EDN selector contract, no-target routing, generated-task artifact contract,
test-net semantics, validation-capture producer boundary, and the ordered
post-implementation review gates.

## Approach

Implement a new user-visible architecture-level simplification workflow as
workflow/skill/doc artifacts, not a new runtime feature. The existing workflow
loader, `workflow/pass-status-routing`, `review-step`, `review-follow-up-steps`,
and `implement-task` machinery are sufficient; the task should add only the
workflow definition, the dedicated implementation-architecture review skill,
loader/content-lock tests, docs, and changelog unless implementation discovers a
real loader/runtime incompatibility.

Build dependency-first:

1. Add the referenced review skill before the workflow that names it.
2. Author `.psi/workflows/reduce-architectural-complexity.edn` as an explicit
   architecture workflow, mirroring the safe parts of `reduce-incidental-complexity`
   while replacing function-level selection with `bb gordian architecture-targets
   --edn` and replacing the generic implementation-review delegate with the
   design-mandated validation/review sequence.
3. Lock the workflow shape with focused workflow-loader tests before docs are
   treated as final.
4. Update user-facing docs and changelog after names, gates, and routing are
   test-locked.

### Key decisions from design

- Workflow name is exactly `reduce-architectural-complexity`.
- The workflow runs in the invoking worktree. It must not call `work-on`, create
  a worktree, or switch branches.
- Target selection uses `bb gordian architecture-targets --edn` and the top-level
  `:winner` / `:candidates` envelope. `target-issues` is informational framing
  after selection, never a selector or eligibility gate.
- `select-and-create` is the only no-target / uninterpretable-output decision
  point. It emits exactly one raw `PASS_STATUS` line and routes only through
  normalized `"DONE"` / `"REPEAT"` EDN routes.
- Generated tasks must record task-local root-relative Gordian artifacts and a
  single authoritative selected candidate map. Later gates reuse the same
  target/source area rather than recomputing membership.
- Simplification is gated by clean baseline, coverage review, characterization
  fix/disposition, and diff gate before `implement-task` can run.
- `validation-capture` runs after `implement-task` and before any
  post-implementation review gate. Failed/missing validation artifacts route
  back to `implement-task` repair.
- Post-implementation review is an explicit workflow-local `review-step` chain:
  `task-implementation-review`, `task-test-review`,
  `review-implementation-architecture`, `test-shaper`, `review-task-docs`,
  `code-shaper`. The generic `review-task-implementation` wrapper is not called.
- The implementation architecture review skill identity/path is exactly
  `.psi/skills/review-implementation-architecture/SKILL.md` with skill value
  `review-implementation-architecture`.

## Risks

- **R1 — Prompt-as-code selector precision.** Selection, task generation, and
  artifact capture happen in a fat `:session` prompt. A vague prompt could create
  tasks from unsupported output, recompute membership later, or treat
  `target-issues` failure as no-target. Mitigation: lift the design contracts
  directly into the prompt and content-lock the required substrings, statuses,
  artifacts, and unsupported-`target-issues` branch.
- **R2 — Gordian contract drift.** Existing `gordian` skill prose still contains
  older ranking wording in places, while the task design pins the live EDN
  envelope to top-level `:winner` / `:candidates`. Mitigation: tests exercise the
  real/expected `bb gordian architecture-targets --edn` envelope and the workflow
  prompt explicitly names the authoritative contract.
- **R3 — Review-loop misuse.** It would be easy to call the generic
  `review-task-implementation` workflow or invent a custom architecture review
  loop. Mitigation: tests assert the explicit six `review-step` delegates, their
  order, exact skill values, and absence of a generic `review-task-implementation`
  delegate target.
- **R4 — Validation artifacts accepted too late or too loosely.** Reviews need
  before/after Gordian artifacts, but `implement-task` does not own producing
  them. Mitigation: implement a workflow-local `validation-capture` step whose
  routing, artifact filenames, failure-map contract, and producer-before-review
  ordering are test-locked.
- **R5 — Test-net gate weakens for architecture targets.** The inherited
  incidental-complexity gate must be adapted to namespace/family/pair/community
  targets without permitting broad production edits before characterization.
  Mitigation: clean-baseline and diff-gate prompts consume the generated task's
  recorded `:target/source-areas`, `:target/allowed-adjacent-source-areas`, and
  `:target/affected-test-areas` rather than free-form source guesses.
- **R6 — Test file size / shared-test sprawl.** The existing workflow definition
  tests are already large. Mitigation: use a dedicated sibling test namespace for
  task 218 workflow-definition/content-lock tests, following the task-209 split
  precedent.

## Slice order

1. **Slice 1 — Preflight and dedicated review skill.** Verify the live Gordian
   command shapes needed by the workflow and add
   `.psi/skills/review-implementation-architecture/SKILL.md` with the exact
   skill identity. The skill should review actual implementation changes against
   the selected architecture target, Gordian evidence, project architecture
   sources, validation artifacts, and blast-radius limits.
2. **Slice 2 — Architecture workflow selection and task-generation shell.** Add
   `.psi/workflows/reduce-architectural-complexity.edn` with `select-and-create`,
   no-target routing, target-present delegation to `review-task-design`,
   `create-task-plan`, and `review-task-plan`, and the generated task/artifact
   contract. Keep it in the invoking worktree and do not introduce a worktree
   wrapper.
3. **Slice 3 — Pre-simplification test-net gates.** Add/adapt the explicit
   `clean-baseline`, `coverage-review`, `coverage-disposition`, `coverage-fix`,
   and `diff-gate` steps for architecture target/source areas. Ensure
   `implement-task` is unreachable until coverage and diff gates pass.
4. **Slice 4 — Validation capture and post-implementation review chain.** Add
   `implement-task`, `validation-capture`, the six ordered `review-step` gates,
   terminal stop summary, and final summary. Route failed validation back to
   `implement-task` and route successful validation into reviews.
5. **Slice 5 — Workflow-loader/content-lock tests.** Add focused tests for load,
   routing, prompt contracts, real `architecture-targets --edn` envelope,
   unsupported `target-issues` informational handling, invoking-worktree
   constraints, test-net ordering, validation-capture ordering, and all six
   post-implementation review-step gates with exact skill values/context.
6. **Slice 6 — User-facing docs, changelog, and coherence verification.** Update
   workflow documentation and `CHANGELOG.md`, run focused workflow-loader tests,
   targeted lint/format checks for changed files, and record verification in the
   task artifacts.

## Non-blocking notes

- `target-issues` support is candidate-type dependent by design; unsupported
  pair/community framing is an explicit informational branch, not an ambiguity.
- The generated architecture task id/slug is intentionally allocated at workflow
  runtime from the invoking worktree's `munera/open/` and `munera/closed/` state.
- A persistent skip list remains out of scope unless implementation finds it is
  necessary for deterministic no-progress avoidance.
