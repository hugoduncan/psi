# Steps — 239 post-turn tooling-friction analysis

## Slice 1 — pure core

- [x] Generalize `render-history-excerpt` in
      `extensions/context-manager/src/extensions/context_manager.clj` to
      accept turn-count and char-cap parameters (default preserving current
      entity-resolution behaviour); verify existing rendering tests still pass.
- [x] Add `build-friction-prompt`: takes {history-excerpt, open-tasks,
      recent-closed-tasks} → {system-prompt, user-prompt} embedding detection
      criteria (tooling/dependency friction only; excludes project bugs,
      features, user mistakes), the dedup task list, the strict output
      contract (ISSUE/FRICTION/EVIDENCE/SUGGESTION blocks, DUPLICATE lines,
      NONE), and the per-run expectation of at-most-a-few issues.
- [x] Add `parse-friction-output`: text → {:issues [{:slug :title :friction
      :evidence :suggestion}] :duplicates [{:slug :existing-id}]}; malformed
      blocks dropped; nil/blank/NONE → empty; unit tests for nominal, NONE,
      malformed, mixed issue+duplicate output.
- [x] Add `render-friction-design-md`: issue map + evidence context → design.md
      content with auto-generated marker naming the analyzer, friction,
      evidence (turn references), suggested change; unit test asserts all four
      required elements present.
- [x] Add cap logic: `cap-issues` takes issues + cap (2) → {:selected :dropped};
      unit test 0/1/2/3-issue cases.
- [x] Run `bb test --focus extensions.context-manager` (or matching test ns),
      lint, `clj-paren-repair`; commit slice 1.

## Slice 2 — task-file creation

- [x] Add `allocate-task-id`: scan `munera/open/` and `munera/closed/` under a
      given root → max NNN + 1, zero-padded ≥3; unit test with temp dirs
      (empty, open-only, closed-max, non-numeric noise dirs preserved/ignored).
- [x] Add `create-friction-task!`: worktree-root + issue → writes
      `munera/open/NNN-slug/design.md` only; on pre-existing directory,
      re-allocate NNN (bounded retries, e.g. 5) then give up returning nil;
      returns created task id or nil; unit tests: creation, collision
      re-allocation, retry exhaustion, design.md-only (no plan.md/steps.md).
- [x] Add closed-task listing: `recent-closed-tasks` root → last 20 closed task
      ids+titles by git commit order of moves into `munera/closed/`, falling
      back to name/mtime order when git fails; `open-tasks` → all open task
      ids+titles; unit tests with temp git repo and non-git dir fallback.
- [x] Run tests + lint; commit slice 2.

## Slice 3 — orchestration

- [x] Add `friction-helper-session-ids` defonce atom; record in
      implementation.md the deliberate atom-vs-ctx decision and the ctx-keyed
      managed-service migration as a follow-up candidate.
- [x] Add `friction-analysis` `(api payload collaborators)` orchestration:
      guard (payload session-id ∈ friction-helper-session-ids ∨
      entity-resolution-helper-session-ids ∨ known-helper session info) →
      no-op; fetch history/worktree via collaborators; select local model;
      single bounded helper run; parse; dedup diagnostics logged for
      DUPLICATE lines; cap at 2 with dropped-issues diagnostic; create tasks
      via `:create-task!`; every failure path (no model, no worktree, helper
      failure/timeout, parse-empty) → no task + diagnostic log + no throw.
- [x] Tests (injected collaborators, no real model/sessions) covering AC7:
      issue → task created; duplicate → skipped + diagnostic; helper failure
      → no-op; missing local model → no-op; missing worktree → no-op; own
      helper session excluded; entity-resolution helper session excluded;
      other known helper/infra session excluded; 3 issues → 2 tasks (cap).
- [x] Test: `friction-analysis` never throws when every collaborator throws.
- [x] Run tests + lint; commit slice 3.

## Slice 4 — wiring & real collaborators

- [x] Confirm EQL attributes for post-turn history (last 4 turns), session
      effective worktree, and helper-session identification against the
      resolver graph; note findings (and any fallback used) in
      implementation.md.
