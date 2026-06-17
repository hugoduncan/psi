# 230 — Steps

Checklist for implementing the unresolved-`SCOPE_QUESTION` lifecycle gate.
Grouped by the `plan.md` slices. Tick each item with its commit sha / decision /
snag as work proceeds. Red→green per slice; cljfmt + clj-kondo pre-commit must
pass (AC-4).

## Slice 1 — pure scanner + unit tests

- [ ] Add `parse-scope-question-gate` to
      `components/agent-session/src/psi/agent_session/workflow/routing.clj`:
      args `(content marker proceed-route open-route)`; returns the standard
      `{:status :ok :data <route> :summary <route> :details {…}}` shape (DI-1).
- [ ] Scanner logic (D5): per line, `str/triml`, match unchecked checkbox
      `- [ ]` followed by optional whitespace then `marker`; collect the concern
      text after the marker for each open line. `nil`/empty/no-match →
      `proceed-route`; ≥1 open → `open-route` with
      `:details {:open-questions [...]}`. Ignore `- [x]`/`- [X]` items.
- [ ] Unit tests in
      `components/agent-session/test/psi/agent_session/workflow/routing_test.clj`:
  - [ ] single unchecked `- [ ] SCOPE_QUESTION: …` → `open-route` + named question
  - [ ] only checked `- [x] SCOPE_QUESTION: …` → `proceed-route` (AC-2)
  - [ ] `nil` content and empty string → `proceed-route` (AC-2 absent file)
  - [ ] mixed checked + unchecked → `open-route`
  - [ ] indented unchecked item (leading whitespace) → `open-route` (`triml`)
  - [ ] unchecked non-marker checklist item → `proceed-route` (ignored)
  - [ ] route labels honoured from args (not hardcoded)
- [ ] Run focused routing Scry suite green; clj-kondo clean.
- [ ] Commit (`⊨` / `⚒` 230 Slice 1: scope-question scanner).

## Slice 2 — task-artifact-content resolver + test

- [ ] Add `agent-session-task-artifact-content` resolver to
      `components/agent-session/src/psi/agent_session/resolvers/session.clj`
      (next to `agent-session-cwd`): input
      `[:psi.agent-session/worktree-path :psi.munera/task-path
      :psi.munera/artifact-name]`, output `[:psi.munera/task-artifact-content]`;
      slurp `(io/file worktree-path task-path artifact-name)` from the
      working tree if it exists, else `nil` (DI-2).
- [ ] Register the resolver in `session-resolvers/resolvers` so it reaches
      `all-resolvers` / `resolvers/query-in`.
- [ ] Resolver test (temp worktree dir fixture): present `design-steps.md` →
      slurped content; absent file → `nil`.
- [ ] Run focused agent-session resolver Scry suite green; clj-kondo clean.
- [ ] Commit (230 Slice 2: task-artifact-content resolver).

## Slice 3 — deterministic gate operation + invocation test

- [ ] Register `workflow/scope-question-gate-routing` in
      `components/agent-session/src/psi/agent_session/workflow/core.clj`
      `register-built-in-deterministic-operations!` with a `:description`.
- [ ] Handler (DI-3): read `task-path`/`artifact`/`marker`/`proceed-route`/
      `open-route` from `args` (validate; `:status :error` on malformed args);
      normalize `task-path` to a worktree-relative task dir (DI-4); run
      `resolvers/query-in` seeded with session-id + `:psi.munera/task-path` +
      `:psi.munera/artifact-name` for `[:psi.munera/task-artifact-content]`; call
      `routing/parse-scope-question-gate` and return its result.
- [ ] Confirm the real `:workflow-input` shape against an actual `task-lifecycle`
      invocation; finalize DI-4 normalization accordingly.
- [ ] Operation invocation test (temp worktree + task dir + `design-steps.md`
      fixtures), covering acceptance criteria:
  - [ ] unchecked `SCOPE_QUESTION:` → `SCOPE_QUESTION_OPEN`, names the question (AC-1)
  - [ ] only-checked items → `DONE` (AC-2)
  - [ ] absent `design-steps.md` → `DONE` (AC-2)
  - [ ] resume: same task after the item is checked → `DONE` (AC-3)
  - [ ] DI-4 input-shape normalization (bare `NNN-slug` and `munera/open/…` path)
  - [ ] malformed args → `:status :error`
