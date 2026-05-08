# 117 — Session persistence component extraction

## Goal

Extract canonical session journal and persistence ownership into a lower component so authoritative journal entry construction, canonical journal read/append helpers, flush-state handling, persisted session file loading/listing, and the persistence-owned state-path surface no longer live primarily under `agent-session`.

## Why

Recent registry and subsystem extractions clarified a useful decomposition rule:

- lower components own canonical data-model and runtime boundary semantics
- higher layers keep orchestration, dispatch registration, and multi-domain policy

`psi.agent-session.persistence` now looks structurally like a lower component already:

- it depends downward on `psi.session-journal.store`
- it depends downward on `psi.session-state.model`
- it depends downward on `psi.session-state.state`
- it does not depend on workflows, extensions, auth, prompt assets, tools, adapters, or UI

At the same time, `psi.turn` still depends directly on `psi.agent-session.persistence` for journal entry helpers and journal message projection. That is a real blocker to further turn extraction.

The current shape also reveals an ownership mismatch:

- persistence semantics are implemented in `psi.agent-session.persistence`
- but the canonical journal/flush-state paths currently live in `psi.session-state.state`
- session initialization helpers above the boundary manually construct persistence state maps

That split makes the lower persistence boundary less explicit than it should be.

## Problem

Session persistence ownership is currently split across several layers:

1. `components/agent-session/src/psi/agent_session/persistence.clj`
   - journal entry constructors
   - ctx-based journal read helpers
   - append-and-persist behavior
   - flush-state handling
   - wrappers over on-disk journal store operations
2. `components/session-state/src/psi/session_state/state.clj`
   - canonical path builders for:
     - `:journal`
     - `:flush-state`
   - journal append helper rooted in those paths
3. `components/agent-session/src/psi/agent_session/session_runtime.clj`
   - `persistence-state` constructor for runtime session initialization
4. `components/agent-session/src/psi/agent_session/child_session_state.clj`
   - direct manual initialization of `:persistence {:journal ... :flush-state ...}`
5. `components/agent-session/src/psi/agent_session/dispatch_effects.clj`
   - persistence append effects routed to the current `agent-session.persistence` owner
6. multiple higher-level consumers across turn, workflow, app-runtime, session lifecycle, scheduler, and tests

Without an extracted lower owner:

- `agent-session` continues to own a broadly shared journal/persistence API surface
- `psi.turn` and other lower-ish domains keep depending back up on `agent-session`
- persistence state shape and path ownership remain split between `session-state` and `agent-session`
- child/runtime session initialization keeps reconstructing persistence-owned state manually instead of delegating to the canonical persistence owner

## Intent

Create one explicit lower-level component for canonical session persistence ownership.

This component should own:

- canonical journal entry constructors
- canonical ctx-based journal read helpers
- canonical ctx-based journal append/persist helpers
- flush-state data shape and helpers
- persisted session file path helpers, file writing, file loading, and persisted session listing wrappers over `session-journal.store`
- the canonical persistence state-path surface currently exposed out of `session-state.state`
- canonical persistence-state initialization helpers used by top-level and child session creation

This component should not own:

- broader session lifecycle orchestration
- dispatch registration or effect schema ownership
- session state root map ownership as a whole
- prompt preparation or turn orchestration
- compaction policy
- workflow/session branching policy
- app-runtime projections
- extension install persistence

## Proposed boundary

### New component responsibility

Create a lower `session-persistence` component.

Representative namespace shape for the first cut:

- authoritative public namespace: `psi.session-persistence.core`
  - public journal entry constructors
  - canonical ctx-based append/read helpers
  - flush-state helpers
  - persistence-state initialization helpers
  - canonical journal/flush-state path helpers
  - wrappers over file-backed journal-store helpers
- optional internal support namespace: `psi.session-persistence.paths`
  - allowed only if implementation wants internal organization
  - must not become a second competing public entrypoint in the first cut

First-cut namespace/API decision:

- `psi.session-persistence.core` is the single canonical public entrypoint for the extracted component
- callers should not need to guess between `core` and `paths` for authoritative usage
- if a support namespace exists, it is internal structure rather than a second public surface

Settled first-cut public API names:

- canonical path helpers:
  - `session-journal-path`
  - `session-flush-state-path`
- canonical primitive subtree constructors:
  - `flush-state`
  - `persistence-state`
- canonical ctx-based append/persist helpers:
  - `append-journal-entry-in!`
  - `persist-journal-in!`
