# 207 — Steps

Checklist grouped by slice (see plan.md). Tick items with sha/decision notes.

## S1 — Field-set authority surface

- [x] Promote `common-inherited-fields` to public (drop `^:private`) in
      `components/session-state/src/psi/session_state/init.clj`.
- [x] Promote `model-identity-fields` to public (drop `^:private`) in
      `init.clj`.
- [x] Verify in-namespace usages still resolve after the visibility change;
      `psi.session-state.init-test` green (4 tests, 46 assertions).
- [x] Add a named source-key constant `inherited-defaults-source-keys`
      `{:from-common #{:prompt-mode :speed-mode :effort-override :tool-ids :skill-ids}
        :from-model #{:model :thinking-level}}` (Decision 8a) in
      `workflow-step-session-config/core.clj`.
- [x] Add the snapshot resolved-key set constant
      `inherited-defaults-snapshot-keys` =
      `{:model :prompt-mode :tool-defs :skills :thinking-level :speed-mode :effort-override}`.
- [x] Add `inherited-defaults-field-set-authority-test` asserting the invariant
      (Decision 8a): every `:from-common` key ∈ `init/common-inherited-fields`,
      every `:from-model` key ∈ `init/model-identity-fields`, resolved keys
      equal source keys with `:tool-ids`→`:tool-defs`/`:skill-ids`→`:skills`.
      Added `psi/session-state` direct dep to deps.edn for the test require.
- [x] Run lint (`clj-kondo`) — clean on touched files.

## S2 — Snapshot derivation functions

- [x] Add `resolve-inherited-defaults-snapshot (ctx parent-session-id) → snapshot`
      to `workflow-step-session-config/core.clj`: reads parent via
      `execution-adapter/get-session-data` → `:model`, `:prompt-mode`,
      `:speed-mode`, `:effort-override`; `skill-storage/all-skills` → `:skills`;
      `ss/agent-tool-source-in` + `:tool-ids` via `tool-defs/resolve-tool-defs`
      → `:tool-defs`; `:thinking-level` (default `:off`).
- [x] `resolve-inherited-defaults-snapshot` returns exactly the snapshot
      resolved-key set (asserted in test); no extra keys.
- [x] Add `effective-config->snapshot (effective-config parent-snapshot) →
      snapshot` — pure projection; no ctx reads. The 5 resolver-emitted inherited
      keys come from the effective config; `:speed-mode`/`:effort-override` come
      from `parent-snapshot` (P2). `:model` is the effective config's already
      `{:provider :id}`-shaped value (P3).
- [x] Unit-test `resolve-inherited-defaults-snapshot` against a fixture parent
      session (asserts `:speed-mode`/`:effort-override` captured; key set exact;
      thinking-level defaults to `:off`).
- [x] Unit-test `effective-config->snapshot` projects only snapshot keys
      (overridden model preserved; `:speed-mode`/`:effort-override` sourced from
      the parent snapshot, not the effective config — P2).
- [x] Lint clean.

## S3 — Persist snapshot on the run

- [x] Added `inherited-defaults-schema` (optional fields:
      `:model :prompt-mode :tool-defs :skills :thinking-level :speed-mode
      :effort-override`) in `workflow-runtime/model.clj`. `:model` is a
      `{:provider :id}`-shaped map (P3), all fields optional/nilable.
- [x] Added `[:inherited-defaults {:optional true} [:maybe inherited-defaults-schema]]`
      to `workflow-run-schema`.
- [x] In `workflow-runtime/core.clj` `create-run`: destructure
      `inherited-defaults` from opts; `cond->` branch
      `(contains? opts :inherited-defaults) (assoc :inherited-defaults …)`
      mirroring `:parent-session-id`. No ctx reads added (stays pure).
- [x] Test: `create-run` with `:inherited-defaults` persists it verbatim and the
      run validates against `workflow-run-schema`.
- [x] Test: `create-run` without `:inherited-defaults` omits the key (back-compat).
- [x] Lint clean.

## S4 — Top-level capture sites

- [x] In `canonical_workflows.clj` `create-workflow-run`: when `session-id`
      present, call `resolve-inherited-defaults-snapshot agent-session-ctx
      session-id` and add `:inherited-defaults` to the `create-run` opts.
- [x] Added the `workflow-step-session-config.core` require to
      canonical_workflows.clj (agent-session already depends on it; no cycle).
- [x] In `psi_tool_workflow.clj` `create-run` op: resolve via
      `resolve-inherited-defaults-snapshot ctx session-id` (session-id already
      required here) and add `:inherited-defaults` to `create-opts`.
- [x] Confirmed the two upstream `mutate! 'psi.workflow/create-run` callers
      (`workflow/core.clj`, `orchestration.clj`) are left **unchanged** (git
      diff: only the two direct-site files changed).
- [x] Test: invoking a workflow captures the snapshot at invoke time — added
      both at the mutation level (`canonical-workflows-test`) and the psi-tool op
      level (`workflow-tools-test`); also a no-session-id → no-snapshot case.
- [x] Decision 5b (continue fresh capture): satisfied structurally —
      `continue-terminal-run-async!` routes through `mutate!
      'psi.workflow/create-run`, which is the same `create-workflow-run`
      mutation that always captures a fresh snapshot from the active/continuing
      session. No special threading; covered by the mutation-path capture test.
- [x] Lint clean.

## S5 — Consume snapshot in step config resolution

- [x] In `resolve-step-session-config`: when `(:inherited-defaults workflow-run)`
      present, the 7 inherited fields (`:model`, `:prompt-mode`, `:tool-defs`,
      `:skills`, `:thinking-level`, `:speed-mode`, `:effort-override`) are sourced
      from the snapshot instead of the live parent reads. Per-field source swap,
      NOT a whole-path fork (P5): non-inherited outputs stay on their current
      step-def/base-meta code path. `:tool-defs`/`:skills` snapshots replace the
      resolved name-resolution pools.