- [ ] Run focused operation Scry suite green; clj-kondo clean.
- [ ] Commit (230 Slice 3: scope-question-gate-routing operation).

## Slice 4 — wire task-lifecycle.edn + update task-lifecycle-test

- [ ] Edit `.psi/workflows/task-lifecycle.edn` (DI-5):
  - [ ] insert `check-scope-question-status` `:invoke` step at `:steps` index 2
        (after `check-design-review-status`) with the gate idiom: step
        `:operation constant-routing {:route "DONE"}`; `:judge`
        `workflow/scope-question-gate-routing` with authored args
        (`:task-path {:from :workflow-input :path [:input]}`,
        `:artifact "design-steps.md"`, `:marker "SCOPE_QUESTION:"`,
        `:proceed-route "DONE"`, `:open-route "SCOPE_QUESTION_OPEN"`);
        `:on {"DONE" {:goto "create-task-plan"}
        "SCOPE_QUESTION_OPEN" {:goto "final-summary-scope-question-open"}}`.
  - [ ] repoint `check-design-review-status` `:on` `"DONE"` →
        `{:goto "check-scope-question-status"}`.
  - [ ] append `final-summary-scope-question-open` `:session` step **last**:
        `:tools ["read" "bash"]`, contributions from `:workflow-original`;
        template names the open `SCOPE_QUESTION:` item(s) read from
        `design-steps.md`, states the lifecycle stopped before plan creation,
        asks the human to record the decision + rationale in `design.md`, check
        the item, and re-invoke `task-lifecycle` to resume; does not proceed to
        plan or extract knowledge; explicit-terminal
        `:judge constant-routing {:route "DONE"}` + `:on {"DONE" {:goto :done}}`.
- [ ] `clj-paren-repair .psi/workflows/task-lifecycle.edn`; confirm it loads
      (workflow-loader) without errors.
- [ ] Update `task-lifecycle-test`
      (`components/workflow-loader/test/psi/workflow_loader/
      workflow_definitions_test.clj:655`):
  - [ ] count `13 → 15`; insert `check-scope-question-status` (index 2) +
        `final-summary-scope-question-open` (last) in the name vector; insert
        `:invoke` (index 2) + `:session` (last) in the type vector.
  - [ ] `(repeat 13 {})` → `(repeat 15 {})` for the `:yields`/`:terminal-contract`
        assertion.
  - [ ] change the design-gate `:on` `"DONE"` target assertion from
        `"create-task-plan"` to `"check-scope-question-status"`.
  - [ ] add scope-gate assertions: `:judge` =
        `workflow/scope-question-gate-routing` with authored args; `:on` =
        `{"DONE" {:goto "create-task-plan"}
        "SCOPE_QUESTION_OPEN" {:goto "final-summary-scope-question-open"}}`.
  - [ ] add handback assertions: `:tools ["read" "bash"]`; template names the
        open `SCOPE_QUESTION` / stops before plan creation; terminates
        `:goto :done` (AC-4 definition coverage).
  - [ ] confirm the `delegate-steps` (by-`:type`) assertions still hold
        unchanged (6 delegates).
- [ ] Run focused workflow-loader Scry suite green; clj-kondo clean.
- [ ] Commit (230 Slice 4: wire pre-plan scope-question gate into task-lifecycle).

## Slice 5 — docs + coherence

- [ ] `doc/workflows.md`: document the pre-plan `SCOPE_QUESTION` gate in
      `task-lifecycle` (content-based detection in `design-steps.md`; halts +
      hands back; resume by checking the item, recording the decision in
      `design.md`, and re-invoking).
- [ ] `CHANGELOG.md` `[Unreleased] Changed`: `task-lifecycle` halts before plan
      creation and hands back, naming the open `SCOPE_QUESTION:` item(s), instead
      of silently defaulting the scope decision.
- [ ] Coherence: re-read edited files (`sync`); update `mementum/state.md`
      workflow-gate bullet if warranted.
- [ ] Final verify: AC-1..AC-4 each covered by a test; full focused suites green;
      clj-kondo clean.
- [ ] Commit (230 Slice 5: docs + changelog + coherence).
