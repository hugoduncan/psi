# 109 — Shared config resolution component extraction

## Goal

Extract the shared file-based config reading and layered config resolution logic out of `agent-session` into its own lower component, so config ownership no longer sits artificially high and other components can depend downward on one authoritative config substrate instead of copying the logic.

## Why

Current config resolution logic is small, coherent, and already lower-level than most `agent-session` responsibilities.

Today the main read path is:

- `psi.agent-session.config-resolution`
  - `psi.agent-session.project-preferences`
  - `psi.agent-session.user-config`

And the strongest architectural smell is duplication:

- `psi.project-nrepl.config` currently reimplements user-config file lookup, project/shared/local config file lookup, EDN best-effort reading, layered merge behavior, and malformed-file fallback/warning behavior
- this happened specifically to avoid making the extracted `project-nrepl` component depend upward on `agent-session`

That is strong evidence that the config boundary is owned too high.

## Problem

Config logic is currently split across:

- `psi.agent-session.config-resolution`
- `psi.agent-session.project-preferences`
- `psi.agent-session.user-config`
- duplicated logic in `psi.project-nrepl.config`

This causes several problems:

- lower components cannot reuse config resolution without depending upward on `agent-session`
- extracted components may copy config-reading logic rather than depending on one authoritative owner
- config file path conventions and malformed-file handling can drift across components
- config ownership is blurred between generic file-backed configuration and session-specific policy/accessors

## Intent

Create a lower shared config component that owns:

- user config file discovery/read/write
- project shared/local config file discovery/read/write
- layered merge behavior for those files
- best-effort malformed-file handling policy
- authoritative full-map reads for user/project config files
- extraction of the `:agent-session` subtree from those full maps where needed
- the current shared `agent-session` layered resolution behavior

This task should also migrate current consumers so that:

- `app-runtime` no longer depends on `psi.agent-session.config-resolution`
- `project-nrepl` no longer carries copied config file-reading/resolution logic
- `agent-session` depends downward on the extracted shared config component

## Out of scope

- changing config semantics or precedence
- changing config file locations
- changing session-state runtime override behavior
- redesigning all typed config accessors across the repo
- broad validation/schema redesign for every config key
- extracting model-registry or session-default policy

## Current dependency picture

### Below current shared config resolution

Current downward tree:

- `psi.agent-session.config-resolution`
  - `psi.agent-session.project-preferences`
    - `clojure.edn`
    - `clojure.java.io`
  - `psi.agent-session.user-config`
    - `clojure.edn`
    - `clojure.java.io`

### Above and adjacent to current shared config resolution

Current direct consumers and adjacent duplication:

- `psi.app-runtime`
  - calls `resolve-config`
  - calls typed accessors `resolved-model`, `resolved-thinking-level`, `resolved-prompt-mode`, `resolved-nucleus-prelude-override`
- `psi.agent-session.dispatch-effects`
  - calls `project-preferences/update-agent-session!`
  - calls `user-config/update-agent-session!`
- `psi.project-nrepl.config`
  - duplicates user-config file lookup
  - duplicates project shared/local file lookup
  - duplicates EDN best-effort read policy
  - duplicates layered merge behavior
  - duplicates malformed-file warning/fallback behavior

## Target shape

Chosen target for this task:

- component path: `components/shared-config/`
- namespace family: `psi.shared-config.*`

Authoritative first-cut namespaces:

- `psi.shared-config.user`
- `psi.shared-config.project`
- `psi.shared-config.resolution`

Expected ownership:

- `psi.shared-config.user`
  - user-global config file path
  - best-effort full-map read
  - full-map write helper
  - `:agent-session` subtree update helper for current consumers
- `psi.shared-config.project`
  - shared/local project config file paths
  - deep merge
  - malformed-file warnings
  - best-effort layered full-map read for project shared + local
  - project-local write helper
  - `:agent-session` subtree update helper for current consumers
- `psi.shared-config.resolution`
  - shared `agent-session` system defaults
  - `:agent-session` subtree extraction helpers from full maps
  - layered merge for effective shared `agent-session` config
  - typed accessors for the currently shared `agent-session` config surface

This resolves an important ownership ambiguity:

- user/project namespaces own file mechanics and authoritative full-file maps
- the resolution namespace owns turning those full-file maps into the effective shared `:agent-session` configuration surface
- project-nREPL remains free to consume full maps or the extracted `:agent-session` subtree as needed without moving its own interpretation rules into shared-config

## First-cut decisions to remove ambiguity

These decisions are part of this task's intended shape, so the implementation should not rediscover them from scratch:

1. Preserve the existing persisted file shapes.
   - user config remains at `~/.psi/agent/config.edn`
   - project config remains at `<cwd>/.psi/project.edn`
   - project-local config remains at `<cwd>/.psi/project.local.edn`
   - the extracted component does not introduce new config files or rename existing keys

2. Preserve the current top-level config key layout.
   - shared-config user/project reads remain authoritative over the full persisted EDN maps, including `:version`
   - first-cut shared agent-session resolution still treats `:agent-session` as the owning persisted subtree for the current shared session settings
   - project-nREPL config still lives under `[:agent-session :project-nrepl]`
   - this task is not a keyspace redesign

3. Preserve the current effective precedence exactly.
   - system defaults < user config < project shared < project local
   - session runtime overrides remain outside the extracted component and continue to win later in the runtime path

4. Minimize first-cut consumer churn by preserving current public behavior where reasonable.
   - `app-runtime` should still be able to ask one resolution namespace for an effective agent-session config map plus the same typed accessors it uses today
   - `agent-session.dispatch-effects` should still be able to perform explicit user/project writes through clear helper functions without learning raw file mechanics
   - `project-nrepl` should keep owning its public project-nREPL-specific config API, while consuming shared lower-level config readers/mergers beneath that surface

