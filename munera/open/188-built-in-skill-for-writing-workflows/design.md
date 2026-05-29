# 188 built-in skill for writing workflows

## Intent

Add a psi-owned built-in skill for workflow authoring and workflow updates, replacing the current project-local `workflow` skill at `.psi/skills/workflow/SKILL.md`.

This task is a task-creation/design task only. Its purpose is to define the intended replacement clearly enough that a follow-up planning/implementation pass can build it without re-deriving identity, scope, migration expectations, or verification goals.

## Problem

Psi currently has a project-local workflow skill at `.psi/skills/workflow/SKILL.md`:

- it teaches workflow comprehension and authoring
- it is referenced in the current agent skill list as `workflow`
- it is not shipped as a psi-owned built-in packaged skill

That creates three problems:

1. **Wrong ownership/distribution model.** Workflow authoring is a core psi capability, but the guidance currently lives only as a project-local skill instead of a packaged built-in skill.
2. **Fragile availability.** The current skill depends on repository-local `.psi/skills` discovery rather than the built-in packaged skill mechanism established by tasks 184 and 185.
3. **Split source of truth risk.** Once psi supports built-in packaged skills, keeping both a built-in workflow-writing skill and the existing `.psi/skills/workflow/SKILL.md` would create ambiguity about which artifact is canonical.

The task should therefore replace the project-local workflow skill with a built-in packaged skill, while preserving the useful workflow-authoring guidance and ordinary skill readability semantics.

## Desired outcome

Psi ships a built-in packaged skill for writing and updating workflows.

The replacement should:

- use the built-in packaged skill mechanism rather than project-local `.psi/skills` installation
- replace the current project-local `workflow` skill as the canonical workflow-authoring skill
- preserve the canonical runtime skill name `workflow`
- be authored in the source tree at `bases/main/resources/psi/skills/workflow/SKILL.md`, producing packaged built-in resource path `psi/skills/workflow/SKILL.md`
- remain readable through ordinary materialized file-backed skill semantics
- provide workflow comprehension and authoring guidance specific to this repository's workflow system
- cover both creating a new workflow and updating an existing workflow
- point the agent at the authoritative workflow authoring/runtime/reference seams in this repository
- remove ambiguity about the role of `.psi/skills/workflow/SKILL.md` by replacing it rather than maintaining two canonical workflow skills

## In scope

- define a new psi-owned built-in skill for workflow authoring
- author it at `bases/main/resources/psi/skills/workflow/SKILL.md`
- package it as built-in resource `psi/skills/workflow/SKILL.md`
- preserve runtime skill identity `:name "workflow"`
- replace the current project-local `.psi/skills/workflow/SKILL.md` skill as the canonical workflow-authoring artifact
- migrate or rewrite the current workflow skill content as needed so the built-in artifact is the authoritative source
- ensure the built-in skill remains discoverable through the ordinary built-in skill discovery flow
- ensure the normal skill read/invocation path can read the built-in workflow skill via ordinary file-backed semantics
- update bootstrap/prompt/discovery expectations so the workflow skill continues to appear as an available built-in skill
- define what happens to `.psi/skills/workflow/SKILL.md` after replacement
- document or otherwise align user-facing/project-facing references if they still point to the old project-local path
- verify built-in resource presence, built-in provenance, readable-file semantics, and replacement of the project-local source of truth

## Out of scope

- redesigning workflow runtime semantics
- rewriting all workflow documentation unrelated to the skill replacement
- migrating all project-local skills to built-ins in this task
- changing the canonical runtime skill name away from `workflow`
- adding multiple workflow-related built-in skills in this task
- changing workflow loader/runtime behavior except where required for the built-in skill to exist and be discoverable

## Constraints

- The new skill should follow the built-in packaged skill model established by tasks `184-package-skills-inside-psi-easiest-path` and `185-implement-built-in-packaged-skills`.
- The built-in skill should preserve the current useful workflow-authoring role of the existing `workflow` skill rather than becoming generic or repository-agnostic.
- The task must eliminate source-of-truth ambiguity: after implementation, there should be one canonical workflow-authoring skill artifact, not two competing versions.
- Verification should prefer structural/discovery/readability assertions over brittle full-text snapshots of the authored prose.
- The replacement should maintain the existing ordinary skill model: readable artifact, file-backed access, relative-path semantics when relevant.

