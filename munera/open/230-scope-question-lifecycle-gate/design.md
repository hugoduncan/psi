# 230 — Unresolved SCOPE_QUESTION items must gate the task lifecycle

## Intent (why)

The design-review workflow has a **scope-freeze guardrail**: a reviewer who
believes a task's scope boundary is wrong may not silently edit it — it must file
exactly one follow-up item prefixed `SCOPE_QUESTION:` for a human to decide
(`review-task-design-{architecture,ambiguity,inconsistency}-review.md`), and the
follow-up executor must **not** act on it
(`review-follow-up-design.md`: "Do not execute `SCOPE_QUESTION:` items … Leave
every such item unchecked … record … that the scope question is deferred to the
user"). This correctly stopped silent re-scoping.

But the guardrail only does half the job: a `SCOPE_QUESTION` is **recorded and
deferred, and then nothing in the lifecycle blocks on it**. `task-lifecycle`
proceeds design → plan → implement → review → extract-knowledge and is ready to
close with the scope question still open. In a real run (rocksdb task
`022-persisted-schema-reopen-validation`) an open `SCOPE_QUESTION` about whether
value-grouped `:bucket-size` belonged in the reopen identity slid all the way to
"ready to close", got **silently defaulted "out" in shipped code** (a real
silent-data-corruption hole), and was only caught because a human later asked.

Filing-without-surfacing turns a scope question into a silent default — the exact
failure mode (silent, unreviewed scope decisions) the guardrail exists to
prevent. Scope decisions are precisely the class that must not be made implicitly
by an autonomous lifecycle.

## Goal

Make an unresolved `SCOPE_QUESTION` a **blocking gate** in `task-lifecycle`: when
one or more open (unchecked) `SCOPE_QUESTION:` items exist for a task, the
lifecycle must **halt and surface them to the human for a decision** instead of
auto-proceeding into plan/implement/close. The human's decision and rationale are
recorded in the task artifacts; only then does the lifecycle continue.

## Context (current behaviour)

- `SCOPE_QUESTION:` items are filed as design-review follow-up items and, per the
  observed convention (cf. task 229's `design-steps.md`), live as unchecked
  checklist lines (`- [ ] SCOPE_QUESTION: …`) in the task's **`design-steps.md`**.
- Crucially, the design-review loop's convergence signal does **not** reliably
  reflect an open `SCOPE_QUESTION`. A `SCOPE_QUESTION` "counts as actionable
  feedback" when first raised, but the prompts forbid re-raising the same boundary
  concern on later passes — so a subsequent pass can emit a converged
  (`REVIEW_COMPLETE`/DONE) signal while the `SCOPE_QUESTION` line remains
  unchecked in `design-steps.md`. The loop reports "done" with the scope question
  still open. Detection must therefore be **content-based** (scan
  `design-steps.md`), not derived from the review loop's pass/convergence status.
- The established lifecycle-gate pattern to mirror is
  `task-lifecycle.edn`'s `check-implementation-review-status`: an `:invoke` step
  whose `pass-status-routing` judge routes the lifecycle to a handback summary
  (`final-summary-without-extraction`) when review did not pass, stopping the run
  and handing back to the human.
- Candidate judge/operation surfaces to reuse or extend:
  `workflow/pass-status-routing`, `workflow/pass-feedback-routing`,
  `workflow/constant-routing`, plus the deterministic-operation mechanism
  (a new `:invoke` operation that scans `design-steps.md`).

## Scope

In scope:

1. **Detect** open (unchecked) `SCOPE_QUESTION:` items in a task's
   `design-steps.md` (content-based; independent of the design-review convergence
   signal). Likely a deterministic operation (`:invoke`) that scans for
   `- [ ] … SCOPE_QUESTION:` lines and yields a routing status + the list of open
   questions, mirroring `check-implementation-review-status`.
2. **Gate** the lifecycle: when ≥1 open `SCOPE_QUESTION` exists, halt **before
   plan creation** and produce a clear, human-facing "scope decision needed"
   hand-back that names the open question(s). (Backstop before close to be
   decided — see open questions.)
3. **Resume**: define and test how a human records a resolution (decision +
   rationale in the task artifacts; check the `SCOPE_QUESTION` item) and re-enters
   the lifecycle so it continues from the gate.
4. **Tests**: workflow grammar/runtime coverage of the gate (open → halt; none →
   no regression; resume path), under the existing `workflow_*_test` suites
   (`components/agent-session/test`, `components/workflow-runtime/test`,
   workflow-loader definition tests). cljfmt + clj-kondo pre-commit pass.

Out of scope (separate task, cross-referenced):

- The **engine** change making `:max-iterations` exhaustion route
  author-controllable instead of hardcoded `:target :failed`
  (`components/workflow-runtime/.../statechart.clj` `compile-routing-transitions`,
  schema in `ir.clj`) — graceful non-convergence. This is task
  **229-author-routed-workflow-exhaustion** (`:on-max-iterations`). The
  `SCOPE_QUESTION` gate is detectable purely from `design-steps.md` content and
  does **not** require the engine change.

## Relationship to task 229 (must coordinate, do not bundle)

229 and 230 both add lifecycle gates after `review-task-design` and both hand back
to the human, but they answer different questions and compose:

- **229** — *did the design review converge?* Generic non-convergence handback
  (engine `:on-max-iterations` + a `check-design-review-status` gate routing an
  unconverged review to a "did not converge" summary). An open `SCOPE_QUESTION` is
  one *cause* of non-convergence 229 may catch generically.
- **230** — *is there an open scope decision?* Specific, content-based detection
  that must fire **even when the design review reports converged** with an
  unchecked `SCOPE_QUESTION` (the precise gap that let 022 slip through). It names
  the scope question(s) explicitly rather than a generic "did not converge".

The design must specify how the 230 gate orders/composes with 229's
`check-design-review-status` gate (both sit between `review-task-design` and
`create-task-plan`). 229 is being implemented concurrently on branch
`exhaustion-routing`; 230 should assume the post-229 `task-lifecycle.edn` shape
and reuse the gate idiom rather than duplicating handback machinery.

## Open design questions (to settle collaboratively)

1. **Gate placement.** Before plan creation only (primary), or also a backstop
   before close? Recommend primary gate pre-plan; evaluate whether a pre-close
   backstop adds value or is redundant once the pre-plan gate exists.
2. **Detection source of truth.** Is `design-steps.md` the sole canonical
   location for `SCOPE_QUESTION:` items, or can they also appear elsewhere (e.g.
   `implementation.md` deferral notes)? Confirm the single authoritative source
   the scanner reads. Define behaviour when `design-steps.md` is absent (treat as
   no open questions → proceed).
3. **Resume mechanism.** How the human's resolution re-enters the lifecycle:
   check the `SCOPE_QUESTION` item + record decision/rationale (where —
   `design.md`? `implementation.md`?), then resume via re-run / workflow
   `resume-run`. The gate re-scans and proceeds when no unchecked `SCOPE_QUESTION`
   remains. Must be a *defined, tested* path (AC-3).
4. **Status vocabulary.** A dedicated deterministic operation yielding a
   DONE/handback route, vs reusing `pass-status-routing` with a new status. Keep
   workflow-specific labels in authored EDN/operations, not generic runtime code.
5. **What counts as "resolved".** Checked checkbox alone, or checkbox + a recorded
   decision marker? Define the minimal, machine-checkable resolution signal.

## Acceptance criteria

- AC-1: A task whose `design-steps.md` has ≥1 unchecked `SCOPE_QUESTION:` item
  causes `task-lifecycle` to halt with a clear human-facing prompt naming the open
  question(s), instead of proceeding to plan/implement/close.
- AC-2: With no open `SCOPE_QUESTION:` items, the lifecycle behaves exactly as
  today (no regression), including a `design-steps.md` with only checked
  `SCOPE_QUESTION` items and a task with no `design-steps.md`.
- AC-3: There is a defined, tested path for the human to record a resolution and
  resume the lifecycle from the gate.
- AC-4: Workflow grammar/runtime + definition tests cover the gate (halt, no-op,
  resume); cljfmt + clj-kondo pre-commit hooks pass.