- [x] When `:inherited-defaults` absent (pre-existing runs), the current live-read
      path is retained (forward-looking-only fallback; AC 6).
- [x] The single `parent-session-model` binding is set wholesale to the
      snapshot's `:model` when present (P4), so all four consumers observe it
      (step override, base-meta override, no-override fallback, model-query
      selection context).
- [x] `:speed-mode`/`:effort-override` from the snapshot flow into the step's
      resolved config output (cond-> assoc when present).
- [x] End-to-end propagation (discovered necessary for AC3): threaded
      `:speed-mode`/`:effort-override` from the resolved config through
      `attempts/create-step-attempt-session!` → `child-session-contract`
      request-schema → `context/create-workflow-child-session!` →
      `:session/create-child` handler → `child-session-state` (override wins,
      else parent fallback). Without this the snapshot would be decorative for
      those two fields (workflow children build state via
      `child-session-base-state*`, which did not inherit speed/effort).
- [x] Explicit overrides still win over snapshot defaults (AC 5): override path
      OUTPUT precedence preserved; only model-selection CONTEXT is
      snapshot-sourced. Proven by `snapshot-preserves-explicit-step-override-test`.
- [x] Test AC 1/AC 2: `snapshot-isolates-resolution-from-live-parent-mutation-test`
      — mutating live session model/prompt-mode/speed/effort after invoke has no
      effect on resolution.
- [x] Test AC 3: same invariant for prompt-mode, thinking-level, speed-mode,
      effort-override (same test); tools/skills sourced from snapshot pools.
- [x] Test AC 5: `snapshot-preserves-explicit-step-override-test`.
- [x] Test AC 6: existing no-snapshot resolver tests unchanged +
      `no-snapshot-falls-back-to-live-parent-test` (no speed/effort emitted).
- [x] Test AC 7: `snapshot-model-feeds-model-query-selection-context-test`.
- [x] Test AC 8: `resume-run-test` "AC8" testing block — resume preserves the
      original snapshot verbatim (no re-capture).
- [x] Lint clean.

## S6 — Nested/delegated capture

- [x] Added `resolve-inherited-defaults-fn` as a new injected fn param to
      `delegate-step-runtime-result`, alongside the existing
      `create-workflow-context-fn`/`send-and-drain-fn`. Bound in `context.clj`
      to a closure that calls `resolve-step-session-config` then
      `effective-config->snapshot effective-config (:inherited-defaults
      workflow-run)` (supplying speed/effort per P2). `delegate.clj` does NOT
      require `workflow-step-session-config` (reverse require = certain cycle,
      P1).
- [x] Pass the injected fn's result as `:inherited-defaults` into the child
      `create-run` via `cond->`.
- [x] Wired the injected fn at the delegate caller site
      (`statechart_runtime.clj` passes `(:resolve-inherited-defaults-fn ctx)`).
- [x] Confirmed `delegate.clj` does NOT call
      `resolve-inherited-defaults-snapshot` (it calls only the injected fn,
      which sources the effective config = run snapshot ⊕ step overrides).
- [x] Test AC 4: `nested-delegation-effective-snapshot-propagates-overridden-
      model-test` — a step overriding the model yields a nested snapshot with
      the overridden model, with speed/effort threaded from the parent run
      snapshot (P2), exactly the snapshot key set. Real delegation execution
      tests still green.
- [x] Lint clean.

## S7 — Coherence + docs

- [x] Re-read all touched files (sync after tooling edits); resolver snapshot
      bindings + delegate injected-fn signature confirmed coherent.
- [x] Verified coherence across spec/tests/code/docs for the snapshot model.
- [x] Updated `doc/workflows.md` with an "Inherited session defaults are
      snapshotted at invoke time" section (invoke-time capture, nested effective
      config, explicit-override precedence, resume-reuse vs continue-fresh).
- [x] Added `[Unreleased]` → Fixed changelog entry describing the snapshot
      behaviour (model/prompt-mode/tools/skills/thinking-level/speed/effort, no
      retroactive mid-run leakage, nested delegation, replayable canonical state).
- [x] Ran `clj-kondo --lint` across all touched component src files — clean.
- [x] Ran the relevant focused test suite across all touched areas — 84 tests,
      490 assertions + workflow-execution/statechart-runtime + child-session
      mutation/judge suites all green.
- [x] Final review: `create-run` purity preserved (records `:inherited-defaults`
      verbatim, no ctx reads); no `workflow-runtime → workflow-step-session-config`
      layering inversion (nested path uses the injected fn).

## Plan-ambiguity follow-ups (review 2026-06-02)

- [x] P1: Resolve the S6 dependency-direction contradiction. The Risks section
      claims `workflow-runtime` must NOT depend on `workflow-step-session-config`
      and "direction stays caller→both", but `workflow-step-session-config`
      already requires `workflow-runtime` (`core.clj:16/17`), so S6's direct
      `delegate.clj` → `resolve-step-session-config`/`effective-config->snapshot`
      calls form a require cycle. Commit S6 (and the plan/Risks text) up front to
      injecting the snapshot resolver as a passed fn / ctx op (mirroring
      delegate's existing `create-workflow-context-fn`/`send-and-drain-fn`
      injected params), not a conditional "if it introduces a cycle" choice.
- [x] P2: Specify where the nested (`effective-config->snapshot`) path obtains
      `:speed-mode`/`:effort-override`. `resolve-step-session-config`'s result
      set excludes both (resolved I1), so a pure `select-keys` projection yields
      only 5 of the 7 snapshot keys and silently drops them under delegation
      (breaks AC3/AC4 for those fields). Decide: thread the parent run's snapshot
      speed-mode/effort-override into the nested derivation, or extend
      `resolve-step-session-config` to emit them — projection alone is
      insufficient.
