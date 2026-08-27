# Plan

## Approach

Implement the blocked outcome as authored workflow policy while retaining the existing generic workflow runtime and routing operations.

1. Extend the implementation-pass prompt contract first so a blocked pass durably appends the exact two-field `IMPLEMENTATION_BLOCKER` block before returning `PASS_STATUS: IMPLEMENTATION_BLOCKED`.
2. Reshape `implement-task.edn` into three authored routes using `workflow/exact-marker-routing`: bounded repeat, normal terminal summary, and blocked terminal summary. Keep both terminal summaries explicit and export a branch-matching `IMPLEMENTATION_STATUS` marker.
3. Insert an exact-marker gate in `task-lifecycle.edn` immediately after the `implement-task` delegate. Route completion to implementation review and blocked status to a dedicated handback that cannot reach review or extraction.
4. Prove the contracts at the narrowest existing seams: workflow definition/loader tests for authored topology and prompt contracts; routing/execution tests for all statuses, malformed markers, terminal-outcome projection, delegate yield, blocker selection, and the blocked lifecycle boundary.
5. Synchronize user-facing workflow documentation and run focused workflow-loader and agent-session verification.

Key decisions:

- Status vocabulary, branch topology, blocker syntax, and handback policy remain in authored workflow definitions/prompts; do not add an implementation-blocked runtime primitive or modify `workflow/pass-status-routing`.
- Use `workflow/exact-marker-routing` for both new gates because their raw route names are authored policy rather than the fixed `DONE`/`REPEAT` family.
- Treat the last complete blocker block in file order as authoritative. Missing or incomplete records must fail validation rather than be inferred from prose. A generic resolver-backed final-complete-block gate accepts caller-authored delimiters, field prefixes, and route so the workflow owns blocker syntax and policy.
- Preserve the existing `MORE_WORK_REMAINS` maximum of 20 iterations and the current normal completion behavior.
- Characterize and rely on existing `:terminal-outcome :step-id` result projection. Change generic runtime code only if executable proof reveals that the documented existing behavior is defective; any such finding must be reconciled with the design before proceeding.
- Assert observable workflow state and yielded text, not child-session interaction counts alone.

## Risks

- Branch declaration order could accidentally determine the projected result if tests do not explicitly prove `:terminal-outcome :step-id` selection for both terminal summaries and delegated yield.
- A summary prompt could infer a blocker from surrounding prose or select a stale/malformed record unless tests exercise multiple, incomplete, and malformed blocks.
- Adding the lifecycle gate at the wrong location could allow implementation review or knowledge extraction to start on the blocked route.
- Existing consumers of `implement-task` may assume its current summary shape; exported `IMPLEMENTATION_STATUS` lines must be additive and branch-correct without weakening existing handoff fields.
- Broad runtime changes would violate the authored-policy boundary and risk conflating a clean handback with runtime `:blocked`, failure, cancellation, malformed output, or iteration exhaustion.
- The worktree contains unrelated changes; implementation commits must stage only task-scoped files.

## Slice order

### Slice 1 — Implementation-pass blocked contract

Update the implementation-pass prompt and definition-level proofs for the exact third `PASS_STATUS` and durable blocker-record requirements.

### Slice 2 — Standalone implement-task routing and terminal handbacks

Replace the two-route judge with exact-marker routing, add the blocked terminal summary, export branch-specific `IMPLEMENTATION_STATUS`, and prove repeat/completion/blocked routing plus terminal projection and blocker selection.

### Slice 3 — Task-lifecycle implementation gate

Add the deterministic post-delegate gate and blocked lifecycle handback, then prove completion alone reaches review while blocked execution reaches neither review nor extraction.

### Slice 4 — Documentation and integrated verification

Update workflow documentation, run focused loader/runtime tests and lint, inspect the final diff for design coherence, and record verification in task artifacts.
