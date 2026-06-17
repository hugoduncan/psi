# 230 — Design/Plan review follow-up items

Unchecked items are open actionable feedback. `SCOPE_QUESTION:`-prefixed items
are scope decisions for the human only (do not execute; leave unchecked).

## Plan-review — ambiguity

- [ ] DI-3 invocation-key mismatch on the gate's real (judge) path. DI-3 says the
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

- [ ] DI-1 `:open-questions` content underspecified / self-contradictory. The
      "Scanner semantics" bullet specifies `:details {:open-questions [<line…>]}`
      (full matched lines), while the same bullet's closing sentence and
      `steps.md` say the detail "captures the concern text (the substring after
      the marker)". Two interpretations of one field. Pin exactly what each
      `:open-questions` entry holds (raw matched line vs trimmed post-marker
      concern substring) so the scanner unit tests (Slice 1) assert a single
      defined shape.
