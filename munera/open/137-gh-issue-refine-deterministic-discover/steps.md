# Steps

## Phase 1 — `psi/github` extension

- [ ] Create `extensions/github/` extension scaffold (`deps.edn` with `cheshire/cheshire "5.13.0"`, `src/`, `test/`)
- [ ] Implement `psi.github.find-issue` with `:github-shell-fn` seam, cheshire JSON parsing, narrowing logic, slug derivation, and `result->handoff-md` Markdown serializer
- [ ] Implement `psi.github.extension` registering the `github/find-issue` deterministic operation via `(:register-operation api)`
- [ ] Write focused unit tests for `psi.github.find-issue` using nullable shell stub
  - no candidates → `{:status :error :reason :psi.github/no-matching-issue ...}`
  - single candidate → correct structured map + slug derivation in `:data`; correct `## Handoff Data` in `:summary`
  - multiple candidates + no narrowing → lowest number selected
  - narrowing by integer → exact match
  - narrowing by URL → number extracted, correct match
- [ ] Register `psi/github {}` in `.psi/extensions.edn`; add `psi/github {:local/root "extensions/github"}` to root `deps.edn` `:deps`; add `extensions/github/src` to `:run`, `:psi`, `:tui-demo`, `:test-paths`, `:test` aliases; add `extensions/github/test` to `:test-paths` and `:test` aliases
- [ ] Wire `extensions/github/` into Kaocha `tests.edn` `:extensions` suite (add `extensions/github/test` to `:test-paths`, `extensions/github/src` to `:source-paths`)
- [ ] Lint clean

## Phase 2 — Workflow update

- [ ] Update `gh-issue-refine.md`: replace `discover` `:delegate` step with `:invoke` step
  - `{:name "discover" :type :invoke :operation "github/find-issue" :args {:labels ["enhancement" "refine"] :input {:from :workflow-input :path [:input]}} :outputs {:summary {:source :invoke/summary}} :yields {:type :text :text :summary}}`
- [ ] Write focused workflow-runtime integration test: `:invoke` step with `github/find-issue` produces correct Markdown handoff, no session spawned
- [ ] Smoke test: run `gh-issue-refine` end-to-end against a real labeled issue, confirm discover step emits correct handoff
- [ ] Verify downstream steps (`worktree`, `refine-design`) parse the new handoff correctly (format unchanged)

## Phase 3 — Coherence

- [ ] Update CHANGELOG.md under `[Unreleased]`
- [ ] Commit all changes with appropriate symbols
