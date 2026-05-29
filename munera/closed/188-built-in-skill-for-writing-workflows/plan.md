# Plan

## Approach

Implement the task as a same-slice workflow-skill cutover: add the built-in packaged `workflow` skill, migrate/refine the existing workflow-authoring guidance into that built-in artifact, remove the project-local `.psi/skills/workflow/SKILL.md` in the same implementation slice, and add focused proof that ordinary discovery, listing, and invocation surfaces now resolve `workflow` as a built-in skill.

1. Add the built-in skill source file at `bases/main/resources/psi/skills/workflow/SKILL.md`, preserving runtime identity `workflow` and carrying forward the repository-specific workflow authoring/update guidance from the current project-local skill.
2. Reuse the existing built-in skill packaging/materialization/discovery path established by task 185 rather than introducing any special registration path for this skill.
3. Remove `.psi/skills/workflow/SKILL.md` in the same slice so current precedence (`project > user > built-in`) cannot leave the project-local artifact shadowing the built-in replacement.
4. Update any prompt/bootstrap/docs references that still point to the old project-local path so the built-in skill becomes the single canonical source of truth.
5. Add or extend focused tests proving packaged-resource presence, built-in provenance, readable materialized file semantics, focused resolver/discovery visibility on `:psi.agent-session/skills` and `:psi.skill/by-source`, and representative command/listing/invocation behavior for `workflow`.
6. Verify the authored built-in skill content still points agents at the repository’s canonical workflow docs, grammar docs, example workflows, loader seams, runtime seams, and tests named in the design.

## Key decisions

- Canonical runtime skill name remains `workflow`.
- Canonical source-tree authoring path is `bases/main/resources/psi/skills/workflow/SKILL.md`.
- Canonical packaged resource path is `psi/skills/workflow/SKILL.md`.
- Default cutover is same-slice removal of `.psi/skills/workflow/SKILL.md`; a compatibility stub is not the planned path.
- The built-in skill should preserve the current workflow skill’s useful guidance while refining it around the repository’s current canonical workflow docs and seams.
- Verification should prefer structural assertions over full-prose snapshots.

## Risks

- Because `project` skills outrank `built-in`, forgetting same-slice removal would silently defeat the replacement and leave the wrong artifact active.
- Existing tests may currently prove built-in skill mechanics generically but not the specific `workflow` identity across `/skills`, `/help`, resolver discovery, and `/skill:workflow` surfaces.
- The migrated skill prose could drift toward abstract workflow advice; keep it concise and anchored to the explicit authoritative references in the design.
- Prompt/bootstrap references may still mention the old project-local skill location even after runtime discovery is correct.

## Non-goals for implementation

- No redesign of workflow runtime semantics, grammar, loader behavior, or registry behavior beyond what the built-in skill replacement itself requires.
- No broad migration of unrelated project-local skills to built-ins.
- No coexistence period where the old project-local `workflow` skill remains a normal discoverable skill that can shadow the built-in replacement.
