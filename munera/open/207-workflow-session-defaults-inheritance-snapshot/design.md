# 207 — Workflow session-defaults inheritance snapshot

## Intent / Why

A running delegated workflow must have a **stable** notion of the "default
session details" it inherits. Today those defaults are read **live** from the
parent session every time a step's child-session config is resolved, so changes
made to the parent *after* the workflow was invoked retroactively alter the
behaviour of the still-running workflow.

Desired rule:

- **Workflows inherit their default session details from their parent.**
- **Top-level steps inherit from the invoking session, captured at invoke
  time.**
- Later changes — the invoking session switching model, or the user/project
  default model being changed — **must not** affect an already-running
  delegated workflow.

This aligns with the VSM ethos: a workflow run's inherited defaults become part
of its captured state at creation (deterministic, replayable), rather than a
live dependency on mutable ambient session/user/project state.

## Context (current behaviour)

- `psi.workflow-step-session-config.core/resolve-step-session-config` resolves a
  step's child-session config. For inherited defaults it reads the **live**
  parent session via
  `execution-adapter/get-session-data` → `(:model parent-session)`,
  `(:prompt-mode parent-session)`, `skill-storage/all-skills`,
  `ss/agent-tool-source-in` + `(:tool-ids parent-session)`, and a thinking-level
  fallback.
- `psi.workflow-runtime.core/create-run` records `:parent-session-id` on the run
  but does **not** snapshot any of the parent's default session details.
- The authoritative parent is resolved at resolve time as
  `parent-session-id` → `(:parent-session-id workflow-run)` → first context
  session.
- Consequence: a mid-run switch of the invoking session's model (or a change to
  the user/project default that the parent session reflects) is observed by all
  later steps of the running workflow.

## Scope

In scope:

- Capture the inheritable **default session details** from the parent **once, at
  the point the workflow run is invoked/created**, and persist them on the
  workflow run.
- Resolve a step's inherited defaults from that captured snapshot instead of
  re-reading the live parent session.
- Define inheritance for **nested/delegated** workflows: a child workflow run
  inherits its defaults from its parent (the delegating workflow run / step),
  captured when the child run is created — not re-derived live from the
  original invoking session.

Out of scope:

- Changing how a step's **explicit** overrides work (`:session` spec /
  `:workflow-file-meta` model / model-query / tools / skills / thinking-level /
  etc.). Snapshotting governs only the *inherited default* used when a step does
  not specify its own value.
- Changing user/project default-model resolution itself.
- Retroactively snapshotting already-created runs (forward-looking only).

## Resolved decisions

1. **Which details are "default session details" — ALL of them.** Snapshot the
   whole inherited-default set, not just `model`. This includes the fields the
   resolver inherits live today (`model`, `prompt-mode`, `skills`, `tools` =
   tool source + tool-ids, `thinking-level`) **and** the recently introduced
   request overrides `:speed-mode` and `:effort-override`. (Note: these mirror
   `session-state/init.clj`'s `common-inherited-fields`, the canonical
   child-session inheritance set; `speed-mode`/`effort-override` are there as
   inherited-but-transient fields — they must be part of the workflow snapshot.)

2. **Snapshot shape — fully resolved.** Capture a concrete resolved snapshot
   (e.g. `{:model … :prompt-mode … :tool-defs … :skills … :thinking-level …
   :speed-mode … :effort-override …}`), not raw parent fields. Resolved is
   robust against later parent mutation.

