# Plan — 137 gh-issue-refine: deterministic discover step

## Approach

Two phases. Phase 1 builds and tests the `psi/github` extension with the `github/find-issue` deterministic operation. Phase 2 wires it into `gh-issue-refine.md` and verifies end-to-end.

No new step types, IR schema changes, or execution-adapter keys are needed — the existing `:invoke` step type + deterministic-operation-registry handles everything.

## Phase ordering

### Phase 1 — `psi/github` extension (prerequisite for Phase 2)

1. Scaffold `extensions/github/` (`deps.edn` with `cheshire 5.13.0`, `src/`, `test/`)
2. Implement `psi.github.find-issue/invoke` with `:github-shell-fn` seam, JSON parsing via cheshire, narrowing logic, slug derivation, and `result->handoff-md` serializer
3. Implement `psi.github.extension/init` registering the `github/find-issue` operation
4. Write focused unit tests (nullable shell stub): no candidates → error; single candidate → correct map + slug; multiple → lowest number; narrowing by integer; narrowing by URL
5. Register `psi/github {}` in `.psi/extensions.edn`; add `psi/github {:local/root "extensions/github"}` to root `deps.edn` `:deps`; add `extensions/github/src` to `:run`, `:psi`, `:tui-demo`, `:test-paths`, `:test` aliases; add `extensions/github/test` to `:test-paths` and `:test` aliases
6. Wire `extensions/github/` into Kaocha `tests.edn` `:extensions` suite (test-paths + source-paths)
7. Lint clean

### Phase 2 — Workflow update (requires Phase 1 complete)

1. Update `gh-issue-refine.md`: replace `discover` `:delegate` step with `:invoke` step (`operation "github/find-issue"`, `:outputs {:summary {:source :invoke/summary}}`, `:yields {:type :text :text :summary}`)
2. Write focused workflow-runtime integration test: prove `:invoke` step with `github/find-issue` operation produces correct Markdown handoff and no session is spawned
3. Smoke test: run `gh-issue-refine` end-to-end against a real labeled issue, confirm discover step emits correct handoff
4. Verify downstream steps (`worktree`, `refine-design`) parse the new handoff correctly

### Phase 3 — Coherence

1. Update CHANGELOG.md under `[Unreleased]`
2. Commit all changes with appropriate symbols

## Risks

- Shell output format of `gh issue list --json` may vary across `gh` CLI versions — pin the exact JSON fields used and test with the nullable stub to isolate.
- The `:summary` field on operation results is optional in the registry schema — confirm it passes through `operation-result->invoke-step-result` correctly (it does: the `:summary` key is conditionally assoc'd into `:outputs`).
