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

1. **Detect** open (unchecked) `- [ ] SCOPE_QUESTION: …` items in a task's
   `design-steps.md` (content-based; independent of the design-review convergence
   signal). A dedicated deterministic operation (D4) consumes resolver-read
   content (D2) and yields a routing status + the list of open questions.
2. **Gate** the lifecycle: a single pre-plan gate (D1) — when ≥1 open
   `SCOPE_QUESTION` exists, halt **before `create-task-plan`** and produce a
   clear, human-facing "scope decision needed" hand-back that names the open
   question(s).
3. **Resume**: stateless re-scan (D3) — the human checks the item and records the
   decision/rationale in `design.md` (D5), then re-invokes `task-lifecycle`; the
   idempotent gate re-scans and proceeds. This path is tested (AC-3).
4. **Tests**: workflow grammar/runtime coverage of the gate (open → halt; none →
   no regression; resume path), under the existing `workflow_*_test` suites
   (`components/agent-session/test`, `components/workflow-runtime/test`,
   workflow-loader definition tests). cljfmt + clj-kondo pre-commit pass.

Out of scope (separate tasks, cross-referenced):

- The **engine** change making `:max-iterations` exhaustion route
  author-controllable instead of hardcoded `:target :failed` — graceful
  non-convergence. This is task **229-author-routed-workflow-exhaustion**
  (`:on-max-iterations`), now **implemented and closed** (landed in
  `task-lifecycle.edn` as `check-design-review-status`/`check-plan-review-status`
  + `final-summary-not-converged`). The `SCOPE_QUESTION` gate is detectable purely
  from `design-steps.md` content and does **not** require it.
- A **design-review guardrail** that treats *"the design defers a decision to the
  human"* as a halt/`SCOPE_QUESTION` condition, stopping the review loop from
  thrashing on under-decided designs at the source. Distinct from 230 (which gates
  on an *already-filed* `SCOPE_QUESTION`). Separate task; cross-reference only.
  (Motivated directly by 230's own runaway design review — see git tag
  `230-design-review-churn`.)

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

Composition is settled in D1: the 230 gate sits **after** 229's
`check-design-review-status` and **before** `create-task-plan`; when a run is both
non-converged and has an open `SCOPE_QUESTION`, the `SCOPE_QUESTION` handback wins.
229 is **implemented and closed** on `exhaustion-routing`, so 230 builds on the
post-229 `task-lifecycle.edn` shape and reuses the gate idiom rather than
duplicating handback machinery.

## Settled design decisions

- **D1 — Gate placement: one gate, pre-plan.** A single gate sits between
  `review-task-design` (after 229's `check-design-review-status`) and
  `create-task-plan`. No separate pre-close backstop: `SCOPE_QUESTION`s are only
  *produced* by design review (plan/implement do not file them), so the pre-plan
  gate covers the lifecycle (`λone_way`, minimal surface). When a run is both
  non-converged (229) **and** has an open `SCOPE_QUESTION`, the `SCOPE_QUESTION`
  handback wins (it names the decision; 229's is generic).
- **D2 — Detection source + mechanism.** `design-steps.md` is the **sole**
  canonical location for `SCOPE_QUESTION:` items (it is where design-review
  follow-ups live). Absent `design-steps.md` → no open questions → proceed (AC-2).
  *Mechanism principle:* the file read goes through a **generic task-artifact read
  resolver** (reads-through-resolvers, mirroring
  `agent_session/resolvers/session.clj`, which already `slurp`s git files inside a
  `defresolver`), feeding a **pure parser**. The workflow-specific
  `design-steps.md` path and `SCOPE_QUESTION:` marker live in **authored EDN**,
  not runtime code. design.md records only this principle; the resolver-vs-step
  wiring is deferred to plan.md (over-specifying it here is what triggered the
  earlier review thrash).
- **D3 — Resume: stateless re-scan.** The gate reads current `design-steps.md`
  content on each run. The human resolves by checking the item and recording the
  decision (D5), then **re-invokes `task-lifecycle`**; the gate re-scans, finds no
  unchecked `SCOPE_QUESTION`, and proceeds. The gate is idempotent so re-invoke is
  safe; no special resume plumbing required (this is the defined, tested path for
  AC-3).
- **D4 — Status vocabulary: dedicated deterministic operation.** The gate is a
  **deterministic content scan with no LLM**, unlike `pass-status-routing` (which
  parses an LLM `PASS_STATUS` line). A dedicated pure operation yields the
  DONE/handback route, keeping the gate deterministic and avoiding op-output/naming
  collisions. Route labels, marker, and artifact path stay authored-EDN (generic
  primitive).
- **D5 — Resolved signal: the checkbox.** The machine-checkable signal is the
  checkbox state: the gate passes iff no unchecked `- [ ] SCOPE_QUESTION: …`
  lines remain. The human additionally records the decision + rationale in
  `design.md` (per `change_chain`, scope decisions are *intent* → live in design);
  that is a human obligation/AC, not machine-enforced. **Canonical marker form:**
  `- [ ] SCOPE_QUESTION: <concern>` — the marker is the item prefix immediately
  after the checkbox, matching the review prompts' "item prefixed `SCOPE_QUESTION:`".

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
