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
- Fixed workflow max-iterations exhaustion bug (branch `fix-workflow-max-iterations`):
  - Three interconnected bugs: (1) `judged-routing-transition` compared vector `[:failed]` against keyword `:failed`, always falling through to the non-failed `judge/record` path; (2) `:terminal/record` action was a no-op with no terminal-outcome recording; (3) execute-run mutation only extracted step-level errors, missing iteration exhaustion
  - Added `:iteration/exhausted` action on the exhaustion transition with correct vector-or-keyword target comparison
  - New handler in `statechart_runtime.clj` records `terminal-outcome` with `:reason :iteration-limit-reached`, step-id, counts, last-judge-signal, and last-result-text
  - Extracted `terminal-outcome-error-message` and `run-failure-error` helpers in `canonical_workflows.clj`; both execute-run and resume-run mutations now surface iteration exhaustion details
  - 56 focused tests, 310 assertions, 0 failures; lint clean
- Task 125 workflow-runtime core component extraction is now landed locally with follow-up decomposition, Kaocha wiring, and test-shaping polish complete:
  - added lower component `components/workflow-runtime/` with authoritative namespaces under `psi.workflow-runtime.*`
  - moved canonical workflow runtime owners out of `components/agent-session/src/psi/agent_session/`: model, IR, target IR compiler, statechart, source resolution, runtime core, progression recording, attempts, terminal contract, statechart runtime, and bounded turn execution contract
  - split the former mixed lower `psi.workflow-runtime.step-prep` owner into:
    - `psi.workflow-runtime.step-materialization`
    - `psi.workflow-runtime.step-session-config`
  - removed `psi.workflow-runtime.step-prep` instead of keeping a façade after rewiring production/test consumers directly to the split owners
  - rewired higher `agent-session` owners (`context`, `workflow-execution`, workflow mutations/resolvers, `psi_tool_workflow`, and `workflow-judge`) to depend downward on `psi.workflow-runtime.*`
  - rewired workflow callback/backfill surfaces directly to the split owners:
    - `:resolve-workflow-step-session-config-fn` → `psi.workflow-runtime.step-session-config/resolve-step-session-config`
    - `:materialize-workflow-step-session-conversation-fn` → `psi.workflow-runtime.step-materialization/materialize-step-session-conversation`
    - `:split-workflow-step-session-conversation-fn` → `psi.workflow-runtime.step-materialization/split-step-session-conversation`
  - moved the bounded prompt seam to `psi.workflow-runtime.turn-execution-contract` and deleted the old `agent-session` owner instead of leaving a compatibility shim
  - changed the lower turn execution contract to call a ctx-supplied `:workflow-prompt-execution-result-fn`, removing the back-edge from `workflow-runtime` to `psi.agent-session.turn`
  - decomposed the former large `psi.workflow-runtime.statechart-runtime` into smaller role-focused lower namespaces:
    - `psi.workflow-runtime.statechart-runtime.state`
    - `psi.workflow-runtime.statechart-runtime.queue`
    - `psi.workflow-runtime.statechart-runtime.step-execution`
    - `psi.workflow-runtime.statechart-runtime.delegate`
    - `psi.workflow-runtime.statechart-runtime.lifecycle`
    - with `psi.workflow-runtime.statechart-runtime` retained as the public orchestration façade
  - rewired lower proof ownership to match the split:
    - `psi.workflow-runtime.step-materialization-test`
    - `psi.workflow-runtime.step-session-config-test`
    - shared `psi.workflow-runtime.step-test-support`
  - wired `components/workflow-runtime/{src,test}` into the top-level Kaocha `tests.edn` unit and integration suites
  - verification green for the role split: focused workflow/runtime proofs `13 tests, 39 assertions, 0 failures`; lint green `0 errors, 0 warnings`
