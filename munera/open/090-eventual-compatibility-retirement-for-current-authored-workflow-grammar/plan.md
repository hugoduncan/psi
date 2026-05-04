Approach:
- treat retirement as the last migration step, not as an opportunistic cleanup hidden inside earlier implementation work
- define explicit gates so removal happens only after examples, runtime, docs, and dependent workflows are ready
- remove compatibility surgically and verify that the remaining model is simpler and coherent rather than merely smaller
- keep the cleanup user- and author-facing: docs and guidance matter as much as code deletion here

Likely steps:
1. inventory remaining current-authored workflow definitions, tests, loaders, and docs
2. define explicit retirement gates for migrated workflows, runtime support, and documentation readiness
3. migrate or replace remaining blockers
4. remove current-authored grammar loading/compilation support
5. delete compatibility-only tests/docs/helpers no longer needed
6. update guidance so the target grammar is the sole supported authored surface
7. run focused and broader verification to prove the simplified model still works end-to-end

Proof target:
- the project can author and execute workflows using only the target grammar plus the normalized IR runtime model

Risks:
- hidden dependencies on current-authored grammar may survive in tests, examples, or loader seams
- removing compatibility too early could strand workflows not yet migrated
- leaving stale docs behind would preserve conceptual confusion even if code cleanup is complete
