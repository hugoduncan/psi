# 113 — Command registration component extraction

## Goal

Extract extension-owned command registration into a lower component so canonical command registration, validation, lookup, and listing no longer live primarily inside `agent-session`'s broader extension registry/orchestration layer.

## Why

Recent registry extractions have clarified a useful decomposition pattern:

- `tool-registry` owns registered tool definition semantics
- `skill-registry` is shaping session-local registered skill semantics
- higher layers such as `agent-session` keep orchestration, dispatch, and side effects

Commands now appear to have the same missing lower seam.

Current command ownership is still mixed:

- extension API and mutation entrypoints register commands through `agent-session`
- canonical command registration and command listing/query helpers still live in `psi.agent-session.extensions`
- command consumers in slash-command parsing, RPC/UI listing, and extension activation depend on those ad hoc command-registry semantics indirectly

A lower command-registration owner would make this boundary explicit and parallel the landed `tool-registry` extraction.

## Problem

Command-related ownership is currently spread across layers:

1. extension-owned command registration state and dedup semantics
2. command-name validation rules and canonical stored command shape
3. command lookup/listing helpers used by higher-level command dispatch and discovery surfaces
4. extension mutation/API entrypoints that should remain higher-level seams rather than registry owners

The main current implementation surfaces are:

- `components/agent-session/src/psi/agent_session/extensions.clj`
  - `register-command-in!`
  - command lookup/listing helpers
- `components/agent-session/src/psi/agent_session/mutations/extensions.clj`
  - `psi.extension/register-command`
- `components/agent-session/src/psi/agent_session/extensions/api.clj`
  - `:register-command`
- higher-level command consumers such as:
  - `components/agent-session/src/psi/agent_session/commands.clj`
  - RPC command paths and tests
  - extension install/activation flows

Without an explicit extracted owner:

- `agent-session` still mixes registry-style command ownership with broader extension/runtime orchestration
- command semantics remain embedded in a large mixed-responsibility namespace
- future command work risks either widening `extensions.clj` further or re-solving the same boundary in multiple places

## Intent

Create one explicit lower-level component for extension-owned command registration and canonical command query semantics.

This component should own:

- canonical command registration-by-name semantics
- minimal command validation needed at registration time
- canonical command listing and lookup helpers over extension-registry state
- duplicate handling and first-registration-wins query semantics, if that is the current effective behavior
- command-name set queries used by higher-level consumers

This component should not own:

- command execution/dispatch policy
- slash-command parsing or routing behavior
- RPC/TUI/Emacs command-picker UI behavior
- extension API surface as a whole
- generic extension registry ownership beyond the command-specific slice
- tool, flag, shortcut, prompt-contribution, or deterministic-operation registration

## Proposed boundary

### In scope for extraction

Authoritative first-cut ownership should move below `agent-session` for:

- current command registration helpers in `psi.agent-session.extensions`
- current command query/listing helpers in `psi.agent-session.extensions`
- minimal command validation/normalization required to preserve current registration behavior

Expected first-cut component path:

- `components/command-registry/`

Expected first-cut authoritative namespace family:

- `psi.command-registry.registry`
- optionally `psi.command-registry.defs` only if a separate normalization layer proves useful during implementation

### Expected higher-level seams that remain above the boundary

These should remain outside the extracted component in the first cut:

- `psi.extension/register-command` mutation entrypoint
- `extensions/api.clj` `:register-command`
- command execution/routing behavior in `commands.clj`
- UI/RPC consumer behavior that chooses when/how commands are surfaced or invoked

Boundary rule:

- the extracted component owns registered command semantics
- `agent-session` and adapters continue to own command orchestration and invocation policy

## First-cut design decisions

### 1. Mirror the successful `tool-registry` shape where it fits

This should be a command-specific registry extraction, not a broad generic extension-registry redesign.

The goal is not to unify all extension registration into one new abstraction. The goal is to give commands one obvious lower owner.

