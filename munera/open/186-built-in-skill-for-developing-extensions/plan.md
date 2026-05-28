# Plan

## Approach

Implement the task as a built-in packaged skill addition plus focused proof updates.

1. Add the built-in skill source file at `bases/main/resources/psi/skills/extension-development/SKILL.md` with concise repository-specific guidance for creating, modifying, and debugging extensions, preserving packaged resource identity `psi/skills/extension-development/SKILL.md`.
2. Reuse the existing built-in skill materialization/discovery path in `components/prompt-assets/src/psi/prompt_assets/skills.clj` rather than introducing a new registration mechanism.
3. Add or extend focused tests around packaged built-in skill discovery so verification proves:
   - source-tree authoring path and packaged resource presence for `psi/skills/extension-development/SKILL.md`
   - runtime identity `:name "extension-development"`
   - built-in provenance `:source :built-in`
   - readable materialized `:file-path`
   - normal invocation/readability semantics
4. Add focused proof for representative higher discovery/listing surfaces already named in the design, such as resolver discovery output and `/skills` command visibility, so verification matches the stated discovery/listing acceptance target.
5. Update any task-local documentation/proof notes needed to reflect the implemented identity and verification decisions.

## Key decisions

- Canonical skill identity is `extension-development`.
- Canonical source-tree authoring path is `bases/main/resources/psi/skills/extension-development/SKILL.md`.
- Canonical packaged resource path is `psi/skills/extension-development/SKILL.md`.
- Scope includes creating, modifying, and debugging extensions.
- The minimum authoritative references should center on `doc/extension-api.md`, `doc/extensions.md`, `doc/extensions-install.md`, `doc/architecture.md`, and the built-in skill/discovery implementation seams.
- Verification should prefer structural assertions over exact full-prose snapshots.

## Risks

- Existing built-in skill tests may currently exercise only fixture roots; implementation may need an additional focused proof that targets the production resource root without making tests brittle.
- The skill prose could become too broad; keep it concise and seam-oriented.
- Discovery proofs must confirm built-in provenance and ordinary file semantics, not just that the file exists in the repo.

## Non-goals for implementation

- No changes to extension architecture or lifecycle semantics unless a true packaging/discovery gap appears.
- No broad rework of skill discovery beyond what the new built-in skill and its proofs require.
