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
