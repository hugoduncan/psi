# 162 — Plan

## Approach

Three sequential steps, each independently testable:

1. **Collapse 3+4 arities** — Merge the 3-arity and 4-arity into a single `(ctx ai-model opts)` form that reads optional `:session-id` from opts. Update `main.clj` to pass `:session-id` in opts. Update the `start-tui-runtime!` test mock to a single arity.

2. **Extract test helper** — Create `psi.app-runtime.test-support/bootstrap-fresh-session!` in `components/app-runtime/test/psi/app_runtime/test_support.clj`. This wraps `create-runtime-session-context` + `bootstrap-runtime-session!` for the "create everything from ai-model + opts" pattern.

3. **Remove 2-arity** — Replace all 2-arity `(bootstrap-runtime-session! ai-model opts)` callers with the new test helper. Delete the 2-arity from the production function.

## Decisions

- Steps 1→2→3 must be sequential: step 3 depends on the helper from step 2, step 2 depends on the collapsed arity from step 1.
- Each step produces a green test run and a commit.

## Risks

- The `start-tui-runtime!` test mock is sensitive to arity — must be updated in step 1 alongside the production code.
- Test helper must extract `create-runtime-session-context` keys correctly; validated against existing 2-arity body.