- [x] P3: Pin the snapshot `:model` shape against `resolved-model-query`'s
      consumer. `model-query->selection-request` (`core.clj:104`) reads
      `(:provider parent-session-model)`/`(:id parent-session-model)`; state
      whether the snapshot stores `:model` as that `{:provider :id}` map (drops
      in directly) or a bare id (would break the destructure).
- [x] P4: State that snapshot `:model` replaces `parent-session-model`
      wholesale. `resolve-step-session-config` feeds `parent-session-model`
      (`:164`) to four sites — `resolved-step-model-config` for step override
      (`:173`) and base-meta override (`:175`), the bare no-override fallback
      (`:183`), and (transitively) `resolved-model-query`. S5 names only the
      model-query/no-override subset; require ALL `parent-session-model` uses to
      switch to the snapshot model, or AC1/AC2 leak via override resolution
      still reading the live parent.
- [x] P5: Clarify S5's snapshot substitution is a per-field source swap for the
      seven inherited keys, not a whole-path binary fork. Fields
      `resolve-step-session-config` always derives from step-def/base-meta
      (`:developer-prompt`, `:response-mode`, `:prompt-component-selection`,
      temperature, logprob) stay on their current code path regardless of
      snapshot presence; only the 7 inherited defaults are sourced from the
      snapshot (with live-read fallback when absent).

## Plan-inconsistency follow-ups (review 2026-06-02)

