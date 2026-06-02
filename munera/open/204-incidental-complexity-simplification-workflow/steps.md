# 204 — Steps

Checklist derived from `plan.md`. Build order is dependency-first. Tick each item
with the commit sha / decision when done.

## Slice 1 — `incidental-complexity-finder` skill

- [ ] Re-verify live CLI shape: run `bb gordian local --sort total --json` and
      `bb gordian complexity --json`; confirm each emits a `units` array with
      `ns`/`var`/`arity`, `local` carrying `lcc-total` (+ per-dimension burdens),
      `complexity` carrying `cc`.
- [ ] Create `.psi/skills/incidental-complexity-finder/SKILL.md` with frontmatter
      (`name`, `description`, `lambda`) consistent with sibling skills
      (`refactoring`, `gordian`, `code-shaper`).
- [ ] In SKILL.md, state the scope explicitly: **a single executable unit**;
      encode the false-positive guard (high CC alone is not a target).
- [ ] Embed the **fixed verbatim join recipe**: run both lenses in machine form,
      join on `(ns, var, arity)`, compute `gap = lcc-total / max(cc, 1)`.
- [ ] Encode the **unmatched-row rule** (A1): inner join keyed on the `local`
      side — a `local` unit with no matching `cc` row is **dropped**, never
      defaulted to `cc=1`; `complexity`-only units are absent; `max(cc,1)` guards
      only the matched zero-cc case.
- [ ] Encode the **qualification filter**: a unit qualifies iff
      `lcc-total ≥ 5.0 ∧ gap ≥ 2.0`; rank qualifying units by `gap`; if none
      qualify there is no target (drives early stop).
- [ ] Encode the **judgment guard**: read the top 5 qualifying units by `gap`,
      confirm burden is incidental (braiding / state threading / abstraction
      oscillation / helper-chasing / working-set overload on low/moderate CC) and
      not an essential irreducible algorithm; choose the first that passes;
      report no target if none of the top 5 pass.
- [ ] Encode the **evidence emission**: chosen target emits `ns`, `var`, `arity`,
      file, line range, `lcc-total` with per-dimension burdens, `cc`, `gap`, the
      `local` findings, and a **coverage hint** (sibling test ns exists? any test
      references the target var?).
- [ ] State thresholds (`lcc-total ≥ 5.0`, `gap ≥ 2.0`, top-5 guard depth) are
      explicit and tunable.
- [ ] Verify the skill registers/loads (discoverable in skills registry) and,
      run interactively against this repo, produces a target + evidence (or a
      well-formed no-target report).
- [ ] Commit Slice 1 (`⚒ skill: add incidental-complexity-finder`).

## Slice 2 — `task-lifecycle-in-worktree` wrapper workflow

- [ ] Create `.psi/workflows/task-lifecycle-in-worktree.md` mirroring
      `implement-task-in-worktree.md` (`.md` with EDN body + frontmatter
      `name`/`description`).
- [ ] Add `resolve-worktree` step: `:type :session`, tools
      `["read" "bash" "work-on"]`, contribution template extracts `worktree_path:`
      and the Munera task path from `{{input}}`, calls `work-on` with the
      extracted worktree path, then yields **only** the bare task path on one line.
- [ ] Add `lifecycle` step: `:type :delegate`, `:target "task-lifecycle"`,
      `:prompt-string {:type :map :fields {:input {:from {:step "resolve-worktree" :yield :text}}}}`.
- [ ] (Decision) Keep the wrapper minimal at two steps per design's "thin
      two-step adapter"; record whether a trailing `summary` `:session` step is
      added (mirroring `implement-task-in-worktree`) or deliberately omitted.
- [ ] Run `clj-paren-repair` on the EDN body if needed; verify the workflow
      parses, loads, and is registered.
- [ ] Commit Slice 2 (`⚒ workflow: add task-lifecycle-in-worktree wrapper`).

## Slice 3 — `reduce-incidental-complexity` outer workflow

- [ ] Create `.psi/workflows/reduce-incidental-complexity.edn` with `:name`
      `"reduce-incidental-complexity"` and a `:description`.
- [ ] Author **step-1** (`:type :session`): tools
      `["read" "bash" "edit" "write" "work-on"]`, skills
      `["incidental-complexity-finder" "gordian" "code-shaper"]`.
- [ ] Step-1 prompt: `git fetch origin master`; treat `origin/master` as base.
- [ ] Step-1 prompt: apply `incidental-complexity-finder` to choose the single
      highest incidental-complexity unit.
- [ ] Step-1 prompt: **early stop** — if no qualifying unit exists, stop and
      report; do **not** create a worktree or task.
- [ ] Step-1 prompt: create an isolated worktree via `work-on` based on
      `origin/master`, described from the target (`simplify <target>`).
- [ ] Step-1 prompt: capture baselines into the task dir —
      `before-local.json` (`bb gordian local --json`) and
      `before-diagnose.edn` (`bb gordian diagnose --edn`).
- [ ] Step-1 prompt: allocate next task id, create `munera/open/NNN-slug/design.md`
      for the generated refactor task; record the concrete task path so Phase-1
      commands use the **worktree-root-relative task-dir path** for baselines.
