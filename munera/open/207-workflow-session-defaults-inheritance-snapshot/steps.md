# 207 — Steps

Checklist grouped by slice (see plan.md). Tick items with sha/decision notes.

## S1 — Field-set authority surface

- [ ] Promote `common-inherited-fields` to public (drop `^:private`) in
      `components/session-state/src/psi/session_state/init.clj:30`.
- [ ] Promote `model-identity-fields` to public (drop `^:private`) in
      `init.clj:67`.
- [ ] Verify in-namespace usages (`initialize-new-session-state`,
      `initialize-forked-session-state`, `resume-inherited-fields`) still
      resolve after the visibility change; run that component's tests.
- [ ] Add a named source-key constant in
      `components/workflow-step-session-config/src/psi/workflow_step_session_config/core.clj`,
      e.g. `inherited-defaults-source-keys`
      `{:from-common #{:prompt-mode :speed-mode :effort-override :tool-ids :skill-ids}
        :from-model #{:model :thinking-level}}` (Decision 8a).
- [ ] Add the snapshot resolved-key set constant (or derive it) =
      `{:model :prompt-mode :tool-defs :skills :thinking-level :speed-mode :effort-override}`.
- [ ] Add a test asserting the field-set invariant (Decision 8a): every
      `:from-common` key ∈ `init/common-inherited-fields`, every `:from-model`
      key ∈ `init/model-identity-fields`, and resolved keys equal source keys
      with `:tool-ids`→`:tool-defs` and `:skill-ids`→`:skills` substituted.
- [ ] Run lint (`clj-kondo`) + repair on touched files.

## S2 — Snapshot derivation functions

- [ ] Add `resolve-inherited-defaults-snapshot (ctx parent-session-id) → snapshot`
      to `workflow-step-session-config/core.clj`: read parent via
      `execution-adapter/get-session-data` → `:model`, `:prompt-mode`,
      `:speed-mode`, `:effort-override`; `skill-storage/all-skills` → `:skills`;
      `ss/agent-tool-source-in` + `:tool-ids` via `tool-defs/resolve-tool-defs`
      → `:tool-defs`; `:thinking-level` (default `:off`).
- [ ] Confirm `resolve-inherited-defaults-snapshot` returns exactly the snapshot
      resolved-key set (S1 constant); no extra keys.
- [ ] Add `effective-config->snapshot (effective-config parent-snapshot) →
      snapshot` — pure projection; no ctx reads. The 5 resolver-emitted inherited
      keys (`:model :prompt-mode :tool-defs :skills :thinking-level`) come from
      the effective config; `:speed-mode`/`:effort-override` come from
      `parent-snapshot` (resolver emits neither — resolved P2). `:model` is the
      effective config's already `{:provider :id}`-shaped value (resolved P3).
- [ ] Unit-test `resolve-inherited-defaults-snapshot` against a fixture
      parent session (asserts `:speed-mode`/`:effort-override` captured).
- [ ] Unit-test `effective-config->snapshot` projects only snapshot keys from
      an effective config + parent snapshot (overridden model preserved;
      `:speed-mode`/`:effort-override` sourced from the parent snapshot — P2).
- [ ] Lint + repair.

## S3 — Persist snapshot on the run

- [ ] Add an `inherited-defaults-schema` (optional fields:
      `:model :prompt-mode :tool-defs :skills :thinking-level :speed-mode
      :effort-override`) in `workflow-runtime/src/psi/workflow_runtime/model.clj`.
      `:model` is a `{:provider :id}`-shaped map (matches live
      `(:model parent-session)` / `model-query->selection-request` consumer —
      resolved P3), not a bare id string.
- [ ] Add `[:inherited-defaults {:optional true} [:maybe inherited-defaults-schema]]`
      to `workflow-run-schema` (model.clj:179).
- [ ] In `workflow-runtime/core.clj` `create-run` (line 110): destructure
      `inherited-defaults` from opts; add a `cond->` branch
      `(contains? opts :inherited-defaults) (assoc :inherited-defaults …)`
      mirroring `:parent-session-id`. No ctx reads added.
- [ ] Test: `create-run` with `:inherited-defaults` persists it verbatim on the
      run and the run validates against `workflow-run-schema`.
- [ ] Test: `create-run` without `:inherited-defaults` omits the key (back-compat).
- [ ] Lint + repair.

## S4 — Top-level capture sites

- [ ] In `agent_session/mutations/canonical_workflows.clj` `create-workflow-run`
      (line ~96): when `session-id` present, call
      `resolve-inherited-defaults-snapshot agent-session-ctx session-id` and
      add `:inherited-defaults` to the `create-run` opts.
- [ ] Add the `workflow-step-session-config` require to canonical_workflows.clj
      (verify no dependency cycle; agent-session already depends on it).
- [ ] In `agent_session/psi_tool_workflow.clj` `create-run` op (line ~143):
      when `session-id` present, resolve via
      `resolve-inherited-defaults-snapshot ctx session-id` and add
      `:inherited-defaults` to `create-opts`.
- [ ] Confirm the two upstream `mutate! 'psi.workflow/create-run` callers
      (`workflow/core.clj:382`, `orchestration.clj:208`) are left **unchanged**.
- [ ] Test: invoking a workflow captures the snapshot on the run at invoke time
      (top-level path).
- [ ] Test (Decision 5b): `continue-terminal-run-async!` produces a new run with
      a **fresh** snapshot resolved from the continuing session.
