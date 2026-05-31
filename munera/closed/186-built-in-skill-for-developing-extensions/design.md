# 186 built-in skill for developing extensions

## Intent

Add a psi-owned built-in skill that helps the agent develop psi extensions correctly and consistently.

This task now has enough design detail to support implementation planning and execution without leaving the built-in skill identity, scope, reference set, or verification target ambiguous.

## Problem

Psi now supports built-in packaged skills, but there is not yet a dedicated built-in skill focused on extension development. Extension work has important project-specific conventions around architecture, registration, dispatch/state boundaries, permissions/capabilities, and documentation. Without a focused built-in skill, extension-development guidance remains scattered across repository docs and prior task context.

A built-in extension-development skill should give the agent a stable, discoverable, psi-owned artifact it can read on demand when creating, modifying, or debugging extensions.

## Desired outcome

Psi ships a built-in skill for developing extensions.

The skill should:

- be available through the built-in packaged skill mechanism rather than requiring project-local installation
- have the canonical runtime skill name `extension-development`
- be authored in the source tree at `bases/main/resources/psi/skills/extension-development/SKILL.md`, producing the packaged built-in resource path `psi/skills/extension-development/SKILL.md`
- surface stable built-in provenance through the ordinary built-in skill discovery flow
- present concise, actionable guidance for extension development in this repository
- help the agent choose the correct seams for extension work
- point the agent at the most relevant authoritative files and concepts
- include the canonical GitHub link `https://github.com/hugoduncan/psi/blob/main/doc/extension-api.md` so the skill can reference the extension API documentation from outside a local checkout context as well
- fit the existing readable-artifact skill model so it can be inspected with ordinary skill usage and file reads

## In scope

- define and add a new built-in skill for extension development
- author it in the source tree at `bases/main/resources/psi/skills/extension-development/SKILL.md`, producing the packaged built-in resource `psi/skills/extension-development/SKILL.md`
- register/discover it with runtime skill identity `:name "extension-development"`
- ensure the skill is discoverable as a psi-owned built-in skill
- document the extension-development guidance inside the skill artifact itself
- cover routine extension work across:
  - creating a new extension
  - modifying an existing extension
  - debugging an existing extension
- cover the key extension-development concerns needed for routine agent use, including:
  - what an extension is in psi
  - where extension code and manifests live
  - capability/permission expectations
  - how extension behavior should interact with dispatch, handlers, effects, and resolvers
  - how built-in versus manifest-installed/project-local extension concerns differ when relevant
  - where to look for authoritative examples or documentation
  - the GitHub link `https://github.com/hugoduncan/psi/blob/main/doc/extension-api.md`
- verify the skill is visible and usable like other built-in skills through ordinary discovery/read surfaces

## Out of scope

- redesigning the extension system
- broad refactoring of extension architecture
- changing extension lifecycle semantics unless required to expose the skill
- creating multiple extension-related skills in this task
- implementing unrelated built-in skill packaging improvements beyond what is required for this skill to exist and be usable

## Constraints

- The new skill should follow the built-in packaged skill model established by tasks 184 and 185.
- The skill should align with current project architecture and repository instructions rather than inventing a parallel extension model.
- The skill should remain concise, practical, and optimized for agent use during implementation work.
- The skill should reference authoritative project documentation and source locations where that improves correctness.
- Verification should prove discoverability and usability through stable structural surfaces, not brittle full-text assertions over the authored skill prose.

## Minimum authoritative reference set

The skill should point agents at this minimum reference set, because these are the canonical seams the task wants the guidance to anchor to:

### Primary extension API/doc surfaces

- `doc/extension-api.md`
- `https://github.com/hugoduncan/psi/blob/main/doc/extension-api.md`
- `doc/extensions.md`
- `doc/extensions-install.md`
- `doc/architecture.md`

### Skill discovery / packaged built-in skill surfaces

- `components/prompt-assets/src/psi/prompt_assets/skills.clj`
- `components/prompt-assets/test/psi/prompt_assets/skills_test.clj`

### Runtime discovery / introspection surfaces

- `components/agent-session/src/psi/agent_session/resolvers/discovery.clj`
- `components/agent-session/src/psi/agent_session/commands.clj`

### Specific seam expectations the skill should reference

- extension API and runtime helper surface: `doc/extension-api.md`
- manifest/install model and stable manifest identities such as `manifest:{lib}`: `doc/extensions-install.md`
- extension authoring basics, extension-scoped mutate behavior, and programmatic tool plans: `doc/extensions.md`
- dispatch/runtime/handler/effect boundaries and the architecture layer model: `AGENTS.md` architecture section plus `doc/architecture.md`
- capability/permission constraints for extensions: `AGENTS.md` Viable System Model section, especially capability catalog / session capabilities / permission interceptor expectations
- built-in skill packaging, materialization, and ordinary file-backed readability semantics: `components/prompt-assets/src/psi/prompt_assets/skills.clj`

The skill does not need to cite every file exhaustively, but implementation should ensure the authored guidance points at this authoritative set rather than drifting to incidental examples only.

## Scope clarification

The skill scope is not limited to new-extension creation. It should explicitly help with:

- creating a new extension
- modifying an existing extension
- debugging an existing extension/runtime interaction

That broader scope matches the underlying problem: the project-specific extension seams matter whenever an agent changes extension behavior, not just when scaffolding a new namespace.

## Verification definition

For this task, “discoverable and usable” should be proved through these structural surfaces rather than brittle assertion of exact guidance prose:

1. **Packaged built-in resource presence**
   - the source-tree authored file lives at `bases/main/resources/psi/skills/extension-development/SKILL.md`
   - the built-in resource set includes packaged path `psi/skills/extension-development/SKILL.md`
   - built-in skill materialization/discovery can load it into the deterministic readable snapshot
2. **Built-in provenance on discovery**
   - built-in skill discovery returns a skill with `:name "extension-development"` and `:source :built-in`
3. **Ordinary readable-file semantics**
   - the discovered skill has a materialized readable `:file-path` pointing into the built-in snapshot, so it behaves like normal file-backed skills rather than a special unreadable registry-only object
4. **Normal user/agent discovery surfaces**
   - representative skill introspection/discovery surfaces such as `:psi.agent-session/skills` / `:psi.skill/by-source` can see `extension-development`
   - representative command/UI-oriented skill listing surfaces such as `/skills` remain able to present it as a built-in skill
5. **Invocation/readability surface**
   - the normal skill invocation/read path can read the skill content through the same semantics used for other skills, without requiring an extension-specific loader or special-case runtime path

Verification may assert targeted authored content that anchors the design-critical identity/reference decisions above, such as the canonical skill name and the GitHub `doc/extension-api.md` link, but should avoid freezing the entire instructional prose.

## Acceptance criteria

1. A new psi-owned built-in skill exists for developing extensions with runtime skill name `extension-development`.
2. The skill is packaged at `psi/skills/extension-development/SKILL.md` and discoverable through the built-in skill mechanism rather than only as a project-local file.
3. The skill is readable through the normal skill/artifact flow used by the agent, via ordinary materialized file-backed semantics.
4. The skill gives actionable guidance for developing extensions in psi, including architecture boundaries, relevant source/doc locations, manifest/install guidance, permissions/capabilities expectations, and the GitHub link `https://github.com/hugoduncan/psi/blob/main/doc/extension-api.md`.
5. The skill content is specific to this repository's extension model rather than generic extension advice.
6. The skill is available with stable built-in provenance like other built-in skills.
7. Verification demonstrates the packaged resource presence, built-in provenance, ordinary readable-file semantics, and normal discovery/invocation usability of the skill.