- [ ] Step-1 prompt: embed the **two-phase behaviour-preserving contract** in the
      generated `design.md` instructions, lifted verbatim from `design.md`'s
      "Generated task design" section:
  - [ ] Phase 0: assess coverage vs `{nominal, edge, boundary}`; add
        characterization tests (state/outputs, no interactions,
        `testing-without-mocks`) if insufficient; tests green against unmodified
        code before refactor; untestable-tangle → seam or close.
  - [ ] Phase 1 acceptance A5: target `lcc-total` decreased vs stored
        `munera/open/NNN-slug/before-local.json` (the single authoritative
        baseline), keyed by `(ns, var, arity)`.
  - [ ] Phase 1 acceptance A2: net burden over the **metric-derived touched set**
        `{u | before(u) ≠ after(u)}` strictly decreases (`Σ after < Σ before`).
  - [ ] Phase 1 acceptance A3: `bb gordian gate --baseline
        munera/open/NNN-slug/before-diagnose.edn --fail-on
        new-cycles,new-high-findings --max-new-medium-findings 0` passes (exit 0).
  - [ ] Phase 1: Phase-0 + existing tests green; change minimal/local/decomplecting.
- [ ] Step-1 prompt: commit the task creation; emit a **structured handoff**
      block with at minimum `worktree_path:` (absolute) and `munera_task_path:`
      lines (mirroring `gh-issue-implement.edn`'s `design`-step handoff).
- [ ] Author **step-2** (`:type :delegate`): `:target "task-lifecycle-in-worktree"`,
      `:prompt-string {:type :map :fields {:input {:from {:step "<step-1-name>" :yield :text}}}}`.
- [ ] Confirm the outer workflow ends with a completed/reviewed task on the local
      worktree branch — **no push/PR**, no workflow-level verification step.
- [ ] Run `clj-paren-repair` on the EDN; verify it parses, loads, and that the
      `incidental-complexity-finder` skill + `task-lifecycle-in-worktree` target
      references resolve.
- [ ] Commit Slice 3 (`⚒ workflow: add reduce-incidental-complexity`).

## Slice 4 — verification + definition tests

- [ ] Extend `components/workflow-loader/test/.../workflow_definitions_test.clj`:
      assert `reduce-incidental-complexity` and `task-lifecycle-in-worktree`
      parse/load.
- [ ] Assert outer two-step shape: step-1 `:session` (with `work-on` tool +
      `incidental-complexity-finder` skill), step-2 `:delegate`
      `:target "task-lifecycle-in-worktree"` with the
      `:prompt-string {:type :map :fields {:input {:from {:step … :yield :text}}}}`
      wiring.
- [ ] Assert wrapper two-step shape: `resolve-worktree` `:session` with `work-on`
      tool; `lifecycle` `:delegate` `:target "task-lifecycle"` with `:input`
      sourced from `resolve-worktree` `:yield :text`.
- [ ] Assert (where the test ns convention supports it) the step-1 prompt emits
      the `worktree_path:` / `munera_task_path:` handoff fields and the early-stop
      intent (R1 lock).
- [ ] Assert the `incidental-complexity-finder` skill registers / is discoverable.
- [ ] Run focused workflow tests (workflow-loader + relevant agent-session
      workflow definition tests) green.
- [ ] Run `clj-kondo --lint` over any changed source/test paths; 0 new findings.
- [ ] Commit Slice 4 (`⚒ test: lock reduce-incidental-complexity + wrapper definitions`).

## Slice 5 — docs + coherence

- [ ] Update `doc/workflows.md` (and/or the workflow listing it curates) to
      document `reduce-incidental-complexity` and `incidental-complexity-finder`.
- [ ] Add a CHANGELOG `[Unreleased] → Added` entry for the new user-visible
      workflow + skill.
- [ ] Verify coherence across `design.md` ↔ SKILL.md ↔ both workflow `.edn/.md`
      ↔ docs (names, thresholds, gate flags, handoff fields all consistent).
- [ ] Run final focused workflow tests + `clj-kondo`; record results in
      `implementation.md`.
- [ ] Commit Slice 5 (`⚒ doc: document reduce-incidental-complexity workflow`).

## Plan/steps ambiguity follow-ups (review pass 1)

- [ ] P1 — State the criterion for the Slice-2 wrapper `summary` step: decide
      add-vs-omit by whether the outer workflow needs a user-facing *terminal*
      summary (outer step-2 is terminal; `implement-task-in-worktree` keeps the
      summary step). Resolve the choice in plan.md/steps.md, don't leave it open.
- [ ] P2 — Reconcile the two `local` invocations in steps.md: explicitly note
      that `before-local.json` is captured with `bb gordian local --json` (no
      `--sort`) and that `--sort total` (selector-only, line 8) is irrelevant to
      the `(ns,var,arity)`-keyed before/after comparison, so the baseline is
      valid regardless of sort. Carry the design's inconsistency-review
      conclusion into the steps.
- [ ] P3 — Define the task-id allocation scan root: state that NNN is allocated
      by scanning `open/ ∪ closed/` in the `origin/master`-based **worktree**
      (where the task is created), per Munera `alloc → max(NNN)+1`, to avoid
      collision with the outer checkout's open tasks.
- [ ] P4 — Make the task-creation commit location explicit in Slice 3: the task
      dir is created **and committed on the `work-on` worktree branch** (off
      `origin/master`), so the handoff's `munera_task_path:` resolves for
      step-2's `resolve-worktree`/`work-on` before the delegated lifecycle runs.

## Contingency (non-planned; only if Slice 3 step-1 proves unwieldy)

- [ ] Split step-1 selection from task-creation into two `:session` steps,
      threading selection output forward (accepting added inter-step data flow).
      Per design "Open questions": keep as one step unless it proves unwieldy.
