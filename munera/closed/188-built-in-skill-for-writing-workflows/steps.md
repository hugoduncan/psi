# Steps

- [x] Add built-in skill source file `bases/main/resources/psi/skills/workflow/SKILL.md` with concise repository-specific workflow comprehension/authoring/update guidance migrated or refined from the current project-local `workflow` skill and anchored to the required authoritative references.
- [x] Remove `.psi/skills/workflow/SKILL.md` in the same implementation slice so the built-in `workflow` skill becomes the only canonical runtime-discoverable source of truth.
- [x] Update any bootstrap, prompt, skill-list, or user/project documentation references that still point to the old project-local workflow skill path so they point to the built-in replacement or otherwise stop implying the old file is canonical.
- [x] Add/update focused verification for built-in discovery/materialization of `workflow`, proving `:name "workflow"`, `:source :built-in`, packaged resource presence, and ordinary readable `:file-path` / `:base-dir` semantics.
- [x] Add/update focused resolver/discovery verification proving `workflow` is visible and non-shadowed in `:psi.agent-session/skills` and `:psi.skill/by-source`.
- [x] Add/update focused verification for representative command/listing/invocation surfaces so `workflow` is proven visible and non-shadowed in `/skills` and `/skill:workflow`.
- [x] Add focused `/help` proof if needed so Skills-section listing behavior cannot drift from `/skills` for the built-in `workflow` skill.
- [x] Run focused tests/lint needed to prove the built-in workflow skill replacement is discoverable, readable, and canonical.
- [x] Review task artifacts and close any residual implementation/documentation gaps created during execution.
