# Plan

1. Create the required Munera execution artifacts for this task and keep them aligned with the refined design.

2. Update workflow discovery/loading design and docs to support both `.md` single-step workflows and `.edn` multi-step workflows in the existing workflow roots, including:
   - broadening loader file discovery beyond `.md`
   - preserving root precedence for same-kind duplicates
   - rejecting mixed-kind duplicate workflow names
   - broadening `/delegate-reload` documentation and any built-in discovery wording from `.psi/workflows/*.md` to `.psi/workflows/` plus file kind rules
   - explicitly documenting that currently checked-in multi-step `.psi/workflows/*.md` built-ins/examples remain transitional compatibility artifacts during this task rather than being bulk-migrated here

3. Split loader/compiler responsibilities by file kind:
   - `.md` parser/compiler path validates the new single-step markdown contract
   - `.edn` parser/compiler path validates multi-step workflow definitions directly
   - both feed the canonical workflow-definition registry/runtime shape

4. Implement standalone `.md` compilation to exactly one canonical `:session` step, with frontmatter mapped onto existing session-step fields and markdown body mapped to the sole prompt source.

5. Implement `.edn` `:session` step reuse of `.md` prompt workflows through `:prompt-workflow`, including:
   - relative path resolution from the consuming `.edn` file
   - wrong-kind/missing-file/invalid-target errors
   - dual-prompt-source rejection
   - step-local override of referenced markdown frontmatter config

6. Update validation and focused proof coverage for:
   - supported/unsupported frontmatter keys
   - empty body and EDN-in-markdown rejection
   - mixed `.md`/`.edn` same-name collisions
   - `:prompt-workflow` path resolution and merge semantics
   - `/delegate-reload` + loader behavior across both file kinds

7. Update user docs (`README.md` if needed, `doc/workflows.md`, and any workflow-authoring references touched by the implementation) so file-kind rules, discovery paths, standalone markdown workflow authoring, `.edn` prompt-workflow reuse, and the transitional status of existing checked-in multi-step `.md` workflows are explicit and synchronized with the implementation.
