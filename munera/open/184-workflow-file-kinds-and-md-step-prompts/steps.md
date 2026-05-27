# Steps

- [x] Update workflow loader discovery so workflow roots scan both `.md` and `.edn` files, preserve same-kind later-wins precedence, and fail clearly for mixed-kind duplicate workflow names.
- [x] Add/reshape parser and compiler seams so `.md` files compile as single-step markdown workflows and `.edn` files compile as multi-step workflow definitions.
- [x] Implement single-step `.md` validation and compilation: required `name`/`description`, explicit allowed frontmatter keys, non-empty markdown body, no EDN workflow-definition block, exactly one canonical `:session` step.
- [x] Implement `.edn` `:session` step `:prompt-workflow` support with relative path resolution, wrong-kind/missing-target errors, dual-prompt-source rejection, and step-local override of referenced markdown config.
- [x] Add focused tests for mixed file-kind discovery, duplicate-name collision behavior, single-step markdown compilation/validation, and `:prompt-workflow` reference/merge/error cases.
- [x] Update workflow authoring/reload docs to describe `.md` vs `.edn` workflow kinds, `.psi/workflows/` discovery, `:prompt-workflow` reuse, and the transitional status of existing checked-in multi-step `.md` built-ins/examples until a follow-on migration task moves them to `.edn`.
- [x] Run focused verification for loader/compiler/runtime/doc-adjacent tests and record results.
- [x] Align focused mixed-kind collision proof and residual docs/comments with the finalized mixed-kind duplicate-name load-error contract.
- [x] Reject non-relative `:prompt-workflow` references so absolute paths and `..` escapes outside the consuming workflow authoring tree fail clearly, and add focused compiler proof for the relative-only contract.
- [ ] Migrate checked-in `.psi/workflows/` artifacts so repository examples match the new file-kind contract: move multi-step EDN-bodied markdown workflows to `.edn`, and reshape any intended standalone prompt workflows into valid single-step markdown.
  - The current focused migration proof still fails because checked-in `planner.md`, `builder.md`, and `reviewer.md` remain legacy EDN-bodied markdown and many other `.psi/workflows/*.md` artifacts are still multi-step transitional files.
- [ ] Add focused proof that the checked-in `.psi/workflows/` corpus matches the finalized file-kind split contract after artifact migration, so repo-level validation covers both valid standalone single-step markdown workflows and migrated multi-step `.edn` workflows.
- [ ] Resolve the repository-level mixed-kind collision state in `.psi/workflows/`: do not leave sibling same-name `.md` + `.edn` files checked in under the finalized loader contract. Either revert to the explicit deferred-migration compatibility shape or complete the file-kind migration atomically so checked-in workflow discovery is loadable again.
- [x] Broaden `workflow-migration-validation-test` from a curated-required-workflow subset to corpus-wide contract validation, so the checked-in workflow tree cannot silently drift into invalid mixed-kind collisions or malformed migrated artifacts outside the named sample set.
