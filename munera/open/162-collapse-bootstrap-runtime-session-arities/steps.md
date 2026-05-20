# 162 — Steps

- [ ] Step 1: Collapse 3+4 arities into single `(ctx ai-model opts)` with `:session-id` in opts
  - [ ] Merge 4-arity body into 3-arity (read `:session-id` from opts, default to `create-initial-startup-session!`)
  - [ ] Remove 4-arity from `bootstrap-runtime-session!`
  - [ ] Update `main.clj` `:bootstrap-fn!` to pass `(assoc opts :session-id session-id)` instead of positional arg
  - [ ] Update `start-tui-runtime!` test mock to single 3-arity form
  - [ ] Run tests, commit
- [ ] Step 2: Create test helper `bootstrap-fresh-session!`
  - [ ] Create `components/app-runtime/test/psi/app_runtime/test_support.clj` with `bootstrap-fresh-session!`
  - [ ] Helper: `(ai-model opts)` → calls `create-runtime-session-context` → calls `bootstrap-runtime-session!` → returns merged result
  - [ ] Run tests (helper exists but no callers yet), commit
- [ ] Step 3: Migrate 2-arity callers → test helper, remove 2-arity
  - [ ] Replace all `(#'app-runtime/bootstrap-runtime-session! ai-model opts)` calls with `(test-support/bootstrap-fresh-session! ai-model opts)`
  - [ ] Replace all `(app-runtime/bootstrap-runtime-session! ai-model opts)` calls with `(test-support/bootstrap-fresh-session! ai-model opts)`
  - [ ] Remove 2-arity from `bootstrap-runtime-session!`
  - [ ] Update docstring
  - [ ] Run tests, commit