- [x] Implement real collaborators: `:fetch-history` (EQL, last 4 turns →
      excerpt input), `:session-info` (worktree + helper-session detection),
      `:list-tasks` (slice-2 fns against the session worktree),
      `:create-task!` (slice-2 fn), `:select-model`
      (reuse `default-select-model`), `:run-helper` (bounded no-tools helper
      child session tracked in `friction-helper-session-ids`, 120s budget,
      future-owns-teardown pattern as in `default-run-helper`).
- [x] Wire fire-and-forget: extend the existing `session_turn_finished`
      handler in `init` to spawn a `future` calling `friction-analysis`
      with real collaborators; catch-all + `(:log api)` inside the future;
      handler return value unchanged.
- [x] Align extension manifest/permissions (subscriptions, mutations used:
      create-child-session, run-agent-loop-in-session, close-session; EQL
      reads) per extension-development skill. (No manifest/permission
      changes needed — `.psi/extensions.edn` declares no
      `:allowed-events`/permission restriction for `psi/context-manager`,
      matching the pre-existing entity-resolution augmenter's use of the
      same mutation ops with no extra manifest entries.)
- [x] Integration-style test: fire `session_turn_finished` through the test
      support state → handler returns promptly (non-blocking) and analysis
      runs (deterministic via injected collaborators or latch).
- [x] Run tests + lint; commit slice 4.

## Slice 5 — docs & verification

- [x] Update extension namespace docstring and any context-manager docs in
      `doc/` describing the friction analyzer (trigger, scope, exclusions,
      cap, dedup, generated-task format).
- [x] Add CHANGELOG `[Unreleased]` → Added entry (user-visible behaviour:
      automatic post-turn tooling-friction task creation).
- [x] Verify all acceptance criteria 1–7 against design.md; note verification
      in implementation.md.
- [x] Full `bb test`; `clj-kondo --lint` on changed files; commit slice 5.

## Follow-up (implementation review)

- [x] Fix flaky cross-namespace test pollution: `entity-resolution-helper-session-ids`
      and `friction-helper-session-ids` are `defonce` atoms shared across the
      whole test JVM, and several test files (e.g. every friction-analysis
      test, `context_manager_test_support/base-tp`) use the same hardcoded
      `session-id "s1"`. `context_manager_model_selection_test.clj` has no
      `use-fixtures` resetting either atom, so a prior test in a different
      namespace that leaves `"s1"` tracked (e.g. via
      `entity-resolution-helper-session-excluded-test` or
      `own-helper-session-excluded-test`) causes
      `entity-resolution-no-local-model-no-op-test` to spuriously return a
      no-op with `:turn-augmentation/diagnostic nil` instead of
      `"no local model"`. Reproduced directly: `clojure -M:test --focus
      extensions` failed this way in 1 of 6 unseeded runs. Add a
      `use-fixtures :each` reset of both atoms to
      `context_manager_model_selection_test.clj` (and audit other
      no-fixture context-manager test files for the same gap), or use
      distinct session-ids per test file to avoid the shared-state
      collision.
- [x] Reduce duplication between `default-run-helper` (entity-resolution,
      task 238) and `default-friction-run-helper` (task 239): the two
      functions are near-identical (~40–130 lines each) copies of the same
      bounded-child-session / future-owns-teardown / wall-clock-timeout
      mechanism, differing only in session-name, `:tool-ids`/`:tool-names`,
      and which tracking atom is swapped. Consider extracting a shared
      parameterized helper (session-name, tool-ids, tracking atom as
      parameters) to avoid the two copies drifting out of sync on future
      changes to the timeout/teardown logic.
- [x] Add a real wall-clock-timeout unit test for
      `default-friction-run-helper`, mirroring
      `default-run-helper-timeout-branch-test`
      (`context_manager_helper_runtime_test.clj`) for the entity-resolution
      helper — the friction helper's timeout/teardown branch currently has
      no dedicated test exercising the real `deref`/`::timeout` path with an
      injected small `:wall-clock-ms`.
