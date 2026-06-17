# 230 — Steps

Checklist for implementing the unresolved-`SCOPE_QUESTION` lifecycle gate.
Grouped by the `plan.md` slices. Tick each item with its commit sha / decision /
snag as work proceeds. Red→green per slice; cljfmt + clj-kondo pre-commit must
pass (AC-4).

## Slice 1 — pure scanner + unit tests

- [x] Add `parse-scope-question-gate` to
      `components/agent-session/src/psi/agent_session/workflow/routing.clj`:
      args `(content marker proceed-route open-route)`; returns the standard
      `{:status :ok :data <route> :summary <route> :details {…}}` shape (DI-1).
- [x] Scanner logic (D5): per line, `str/triml`, match unchecked checkbox
      `- [ ]` followed by optional whitespace then `marker`; collect the
      **trimmed concern substring** — the text after the `marker`, `str/trim`med
      (DI-1 single defined shape; **not** the raw line) — for each open line.
      `nil`/empty/no-match → `proceed-route`; ≥1 open → `open-route` with
      `:details {:open-questions [<concern…>]}`. Ignore `- [x]`/`- [X]` items.
- [x] Unit tests in
      `components/agent-session/test/psi/agent_session/workflow/routing_test.clj`:
  - [x] single unchecked `- [ ] SCOPE_QUESTION: …` → `open-route` + named question
  - [x] only checked `- [x] SCOPE_QUESTION: …` → `proceed-route` (AC-2)
  - [x] `nil` content and empty string → `proceed-route` (AC-2 absent file)
  - [x] mixed checked + unchecked → `open-route`
  - [x] indented unchecked item (leading whitespace) → `open-route` (`triml`)
  - [x] unchecked non-marker checklist item → `proceed-route` (ignored)
  - [x] route labels honoured from args (not hardcoded)
- [x] Run focused routing Scry suite green; clj-kondo clean. (9 tests / 236 assertions)
- [x] Commit (`⊨` / `⚒` 230 Slice 1: scope-question scanner).

## Slice 2 — task-artifact-content resolver + test

- [x] Add `agent-session-task-artifact-content` resolver to
      `components/agent-session/src/psi/agent_session/resolvers/session.clj`
      (next to `agent-session-cwd`): input
      `[:psi.agent-session/worktree-path :psi.munera/task-path
      :psi.munera/artifact-name]`, output `[:psi.munera/task-artifact-content]`;
      slurp `(io/file worktree-path task-path artifact-name)` from the
      working tree if it exists, else `nil` (DI-2).
- [x] Register the resolver in `session-resolvers/resolvers` so it reaches
      `all-resolvers` / `resolvers/query-in`.
- [x] Resolver test (temp worktree dir fixture): present `design-steps.md` →
      slurped content; absent file → `nil`; path composed from worktree+task+artifact.
      (`task_artifact_content_resolver_test.clj`, 1 test / 3 assertions)
- [x] Run focused agent-session resolver Scry suite green; clj-kondo clean.
- [x] Commit (230 Slice 2: task-artifact-content resolver).

## Slice 3 — deterministic gate operation + invocation test

- [x] Register `workflow/scope-question-gate-routing` in
      `components/agent-session/src/psi/agent_session/workflow/core.clj`
      `register-built-in-deterministic-operations!` with a `:description`.
- [x] Handler (DI-3): reads `task-path`/`artifact`/`marker`/`proceed-route`/
      `open-route` from `args` (validates string-ness; `:status :error`
      `:invalid-scope-question-gate-args` on malformed args); resolves owning
      session id as `(or parent-session-id session-id)` (judge path supplies
      `:parent-session-id`, not `:session-id`); normalizes `task-path` via
      `routing/normalize-open-task-path` (DI-4); runs `resolvers/query-in`
      passing `(:ctx invocation)` positionally + single extra-entity map carrying
      `:psi.agent-session/session-id` + `:psi.munera/task-path` +
      `:psi.munera/artifact-name`; calls `routing/parse-scope-question-gate`.
- [x] Confirmed the real `:workflow-input` shape: the gate arg
      `:task-path {:from :workflow-input :path [:input]}` resolves identically to
      the existing task-lifecycle delegate steps' `:input` field (same source
      ref). DI-4 normalization (open-only, anchored) + fail-open covers all
      shapes (bare `NNN-slug`, full `munera/open/NNN-slug`, free text).
- [x] Operation invocation test
      (`scope_question_gate_operation_test.clj`, 7 tests / 17 assertions):
  - [x] unchecked `SCOPE_QUESTION:` → `SCOPE_QUESTION_OPEN`, names the question (AC-1)
  - [x] only-checked items → `DONE` (AC-2)
  - [x] absent `design-steps.md` → `DONE` (AC-2)
  - [x] resume: same task after the item is checked → `DONE` (AC-3)
  - [x] DI-4 input-shape normalization: full path verbatim; bare token →
        `munera/open/NNN-slug`; `munera/closed/…` / free text → fail-open `DONE`
  - [x] malformed args → `:status :error`
  - [x] judge-path: invocation carrying `:parent-session-id`, no `:session-id`,
        resolves worktree from `:parent-session-id` and fires `SCOPE_QUESTION_OPEN`
