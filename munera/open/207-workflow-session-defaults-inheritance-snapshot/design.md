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
