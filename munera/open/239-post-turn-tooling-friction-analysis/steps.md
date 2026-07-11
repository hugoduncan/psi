      race is only guarded against for id-collision (the `next-free-task-id`
      retry loop), not for two concurrent runs on the same session both
      passing their own dedup check for the same underlying issue. Consider
      a per-session in-flight guard (e.g. skip/coalesce a new run for
      `session-id` while a previous run for that same `session-id` is still
      in flight).

## Follow-up (test-shaper skill)

- [x] The **`create-task!` failure/nil-return path on the success branch**
      is untested. `friction/run-analysis`
      (`extensions/context-manager/src/extensions/context_manager/friction.clj`)
      creates tasks via
      `(->> selected (keep (fn [issue] (try (create-task! worktree-root issue) (catch Throwable _ nil)))) vec)`
      — so an issue whose `create-task!` **returns `nil`** (a real path:
      `create-friction-task!`/`next-free-task-id` return `nil` on collision
      retry exhaustion — see `next-free-task-id-test`) or **throws** is
      silently dropped from `:created-task-ids`, while any sibling issues
      still succeed. No `friction-analysis`/`run-analysis` test exercises
      this partial-creation behavior: `issue-creates-task-test`,
      `cap-applied-test`, and `duplicate-skipped-test` all use a
      `create-task!` that always returns a non-nil id, and the only
      throwing-`create-task!` test (`all-collaborators-throw-never-throws-
      test`) makes *every* collaborator throw so the analysis short-circuits
      at `session-info` to `:no-op` — it never reaches the success branch
      with a selectively-failing `create-task!`. A regression that (e.g.)
      let a `create-task!` exception escape the `keep`, or that recorded a
      `nil` id into `:created-task-ids`, would pass every current test. Add
      a `friction-analysis`/`run-analysis` test: two detected issues where
      `create-task!` returns a real id for one and `nil` (and, separately,
      throws) for the other → `:status :success`, `:created-task-ids`
      contains only the successful id (no `nil`, no throw). This is the same
      "collaborator degrades mid-success-path, analysis still completes"
      contract the empty-`list-tasks` and dedup-diagnostic cases embody,
      applied to the create step.
- [x] The **`list-tasks` throwing/nil-return degradation on the success
      branch** is untested. `friction/run-analysis` wraps the dedup-list
      fetch in `(try (list-tasks worktree-root) (catch Throwable _ nil))`
      and then destructures `{:keys [open recent-closed]}` from the result
      — so a `list-tasks` that **throws** or **returns `nil`** yields
      `open`/`recent-closed` = `nil`, and analysis proceeds to
      `build-friction-prompt` with empty dedup lists (detection still runs,
      just without dedup context) rather than aborting. No
      `friction-analysis` test drives a throwing or `nil`-returning
      `list-tasks` on the success path: `collaborators`'s default
      `list-tasks` always returns `{:open [] :recent-closed []}`, and the
      all-throwing test short-circuits at `session-info` before
      `list-tasks` is reached. A regression that let a `list-tasks`
      exception escape the try, or that aborted when the dedup list was
      unavailable, would pass every current test — silently converting a
      degraded-dedup case into a lost-analysis case. Add a
      `friction-analysis`/`run-analysis` test asserting a throwing (and,
      separately, `nil`-returning) `list-tasks` still reaches `run-helper`
      and creates the detected task (`:status :success`, task created),
      pinning the "dedup unavailable → detect without dedup, don't abort"
      contract.

## Follow-up (implementation review, round 14)