- canonical ctx-based read helpers preserve current names:
  - `all-entries-in`
  - `entries-of-kind-in`
  - `entries-up-to-in`
  - `last-entry-of-kind-in`
  - `messages-from-entries-in`
  - `messages-up-to-in`
- disk/store wrappers preserve current names:
  - `session-dir-for`
  - `new-session-file-path`
  - `write-header!`
  - `append-entry-to-disk!`
  - `flush-journal!`
  - `load-session-file`
  - `find-most-recent-session`
  - `list-sessions`
  - `list-all-sessions`
- semantic journal entry constructors preserve current names:
  - `message-entry`
  - `thinking-level-entry`
  - `model-entry`
  - `compaction-entry`
  - `branch-summary-entry`
  - `custom-message-entry`
  - `label-entry`
  - `session-info-entry`

Compatibility naming rule for the first cut:

- current names such as `append-entry-in!` and `persist-entry-in!` may exist temporarily as delegating compatibility seams during migration
- current names in `psi.agent-session.persistence` and `psi.session-state.state` may forward to the new owner during migration only
- task closure should leave `append-journal-entry-in!` and `persist-journal-in!` as the authoritative public names for the new component

### Concrete extraction mapping

#### Move from `agent-session.persistence`

Move or re-home the following responsibilities into the new lower component under the settled first-cut API names:

- disk wrappers:
  - `session-dir-for`
  - `new-session-file-path`
  - `write-header!`
  - `append-entry-to-disk!`
  - `flush-journal!`
  - `load-session-file`
  - `find-most-recent-session`
  - `list-sessions`
  - `list-all-sessions`
- in-memory / ctx journal helpers:
  - `create-journal`
  - `append-entry!`
  - `all-entries`
  - `entries-of-kind`
  - `entries-up-to`
  - `last-entry-of-kind`
  - `messages-from-entries`
  - `messages-up-to`
  - canonical ctx append helper: `append-journal-entry-in!`
  - `all-entries-in`
  - `entries-of-kind-in`
  - `entries-up-to-in`
  - `last-entry-of-kind-in`
  - `messages-from-entries-in`
  - `messages-up-to-in`
- flush-state helpers:
  - `flush-state`
  - `create-flush-state` as a compatibility/test-oriented helper if still needed after extraction
  - `persist-state-entry!`
  - `persist-entry!`
  - canonical ctx persist helper: `persist-journal-in!`
- entry constructors:
  - `message-entry`
  - `thinking-level-entry`
  - `model-entry`
  - `compaction-entry`
  - `branch-summary-entry`
  - `custom-message-entry`
  - `label-entry`
  - `session-info-entry`

Public-surface decision for atom-oriented helpers:

- ctx-based helpers are the canonical public API of the extracted component
- atom-oriented helpers may remain public in the first cut for compatibility and focused tests
- however, they should be documented in code/comments as secondary compatibility/testing helpers rather than the preferred runtime surface
- the extraction should not let these secondary helpers obscure the canonical ctx-based ownership boundary

Settled entry-constructor ownership decision:

- `psi.session-state.model` should remain the owner of entry schema and the primitive `make-entry` helper
- the extracted persistence component should become the owner of semantically named journal-entry constructors built on top of that primitive
- callers that want a typed session journal entry should use the persistence component rather than reaching to `session-state.model/make-entry` directly

#### Move persistence path ownership out of `session-state.state`

Current `session-state.state` owns these persistence-specific path builders:

- `session-journal-path`
- `session-flush-state-path`
- `state-path` registrations for:
  - `:journal`
  - `:flush-state`

First-cut boundary decision:

- these paths should become persistence-owned, not generic session-state-owned
- the extracted persistence component should expose canonical path helpers for journal and flush-state
- `session-state.state` should stop being the authoritative home for persistence-specific path builders
- first-cut compatibility is allowed to be temporary only: if any `session-state.state` path indirection remains for `:journal` or `:flush-state`, it must be a short delegating compatibility seam and not the authoritative implementation

Settled path contract for task closure:

- authoritative callers should use `psi.session-persistence.core` path helpers directly
- `psi.session-state.state/state-path` may temporarily delegate `:journal` and `:flush-state` during migration
- task closure should remove direct production dependence on `session-state.state/state-path` for persistence-specific keys unless one narrow compatibility consumer remains explicitly justified in `implementation.md`

The intended direction is:

- session-state owns generic session data / telemetry / hierarchy paths
- session-persistence owns persistence-specific state layout beneath the session root

