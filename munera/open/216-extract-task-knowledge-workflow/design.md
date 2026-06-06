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
   knowledge. The mementum protocol's default `synthesize`/`create-knowledge`
   path is approval-gated (`termination: approval ≡ human`), so nothing extracts
   knowledge unprompted unless this completed-task extraction path is explicitly
   authorized.
3. **The approval gate is the bottleneck.** Autonomous capture is explicitly
   wanted here, so this workflow uses the protocol's narrow completed-task
   artifact-extraction exception rather than the normal human-approval
   termination condition.

## Core tension (resolved)

The mementum protocol normally states: `termination: synthesis ≡ AI | approval ≡
human | human ≡ termination_condition` and `memories: AI_proposes →
human_approves → AI_commits`. This task uses a **narrow protocol-authorized
exception** for completed-task artifact extraction: `extract-task-knowledge` may
autonomously write and commit memories/knowledge when the source is a completed
Munera task's artifacts plus its git history, and the extraction passes the
mementum value gates, project-generality/significance filters, dedupe recall, and
`uncertain → skip` conservatism.

This raises a quality/noise risk: autonomous writes can flood mementum with
low-value or duplicative entries, degrading recall. The design therefore:

- Reuse the mementum **value gates** (gate-1: helps future AI session, not
  personal/off-topic; gate-2: effort > 1 attempt ∨ likely-recur) as the
  *extraction filter*, even though the *approval* gate is removed.
- Bias toward **project-general** knowledge and explicitly reject task-local
  trivia (the "outside the task's own context" requirement).
- Have a defensible answer for deduplication (update existing knowledge vs.
  create new) so it does not re-emit what is already captured.

## Resolved decisions

1. **Output target = both.** The workflow may persist mementum **knowledge**
   pages (`mementum/knowledge/{topic}.md`) and **memories**
   (`mementum/memories/{slug}.md`), choosing the appropriate unit per insight
   (memory = single insight; knowledge page = synthesized topic).
2. **Trigger = standalone + integrated.** Primary surface is a standalone
   `/delegate` run. Additionally it is appended as a final stage of the
   `task-lifecycle` workflow (a new trailing `:delegate` step after
   `review-task-implementation`), so the full lifecycle ends by extracting
   knowledge.
3. **Single pass.** One extraction pass per run (no iterative loop) for now.
4. **Dedup via prompt.** The extraction prompt instructs the actor to recall
   existing `mementum/` knowledge and memories (e.g. via `git grep` / reading
   the relevant files) and to *not* duplicate what is already captured —
   updating or skipping rather than re-creating. No deterministic dedup
   mechanism is built; this is a prompted responsibility.
5. **No mementum code.** Mementum is a git-native markdown protocol with no
   `create-knowledge` code surface. The workflow's session step authors and
   commits files purely via `read` / `bash` / `write`. This task introduces **no
   new code in the mementum extension**.
6. **Extraction guard = significance, not count, conservative by default.**
   There is no numeric cap on entries per run. The filter is qualitative: an
   insight is extracted only if it is **useful to the project outside the task's
   own context** and **significant for the future development of the project**.
   Because there is no human to prune false positives, the prompt is
   **conservative**: when significance is uncertain, do *not* write. This
   deliberately inverts the interactive mementum default
   (`false_positive < missed_insight`). Extracting **nothing** is a valid,
   successful outcome.
7. **Input = task slug.** Invoked as `/delegate extract-task-knowledge {NNN-slug}`
   (consistent with the other task-* workflows that take the task identifier as
   `:input`). The standalone surface receives the slug; the `task-lifecycle`
   integration threads the same `:input`.
8. **Workflow file shape = one markdown prompt workflow.** The standalone
   extraction workflow is authored as exactly
   `.psi/workflows/extract-task-knowledge.md`, a single-step prompt workflow with
   `read`, `bash`, and `write` tools. There is **no**
   `.psi/workflows/extract-task-knowledge.edn`, avoiding the mixed-kind
   same-name collision that workflow loading treats as an error. The
   `task-lifecycle` change is only an additional trailing `:delegate` step in
   `.psi/workflows/task-lifecycle.edn` with `:target "extract-task-knowledge"`.