- [x] `friction/run-analysis`
      (`extensions/context-manager/src/extensions/context_manager/friction.clj`)
      does **not short-circuit when the history excerpt is empty/blank**: it
      calls `fetch-history`, and whether that returns `nil`, `""`, or a
      blank string, `build-friction-prompt` simply renders the excerpt slot
      as `"(none)"` and the orchestration proceeds straight to the bounded
      `run-helper` local-model session anyway (there is no `str/blank?`
      guard on `history-excerpt` between fetch and `run-helper`, unlike the
      `:worktree-root` blank-guard immediately above it). Confirmed against
      the code path: `default-fetch-history` builds its tail from the
      EQL-queried message history and renders via `render-history-excerpt`,
      which returns an **empty string** for an empty/absent `:tail`
      (`extensions/context_manager.clj` — no lines → `str/join` of nothing);
      that empty string flows through `fetch-history` → `build-friction-
      prompt`'s `(or history-excerpt "(none)")` → the helper prompt. So for
      any turn whose analyzable recent history is empty — e.g. a freshly
      created session's first `session_turn_finished`, or any session where
      the history query yields nothing usable — the analyzer still spins up
      a bounded (120s wall-clock budget) no-tools local-model child helper
      session to reason over a `(none)` excerpt every turn. This is wasted
      helper-session work (a real local-model run per empty-history turn)
      and a spurious-task risk (a small model asked to find friction in an
      empty conversation may hallucinate an ISSUE block, which — if its slug
      passes the kebab-case check and it isn't a dedup match — becomes an
      auto-created task). An empty/blank history is not a *failure* path
      (design.md AC4 covers helper failure / missing model / missing
      worktree, all of which already no-op here) but it is the same class of
      "nothing meaningful to analyze → don't run the model" case those
      guards embody. Add a `str/blank?`/empty-`:tail` short-circuit on the
      fetched history excerpt in `run-analysis` (mirroring the existing
      `(str/blank? (:worktree-root info))` no-op branch) that returns
      `{:status :no-op :diagnostic "no history"}` (with a diagnostic log)
      before `run-helper` is called, and add a `run-analysis`/
      `friction-analysis` test asserting an empty/blank `:fetch-history`
      result yields a no-op with no `run-helper` call and no `create-task!`
      call (mirroring the existing no-worktree/no-model no-op tests).

## Follow-up (implementation review, round 11)

- [x] The **uncommitted working-tree change** that begins the round-10
      file-length fix is broken: `history-line`, `tail-lines-within`, and
      `slash-command-only?` were *moved out of*
      `extensions/context-manager/src/extensions/context_manager.clj` into
      `extensions/context-manager/src/extensions/context_manager/friction.clj`
      (as public `defn`s), but the three remaining call sites in
      `context_manager.clj` were **not** re-pointed at the `friction/` alias:
      `render-history-excerpt` still calls unqualified `history-line`
      (line ~157) and `tail-lines-within` (line ~163), and
      `entity-resolution-augmentation` still calls unqualified
      `slash-command-only?` (line ~692). `clj-kondo --lint
      extensions/context-manager/src/extensions/context_manager.clj` reports
      exactly three `error: Unresolved symbol` (`history-line`,
      `tail-lines-within`, `slash-command-only?`) — the namespace will not
      compile / load as-is, so this in-progress fix breaks the extension.
      Complete the move by qualifying those three call sites (e.g.
      `friction/history-line`, `friction/tail-lines-within`,
      `friction/slash-command-only?`), re-lint, load the ns under
      `clojure -M:test --focus extensions.context-manager`, and re-run
      `bb commit-check:file-lengths` to confirm both the compile fix and that
      the file is now under the 800-line ratchet (the move drops it to ~786
      lines, resolving the round-10 item below in the same edit). Do not
      commit the partial/broken working-tree state.

## Follow-up (implementation review, round 10)