- [x] Run focused operation Scry suite green; clj-kondo clean.
- [x] Commit (230 Slice 3: scope-question-gate-routing operation).

## Slice 4 — wire task-lifecycle.edn + update task-lifecycle-test

- [x] Edit `.psi/workflows/task-lifecycle.edn` (DI-5):
  - [x] insert `check-scope-question-status` `:invoke` step at `:steps` index 1
        (after `review-task-design`, before `check-design-review-status`) with the
        gate idiom: step `:operation constant-routing {:route "DONE"}`; `:judge`
        `workflow/scope-question-gate-routing` with authored args
        (`:task-path {:from :workflow-input :path [:input]}`,
        `:artifact "design-steps.md"`, `:marker "SCOPE_QUESTION:"`,
        `:proceed-route "DONE"`, `:open-route "SCOPE_QUESTION_OPEN"`);
        `:on {"DONE" {:goto "check-design-review-status"}
        "SCOPE_QUESTION_OPEN" {:goto "final-summary-scope-question-open"}}`.
  - [x] leave `check-design-review-status` `:on` unchanged
        (`{"DONE" {:goto "create-task-plan"} "REPEAT" {:goto
        "final-summary-design-not-converged"}}`).
  - [x] append `final-summary-scope-question-open` `:session` step **last**:
        `:tools ["read" "bash"]`, contributions from `:workflow-original`;
        template names the open `SCOPE_QUESTION:` item(s) read from
        `design-steps.md`, states the lifecycle stopped before plan creation,
        asks the human to record the decision + rationale in `design.md`, check
        the item, and re-invoke `task-lifecycle` to resume; does not proceed to
        plan or extract knowledge; explicit-terminal
        `:judge constant-routing {:route "DONE"}` + `:on {"DONE" {:goto :done}}`.
- [x] `clj-paren-repair .psi/workflows/task-lifecycle.edn`; confirm it loads
      (workflow-loader) without errors.
- [x] Update `task-lifecycle-test`
      (`components/workflow-loader/test/psi/workflow_loader/
      workflow_definitions_test.clj:655`):
  - [x] count `13 → 15`; insert `check-scope-question-status` (index 1) +
        `final-summary-scope-question-open` (last) in the name vector; insert
        `:invoke` (index 1) + `:session` (last) in the type vector.
  - [x] `(repeat 13 {})` → `(repeat 15 {})` for the `:yields`/`:terminal-contract`
        assertion.
  - [x] keep the design-gate `:on` `"DONE"` target assertion `"create-task-plan"`
        (design gate is not repointed).
  - [x] add scope-gate assertions: `:judge` =
        `workflow/scope-question-gate-routing` with authored args; `:on` =
        `{"DONE" {:goto "check-design-review-status"}
        "SCOPE_QUESTION_OPEN" {:goto "final-summary-scope-question-open"}}`.
  - [x] add handback assertions: `:tools ["read" "bash"]`; template names the
        open `SCOPE_QUESTION` / stops before plan creation; terminates
        `:goto :done` (AC-4 definition coverage).
  - [x] confirm the `delegate-steps` (by-`:type`) assertions still hold
        unchanged (6 delegates).
- [x] Run focused workflow-loader Scry suite green; clj-kondo clean.
- [x] Commit (230 Slice 4: wire pre-plan scope-question gate into task-lifecycle).

## Slice 5 — docs + coherence

- [x] `doc/workflows.md`: document the pre-plan `SCOPE_QUESTION` gate in
      `task-lifecycle` (content-based detection in `design-steps.md`; halts +
      hands back; resume by checking the item, recording the decision in
      `design.md`, and re-invoking).
- [x] `CHANGELOG.md` `[Unreleased] Changed`: `task-lifecycle` halts before plan
      creation and hands back, naming the open `SCOPE_QUESTION:` item(s), instead
      of silently defaulting the scope decision.
- [x] Coherence: re-read edited files (`sync`); update `mementum/state.md`
      workflow-gate bullet if warranted.
- [x] Final verify: AC-1 (gate-open + handback wiring), AC-2 (only-checked /
      absent proceed), AC-3 (resume re-scan), AC-4 (routing/resolver/operation +
      task-lifecycle definition tests, cljfmt + clj-kondo pre-commit) each
      covered. Task-230 suites green; one pre-existing unrelated
      `review-follow-up-plan-prompt-contract-test` failure (commit `2b7b0face`,
      out of scope) noted in implementation.md.
- [x] Commit (230 Slice 5: docs + changelog + coherence).
