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

## Follow-up (implementation review, round 2)

- [x] Sanitize/validate the model-supplied `slug` before it is used to build
      a filesystem path. `parse-friction-block`
      (`extensions/context-manager/src/extensions/context_manager/friction.clj`)
      accepts any non-blank text between `ISSUE:` and `|` as `:slug` with no
      format check, and `create-friction-task!`/`allocate-task-id` splice it
      directly into `munera/open/NNN-slug` via `io/file`. A local-model
      output containing `/` or `..` in the slug (e.g. `ISSUE:
      ../../../../tmp/pwned | Evil`) is parsed through unchanged (confirmed
      by direct repro: `parse-friction-output` returns
      `:slug "../../../../../tmp/pwned"` verbatim) and passed straight to
      `(io/file root "munera" "open" id)`, i.e. path-traversal-shaped
      segments from untrusted model output reach filesystem-path
      construction with no validation — munera's own convention (`slug ∈
      kebab_case`, AGENTS.md) is not enforced here. Reject/drop ISSUE
      blocks whose slug isn't a plain kebab-case token (e.g.
      `#"^[a-z0-9]+(-[a-z0-9]+)*$"`) in `parse-friction-block`, consistent
      with the existing fail-safe "malformed block dropped" pattern, rather
      than relying only on `create-friction-task!`'s own I/O layer.
- [x] Add direct unit tests for the real (non-injected) `:fetch-history`/
      `:session-info` collaborators added in slice 4 —
      `default-fetch-history`, `default-session-info`,
      `friction/message-snippet`, and `friction/session-info-of`
      (`extensions/context_manager.clj` /
      `extensions/context_manager/friction.clj`) currently have no test
      exercising them against realistic EQL query-session result shapes
      (raw agent-core message maps with `:role`/`:content`,
      `:psi.agent-session/worktree-path`/`:psi.agent-session/session-name`
      result maps). The existing wiring test
      (`context_manager_friction_wiring_test.clj`) only reaches the
      "no worktree" no-op path via the nullable API's default empty
      query-session result, so it never actually calls these functions with
      data. This breaks the pattern already established for
      entity-resolution's equivalent real collaborators
      (`default-select-model`, `default-run-helper`), which do have direct
      `#'context-manager/...` unit tests
      (`context_manager_model_selection_test.clj`,
      `context_manager_helper_runtime_test.clj`).

## Follow-up (implementation review, round 3)

- [x] `known-helper-session-names` (`extensions/context_manager.clj`) is a
      fixed literal set `#{"entity-resolution" "friction-analysis"}`, but the
      runtime's actual workflow-step child sessions are named dynamically as
      `(str "workflow " step-id " attempt")` (confirmed:
      `components/workflow-runtime/src/psi/workflow_runtime/statechart_runtime.clj:179`,
      e.g. `"workflow builder attempt"`, `"workflow reviewer attempt"`). None
      of these match the fixed literal set, so `known-helper-session?`'s
      name-based backstop does not actually exclude "other workflow helper
      sessions" as design.md's Scope-of-sessions decision and AC5 require
      (design.md explicitly gives "other workflow helper sessions" as an
      example of what must be excluded; AC7's test list requires "other
      known helper/infra session excluded" coverage) — the friction analyzer
      will run on essentially every delegate/workflow-step child session
      (builder, reviewer, planner, sub-agent, etc.), the opposite of the
      intended exclusion. The existing
      `other-known-helper-session-excluded-test` only exercises the literal
      `"entity-resolution"` name, so it doesn't catch this gap. Match the
      `"workflow "`/`" attempt"` naming convention (e.g. a
      `str/starts-with? "workflow "` check, or a shared regex) in addition
      to the fixed literal set, and add a test using a realistic
      `"workflow builder attempt"`-style session-name.
- [x] `friction-history-turn-count` (4) is documented as "Number of
      most-recent turns fed to the friction helper" and design.md's AC1
      requires analysis of "the last 4 turns", but `default-fetch-history`
      builds its `:tail` from `:psi.agent-session/message-history` — one
      entry per *raw* agent-core message (user/assistant/tool-call/
      tool-result), not one per conversational turn (compare
      `build-augmentation-history-projection` in
      `components/agent-session/src/psi/agent_session/dispatch_effects.clj`,
      which the entity-resolution augmenter's equivalent `:tail` input also
      uses — same one-entry-per-raw-message granularity). `render-history-
      excerpt`'s `turn-count` arg then does `take-last 4` on that
      per-message tail, i.e. the friction helper actually sees the last 4
      raw messages, not the last 4 conversational turns — for any
      tool-heavy turn (several tool calls/results before a final reply) this
      can be far less context than 4 real turns, undermining AC1's intent.
      Either group raw messages into turn boundaries before taking the last
      4, or rename/redocument `friction-history-turn-count` to reflect that
      it bounds messages, not turns, and re-confirm the resulting window
      still satisfies AC1's intent.
