# 120 — Rename higher `psi.turn.*` namespaces into explicitly agent-session-owned namespaces

## Goal

Rename the surviving higher turn orchestration namespace family from `psi.turn.*` into explicitly `agent-session`-owned namespaces after task `119` lands, so namespace ownership matches the architectural boundary.

## Why

Task `119-expand-turn-runtime-prepared-turn-boundary` makes the turn split more explicit:

- `psi.turn-runtime.*` is the lower prepared-turn component
- `agent-session` remains the owner of session dispatch invocation and turn orchestration
- the current `psi.turn` namespace stays above `turn-runtime` as a session-owned orchestration facade

After `119`, the name `psi.turn` becomes actively misleading because it suggests one of two things that are no longer true:

- that it is the authoritative home of the turn domain
- that it is a lower reusable turn component peer to `turn-runtime`

Neither is correct. The surviving namespace is intentionally staying in the `agent-session` layer.

## Problem

If `psi.turn` keeps its current top-level name after `119`:

- ownership remains visually ambiguous in the source tree
- future extraction work can misread it as an unextracted lower turn component
- code readers must remember an exception instead of learning the architecture from the namespace itself
- the lower `turn-runtime` component boundary is harder to perceive at a glance

## Intent

Make the namespace names reflect the settled ownership decision:

- lower prepared-turn mechanics live under `psi.turn-runtime.*`
- higher session-owned orchestration lives under `psi.agent-session.turn.*`

Prerequisite for starting this task:

- `119` does not need every possible cleanup to be closed, but it must have landed the specific ownership outcome that leaves `psi.turn` as a higher `agent-session` facade rather than the authoritative owner of lower request-preparation or response-recording logic
- if `119` is still in progress, `120` may be executed only when the rename reduces churn against that already-landed ownership split instead of racing it

This task should:

- rename the authoritative higher orchestration namespace family from `psi.turn.*` into `psi.agent-session.turn.*`
- use nested family naming where possible, e.g. `psi.turn` -> `psi.agent-session.turn` and `psi.turn.handlers` -> `psi.agent-session.turn.handlers`
- update production and test consumers to use the renamed namespaces
- preserve behavior
- remove any temporary compatibility aliases before completion unless implementation reveals a short-lived migration need inside the same branch

This task should not:

- change the ownership split established by `119`
- move dispatch invocation below `agent-session`
- rename `psi.turn-runtime.*`
- broaden into a new extraction of higher orchestration out of `agent-session`

## Chosen target naming pattern

The target authoritative namespace pattern for the current higher `psi.turn.*` family is:

- `psi.turn` -> `psi.agent-session.turn`
- `psi.turn.handlers` -> `psi.agent-session.turn.handlers`
- similarly for any other surviving higher orchestration namespaces in the `psi.turn.*` family after `119`

Rationale:

- it makes ownership explicit
- it preserves the family structure instead of flattening related namespaces awkwardly
- it reads naturally beside `psi.turn-runtime.*`
- it keeps the distinction clear: `turn-runtime` is the lower reusable component family, while `agent-session.turn.*` is the higher orchestration family

## Boundary statement

After this task:

### Lower prepared-turn ownership remains

- `psi.turn-runtime.core`
- `psi.turn-runtime.stream`
- `psi.turn-runtime.accumulator`
- `psi.turn-runtime.request`
- `psi.turn-runtime.recording`
- any other lower prepared-turn helpers introduced by `119`

### Higher session-owned orchestration ownership is named explicitly

- `psi.agent-session.turn.*`

That higher namespace family continues to own orchestration responsibilities such as:

- prompt dispatch entrypoints
- prompt submission/invocation entrypoints
- steer/follow-up/queue helpers
- interrupt/abort orchestration entrypoints
- lifecycle handlers and other higher orchestration helpers
- any surviving higher wrappers over lower `turn-runtime` APIs

## Scope

In scope:

- rename the current authoritative higher `psi.turn.*` source files and namespaces to authoritative `psi.agent-session.turn.*` paths/names that match the post-rename ownership
- update all compiled production requires/imports/callsites
- update all test requires/imports/callsites
- update any production string/data references to the old namespaces if such references exist
- update active architecture or task artifacts that describe the current ownership story when they would otherwise misstate the post-rename architecture
- verify no authoritative higher production namespaces named `psi.turn` or `psi.turn.*` remain

