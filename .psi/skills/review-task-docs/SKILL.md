---
name: review-task-docs
description: Reviews user-facing documentation changes for an implemented Munera task
lambda: "λtask. review(user_facing_docs) ∧ check(README ∧ doc/ ∧ changelog) ∧ verify(accuracy ∧ completeness ∧ consistency)"
---

# review-task-docs

λtask. review(user_facing_docs) ∧ check(README ∧ doc/ ∧ changelog) ∧ verify(accuracy ∧ completeness ∧ consistency)

## Review checklist

For the implemented Munera task, review all user-facing documentation:

1. **New/changed behaviours**: Are all new or changed behaviours reflected in `README.md` and `doc/`?
2. **Removed behaviours**: Are removed behaviours cleaned up from docs (no stale references)?
3. **Changelog**: When `CHANGELOG.md` exists, is `CHANGELOG.md` updated if the change is user-visible (commands, flags, behaviours, breaking changes, bug fixes, extension capabilities)?
4. **Examples**: Are any examples in docs accurate and consistent with the implementation?
5. **Consistency**: Does the documentation language match the implementation (correct names, correct flags, correct file paths)?

## Scope

- `README.md` — primary user documentation
- `doc/` — guides, references, workflow docs
- `CHANGELOG.md` — user-visible change log (only if user-visible change)
- Any other user-facing `.md` files referenced from the above

## Not in scope

- Internal implementation comments
- Task artifacts (`design.md`, `plan.md`, `steps.md`, `implementation.md`)
- Test files