## Current source being replaced

The current skill to replace is:

- `.psi/skills/workflow/SKILL.md`

Its present role is a workflow comprehension and authoring skill, currently described as:

- name: `workflow`
- description: `A workflow comprehension and authoring skill. Use when the asks "create a workflow" or "update a workflow".`

Its content currently teaches:

- top-level workflow shapes
- multi-step workflow structure
- step/session/judge/on routing model
- author-facing step identity
- source/reference/default data flow
- session construction/default override behavior
- judge and routing semantics
- loop/fork/chain patterns
- compile-time validation expectations

That guidance should not be lost; it should either be migrated into the built-in packaged skill or deliberately refined there.

## Replacement semantics

This task requires an explicit replacement outcome rather than silent coexistence.

The intended post-implementation state is:

1. the canonical workflow-authoring skill is the built-in packaged skill at `psi/skills/workflow/SKILL.md`
2. runtime discovery/provenance presents `workflow` as a built-in skill (`:source :built-in`)
3. the old project-local `.psi/skills/workflow/SKILL.md` is no longer the authoritative source of truth

The implementation follow-up must choose one of these concrete end states for the old project-local file and make it consistent everywhere:

- **preferred and required for the main implementation slice:** remove `.psi/skills/workflow/SKILL.md` in the same slice that adds the built-in replacement and updates references
- compatibility stub retention is acceptable only if removal is blocked by an identified external dependency discovered during implementation; in that case the task must treat the stub as an explicit temporary exception and prove that it cannot behave like the canonical `workflow` skill

Because current skill discovery precedence is `project > user > built-in`, ordinary coexistence of a project-local `.psi/skills/workflow/SKILL.md` with a built-in `workflow` skill would leave the project-local file shadowing the built-in one. Therefore the default implementation proof must be same-slice cutover removal, not eventual cleanup.

If a temporary compatibility stub is truly unavoidable, it must satisfy all of the following constraints:

- it must be explicitly non-canonical in its frontmatter/content and in any related docs
- it must not remain registered/discoverable as an ordinary `workflow` skill that wins by project precedence
- verification must prove no shadowing across at least `:psi.agent-session/skills`, `:psi.skill/by-source`, `/skills`, and `/skill:workflow`
- the blocking dependency that forced stub retention must be recorded in the task implementation notes, along with the remaining removal follow-up

In other words, the task does **not** permit a quiet transitional state where both artifacts exist and the higher-precedence project skill still answers ordinary discovery and invocation surfaces.

## Minimum authoritative reference set

The built-in workflow skill should anchor itself to the repository's actual workflow authoring and runtime surfaces rather than only restating abstract concepts.

At minimum it should point agents at the most relevant authoritative references for workflow work:

### Workflow authoring and behavior docs

- `doc/workflows.md`
- `AGENTS.md` workflow/runtime/dispatch architecture guidance where relevant

### Existing workflow artifacts and packaged built-in patterns

- `bases/main/resources/psi/skills/` as the built-in skill source root
- `.psi/workflows/` as the ordinary project workflow definition location
- concrete example workflow anchors for current repository conventions:
  - `.psi/workflows/create-task-plan.edn` as a representative multi-step packaged workflow definition
  - `.psi/workflows/review-task-design.edn` as a representative review/orchestration workflow definition
  - `.psi/workflows/planner.md` and `.psi/workflows/builder.md` as representative prompt-backed workflow companion files

### Workflow loading / discovery / compilation seams