- [x] `extensions/context-manager/src/extensions/context_manager.clj` is now
      **828 lines**, over the default 800-line `file-length-legacy-max-lines`
      ratchet (`bb.edn`) — confirmed by `bb commit-check:file-lengths`, which
      reports it as the only violation:
      `context_manager.clj (828 lines, 800 limit)`. The round-9 follow-up
      (`"auto-session-name"` exclusion + `:is-error`/`[error]`-marker
      threading through `default-fetch-history`/`history-line`) added code
      without keeping the file under the ratchet, even though every prior
      round (see implementation.md's slice-2/4/5 and round-3 notes) treated
      staying under 800 as an explicit constraint and repeatedly moved code
      into `friction.clj` or trimmed docstrings to hold it. There is no
      legacy-max-lines exception entry for this file (only
      `auto_session_name_test.clj`, `dev_http_test.clj`,
      `mcp_tasks_run_test.clj`, `mcp_tasks_run.clj` have entries), so this
      will fail the `commit-check:file-lengths` gate. Bring the file back
      under 800 by the same technique used before — move the round-9
      additions' mechanical/pure helpers (e.g. the `:is-error` snippet-
      shaping / `[error]` prefix logic) into `extensions.context-manager.
      friction` where the rest of task 239's pure/support code already
      lives, or otherwise relocate/trim — rather than adding a ratchet
      exception for this file.

## Follow-up (implementation review, round 9)

- [x] `known-helper-session-names` (`extensions/context_manager.clj`) is
      `#{"entity-resolution" "friction-analysis"}` and `known-helper-session?`
      additionally excludes `friction/workflow-step-session?` names, but it
      does NOT exclude the `auto-session-name` extension's helper child
      sessions, which are created with `:session-name "auto-session-name"`
      (`extensions/auto-session-name/src/extensions/auto_session_name.clj:254`,
      `create-helper-child-session`) and run a real agent loop
      (`run-agent-loop-in-session`, `run-helper-attempt` at
      `:266`/`:278`) — so they complete a turn and fire
      `session_turn_finished` (auto-session-name subscribes to it, `:338`),
      triggering the friction analyzer on a helper/infra session that just
      auto-renamed another session. This is exactly the "other known
      helper/infra sessions … other workflow helper sessions" case
      design.md's Scope-of-sessions decision and AC5 require to be excluded
      as non-representative analysis inputs — the same class of gap the
      round-3 follow-up closed for `"workflow <step-id> attempt"` sessions,
      but for a different fixed helper name that was missed. Unlike the
      entity-resolution helper, the auto-session-name helper's session-id is
      NOT tracked in either `friction-helper-session-ids` or
      `entity-resolution-helper-session-ids` (those atoms only hold *this*
      analyzer's and task-238's own children), so the only backstop that
      could catch it is the session-name set — which omits it. Add
      `"auto-session-name"` to `known-helper-session-names` (and, more
      robustly, audit the other extensions that create helper child
      sessions — `logprobs`/`metrics` do not, but future ones might — for a
      shared source-of-truth of known helper-session names rather than a
      literal set that drifts). Add a test using a realistic
      `"auto-session-name"` session-name asserting the analyzer no-ops
      (mirroring `other-known-workflow-step-session-excluded-test`).
- [x] `friction/message-snippet`
      (`extensions/context-manager/src/extensions/context_manager/friction.clj`)
      renders a `"toolResult"` message's `:content` text but silently drops
      its `:is-error` flag. Persisted tool-result messages carry
      `:role "toolResult"` + `:is-error true` on failure (confirmed:
      `components/agent-session/src/psi/agent_session/dispatch_effects.clj:245`,
      `prompt_request.clj:37`, `turn.clj:270`, `session_close.clj:47`;
      `compaction.clj:511` confirms `:role "toolResult"` is the persisted
      shape), and design.md's Issue-definition names "tool errors/retries"
      as a *primary* friction target. The round-7 follow-up surfaced
      assistant-message `{:type :error}` content blocks, but tool-*result*
      failures are a distinct message shape (a whole different role +
      out-of-band `:is-error` flag) that round-7 did not cover: while the
      failing tool-result's *text* is included in the excerpt (its
      `{:type :text}` block is), nothing in the rendered `Role: text` line
      marks it as an error, so the helper can't distinguish a successful
      tool result from a failed one. Consider having `message-snippet` (or
      the `default-fetch-history` `{:role :snippet}` shaping) surface the
      `:is-error true` status — e.g. prefix the snippet with an error marker
      when `(:is-error message)`, or fold it into the rendered role label —
      so the friction detector sees the tool-error signal it's meant to key
      on, and add a `message-snippet`/`default-fetch-history` test over a
      realistic `{:role "toolResult" :is-error true :content [...]}` message.

