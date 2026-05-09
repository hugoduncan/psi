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
- migrating built-in workflow off `ext/register-extension-in!` + `ext/create-extension-api` usage in its bootstrap path when possible
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
- built-in workflow tool/command/prompt/lifecycle surfaces are installed through a built-in registration path
- extension registry remains for extensions
- built-in workflow remains visible through user-facing runtime behavior, but not as pseudo-extension-owned canonical state

Allowed fallback:

- if one or two surfaces still need extension-style storage temporarily, record exactly which surfaces remain and why the remaining compromise is narrower than task 133’s current residual exception

## Design constraints

- preserve `delegate`, `/delegate`, `/delegate-reload`, reload behavior, and prompt contribution behavior
- preserve direct-tool explicit `:session-id` targeting fix from the task 133 follow-up work
- prefer the smallest path that introduces a reusable built-in mechanism without broadening into a general registry rewrite
- keep built-in workflow proof surfaces aligned with higher-core ownership

## Key design questions

1. What is the smallest built-in registration abstraction that can replace pseudo-extension registration for workflow?
2. Should built-in tools/commands/prompt contributions live in separate built-in registries, or should the existing registries gain explicit built-in entries without pretending to be extensions?
3. How should built-in lifecycle hooks be modeled if they no longer hang off extension event registration?
4. Which existing runtime projections currently assume command/tool provenance is extension-owned, and how much must change to accommodate built-in ownership cleanly?
5. Can built-in workflow stop using extension-registry storage entirely, or should this task first introduce explicit built-in-vs-extension provenance in shared registries?

## Acceptance

- a focused task exists for removing built-in workflow’s pseudo-extension registration compromise
- the task clearly states the remaining architectural mismatch from task 133
- the design preserves task 133 behavior while targeting a true built-in registration path
- scope boundaries prevent this from broadening into a generic extension-runtime rewrite