9. **Completed-task resolution is explicit.** The extraction actor resolves the
   exact slug against `munera/closed/{NNN-slug}` and `munera/open/{NNN-slug}` and
   stops with no extraction if there are zero matches or more than one match. A
   standalone invocation may extract only from `munera/closed/{NNN-slug}`; an
   open-only standalone match is reported as not completed and produces no
   mementum writes. The `task-lifecycle` trailing invocation may extract from the
   still-open task directory only because it runs immediately after a successful
   `review-task-implementation` step; that successful predecessor is the
   lifecycle-local completion proof. If that predecessor context is absent or
   does not show a successful implementation review, an open task is treated as
   incomplete and extraction is skipped.
10. **Git-history boundary is task-specific.** The workflow may use only three
    history lenses as evidence: commits touching the resolved task directory
    (`git log --follow -- <task-dir>`), commits whose message mentions the task
    id or slug, and commit SHAs explicitly recorded in the task artifacts
    (especially `implementation.md`). It may inspect the diffs/stat for those
    commits to understand the lesson, but it must not roam unrelated repository
    history looking for material to mine.
11. **Lifecycle terminal result preserves the prior outcome.** Because workflow
    semantics make the last step's yield the `task-lifecycle` result, the
    trailing extraction delegate must receive the prior
    `review-task-implementation` yielded text as context and include that prior
    lifecycle/review outcome in its final summary alongside the extraction
    result. The `:prompt-string` still passes the same original task `:input`;
    the review output is context, not a replacement identifier.

## Scope

In scope:

- A new single-step prompt workflow definition,
  `.psi/workflows/extract-task-knowledge.md` (and no same-name `.edn` file),
  that:
  - takes a task slug as `:input`,
  - independently inspects that task's artifacts (`design.md`, `plan.md`,
    `steps.md`, `implementation.md`) and relevant git history,
  - recalls existing `mementum/` knowledge and memories to avoid duplication,
  - identifies candidate knowledge that is significant and generalizable to the
    project beyond the task,
  - authors and commits mementum knowledge pages and/or memories autonomously
    (mementum commit conventions; no human approval),
  - produces a concise summary of what was (and was not) extracted.
- Appending `extract-task-knowledge` as the final `:delegate` step of
  `task-lifecycle`, passing the same task `:input` and adding the
  `review-task-implementation` yielded text as context so the final lifecycle
  output preserves the review/lifecycle outcome as well as the extraction
  summary.
- Tests proving the workflow definition is well-formed and routes/terminates as
  intended (consistent with how other workflow definitions are tested), and that
  `task-lifecycle` includes the new trailing step.
- Documentation of the new workflow (`doc/workflows.md`).

Out of scope (candidate follow-on tasks):

- Changing the global mementum approval protocol for the interactive
  (non-artifact) path.
- An iterative multi-pass extraction loop.
- A deterministic (non-prompted) dedup mechanism.
- Any UI surface.
- Any new code in the mementum extension.

## Acceptance criteria

1. The workflow is runnable via `/delegate extract-task-knowledge {NNN-slug}`
   and runs to completion without requesting human approval. It is loaded from
   `.psi/workflows/extract-task-knowledge.md` with no same-name `.edn` collision.
2. Given a task whose artifacts contain a project-significant, project-general
   insight, the workflow persists a corresponding mementum knowledge page and/or
   memory and commits it using mementum commit conventions.
3. Given a task whose artifacts contain only task-local detail, the workflow
   extracts nothing (no spurious entries) and reports that nothing was extracted.
   Zero entries is a successful, non-error outcome.
4. The workflow does not duplicate knowledge/memories already present in
   `mementum/` (it updates or skips rather than re-creating), per its prompt.
5. `task-lifecycle` ends with an `extract-task-knowledge` `:delegate` step that
   receives the same task `:input`, receives the implementation-review output as
   context, and yields a final summary that preserves the lifecycle/review
   outcome plus the extraction outcome.
6. The workflow definition and the `task-lifecycle` change have test coverage,
   and the new workflow is documented.

## Notes / risks

- **Quality without an approval gate.** The only safeguard against mementum
  noise is the qualitative, conservative significance/project-generality filter
  in the prompt (see Resolved decision 6).
- **Autonomous commits.** The workflow commits directly to `mementum/`. Within
  `task-lifecycle` this happens at the very end, after implementation review.
- **Open path during lifecycle.** A lifecycle-run task may still physically live
  under `munera/open/` when extraction runs. That path is authorized only for the
  trailing lifecycle invocation and only when the successful implementation-review
  predecessor is present as context; standalone open-task extraction is skipped.
