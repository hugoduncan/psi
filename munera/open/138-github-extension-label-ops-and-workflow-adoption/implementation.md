# Implementation Notes

## 2026-05-10 — Task created

Initial scope survey:

**Extension current state:**
- `psi.github.extension/init` registers exactly one operation: `github/find-issue`
- `psi.github.find-issue` uses `derive-slug` inline (not shared); must be extracted first
- `psi.github.extension-test` asserts exactly one registration — will need update

**Workflows using inline `gh issue list` (need find-issue or find-pr adoption):**
- `gh-bug-discover-and-read` — full AI session for discovery
- `gh-bug-triage` — flat single-session, does both discovery and label mutation inline
- `gh-issue-ingest` — flat single-session, does both inline
- `gh-issue-implement` — dedicated discover session using `gh pr list`
- `gh-pr-fix-checks` — dedicated discover session using `gh pr list`

**Workflows using inline `gh issue edit` / `gh pr edit` for labels only:**
- `gh-bug-post-repro` — AI classify session emits label instructions
- `gh-bug-request-more-info` — AI post session emits label instructions
- `gh-issue-refine` — AI publish session emits label instructions (issue + PR)
- `gh-bug-fix-and-pr` — AI implement session emits label instruction (remove `fix`)

**Key design note on conditional label steps:**
`gh-bug-triage` and `gh-bug-post-repro` branch: add `waiting` OR add `fix` depending on
reproduction outcome.  The current workflow IR does not support conditional `:invoke`
steps.  Resolution: keep the AI session responsible for posting the comment and
signalling outcome via handoff data (`result_type: waiting-for-reporter` vs
`repro-ready-for-fix`); add two unconditional label `:invoke` steps after the classification
AI session — but only for the label that is always applied (remove `triage` is always
done).  The conditional add (`waiting` vs `fix`) must stay AI-driven until the workflow
IR gains conditional branching.  Document this explicitly in the steps for those two
workflows so the builder does not over-migrate.

**`gh-pr-fix-checks` note:**
PR discovery is the only change here; no label mutation occurs in this workflow.

**`gh-issue-refine` note:**
Two label targets: remove `refine` from the *issue*, add `waiting` to the *PR*.  Both
are now deterministic `:invoke` steps with explicit `:target` args.