3. **Nested workflow parent semantics — inherit the parent's *effective*
   session config, captured when the sub-delegation is created.** The parent of
   a delegated sub-workflow is the delegating **step's effective/resolved
   session config** = (run snapshot ⊕ that step's own overrides). Concretely: if
   a step overrides the model and then invokes a delegation of its own, that
   sub-delegation (and its steps) sees the **overridden** model — overrides
   propagate down the delegation tree as the new inherited default. The whole
   tree is built from invoke-time-captured snapshots, never a live re-read of the
   original invoking session.

4. **model-query context — use the snapshot's model.** `resolved-model-query`
   takes its `parent-session-model` selection context from the snapshot (the
   parent's effective model), for determinism.

5. **continue/resume — keep the original invoke-time snapshot.** Resuming a
   blocked run does not re-capture defaults; it reuses the snapshot taken at
   original invoke time.

   5a. **`resume-run` (`continue-blocked-run-async!`) — reuse, no
   re-capture.** `resume-run` (`orchestration.clj` `continue-blocked-run-async!`)
   targets the **same** `run-id` and re-uses that run's existing canonical
   state, including its already-stored `:inherited-defaults` snapshot. No new
   snapshot is resolved; `resume-run` never calls
   `resolve-inherited-defaults-snapshot`.

   5b. **`continue-terminal-run-async!` — a NEW run captures a FRESH
   snapshot.** `continue-terminal-run-async!` (`orchestration.clj:201`)
   creates a **new** run via `mutate! 'psi.workflow/create-run` (a distinct
   `run-id`, derived `…-continue-<ts>` name) from the original run's
   `source-definition-id`. Because it is a fresh `create-run`, it follows the
   ordinary invoke-time capture rule (Decision 6): the `create-workflow-run`
   mutation resolves a **fresh** snapshot from the continuing `session-id` at
   continuation time. Continue does **not** reuse the original terminal run's
   snapshot. Rationale: a continuation is a new top-level invocation from the
   live session (it carries a fresh user prompt via `continue-workflow-input`),
   so it should inherit the session's defaults as they stand at continuation —
   consistent with "top-level steps inherit from the invoking session, captured
   at invoke time." Reuse of the original snapshot would require threading the
   prior run's snapshot through `continue-terminal-run-async!`, which holds only
   `mutate!`/`run-id`/`session-id` and intentionally re-invokes from the
   definition; that threading is explicitly **not** done.

6. **Purity boundary — impure resolution outside pure `create-run`.**
   `workflow-runtime.core/create-run` is the canonical *pure* root-state
   lifecycle op: it takes `state` (not `ctx`) and must remain free of ctx reads
   (`get-session-data`, `all-skills`, `agent-tool-source-in`,
   `list-context-sessions`). The inherited-default snapshot is therefore
   resolved **impurely by the caller** (the create-run invocation site) and
   passed as **already-resolved data** into `create-run`, which records it
   verbatim on the run's canonical state. `create-run` gains an optional
   `:inherited-defaults` snapshot in `opts`, persisted (like `:parent-session-id`)
   on the run when present; it performs no resolution. This preserves
   create-run's pure lifecycle contract and the one-way state boundary
   (ctx reads → resolved data → pure state transform). All current create-run
   sites already read `@(:state* ctx)` impurely before calling create-run
   (`psi_tool_workflow.clj`, `mutations/canonical_workflows.clj`,
   `statechart_runtime/delegate.clj`), so this places the snapshot resolution
   alongside the existing impure read at each site.

   6a. **Resolution site across the mutation hop — inside the mutation, not its
   `mutate!` callers.** There are exactly three sites that call
   `workflow-runtime/create-run` directly, and they are the snapshot-resolution
   sites:

   - `mutations/canonical_workflows.clj:96` — the `create-workflow-run`
     mutation (`psi.workflow/create-run`). It holds `agent-session-ctx`
     (`ctx` with `:state*`) and `session-id` (= `:parent-session-id`). The two
     upstream `mutate! 'psi.workflow/create-run` callers —
     `workflow/core.clj:382` (psi-tool delegate path) and
     `orchestration.clj:208` (`continue-terminal-run-async!`) — do **not** hold
     `ctx` and pass only `:definition-id`/`:workflow-input`/`:run-id`. The
     resolver (`resolve-inherited-defaults-snapshot ctx parent-session-id`)
     needs `ctx`+`parent-session-id`, both of which the mutation already has.
     Therefore the impure resolution for the mutation path lives **inside the
     `create-workflow-run` mutation** (resolving the snapshot from
     `agent-session-ctx`+`session-id` and passing it as `:inherited-defaults`
     into the pure `create-run`), **not** in the upstream `mutate!` callers.
     This keeps both `mutate!` callers unchanged and gives a single
     mutation-path resolution point, so the continuation path (Decision 5b)
     automatically captures a fresh snapshot for free.
   - `psi_tool_workflow.clj:148` — the psi-tool `create-run` op. It holds `ctx`
     and `session-id` (= `:parent-session-id`); it resolves the snapshot here
     and passes `:inherited-defaults` into `create-run`.
   - `statechart_runtime/delegate.clj:44` — the nested/delegated create-run
     site, governed by Decision 7 (effective-config → snapshot), not the
     top-level `resolve-inherited-defaults-snapshot` path.

7. **Single ownership of snapshot derivation.** `workflow-step-session-config`
   owns the resolution of inherited defaults from a session today; it is the
   single component that owns deriving the inherited-default snapshot. It
   exposes one function — `resolve-inherited-defaults-snapshot` (ctx,
   parent-session-id) → resolved snapshot map — built on the live-read logic
   `resolve-step-session-config` already uses for its no-override path
   (`get-session-data` → model/prompt-mode, `all-skills`, tool source + tool
   ids → tool-defs, thinking-level). It additionally reads `:speed-mode` and
   `:effort-override` from the parent session: those two are **not** part of
   `resolve-step-session-config`'s current reads or output (the resolver today
   emits only `:developer-prompt :prompt-mode :response-mode :tool-defs
   :thinking-level :skills :model`, plus optional temperature/model-fallback/
   logprob — it reads neither field). Consistent with Decision 1, which frames
   `:speed-mode`/`:effort-override` as recently introduced overrides layered on
   top of the live-inherited set, the snapshot resolver **adds** these two ctx
   reads; it does not reuse a no-override path that already includes them
   (there is none). The pure
   create-run sites call this resolver impurely (with the ctx they already hold)
   and pass the result into `create-run`. `workflow-runtime` does **not**
   `require` `workflow-step-session-config`: that reverse require is a **certain
   cycle**, because `workflow-step-session-config` already requires
   `workflow-runtime` (`deps.edn` declares `psi/workflow-runtime`; `core.clj:16/17`
   require `execution-adapter`/`statechart`). For the top-level capture sites the
   caller already lives in a component that depends on both, so it calls the
   resolver directly. For the **nested** delegated path, where the caller is
   `workflow-runtime`'s own `delegate.clj`, the snapshot resolver is reached via
   an **injected fn** (`resolve-inherited-defaults-fn`) passed into
   `delegate-step-runtime-result`, mirroring its existing injected
   `create-workflow-context-fn`/`send-and-drain-fn` params — `delegate.clj` does
   not require `workflow-step-session-config`, so no layering inversion / cycle.
   For a **nested** delegated run (Decision 3), the delegating step's *effective*
   config (run snapshot ⊕ step overrides) is the parent: `resolve-step-session-config`
   already produces that effective config for the step, and the same component
   derives the child run's snapshot from it (effective config + parent snapshot →
   snapshot map), so effective-snapshot derivation lives in exactly one component
   with no duplicated resolution logic. The injected fn (bound by the caller, which
   depends on both components) calls `resolve-step-session-config` then
   `effective-config->snapshot`; `delegate.clj`'s child create-run site passes the
   resulting effective snapshot rather than re-reading the invoking session.

   7a. **Nested-derivation entry point and signatures.**
   `workflow-step-session-config` exposes **two** snapshot functions, distinct
   by input:

   - `resolve-inherited-defaults-snapshot` — `(ctx parent-session-id) →
     snapshot-map`. The **top-level** path (Decisions 6, 6a). Performs the live
     ctx reads `resolve-step-session-config`'s no-override path already uses
     (`get-session-data` → model/prompt-mode, `all-skills`, tool source +
     tool-ids → tool-defs, thinking-level) **and** two reads that path does not
     have today — `:speed-mode` and `:effort-override` from the parent session
     — which this resolver adds (Decision 7 / Decision 1; the current resolver
     reads neither). Returns the resolved snapshot.
   - `effective-config->snapshot` — `(effective-config parent-snapshot) →
     snapshot-map`. The **nested** path. Pure projection; no ctx reads. The five
     resolver-emitted inherited keys (`:model :prompt-mode :tool-defs :skills
     :thinking-level`) come from the already-resolved effective step-config; the
     remaining two snapshot keys (`:speed-mode`/`:effort-override`) come from
     `parent-snapshot` (the parent run's `:inherited-defaults`), because
     `resolve-step-session-config` emits neither (resolved I1) — a single-arg
     projection would silently yield only 5 of the 7 snapshot keys and drop
     speed/effort under delegation. It is the single point that maps effective
     config + parent snapshot → snapshot, shared so the two paths cannot drift.

   `delegate.clj`'s `delegate-step-runtime-result` holds `ctx`,
   `parent-session-id`, `step-id`, `step-def`, and `workflow-run` but does **not**
   currently resolve the delegating step's effective config, and (per Decision 7)
   must not `require` `workflow-step-session-config`. The nested derivation
   therefore goes through an **injected fn** (`resolve-inherited-defaults-fn`)
   added to `delegate-step-runtime-result`'s param list alongside its existing
   injected `create-workflow-context-fn`/`send-and-drain-fn`. The caller — which
   depends on both components — binds it to a closure that first calls
   `resolve-step-session-config` (`ctx parent-session-id workflow-run step-id`),
   producing the step's effective config (run snapshot ⊕ step overrides), then
   calls `effective-config->snapshot` on that result **plus the parent snapshot**
   (`(:inherited-defaults workflow-run)`, supplying `:speed-mode`/
   `:effort-override`). `delegate-step-runtime-result` passes that injected fn's
   result as `:inherited-defaults` into the child `create-run` at
   `delegate.clj:44`. The injected fn does **not** call
   `resolve-inherited-defaults-snapshot` (that would re-read a live parent
   session and lose the step overrides); the effective config supplies the
   parent semantics required by Decision 3.

8. **Snapshot field-set authority — single source of truth.** The snapshot
   field set is **not** an independently hand-maintained list. The canonical
   inheritance field set is `session-state/init.clj`'s `common-inherited-fields`
   (which already includes `:speed-mode` and `:effort-override`). The workflow
   snapshot field set is **derived from / validated against** that authority:
   `common-inherited-fields` is promoted to a public (non-`^:private`) var (or
   re-exported through a small accessor) so `workflow-step-session-config` can
   reference it rather than re-enumerate keys, and a test asserts the workflow
   snapshot's resolved-field set matches the canonical inheritance set (modulo
   the documented resolved-vs-raw shape difference: the snapshot stores resolved
   `:tool-defs`/`:skills` where the raw inheritance set stores `:tool-ids`/
   `:skill-ids`). This keeps the two inheritance field lists from drifting.

   8a. **Exact snapshot field set as a named subset, with the
   model/thinking-level gap made explicit.** `common-inherited-fields`
   (`init.clj:30`) holds 19 fields and is a *child-session-init* concern
   (capability membership, preferences, UI, runtime telemetry). It does **not**
   include `:model` or `:thinking-level` — those live in the separate
   `model-identity-fields` constant (`init.clj:67`). The workflow snapshot is a
   narrower *resolved-default* set: the fields a step inherits when it gives no
   override of its own. The snapshot is therefore **not** all of
   `common-inherited-fields`; it is an explicitly named set spanning two
   authorities:

   - **From `common-inherited-fields`** (a named subset, by their raw keys):
     `:prompt-mode`, `:speed-mode`, `:effort-override`, plus the
     resolved-vs-raw pair `:tool-ids` → resolved `:tool-defs` and `:skill-ids`
     → resolved `:skills`.
   - **From `model-identity-fields`**: `:model`, `:thinking-level`.

   So the snapshot's seven resolved keys are
   `{:model :prompt-mode :tool-defs :skills :thinking-level :speed-mode
   :effort-override}`, mapping to authority keys
   `{:model :prompt-mode :tool-ids :skill-ids :thinking-level :speed-mode
   :effort-override}`.

   The remaining 14 `common-inherited-fields` entries (19 total minus the 5
   included raw keys `:prompt-mode :speed-mode :effort-override :tool-ids
   :skill-ids`) are **deliberately excluded** from the workflow snapshot:
   capability membership beyond
   tools/skills (`:prompt-contribution-ids`, `:prompt-templates`, `:extensions`),
   the remaining preferences (`:auto-retry-enabled`, `:auto-compaction-enabled`,
   `:nucleus-prelude-override`, `:developer-prompt`,
   `:developer-prompt-source`, `:cache-breakpoints`, `:scoped-models`,
   `:tool-output-overrides`), `:ui-type`, and the runtime telemetry fields
   (`:context-tokens`, `:context-window`). These are not part of the
   per-step inherited-default that `resolve-step-session-config` overrides
   today, so they are out of scope for this snapshot (Decision 1 governs only
   the inherited defaults the resolver reads live).

   **Validation against the authority** (not a hand-maintained parallel list):
   `common-inherited-fields` (and, if needed, `model-identity-fields`) is
   promoted to a public var/accessor. A named constant in
   `workflow-step-session-config` declares the snapshot's source keys
   (`{:from-common #{:prompt-mode :speed-mode :effort-override :tool-ids
   :skill-ids} :from-model #{:model :thinking-level}}`), and a test asserts
   the invariant: every `:from-common` key ∈ `common-inherited-fields`, every
   `:from-model` key ∈ `model-identity-fields`, and the snapshot's resolved
   keys equal the declared source keys with `:tool-ids`→`:tool-defs` and
   `:skill-ids`→`:skills` substituted. Drift (a key disappearing from either
   authority, or a snapshot key without an authority source) fails the test.

## Acceptance criteria

1. After a workflow is invoked, switching the invoking session's model has **no
   effect** on the session details of subsequent steps of that running workflow.
2. After a workflow is invoked, changing the user/project default model has **no
   effect** on subsequent steps of that running workflow.
3. The same invariant holds for **every** inherited default — `prompt-mode`,
   `tools`, `skills`, `thinking-level`, `speed-mode`, `effort-override` — not
   just `model`.
4. A nested/delegated workflow inherits the **effective** session details of its
   delegating step (run snapshot plus that step's overrides), captured when the
   sub-delegation is created — not the (possibly-since-mutated) invoking session.
   A step that overrides the model and then delegates: the sub-delegation sees
   the overridden model.
5. A step that specifies an explicit override still applies that override
   (snapshot governs only inherited defaults).
6. For the no-mutation case, existing single-step and multi-step config
   resolution behaviour is unchanged.
7. `resolved-model-query` selection context comes from the snapshot's effective
   model.
8. Resuming a blocked run reuses the original invoke-time snapshot (no
   re-capture).
9. The captured snapshot(s) are part of the workflow run's state (deterministic
   / replayable), consistent with the canonical-workflow architecture.
