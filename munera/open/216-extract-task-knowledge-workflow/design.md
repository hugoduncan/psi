# 216 — Extract task knowledge workflow

## Intent

Add a workflow that mines a *completed* Munera task's artifacts and harvests
knowledge that is useful to the **project as a whole**, beyond the task's own
context, and persists it **autonomously** (no human approval gate) into the
project's durable memory.

The goal is feed-forward: every completed task should leave the project smarter
without requiring a human to notice, judge, and approve the insight. Hard-won
lessons currently sit inert in `implementation.md` / git history and are lost to
future sessions unless someone manually metabolizes them.

## Problem

1. **Knowledge evaporates at task closure.** A task's `design.md`, `plan.md`,
   `steps.md`, `implementation.md`, and commit trail accumulate genuinely
   reusable insights (failure modes, architectural constraints, patterns, gotchas).
   When the task closes, these are not systematically lifted into project-general
   knowledge. `mementum/state.md` shows huge volumes of per-task notes that are
   task-local and never synthesized.
2. **The existing surfaces don't do this.** The `implement-task` final-summary
   step produces a *user-facing* outcome/verification/handoff summary — a
   different purpose (reporting the run), not extracting durable project
   knowledge. The mementum protocol *describes* `synthesize`/`create-knowledge`
   but gates them on **human approval** (`termination: approval ≡ human`), so
   nothing extracts knowledge unprompted.
3. **The approval gate is the bottleneck.** Autonomous capture is explicitly
   wanted here, which means this workflow deliberately operates *outside* the
   mementum human-approval termination condition.

## Core tension (must be resolved in design)

The mementum protocol states: `termination: synthesis ≡ AI | approval ≡ human |
human ≡ termination_condition` and `memories: AI_proposes → human_approves →
AI_commits`. This task **overrides** that gate for the artifact-extraction path:
extraction is autonomous and self-committing.

This raises a quality/noise risk: autonomous writes can flood mementum with
low-value or duplicative entries, degrading recall. The design must therefore:

- Reuse the mementum **value gates** (gate-1: helps future AI session, not
  personal/off-topic; gate-2: effort > 1 attempt ∨ likely-recur) as the
  *extraction filter*, even though the *approval* gate is removed.
- Bias toward **project-general** knowledge and explicitly reject task-local
  trivia (the "outside the task's own context" requirement).
- Have a defensible answer for deduplication (update existing knowledge vs.
  create new) so it does not re-emit what is already captured.

## Scope

In scope:

- A new workflow definition (in `.psi/workflows/`, `.edn` + prompt `.md`, in the
  style of the existing task workflows) that:
  - takes a completed task identifier / path as input,
  - independently inspects that task's artifacts and relevant git history,
  - identifies candidate knowledge that is generalizable to the project,
  - applies the mementum value/scope filter,
  - writes durable mementum entries and commits them autonomously,
  - produces a concise summary of what was (and was not) extracted.
- Tests proving the workflow definition is well-formed and routes/terminates as
  intended (consistent with how other workflow definitions are tested).
- Documentation of the new workflow.

Out of scope (candidate follow-on tasks):

- Changing the global mementum approval protocol for the interactive
  (non-artifact) path.
- Modifying `implement-task` / `task-lifecycle` to auto-chain this workflow
  (the *trigger/orchestration* question — see open questions).
- Any UI surface.

## Acceptance criteria (draft — to refine)

1. The workflow is discoverable and runnable via `delegate` against a completed
   task and runs to completion without requesting human approval.
2. Given a task whose artifacts contain a project-general insight, the workflow
   persists a corresponding mementum entry and commits it, using mementum commit
   conventions.
3. Given a task whose artifacts contain only task-local detail, the workflow
   extracts nothing (no spurious entries) and says so.
4. The workflow does not duplicate knowledge already present in `mementum/`
   (it updates or skips rather than re-creating).
5. The workflow definition has test coverage and is documented.

## Open questions (to resolve collaboratively before plan.md)

1. **Output target.** Knowledge pages (`mementum/knowledge/{topic}.md`),
   memories (`mementum/memories/{slug}.md`), or both? Memories are the lighter
   per-insight unit; knowledge pages are synthesized topics. Which does
   "extract knowledge" mean here?
2. **Trigger / orchestration.** Standalone (invoked manually via `delegate`
   after closure), or chained as a final stage of `implement-task` /
   `task-lifecycle`? The intent says "run after the task completes" — does that
   mean automatic chaining or a separate explicit run?
3. **Single pass vs. loop.** One extraction pass, or an iterative loop (like the
   review/implement loops) until no further extractable knowledge remains?
4. **Dedup mechanism.** How does it detect existing knowledge — `git grep` /
   recall over `mementum/`, then decide create-vs-update-vs-skip? How aggressive?
5. **Extraction mechanism.** Since mementum is a *protocol* (git-native markdown,
   no `create-knowledge` function in code — it's executed via bash/git/write),
   the workflow's session step uses `read`/`bash`/`write` to author and commit
   files. Confirm this is the intended mechanism (no new code surface in the
   mementum extension).
6. **Volume guard.** Should there be a cap on entries per run to bound noise?
7. **Input shape.** What does the workflow receive — task path, task NNN-slug,
   or the prior step's handoff data (so it could chain off implement-task)?
