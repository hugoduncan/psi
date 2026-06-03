# 209 — Plan

Derived from the stable `design.md` (design review complete: architecture-fit +
A1–A5 + I1 all resolved REVIEW_COMPLETE; design-steps unchecked count 0).

## Approach

Three authoring deliverables, all S1 capability-catalog artifacts (a skill and
two workflows). No production Clojure changes are required — the workflows are
data (`.edn`/`.md` definitions) and the skill is markdown. Verification is by the
existing workflow-loader/parse/definition tests plus live loadability: **no new
production Clojure** — the Slice-4 assertions for the two new workflows + skill
registration are test-only. They initially **extended the existing**
`workflow_definitions_test.clj` namespace (see R4 + Slice 4); R6 (independent
impl-review follow-up) later **extracted the two task-204 workflow-definition
deftests into a dedicated sibling test ns**
(`task_204_workflow_definitions_test.clj`) once the shared file reached the hard
800-line `bb commit-check:file-lengths` boundary — superseding the original
single-ns intent. This follows the existing `incidental_complexity_finder_skill_test.clj`
split precedent and restored full headroom (shared ns 800 → 593 lines).

Build order is **dependency-first**: the inner `task-lifecycle-in-worktree`
wrapper before the outer `reduce-incidental-complexity` workflow that delegates
to it; the `incidental-complexity-finder` skill before the outer workflow that
references it in its step-1 `:skills`. This way each artifact's references
resolve as it is added.

### Key decisions (inherited from design — locked)

- **Selection is a skill** (`incidental-complexity-finder`), judgment-bearing and
  reusable, encoding the `gap = lcc-total / max(cc,1)` recipe as a fixed verbatim
  snippet, the qualification filter (`lcc-total ≥ 5.0 ∧ gap ≥ 2.0`), the top-5
  essential-vs-incidental judgment guard, and the inner-join-on-`local`-side
  unmatched-row rule (drop, never default `cc=1`).
- **Worktree continuity uses the verified wrapper pattern** (Locked decision 11):
  the outer workflow's step-1 emits a structured handoff carrying `worktree_path:`
  + `munera_task_path:`; step-2 delegates to a thin `task-lifecycle-in-worktree`
  wrapper whose `resolve-worktree` `:session` step re-calls `work-on` before
  sub-delegating to `task-lifecycle`. Structurally identical to the loadable
  `review-implementation-in-worktree.edn` (the `.edn` realisation of the intended
  `implement-task-in-worktree` shape — see D1), with `task-lifecycle` substituted
  for the inner delegate. No direct `task-lifecycle` delegate (unverified
  inheritance).
- **Grammar-conformant handoff wiring**: step-2 sources `:input` via
  `:prompt-string {:type :map :fields {:input {:from {:step "<step-1>" :yield :text}}}}`;
  the wrapper's inner `lifecycle` delegate uses the same form keyed on its
  `resolve-worktree` step. Verified precedent: `gh-issue-implement.edn` +
  the loadable `review-implementation-in-worktree.edn` (see D1).
- **Generated tasks are two-phase behaviour-preserving contracts**: Phase 0
  test-coverage gate (characterization tests + green net before refactor),
  Phase 1 refactor with `local`-lens before/after (`before-local.json`) + net
  touched-units burden + `gordian gate --baseline before-diagnose.edn --fail-on …`
  + green tests (both baselines — `before-local.json` *and* `before-diagnose.edn`
  — captured in the task dir during step-1). This contract
  lives in the workflow's step-1 prompt (the design-template the workflow
  generates), not in this task's own code.
- **Endpoint**: completed, reviewed task on a local worktree branch — no push/PR.
- **Autonomy** is acceptable for this generated task class; `review-task-design`
  in `task-lifecycle` substitutes for live collaboration.

### Artifact locations (verified against repo layout)

- Skill: `.psi/skills/incidental-complexity-finder/SKILL.md` (sibling to
  `refactoring/`, `gordian/`, `code-shaper/`).