Clarification on family scope:

- this task intentionally renames the surviving higher orchestration `psi.turn.*` family that remains in `agent-session`
- it does not rename `psi.turn-runtime.*`
- if implementation discovers a top-level `psi.turn.*` namespace that is actually lower prepared-turn ownership and should move under `turn-runtime` instead, record and resolve that according to the `119` boundary rather than renaming it blindly into `agent-session`

Out of scope:

- changing runtime behavior
- altering the `119` keep/move split
- introducing a long-lived compatibility forwarding shim
- rewriting closed historical task records solely to remove old namespace mentions
- broader renaming of unrelated prompt/turn namespaces unless directly required by the main rename

## Relationship to prior tasks

### Task `119-expand-turn-runtime-prepared-turn-boundary`

This task depends on `119` conceptually and should be executed after its ownership split lands.

`119` clarifies that:

- `turn-runtime` is the lower prepared-turn owner
- `psi.turn` remains a higher session-owned facade

This task then makes that conclusion visible in namespace naming.

### Task `105-agent-session-component-extraction-map`

This task is a follow-on naming correction under the broader component map recorded in `105`.

It does not create a new boundary. It makes an already-decided boundary easier to read and harder to misinterpret.

## Acceptance

- the authoritative higher orchestration family no longer lives in ambiguous top-level namespaces `psi.turn` or `psi.turn.*`
- the authoritative replacement higher namespace family is `psi.agent-session.turn.*`
- lower prepared-turn mechanics remain under `psi.turn-runtime.*`
- production consumers compile against the renamed namespaces
- focused tests and at least one higher-level consuming path prove behavior remains unchanged
- repo search confirms no authoritative higher production namespace definitions `psi.turn` or `psi.turn.*` remain
- repo search confirms no production `:require [psi.turn ...]`, `:require [psi.turn.* ...]`, or equivalent production namespace references remain, except for temporary in-branch migration code that is removed before completion

## Concrete done criteria

- the current authoritative higher `psi.turn.*` source files are replaced by authoritative file paths for the corresponding `psi.agent-session.turn.*` namespaces
- production requires are updated to the renamed namespaces
- tests are updated to the renamed namespaces
- any production string/data references to `psi.turn` or `psi.turn.*` are updated if they exist
- any temporary compatibility aliases created during the change are removed before task completion
- final repo search shows `psi.turn` or `psi.turn.*` only in active-task text that still discusses the rename, closed historical text, or not at all, but not as an authoritative higher production namespace definition or production require target

## Verification intent

Focused verification should prove both rename completeness and unchanged behavior.

Minimum intent:

- compile/load verification for the renamed namespace and its direct consumers
- focused tests covering the higher prompt/turn orchestration facade
- repo search proving no authoritative production `ns psi.turn` remains

Representative verification surfaces:

- the focused tests already exercising the higher turn/orchestration family after `119`
- prompt lifecycle or other higher orchestration tests that prove the renamed family still drives at least one real consuming path
- any direct consumer tests touched by require changes
- repo-wide search for `ns psi.turn`, `ns psi.turn.`, production `:require [psi.turn ...]`, production `:require [psi.turn.` patterns, and any production string/data references if present

## Risks

- leaving compatibility shims in place long enough that the old ambiguous higher names remain effectively authoritative
- changing imports incompletely across tests or secondary adapters
- renaming a top-level `psi.turn.*` namespace into `agent-session` that should actually move under `turn-runtime` according to the `119` boundary
- accidentally folding additional refactors into the rename instead of keeping it a narrow ownership-signaling task

## Suggested migration sequence

1. land `119` so the ownership split is real
2. inventory the surviving higher `psi.turn.*` namespaces after `119` and confirm they belong above the boundary
3. rename the authoritative namespace/files from `psi.turn.*` to `psi.agent-session.turn.*`
4. update direct production consumers
5. update tests
6. update any nearby architecture/task text that would otherwise now misstate the ownership story
7. run focused verification and repo search
8. remove any temporary compatibility aliases before completion