#### Move persistence state initialization ownership

Current initialization ownership is split:

- `session-runtime/persistence-state` constructs `{:journal [] :flush-state {:flushed? ... :session-file ...}}`
- `child-session-state/initialize-child-session-state` manually constructs the same shape

First-cut boundary decision:

- the extracted persistence component should provide canonical constructors for persistence slots/state
- top-level session initialization and child-session initialization should delegate to those constructors
- no higher layer should manually rebuild the canonical persistence map shape once the component exists

Settled initialization-scope decision:

- the first cut must own the primitive canonical persistence subtree constructors
- the first cut does not need to own every scenario-specific session lifecycle constructor
- higher layers may still decide when to use preloaded entries, persistent session files, or resumed/forked session metadata
- but they must obtain the canonical subtree shape from the persistence component rather than rebuilding raw maps inline

Representative helper shape:

- `flush-state`
- `persistence-state`
- `persistence-state` should be opts-driven in the first cut, supporting the primitive subtree inputs needed by higher layers such as `:journal`, `:session-file`, and `:flushed?`

These names are now settled for the first cut; ownership should move downward with those helpers.

### Responsibilities that remain outside the new component

#### `session-state`

`session-state` should remain the owner of:

- generic session root-state reads/writes
- generic session data path helpers
- session hierarchy helpers
- generic root-state update helpers

Boundary rule:

- session-state owns the canonical root map and generic mutation primitives
- session-persistence owns the persistence subtree shape, path helpers, and persistence operations over that subtree

Settled append-primitive decision:

- the canonical journal append primitive should move under the extracted persistence component as `append-journal-entry-in!`
- any remaining `session-state.state/append-journal-entry-in!` helper should be treated as a temporary delegating compatibility seam rather than the authoritative owner
- task closure should leave production callers using `psi.session-persistence.core/append-journal-entry-in!` rather than the session-state helper directly

#### `agent-session`

`agent-session` should remain the owner of:

- when sessions are created, resumed, forked, or closed
- when journal entries are appended as part of higher-level workflows
- dispatch effect registration and higher-level event orchestration
- session lifecycle policy for model/thinking/session-info journaling

Boundary rule:

- `agent-session` decides when to append or load
- `session-persistence` owns how journal state and persisted session files are represented and manipulated

Clarification on persisted-session discovery/listing ownership:

- persisted session loading and listing are part of persistence ownership in this task
- the extracted component should remain the authoritative owner of `load-session-file`, `find-most-recent-session`, `list-sessions`, and `list-all-sessions`
- higher layers may project or consume those results, but should not become the owner of persisted-session discovery semantics

#### `dispatch-effects`

The dispatch effect registration surface may remain in `agent-session` for now.

First-cut rule:

- `:persist/...` effect execution may continue to be dispatched from `agent-session.dispatch-effects`
- but it should delegate downward into the extracted persistence component as the canonical owner
- this task should not expand into a global dispatch/effect ownership redesign

Compatibility-exit decision:

- temporary compatibility wrappers are allowed during migration only where they reduce change risk
- task closure should remove temporary production compatibility wrappers unless a remaining wrapper is explicitly justified in `implementation.md`
- in particular, this task should prefer ending with direct production dependencies on `psi.session-persistence.core` rather than preserving broad `psi.agent-session.persistence` or `psi.session-state.state` compatibility surfaces indefinitely

## Main design decisions

### 1. Make persistence a true lower component

This extraction should intentionally reduce upward dependencies from lower domains such as `psi.turn`.

A successful extraction means journal helpers and entry constructors are no longer owned by `agent-session`.

### 2. Move path ownership with the component

This task should not only move helper functions while leaving persistence-specific path ownership behind in `session-state.state`.

The persistence component should own:

- where the journal lives
- where flush-state lives
- how persistence slots are initialized

This is a key part of making the boundary real.

### 3. Preserve live semantics first

The first cut should preserve current behavior intentionally, including:

- lazy first-assistant flush behavior
- append-after-flush behavior
- persisted session header shape and migration behavior via `session-journal.store`
- ctx-based append semantics: append in memory first, then persist if applicable
- current session file listing/loading semantics
- current entry constructor shapes

This task is about ownership extraction, not behavior redesign.

### 4. Keep `session-journal.store` as the lower disk substrate

This task should not collapse file codec/store logic back upward.

Expected layering after extraction:

- `session-journal.store` owns raw file codec/store behavior
- `session-persistence` owns canonical session-facing journal/persistence semantics
- `agent-session` owns orchestration over that component

