2026-05-05
- Task created as the explicit follow-on to `092-delegate-result-surface-convergence-and-rich-example-migration`.
- Rationale: `092` correctly solved the minimum canonical delegated yielded-text seam, but realistic multi-phase orchestration still lacks a canonical structured inter-workflow handoff contract.
- This task exists to solve that broader contract/dataflow problem explicitly rather than letting `gh-bug-triage-modular` remain blocked on hidden assumptions or text parsing.
- Relationship to adjacent tasks:
  - `092` established delegated `:yield :text` as the minimal downstream-consumable delegated result surface
  - `090` remains the eventual compatibility-retirement task; this task is intended to remove another substantive blocker by making realistic multi-phase target-authored delegation viable
  - `077` remains the umbrella migration context for the converged target grammar and canonical IR runtime

2026-05-05 ambiguity review
- Found one actionable ambiguity: the design strongly preferred a dual-plane model but did not require explicit first-cut decisions for declaration shape, caller-side ref shape, standard contract keys, or fallback behavior for undeclared structured exports.
- Resolved by tightening the design, plan, steps, and acceptance so the task must make one explicit decision for each of those items rather than leaving multiple plausible implementation paths.
- Found a second actionable ambiguity: the anchor proof requirement did not state the minimum proof strength for `gh-bug-triage-modular` specifically, so a migration could overclaim without proving both structured-handoff consumption and yielded-text chaining.
- Resolved by tightening the anchor requirement to require both surfaces distinctly when `gh-bug-triage-modular` is the proof target.

2026-05-05 implementation
- Chose the first-cut workflow-level declaration shape as `:terminal-contract {:handoff {:type :markdown-handoff-data}}` on target-authored workflow definitions.
- Chose the caller-side consumption surface as delegate-step `:output :handoff`, leaving delegate yielded text on `:yield :text`.
- Chose `:handoff` as the only new standard structured export key in this slice; `:transcript` remains a separate normal output surface and `:result` remains non-primary/runtime-oriented.
- Chose explicit fallback behavior: workflows without a declared terminal handoff contract should not be relied on for `:output :handoff`.
- Added `workflow_terminal_contract.clj` to own terminal text extraction and `## Handoff Data` parsing.
- Delegate runtime now merges parsed handoff data into the delegating step's accepted-result `:outputs` while preserving the delegated terminal yielded text separately as `:final-llm-reply`.
- Added focused proofs that delegated yielded text and delegated handoff are distinct downstream surfaces.
- Extended target-authored workflow-file compilation to preserve `:terminal-contract` from file config.
- Migrated `gh-bug-discover-and-read`, `gh-issue-create-worktree`, `gh-bug-reproduce`, and `gh-bug-post-repro` to target-authored single-step workflows with explicit terminal handoff declarations.
- Migrated `gh-bug-triage-modular` to target-authored delegate syntax using yielded text for immediate asks and structured `:handoff` outputs for machine-facing orchestration.
- Updated workflow docs and grammar docs to teach the dual-plane delegated contract explicitly.
- Net effect: this removes a substantive blocker for task `090` by making the realistic bug-triage orchestration example target-authored without falling back to current-authored compatibility wiring.
