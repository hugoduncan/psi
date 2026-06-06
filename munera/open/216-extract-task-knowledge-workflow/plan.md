# Plan

## Approach

Implement the task as a small workflow-definition vertical slice plus docs/release-note synchronization. The mementum protocol/meta exception and task design are already settled; this plan does not change mementum code.

Key implementation decisions:

- Author the standalone workflow as exactly `.psi/workflows/extract-task-knowledge.md`, a single-step markdown prompt workflow. Do **not** create `.psi/workflows/extract-task-knowledge.edn`.
- Give the markdown workflow only the tools it needs to inspect artifacts/history and author mementum files: `read`, `bash`, and `write`.
- Put the extraction policy in the prompt body: resolve the task slug; enforce standalone closed-task-only extraction; allow open-task extraction only when lifecycle context proves a successful immediately preceding `review-task-implementation`; restrict git-history inspection to the task directory, task id/slug message matches, and SHAs recorded in task artifacts; recall existing `mementum/` entries; apply gate-1/gate-2, project-generality, significance, dedupe, and `uncertain → skip`; commit any autonomous mementum writes using mementum conventions; report zero extraction as success.
- Append a final `:delegate` step to `.psi/workflows/task-lifecycle.edn` targeting `extract-task-knowledge`. The step passes the same original task `:input` via `:prompt-string` and forwards both `:workflow-original` and the `review-task-implementation` yielded text as context, so the final lifecycle yield can preserve the review/lifecycle outcome alongside the extraction outcome.
- Add workflow-loader coverage for the new markdown workflow definition, the absence of the same-name `.edn` collision, the prompt's safety/eligibility/dedupe/history contracts, and the new trailing `task-lifecycle` delegate wiring/context.
- Update `doc/workflows.md` and `CHANGELOG.md` `[Unreleased]` for the new `/delegate extract-task-knowledge` workflow and changed `task-lifecycle` terminal behavior.

## Risks

- **Autonomous mementum noise.** The workflow intentionally bypasses the normal human approval gate for a narrow source. Mitigation: prompt-level conservative filters, dedupe recall, `uncertain → skip`, and explicit project-general/significant requirements.
- **Munera completion-boundary drift.** Standalone extraction from `munera/open/` would violate location-based task state. Mitigation: prompt and tests lock closed-only standalone behavior; open extraction is allowed only with lifecycle predecessor context.
- **Workflow kind collision.** A same-name `.edn` plus `.md` would make workflow loading fail. Mitigation: create only `.md` and test there is no `.edn` sibling.
- **Task-lifecycle terminal regression.** Adding extraction as the last step changes the lifecycle's final yield. Mitigation: forward review output as context and prompt the extraction workflow to include the prior lifecycle/review outcome in its final summary; test the wiring.
- **Prompt-only dedupe is imperfect.** There is no deterministic dedup implementation in scope. Mitigation: make recall/update/skip instructions explicit and conservative; do not add mementum extension code.
- **Documentation/changelog drift.** The workflow is user-visible and changes lifecycle behavior. Mitigation: update `doc/workflows.md` and `[Unreleased]` entries in the same implementation pass.

## Slice order

1. **Standalone extraction workflow prompt** — create `.psi/workflows/extract-task-knowledge.md` with the resolved policy, tools, and final summary contract.
2. **Task-lifecycle integration** — append the trailing `extract-task-knowledge` delegate step with original input and review-output context.
3. **Workflow definition tests** — prove the new workflow loads, has no mixed-kind collision, carries the safety contracts, and `task-lifecycle` now ends with the correctly wired extraction delegate.
4. **Docs and changelog** — document `/delegate extract-task-knowledge`, the lifecycle trailing extraction behavior, zero-extraction success, and add `[Unreleased]` Added/Changed entries.
5. **Verification/coherence pass** — run focused workflow-loader tests, lint/format as needed, inspect diffs for scope, and record implementation notes.
