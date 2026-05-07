# 107 — Project nREPL component extraction

## Goal

Extract the managed project nREPL subsystem into a separate component so project REPL lifecycle and operations no longer live under `agent-session` ownership.

## Why

Task `105-agent-session-component-extraction-map` identified project nREPL as one of the clearest bounded subsystems currently latent inside `agent-session`.

The current namespace family is already strongly shaped as its own technical subsystem:

- `components/agent-session/src/psi/agent_session/project_nrepl_config.clj`
- `components/agent-session/src/psi/agent_session/project_nrepl_runtime.clj`
- `components/agent-session/src/psi/agent_session/project_nrepl_client.clj`
- `components/agent-session/src/psi/agent_session/project_nrepl_attach.clj`
- `components/agent-session/src/psi/agent_session/project_nrepl_started.clj`
- `components/agent-session/src/psi/agent_session/project_nrepl_eval.clj`
- `components/agent-session/src/psi/agent_session/project_nrepl_ops.clj`
- `components/agent-session/src/psi/agent_session/project_nrepl_commands.clj`

This subsystem is conceptually distinct from session lifecycle orchestration, prompt-turn semantics, tool runtime ownership, and workflow runtime ownership.

Extracting it creates a cleaner dependency map for later work while avoiding the boundary ambiguity present in turn/prompt extractions.

## Problem

Managed project nREPL currently lives under `psi.agent-session.*`, but it is not fundamentally session-orchestration logic.

That creates three ownership problems:

- `agent-session` appears to own a standalone technical subsystem whose main concerns are REPL connection lifecycle and operations rather than session orchestration
- downstream consumers that need project nREPL functionality must reach into `psi.agent-session.*` even when the dependency is really project-REPL-specific rather than session-specific
- later structural work is harder to reason about while project nREPL remains embedded inside `agent-session`

## Intent

Create one explicit lower-level component for managed project nREPL concerns.

That component should own:

- project nREPL config resolution
- managed instance runtime state helpers
- nREPL client connection/eval/interrupt helpers
- attach/start/started/eval operational logic
- higher-level nREPL operation entrypoints
- command-level parsing/dispatch for project REPL operations only when that logic is specific to the nREPL subsystem itself rather than to the broader agent-session command surface

Decision rule for `project_nrepl_commands.clj`:

- if a function’s primary job is parsing or dispatching project-nREPL-specific operations, it belongs in the extracted component
- if a function’s primary job is integrating project nREPL operations into broader agent-session command routing, it remains above the boundary

That component should not own:

- session lifecycle orchestration
- generic command routing outside the project nREPL operation surface
- `psi-tool` workflow/tool orchestration outside the project nREPL operation surface
- adapter or UI behavior
- resolver/mutation projection unrelated to project nREPL state

## Refactoring findings

Repo search shows a coherent `project_nrepl_*` namespace family with broad but straightforward consumer migration.

Current direct production consumers include:

- `components/agent-session/src/psi/agent_session/commands.clj`
- `components/agent-session/src/psi/agent_session/context.clj`
- `components/agent-session/src/psi/agent_session/psi_tool.clj`
- `components/agent-session/src/psi/agent_session/resolvers/project_nrepl.clj`

Current direct focused test surfaces include:

- `project_nrepl_attach_test.clj`
- `project_nrepl_runtime_test.clj`
- `project_nrepl_eval_test.clj`
- `project_nrepl_config_test.clj`
- `project_nrepl_started_test.clj`
- `project_nrepl_client_test.clj`
- `project_nrepl_commands_test.clj`
- `project_nrepl_resolvers_test.clj`
- `project_nrepl_extension_install_test.clj`
- higher-level `tools_test.clj` coverage via `project-nrepl-ops`

This is a stronger signal for component extraction than for continued internal `agent-session` reshaping.

## Scope

In scope:

- create a new `project-nrepl` component
- move the authoritative managed project nREPL namespace family out of `agent-session` as the default plan
- where `project_nrepl_commands.clj` proves to mix subsystem-owned command parsing/dispatch with broader agent-session command-surface integration, split that boundary rather than forcing the whole namespace below the component seam
- update direct consumers to depend on the extracted component
- keep behavior unchanged
- move or update focused tests so ownership is explicit and still proven
- rename moved component-owned tests to `psi.project-nrepl.*-test` namespaces so test ownership matches component ownership
- record, at completion, which tests moved into `components/project-nrepl/test/psi/project_nrepl/` and which remained elsewhere, with a brief reason

Out of scope:

- redesigning nREPL behavior or protocol usage
- changing project REPL UX semantics
- changing generic command routing beyond dependency/ownership updates
- changing resolver behavior beyond dependency/ownership updates
- redesigning `psi-tool` surfaces beyond dependency/ownership updates
- broad cleanup of unrelated `agent-session` code

## Boundary

### In the new component

The extracted component should own the authoritative implementation of:

- current `project_nrepl_config.clj` responsibilities
- current `project_nrepl_runtime.clj` responsibilities
- current `project_nrepl_client.clj` responsibilities
- current `project_nrepl_attach.clj` responsibilities
- current `project_nrepl_started.clj` responsibilities
- current `project_nrepl_eval.clj` responsibilities
- current `project_nrepl_ops.clj` responsibilities
- current `project_nrepl_commands.clj` responsibilities only to the extent they are truly subsystem-owned rather than broader agent-session command-surface integration

### Above the new component

The following responsibilities must remain outside the new component:

