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
- [ ] Add focused proof that transitional checked-in multi-step `.psi/workflows/*.md` artifacts still parse/compile/load during this task's deferred-migration scope, so the repo-level validation suite covers the documented compatibility contract.