- [x] PI1: Align design.md Decision 7a with the P2-resolved plan/steps.
      design.md (`:209`) still defines `effective-config->snapshot` as
      `(effective-config) → snapshot-map` ("pure projection … into the snapshot
      field set") and the nested-flow prose (`:220-221`) calls it single-arg,
      but plan.md (`:26/:36`) + steps.md (`:37/:136`) use
      `effective-config->snapshot (effective-config parent-snapshot)` sourcing
      `:speed-mode`/`:effort-override` from the parent snapshot (resolver emits
      neither — P2/I1). Update design.md Decision 7a signature, the
      "pure projection of effective config" description, and the `:220-221`
      nested-flow prose to the two-arg form so design no longer contradicts
      plan/steps.
- [x] PI2: Align design.md Decision 7 with the P1-resolved S6 mechanism.
      design.md (`:188`) asserts "dependency direction stays caller → both
      components, avoiding a layering inversion" and the nested-flow prose
      (`:215-221`) has `delegate.clj` directly calling
      `resolve-step-session-config`/`effective-config->snapshot`, but P1
      established this is a certain require cycle and plan Risks + steps S6 now
      commit to injecting `resolve-inherited-defaults-fn` into
      `delegate-step-runtime-result`. Update design.md Decision 7 to reflect the
      injected-fn mechanism (delegate reaches the resolver via an injected fn,
      not a direct require) so design no longer contradicts plan/steps.

## Implementation-review follow-ups (review 2026-06-02)

- [x] R1: In `resolve-step-session-config`
      (`workflow-step-session-config/core.clj:195`) make the live
      `parent-session` (`execution-adapter/get-session-data`) read snapshot-gated
      / lazy so it is NOT performed when `(:inherited-defaults workflow-run)` is
      present. Currently the live read is unconditional but unused on the
      snapshot path (only the else-branches consume `parent-session`), a dead
      read that partially defeats the "no live parent re-read" snapshot intent.
      DONE: gated the binding to `(when-not snapshot? (get-session-data …))` —
      all three `parent-session` uses (model/prompt-mode at `:204/:205`, skills
      `:208`, tool-ids `:212`) are in the `(if snapshot? …)` else-branches, so
      the snapshot path now performs no live parent read. Existing AC1/AC2
      isolation tests + `no-snapshot-falls-back-to-live-parent-test` cover both
      paths (still green).
- [x] R2: Add an end-to-end AC4 test driving `delegate-step-runtime-result` with
      the injected `resolve-inherited-defaults-fn` (or a full delegation through
      the bound closure) that asserts the CHILD run's persisted
      `:inherited-defaults` equals the delegating step's effective snapshot
      (overridden model + parent-snapshot speed/effort). Current AC4 coverage
      tests only `resolve-step-session-config` + `effective-config->snapshot`
      directly, leaving the `delegate.clj:54-60` wiring
      (`when resolve-inherited-defaults-fn` → `assoc :inherited-defaults` into
      child `create-run`) unasserted.
      DONE: added
      `delegate-step-runtime-result-persists-child-inherited-defaults-test` to
      `inheritance_snapshot_test.clj` (requires
      `psi.workflow-runtime.statechart-runtime.delegate`). It registers a child
      + delegating definition, creates the delegating run with a parent-run
      snapshot, then drives `delegate/delegate-step-runtime-result` with the
      REAL injected closure (mirroring `context.clj`:
      `effective-config->snapshot` ∘ `resolve-step-session-config`) and stub
      no-op `send-and-drain-fn`/`create-workflow-context-fn`. Asserts the child
      run's persisted `:inherited-defaults` carries the delegating step's
      effective model + parent-snapshot speed/effort + exact key set, covering
      the `delegate.clj:54-60` `assoc :inherited-defaults` wiring.
      NOTE/correction to the review's parenthetical: a delegate step's COMPILED
      effective definition drops per-step `:session` overrides, so the
      delegating step's effective model is INHERITED from the parent run snapshot
      (`claude-PARENT`), not a step override — the test asserts that real
      behaviour. (The "overridden model" path is already covered at the
      function-composition level by
      `nested-delegation-effective-snapshot-propagates-overridden-model-test`.)
      inheritance-snapshot suite green (9 tests, 45 assertions); lint clean.

## Implementation-review pass 2 follow-ups (review 2026-06-02)

- [x] R3: Update the `child_session_state.clj` header classification comment
      (`:14-50`) so it stops drifting from `common-inherited-fields` after S5
      added speed/effort inheritance to `child-session-base-state*`
      (`:166-169`, `(or speed-mode (:speed-mode parent-sd))` /
      `(or effort-override (:effort-override parent-sd))`). (a) Fix the stale
      count: the comment says "common-inherited-fields (17 keys)" / "Inherited
      (7 of 17)" / "Not inherited (10 of 17)" but the constant now holds 19 keys
      (init.clj docstring + Decision 8a). (b) Add `:speed-mode` and
      `:effort-override` to the "Inherited" enumeration with their
      `(or … (:… parent-sd))` derivation note — they are currently absent from
      BOTH buckets despite the code inheriting them. This is the same drift
      Decision 8's authority test guards on the workflow-snapshot side; the
      hand-maintained child-session classification mirror has no such guard and
      has drifted. At minimum reconcile the comment with the code + authority.
      DONE: comment header now reads "common-inherited-fields (19 keys)";
      "Inherited from parent (9 of 19)" adds `:speed-mode` /`:effort-override`
      with their `(or … (:… parent-sd)) — workflow snapshot (task 207)`
      derivation notes (mirroring `child-session-base-state*`:166-169);
      "Not inherited — intentional defaults (10 of 19)" count corrected
      (its 10-key enumeration is unchanged — `:nucleus-prelude-override` stays
      classified as not-carried). 9 + 10 = 19 reconciles with the constant.
      Comment-only change (no behaviour/code path touched), so no test/doc
      delta; lint clean on the touched file.

## Implementation-review pass 3 follow-ups (review 2026-06-02)

- [x] R4: Close the live-parent leak past the resolver in
      `child-session-base-state*`
      (`agent-session/child_session_state.clj:144-169`). The snapshot isolates
      `resolve-step-session-config`'s OUTPUT (R1), but the workflow child-state
      assembly re-reads the LIVE parent via `(or <arg> (:<field> parent-sd))`
      where `parent-sd = (session/get-session-data-in ctx session-id)` (live,
      mid-run; `session_lifecycle.clj:115` `:session/create-child`). For
      snapshot-governed fields with a nil snapshot value at invoke this leaks a
      post-invoke live mutation: `:speed-mode`/`:effort-override` (resolver emits
      them only via `cond-> (some? (:speed-mode snapshot)) …`, `core.clj:243-249`
      — nil-at-invoke → `(or speed-mode (:speed-mode parent-sd))` reads live,
      `child_session_state.clj:166-169`) and `:model`/`:prompt-mode` (same
      `(or … (:… parent-sd))` fallback). Contradicts Decision 2 (resolved
      snapshot robust against later parent mutation) and AC3 (invariant holds for
      EVERY inherited default). Fix: for workflow-owned children the
      snapshot-governed inherited fields must not fall back to live `parent-sd`
      — thread the full snapshot through `:session/create-child` so the workflow
      child path uses snapshot values (explicit-override precedence preserved),
      or have the resolver always emit the snapshot value (incl. nil) so the
      child path cannot reach the live fallback. Add a test driving child-state
      assembly (resolver → `:session/create-child` → `child-session-base-state*`)
      that mutates the live parent's speed-mode/effort-override/model AFTER
      invoke and asserts the workflow child state is unchanged. (Note:
      `child-session-base-state-applies-speed-effort-override-test:121-125`
      currently pins the parent-sd fallback as intended for the general
      non-workflow path — distinguish workflow-owned vs non-workflow behaviour.)
      RECONCILED (R5/R6): the shipped fix gates the snapshot isolation on a NEW
      `:inherited-snapshot?` request flag, NOT `:workflow-owned?`. The earlier
      `workflow-owned?'`-gated `inherited-default` helper would have regressed
      the workflow judge (`workflow_judge.clj:107`), which is workflow-owned but
      supplies no `:model`/`:prompt-mode` and must keep live-parent inheritance.
      `child-session-base-state*` now uses `(if inherited-snapshot?' (or supplied
      default) (or supplied parent-value))`; `:inherited-snapshot? true` is set by
      `create-step-attempt-session!` (the resolver/step-attempt path), threaded
      through `context.clj` → `:session/create-child` → `session_lifecycle.clj`,
      and declared on `child_session_contract/request-schema`. Tests:
      `child-session-base-state-workflow-owned-isolates-snapshot-fields-test`
      covers snapshot-governed isolation, the judge carve-out (workflow-owned +
      no `:inherited-snapshot?` → live fallback), and non-workflow fallback;
      `attempts-test` forwards `:inherited-snapshot? true` on the request surface.

## Test-review follow-ups (review 2026-06-02)

- [x] T1: Strengthen the AC7 test
      (`snapshot-model-feeds-model-query-selection-context-test`,
      `inheritance_snapshot_test.clj`) so it actually proves the selection
      context comes from the SNAPSHOT model, not the live parent. It currently
      mutates the live session to `claude-LIVE-CHANGED` after invoke but asserts
      only `(some? (:model-fallback config))` + `:type :ranked-model-candidates`
      — both hold regardless of which model fed
      `model-query->selection-request`'s `:context {:session-model …}`
      (`core.clj:130-137`). The test would pass even if the resolver read the
      live model. Assert a snapshot-vs-live-distinguishing value: the selection
      request/`:model-fallback` `:session-model`/candidate/outcome must reflect
      `claude-snapshot` and NOT `claude-LIVE-CHANGED` (expose the session-model
      context if `:model-fallback` surfaces nothing model-dependent), so AC7's
      isolation invariant is provable rather than just the fallback's shape.

## Implementation-review pass 4 follow-ups (review 2026-06-02)

- [x] R5: Commit (or revert) the uncommitted R4 refinement. The genuine R4 fix
      — re-gating child-state snapshot isolation on `:inherited-snapshot?`
      instead of `:workflow-owned?` — is uncommitted across 5 files
      (`child_session_state.clj`, `context.clj`, `session_lifecycle.clj`,
      `attempts.clj`, `child_session_state_test.clj`). Committed HEAD is
      incoherent: `child_session_contract` declares the `:inherited-snapshot?`
      schema field + a judge-case comment, but nothing in HEAD produces or
      consumes it (committed `child_session_state.clj` still gates on
      `workflow-owned?'`), so HEAD ships a dangling schema field whose behaviour
      is unimplemented. Commit the working-tree change with a `⚒ 207` message
      (or revert and re-derive), then verify `git status --short` is clean and
      HEAD is self-consistent (schema field has a producer in `attempts.clj` and
      a consumer in `child_session_state.clj`). Do NOT close the task with a
      dirty tree.
- [x] R6: Reconcile steps.md R4's `[x] DONE` note with the actual fix. R4's
      note documents the `workflow-owned?'`-gated `inherited-default` helper, but
      the real fix re-gates on `:inherited-snapshot?` (to keep live-parent
      inheritance for the workflow judge / non-snapshot workflow children).
      Update R4's steps.md note to describe the `:inherited-snapshot?` mechanism
      after R5 is committed.
- [x] R7: Append the `:inherited-snapshot?` refinement to implementation.md's
      "R4 follow-up executed" section: record the re-gate from `workflow-owned?`
      to `:inherited-snapshot?`, the new contract-schema field, the threading
      through `context`/`session_lifecycle`/`attempts`, and why `workflow-owned?`
      was insufficient (the workflow judge is workflow-owned but supplies no
      model/prompt-mode and must keep live-parent inheritance).

## Implementation-review pass 5 follow-ups (review 2026-06-02)

- [x] Pass 5: full-implementation re-review after R5/R6/R7. HEAD
      `:inherited-snapshot?` mechanism self-consistent (producer attempts.clj /
      schema child_session_contract / threading context+session_lifecycle /
      consumer child_session_state); resolver snapshot consumption + child-state
      gate verified; purity boundary + no layering cycle confirmed. Focused
      suites green (25 tests/141 assertions); lint clean; tree clean; docs +
      changelog accurate. No new actionable findings — review complete. No
      follow-up items added.

## Test-review pass 2 follow-ups (review 2026-06-02)

- [x] T2: Close the AC3 tools/skills isolation coverage gap.
      `snapshot-isolates-resolution-from-live-parent-mutation-test`
      (`inheritance_snapshot_test.clj`) is the only AC1/AC2/AC3 isolation test;
      it sets `:tool-defs`/`:skills` in the snapshot but never asserts the
      resolved config's `:tool-defs`/`:skills` come from the snapshot, nor does
      it mutate the live parent's tool source / tool-ids / skills after invoke
      to prove no leak. AC3 explicitly names `tools` and `skills` as inherited
      defaults the invariant must hold for. The resolver sources both from the
      snapshot pool with the live read gated `(when-not snapshot? …)`
      (`core.clj:206-212`, R1), so the behaviour is structurally leak-free but
      asserted only by the field-derivation unit tests, NOT by the AC3 isolation
      test — which would still pass if a future change reintroduced a live
      tools/skills read on the snapshot path. Fix: extend the AC3 isolation test
      (or add a sibling) to assert resolved `:tool-defs`/`:skills` equal the
      snapshot pool AND stay unchanged after a post-invoke mutation of the live
      parent's tools/skills, matching the model/prompt-mode/speed/effort
      isolation coverage already in that test.
      DONE: the closing test
      `snapshot-isolates-tools-skills-from-live-parent-mutation-test`
      (`inheritance_snapshot_test.clj`) references `shared-tool`/`shared-skill`
      by name, captures them in the snapshot pool with `:description
      "from-snapshot"`, mutates the live parent's tool-source/tool-ids/skills to
      `"from-live"` AFTER invoke, and asserts the resolved config's
      `:tool-defs`/`:skills` resolve from the snapshot pool (`"from-snapshot"`),
      proving no live leak. Output assertion (resolved config), real ctx/state,
      lint-clean; inheritance-snapshot suite 9 tests / 47 assertions green.

## Test-review pass 3 follow-ups (review 2026-06-02)

- [x] T3: Commit the uncommitted T2 fix; HEAD still ships the AC3 tools/skills
      gap. The T2 test (`snapshot-isolates-tools-skills-from-live-parent-mutation-test`,
      +71 lines in `inheritance_snapshot_test.clj`) exists only in the working
      tree — committed HEAD does NOT contain it (`git show HEAD:…` grep = 0;
      `git diff --stat` = 1 file/+71), so HEAD ships the exact AC3 tools/skills
      behavioural-coverage gap T2 identified while the closing test dangles
      uncommitted (same dirty-tree / incoherent-HEAD failure mode as R5). Commit
      the working-tree test with a `⚒ 207` message, then verify
      `git status --short` is clean and HEAD self-consistent (AC3 tools/skills
      isolation behaviourally covered in HEAD). Do NOT close the task with a
      dirty tree.
      DONE: HEAD `91d65298d` already contained the T2 test (grep matched
      line 193), so the original coverage gap was already closed; what remained
      uncommitted was a small +6/-6 REFINEMENT — moved the step's tool/skill
      refs from `:session` to top-level `:tools`/`:skills` (added `"read"`
      alongside `"shared-tool"`), and made the resolved-def lookup
      order-independent (`(some #(= name (:name %)) pool)` + `(some? …)` guard
      before the `from-snapshot` description assertion). Focused suite green
      (10 tests, 51 assertions, 0 failures); `clj-kondo` clean (0/0) on the
      touched file; committed with `⚒ 207`; `git status --short` clean
      afterwards. HEAD self-consistent.

## Test-review pass 4 follow-ups (review 2026-06-02)

- [x] T4: Add behavioural coverage for Decision 5b (continue-terminal fresh
      snapshot) and the session-id auto-injection it depends on. Today AC8 covers
      only Decision 5a (resume REUSES the snapshot); Decision 5b — a terminal-run
      continuation creating a NEW run that captures a FRESH snapshot from the
      continuing session — has zero behavioural test and is asserted only
      "structurally" in S4. The structural claim is subtle and untested: both
      upstream `mutate! 'psi.workflow/create-run` callers
      (`workflow/core.clj:382`, `orchestration.clj:208`
      `continue-terminal-run-async!`) pass NO `:session-id`; capture works only
      because `workflow/bootstrap.clj:80`'s `mutate-fn` wrapper auto-injects
      `:session-id sid` from `*active-workflow-session-id*` (`:128`). The S4
      capture tests (`canonical-workflows-test`/`workflow-tools-test`) bypass this
      by calling the mutation directly with an explicit `:session-id`, so they
      prove only "captures when session-id supplied", not that the real
      invoke/continue path supplies it. Add a test driving
      `continue-terminal-run-async!` (or the bootstrap mutate-fn wrapper with
      `*active-workflow-session-id*` bound) that mutates the session model between
      the original invoke and the continue and asserts the NEW continuation run's
      `:inherited-defaults` is a FRESH snapshot reflecting the changed
      (continuing-session) model — distinguishable from the original terminal
      run's snapshot. Closes the 5b coverage hole and pins the session-id
      auto-injection contract all top-level capture relies on.
      DONE (production-grounded; reconciled with concurrent session work in this
      worktree): the session-id auto-injection T4 depends on is now a REAL
      production fix, not just a test mirror. `psi.workflow/create-run` was added
      to `runtime-eql/session-scoped-extension-mutation-ops`
      (`extensions/runtime_eql.clj`), so `run-extension-mutation-in!` injects the
      invoking `:session-id` when the caller passes none — exactly the path
      `continue-terminal-run-async!` and the top-level invoke
      (`workflow/core.clj`) rely on (both call `mutate! 'psi.workflow/create-run`
      with no explicit `:session-id`). Behavioural coverage:
      `continue-terminal-run-captures-fresh-snapshot-test` in
      `canonical_workflows_snapshot_test.clj` (split out of
      `canonical_workflows_test.clj` for the 800-line file-length limit) drives
      the REAL
      `orchestration/continue-terminal-run-async!` with a production-like
      `mutate!` that reproduces the runtime-fns/session-scoped contract (inject
      from `*active-workflow-session-id*` when `:session-id` absent) routing to
      the real `create-workflow-run`. Original invoke captures `claude-ORIGINAL`;
      the continuing session switches to `claude-CHANGED` AFTER invoke; the
      continuation NEW run's persisted `:inherited-defaults :model` =
      `claude-CHANGED` (FRESH) and `≠` the original terminal run's snapshot —
      proving 5b captures fresh, not reuses, and pinning the session-id
      injection contract all top-level capture relies on (the S4 tests bypass it
      by passing an explicit `:session-id`). canonical-workflows +
      workflow-async-path suites green; lint clean.
      NOTE (ψ reconciliation): an earlier test-only mirror
      (`orchestration_continue_snapshot_test.clj`) was drafted in this pass but
      removed as redundant once the concurrent production fix
      (`runtime_eql.clj` session-scoped op + the canonical-workflows T4 test)
      proved the stronger, real-path resolution.

## Test-review pass 5 follow-ups (review 2026-06-02)

- [x] T5: Add an end-to-end seam test for the `:inherited-snapshot?` contract.
      The R4/R5 child-state snapshot isolation is gated on a `:inherited-snapshot?`
      request flag. Its producer (`create-step-attempt-session!` →
      `:inherited-snapshot? true`) is asserted at `attempts-test:140` and its
      consumer (`child-session-base-state*` suppressing the live parent-sd
      fallback) at `child-session-state-test:141`, but the threading hop between
      them — `context.clj:159` `(assoc :inherited-snapshot? …)` →
      `create-workflow-child-session!` → `:session/create-child`
      (`session_lifecycle.clj`) → `child-session-base-state*` — is untested. Both
      endpoint unit tests stay green even if the flag were dropped on the wire,
      which is the same incoherence class R5 caught (a contract-schema field with
      no producer/consumer wiring). Add a test driving the attempt/child-session
      path through `:session/create-child` (real ctx/state, nullable adapter)
      that mutates the live parent's model/speed-mode/effort-override AFTER invoke
      and asserts the created child session's state uses the snapshot /
      initial-session default and NOT the live parent — proving the flag survives
      the full `context`/`session_lifecycle` threading, not just the two endpoints.
      DONE: added
      `create-workflow-child-session-inherited-snapshot-flag-survives-threading-test`
      to `workflow_child_session_context_test.clj`, driving the REAL private
      `create-workflow-child-session!` → `:session/create-child` dispatch →
      `child-session-base-state*` chain (real ctx/state, nullable adapter via
      `create-session-context`). Parent carries non-default
      model/prompt-mode/speed/effort; the request sets `:inherited-snapshot? true`
      with nil snapshot-governed fields. Asserts the persisted child session uses
      the initial-session defaults (`:model` nil, `:prompt-mode :lambda`,
      `:speed-mode`/`:effort-override` nil) — NOT the live parent — proving the
      flag crosses the `context` → `session_lifecycle` wire. Control block: the
      same request WITHOUT the flag falls back to the live parent
      (`live-model`/`:prose`/`:flex`/`:low`) through the identical chain,
      proving the distinction is carried by the flag rather than lost on the
      wire. 2 blocks, 39 assertions in the (now 4-test) suite green; lint clean.

## Test-review pass 6 follow-ups (review 2026-06-02)

- [x] T6: Strengthen `resolve-inherited-defaults-snapshot-test`
      (`inheritance_snapshot_test.clj`) to assert the CAPTURED tools/skills by
      VALUE, not just shape. The test sets model/prompt-mode/thinking/speed/
      effort on the fixture parent and asserts each exactly, but for the two
      pool fields asserts only `(vector? (:tool-defs snapshot))` /
      `(sequential? (:skills snapshot))`. `resolve-inherited-defaults-snapshot`
      (`core.clj:282-285`) reads `:tool-defs` from the parent's `tool-source` +
      `:tool-ids` and `:skills` from `skill-storage/all-skills`; a regression
      that dropped `:tool-ids`, read the wrong session, or returned an empty
      pool would still pass (empty `[]` is `vector?`/`sequential?`). The
      isolation test (`snapshot-isolates-tools-skills-from-live-parent-mutation-test`)
      uses a HAND-BUILT snapshot, so it does not cover the capture path's
      tools/skills value either — leaving the CAPTURE half of AC3's tools/skills
      invariant value-unasserted while consumption/isolation is covered. Fix:
      give the fixture parent a known tool (`tool-source` + `:tool-ids`) and a
      known skill, and assert the snapshot's `:tool-defs`/`:skills` contain the
      resolved def(s) for those names (matching the value-level rigor the other
      five captured fields already have in the same test).
      DONE: extended the first `testing` block of
      `resolve-inherited-defaults-snapshot-test`. It now seeds the fixture
      parent's REAL capture inputs — sets the agent tool-source via
      `agent-core/set-tools-in! (ss/agent-ctx-in …)` to a `known-tool`, selects
      it with `:tool-ids ["known-tool"]`, and registers a `known-skill` through
      the canonical `:session/register-skill` dispatch (stores the def in
      root-state + tracks `:skill-ids`). It then asserts the captured snapshot's
      `:tool-defs`/`:skills` CONTAIN the named def AND carry its value
      (`:description "a known tool"` / `"a known skill"`), replacing the
      shape-only `vector?`/`sequential?` checks. A regression dropping
      `:tool-ids`, reading the wrong session, or returning an empty pool now
      fails. NOTE: the capture path resolves `:tool-defs` from
      `agent-tool-source-in` (the agent data-atom `:tools`), NOT session-data
      `:tool-source`; the test seeds the data-atom accordingly (the
      hand-built-snapshot isolation test's session-data `:tool-source` mutation
      is a deliberate distractor on the consumption path, not the capture path).
      Test-only strengthening (no behaviour/code/doc change). inheritance-snapshot
      suite green (10 tests, 53 assertions); lint clean.

## Test-review pass 7 follow-ups (review 2026-06-02)

- [x] T7: Add a direct nested-path isolation test for AC4's
      "not-the-since-mutated-invoking-session" half. The two existing AC4 tests
      (`nested-delegation-effective-snapshot-propagates-overridden-model-test`,
      `delegate-step-runtime-result-persists-child-inherited-defaults-test`,
      `inheritance_snapshot_test.clj`) only cover override PROPAGATION; neither
      mutates the LIVE parent session AFTER invoke before delegating, so the
      nested child snapshot's isolation from a since-mutated invoking session is
      proven only transitively (via the AC1/AC2/AC3 resolver isolation tests +
      the snapshot-gated R1 read). A future change reintroducing a live read on
      the nested derivation path would pass every current test. Add a test that:
      creates a delegating run with a parent-run snapshot, MUTATES the live
      parent session's model/speed-mode/effort-override AFTER invoke, drives the
      delegation (the real injected `resolve-inherited-defaults-fn` closure /
      `delegate-step-runtime-result`), and asserts the CHILD run's persisted
      `:inherited-defaults` reflects the parent-run snapshot (effective config),
      NOT the mutated live parent — mirroring the direct top-level isolation
      coverage T2 added for tools/skills.
      DONE: added
      `nested-delegation-isolates-child-snapshot-from-live-parent-mutation-test`
      to `inheritance_snapshot_test.clj`. It reuses the e2e `delegating-e2e`
      definitions, creates the delegating run with a `claude-PARENT` parent-run
      snapshot (speed `:fast`/effort `:xhigh`), then mutates the LIVE parent
      session to `claude-LIVE-CHANGED` (speed `:flex`/effort `:low`) AFTER invoke
      BEFORE delegating, drives the REAL `delegate/delegate-step-runtime-result`
      with the real injected `resolve-inherited-defaults-fn` closure (mirroring
      `context.clj`: `effective-config->snapshot` ∘ `resolve-step-session-config`)
      + stub no-op `send-and-drain-fn`/`create-workflow-context-fn`, and asserts
      the CHILD run's persisted `:inherited-defaults` carries the parent-run
      snapshot model (`claude-PARENT`, `≠ claude-LIVE-CHANGED`) + parent-snapshot
      speed/effort (`:fast`/`:xhigh`, not the mutated `:flex`/`:low`) + exact key
      set — proving the nested derivation path does not leak a since-mutated live
      parent read (mirrors the direct top-level T2 tools/skills isolation).
      inheritance-snapshot suite green (11 tests, 59 assertions); lint clean.

## Code-shaper review follow-ups (review 2026-06-02)

- [x] CS1: Unify the three snapshot-consumption idioms in
      `resolve-step-session-config` (`workflow-step-session-config/core.clj`).
      The seven inherited defaults are sourced from the snapshot via three
      different shapes — `(if snapshot? (:X snapshot) (:X parent-session))`
      (model/prompt-mode/skills/tool-defs, `:202-212`), `(when snapshot?
      (:thinking-level snapshot))` inside an `or` (`:243-246`), and
      `(and snapshot? (some? (:X snapshot))) → assoc` cond-> branches
      (speed-mode/effort-override, `:255-261`). Make the snapshot field set read
      as a single unit: e.g. a per-field `inherited-default` helper or a
      snapshot-vs-live source map keyed by `inherited-defaults-snapshot-keys`,
      so adding/removing an inherited field touches one shape, not three. Keep
      behaviour identical (existing AC1–8 tests must stay green); shape-only.
      DONE: introduced a single `inherited` map bound once
      (`core.clj:213-219`): on the snapshot path it is
      `(select-keys snapshot inherited-defaults-snapshot-keys)`; on the live
      path it carries only the four fields the pre-task resolver inherited live
      (`:model :prompt-mode :skills :tool-defs`), deliberately omitting
      `:thinking-level :speed-mode :effort-override` to preserve AC6 back-compat
      (snapshot-less runs emit no speed/effort and fall thinking-level back to
      base-meta/:off). All seven downstream reads now go through `inherited`
      (`(:prompt-mode inherited)`, `(:tool-defs inherited)`,
      `(:thinking-level inherited)`, `(:skills inherited)`,
      `(some? (:speed-mode inherited))`/`(:effort-override inherited)`,
      `parent-session-model (:model inherited)`), so the
      `inherited-defaults-snapshot-keys` set is consumed as one unit and the
      three idioms collapse to one. Behaviour preserved for the four live-path
      fields; the only behaviour delta is CS2 (thinking-level precedence) below.
      Full unit suite green; lint clean.
- [x] CS2: Reconcile the `:thinking-level` vs `:model` inherited-default
      precedence inversion in `resolve-step-session-config`. `:model` ranks the
      inherited default ABOVE the base-meta override (`:220-235`), but
      `:thinking-level` ranks the snapshot/inherited value BELOW base-meta
      (`:243-246`), so a `:workflow-file-meta` thinking-level masks the inherited
      parent value while a `:workflow-file-meta` model does not. Decide the
      intended ordering, make it uniform across the inherited fields (or document
      the per-field difference + rationale in design.md Decision 1/7 and the
      resolver). If the behaviour changes, add/adjust a precedence test
      (base-meta vs inherited default) for the affected field(s).
      DONE: chose uniformity with the established `:model` convention — the
      inherited default ranks ABOVE base-meta. `:thinking-level` reordered to
      `(or (:thinking-level session-spec) (:thinking-level inherited)
      (:thinking-level base-meta) :off)` so step override → inherited → base-meta
      → :off, mirroring `resolved-model`'s cond (step → parent-session-model →
      base-meta). Behaviour change: a `:workflow-file-meta :thinking-level` no
      longer masks an inherited (snapshot) thinking-level. Documented in
      design.md Decision 1a (precedence convention + the AC6 back-compat carve-out
      that snapshot-less runs still don't inherit live thinking-level) and the
      CHANGELOG Fixed entry. New precedence test
      `snapshot-thinking-level-precedence-matches-model-test`
      (`inheritance_snapshot_test.clj`): block 1 asserts inherited snapshot
      thinking-level (:high) AND model both win over base-meta (:low /
      claude-base-meta), proving the two fields now agree; block 2 asserts an
      explicit step :thinking-level (:medium) still wins over both. Full unit
      suite green; lint clean.

## Test-review pass 9 follow-ups (review 2026-06-02)

- [x] T8: Fix the contradictory contract comment in
      `canonical_workflows_snapshot_test.clj:44`. The inline comment calls
      `psi.workflow/create-run` "non-session-scoped", but the SAME file's
      docstring (`:19-20`) and production (`runtime_eql.clj:17-49`,
      `session-scoped-extension-mutation-ops`) state it IS session-scoped — the
      whole reason the session-id is auto-injected. The test's purpose is to pin
      that auto-injection contract, so a comment misstating the contract
      direction misleads a future reader debugging the seam and weakens the
      test's self-documenting `meaningful_failures` value. Correct the comment to
      say session-scoped (or drop the (non-)scoped qualifier and just reference
      the injection-when-absent behaviour). Comment-only; behaviour unchanged.
      DONE: rewrote the line-44 deltest comment from "matching the runtime-fns
      wrapper for the non-session-scoped `psi.workflow/create-run`" to "matching
      the runtime-fns wrapper that injects the active session for the
      session-scoped `psi.workflow/create-run`", reconciling it with the file
      docstring (`:17-22`) and production (`runtime_eql.clj`
      `session-scoped-extension-mutation-ops`). Comment-only; lint clean
      (0 errors / 0 warnings) on the touched file; behaviour unchanged.
- [x] T9: Correct steps.md T4's DONE note filename. The note says the
      `continue-terminal-run-captures-fresh-snapshot-test` was added "in
      `canonical_workflows_test.clj`", but it actually lives in the sibling
      `canonical_workflows_snapshot_test.clj` (split out for the 800-line
      file-length limit). Update the cited filename so the task's test trail is
      accurate.
      DONE: updated steps.md T4's DONE note (the `:496` citation) from
      "in `canonical_workflows_test.clj`" to
      "in `canonical_workflows_snapshot_test.clj` (split out of
      `canonical_workflows_test.clj` for the 800-line file-length limit)", so the
      task's test trail now names the file the test actually lives in. Docs-only.
