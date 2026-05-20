# 162 — Implementation Notes

## Test review follow-up — session-id reuse test (2026-05-20)

Added `bootstrap-runtime-session-reuses-pre-created-session-test` in `app_runtime_bootstrap_test.clj`. Test pre-creates a session via `session/new-session-in!`, passes its id as `:session-id` in opts to the real `bootstrap-runtime-session!`, asserts the returned session-id matches and no extra session was created. 301 tests pass, lint clean.

## Test review (2026-05-19)

**Coverage gap — `:session-id` reuse path untested with real function.**
`bootstrap-runtime-session!` accepts `:session-id` in opts to reuse a pre-created session (skipping `create-initial-startup-session!`). No test exercises this path through the real function. The `start-tui-runtime!` test handles `:session-id` only in its mock. The `bootstrap-runtime-session-creates-initial-session-after-startup-plan-test` calls `(bootstrap-runtime-session! ctx ai-model {:cwd cwd})` — always the default "create new session" path. `main.clj` is the sole production caller of the `:session-id` path; it has no direct test coverage.

**Interaction-testing in ordering test — acceptable pragmatic choice.**
`bootstrap-runtime-session-creates-initial-session-after-startup-plan-test` asserts call ordering via `with-redefs` + `calls` atom (interaction-testing). The ordering invariant (startup plan assembled before session creation) is genuinely hard to verify via state alone. The test also asserts state (session count at each step), which partially mitigates.

**Infrastructure deps correctly nulled.** OAuth, templates, skills, system-prompt, introspection, memory-runtime all use `with-redefs` to supply nullable implementations — consistent with testing-without-mocks guidance.

**All design behaviours covered except the `:session-id` reuse path.**

## Implementation review — pass (2026-05-20)

All 7 acceptance criteria verified against code. No actionable issues found.

- Single arity `(ctx ai-model opts)` confirmed; no residual multi-arity or type-sniffing dispatch
- Test helper `bootstrap-fresh-session!` correctly splits opts between `create-runtime-session-context` and `bootstrap-runtime-session!`, forwards full opts map
- All 5 test files migrated to helper; no stale `psi.app-runtime` requires remain
- `main.clj` `:bootstrap-fn!` correctly passes `:session-id` and `:cwd` in opts
- `start-tui-runtime!` test mock updated to single 3-arity handling `:session-id` via opts
- `rpc_real_delegate_command_test` unchanged (already used target form)
- 301 bb tests + lint clean
- No new patterns duplicating existing ones; no unnecessary abstractions; no structural performance issues

**Pre-existing observation (out of scope):** `run-session` and `main.clj` `:bootstrap-fn!` reference `(:cwd ctx)` but `ctx` never contains `:cwd` — `create-runtime-session-context` returns `cwd` as a sibling, not inside `ctx`. Falls through harmlessly to `System/getProperty "user.dir"` but is fragile. Not introduced by this task.

## Design review — inconsistency follow-up (2026-05-20)

Executed 3 design-steps from inconsistency review:

1. **AC4 narrowed**: Now states only `main.clj` needs updating; `run-session`/`start-tui-runtime!` explicitly noted as already conforming.
2. **Scope reconciled**: Production callers list distinguishes `main.clj` (4-arity, needs change) from `run-session`/`start-tui-runtime!` (already 3-arity target form, no change).
3. **Mock noted in design**: New design decision documents that the `start-tui-runtime!` test mock (~line 200) covers both arities and needs updating — the change is in the mock, not in `start-tui-runtime!` itself.

All 3 items completed and marked done in design-steps.md. Design.md now internally consistent on which callers need changes.

## Design review — inconsistency pass (2026-05-20)

Three inconsistencies found between design.md claims and actual codebase:

1. **Scope/AC4 overstates production caller changes**: Design scope lists `run-session`, `start-tui-runtime!`, and `main.clj` as production callers needing updates. AC4 says "All production callers (`run-session`, `start-tui-runtime!`, `main.clj`) updated". But `run-session` (line 586) and `start-tui-runtime!` (line 642) already call the 3-arity target form `(ctx ai-model opts)` — they need **zero changes**. Only `main.clj` (4-arity caller) needs updating. This parallels the RPC test finding in AC6 but was not applied consistently to production callers.