- session lifecycle/state orchestration
- context assembly that wires the subsystem into the larger runtime
- generic command routing outside project nREPL operations
- `psi-tool` workflow/tool orchestration outside project nREPL operations
- resolver/mutation projection of subsystem state into public graph surfaces
- adapter/UI rendering behavior

Boundary clarification:

- callers outside the new component may continue to own when project nREPL operations are triggered and how their results are surfaced to users
- the new component owns how managed project nREPL works, not when the surrounding system chooses to invoke it
- explicit expected split for first cut:
  - `psi.project-nrepl.ops` is the preferred lower operational entry surface for managed project REPL operations
  - higher-level adapters such as `psi.agent-session.psi-tool`, broader command routing, and resolver projection remain above the boundary
- preferred context seam for first cut:
  - `context.clj` should depend on the smallest subsystem surface that satisfies its needs, preferably `psi.project-nrepl.runtime` for runtime-state wiring and `psi.project-nrepl.ops` only where operational entrypoints are genuinely needed
  - avoid turning `context.clj` into a wide fan-out consumer of many `psi.project-nrepl.*` namespaces unless a concrete need is already present
- resolver boundary clarification:
  - extracted `psi.project-nrepl.*` namespaces own raw project-nREPL runtime/config/operation behavior
  - resolver projection remains above the boundary in `agent-session` resolver namespaces

## Target shape

Chosen target for this task:

- component path: `components/project-nrepl/`
- namespace family: `psi.project-nrepl.*`

Naming clarification:

- the component is named `project-nrepl` because its public responsibility is managed project REPL lifecycle and operations
- preserve the current family split explicitly in the extracted namespace family rather than collapsing everything into one `core` namespace

First-cut authoritative namespaces:

- `psi.project-nrepl.config`
- `psi.project-nrepl.runtime`
- `psi.project-nrepl.client`
- `psi.project-nrepl.attach`
- `psi.project-nrepl.started`
- `psi.project-nrepl.eval`
- `psi.project-nrepl.ops`
- `psi.project-nrepl.commands`

API-surface clarifications:

- higher-level consumers should depend on the smallest extracted namespace that matches the API they actually use
- lower helper functions may remain public where current tests or legitimate consumers already depend on them, but the extracted component should present one obvious owner per surface

Ownership clarifications:

- preferred steady-state dependency slope should be:
  - higher-level app/runtime/rpc/agent-session code -> `psi.project-nrepl.ops` for operational entry
  - helper-level callers may depend on narrower `psi.project-nrepl.*` namespaces only where a concrete local need already exists
- authoritative extracted `psi.project-nrepl.*` namespaces must not depend on `psi.agent-session.*` implementation namespaces at completion
- this task does not require removing `psi.session-state.state` dependencies from the extracted subsystem; it requires removing `psi.agent-session.*` implementation ownership

Compatibility-shim preference:

- default expectation is no compatibility shim unless the edit sequence concretely requires one to keep the tree compiling during migration
- if a temporary shim is introduced, it must be removed in the same slice before final verification
- the old `psi.agent-session.project-nrepl-*` namespaces must not remain authoritative owners at completion

## Consumer migration set

Known direct production consumers to evaluate in this slice:

- `components/agent-session/src/psi/agent_session/commands.clj`
- `components/agent-session/src/psi/agent_session/context.clj`
- `components/agent-session/src/psi/agent_session/psi_tool.clj`
- `components/agent-session/src/psi/agent_session/resolvers/project_nrepl.clj`
- tests and any remaining direct consumers found by repo search

## Acceptance

- a separate `project-nrepl` component exists
- the authoritative project nREPL implementation no longer resides under `components/agent-session/`
- the authoritative namespace names match the new component ownership
- no new component cycle is introduced
- all direct consumers compile against the extracted namespaces
- focused project-nrepl verification is green from the new component boundary
- at least one higher-level consuming path still works unchanged in behavior
- extracted authoritative `psi.project-nrepl.*` namespaces do not depend on `psi.agent-session.*` implementation namespaces directly
- any compatibility shim is used only temporarily during migration and removed before completion

## Suggested migration sequence

1. create `components/project-nrepl/` and add repo/component deps
2. move `project_nrepl_config.clj` to `psi.project-nrepl.config`
3. move `project_nrepl_runtime.clj` to `psi.project-nrepl.runtime`
4. move `project_nrepl_client.clj` to `psi.project-nrepl.client`
5. move `project_nrepl_attach.clj` to `psi.project-nrepl.attach`
6. move `project_nrepl_started.clj` to `psi.project-nrepl.started`
7. move `project_nrepl_eval.clj` to `psi.project-nrepl.eval`
8. move `project_nrepl_ops.clj` to `psi.project-nrepl.ops`
9. move `project_nrepl_commands.clj` to `psi.project-nrepl.commands`
10. update direct consumers
11. update/move focused tests
12. remove any temporary compatibility shims
13. run focused verification and record final ownership in task notes

## Verification intent

Focused verification should cover both the extracted component and at least one higher-level consumer.

Representative focused verification surfaces after migration:

- moved component-owned tests under `components/project-nrepl/test/psi/project_nrepl/`
- higher-level consuming-path tests such as commands, tools, resolvers, or context wiring tests that still prove unchanged behavior through the extracted component

## Related work

- task `105-agent-session-component-extraction-map` is the umbrella architectural map that identified project nREPL as one of the simplest first extractions
- this task is a concrete child extraction under that umbrella