- Task 128 workflow execution adapter seam is now implemented locally:
  - added lower owner `components/workflow-runtime/src/psi/workflow_runtime/execution_adapter.clj`
  - chose named seam `psi.workflow-runtime.execution-adapter` with adapter value key `:workflow-execution-adapter`
  - moved lower workflow-runtime higher/session-bound crossings behind the named seam in:
    - `psi.workflow-runtime.attempts`
    - `psi.workflow-runtime.turn-execution-contract`
    - `psi.workflow-runtime.step-session-config`
    - `psi.workflow-runtime.statechart-runtime`
  - canonical adapter assembly now lives in `psi.agent-session.context/workflow-execution-adapter`
  - `psi_tool_workflow` now backfills the named seam for older live ctx maps after compatibility callback backfill
  - focused tests now stub the named seam where they are proving workflow-runtime consumption
  - intentionally left lower-owned workflow step session-config/materialization collaborators outside the seam because they are not runtime → session crossings
  - focused verification green: `12 tests, 66 assertions, 0 failures, 0 errors`; lint clean
- Task 130 workflow step materialization component extraction is now landed locally:
  - added lower component `components/workflow-step-materialization/`
  - moved authoritative owners out of `components/workflow-runtime/` into:
    - `psi.workflow-step-materialization.core`
    - `psi.workflow-step-materialization.source-resolution`
  - preserved the `127` role split: step materialization remains separate from `psi.workflow-step-session-config.core`
  - rewired lower runtime consumers downward to the new owner:
    - `psi.workflow-runtime.statechart-runtime.step-execution`
    - `psi.workflow-runtime.statechart-runtime.delegate`
  - rewired higher session assembly/backfill surfaces downward to the new owner:
    - `psi.agent-session.context`
    - `psi.agent-session.psi-tool-workflow`
    - `psi.agent-session.test-support`
  - moved lower proof ownership to the new component:
    - `psi.workflow-step-materialization.core-test`
    - `psi.workflow-step-materialization.source-resolution-test`
  - removed the old runtime owners entirely instead of leaving forwarding seams:
    - `psi.workflow-runtime.step-materialization`
    - `psi.workflow-runtime.source-resolution`
  - preserved existing public behavior/call/output contracts while changing only ownership and namespace placement
  - retained direct dependency on `psi.workflow-judge/project-messages` inside the new lower source-resolution owner as legitimate shared lower workflow projection semantics
  - focused verification green: `16 tests, 35 assertions, 0 failures`; broader workflow/session verification green: `24 tests, 79 assertions, 0 failures`; lint green `0 errors, 0 warnings`

- Task 138 github extension label ops and workflow adoption is now complete and closed:
  - extracted `psi.github.slug` shared ns; `find-issue` rewired
  - added `psi.github.find-pr` (deterministic PR selection, parallel to `find-issue`; URL regex `#"/pull/(\d+)"`)
  - added `psi.github.label-ops` with `add-label` and `remove-label` handlers (`:target` dispatch; shared `label-csv`)
  - `psi.github.extension/init` now registers all four ops; `extension-test` asserts all four ids
  - 36 unit tests, 117 assertions, lint clean
  - migrated 10 workflows: `gh-bug-discover-and-read`, `gh-bug-triage`, `gh-issue-ingest`, `gh-issue-implement`, `gh-pr-fix-checks`, `gh-bug-post-repro`, `gh-bug-triage-modular`, `gh-bug-request-more-info`, `gh-issue-refine`, `gh-bug-fix-and-pr`
  - `gh-pr-fix-checks` uses `:labels []` (no label filter) for find-pr
  - `gh-bug-triage-modular` post-repro prompt-string is now `:type :map` (structured, not rendered text) per §P

- Task 139 active-session-id root attr is now complete and closed:
  - added `active-session-id-resolver` in `resolvers/session.clj`
  - `::pco/input [:psi.agent-session/session-id]` (single root seed — passes fixed-point reachability)
  - returns session-id from psi-tool query context; nil when present-but-nil; Pathom3 `:not-found` when absent
  - appended to `resolvers` def; appears in `:psi.graph/root-queryable-attrs`
  - 3 focused unit tests in `resolvers_test.clj`; `root-queryable-attrs-contract-test` auto-covers the new attr
  - 1678 tests, 12513 assertions, 0 failures; lint clean; live query verified

