# Mementum State

Bootstrapped on 2026-04-02.

## Current orientation
- Project: psi
- Runtime: JVM Clojure

## Key files
- `README.md` — top-level user documentation
- `META.md` — project meta model
- `munera/plan.md` — active task orchestration
- `STATE.md` — project-local state file
- `AGENTS.md` — bootstrap/system instructions

## Current work state

- Task 160 remove-mutation-mediated-bootstrap-resource-loading is now complete and closed:
  - replaced `load-startup-resources-via-mutations-in!` with direct `dispatch/dispatch!` calls for templates, skills, and tools (no more throwaway Pathom query context / mutation round-trips)
  - replaced extension-paths loop with direct `ext-rt/add-extension-in!` calls
  - removed throwaway query-context setup (`query/create-query-context`, `register-resolvers-in!`, `register-mutations-in!`) from bootstrap
  - renamed function to `load-startup-resources-in!` to reflect it no longer uses mutations
  - updated bootstrap summary map to drop the `:mutations` key
  - EQL mutations themselves preserved for external consumers
  - implementation review clean; code-shaper and test-shaper follow-ups executed
  - focused verification green; lint clean

- Task 159 app-runtime nREPL bootstrap test split is complete and closed:
  - commit `c255eace` — `⚒ 159: split app-runtime nREPL bootstrap tests`
  - focused verification green: `25 tests, 102 assertions, 0 failures`

- Task 151 edit-clj structural edit extension is complete and closed:
  - `psi.edit-clj.core` (pure: parse, find-candidates, apply-line-filter, replace-in) + `psi.edit-clj.extension` (tool registration, I/O, JSON)
  - 19 tests, 73 assertions; broader suite green; lint clean

- Task 145 logprobs-out-of-band-extension is complete and closed:
  - logprob data moved out-of-band into `extensions.logprobs`; synthetic projection removed
  - `logprobs/perplexity` deterministic operation surface added

- Task 140 logprobs-openai-completions-flag is complete and closed

- Task 139 active-session-id root attr is complete and closed

- Task 138 github extension label ops and workflow adoption is complete and closed

- Task 136 built-in-registration-path-for-workflow is complete and closed

- Task 134 psi-tool mutation surface and active-session/session-summary introspection is complete and closed

- Task 130 workflow step materialization component extraction is complete and closed

- Task 128 workflow execution adapter seam is complete and closed

- Task 125 workflow-runtime core component extraction is complete and closed

- Local OpenAI-compatible chat-completions requests now project `/thinking off` onto `chat_template_kwargs.enable_thinking=false` for models marked `:locality :local`

## Suggested next step
- Bootstrap analysis after `160`: the main remaining complexity is concentrated in `psi.app-runtime/adopt-startup-plan-into-session!`
  - Biggest simplification opportunities: unify the two-stage system-prompt build (tools/extensions/graph-capability inputs settle late), collapse duplicate startup active-tool refresh/composition paths
  - Consider a narrower pre-session manifest-extension discovery/plan phase
- `149-reload-fixup-inventory-and-safety` remains important but is reload correctness, not bootstrap simplification
- `124-turn-execution-contract-extraction` continues the component extraction map
- `147-workflow-child-session-creation-contract` and `141-workflow-child-session-non-streaming-execution` are the next workflow-architecture items

## Latest session notes
- Closed task 160 after review-implementation workflow found no remaining actionable follow-up
- All review-originated steps (6–15) checked off; remaining unchecked steps (1–5, 12, 14) are planned implementation work gated on the core rewrite (step 1), not independently actionable
