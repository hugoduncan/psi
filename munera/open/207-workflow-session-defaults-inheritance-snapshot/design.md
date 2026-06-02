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

Out of scope (proposed — confirm):

- Changing how a step's **explicit** overrides work (`:session` spec /
  `:workflow-file-meta` model / model-query / tools / skills / thinking-level).
  Snapshotting governs only the *inherited default* used when a step does not
  specify its own value.
- Changing user/project default-model resolution itself.
- Retroactively snapshotting already-created runs (forward-looking only).

## Open questions (to refine collaboratively)

1. **Which details are "default session details"?** Candidates inherited live
   today: `model`, `prompt-mode`, `skills`, `tools` (tool source + tool-ids),
   `thinking-level` fallback. Snapshot all of them, or only `model`? The example
   names model explicitly; a consistent rule probably snapshots the whole
   inherited-default set, but this needs a decision.

2. **Snapshot granularity / shape.** A resolved snapshot (e.g. concrete
   `{:model … :prompt-mode … :tool-defs … :skills … :thinking-level …}`) versus
   a lighter snapshot of just the parent session's raw default fields. Resolved
   is more robust against later parent mutation; raw is smaller. Leaning
   resolved.

3. **Nested workflow parent semantics.** Confirm a delegated sub-workflow
   inherits from the *parent workflow run's snapshot* (so the whole tree shares
   the invoke-time defaults) rather than re-reading the invoking session. Is the
   parent a workflow-run snapshot, or the parent step's child session?

4. **model-query context.** `resolved-model-query` uses `parent-session-model`
   as selection context. Should it use the snapshot's model (preferred for
   determinism) — confirm.

5. **Interaction with continue/resume.** On resume of a blocked run, defaults
   should still come from the original invoke-time snapshot (not re-captured).
   Confirm.

## Acceptance criteria

1. After a workflow is invoked, switching the invoking session's model has **no
   effect** on the session details of subsequent steps of that running workflow.
2. After a workflow is invoked, changing the user/project default model has **no
   effect** on subsequent steps of that running workflow.
3. A nested/delegated workflow inherits the invoke-time default session details
   of its parent, not the (possibly-since-mutated) invoking session.
4. A step that specifies an explicit model/tools/skills/thinking-level override
   still applies that override (snapshot governs only inherited defaults).
5. For the no-mutation case, existing single-step and multi-step config
   resolution behaviour is unchanged.
6. The captured snapshot is part of the workflow run's state (deterministic /
   replayable), consistent with the canonical-workflow architecture.
