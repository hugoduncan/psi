# 150 — explicit runtime-vs-persisted session graph surface

## Goal

Make the graph surface encode the distinction between in-memory runtime sessions and persisted on-disk sessions directly in attribute ids and resolver names, so callers do not need prompt conventions or operator memory to choose the correct session surface.

## Why

The current session graph mixes two different concepts under names that are individually reasonable but collectively ambiguous:

- in-memory/runtime session state is exposed through `:psi.agent-session/context-sessions`, `:psi.agent-session/context-session-count`, and `:psi.agent-session/active-session-id`
- persisted/on-disk session discovery is exposed through `:psi.session/list` and `:psi.session/list-all`

That naming split reflects implementation history more than user meaning. `context` vs `list` does not make the storage boundary obvious, and a caller can easily mistake persisted-session discovery for the current runtime context.

This is a correctness problem, not just a docs problem. Destructive or administrative session operations should be guided by an obvious graph surface where runtime-vs-persisted intent is visible at the call site.

## Problem

Today the authoritative graph attrs do not consistently answer the first question a caller needs:

- am I operating on loaded in-memory runtime sessions?
- or am I operating on persisted session files discoverable from disk?

Because that distinction is not encoded clearly enough in the attr ids and resolver names, tools and operators can choose the wrong surface even when they are using the graph correctly.

## Intent

Introduce explicit session-surface naming in the graph so runtime sessions and persisted sessions are visibly different by default.

The task should:

- add explicit attr ids whose names encode `runtime` vs `persisted`
- add or rename resolver owners so resolver names also encode the distinction
- preserve current behaviour and data shapes unless a shape change is required for clarity
- provide a compatibility path for existing attrs long enough to migrate internal callers and documentation safely
- update authoritative graph-facing docs/tests so the explicit names become the preferred surface

The task should not:

- redesign session persistence
- redesign session lifecycle semantics
- broaden into a generic naming cleanup across unrelated graph surfaces
- require immediate removal of all current attrs if compatibility aliases are the safer migration path

## Naming decision

The graph should expose two clearly named domains, and this task fixes that choice now rather than leaving it open.

### Runtime / in-memory session surface

Chosen attr ids:

- `:psi.runtime-session/list`
- `:psi.runtime-session/count`
- `:psi.runtime-session/active-id`

Decision:

- use the dedicated `psi.runtime-session/*` domain
- do not use a longer `psi.agent-session/runtime-*` form as the new preferred surface

Why this choice:

- `runtime-session` states the storage/operational boundary directly at the call site
- it avoids overloading `psi.agent-session/*`, which already mixes broader session-oriented concepts
- it forms the cleanest contrast with the persisted surface below
- it keeps the preferred public graph naming short enough to become the obvious default in handwritten queries

The older attrs remain available only as compatibility surfaces during migration.

### Persisted / on-disk session surface

Chosen attr ids:

- `:psi.persisted-session/list`
- `:psi.persisted-session/list-all`

Optional follow-on only if it falls out naturally from the same owners:

- `:psi.persisted-session/count`
- `:psi.persisted-session/all-count`

Decision:

- use the dedicated `psi.persisted-session/*` domain
- do not introduce `psi.session-store/*` in this task

Why this choice:

- `persisted-session` names the semantic thing the caller is operating on rather than the storage implementation owner
- it pairs directly and readably with `runtime-session`
- it avoids forcing callers to think in terms of a lower implementation concept like `store` when the real distinction is loaded runtime state vs persisted session artifacts

This task does not require introducing every possible persisted-session aggregate if the current authoritative behaviour is still the two list surfaces.

## Resolver naming intent

Resolver names should match the same distinction rather than preserving ambiguous names such as `session-list-resolver`.

Chosen resolver naming direction:

- runtime session resolver names explicitly include `runtime-session`
- persisted session resolver names explicitly include `persisted-session`
- active-session resolver names explicitly include `runtime` when they refer to the loaded context rather than persisted discovery

Preferred examples:

- `runtime-session-list-resolver`
- `runtime-active-session-id-resolver`
- `persisted-session-list-resolver`
- `persisted-session-list-all-resolver`

Exact namespace-local symbol spelling may vary, but the storage boundary must be obvious from resolver names during maintenance and graph introspection.

## Behavioural requirements

### Runtime session surface

The explicit runtime attrs must describe the currently loaded in-memory runtime context, not persisted journal files.

At minimum:

- runtime session list returns the same semantic set currently represented by `:psi.agent-session/context-sessions`
- runtime session count returns the same semantic count currently represented by `:psi.agent-session/context-session-count`
- runtime active id returns the same semantic value currently represented by `:psi.agent-session/active-session-id`

### Persisted session surface

The explicit persisted attrs must describe session journals discoverable from disk, not only currently loaded sessions.

At minimum:

- persisted session list preserves the current worktree-scoped persisted discovery semantics of `:psi.session/list`
- persisted session list-all preserves the current cross-project persisted discovery semantics of `:psi.session/list-all`

## Compatibility and migration

This task should prefer a staged migration rather than a flag day.

Required migration shape:

1. add the new explicit attrs
2. keep current attrs available as compatibility aliases or equivalent compatibility surfaces
3. migrate internal graph consumers, docs, and examples to the explicit attrs
4. add clear proof that the explicit attrs and compatibility attrs preserve the expected behaviour during the migration window
5. only consider removal of old attrs in a later dedicated cleanup task if that churn is still worthwhile

