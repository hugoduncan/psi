# 186 improve project workflows — Plan

## Approach

Execute 9 ordered slices, refactors first so new workflows are authored against the final naming and prompt-extraction patterns. Each slice is independently committable and verifiable.

Slices 1–2 are pure renames/scope changes to existing files — low risk, no new runtime behavior.
Slice 3 is a structural refactor (prompt extraction) — no behavior change, just file reorganization.
Slices 4–6 are additive (new skill, new workflows).
Slice 7 is a targeted Clojure change (`compile-judge`) plus schema additions — small, testable.
Slice 8 wires structured output into the workflow EDN files — authoring-only, no runtime code change.
Slice 9 is tests — validates all prior slices compile correctly.

## Key decisions

- **Rename first, then extract, then create**: avoids duplicating prompt text that would immediately be extracted.
- **`.md` prompt files live alongside their `.edn` workflow** in `.psi/workflows/`, named `<workflow>-<step>.md`.
- **`design-steps.md` is owned by `review-task-design`** and never written by other workflows.
- **`review-task-plan` follow-ups go to `steps.md`**, not `design-steps.md`.
- **Loader tests only** for structured output assertions — no IR invocation in tests.
- **`compile-judge` `:outputs` passthrough** is the minimal fix needed before structured output can be declared on judge steps.
- **`judge-routing-result` and `pass-status-result` are additive** alongside existing `judge-review-result`.

## Risks

- Renaming `review-implementation` breaks any external references in workflow EDN files (e.g. `review-implementation-in-worktree`). Must audit all callsites before renaming.
- Prompt extraction must not change prompt semantics — extracted `.md` bodies must match original inline text exactly (except for scope-narrowing changes in slice 2).
- `compile-judge` change touches compiled IR shape — needs focused test before wiring structured output.

## Slice order

1. Rename `review-implementation` → `review-task-implementation`; update `review-implementation-in-worktree` (`:target` + `:description`).
2. Rename `review-task-until-clear` → `review-task-plan`; narrow prompts to `plan.md`/`steps.md` only; redirect follow-ups from `design-steps.md` → `steps.md`.
3. Extract inline prompts to `.md` files for `review-task-plan`, `review-step`, `implement-task`.
4. Add `review-task-docs` skill; add `review-task-docs` step to `review-task-implementation`.
5. Create `review-task-design` workflow (`.md` prompt files from the start).
6. Create `create-task-plan` workflow (`.md` prompt file from the start).
7. Extend `compile-judge` to pass through `:outputs`; add `judge-routing-result` and `pass-status-result` schema ids.
8. Adopt structured output schemas in `review-task-design`, `review-task-plan`, `review-task-implementation`, `review-step`, `implement-task`.
9. Add loader/compiler tests for all new and renamed workflow definitions.
