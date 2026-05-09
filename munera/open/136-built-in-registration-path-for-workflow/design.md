# 136 — Built-in registration path for workflow

## Goal

Replace built-in workflow’s remaining reuse of extension-registry/API registration machinery with a true built-in registration path, while preserving the built-in workflow behavior already landed in task 133.

## Why

Task 133 reframed workflow as a built-in core capability and removed extension packaging residue, but one architectural compromise remains:

- built-in workflow is still installed mechanically by registering `built-in:workflow` in the extension registry and using the extension API contract to register its tool, commands, prompt contribution, and lifecycle hook

That compromise was explicitly recorded as a residual exception. It is safe enough to ship, but it is not the clean final shape.

The architecture currently says:
- workflow is built in conceptually
- workflow still behaves like a pseudo-extension mechanically

This task resolves that mismatch.

## Problem

The remaining built-in-via-extension-registry path keeps several ownership boundaries blurrier than they should be:

- canonical built-in workflow registration still depends on extension-specific data structures and APIs
- built-in workflow still contributes to active tool aggregation through extension-registry lookup rather than through a distinct built-in registration path
- built-in workflow command registration still goes through extension command storage rather than a built-in command path
- built-in workflow prompt contribution registration still uses extension-shaped registration semantics for canonical built-in capability surfacing
- future built-in capabilities may copy the pseudo-extension pattern instead of using a clean built-in path

## Intent

Introduce a built-in registration path for canonical workflow surfaces and migrate built-in workflow to it.

This task should:

- preserve all built-in workflow behavior already landed in task 133
- replace built-in workflow’s use of pseudo-extension registration where that usage is only a mechanical convenience
- keep lower workflow component ownership unchanged
- avoid redesigning the general extension runtime beyond the minimum needed to support a clean built-in registration path
- leave third-party/manifest/path extension behavior unchanged

## In scope

- defining a minimal built-in registration path for canonical tool/command/prompt/lifecycle workflow surfaces
- deciding the correct higher-core owner(s) for that path
- migrating built-in workflow off `ext/register-extension-in!` + `ext/create-extension-api` usage in its bootstrap path
- migrating built-in workflow command/tool/prompt/lifecycle registration to the built-in path
- updating any built-in workflow tests that still prove pseudo-extension mechanics rather than built-in mechanics
- updating docs or implementation notes when the residual exception is removed or narrowed

## Out of scope

- redesigning third-party extension runtime semantics
- changing extension manifests or generic extension install behavior for non-built-in extensions
- changing lower workflow loader/runtime/registry/judge/materialization/session-config ownership
- changing user-facing workflow behavior
- broad architectural cleanup unrelated to built-in workflow registration

## Preferred target shape

Preferred final shape:

- built-in workflow does not register itself as `built-in:workflow` in the extension registry
- built-in workflow does not call `ext/register-extension-in!` or `ext/create-extension-api` during canonical bootstrap
- built-in workflow tool/command/prompt/lifecycle surfaces are installed through a built-in registration path
- extension registry remains for extensions
- built-in workflow remains visible through user-facing runtime behavior, but not as pseudo-extension-owned canonical state

Allowed fallback:

- if one or two surfaces still need shared storage temporarily, they may continue to use a shared registry only if they are written through a built-in-specific path rather than through extension registration APIs
- any fallback must record exactly which surfaces remain shared, why direct built-in storage was not introduced now, and why the remaining compromise is narrower than task 133’s residual exception

## Success criteria for “not pseudo-extension”

This task counts as successful only if all of the following are true:

- built-in workflow bootstrap no longer seeds extension-registry extension identity for workflow
- built-in workflow bootstrap no longer builds an extension API wrapper as the way to install workflow surfaces
- built-in workflow provenance is modeled as built-in by the installing owner, not inferred from extension identity
- any remaining shared storage, if one survives, is reached through an explicit built-in path rather than through extension-shaped registration calls

This task does not require every registry implementation in the system to split physically into built-in and extension stores, but it does require built-in workflow installation to stop pretending workflow is an extension.

## Preferred implementation rule

Prefer the smallest implementation that satisfies the success criteria above:

- first choice: existing shared tool/command/prompt/lifecycle registries gain explicit built-in registration entrypoints or provenance-aware insertion paths
- second choice: small dedicated built-in registries if shared registries cannot accept built-in ownership cleanly
- avoid introducing parallel built-in infrastructure unless shared-registry reuse would keep pseudo-extension modeling in place

## Design constraints

- preserve `delegate`, `/delegate`, `/delegate-reload`, reload behavior, and prompt contribution behavior
- preserve direct-tool explicit `:session-id` targeting fix from the task 133 follow-up work
- prefer the smallest path that introduces a reusable built-in mechanism without broadening into a general registry rewrite
- keep built-in workflow proof surfaces aligned with higher-core ownership

## Required inventory before mechanism choice

Before choosing the final registration shape, implementation must inventory every workflow use of extension-shaped registration machinery and classify each use by surface:

- tool registration
- command registration
- prompt contribution registration
- lifecycle hook registration
- active tool aggregation
- prompt rendering/projection
- reload/session-switch lifecycle
- introspection/resolver projections
- tests that assert workflow appears in extension-owned state

The implementation decision must be based on that inventory rather than on a guessed target abstraction.

## Built-in owner preference

Preferred higher-core ownership:

- composition root / installer: `components/app-runtime/` unless review shows a smaller cleaner built-in bootstrap owner
- built-in workflow-specific assembly: `psi.agent-session.workflow.bootstrap` or a closely related built-in higher-core owner
- lower workflow behavior remains in the existing lower workflow components

If a different owner or split is chosen, implementation must record why that split is cleaner and why it does not re-broaden `agent-session` ownership incorrectly.

## Lifecycle modeling requirement

This task must make built-in lifecycle ownership explicit.

Implementation must answer:

- where built-in workflow lifecycle hooks live
- what event or bootstrap path invokes them
- whether they are runtime-scoped, session-scoped, reload-scoped, or some combination
- how reload and session-switch behavior are preserved without extension event registration

It is not sufficient to say that lifecycle behavior is preserved; the built-in invocation path must be named.

## Prompt contribution modeling requirement

This task must make prompt contribution ownership explicit.

Implementation must decide whether:

- built-in prompt contributions share the same underlying prompt contribution store with explicit built-in provenance, or
- built-in prompt contributions have a separate built-in store that is merged at prompt assembly time

Either choice is acceptable if built-in workflow is no longer registered as an extension.

Compatibility note:

- user-visible prompt contribution behavior must remain preserved
- internal prompt rendering labels such as `# Extension Prompt Contributions` may remain temporarily only if recorded explicitly as wording debt rather than as workflow still being extension-owned

## Introspection and provenance requirement

Implementation must state the target behavior for introspection and resolver projections.

Questions this task must answer explicitly:

- should built-in workflow still appear through extension-registry-oriented EQL surfaces after this task
- if not, which built-in projection replaces that visibility
- if some shared projection remains, how does it distinguish built-in from extension provenance cleanly

Tests and runtime projections should stop asserting that canonical built-in workflow is extension-owned state unless the task records a precise temporary exception.

## Key design questions

1. What is the smallest built-in registration abstraction that can replace pseudo-extension registration for workflow?
2. Should built-in tools/commands/prompt contributions/lifecycle hooks share existing registries through built-in-specific entrypoints, or should they live in separate built-in registries?
3. How should built-in lifecycle hooks be modeled and invoked if they no longer hang off extension event registration?
4. Which existing runtime projections currently assume command/tool/prompt provenance is extension-owned, and what is the smallest change needed to accommodate built-in ownership cleanly?
5. Can built-in workflow stop using extension-registry storage entirely, or should this task use shared registries with explicit built-in provenance and non-extension entrypoints?
6. Should `built-in:workflow` disappear entirely, or remain only as a stable built-in provenance identifier outside extension identity/state?

## Acceptance

- a focused task exists for removing built-in workflow’s pseudo-extension registration compromise
- the task clearly states the remaining architectural mismatch from task 133
- the design preserves task 133 behavior while targeting a true built-in registration path
- success is defined as removing extension identity + extension API bootstrap from canonical built-in workflow installation
- the task requires an inventory of affected workflow registration/projection/test surfaces before final mechanism choice
- the task makes lifecycle, prompt contribution, provenance, and introspection decisions explicit rather than leaving them implicit
- scope boundaries prevent this from broadening into a generic extension-runtime rewrite