- Task 140 logprobs-openai-completions-flag is now complete and closed:
  - added `:logprobs-enabled` / `:top-logprobs` / `:last-turn-logprobs` to `agent-session-schema`; `:logprobs` to `session-entry-kind-schema`
  - `build-request` injects `"logprobs": true` / `"top_logprobs": N` when enabled; `session->request-options` propagates the flags
  - `extract-openai-logprob-delta` (per-chunk) and `extract-llama-logprob-delta` (final chunk) emit `:logprob-delta` events; routed in `make-provider-event-consumer`
  - `handle-logprob-delta!` accumulates token vectors into `:logprob-buffer`; `handle-done!` flattens to `:logprobs` on turn-data; `execute-live-turn!` returns `:logprobs`; `execute-prepared-request!` includes `:execution-result/logprobs`
  - `build-record-response` writes `:last-turn-logprobs` to session-data and appends `:logprobs` journal entry when non-empty
  - `journal->provider-messages` skips `:logprobs` entries during provider message projection (logprob data is persisted in the journal but no longer projected as synthetic user messages; analysis is handled out-of-band by the `logprobs/perplexity` extension operation)
  - `:psi.agent-session/last-turn-logprobs` EQL resolver added
  - `/logprobs [on|off|N]` command: `set-logprobs-in!` in session_settings + core; `:session/set-logprobs` handler with `(or top-n 3)` nil-guard; `prefixed-command-prefixes` + `builtin-slash-commands` + `format-help` updated
  - 3 new test namespaces; 1702 tests, 0 failures
- Task 145 logprobs-out-of-band-extension is now complete locally:
  - removed synthetic logprob projection from both `prompt_request.clj` and workflow session-step transcript shaping; session-step `:transcript` is assistant-message only
  - enriched `session_turn_finished` payload with `:logprobs` and structured `:assistant-message`
  - added `extensions.logprobs/logprobs-perplexity` deterministic operation surface via `logprobs/perplexity`
  - updated `local-logprobs` workflow to `run → perplexity → report`
  - simplified extension storage from per-session cache to a single latest snapshot carrying `:session-id`, `:turn-id`, `:logprobs`, and `:assistant-message`
  - removed `logprobs-table` command and stale `/logprobs on` guidance from the extension
  - focused extension verification green after simplification: `7 tests, 43 assertions, 0 failures`; lint clean
- Local OpenAI-compatible chat-completions requests now project `/thinking off` onto `chat_template_kwargs.enable_thinking=false` for models marked `:locality :local`, while cloud models keep the existing reasoning-effort-only behavior.
  - implementation lives in `psi.ai.providers.openai.reasoning/chat-template-kwargs` and `psi.ai.providers.openai.chat-completions/build-request`
  - focused proof added in `psi.ai.providers.openai-test`
  - docs updated in `doc/custom-providers.md`; changelog updated
  - verification green: `clojure -M:test --focus psi.ai.providers.openai-test` → `24 tests, 119 assertions, 0 failures`