5. Do not force project-nREPL validation down into shared-config.
   - `resolved-start-command`, `resolved-attach-endpoint`, target-worktree validation, absolute-path validation, and `.nrepl-port` discovery remain project-nREPL-owned
   - only the duplicated user/project file reading and generic merge mechanics move downward

## Boundary rules

The extracted component should own generic file-backed config mechanics.

The extracted component should not own:

- session runtime override storage in session state
- app-runtime decisions about how resolved config becomes session defaults
- project-nrepl target resolution or `.nrepl-port` discovery
- provider/model runtime lookup
- higher-level tool or UI semantics

Important boundary distinction:

- generic config mechanics move downward
- domain-specific interpretation can remain in the consuming domain when that interpretation is not genuinely shared

For example:

- `agent-session`-specific typed accessors may remain shared-config-owned if they still describe the common config surface directly
- project-nREPL-specific validation such as `resolved-start-command`, `resolved-attach-endpoint`, and worktree/port validation should remain in `psi.project-nrepl.config` or a lower project-nREPL-owned config namespace, while consuming shared lower-level read/merge helpers

## Open questions resolved for this task

The following questions are now answered to keep the task executable:

### 1. Should shared-config be generic across arbitrary config subtrees, or specific to the currently shared config surfaces?

First cut: layered full-map file ownership plus a specific shared `:agent-session` resolution surface.

- shared-config should expose authoritative full-map readers/writers for the existing persisted files
- shared-config may expose small reusable helpers such as deep merge and best-effort EDN map reading
- the extracted resolution surface should be driven by actual current consumers, especially `:agent-session` and nested `:project-nrepl`
- this task does not need to invent a fully generic config framework

### 2. Should typed accessors like `resolved-model` remain in the extracted component?

Yes, in `psi.shared-config.resolution` for the current first cut.

- these accessors are part of the currently shared `agent-session` config resolution surface already used by `app-runtime`
- keeping them in the extracted resolution namespace minimizes churn and keeps one obvious owner for the existing contract
- this keeps typed accessors out of the lower file-mechanics namespaces `psi.shared-config.user` and `psi.shared-config.project`
- a later task may split pure file mechanics from typed session-config interpretation more aggressively if that becomes valuable, but that split is not required here

### 3. Should `psi.project-nrepl.config` disappear entirely?

No.

- `psi.project-nrepl.config` should remain the owning surface for project-nREPL-specific interpretation and validation
- it should consume authoritative full-map reads or `:agent-session` subtree extraction from shared-config rather than reimplementing file mechanics
- only the copied lower-level config file mechanics should move out from under it

### 4. Should `psi.agent-session.project-preferences` and `psi.agent-session.user-config` remain as compatibility shims?

Only temporarily if the migration truly requires it.

- the task should prefer updating consumers directly to the extracted namespaces
- if temporary shims are introduced to keep the slice small, they must be removed before task completion

### 5. Should this task also unify warning text and malformed-file behavior between current `agent-session` and current `project-nrepl` code?

Yes, but by preserving the existing `agent-session` behavior as authoritative.

- current `project-preferences` warning behavior should be treated as the first-cut canonical project-file malformed-data policy
- project-nREPL should adopt that shared lower behavior rather than preserving a second independent implementation

## Migration expectations

This extraction should:

1. create the new component and move authoritative config file-reading/resolution code into it
2. update `app-runtime` to depend on `psi.shared-config.resolution`
3. update `agent-session.dispatch-effects` to write through `psi.shared-config.user` / `psi.shared-config.project`
4. update `project-nrepl.config` to consume shared-config file readers and/or `:agent-session` subtree extraction instead of duplicating file-reading and merge logic
5. remove the old `psi.agent-session.config-resolution`, `psi.agent-session.project-preferences`, and `psi.agent-session.user-config` authoritative ownership from `agent-session`
6. allow temporary shims only during migration and remove them before task completion

Migration ordering decision:

- move shared-config ownership first
- migrate `app-runtime` and `dispatch-effects` directly to the new namespaces
- then simplify `project-nrepl.config` by deleting duplicated lower-level read/merge code and replacing it with shared-config calls
- only after consumers are moved should any remaining compatibility namespaces be removed

This ordering avoids the ambiguity of trying to redesign project-nREPL config semantics while the lower shared substrate is still in flux.

## Acceptance

- a new component exists at `components/shared-config/`
- there is one obvious authoritative owner for shared config file lookup/read/write and layered resolution logic
- shared-config owns authoritative full-file reads/writes for the existing user and project config files
- `app-runtime` no longer depends on `psi.agent-session.config-resolution`
- `project-nrepl` no longer duplicates the shared file-reading/layering logic that exists only because config ownership sat above it
- `agent-session` depends downward on the extracted component for shared config reads/writes
- current persisted key layout remains unchanged, including `:version`, `:agent-session`, and nested `[:agent-session :project-nrepl]`
- current config precedence remains unchanged:
  - system < user < project-shared < project-local
  - session runtime overrides remain outside this component
- malformed-file handling semantics remain unchanged unless explicitly documented as improved but behaviorally equivalent
- focused tests move or are rewritten to prove the new authoritative boundary
- no new upward dependency from extracted components to `agent-session` is introduced

## Likely verification surfaces

- shared-config focused tests for user/project/resolution namespaces
- `app-runtime` focused test coverage for runtime defaulting from resolved config
- `project-nrepl` focused test coverage proving it now uses the shared substrate without behavior drift
- at least one higher-level path proving project/user config writes still affect later resolution correctly
