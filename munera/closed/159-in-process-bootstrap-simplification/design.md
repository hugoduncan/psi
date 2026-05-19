Goal: narrow the in-process bootstrap flow so the initial session is created later, behind an explicit startup-plan phase, without redesigning the broader startup architecture.

## Why

Current in-process bootstrap creates the first session early and then uses that live session as the target for most remaining startup work. That makes bootstrap harder to follow and blurs the boundary between:

- runtime context creation
- startup input discovery
- first-session creation
- post-session runtime adoption

This task should make that boundary explicit in the smallest coherent slice.

## Problem statement

Today `create-runtime-session-context` creates the first session, and `bootstrap-runtime-session!` then continues assembling startup around that already-live session.

The main simplification target is not a full bootstrap redesign. It is to stop creating the initial session before the startup inputs needed to define that session have been assembled.

## Scope

This task is limited to the canonical in-process startup path used by app-runtime.

The authoritative orchestration change for this task is `psi.app-runtime/bootstrap-runtime-session!`. Helper extraction or support changes may touch `psi.agent-session.bootstrap`, `psi.agent-session.context`, and the session-lifecycle creation surface only as needed to support that orchestration split.

In scope:

- split the current startup flow into explicit phases
- introduce a pre-session startup-plan or equivalently explicit assembled data shape
- move initial session creation to after that pre-session assembly
- keep a small post-session adoption phase for the remaining session-dependent work
- preserve the shared startup path used by console, TUI, and RPC entrypoints

Out of scope unless a tiny aligned adjustment is required:

- launcher redesign
- broad extension architecture redesign
- broad workflow bootstrap redesign
- broad prompt lifecycle redesign
- replacing every mutation-shaped startup path with direct initialization
- refactoring resume/fork/child-session bootstrap beyond what is required for the first-session startup path

## Required implementation shape

The resulting top-level orchestration must have four explicit phases, even if the exact function names differ:

1. create runtime context
   - no initial session created here

2. build startup plan
   - discover/assemble startup inputs that do not inherently require a session id
   - represent this phase with a named helper that returns an explicit map-like startup-plan value consumed by later startup phases
   - do not mutate a live session here

3. create initial session
   - one explicit creation point for the startup session

4. adopt startup plan into the session
   - remaining session-specific bootstrap only

The implementation does not need to make every startup concern pre-session. It does need to make the boundary explicit and move the initial session creation to the latest correct point.

## Runtime registries and loaders

For this task, runtime-owned registries are part of runtime context creation, not startup-plan assembly and not initial session creation. The implementation should treat existing runtime registries as pre-session infrastructure unless a very small justified exception is required.

That means the current registry-creation work in the runtime/session context layer should remain on the runtime-context side of the split. Session-specific use of those registries may still happen during the post-session adoption phase.

This task should also make pre-session startup assembly explicit through a small number of loader-style helpers where that clarifies the phase boundary without broadening scope.

Required loader expectation:

- a named startup-plan helper must exist and return an explicit map-like startup-plan value consumed by later startup phases

Preferred narrow loader/component splits, when they materially clarify the boundary:

- startup-plan loader/facade
  - assembles pre-session startup inputs in one place
- tool startup assembler
  - assembles startup-time tool definitions separately from session active-tool application
- extension install discovery/apply split
  - pre-session discovery of install inputs may be separated from post-session activation/adoption

Optional only if implementation pressure makes them worthwhile in this slice:

- built-in workflow install versus session-adopt split
- startup prompt input/builder facade

This task does not require new loader components for every registry-backed subsystem. New loader extraction is justified only when it helps move pre-session assembly out of session mutation choreography and makes the phase boundary explicit.

## Minimum concern inventory

The implementation must account for the following current bootstrap concerns and place each one on one side of the new boundary:

- cwd/config resolution
- model registry initialization
- oauth/runtime context creation
- recursion/runtime root state setup
- prompt template discovery
- skill discovery and diagnostics handling
- context-file discovery
- developer prompt/env resolution
- base tool assembly including `psi-tool`
- base system-prompt construction
- built-in workflow bootstrap
- startup resource loading for templates/skills/tools/extensions
- manifest extension activation
- active-tool refresh
- startup-summary recording
- global resolver/mutation registration
- graph-capability query used for final prompt shaping
- final system-prompt construction and persistence
- memory-runtime sync
- runtime extension-run binding
- startup rehydrate capture

This task does not require the final placement of each concern to be ideal across the whole architecture. It does require the placement chosen in this slice to be explicit and justified.

## Decisions the implementation must make

Record these explicitly in `implementation.md`:

1. What is the new authoritative runtime-context creation function or phase?
2. What is the new authoritative startup-plan function or phase?
3. What is the single explicit initial-session creation point?
4. Which existing bootstrap steps moved before session creation?
5. Which steps remain after session creation, and what concrete session requirement keeps them there?
6. Whether built-in workflow bootstrap and `psi-tool` remained post-session or were partially split, and why
7. Which runtime registries remained purely runtime-context infrastructure in the final shape
8. Which loader-style helpers were introduced or clarified, and why they were the smallest useful extraction

## Constraints

- preserve existing user-visible startup behavior unless a change is intentional, documented, and proved
- keep one authoritative in-process startup path for console, TUI, and RPC
- prefer a small structural refactor over a broad architectural rewrite
- do not broaden this task into redesigning session lifecycle or extension semantics
- this task does not require redesigning `session/new-session-in!` or the underlying session lifecycle API; moving initial session creation later in the orchestration is sufficient unless a small local initialization-input adjustment materially improves the phase split
- do not require elimination of all post-session mutations; only require that pre-session work stop depending on an already-created session

## Acceptance

The task is complete only when all of the following are true:

- the initial session is no longer created during runtime-context creation
- the code has an explicit pre-session startup-plan phase
- there is one explicit point where the initial startup session is created
- startup-plan assembly does not require mutating a live session
- the remaining post-session adoption phase is smaller and clearly session-dependent
- console, TUI, and RPC startup still run through the same canonical in-process bootstrap path
- tests protect the new ordering so early session creation does not accidentally return

## Verification expectations

At minimum, proof should show:

- runtime context creation completes without creating a session
- startup-plan assembly completes without a live session
- initial session creation happens after startup-plan assembly
- the bootstrapped runtime still reaches the expected effective startup state

## Likely hotspots

- `components/app-runtime/src/psi/app_runtime.clj`
- `components/agent-session/src/psi/agent_session/bootstrap.clj`
- `components/agent-session/src/psi/agent_session/context.clj`
- session lifecycle creation surface used by app-runtime startup

## Preferred direction

A good implementation would likely make app-runtime read roughly like:

- create runtime context
- build startup plan
- create initial session
- adopt startup plan into session

This task is successful if that structure becomes explicit and the first session is created later, even if some startup concerns remain session-applied in the final phase.