## Follow-up (implementation review, round 7)

- [x] `friction/group-into-turns` (`extensions/context-manager/src/extensions/context_manager/friction.clj`)
      splits turns via `(= :user (:role message))` — a keyword comparison —
      but real agent-core messages persist `:role` as the *string*
      `"user"`/`"assistant"`, not a keyword: confirmed at
      `components/ai/src/psi/ai/providers/anthropic.clj:631`,
      `components/ai/src/psi/ai/providers/openai/chat_completions.clj:114`
      and `:491`, `components/agent-session/src/psi/agent_session/turn/handlers.clj:24`,
      `components/agent-session/src/psi/agent_session/dispatch_effects.clj:93`,
      and `components/agent-session/src/psi/agent_session/turn.clj:92` (all
      construct `{:role "user"/"assistant" ...}`), and by the existing
      237/238 history-projection test fixture's own comment "real 237
      projection shape" using `:role "user"` string values
      (`context_manager_test.clj:361-365`). No normalization step converts
      role to a keyword before `default-fetch-history`'s EQL query result
      reaches `group-into-turns`. Reproduced directly: 40 real-shaped
      messages (20 turns, `:role "user"`/`"assistant"` strings) group into
      **1** turn via `group-into-turns`, versus 20 groups for the same data
      shaped with keyword roles. In production this means every message
      after the first is merged into one giant "turn", so
      `last-n-turns`/`friction-history-turn-count` (4) bounds nothing beyond
      `bounded-message-tail`'s 200-raw-message cap — contradicting AC1's
      "last 4 turns" intent and silently undoing the round-3 follow-up's
      stated fix. The friction-collaborators test suite doesn't catch this
      because its message fixtures use keyword `:role :user`/`:assistant`
      (invented shape), not the real string-role shape. Fix the boundary
      check to match the real value (e.g. `(= "user" (name (:role
      message)))`), and update `group-into-turns`/`last-n-turns`/
      `default-fetch-history`'s test fixtures to use string roles so the
      test suite actually exercises the production shape.
