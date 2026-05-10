# Steps

## Phase 1 — `psi/github` extension

- [ ] Create `components/github/` component scaffold (`deps.edn`, `src/`, `test/`)
- [ ] Implement `psi.github.find-issue` with shell seam + narrowing logic
- [ ] Implement `psi.github.extension` manifest registration (tool name, fn, schema)
- [ ] Write focused unit tests for `psi.github.find-issue` using nullable shell stub
  - no candidates → `ex-info` with `:psi.github/no-matching-issue`
  - single candidate → correct structured map + slug derivation
  - multiple candidates + no narrowing → lowest number selected
  - narrowing by integer → exact match
  - narrowing by URL → number extracted, correct match
- [ ] Register `psi/github {}` in `.psi/extensions.edn`
- [ ] Wire `components/github/` into Kaocha `tests.edn`
- [ ] Lint clean

## Phase 2 — `:tool` workflow step type

- [ ] Add `:tool` to the step-type enum in `psi.workflow-runtime.model`
- [ ] Add `:tool-name` and `:tool-params` fields to the step model + malli schema
- [ ] Thread `:tool` through the IR and target-IR compiler
- [ ] Add `execute-tool-fn` key to `psi.workflow-runtime.execution-adapter` contract
- [ ] Implement `:tool` branch in `psi.workflow-runtime.statechart-runtime.step-execution`
  - resolve `:tool-params` template vars
  - call `:execute-tool-fn` via adapter
  - serialize result to Markdown handoff via `tool-result->handoff-md`
  - on `:psi.github/no-matching-issue` → terminal error transition
- [ ] Implement `tool-result->handoff-md` serializer (generic map → `## Handoff Data` bullets)
- [ ] Wire `execute-tool-fn` in `psi.agent-session.context/workflow-execution-adapter`
- [ ] Write focused workflow-runtime test for `:tool` step — proves no session spawned, correct yield
- [ ] Lint clean

## Phase 3 — Workflow update

- [ ] Update `gh-issue-refine.md`: replace `discover` `:delegate` step with `:tool` step
- [ ] Smoke test: run `gh-issue-refine` end-to-end against a real labeled issue, confirm discover step emits correct handoff
- [ ] Verify downstream steps (`worktree`, `refine-design`) parse the new handoff correctly (format unchanged)

## Phase 4 — Coherence

- [ ] Update CHANGELOG.md under `[Unreleased]`
- [ ] Commit all changes with appropriate symbols