- [ ] Lint + repair.

## S5 — Consume snapshot in step config resolution

- [ ] In `resolve-step-session-config` (core.clj:145): when
      `(:inherited-defaults workflow-run)` present, source ONLY the 7 inherited
      fields (`:model`, `:prompt-mode`, `:tool-defs`, `:skills`,
      `:thinking-level`, `:speed-mode`, `:effort-override`) from the snapshot
      instead of the live parent reads. This is a per-field source swap, NOT a
      whole-path fork (resolved P5): non-inherited outputs
      (`:developer-prompt`, `:response-mode`, `:prompt-component-selection`,
      `:temperature`, logprob, `:model-fallback`) stay on their current
      step-def/base-meta code path regardless of snapshot presence.
- [ ] When `:inherited-defaults` absent (pre-existing runs), retain the current
      live-read path (forward-looking-only fallback; AC 6).
- [ ] Set the single `parent-session-model` binding (`core.clj:164`) to the
      snapshot's `{:provider :id}` `:model` when a snapshot is present, so ALL
      FOUR consumers observe it (resolved P4): `resolved-step-model-config` step
      override (`:173`), base-meta override (`:175`), bare no-override fallback
      (`:183`), and (transitively) `resolved-model-query` (AC 7). Do NOT replace
      only the model-query/no-override subset — override resolution must also see
      the snapshot model or AC1/AC2 leak.
- [ ] Ensure `:speed-mode`/`:effort-override` from the snapshot flow into the
      step's resolved config output (extend output map — resolver emits neither
      today, resolved I1/P2).
- [ ] Verify explicit overrides (`:session` spec / base-meta) still win over the
      snapshot defaults (AC 5): the override path's OUTPUT still takes precedence;
      only its model-selection CONTEXT (`parent-session-model`) is snapshot-sourced
      (P4).
- [ ] Test AC 1: switching invoking session model after invoke → no effect on
      subsequent steps.
- [ ] Test AC 2: changing user/project default model after invoke → no effect.
- [ ] Test AC 3: same invariant for `prompt-mode`, `tools`, `skills`,
      `thinking-level`, `speed-mode`, `effort-override`.
- [ ] Test AC 5: explicit override still applied.
- [ ] Test AC 6: no-mutation single-step and multi-step resolution unchanged;
      no-snapshot fallback unchanged.
- [ ] Test AC 7: `resolved-model-query` selection context = snapshot model.
- [ ] Test AC 8: `resume-run`/`continue-blocked-run-async!` reuses the original
      snapshot (no re-capture; resolver not called).
- [ ] Lint + repair.

## S6 — Nested/delegated capture

- [ ] Add a new injected fn param (e.g. `resolve-inherited-defaults-fn`) to
      `delegate-step-runtime-result` (`delegate.clj:36`), alongside the existing
      `create-workflow-context-fn`/`send-and-drain-fn`. The caller (depends on
      both components) binds it to a closure that calls
      `resolve-step-session-config ctx parent-session-id workflow-run step-id`
      then `effective-config->snapshot effective-config parent-snapshot`
      (parent-snapshot = `(:inherited-defaults workflow-run)`, supplying
      `:speed-mode`/`:effort-override` per P2). `delegate.clj` does NOT require
      `workflow-step-session-config` (the reverse require is a CERTAIN cycle —
      wssc deps.edn already pulls workflow-runtime; resolved P1).
- [ ] Pass the injected fn's result as `:inherited-defaults` into the child
      `create-run` (line ~44).
- [ ] Wire the injected fn at the delegate caller site(s); update the existing
      injected-param call signatures accordingly.
- [ ] Confirm `delegate.clj` does **not** call
      `resolve-inherited-defaults-snapshot` (would re-read live parent + lose
      step overrides).
- [ ] Test AC 4: a step overrides the model then delegates → sub-delegation and
      its steps see the overridden model, captured at sub-delegation creation,
      not the (since-mutated) invoking session.
- [ ] Lint + repair.

## S7 — Coherence + docs

- [ ] Re-read all touched files (sync after tooling edits).
- [ ] Verify coherence across meta/spec/tests/code/docs for the snapshot model.
- [ ] Update user-facing docs (`doc/workflows.md` and/or `doc/architecture.md`)
      describing invoke-time inheritance snapshot semantics, if user-visible.
- [ ] Add changelog entry under `[Unreleased]` if behaviour is user-visible
      (workflow inheritance now snapshotted at invoke time).
- [ ] Run full lint (`clj-kondo --lint src` across touched components).
- [ ] Run the full relevant test suite; all green.
- [ ] Final review: confirm `create-run` purity preserved and no
      `workflow-runtime → workflow-step-session-config` layering inversion.

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

- [ ] PI1: Align design.md Decision 7a with the P2-resolved plan/steps.
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
- [ ] PI2: Align design.md Decision 7 with the P1-resolved S6 mechanism.
      design.md (`:188`) asserts "dependency direction stays caller → both
      components, avoiding a layering inversion" and the nested-flow prose
      (`:215-221`) has `delegate.clj` directly calling
      `resolve-step-session-config`/`effective-config->snapshot`, but P1
      established this is a certain require cycle and plan Risks + steps S6 now
      commit to injecting `resolve-inherited-defaults-fn` into
      `delegate-step-runtime-result`. Update design.md Decision 7 to reflect the
      injected-fn mechanism (delegate reaches the resolver via an injected fn,
      not a direct require) so design no longer contradicts plan/steps.
