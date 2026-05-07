# 100 — Turn statechart component extraction

## Goal

Extract `psi.agent-session.turn-statechart` into a separate component so the per-turn stream-assembly statechart no longer lives under `agent-session`.

## Why

`psi.agent-session.turn-statechart` is the cleanest currently identified low-level turn namespace:
- it owns one coherent concern: per-turn provider-stream assembly state
- it already has a narrow, explicit API
- it depends on Fulcro statechart libraries, not on session dispatch, persistence, extensions, workflows, or adapter code
- it is currently used by higher-level prompt/runtime/telemetry code that should depend on a turn-owned lower component rather than on `agent-session` ownership

Extracting this namespace first creates a small, low-risk foothold toward a later broader turn component extraction.

## Problem

The per-turn statechart currently lives under `components/agent-session/` even though it is lower-level than most `agent-session` responsibilities.

This causes three ownership problems:
- it makes `agent-session` appear to own generic turn-stream state machinery
- it keeps later turn extraction work coupled to `agent-session` unnecessarily
- it blurs the dependency slope between lower-level turn mechanics and higher-level prompt/session orchestration

## Scope

In scope:
- create a new separate component for the turn statechart
- move the authoritative implementation of `psi.agent-session.turn-statechart` into that component
- preserve current API behavior; the old namespace may exist only as a temporary compatibility shim during migration and must not remain the authoritative namespace at completion
- update direct consumers to depend on the extracted component boundary
- keep behavior unchanged
- move the focused statechart test file with the component and keep focused proof green at the new boundary
- document the ownership change in task notes if any namespace remains as a compatibility facade during migration

Out of scope:
- extracting `psi.agent-session.turn-accumulator`
- extracting `psi.turn` as a whole
- changing turn semantics, prompt semantics, or streaming behavior
- redesigning statechart events or working-memory shape
- broader prompt lifecycle or workflow cleanup

## Boundary

This task is only about the namespace/component that owns:
- `create-turn-data`
- the turn streaming chart definition
- turn-context creation and event sending
- turn-phase / turn-data query helpers
- the helper(s) currently defined in `turn_statechart.clj`, including `make-accumulation-actions`, moving unchanged in this slice

This task is not about moving higher-level orchestration that currently consumes the statechart.

## Target shape

The extracted component should become the authoritative home of the current turn statechart namespace contents.

Chosen target for this task:
- component path: `components/turn-statechart/`
- namespace family: `psi.turn-statechart.*`
- first-cut authoritative namespace: `psi.turn-statechart.core`
- first-cut source file: `components/turn-statechart/src/psi/turn_statechart/core.clj`

First-cut expectations:
- there is one new component path dedicated to this lower-level turn statechart
- the component dependency slope remains one-way: higher-level `agent-session` code depends on the extracted component
- the namespace must be renamed to match the new component ownership rather than retaining an `agent-session` namespace under a non-`agent-session` component
- the migration must leave one obvious authoritative owner and avoid introducing cycles

## Acceptance

- a separate component exists for the turn statechart
- the authoritative implementation no longer resides under `components/agent-session/`
- the authoritative namespace name matches the new component name/path
- no new component cycle is introduced
- all direct consumers compile against the extracted component
- focused turn-statechart verification remains green from the new component test location
- at least one consuming path in higher-level code still works unchanged in behavior
- any compatibility namespace is used only as a migration path and is removed before task completion

## Concrete done criteria

- the task records the chosen component path explicitly as `components/turn-statechart/`
- the extracted authoritative namespace is renamed explicitly to `psi.turn-statechart.core`
- `psi.agent-session.turn-statechart` is removed, or exists only temporarily as a migration shim during the work and is removed before completion
- the canonical statechart implementation has one obvious owner after the move
- all existing direct consumers are updated to require the extracted namespace in this slice
- focused statechart tests move with the component and pass from the new component test location
- no prompt-lifecycle or turn-accumulator behavior changes are required beyond import/ownership adjustments

## Constraints

- prefer the smallest viable extraction slice
- preserve current API and behavior unless a rename is required by the component boundary
- avoid widening this task into turn-accumulator or prompt-lifecycle extraction
- keep the result easy to explain: lower-level turn stream state machinery is below `agent-session`; higher-level prompt/session orchestration stays above it

## Related work

- task `094-prompt-lifecycle-component-extraction` extracted the broader turn lifecycle ownership surface into `psi.turn`
- task `095-abstract-state-kernel-extraction-from-agent-session` extracted the lower generic dispatch substrate
- task `097-session-state-component-extraction-from-agent-session` extracted lower-level session state substrate
- this task is a narrower follow-on aimed at the cleanest remaining low-level turn namespace
- a later follow-on may extract `psi.agent-session.turn-accumulator`, but that is explicitly not part of this slice
