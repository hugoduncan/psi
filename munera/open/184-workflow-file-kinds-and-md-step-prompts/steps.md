# Steps

- [ ] Update workflow loader discovery so workflow roots scan both `.md` and `.edn` files, preserve same-kind later-wins precedence, and fail clearly for mixed-kind duplicate workflow names.
- [ ] Add/reshape parser and compiler seams so `.md` files compile as single-step markdown workflows and `.edn` files compile as multi-step workflow definitions.
- [ ] Implement single-step `.md` validation and compilation: required `name`/`description`, explicit allowed frontmatter keys, non-empty markdown body, no EDN workflow-definition block, exactly one canonical `:session` step.
- [ ] Implement `.edn` `:session` step `:prompt-workflow` support with relative path resolution, wrong-kind/missing-target errors, dual-prompt-source rejection, and step-local override of referenced markdown config.
- [ ] Add focused tests for mixed file-kind discovery, duplicate-name collision behavior, single-step markdown compilation/validation, and `:prompt-workflow` reference/merge/error cases.
- [ ] Update workflow authoring/reload docs to describe `.md` vs `.edn` workflow kinds, `.psi/workflows/` discovery, `:prompt-workflow` reuse, and the transitional status of existing checked-in multi-step `.md` built-ins/examples until a follow-on migration task moves them to `.edn`.
- [ ] Run focused verification for loader/compiler/runtime/doc-adjacent tests and record results.