This task is successful even if the old attrs remain, as long as the new explicit attrs are authoritative and preferred.

## Scope

In scope:

- new explicit graph attrs for runtime session and persisted session surfaces
- resolver renames or new explicit resolver owners that encode the distinction
- compatibility support for existing attrs
- updates to graph-surface documentation/examples/tests to prefer the explicit attrs
- updates to any internal callers whose continued use of old names would undermine the clarity goal

Out of scope:

- changing the underlying persisted session storage format
- changing how sessions are created, resumed, or closed
- changing returned entity shapes beyond what is necessary to preserve or expose the explicit naming split
- a broad deprecation/removal sweep for every historical session attr in one task
- mirroring every existing runtime-oriented session helper attr into `:psi.runtime-session/*` in this task

### Explicit scope decision: compact summaries stay out of scope

` :psi.agent-session/context-session-summaries` remains the preferred compact operational summary surface from task 134 and does not gain a `:psi.runtime-session/*` counterpart in this task.

Why:

- the ambiguity this task is fixing is specifically the mixed naming of the currently ambiguous root inventory/count/active-id surfaces versus persisted listing surfaces
- `context-session-summaries` already encodes a compact operational summary rather than the broad inventory/listing concept that is colliding with persisted discovery
- adding a mirrored summary attr here would broaden the task from a targeted naming split into a larger runtime-domain replication pass

Constraint:

- implementation, docs, and tests for this task must treat `:psi.runtime-session/list`, `:psi.runtime-session/count`, and `:psi.runtime-session/active-id` as the full new preferred runtime naming surface for this slice
- any follow-on mirroring of compact summary surfaces belongs in a separate dedicated task if still needed after the naming split lands

## Design constraints

- graph naming must make runtime-vs-persisted choice obvious at the call site
- prefer additive compatibility-first migration over immediate breakage
- preserve current semantics for both surfaces unless an explicit design decision says otherwise
- keep the new names introspectable and unsurprising in `:psi.graph/root-queryable-attrs` and resolver-index output
- avoid relying on AGENTS/README/operator memory to encode the distinction when the graph can encode it directly
- during migration, graph discovery must still surface compatibility attrs only if the new explicit attrs are also present and documented as the preferred choice

## Discoverability contract during migration

Compatibility means queryability, not preferred discoverability.

Required contract for this task:

- the new explicit attrs must appear in `:psi.graph/root-queryable-attrs`
- old attrs may remain root-queryable during the migration window if the underlying resolver inputs still make them root-reachable
- if old attrs remain listed in `:psi.graph/root-queryable-attrs`, docs/examples/tests updated in this task must still teach the new explicit attrs as the preferred surface and must include proof that both old and new attrs remain queryable during migration
- this task does not require custom filtering or hidden-root machinery solely to remove compatibility attrs from introspection

Why this contract:

- `:psi.graph/root-queryable-attrs` is derived mechanically from the registered resolver surface, so keeping compatibility attrs queryable while adding explicit attrs naturally leaves both discoverable unless a special-case filtering layer is introduced
- introducing special-case discovery suppression here would broaden the task beyond naming clarity into introspection policy redesign
- making the preferred surface obvious through the new names plus migrated docs/examples/tests is sufficient for this slice while preserving compatibility safety

## Migration set required in this task

The minimum in-task migration set is fixed here so the new names become the practical default, not only a latent capability:

- Emacs `/resume` session discovery query in `components/emacs-ui/psi-session-commands.el`
- TUI `/resume` frontend query in `components/app-runtime/src/psi/app_runtime/tui_frontend_actions.clj`
- app-runtime resume selector shaping in `components/app-runtime/src/psi/app_runtime/ui_actions.clj`
- graph-facing resolver/graph tests that currently assert or teach the old attrs in `components/agent-session/test/psi/agent_session/resolvers_test.clj` and any companion graph-surface tests proving root discoverability
- graph/introspection documentation or examples that currently teach session discovery using the old attrs, if encountered in the same authoritative surfaces

Rules for this migration set:

- resume flows that intentionally browse persisted on-disk sessions must migrate from `:psi.session/list` to `:psi.persisted-session/list`
- proofs of the loaded runtime context must migrate to `:psi.runtime-session/*` where they are demonstrating active in-memory session state
- compatibility attrs may remain in place for callers not explicitly migrated in this task, but the above teaching/default surfaces must switch

## Key questions

1. Should the old attrs become thin aliases to the new resolvers, or should the new attrs be additional outputs from existing resolvers during migration?
2. Which internal call sites and docs most need migration in the same task to make the new names the obvious default in practice?

## Success criteria

This task is successful only if all of the following are true:

- there is an explicit graph naming split between runtime/in-memory sessions and persisted/on-disk sessions
- a caller can determine the storage boundary from attr ids alone at the call site
- resolver names also make the runtime-vs-persisted distinction obvious to maintainers
- current behaviour is preserved for both runtime and persisted session discovery surfaces
- compatibility exists for existing attrs during migration
- docs/examples/tests prefer the new explicit attrs
- graph introspection surfaces make the explicit attrs discoverable

## Acceptance

- a new Munera task exists for explicit runtime-vs-persisted session graph naming
- the task defines explicit attr-id direction for both runtime and persisted session surfaces
- the task requires resolver naming that preserves the same distinction
- the task scopes work to graph-surface clarity and migration, not broader session redesign
- the task prefers additive compatibility-first migration rather than immediate removal of historical attrs
- the task defines success in terms of obvious naming at the call site, not documentation-only guidance
