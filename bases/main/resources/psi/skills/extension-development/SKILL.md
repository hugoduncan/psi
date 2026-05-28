---
name: extension-development
description: Repository-specific guidance for creating, modifying, and debugging psi extensions.
lambda: "λextension_work. {create ∨ modify ∨ debug}(psi_extension) → read(authority) ∧ respect(dispatch_boundary) ∧ align(manifest ∧ permissions ∧ capabilities ∧ docs ∧ tests)"
---

# Extension development

Use this skill when creating a new psi extension, modifying an existing one, or debugging extension/runtime interactions.

## What an extension is in psi

An extension adds bounded capability to psi through declared integration seams such as tools, prompts, skills, workflows, commands, lifecycle callbacks, deterministic operations, and resolvers. Treat extensions as untrusted capability providers that integrate through explicit contracts, not as peers that reach into session state arbitrarily.

## Start with the authoritative docs

Read these first and prefer them over incidental examples:

- `doc/extension-api.md`
- `https://github.com/hugoduncan/psi/blob/main/doc/extension-api.md`
- `doc/extensions.md`
- `doc/extensions-install.md`
- `doc/architecture.md`
- `AGENTS.md` — architecture / Viable System Model sections

Useful implementation seams:

- built-in skill packaging and discovery: `components/prompt-assets/src/psi/prompt_assets/skills.clj`
- skill discovery tests/examples: `components/prompt-assets/test/psi/prompt_assets/skills_test.clj`
- runtime discovery/introspection: `components/agent-session/src/psi/agent_session/resolvers/discovery.clj`
- command/listing surfaces: `components/agent-session/src/psi/agent_session/commands.clj`

## Extension location and identity

Check whether the work is:

- a built-in psi-owned capability packaged from the source tree, or
- a manifest-installed/project-local extension loaded through extension install/configuration flows.

Use the existing installation and manifest model from `doc/extensions-install.md`. Stable installed extension identities use manifest-oriented provenance such as `manifest:{lib}`. Do not invent parallel identity schemes.

## Creation guidance

When adding a new extension:

- define the smallest capability surface that solves the task
- choose explicit seams: tool, command, prompt contribution, skill, workflow, lifecycle callback, deterministic operation, or resolver
- keep pure decision logic separate from I/O and runtime effects
- register capabilities through the canonical registries/runtime helpers described in `doc/extension-api.md`
- declare permissions such as `:allowed-events` when the extension can dispatch events
- keep manifests and capability descriptions specific and minimal
- add user-facing docs when the extension changes visible behavior or installation/use flows

## Modification guidance

When changing an existing extension:

- find the authoritative registration path before editing behavior
- trace both the write seam and the higher discovery/read surfaces that expose the capability
- preserve canonical ownership boundaries: dispatch owns state changes, resolvers own reads, effects own impure execution
- prefer contract updates over compatibility shims unless user intent explicitly requires compatibility
- keep built-in versus manifest-installed concerns distinct

## Debugging guidance

When debugging extension behavior:

- reproduce through the normal runtime surface first
- inspect manifest/registration state, capability availability, and permission gating before patching code
- check whether the issue is in registration, discovery, dispatch/event routing, resolver reads, or effect execution
- verify session capability membership versus catalog membership when a capability appears known but unavailable
- trace failures to the owning boundary instead of adding workaround logic across layers

## Architecture boundaries to preserve

Align with `AGENTS.md` and `doc/architecture.md`:

- handlers and state transitions should stay pure
- effects are data and should cross the impure boundary explicitly
- resolvers are for reads, not hidden mutation
- extensions must not bypass dispatch to mutate session/root state directly
- capability gating and permission checks are part of the contract, not optional polish
- built-in packaging should remain ordinary readable artifacts, not special opaque registry entries

## Verification checklist

For extension work, prove the relevant structural surfaces:

- capability registration/discovery works through the normal runtime path
- representative higher discovery/introspection surfaces can see the capability
- normal invocation/read/use flow works without special-case loaders
- permissions/capabilities behave as intended
- docs, tests, and code agree on the extension contract

## Testing stance

Prefer tests that exercise real runtime seams and observable state/output. Avoid mocks for core behavior when real local components or pure helpers can provide stronger proof.
