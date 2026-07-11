## Follow-up (review-task-docs skill)

- [ ] `README.md`'s **context-manager extension bullet is stale/incomplete**:
      it now describes only the *pre-turn* augmenters — "registers pre-turn
      turn augmenters: `project-context` and automatic `entity-resolution`
      (a bash-only local-model helper that injects a `Resolved entities`
      block)" (README.md ~line 182) — and does **not** mention the
      **post-turn tooling-friction analyzer** this task (239) added. That new
      behaviour is user-visible and is already documented in both
      `doc/extensions.md` (the detailed "Post-turn tooling-friction analyzer
      (task 239)" section) and `CHANGELOG.md` `[Unreleased] → Added`, so the
      README — the primary top-level user documentation, which the skill's
      checklist item 1 requires reflect all new/changed behaviours — is the
      one user-facing doc surface left out of sync. A reader scanning the
      README's extension list would not learn context-manager now
      auto-opens Munera friction tasks after every turn. Extend the
      context-manager bullet with a short clause noting the post-turn
      friction analyzer (fire-and-forget; auto-creates capped, deduped
      `munera/open/NNN-slug/design.md` tooling/dependency-friction tasks in
      the analyzed session's worktree; excludes known helper/infra
      sessions), keeping it a summary and pointing to
      `doc/extensions.md` for detail — matching how the `entity-resolution`
      clause already summarizes-and-links. (Accuracy of the existing
      `doc/extensions.md` friction section and the CHANGELOG entry was
      verified against the implementation — cap=2, 20 most-recently-closed
      dedup, and the `entity-resolution`/`friction-analysis`/
      `auto-session-name`/`"workflow <step-id> attempt"` exclusion names all
      match `context_manager.clj` — so no correction is needed there; only
      the README omission.)

      race is only guarded against for id-collision (the `next-free-task-id`
      retry loop), not for two concurrent runs on the same session both
      passing their own dedup check for the same underlying issue. Consider
      a per-session in-flight guard (e.g. skip/coalesce a new run for
      `session-id` while a previous run for that same `session-id` is still
      in flight).

## Follow-up (task-test-review skill, round 3)

- [x] The **default-collaborator resolution in `friction-analysis*`**
      (`extensions/context_manager.clj`) — the `or`-bindings that connect the
      orchestration core (`friction/run-analysis`) to the *real*
      disk-touching collaborators (`:select-model` →
      `default-select-model`, `:run-helper` → `default-friction-run-helper`,
      `:fetch-history` → `default-fetch-history`, `:session-info` →
      `default-session-info`, `:list-tasks` → `{:open (open-tasks root)
      :recent-closed (recent-closed-tasks root)}`, `:create-task!` →
      `create-friction-task!`, `:task-cap` → `friction-task-cap`) — is
      **never exercised through `friction-analysis`**. Every
      `friction-analysis` test in
      `context_manager_friction_analysis_test.clj` (including
      `issue-creates-task-test`, `cap-applied-test`,
      `create-task-partial-failure-still-completes-test`) injects its own
      `:create-task!`/`:list-tasks`/`:fetch-history`/`:session-info` via the
      `collaborators` helper, so `run-analysis` is well-covered but the wiring
      map that binds those slots to the real fns is not. The real
      collaborators are each unit-tested in isolation
      (`create-friction-task!`, `open-tasks`, `recent-closed-tasks` in
      `context_manager_friction_task_files_test.clj`;
      `default-fetch-history`/`default-session-info` in
      `context_manager_friction_collaborators_test.clj`), but nothing pins
      that `friction-analysis*` binds each real fn into the *correct* slot.
      A regression that (a) swapped the `:list-tasks`/`:create-task!`
      bindings, (b) mis-referenced `create-friction-task!` (e.g. dropped a
      currying arg so `run-analysis`'s `(create-task! worktree-root issue)`
      arity mismatched), (c) bound `:fetch-history` to `default-session-info`
      (or vice versa), or (d) forgot `:task-cap friction-task-cap` — would
      pass **every** current test, since the injected collaborators mask the
      default bindings entirely. This is the friction-path analog of
      `context_manager_entity_resolution_registration_test.clj`, which
      deliberately drives the *real* `default-select-model`/`default-run-helper`
      through the threaded api ("real default-select-model ran through the
      threaded api → no-op") for the entity-resolution augmenter. Add a
      `friction-analysis`-level test that calls it with **no** `collaborators`
      (`nil`), backing the api with a nullable/fake `:query-session` that
      returns a real worktree-path + non-helper session-name and a small
      real message history, and either (i) an injected-model-only override so
      it reaches the real `default-fetch-history` → real `open-tasks`/
      `recent-closed-tasks` → real `create-friction-task!` against a temp
      worktree, asserting an actual `munera/open/NNN-slug/design.md` is
      written on disk from a well-formed helper output; or, if a full real
      run is impractical, at minimum a test that pins the default binding map
      (e.g. drives the real `default-select-model → nil` no-op path the way
      the entity-resolution registration test does) so the wiring — not just
      the orchestration — is proven. Without it, the seam between
      `run-analysis` and the real filesystem/EQL collaborators is untested.
- [x] The **`session_turn_finished` future body's outer catch-all** in
      `init` (`extensions/context_manager.clj`) — the `(future (try
      (friction-analysis api payload) (catch Throwable e … "uncaught error:"
      …)))` belt-and-braces guard — is **unexercised**. The wiring test
      (`context_manager_friction_wiring_test.clj`,
      `turn-finished-handler-does-not-block-on-slow-friction-analysis-test`)
      drives the handler only through the *no-worktree no-op* path (the
      nullable `:query-session` default yields no worktree), asserting the
      handler returns promptly and the async run logs *some* `friction-
      analysis:` diagnostic — but never the **success** path (a real detected
      issue flowing through the future to a created task) nor the
      **uncaught-error** path the outer catch exists to swallow. The comment
      itself concedes `friction-analysis` "never throws (belt-and-braces
      outer catch)", making the catch arm effectively dead — but "dead" is an
      assertion no test pins: a future refactor that let `friction-analysis`
      throw (e.g. a change moving the in-flight `swap-vals!`/`finally` such
      that a throw escapes) would silently rely on this untested arm, and a
      regression that dropped the outer `try` (or its logging) would surface
      an uncaught exception on the future's thread with no test catching it.
      Add a wiring-level test that (a) drives the handler's future through a
      **successful** friction-analysis (real or injected collaborators
      yielding a created-task diagnostic, asserting the success log line
      appears via the existing `await-log-line` poll), and (b) — to pin the
      catch-all — injects a `friction-analysis` seam that throws (or an api
      whose `:log`/collaborator throws in a way that escapes the inner
      guards) and asserts the handler still returns nil promptly and the
      `"uncaught error:"` diagnostic is logged, proving the future's last
      line of defence actually fires rather than crashing the thread.

## Follow-up (test-shaper skill, round 10)

- [x] `friction/message-snippet`'s **content-block `:type` filter is
      keyword-only and its test fixtures mask a keyword-vs-string boundary
      bug** — the same class of gap round-7 caught for `:role`, one layer
      down at the content-block `:type`. `message-snippet`
      (`extensions/context-manager/src/extensions/context_manager/friction.clj`)
      selects renderable blocks with `(filter #(contains? #{:text :error}
      (:type %)))` — a **keyword-only** membership test — so a content block
      whose `:type` is the *string* `"text"`/`"error"` is silently dropped
      and contributes no snippet text. But persisted agent-core message
      content carries `:type` as **either** a keyword or a string: the
      canonical codebase helper `psi.agent-session.message-text/
      content-text-parts` (the fn `content-display-text` /
      `build-augmentation-history-projection` use — the *same* pre-turn
      history projection the friction analyzer's rendering is otherwise
      modelled on, `components/agent-session/src/psi/agent_session/
      dispatch_effects.clj:387`) deliberately normalizes both via its
      `->kw` (`(keyword? x) x / (string? x) (keyword x)`), and provider-raw
      content emits string types (`components/ai/src/psi/ai/providers/
      anthropic.clj:616`, `(= "text" (:type block))`). So the string-`:type`
      shape is a real, first-class persisted shape the analyzer will
      encounter — and on it `message-snippet` yields an **empty** snippet,
      dropping the message entirely from the excerpt (a text-less line is
      then dropped by `history-line`). For an `:type "error"` tool failure,
      this silently loses exactly the tool-error signal round-7/round-9
      threaded the `[error]` marker to surface (design.md names 'tool
      errors/retries' as a *primary* friction target). Verified directly
      against the current code (nREPL): `(message-snippet {:role "a"
      :content [{:type :text :text "hi"}]})` → `"hi"`, but `(message-snippet
      {:role "a" :content [{:type "text" :text "hi"}]})` → `""` and
      `(… {:type "error" :text "boom"})` → `""`; the canonical
      `content-text-parts` returns `"hi"` for **both** the keyword and
      string shapes, confirming the codebase treats both as valid and only
      `message-snippet` diverges. The test suite doesn't catch this because
      **every** `message-snippet`/`default-fetch-history` fixture
      (`context_manager_friction_collaborators_test.clj`) uses keyword
      `:type :text`/`:type :error` blocks — the invented shape, exactly as
      round-7's keyword `:role` fixtures masked the `group-into-turns`
      boundary bug — so no test drives the string-`:type` shape through
      `message-snippet` and a regression either way passes silently. This is
      likely an **implementation** fix (normalize `:type` to a keyword
      before the membership test — e.g. `(contains? #{:text :error}
      (some-> (:type %) name keyword))` — or delegate to `content-text-parts`/
      `content-error-parts`, weighed against slice-4's deliberate
      classpath-isolation choice noted in implementation.md), not just a
      test: decide the intended behaviour (surface string-`:type` blocks —
      the canonical-helper-consistent reading), fix it, then add a
      `message-snippet` test (and, ideally, a `default-fetch-history` test)
      driving realistic **string**-`:type` `"text"` and `"error"` content
      blocks and asserting their text (and, for the error case, its
      round-9 `[error]` marker through `history-line`) reaches the excerpt —
      pinning the keyword-vs-string content-block boundary the current
      keyword-only fixtures leave open.

## Follow-up (test-shaper skill, round 9)

- [x] `friction/history-line`'s **`:is-error`-flagged-but-blank/dropped
      arm** is untested: no test pins that an error-flagged entry whose
      snippet is blank/absent (or a slash-command) is dropped **whole** —
      losing the `[error]` tool-error signal round-9 added it to carry.
      `history-line`
      (`extensions/context-manager/src/extensions/context_manager/friction.clj`)
      gates the entire rendered line — `[error]` prefix included — on
      `(when (and role text (not (slash-command-only? text))) …)`, where
      `text` is `(some-> (:snippet entry) str str/trim not-empty)`. So the
      `[error]` marker is emitted **only** when the entry also has a
      non-blank, non-slash snippet. Verified directly against the current
      code (nREPL): `(history-line {:role "toolResult" :is-error true
      :snippet ""})`, `… :snippet nil`, and `… :snippet "/help"` all return
      `nil` (the whole entry, error marker and all, is dropped), while `…
      :snippet "boom"` returns `"[error] Toolresult: boom"`. This is
      load-bearing: `message-snippet` renders **only** `{:type :text}` /
      `{:type :error}` content blocks, so a persisted failed tool result
      (`:role "toolResult" :is-error true`) whose content is non-textual
      (structured/tool blocks only, or an empty result) yields a blank
      snippet → the `[error]` line is dropped → the friction excerpt carries
      **no** tool-error signal for that failure, exactly the signal round-9
      added the marker to surface (design.md names 'tool errors/retries' as
      a *primary* friction target). The current coverage
      (`default-fetch-history-test`,
      `context_manager_friction_collaborators_test.clj`) pins only the two
      *text-present* poles — a failed toolResult **with** text →
      `[error] Toolresult: …`, and a successful toolResult **with** text →
      no marker — plus `message-snippet-test`'s "no :content → empty string";
      nothing joins the two facts (blank snippet **and** `:is-error true`)
      to pin what the analyzer does when the error signal has no text to
      hang on. And `history-line` itself has **no** direct unit test at all
      — it is exercised only transitively (entity-resolution prompt +
      friction fetch-history), neither of which drives an error-flagged
      blank/slash entry. A regression here could silently flip in either
      direction and pass every current test: (a) dropping the `:is-error`
      prefix logic entirely still passes the text-present poles if the text
      still renders; (b) conversely, a "surface all errors" change that made
      a blank error entry render as a bare `"[error] Toolresult: "`
      (role-only, no text) would inject a content-free line into the excerpt
      that no test forbids. Add a direct `friction/history-line` test
      pinning the boundary: `:is-error true` with a blank/`nil`/whitespace
      snippet → `nil` (dropped), with a slash-command snippet → `nil`, and
      (as the positive pole, mirroring the existing collaborator test at the
      unit layer) with real text → the `[error]`-prefixed line — so the
      "error marker rides on a real snippet, and a text-less failure
      contributes nothing" contract is enforced at the fn that owns it. If
      the *intended* behaviour is instead that a text-less tool failure
      should still surface an error signal (a plausible reading of the
      round-9 intent), that is an implementation change, not just a test —
      flag it, decide, then pin the chosen behaviour.
- [x] `render-history-excerpt`'s **`turn-count`-truncation arm is dead code**
      and, being unreachable, is untested — worth removing rather than
      test-covering (economy: don't pin an unreachable branch). The 3-arity
      `render-history-excerpt`
      (`extensions/context-manager/src/extensions/context_manager.clj`)
      still contains `(if (and turn-count (pos? turn-count)) (vec (take-last
      turn-count tail)) tail)`, documented as "used by the friction analyzer
      to bound input to the last N turns". But **neither** production caller
      passes a positive `turn-count`: the entity-resolution path calls the
      1-arity default (`turn-count` nil), and `default-fetch-history` — after
      the round-3/round-7 fix that moved turn-bounding into
      `friction/last-n-turns` (which groups raw messages into *conversational*
      turns, the whole point of that fix) — calls `(render-history-excerpt
      {:tail tail} nil max-history-chars)`, i.e. `turn-count` **nil**. So the
      `take-last turn-count` branch is never exercised in production and its
      docstring rationale is stale (the friction analyzer deliberately does
      **not** use this arm — using it would re-introduce the raw-message
      undercount round-7 fixed). Leaving live-looking-but-dead branching
      invites a future edit to wire it back in and silently re-break the
      turn-vs-message contract. Remove the `turn-count` parameter/branch (and
      correct the stale docstring), collapsing `render-history-excerpt` to
      the `[history char-cap]` shape its two real callers actually use — then
      the remaining behaviour is fully covered by the existing
      entity-resolution + friction excerpt tests. (If a caller for the arm is
      genuinely anticipated, keep it but add a `render-history-excerpt` test
      driving a positive `turn-count` so the branch isn't dead-and-untested.)

## Follow-up (test-shaper skill, round 8)

- [x] `friction/task-title`'s **design.md-present-but-no-`# `-heading
      fallback branch** is untested. `task-title`
      (`extensions/context-manager/src/extensions/context_manager/friction.clj`)
      resolves a task's title through three distinct branches:
      (1) design.md exists **and** has a `# ` heading →
      `(some->> … (some #(when (str/starts-with? % "# ") …)) str/trim
      not-empty)`; (2) design.md **missing** → outer `(or … id)` returns the
      directory id; and (3) design.md **exists but has no `# ` heading** (or
      only a blank/`#`-only heading) → the inner `some->>` yields `nil` (the
      `not-empty` guard drops a `""`/whitespace heading), so the outer
      `(or … id)` falls back to the id. Only branches (1) and (2) are pinned:
      `open-tasks-test`
      (`context_manager_friction_task_files_test.clj`) has "titles from
      design.md heading" (branch 1, `"# Alpha issue"`) and "falls back to id
      as title when design.md missing" (branch 2, no design.md); no
      `open-tasks`/`recent-closed-tasks`/`task-title` test writes a design.md
      that **exists without a `# ` heading** (e.g. body-only, or a `## `
      sub-heading, or a blank-after-`# ` heading the `not-empty` guard is
      there to reject). This branch is reachable in production:
      `open-tasks`/`recent-closed-tasks` scan **every** task directory under
      `munera/open/`/`munera/closed/` — including human-authored or
      malformed ones — not just this analyzer's own always-`# `-headed
      generated tasks, so a headingless/oddly-headed design.md is a real
      dedup-list input. The title feeds straight into `render-task-list` →
      `build-friction-prompt`'s dedup list (design.md 'Dedup'); a regression
      dropping the `not-empty` guard or the outer `(or … id)` fallback would
      emit a `NNN-slug: ` line with an **empty/nil title** (or throw on a
      nil title) into the list the friction helper matches against —
      degrading dedup accuracy (AC3) — yet pass both current `open-tasks`
      cases, since neither drives a file-present-but-headingless task. Add an
      `open-tasks`/`recent-closed-tasks` case with a design.md that exists
      but has no usable `# ` heading (body-only and, ideally, a blank
      `# \n`), asserting the title falls back to the directory id (branch 3),
      distinct from the file-missing fallback branch (2) already covered.

## Follow-up (test-shaper skill, round 7)

- [x] `recent-closed-tasks`'s **git-listed-but-absent-from-disk filtering**
      is untested. `closed-ids-by-git-order`
      (`extensions/context-manager/src/extensions/context_manager/friction.clj`)
      derives closure order from `git log --diff-filter=A -- munera/closed/`,
      whose `A`-records are **append-only history**: an id committed into
      `munera/closed/` and *later deleted/renamed off disk* still appears in
      that log. `recent-closed-ids-git-filtered` guards against this with
      `(filter all-ids ids)`, where `all-ids` is the set of directory names
      *currently* present under `munera/closed/`, so a git-listed id no
      longer on disk is dropped before it reaches the dedup list. This is a
      load-bearing correctness contract: a stale git-derived id in the dedup
      list would tell the friction helper an existing task exists that
      doesn't — a phantom against which it might wrongly suppress a real new
      issue (defeating AC2/AC3). Verified directly against the current code
      (temp git repo, two closed tasks committed, then `rm -rf` one on disk):
      `recent-closed-tasks` returns only the surviving task
      (`[{:id "002-second" :title "Second"}]`), not the deleted one. But
      `recent-closed-tasks-test`'s git case
      (`context_manager_friction_task_files_test.clj`) only ever lists
      on-disk tasks that were committed and never removed, so the
      `(filter all-ids …)` step is dead as far as the tests are concerned — a
      regression dropping it (e.g. trusting git order directly) would emit a
      phantom `NNN-slug` into the dedup list yet pass every current test. Add
      a `recent-closed-tasks` test: commit two closed tasks in git, `rm -rf`
      one from disk, assert the result contains only the surviving task and
      never the deleted id — pinning the git-history-vs-disk reconciliation.
- [x] `recent-closed-tasks`'s **git-repo-but-no-`munera/closed/`-history
      fallback branch** is untested. `closed-ids-by-git-order` returns `nil`
      via its `(when (seq ids) ids)` tail whenever the `git log` succeeds
      (exit 0) but yields **no** `A`-records for `munera/closed/` — a real
      scenario: closed-task directories present on disk in a valid git repo
      whose moves into `munera/closed/` were never committed (or committed
      without the `A` diff-filter matching). `recent-closed-tasks` then falls
      back to name-descending order via
      `(or git-ids (sort #(compare %2 %1) all-ids))`. The two current cases
      (`context_manager_friction_task_files_test.clj`) are **git-with-commits**
      (git branch taken) and **non-git dir** (git *failure* branch → fallback);
      neither drives the git-*success-but-empty* path. Verified directly
      (git-init'd temp repo, two uncommitted closed dirs on disk):
      `recent-closed-tasks` returns them name-descending
      (`[{:id "002-b" ..} {:id "001-a" ..}]`), i.e. the fallback fires even
      inside a git repo. A regression collapsing `(when (seq ids) ids)` to
      `ids` (returning `[]` instead of `nil` from the empty git branch) would
      short-circuit the `or` and return an **empty** closed-task list in a
      valid repo — silently emptying the recently-closed dedup list whenever
      closed tasks aren't yet git-committed, yet pass both current tests
      (which exercise only the committed and non-git branches). Add a
      `recent-closed-tasks` test in a git-init'd repo with on-disk-but-
      uncommitted closed tasks, asserting the name-descending fallback ordering
      is returned — pinning the git-success-empty → fallback branch distinct
      from the git-failure → fallback branch the non-git test already covers.

## Follow-up (test-shaper skill, round 6)

- [x] The analyzer's **own literal helper session-name `"friction-analysis"`
      exclusion arm** is untested. `known-helper-session-names`
      (`extensions/context-manager/src/extensions/context_manager.clj`) is the
      three-element literal set `#{"entity-resolution" "friction-analysis"
      "auto-session-name"}`, and `known-helper-session?` excludes a session
      when its `:session-name` is a member (a name-based backstop distinct
      from the session-id membership in `friction-helper-session-ids`). Every
      *sibling* member is pinned by a dedicated exclusion test driving that
      exact name — `entity-resolution-helper-session-excluded-test`/
      `other-known-helper-session-excluded-test` for `"entity-resolution"`,
      `other-known-auto-session-name-session-excluded-test` for
      `"auto-session-name"`, and the `workflow-step-session?` dynamic arm by
      `other-known-workflow-step-session-excluded-test`
      (`context_manager_friction_analysis_test.clj`) — plus each is
      diagnostic-pinned in `exclusion-no-op-branch-diagnostic-pinned-test`.
      But **no test drives a session whose `:session-info` `:session-name` is
      literally `"friction-analysis"`** through `friction-analysis`; the only
      occurrence of that string in the analysis test suite asserts the
      *outgoing* `create-child-session` param
      (`default-friction-run-helper-settled-run-test`,
      `context_manager_friction_helper_runtime_test.clj`), not the incoming
      exclusion. This is the analyzer's *own* helper name and arguably the
      most important recursion-guard backstop: the `friction-helper-session-
      ids` `defonce` atom is empty after any process restart/reload
      (`context_manager_friction_wiring_test.clj`'s fixture resets it every
      test to prove this), so a lingering `friction-analysis`-named helper
      session that finishes a turn after a reload — its id no longer tracked
      — is caught *only* by this name-set arm. A regression dropping
      `"friction-analysis"` from `known-helper-session-names` (e.g. a refactor
      assuming id-tracking alone suffices) would re-enable the analyzer to run
      on its own helper sessions — the exact recursion design.md's
      Scope-of-sessions decision and AC5 forbid — yet pass every current
      exclusion test, since none drives this specific member. This is the same
      "each distinct value in the fixed set is pinned" contract
      `other-known-auto-session-name-session-excluded-test` already embodies
      for `"auto-session-name"`, absent for the analyzer's own name. Add a
      `friction-analysis` exclusion test (and, ideally, a
      `:diagnostic "known helper/infra session excluded"` assertion mirroring
      `exclusion-no-op-branch-diagnostic-pinned-test`) driving
      `:session-info` → `{:session-name "friction-analysis"}` and asserting
      `:no-op`.

## Follow-up (test-shaper skill, round 5)

- [x] The **negative boundary of the known-helper exclusion** is untested:
      no narrow test pins that a session whose name *superficially resembles*
      an excluded pattern is **not** excluded and analysis proceeds.
      `known-helper-session?`
      (`extensions/context-manager/src/extensions/context_manager.clj`)
      excludes a session when `friction/workflow-step-session?` matches, and
      that predicate
      (`extensions/context-manager/src/extensions/context_manager/friction.clj`)
      is a **two-sided** boundary — `(and (str/starts-with? name "workflow ")
      (str/ends-with? name " attempt"))`. Every exclusion test drives an
      *exact* excluded name (`"workflow builder attempt"`,
      `"entity-resolution"`, `"auto-session-name"`) → `:no-op`; the only
      "not excluded" evidence is the default `collaborators`'
      `:session-name "top-level"` in the happy-path tests
      (`issue-creates-task-test` etc.), which conflate the
      *not-excluded* contract with the task-creation contract and use a name
      that doesn't resemble the boundary at all. Verified directly against
      the current predicate: `"my workflow builder attempt notes"`,
      `"run workflow attempt"`, and `"workflow builder"` all return `false`
      (correctly not excluded), but a regression loosening either arm to a
      `str/includes?` — a natural "match workflow sessions" simplification —
      would flip the mid-string case to `true` and silently **over-exclude**
      a legitimate session whose name merely *contains* `"workflow … attempt"`
      (e.g. a user-named session), suppressing all friction analysis for it,
      yet pass every current test (all exact excluded names still match; the
      `"top-level"` happy path doesn't touch the boundary). This is the exact
      negative-boundary contract `entity-resolution-slash-command-only-
      negative-boundary-test` (`context_manager_test.clj`) already pins for
      the slash-command predicate, absent for the exclusion predicate. Add a
      direct `friction/workflow-step-session?` test (or a `friction-analysis`
      exclusion test) asserting a name that only *contains* the pattern
      (`"my workflow builder attempt notes"`), and one missing each arm
      (`"run workflow attempt"`, `"workflow builder"`), is **not** excluded —
      the predicate returns `false` / analysis reaches `:success` — pinning
      the starts-with ∧ ends-with boundary against a substring-match
      regression.
- [x] `friction/render-task-list`'s **multi-task rendering** (via
      `build-friction-prompt`) is untested: no test drives more than one
      task into either dedup list.
      `render-task-list`
      (`extensions/context-manager/src/extensions/context_manager/friction.clj`)
      `str/join`s `[{:id .. :title ..} ...]` into one `NNN-slug: title` line
      per task and renders `(none)` on empty — but `build-friction-prompt-
      test` (`context_manager_friction_parsing_test.clj`) drives exactly
      **one** open task and **one** closed task, and every
      `friction-analysis` test passes `{:open [] :recent-closed []}`. The
      realistic dedup-list shape is *all* open tasks plus up to 20
      recently-closed (design.md: 'Dedup') — so the multi-task path is the
      production path, not an edge. Verified directly: two open tasks render
      as two ordered lines (`010-a: A\n011-b: B`). A regression that
      collapsed the `str/join`, emitted only the first task, or dropped
      ordering would still pass every current test, since single-task input
      can't distinguish "render all in order" from "render first" — and the
      dedup list silently losing entries directly defeats AC3 (the helper
      can't match against tasks it never sees). Add a `build-friction-
      prompt` (or `render-task-list`) assertion that multiple open and
      multiple recently-closed tasks each render as distinct `NNN-slug:
      title` lines, in order, in the user prompt.

## Follow-up (test-shaper skill, round 4)

- [x] `parse-friction-output`'s **multi-block splitting and
      malformed-then-valid recovery** — the core structural behaviour of
      its `take-while`/`drop` block loop
      (`extensions/context-manager/src/extensions/context_manager/friction.clj`)
      — is untested at the `parse-friction-output` layer. Every case in
      `context_manager_friction_parsing_test.clj` drives a **single** ISSUE
      block (`parse-friction-output-nominal-test`, `-slug-sanitization-test`,
      `-malformed-test`) or a single issue + a single DUPLICATE line
      (`parse-friction-output-mixed-test`); none drives (a) **two or more
      well-formed ISSUE blocks** parsing into multiple `:issues`, nor (b) a
      **malformed ISSUE block immediately followed by a valid ISSUE block**,
      where the parser must drop the first and *recover* the second. Both
      behaviours are real and load-bearing: `parse-friction-output` splits
      each block by taking the header plus `(take-while (not (or ISSUE
      DUPLICATE)))`, then `(drop (count block) …)` to resume at the next
      header — so a bad first block must not swallow the following good one,
      and multiple issues must accumulate. Verified directly against the
      current code: the malformed-then-valid input (an ISSUE missing its
      SUGGESTION line, followed by a complete ISSUE block) yields exactly
      the *second* issue (`{:issues [{:slug "good-one" …}] :duplicates []}`),
      and two blank-line-separated complete blocks yield both issues in
      order. The only multi-block coverage that exists is at the
      *orchestration* layer (`cap-applied-test`/`two-issue-output` through
      `friction-analysis`), which proves cap selection over already-parsed
      issues, not the parser's own block boundaries — and no test anywhere
      exercises the malformed-then-valid recovery path. A regression that
      (e.g.) let a malformed leading block consume the following valid one,
      or that stopped after the first parsed block, would silently drop real
      detected issues yet pass every current `parse-friction-output` test.
      Add two direct `parse-friction-output` tests (mirroring the existing
      single-block cases): one asserting two well-formed ISSUE blocks parse
      into an ordered two-element `:issues`, and one asserting a malformed
      ISSUE block followed by a valid one yields only the valid issue — the
      parse-layer analog of the multi-issue path `cap-applied-test` only
      reaches end-to-end.

## Follow-up (test-shaper skill, round 3)

- [x] The **in-flight-claim release on a no-op / throwing first run** is
      untested. `friction-analysis`
      (`extensions/context-manager/src/extensions/context_manager.clj`)
      claims `session-id` in `friction-in-flight-session-ids` via
      `swap-vals!`, then runs `friction-analysis*` inside a `try` whose
      `finally` does `(swap! friction-in-flight-session-ids disj
      session-id)` — so the claim is released on **every** exit path (no-op,
      success, or thrown). But the only test proving the guard "doesn't leak
      across runs" (`sequential-runs-same-session-not-blocked-test`,
      `context_manager_friction_analysis_test.clj`) drives **two fully
      `:success` runs** (both via `(collaborators {})`, which returns a
      valid model + history + worktree). No test proves the claim is
      released when the *first* run returns a **no-op** (e.g. `:session-info`
      → no worktree, `:select-model` → nil, blank history) or when it
      **throws before returning**. A regression that moved the `disj` out of
      the `finally` and into only the success branch — or that returned the
      "already in flight" no-op without ever claiming/releasing — would
      permanently wedge any session that ever no-ops or errors (every
      subsequent `session_turn_finished` for that session would return
      `"analysis already in flight for this session"` forever), yet every
      current test would still pass, since the only release test's first run
      always succeeds. This is the same "guard state must not leak across
      runs" contract `sequential-runs-same-session-not-blocked-test` already
      pins for the success case, applied to the no-op/throw exits the
      `finally` actually exists to cover. Add a test: a first
      `friction-analysis` call whose collaborators force a no-op (e.g.
      `:session-info` → `{:worktree-root nil}`) — and, separately, one whose
      `:session-info` throws (reaching the outer `try`'s error `finally`) —
      followed by a second call for the *same* `session-id` with normal
      collaborators, asserting the second run reaches `:status :success`
      (the claim was released) rather than the "already in flight" no-op.
- [x] The **four distinct no-op branches of `run-analysis` are only
      diagnostic-pinned for two of them.** `friction/run-analysis`
      (`extensions/context-manager/src/extensions/context_manager/friction.clj`)
      returns four different `:no-op` results with distinct `:diagnostic`
      strings — `"known helper/infra session excluded"` (known-helper),
      `"no worktree"`, `"no local model"`, and `"no history"` — each
      guarding a different skip reason. `missing-local-model-no-op-test` and
      `missing-worktree-no-op-test`
      (`context_manager_friction_analysis_test.clj`) assert their exact
      `:diagnostic` strings, but the **five exclusion tests**
      (`own-helper-session-excluded-test`,
      `entity-resolution-helper-session-excluded-test`,
      `other-known-helper-session-excluded-test`,
      `other-known-workflow-step-session-excluded-test`,
      `other-known-auto-session-name-session-excluded-test`) and
      `blank-history-no-op-test` assert only `(= :no-op (:status result))`
      for the exclusion cases — none assert `:diagnostic "known helper/infra
      session excluded"`. Because `known-helper-session?` is evaluated
      *first* in `run-analysis`'s `cond` (before the worktree/model/history
      guards), a regression that (e.g.) inverted the known-helper predicate
      so an ordinarily-analyzable session fell through to a *different*
      no-op branch — or that mis-routed a real helper session into the
      no-worktree branch — would still produce *some* `:no-op` and pass
      every exclusion test, since none pins *which* branch fired. This is
      the same "assert the specific diagnostic, not just that it no-op'd"
      contract `missing-local-model-no-op-test`/`missing-worktree-no-op-test`
      already embody, absent from the exclusion tests. Add a
      `:diagnostic "known helper/infra session excluded"` assertion to at
      least one exclusion test (ideally each — they exercise distinct
      exclusion sources: own-helper atom, entity-resolution atom,
      literal-name set, `workflow-step-session?`, and `auto-session-name`),
      pinning that the exclusion path — not some other no-op branch — is
      what fired.

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
