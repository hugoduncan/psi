# 122 — Remove `psi.agent-session.prompt-control` compatibility namespace

## Goal

Remove the remaining `psi.agent-session.prompt-control` compatibility facade so turn-entry callers depend directly on the authoritative `psi.agent-session.turn` namespace.

## Why

Recent prompt/turn refactoring intentionally kept `psi.agent-session.prompt-control` as a thin migration seam while ownership moved behind it to `psi.agent-session.turn`.

That migration has now effectively landed:

- `psi.agent-session.turn` is the authoritative higher turn orchestration API
- `psi.agent-session.prompt-control` contains only direct pass-through wrappers
- current production and test callsites can be rewired without reopening the lower `turn-runtime` boundary

Leaving the facade in place now has more cost than value:

- it preserves an extra namespace readers must mentally translate
- it keeps a compatibility seam alive after the ownership transition is already settled
- it creates a false impression that prompt control remains a distinct subsystem instead of a retired name over `agent-session.turn`
- it encourages new callsites to keep using the obsolete surface

## Problem

The namespace `components/agent-session/src/psi/agent_session/prompt_control.clj` is no longer authoritative. It is only a compatibility wrapper over `psi.agent-session.turn`, but it is still required by current production and test code.

As long as it remains:

- direct callers continue depending on a retired name
- repo searches over prompt/turn ownership stay noisier than necessary
- the `agent-session` turn boundary remains slightly harder to read
- compatibility cleanup work under broader tasks `002` and `003` remains incomplete in this narrow area

## Intent

Remove `psi.agent-session.prompt-control` cleanly.

This task should:

- update all production consumers to require/use `psi.agent-session.turn` directly
- update all tests to require/use `psi.agent-session.turn` directly
- remove the compatibility facade namespace file
- replace the current facade-delegation test with focused proof at the authoritative `psi.agent-session.turn` surface or other consuming-path proof as appropriate
- update active task/design text that would otherwise describe `prompt-control` as a current compatibility facade after its removal
- preserve prompt-turn behavior exactly

This task should not:

- redesign turn orchestration semantics
- move higher turn ownership out of `agent-session`
- rename `psi.agent-session.turn`
- broaden into prompt lifecycle redesign beyond removing this obsolete namespace
- reintroduce a replacement shim under a different name

## Current authoritative and obsolete surfaces

Authoritative higher turn surface:

- `components/agent-session/src/psi/agent_session/turn.clj`

Obsolete compatibility surface to remove:

- `components/agent-session/src/psi/agent_session/prompt_control.clj`

Representative current production consumers to rewire:

- `components/agent-session/src/psi/agent_session/core.clj`
- `components/agent-session/src/psi/agent_session/workflow_statechart_runtime.clj`
- `components/agent-session/src/psi/agent_session/workflow_judge.clj`
- `components/agent-session/src/psi/agent_session/compaction_runtime.clj`

Representative current tests to rewire:

- `components/agent-session/test/psi/agent_session/prompt_lifecycle_test.clj`
- workflow runtime/judge tests that currently require or redefine `psi.agent-session.prompt-control/*`

## Boundary statement

After this task:

- `psi.agent-session.turn` remains the single authoritative higher turn orchestration namespace
- lower prepared-turn mechanics remain under `psi.turn-runtime.*`
- no production namespace should require `psi.agent-session.prompt-control`
- no authoritative production namespace definition for `psi.agent-session.prompt-control` should remain

This is a compatibility cleanup task, not a boundary-change task.

## Scope

In scope:

- inventory and update production `:require` and var references from `psi.agent-session.prompt-control` to `psi.agent-session.turn`
- inventory and update test `:require` and var references from `psi.agent-session.prompt-control` to `psi.agent-session.turn`
- remove `prompt_control.clj`
- reshape focused tests so they prove the intended behavior without preserving a facade-delegation assertion for a namespace that no longer exists
- update active task text that would otherwise misstate the current architecture
- run focused verification and repo search for stale references

Out of scope:

- changes to lower `turn-runtime` contracts
- broader prompt/request preparation redesign
- workflow semantic changes
- edits to closed historical task records solely to erase old mentions

## Acceptance

- all production consumers use `psi.agent-session.turn` directly
- all tests use `psi.agent-session.turn` directly where applicable
- `components/agent-session/src/psi/agent_session/prompt_control.clj` is removed
- no production `:require` or authoritative namespace definition for `psi.agent-session.prompt-control` remains
- focused tests still prove prompt-turn behavior on at least one direct path and one higher-level consuming path
- active open-task text no longer describes `prompt-control` as a current compatibility facade
- behavior is unchanged

## Related work

- `002-compatibility-scaffold-removal` — broader compatibility cleanup umbrella
- `003-prompt-lifecycle-architectural-convergence` — broader prompt lifecycle convergence umbrella
- `105-agent-session-component-extraction-map` — umbrella ownership map that still listed `prompt_control.clj` in the turn area
- `120-rename-psi-turn-to-agent-session-turn` — earlier ownership-signaling rename that intentionally left `prompt-control` as a compatibility wrapper at that time