- Task 134 psi-tool mutation surface and active-session/session-summary introspection is now implemented locally:
  - added `psi-tool(action: "mutate")` in `components/agent-session/src/psi/agent_session/psi_tool.clj`
  - request validation now covers required `mutation`, unsupported `entity`, params-map shape, qualified-symbol parsing, and registered-mutation lookup
  - success reports preserve canonical mutation payloads under `:psi-tool/result`; failures return structured `:psi-tool/error`
  - wired mutate through the canonical runtime helper `psi.agent-session.extensions.runtime-eql/run-extension-mutation-in!`
  - fixed explicit targeting in that helper so an already-supplied business `:session-id` is preserved for session-scoped mutations instead of being overwritten by the invoking session id
  - added compact root attr `:psi.agent-session/context-session-summaries` in `resolvers/session.clj`
  - shared live context projection now exposes `:psi.session-info/updated` from session `:updated-at`
  - compact summaries expose exact v1 fields only: `id`, `display-name`, `created`, `updated`, `parent-session-id`, `worktree-path`
  - focused verification green with increased heap:
    - `psi.agent-session.graph-surface-test` → `22 tests, 2247 assertions, 0 failures`
    - `psi.agent-session.tools-test/psi-tool-integration-test` → `1 test, 53 assertions, 0 failures`
    - `psi.agent-session.resolvers-test` + `psi.agent-session.session-close-mutation-test` → `23 tests, 141 assertions, 0 failures`
  - while isolating verification, found and fixed a separate graph test pathology: `graph_surface_test.clj/root-queryable-attrs-contract-test` no longer issues one mega-query over every root attr, because that expansion path could trigger suite OOMs; it now queries each advertised root attr independently
- Reload discoverability/self-guidance improved locally:
  - live-verified the small-namespace self-reload loop from the current session worktree using `psi-tool` query for `[:psi.agent-session/worktree-path]` followed by `reload-code` for `psi.prompt-assets.system-prompt`
  - confirmed namespace reload also succeeds without explicit `worktree-path` when invoked from a session carrying the canonical worktree
  - updated `AGENTS.md` with a concise psi self-reload loop near runtime tooling guidance
  - updated `README.md` to point readers at the documented self-reload loop in `doc/psi-project-config.md`

- Task 151 edit-clj structural edit extension is now complete and closed:
  - added `extensions/edit-clj/` with `psi.edit-clj.core` (pure: parse-single-form, find-candidates, apply-line-filter, replace-in) and `psi.edit-clj.extension` (tool registration + file I/O + JSON serialisation)
  - tool named `"edit-clj"`; matches by sexpr equality via `rewrite-clj`; supports optional `start-line`/`end-line` filtering by node start-row
  - validation order: old-string → new-string → file (first error returned)
  - result shapes: ok, parse-error, file-not-found, no-match, ambiguous-match
  - wired into top-level `deps.edn` (4 source-path locations + test paths + `rewrite-clj/rewrite-clj 1.1.47` in runtime+test extra-deps), `tests.edn` (unit, extensions, integration suites), and `psi-owned-extension-catalog` (`:development`+`:installed` only, following `github` pattern)
  - 19 extension tests, 73 assertions; 1776+169 broader suite green; lint clean

## Suggested next step
- For bootstrap simplification follow-on, strongest next candidate is `136-built-in-registration-path-for-workflow` because canonical startup still installs built-in workflow through pseudo-extension mechanics (`ext/register-extension-in!` + `ext/create-extension-api`) and still refreshes active tools from that path.
- After `136`, the next likely bootstrap simplifications are: remove mutation-shaped startup resource loading in `psi.agent-session.bootstrap/load-startup-resources-via-mutations-in!`, unify startup active-tool assembly to one authoritative post-registration write, and consider a narrower pre-session manifest-extension discovery/plan phase.
- `149-reload-fixup-inventory-and-safety` remains important but is reload correctness, not bootstrap simplification.

## Latest session notes
- Closed and committed the remaining app-runtime nREPL bootstrap test split as part of task `159`:
  - commit `c255eace` — `⚒ 159: split app-runtime nREPL bootstrap tests`
  - focused verification green: `clojure -M:test --focus psi.app-runtime-test --focus psi.app-runtime-nrepl-test` → `25 tests, 102 assertions, 0 failures`
- Bootstrap analysis after `159`:
  - the main remaining complexity is concentrated in `psi.app-runtime/adopt-startup-plan-into-session!`
  - biggest simplification opportunities are built-in workflow de-pseudo-extensioning, removing mutation-based startup resource loading, and collapsing duplicate startup active-tool refresh/composition paths
  - current startup still does a two-stage system-prompt build because tools/extensions/graph-capability inputs settle late; revisit that only after workflow/tool ownership is cleaner