### 2. Preserve extension-registry state shape initially

The first cut may operate directly on the current extension-registry state shape for command-specific operations.

This task does not require extracting a generic extension-registry substrate first.

### 3. Keep extension mutation/API seams above the boundary

`psi.extension/register-command` and the extension API `:register-command` should remain higher-level adapters in the first cut, delegating downward into the extracted component.

### 4. Preserve current duplicate/query semantics explicitly

The extracted component should preserve the current effective command semantics intentionally.

The currently observed live behavior is:

- registration stores commands by exact `:name` within one extension under that extension's command map
- re-registering the same command name within the same extension replaces the previously stored command map for that extension/name pair
- command-name queries return the set of all registered command names across all extensions
- full command listing returns one command per name across all extensions with first-extension-registration-wins semantics
- command lookup by name returns the first command found when scanning extensions in extension registration order

Those semantics should become explicit component-owned contract in this first cut.

This means the extracted component must make explicit and preserve:

- within one extension, duplicate registration by exact command name replaces the existing stored entry
- across extensions, duplicate command registration is allowed rather than rejected globally
- across extensions, `all-commands`/equivalent listing is first-registration-wins by extension registration order
- across extensions, direct lookup by exact command name is first-registration-wins by extension registration order
- command-name queries are set-like rather than ordered list projections
- the `all-commands`/equivalent returned vector should reflect first encounter order while scanning extensions in extension registration order

### 5. Minimal validation and identity policy

The first cut should settle command identity and minimal validation explicitly.

Command identity rule:

- command identity is exact `:name` string equality
- no extra canonicalization beyond the first-cut validation/normalization rules should be introduced unless needed to preserve current behavior

Minimal validation/normalization rule for the first cut:

- `:name` must be present
- `:name` must be a non-blank string
- registration validates but does not otherwise canonicalize command names or command maps in the first cut
- command names are stored and compared exactly as provided after validation
- no slash-prefix normalization should be introduced in this task
- therefore `"hello"` and `"/hello"` are distinct command names in the first cut
- no kebab-case or reserved-name policy should be introduced in this task unless the current live code already enforces it and focused review confirms it is part of the existing contract

Other required-field note for the first cut:

- `:name` is the only registry-required field for this extraction
- fields such as `:handler` and `:description` should be preserved when present, but this task should not introduce a broader command-completeness validation policy unless focused review shows the current contract already requires it
- higher-level command dispatch/invocation code may continue to impose stronger requirements than the registry boundary itself

Invalid registration behavior should also be made explicit in implementation and tests:

- the first cut should choose one explicit behavior and keep it local to the registry boundary
- chosen first-cut design preference: invalid command registration should throw structured `ex-info` rather than silently storing malformed command entries
- only preserve a different behavior if focused live-code review proves an already-established contract that should not be broken, and record that exception explicitly in `implementation.md`

### 6. Canonical stored command shape for the first cut

The first cut should preserve the current stored command-map shape as much as possible rather than introducing broad canonicalization.

Required first-cut rule:

- the extracted component must preserve incoming command maps and extra keys unless a tiny normalization step is necessary to maintain the current contract

Expected commonly relevant fields include:

- `:name`
- `:description`
- `:handler`
- any current extension/runtime metadata already carried by callers

Ownership/path note:

- if current storage/query behavior depends on extension ownership, the component may attach or project extension ownership at query time rather than mutating the stored command map broadly at registration time
- specifically, preserving the current `all-commands` behavior may require returning listed commands with projected extension ownership metadata while still keeping registration storage simple
- if extension ownership metadata is projected, the implementation should preserve the current key shape used by existing consumers rather than inventing a new naming variant in this task
- lookup/listing behavior should make it explicit whether projected ownership metadata is returned only on list queries or also on single-command lookup, and tests should pin that contract down

### 7. Keep command dispatch behavior out of scope