## Current likely extraction points

Primary current implementation points:

- `components/agent-session/src/psi/agent_session/persistence.clj`
- `components/session-state/src/psi/session_state/state.clj`
  - persistence-specific path helpers only
- `components/agent-session/src/psi/agent_session/session_runtime.clj`
  - `persistence-state`
- `components/agent-session/src/psi/agent_session/child_session_state.clj`
  - manual persistence subtree construction
- `components/agent-session/src/psi/agent_session/dispatch_effects.clj`
  - persistence effect delegation

Representative production consumers to update toward the extracted owner:

- `components/agent-session/src/psi/turn.clj`
- `components/agent-session/src/psi/agent_session/runtime.clj`
- `components/agent-session/src/psi/agent_session/session_lifecycle.clj`
- `components/agent-session/src/psi/agent_session/workflow_judge.clj`
- `components/app-runtime/src/psi/app_runtime/session_summary.clj`
- `components/app-runtime/src/psi/app_runtime/selectors.clj`

Consumer-migration decision:

- this task should update real production consumers to depend directly on `psi.session-persistence.core`
- temporary compatibility requires are acceptable only during migration and should not remain the dominant production pattern at task closure
- the closure target is that `psi.agent-session.persistence` is no longer the primary required surface for active production code

Likely test surfaces affected:

- `components/agent-session/test/psi/agent_session/persistence_test.clj`
- `components/agent-session/test/psi/agent_session/journal_append_convergence_test.clj`
- turn/workflow/session-lifecycle tests using persistence helpers
- app-runtime projection tests using message/journal helper constructors
- possibly new focused component-local tests in the extracted component

Testing-scope clarification:

- this task should update tests as needed to follow the new component boundary
- it does not need to reshape every persistence-using test away from existing seam styles unless the extraction itself requires that change
- test-boundary cleanup beyond what is needed to land the extraction can remain a later follow-on

## Suggested implementation shape

1. create `components/session-persistence/`
2. define the canonical public API and state-path ownership of the new component
3. move `psi.agent-session.persistence` logic into the extracted namespace family
4. move persistence-specific path helpers out of `psi.session-state.state`
   - either remove them entirely from `session-state.state`
   - or leave short delegating compatibility wrappers during migration
5. move canonical persistence-state initialization helpers out of `session-runtime` / `child-session-state` and into the new component
6. update `dispatch-effects` to delegate persistence effects downward to the extracted component
7. update `psi.turn`, app-runtime, workflow judge, session lifecycle, and other production consumers to depend on the new component
8. add or move focused component-local tests for persistence behavior, path ownership, and initialization helpers
9. preserve behavior with focused regression checks for:
   - lazy flush
   - append after flush
   - in-memory append first
   - session listing/loading
   - child/top-level persistence slot initialization
10. remove temporary compatibility wrappers if used during migration
11. record final path-ownership and initialization-ownership decisions in `implementation.md`

## Acceptance

- a new lower component exists for canonical session persistence ownership
- authoritative journal entry constructors, ctx-based journal helpers, and flush-state helpers no longer live primarily under `agent-session`
- persistence-specific canonical state paths for journal and flush-state are owned by the new persistence component rather than primarily by `psi.session-state.state`
- top-level and child-session persistence slot initialization delegate to the extracted persistence component rather than constructing the persistence subtree manually
- `agent-session.dispatch-effects` remains, if needed, as an adapter/orchestration seam but delegates persistence work downward to the extracted owner
- `psi.turn` no longer depends on `psi.agent-session.persistence`
- behavior is preserved for:
  - journal append
  - lazy flush on first assistant message
  - persisted session listing/loading
  - session fork/resume persistence behavior, including the current parent-lineage/session-file/header invariants that existing focused tests prove

- task `105-agent-session-component-extraction-map` can reference this as the persistence/journal extraction seam becoming explicit

## Related work

- `098-journal-append-dispatch-effect-convergence` already established the generic append effect and pure in-memory append-first behavior
- `099-session-journal-codec-and-store-extraction-from-agent-session` extracted the lower file codec/store substrate into `session-journal`
- `097-session-state-component-extraction-from-agent-session` established `session-state` as a lower owner of generic session state infrastructure
- `105-agent-session-component-extraction-map` identifies persistence/journal as an extractable component candidate
- this task is a useful dependency-reduction move for the broader `psi.turn` extraction path because it removes a current direct `agent-session` dependency from `psi.turn`