- Wrapper workflow: `.psi/workflows/task-lifecycle-in-worktree.edn` (multi-step
  `.edn` map; sibling to the loadable `review-implementation-in-worktree.edn`,
  mirroring it). **D1 deviation**: design/plan originally specified a
  `.psi/workflows/task-lifecycle-in-worktree.md` `.md`-with-EDN-body form
  mirroring `implement-task-in-worktree.md`, but the live `workflow-loader`
  parser rejects any `.md` body that begins with an EDN map
  (`parser.clj:162`, `body-starts-with-edn-map?`) — `implement-task-in-worktree.md`
  itself does not load. The loadable multi-step-wrapper precedent is
  `review-implementation-in-worktree.edn` (3-step
  resolve-worktree → delegate → summary), so the wrapper was authored as `.edn`
  mirroring it. See implementation.md / design.md F6/D1.
- Outer workflow: `.psi/workflows/reduce-incidental-complexity.edn` (sibling to
  `complexity-reduction-pr.edn`, `task-lifecycle.edn`).

### Verified grammar anchors (read during planning)

- `task-lifecycle.edn`: 5 sub-workflows, each `:delegate`, each reading
  `:input {:from :workflow-input :path [:input]}` (map `{:input "munera/open/NNN-slug"}`).
- `review-implementation-in-worktree.edn`: the **loadable** three-step wrapper
  precedent — `resolve-worktree` (`:session`, tools `["read" "bash" "work-on"]`,
  extracts `worktree_path:`, calls `work-on`, yields bare task path) →
  `review`/`delegate` (`:delegate`,
  `:prompt-string {:type :map :fields {:input {:from {:step "resolve-worktree" :yield :text}}}}`)
  → `summary` (`:session`, user-facing terminal summary). This is the `.edn`
  realisation of the intended `implement-task-in-worktree.md` shape, which does
  **not** load (its `.md` body begins with an EDN map — see D1); hence the 204
  wrapper mirrors `review-implementation-in-worktree.edn`.
  (Per plan/steps ambiguity
  resolution **P1**, the 204 wrapper **keeps the `summary`
  step** — three steps — because outer step-2 is the terminal step of
  `reduce-incidental-complexity`, so the workflow needs a user-facing terminal
  summary. The design's "thin two-step adapter" framing is superseded by P1.)
- `complexity-reduction-pr.edn`: single fat `:session` step doing
  select+worktree+refactor+push — precedent for the step-1 select+worktree+task
  shape (minus task-creation/handoff), and for the `git fetch origin master` /
  `work-on`-based / early-stop idioms.
- `gh-issue-implement.edn`: outer `:delegate` to a worktree-resolving wrapper via
  `:prompt-string {:type :map :fields {:input {:from {:step <name> :yield :text}}}}`
  — the exact handoff form step-2 uses.

## Risks

- **R1 — Grammar drift in the outer step-1 prompt.** Step 1 is a fat `:session`
  step (select + worktree + baseline capture + task-dir creation + structured
  handoff). The prompt must precisely emit the `worktree_path:` /
  `munera_task_path:` handoff lines or step-2's wrapper cannot resolve. Mitigation:
  copy the verified handoff-emission idiom from `gh-issue-implement.edn`'s
  `design`-step prompt and the `complexity-reduction-pr.edn` select/worktree
  idiom; assert the handoff fields in the definition test.
- **R2 — Wrapper/outer reference resolution.** The outer workflow references
  `task-lifecycle-in-worktree` (must exist + load) and the
  `incidental-complexity-finder` skill (must exist + be registered) before the
  outer loads cleanly. Mitigation: dependency-first build order; verify each
  loads before adding its consumer.
- **R3 — Generated `design.md` template fidelity.** The two-phase contract,
  baseline path resolution (worktree-root-relative task-dir paths), gate flags
  (`--fail-on new-cycles,new-high-findings --max-new-medium-findings 0`), and the
  `before-local.json` (A5 lcc decrease) + `before-diagnose.edn` (A3 `gordian gate
  --baseline …` source) baselines + touched-units acceptance must be reproduced
  verbatim in the workflow's generated-design instructions. Mitigation: lift the contract text
  directly from `design.md`'s "Generated task design" section into the step-1
  prompt; do not paraphrase the objective criteria.
- **R4 — Definition-test coverage location.** Workflow-loader definition tests
  live in `components/workflow-loader/test/.../workflow_definitions_test.clj`;
  per-artifact-target assertions exist for other workflows. New assertions for
  the two workflows + skill registration slot into the existing test ns
  conventions. (Superseded by R6: once the shared ns hit the 800-line CI
  boundary, the two task-204 deftests were extracted into a dedicated sibling
  ns — the `incidental_complexity_finder_skill_test.clj` precedent shows this is
  the established same-component split convention, so it adds no harness drift.)
- **R5 — `bb gordian` flag/lens stability.** The skill embeds verbatim
  `bb gordian local/complexity` JSON-join recipe and the generated task embeds
  `bb gordian gate --fail-on …`. These were verified live during design review.
  Mitigation: re-verify the exact flags/JSON shape against the live CLI while
  authoring the skill (design grounding already confirmed `local`/`complexity`
  emit `units` with `ns`/`var`/`arity`/`lcc-total`/`cc`, and `gate` accepts the
  flags).

## Slice order

Vertical, dependency-first. Each slice ends loadable/verifiable.

1. **Slice 1 — `incidental-complexity-finder` skill.** Author the SKILL.md
   (frontmatter + `gap` recipe verbatim + qualification filter + judgment guard
   + single-unit scope + evidence/coverage-hint emission). Verify the skill is
   discoverable/registers and produces a target when run against this repo.
2. **Slice 2 — `task-lifecycle-in-worktree` wrapper workflow.** Author the
   `.edn` wrapper (resolve-worktree `:session`+`work-on` → lifecycle
   `:delegate :target "task-lifecycle"` → `summary` `:session`, per P1), mirroring
   the loadable `review-implementation-in-worktree.edn` (the `.edn` realisation of
   the intended `implement-task-in-worktree` shape — see D1). Verify it parses,
   loads, and is registered.
3. **Slice 3 — `reduce-incidental-complexity` outer workflow.** Author the
   two-step `.edn` (step-1 `:session` select+worktree+baselines+task+handoff with
   early-stop; step-2 `:delegate :target "task-lifecycle-in-worktree"` with the
   grammar-conformant `:input` wiring and the embedded two-phase generated-design
   contract). Verify it parses, loads, references resolve.
4. **Slice 4 — verification + definition tests.** Add/extend workflow definition
   tests asserting: both workflows parse/load; the outer two-step shape +
   early-stop intent + handoff field emission + `:delegate` target +
   `:prompt-string` wiring; the wrapper three-step shape (resolve-worktree +
   lifecycle + summary, per P1) + `work-on` tool + `task-lifecycle` target;
   skill registration. Run focused workflow tests +
   `clj-kondo`.
5. **Slice 5 — docs + coherence.** Update user-facing docs where the capability
   is user-visible (`doc/workflows.md` and/or CHANGELOG `[Unreleased] → Added`):
   new `reduce-incidental-complexity` workflow + `incidental-complexity-finder`
   skill. Verify coherence across design/skill/workflow/docs; final focused
   verification.

## Out of scope (per design non-goals)

- Architectural-level simplification selector (later sibling).
- Modifying/replacing `complexity-reduction-pr`.
- Promoting `gap` into a first-class `gordian` subcommand.
- Sharing `incidental-complexity-finder` with `complexity-reduction-pr`.
- Persistent skip-list (v1 relies on the judgment guard).
- Push/PR from the outer workflow.

## Open question (non-blocking, from design)

- **Step-1 granularity** (design "Open questions"): keeping select + worktree +
  task-creation in one `:session` step is coherent but fat. Plan keeps it as one
  step (Slice 3) per design guidance ("keep as one step unless it proves
  unwieldy"). If authoring proves it unwieldy, split selection from task-creation
  (accepting added inter-step data flow) — recorded as a contingency, not a
  planned slice.
