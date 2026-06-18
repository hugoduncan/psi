# 230 — Design/Plan review follow-up items

Unchecked items are open actionable feedback. `SCOPE_QUESTION:`-prefixed items
are scope decisions for the human only (do not execute; leave unchecked).

## Plan-review — ambiguity

- [x] DI-3 invocation-key mismatch on the gate's real (judge) path. DI-3 says the
      handler receives `{:keys [args ctx session-id]}` and seeds `query-in` with
      `{:psi.agent-session/session-id session-id}`, citing both
      `workflow_judge.clj` and `deterministic-operation-action` as the source of
      the invocation map. Those two paths carry **different keys**: the direct
      `/operation invoke` path (`deterministic-operation-registry/build-invocation`)
      supplies `:session-id`, but the gate actually runs as the `:judge` of the
      `check-scope-question-status` `:invoke` step, whose invocation
      (`workflow_judge/execute-invoke-judge!`) supplies `:ctx` +
      `:parent-session-id` and **no `:session-id`**. On the production judge path
      `(:session-id invocation)` is nil → `agent-session-cwd` resolves
      `worktree-path` from a nil session → resolver returns nil →
      `parse-scope-question-gate` fails-open to `proceed-route` → the gate
      **silently never fires** (the exact silent-default failure mode the task
      exists to prevent). Pin in the plan which identifier the handler reads on
      the judge path (`:parent-session-id`, confirmed to resolve the task's
      worktree), and require a test that exercises the gate through the real
      `:invoke`-step judge invocation, not only the direct-invoke harness — the
      Slice-3 plan tests via `deterministic-operation-action`/registry, which
      provides `:session-id` and would pass while production fails (test/prod
      divergence masking the defect).

- [x] DI-1 `:open-questions` content underspecified / self-contradictory. The
      "Scanner semantics" bullet specifies `:details {:open-questions [<line…>]}`
      (full matched lines), while the same bullet's closing sentence and
      `steps.md` say the detail "captures the concern text (the substring after
      the marker)". Two interpretations of one field. Pin exactly what each
      `:open-questions` entry holds (raw matched line vs trimmed post-marker
      concern substring) so the scanner unit tests (Slice 1) assert a single
      defined shape.

- [x] DI-3 step 3 omits the agent-session-ctx that the handler must pass as
      `resolvers/query-in`'s first positional arg. `query-in`'s signature is
      `(query-in ctx q extra-entity)`: the first `ctx` is seeded as
      `:psi/agent-session-ctx`, which `agent-session-cwd` requires (input
      `[:psi/agent-session-ctx :psi.agent-session/session-id]`; body
      `(support/session-worktree-path agent-session-ctx session-id)`) to resolve
      `:psi.agent-session/worktree-path` for the new
      `agent-session-task-artifact-content` resolver. DI-3 precisely pins the
      session-*id* key (`:parent-session-id`) but never says where the `ctx` arg
      comes from. An implementer who passes `nil`, reconstructs a ctx, or relies
      only on the entity map gets a nil worktree-path → resolver returns `nil` →
      `parse-scope-question-gate` fails open to `proceed-route` → the gate
      silently never fires (the *same* silent-default failure class as the
      already-resolved session-id item). Pin that the handler passes
      `(:ctx invocation)` (the judge-path agent-session-ctx) as `query-in`'s
      first positional arg, alongside the `(or parent-session-id session-id)`
      entity. (Note: `query-in` derives `session-id` from `extra-entity`, so the
      session id must be in `extra-entity`, not only in `ctx`.)

- [x] DI-4 normalization grammar is internally inconsistent, leaving the exact
      regex underspecified. The first bullet matches a
      `munera/(open|closed)/NNN-slug` **substring**, but its own parenthetical
      and the Open-questions note say the task is always `open` during a pre-plan
      gate run, and DI-4 also says to mirror
      `parse-munera-open-task-path-routing`'s grammar — whose pattern
      `#"^munera/open/[0-9]{3,}-[a-z0-9]+(?:-[a-z0-9]+)*$"` is **open-only**,
      **anchored (full-string)**, and therefore cannot substring-match. An
      implementer cannot tell whether to (a) accept `closed` as well as `open`,
      (b) substring-match vs full-match, or (c) reuse vs replace the existing
      anchored pattern. These choices change which inputs the gate accepts
      (observable for `munera/closed/…` or otherwise-prefixed inputs, despite the
      fail-open default). Pin the exact normalization grammar — directory set
      (open-only vs `open|closed`), anchored vs substring, and the slug char
      class — and reconcile the "mirror existing grammar" reference with the
      substring/`closed` intent.

## Plan-review — inconsistency

- [x] design.md D1 vs plan.md DI-5/R4 directly contradict on the
      non-converged-AND-open-`SCOPE_QUESTION` precedence. design.md states as a
      **settled decision** (D1, line 124; restated line 111) that "the
      `SCOPE_QUESTION` handback **wins**" when a run is both non-converged (229)
      and has an open scope question. plan.md decides the **opposite**: because
      the scope gate sits after the design gate's `REPEAT` branch, a non-converged
      run "routes via the 229 design handback, **not the scope handback**" (R4,
      line 273; DI-5), and the plan explicitly acknowledges this "but D1 says the
      `SCOPE_QUESTION` handback should win" then re-interprets D1 as intent-only.
      design.md is the authority for *what/why*, so its literal text and the plan
      disagree on observable routing. Reconcile: either correct design.md D1 (and
      the "Relationship to task 229" restatement) to state the actually-decided
      behaviour — non-converged => 229 handback; the scope handback wins **only
      among runs that reach the scope gate**, i.e. converged-but-open
      `SCOPE_QUESTION` (the 022 gap) — or change the plan to genuinely make the
      scope handback win when both conditions hold. Do not leave the literal
      "`SCOPE_QUESTION` handback wins" in design.md contradicting the plan's
      "not the scope handback".

- [ ] design.md gate-placement wording now contradicts the implemented plan
      (live, untracked-as-open). design.md states the gate sits **after**
      `check-design-review-status` — Composition para (lines 109-110: "the 230
      gate sits **after** 229's `check-design-review-status` and **before**
      `create-task-plan`") and D1 (lines 118-119: "A single gate sits between
      `review-task-design` (after 229's `check-design-review-status`) and
      `create-task-plan`"). The prior inconsistency turn resolved the
      precedence/"scope handback wins" contradiction on the **plan side only**:
      plan.md DI-5/Slice 4 and steps.md now insert `check-scope-question-status`
      at `:steps` **index 1, before `check-design-review-status`**, routing
      `DONE → check-design-review-status`. So design.md's "after
      `check-design-review-status`" placement is stale and directly contradicts
      plan.md/steps.md's "before" placement (and is jointly unsatisfiable with
      design.md's own governing "the `SCOPE_QUESTION` handback wins": under linear
      routing a non-converged run terminates at `check-design-review-status`'s
      REPEAT handback before reaching any gate placed after it). This residual is
      currently recorded only in implementation.md prose, not as a trackable
      design-steps item, and is distinct from the checked precedence item above
      (that item targeted the literal "handback wins" text, now satisfied; this is
      the step-ordering placement wording). The follow-up executor may not edit
      design.md; the **human** must update design.md (Composition para + D1 + any
      "Relationship to task 229" restatement) so the placement wording reads
      "before `check-design-review-status`" to match the governing "scope handback
      wins" decision and the plan/steps.