- [x] `friction/message-snippet` only extracts `{:type :text :text ..}`
      content blocks, silently dropping `{:type :error ...}` blocks — the
      exact representation used throughout the codebase for provider/tool
      failures and timeouts (`components/ai/src/psi/ai/providers/anthropic/error.clj:132`,
      `components/ai/src/psi/ai/providers/openai/transport.clj:168`,
      `components/turn-runtime/src/psi/turn_runtime/core.clj:172,249,291`,
      `components/agent-session/src/psi/agent_session/turn.clj:93`,
      `components/agent-session/src/psi/agent_session/turn/handlers.clj:126`,
      `components/agent-session/src/psi/agent_session/dispatch_effects.clj:94`,
      `components/workflow_runtime/src/psi/workflow_runtime/turn_execution_contract.clj:160`).
      Since design.md's own "Issue definition" names "tool errors/retries"
      as a primary friction example, the analyzer's history excerpt
      currently hides exactly the signal it's meant to detect. Extend
      `message-snippet` to also surface `:type :error` block text (its
      `:text` key), or delegate to the more complete
      `psi.agent-session.message-text/content-error-parts` /
      `content-display-text` (already used by the pre-turn 237 projection
      this analyzer's history rendering is otherwise modelled on) —
      weighing that against the deliberate classpath-isolation choice noted
      in slice 4's implementation.md entry.

## Follow-up (implementation review, round 6)

- [x] `friction-analysis`'s per-session in-flight guard
      (`extensions/context_manager.clj`) has a check-then-act race: it does
      a plain `(contains? @friction-in-flight-session-ids session-id)` read
      followed by a separate `(swap! friction-in-flight-session-ids conj
      session-id)`, with no atomicity between the two. Two genuinely
      concurrent `friction-analysis` calls for the *same* `session-id`
      (e.g. two turns finishing close enough together that both threads
      reach the `contains?` check before either has run its `swap!`) can
      both observe `false` and both proceed to run analysis in parallel —
      reproduced directly with a minimal harness mirroring this exact
      check-then-swap shape (20 concurrent callers against a fresh atom,
      each with a small delay between the read and the `swap!`: all 20
      report `:claimed`, none `:already-in-flight`). This defeats the very
      race the round-4 in-flight guard was added to prevent (two concurrent
      runs on the same session both passing their own dedup snapshot and
      creating duplicate tasks for the same issue) — it only narrows the
      window rather than closing it, and the existing
      `concurrent-run-same-session-guarded-test` doesn't catch this because
      it serializes the two calls (waits for the first to reach its
      blocking `:run-helper` deliver before invoking the second), so the
      first call's `swap!` has already completed by the time the second's
      `contains?` check runs. Replace the check-then-swap with a single
      atomic claim, e.g. a `compare-and-set!`/`swap!`-returns-prior-state
      loop that reports whether `session-id` was already present in the
      *same* atomic operation that adds it, and add a test that starts two
      calls truly concurrently (no ordering handshake) to exercise the
      narrow-window case.

## Follow-up (implementation review, round 5)

- [x] `doc/extensions.md`'s context-manager friction-analyzer section
      (updated through the round-3 follow-up — see commit `b24ea45df`,
      which added the "workflow-runtime step-attempt child sessions"
      exclusion wording) was not updated for the round-4 follow-up (commit
      `e9be80a20`), which added the per-session in-flight guard
      (`friction-in-flight-session-ids`): a second `session_turn_finished`
      analysis for the same session is now skipped/no-op'd while a prior
      run for that session is still in flight. This is user-observable
      behaviour (fewer/no duplicate tasks from closely-spaced turns on the
      same session) not described anywhere in `doc/extensions.md`. Add a
      short bullet describing the in-flight-run guard, alongside the
      existing scope/cap/dedup bullets.## Follow-up (task-test-review skill)

- [x] `default-friction-run-helper` (`extensions/context_manager.clj`)
      requests a no-tools child session (`:tool-ids []`, `:tool-names []`,
      plan.md decision 9 — "the friction helper only reasons over the
      prompt-embedded excerpt + task list, no bash needed") but no test
      asserts this reaches the real `create-child-session` call. The
      equivalent entity-resolution helper has exactly this coverage
      (`context_manager_helper_runtime_test.clj`'s "create-child-session
      gets prompt-component-selection and no :worktree-path" test, which
      asserts `(= ["bash"] (:tool-ids params))` via `fake-run-api`'s
      `:create-calls` capture); no friction test file
      (`context_manager_friction_helper_runtime_test.clj` or elsewhere)
      makes the analogous assertion for the friction helper. A future
      refactor of `bounded-helper-session-run` that drops or defaults the
      `:tool-ids`/`:tool-names` pass-through could silently grant the
      friction helper bash/tool access with no test catching it. Add a
      direct test (mirroring the entity-resolution one, via `fake-run-api`'s
      `:create-calls`) asserting `default-friction-run-helper` passes
      `:tool-ids []`/`:tool-names []` to `create-child-session`.
- [ ] `create-friction-task-test`'s "retry exhaustion → nil, no task
      created" `testing` block
      (`context_manager_friction_task_files_test.clj`) doesn't test
      retry exhaustion at all: its body asserts `(is (some?
      (context-manager/create-friction-task! root issue 5)))` — i.e. that
      a task *is* created, the opposite of the label. The in-code comment
      correctly explains the actual exhaustion path can't be driven via
      `create-friction-task!` (only via `next-free-task-id` directly, which
      `next-free-task-id-test` already covers) — but the misleading
      `testing` string/assertion pair should be renamed to reflect what it
      actually verifies (e.g. "max-retries param threads through to a
      successful call on the happy path" or similar), so a reader doesn't
      believe exhaustion is covered here when it isn't.

## Follow-up (task-test-review skill, round 2)

- [x] `default-friction-run-helper`
      (`extensions/context_manager.clj`) is a thin wrapper over the shared
      `bounded-helper-session-run`, but its friction-specific configuration
      on the **happy path** is untested. The entity-resolution wrapper has
      `default-run-helper-settled-run-closes-and-untracks-test` and
      `default-run-helper-forwards-selected-model-test`
      (`context_manager_helper_runtime_test.clj`) exercising the
      normal-completion path (successful run → returns text, child
      closed + untracked, selected model threaded to
      `run-agent-loop-in-session`). The friction wrapper's only helper-
      runtime test (`context_manager_friction_helper_runtime_test.clj`) is
      `default-friction-run-helper-timeout-branch-test` — the *timeout*
      branch only; no test asserts the friction wrapper on a settled
      successful run (a) returns the run text, (b) passes
      `:session-name "friction-analysis"` to `create-child-session` (via
      `fake-run-api`'s `:create-calls` capture) — distinct from the
      already-flagged `:tool-ids []` gap — and (c) closes + untracks the
      child in `friction-helper-session-ids` on normal completion (the
      timeout test only proves the orphan/timeout untrack path). Because
      the two wrappers only differ by session-name / tool-grant / tracking
      atom, a future edit that mis-wires the friction wrapper's session-name
      or normal-completion teardown would pass every existing friction
      helper-runtime test. Add a settled-run test for
      `default-friction-run-helper` (mirroring
      `default-run-helper-settled-run-closes-and-untracks-test`) asserting
      returned text, `:session-name "friction-analysis"` in `:create-calls`,
      selected-model threading, and child close+untrack on success.
- [x] The **auto-generated marker naming the analyzer** (design.md AC2:
      the generated `design.md` must contain "an auto-generated marker",
      the analyzer's own attribution) is only weakly asserted, and never
      asserted in the *written file*. `render-friction-design-md-test`
      (`context_manager_friction_parsing_test.clj`) checks only
      `(str/includes? content "Auto-generated")` — it does not assert the
      marker actually identifies *this* analyzer (the source emits
      "Auto-generated by the context-manager post-turn tooling-friction
      analyzer (task 239)…"; a regression that dropped "context-manager"/
      "task 239"/"analyzer" from the marker but kept the word
      "Auto-generated" would still pass), nor that `:title` becomes the H1
      heading. Separately, the integration test `create-friction-task-test`
      (`context_manager_friction_task_files_test.clj`) asserts only that the
      **title** substring reached the written `design.md`
      (`(str/includes? … "Missing linter")`) — it does not assert the
      auto-generated marker, friction, evidence, or suggested-change
      sections actually made it into the created file, so AC2's "containing
      friction description, evidence, suggested change, and an
      auto-generated marker" is only proven for the isolated render fn, not
      for the end-to-end created task. Strengthen `render-friction-design-md-
      test` to assert the marker identifies the context-manager/task-239
      analyzer and that the title is rendered as the heading, and add an
      assertion to `create-friction-task-test` that the written `design.md`
      contains the auto-generated marker plus the friction/evidence/
      suggestion content (not just the title).

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

## Follow-up (implementation review, round 4)

- [x] `default-fetch-history` (`extensions/context_manager.clj`) queries the
      session's *entire* unbounded `:psi.agent-session/message-history` via
      EQL (`agent-core-messages` returns the full in-memory `:messages`
      vector, unbounded) and then runs `friction/group-into-turns` +
      `friction/last-n-turns` — both O(total-messages) — over the whole
      thing every single completed turn, merely to keep the last 4 turns.
      This is unlike the existing bounded pre-turn `:turn-augmentation/
      history` projection the entity-resolution augmenter uses
      (`build-augmentation-history-projection`,
      `components/agent-session/src/psi/agent_session/dispatch_effects.clj`,
      already `take-last 8` at the source). For a long-running/autonomous
      session (many turns, e.g. a multi-hour workflow session), this
      re-scans the growing full history on every turn — O(n) work per turn,
      O(n²) cumulative over the session's lifetime — where only a small
      constant-bounded tail is ever used. Bound the EQL query or the
      grouping/slicing to a small tail (mirroring the existing `take-last 8`
      pattern) instead of scanning the full message vector each turn.
- [x] `friction-analysis` has no per-session serialization/coalescing guard:
      if two turns of the *same* session complete close enough together
      that a still-in-flight `future` (bounded by the 120s helper wall-clock
      budget) hasn't finished when the next turn's analysis starts, both
      runs independently snapshot `:list-tasks` (open/recent-closed) before
      either has created a task, so both can independently detect the same
      issue and both create a task for it — a duplicate neither run's own
      dedup pass can see, since dedup only checks against tasks that existed
      *before* that run started. AC3 requires duplicates be skipped; this
      race is only guarded against for id-collision (the `next-free-task-id`
      retry loop), not for two concurrent runs on the same session both
      passing their own dedup check for the same underlying issue. Consider
      a per-session in-flight guard (e.g. skip/coalesce a new run for
      `session-id` while a previous run for that same `session-id` is still
      in flight).

## Follow-up (test-shaper skill, round 2)

- [x] The **per-run-cap dropped-issues diagnostic log** is unasserted.
      `friction/run-analysis`
      (`extensions/context-manager/src/extensions/context_manager/friction.clj`)
      does `(when (seq dropped) (log (str "context-manager: friction-analysis: "
      (count dropped) " issue(s) dropped by per-run cap")))` — a distinct
      observable behavior (plan.md decision 8: "log a diagnostic for the
      remainder") separate from the `:dropped-count` return value.
      `cap-applied-test`
      (`context_manager_friction_analysis_test.clj`) asserts only the return
      shape (`(= 1 (:dropped-count result))` + 2 created ids) and passes a
      `{}` api with **no `:log`** collaborator, so the dropped-cap log line
      is never exercised — a regression that dropped, mis-worded, or
      inverted the `(when (seq dropped) …)` guard would pass every current
      test. This is the exact same "diagnostic log fired for a suppressed
      case" contract `duplicate-skipped-test` already pins for the dedup
      path (`(is (some #(re-find #"duplicate" %) @logged))`), applied to the
      cap path. Add to `cap-applied-test` (or a sibling) a `{:log #(swap!
      logged conj %)}` api and assert a log line matches
      `#"dropped by per-run cap"` when issues exceed the cap, and — for the
      complementary negative — assert **no** such line fires when
      `issues ≤ cap` (mirroring `cap-issues-test`'s `:dropped []` cases at
      the orchestration level).
- [x] No `friction-analysis`/`run-analysis` test drives a **mixed
      issue+duplicate helper output** (some new ISSUE blocks → task created,
      some DUPLICATE lines → diagnostic) in a single run — the realistic
      model-output shape. `issue-creates-task-test` uses issue-only output;
      `duplicate-skipped-test` uses duplicate-only output
      (`"DUPLICATE: slow-tests ~ 001-slow-tests\n"`). `run-analysis` emits
      **both** `:created-task-ids` (from `selected`) and
      `:duplicate-diagnostics`/dedup log lines (from `duplicates`) in the
      same pass, but a regression where the presence of a created issue
      suppressed duplicate-diagnostic emission (or where a duplicate line
      suppressed task creation) would pass every current orchestration test,
      since no test asserts the two coexist. `parse-friction-output-mixed-
      test` (`context_manager_friction_parsing_test.clj`) pins this coexistence
      only at the *pure parse* layer, not end-to-end through `run-analysis`.
      Add a `friction-analysis` test driving a `run-helper` output containing
      one well-formed ISSUE block plus one `DUPLICATE:` line, asserting
      `:status :success`, `:created-task-ids` = the one created id,
      `:duplicate-diagnostics` = the one duplicate entry, and a dedup log
      line fired — the orchestration-level analog of
      `parse-friction-output-mixed-test`.