2. **AC4 vs AC6 asymmetry**: AC6 correctly identifies `rpc_real_delegate_command_test` as needing no change. The same analysis should apply to `run-session` and `start-tui-runtime!` — they also already use the target form. AC4 should either narrow to `main.clj` only or explicitly note which production callers already conform.

3. **`app_runtime_test.clj` mock covers both arities**: The `start-tui-runtime!` test (line ~200) mocks `bootstrap-runtime-session!` with both a 3-arity and 4-arity form. After the refactor, this mock needs updating (remove 4-arity, handle `:session-id` in opts if needed). The design's AC5 covers this generically but the mock is noteworthy: the real change is in the mock, not in `start-tui-runtime!` itself — contradicting AC4's implication that `start-tui-runtime!` needs updating.

## Design review — ambiguity follow-up (2026-05-19)

All 6 design-steps from ambiguity review resolved by code analysis:

- **AC1 convenience arity**: No — strictly `(ctx ai-model opts)`. Zero `(ctx ai-model)` callers exist; every 2-arity call is `(ai-model opts)`.
- **Test helper opts**: Full opts map, not curated subset. Callers already pass varied subsets (`{:cwd}`, `{:persist? false}`, `{:memory-runtime-opts ...}`, etc.).
- **Test helper location**: `components/app-runtime/test/psi/app_runtime/test_support.clj`. Gordian test already has `app-runtime` dep.
- **RPC test**: No change needed — already uses 3-arity target form.
- **Session-id opts key**: `:session-id` — matches existing 4-arity binding.
- **Dead code**: `(:state* x)` → true branch has zero callers. No migration path needed.

Design.md updated with "Design decisions" section and refined ACs. All design-steps marked done.

## Design review — ambiguity pass (2026-05-19)

1. **AC1 "exactly one public arity" vs convenience 2-arg form**: AC1 says the single arity is `(ctx ai-model opts)` (3 args). Currently `(ctx ai-model)` (2 args, when `:state*` present) delegates to `(ctx ai-model {})`. Should the collapsed function also offer a 2-arg `(ctx ai-model)` convenience arity, or must callers always pass `{}`? If strictly one arity, the design should say so explicitly and note that `opts` is required.

2. **Test helper opts surface unspecified**: The 2-arity "create everything" path currently forwards `:session-config`, `:ui-type`, `:persist?`, `:session-root`, `:thinking-level-override` to `create-runtime-session-context`. The design says the test helper wraps `create-runtime-session-context` + `bootstrap-runtime-session!` but doesn't specify which opts the helper accepts. At minimum it needs `:cwd`, `:persist?`, `:session-root` (used by existing test callers). Should the helper accept the full opts map or a curated subset?

3. **Test helper location across components**: Tests using the 2-arity "create everything" form span `app-runtime` and `gordian-launcher` components. The suggested ns `psi.app-runtime.test-support` implies it lives in `components/app-runtime/test/`. The `gordian_launcher_manifest_runtime_boundary_test` already depends on `app-runtime` so this works, but the design should confirm the helper is in `components/app-runtime/test/` (not a shared test-utils component).

4. **`rpc_real_delegate_command_test` listed in scope but uses 3-arity**: This test already calls `(bootstrap-runtime-session! ctx ai-model opts)` — the target form. It doesn't use the 2-arity "create everything" path. The design lists it in scope but it may need no change at all (only if the opts key for `:session-id` changes the return shape). Clarify whether this caller needs any modification.

5. **4-arity `main.clj` caller: opts key name**: The 4-arity `(ctx session-id ai-model opts)` in `main.clj` will become `(ctx ai-model (assoc opts :session-id session-id))`. The design should confirm the opts key is literally `:session-id` (matching the existing `session-id` binding in the 4-arity body).

6. **No 2-arity `(ctx ai-model)` callers exist in production or tests**: The `(:state* x)` → true branch (interpreting 2-arity as ctx+ai-model) is documented but has zero callers. Every 2-arity call passes an ai-model map as first arg. The design correctly identifies this as dead code but could note that no migration of `(ctx ai-model)` callers is needed — only the `(ai-model opts)` callers need the test helper.
