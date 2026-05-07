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
- generic extraction of sub-config maps from layered config
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
  - best-effort read
  - write/update helpers
- `psi.shared-config.project`
  - shared/local project config file paths
  - deep merge
  - malformed-file warnings
  - best-effort read
  - write/update helpers for project-local overrides
- `psi.shared-config.resolution`
  - system defaults passed or defined at this layer for shared agent-session config
  - generic sub-map extraction helpers
  - layered merge for user + project config
  - typed accessors for the currently shared `agent-session` config surface, unless extraction naturally splits them more clearly

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

## Migration expectations

This extraction should:

1. create the new component and move authoritative config file-reading/resolution code into it
2. update `app-runtime` to depend on `psi.shared-config.resolution`
3. update `agent-session.dispatch-effects` to write through `psi.shared-config.user` / `psi.shared-config.project`
4. update `project-nrepl.config` to consume the shared lower config substrate instead of duplicating file-reading and merge logic
5. remove the old `psi.agent-session.config-resolution`, `psi.agent-session.project-preferences`, and `psi.agent-session.user-config` authoritative ownership from `agent-session`
6. allow temporary shims only during migration and remove them before task completion

## Acceptance

- a new component exists at `components/shared-config/`
- there is one obvious authoritative owner for shared config file lookup/read/write and layered resolution logic
- `app-runtime` no longer depends on `psi.agent-session.config-resolution`
- `project-nrepl` no longer duplicates the shared file-reading/layering logic that exists only because config ownership sat above it
- `agent-session` depends downward on the extracted component for shared config reads/writes
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