The extracted component should not absorb handler invocation or slash-command routing logic.

Its scope ends at registration/query semantics.

## Current live source/consumer inventory

These are the minimum known surfaces at task creation time and should be reevaluated during implementation.

### Current authoritative sources likely to move or split

- `components/agent-session/src/psi/agent_session/extensions.clj`

### Current higher-level registration entrypoints that should become adapters/seams

- `components/agent-session/src/psi/agent_session/mutations/extensions.clj`
- `components/agent-session/src/psi/agent_session/extensions/api.clj`

### Current production consumers of registered command semantics

- `components/agent-session/src/psi/agent_session/commands.clj`
- RPC/session command paths under `components/rpc/src/psi/rpc/session/`
- extension install/activation flows and related tests
- any command-listing/read helpers surfaced by resolvers or extension API state

### Current test surfaces likely affected

- command-related portions of `components/agent-session/test/psi/agent_session/extensions_test.clj`
- `components/agent-session/test/psi/agent_session/commands_test.clj`
- RPC tests that exercise extension-registered commands
- extension install/manifest activation tests that prove `:register-command`

### Registry API contract note

The design intentionally leaves exact function names open until implementation, but the first cut must make the API shape explicit in code/tests.

At minimum, the component API should make obvious:

- registration into the extension-owned command collection for one extension path
- lookup by exact command name across registered extensions
- set-like command-name query across registered extensions
- full command listing/query across registered extensions with first-registration-wins projection
- a clear registration result contract, with implementation/tests making explicit whether registration reports replacement and/or count information in addition to returning updated registry state

Lookup/query behavior should be nil-returning rather than exception-throwing for missing names unless live behavior review reveals an already-established stricter contract.

## Suggested implementation shape

1. create `components/command-registry/`
2. define the smallest useful canonical registry API for commands
   - expected first-cut helpers should cover:
     - registration by extension path + command map
     - command lookup by exact name
     - command-name set query
     - full command listing with first-registration-wins semantics
3. move or re-express command registration and lookup/listing helpers there
4. add focused component-local tests for registration/query semantics first
   - include explicit proofs for:
     - same-extension replacement on duplicate registration
     - cross-extension first-registration-wins lookup/listing behavior
     - invalid registration behavior
5. delegate `register-command` mutation/API seams downward into the extracted component
6. update higher-level consumers to require `psi.command-registry.*` where appropriate
7. keep command execution/routing ownership in existing higher-level code
8. remove any temporary compatibility wrappers if they are used during migration

## Acceptance

- a separate `command-registry` component exists
- the authoritative command registration helper no longer lives under `psi.agent-session.*`
- the authoritative command lookup/listing helpers no longer live under `psi.agent-session.*`
- the first-cut command contract is explicit and preserved:
  - command identity is exact `:name`
  - command names are stored and compared exactly as provided after validation, with no slash-prefix normalization
  - `"hello"` and `"/hello"` remain distinct names in the first cut
  - same-extension duplicate registration replaces the previously stored command for that name
  - cross-extension duplicate registration is allowed
  - cross-extension lookup/listing is first-registration-wins by extension registration order
  - command-name queries are set-like across registered names
  - invalid registration behavior is explicit and proven
- higher-level command mutation/API seams still behave the same while depending downward on the extracted component
- no command execution/routing policy is pulled into the extracted component
- no new component cycle is introduced
- existing user-visible command behavior remains unchanged
- task `105-agent-session-component-extraction-map` can reference this as a concrete child extraction of the remaining extension/command boundary

## Related work

- `105-agent-session-component-extraction-map` is the umbrella architectural map
- `111-tool-registration-component-extraction` is the closest direct analogue
- `112-skill-registration-component-extraction` is a parallel registration-boundary refinement on the session-local side
- a later follow-on may revisit whether command registration, flags, and shortcuts want a broader shared extension-registration substrate, but that is explicitly outside this task