- `components/workflow-loader/src/psi/workflow_loader/parser.clj`
- `components/workflow-loader/src/psi/workflow_loader/compiler.clj`
- `components/workflow-loader/src/psi/workflow_loader/authoring_session.clj`
- `components/workflow-loader/src/psi/workflow_loader/authoring_routing.clj`
- `components/workflow-loader/test/psi/workflow_loader/parser_test.clj`
- `components/workflow-loader/test/psi/workflow_loader/compiler_target_authoring_test.clj`
- `components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`
- `components/agent-session/src/psi/agent_session/workflow/bootstrap.clj`
- `components/agent-session/src/psi/agent_session/workflow/core.clj`
- `components/agent-session/src/psi/agent_session/workflow_execution.clj`
- `components/agent-session/src/psi/agent_session/workflow_judge.clj`

### Skill packaging / discovery seams

- `components/prompt-assets/src/psi/prompt_assets/skills.clj`
- `components/prompt-assets/test/psi/prompt_assets/skills_test.clj`
- `components/agent-session/src/psi/agent_session/resolvers/discovery.clj`
- `components/agent-session/src/psi/agent_session/commands.clj`

The skill does not need to be an exhaustive architecture document, but it should direct the agent toward this authoritative set instead of relying on generic workflow advice.

## Scope clarification

The built-in `workflow` skill is not just for greenfield workflow creation. It should explicitly help with:

- understanding an existing workflow before changing it
- creating a new workflow
- updating an existing workflow
- checking workflow authoring choices against current repository conventions
- locating the right files and tests when a workflow change is required

It should remain concise and practical for active implementation work.

## Verification definition

For this task, successful replacement should be proved through structural surfaces rather than by freezing the entire prose of the skill.

Verification should cover at least:

1. **Packaged built-in resource presence**
   - the authored file exists at `bases/main/resources/psi/skills/workflow/SKILL.md`
   - the packaged built-in resource path is `psi/skills/workflow/SKILL.md`
   - built-in skill materialization/discovery can load it
2. **Built-in provenance on discovery**
   - built-in skill discovery returns a skill with `:name "workflow"` and `:source :built-in`
3. **Ordinary readable-file semantics**
   - the discovered built-in `workflow` skill has a materialized readable `:file-path` and `:base-dir`
   - normal skill read/invocation surfaces can read it without special-case runtime handling
4. **Normal introspection/listing surfaces**
   - representative skill discovery/introspection surfaces such as `:psi.agent-session/skills`, `:psi.skill/by-source`, and `/skills` can see `workflow` as a built-in skill
5. **Replacement of the old source of truth**
   - the implementation leaves no ambiguous pair of canonical `workflow` skills across built-in and project-local sources
   - if `.psi/skills/workflow/SKILL.md` is removed, tests/docs/discovery should reflect that removal
   - if a temporary compatibility stub remains, its non-canonical role must be explicit and verified not to shadow the built-in skill incorrectly
6. **Targeted authored-content anchors**
   - verification may assert the skill still identifies itself as the workflow authoring/update skill
   - verification may assert presence of a few design-critical references such as `doc/workflows.md` or `.psi/workflows/`
   - verification should avoid snapshotting the entire instructional prose

## Acceptance criteria

1. Psi ships a built-in packaged skill for workflow comprehension and authoring with runtime skill name `workflow`.
2. The built-in workflow skill is authored at `bases/main/resources/psi/skills/workflow/SKILL.md` and packaged as `psi/skills/workflow/SKILL.md`.
3. The built-in workflow skill is discoverable through the built-in skill mechanism with stable built-in provenance.
4. The built-in workflow skill is readable through the normal skill/artifact flow using ordinary materialized file-backed semantics.
5. The built-in workflow skill provides repository-specific guidance for understanding, creating, and updating workflows in psi.
6. The built-in workflow skill points agents at the authoritative workflow docs/code/test seams needed for routine workflow work.
7. The existing project-local `.psi/skills/workflow/SKILL.md` no longer remains an equal competing source of truth after implementation; the default required outcome is same-slice removal, with a compatibility stub allowed only as an explicitly justified temporary exception that is proven not to shadow the built-in skill.
8. Verification demonstrates packaged resource presence, built-in provenance, readable-file semantics, normal discovery/invocation usability, and unambiguous replacement of the old project-local workflow skill, including no-shadowing proof across `:psi.agent-session/skills`, `:psi.skill/by-source`, `/skills`, and `/skill:workflow` if any temporary stub remains.
