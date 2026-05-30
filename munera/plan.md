# Munera plan

Open tasks in suggested execution order:

Backlog:

`munera/open/192-add-opus-4-8-model/`
`munera/open/021-emacs-session-tree-buffer-with-magit-sections/`
`munera/open/108-project-nrepl-testing-without-mocks/`
`munera/open/154-fix-workflow-max-iterations-error-surfacing/`
`munera/open/157-jar-owned-deps-release-startup/`

`munera/open/001-post-wave-b-gordian-follow-on/`
`munera/open/002-compatibility-scaffold-removal/`
`munera/open/173-investigate-and-close-completed-open-tasks-from-077/`
`munera/open/003-prompt-lifecycle-architectural-convergence/`
`munera/open/006-agent-tool-skill-prelude-follow-on/`
`munera/open/005-canonical-dispatch-pipeline-trace-observability/`
`munera/open/175-workflow-self-loop-control-edges/`
`munera/open/186-built-in-skill-for-developing-extensions/`
`munera/open/189-workflow-run-retention-and-cleanup/`

Recently completed:

`munera/closed/190-ai-request-retry-backoff-observability/`
`munera/closed/184-workflow-file-kinds-and-md-step-prompts/`
`munera/closed/188-built-in-skill-for-writing-workflows/`

Notes:

- `169` is complete and closed: model/provider structured-output capability surface implemented; OpenAI Chat Completions native JSON Schema, Codex prompted-JSON fallback, Anthropic forced-tool native and fallback streaming/result surfaces, strategy metadata, model/user capability normalization, and focused structured-output/model tests green.
- `171` is complete and closed: Anthropic JSON Schema native output implemented as `:anthropic/json-schema-output`; live OAuth smoke verified the beta request shape `output_format {:type "json_schema" :schema ...}` plus `structured-outputs-2025-11-13`, with `output_format.name`/`strict` omitted; review-implementation completed.
- `173` is complete and closed: skill registration order is no longer semantic; `skill-registry` and affected prompt/discovery/TUI/command/workflow-selected skill-list surfaces now use canonical exact skill-name ordering, while duplicate-ignore, first-write-wins identity, and `:added?` / `:changed?` behavior are preserved. Full `bb test` passed before close.
- `136` is complete and closed: built-in workflow now installs via explicit built-in paths — `register-built-in-tool-in!` / `register-built-in-command-in!` in the shared registries, direct `:session/register-prompt-contribution` dispatch for prompt contributions, and `register-built-in-lifecycle-callback!` / `invoke-built-in-lifecycle!` for session_switch; `ext/register-extension-in!` and `ext/create-extension-api` removed from bootstrap; `built-in:workflow` retained only as stable provenance identifier; 6 commits, lint clean, all affected workflow tests pass.


- `151` is complete and closed: `edit-clj` structural edit extension; `psi.edit-clj.core` (pure: parse, find-candidates, apply-line-filter, replace-in) + `psi.edit-clj.extension` (tool registration, I/O, JSON); wired into top-level `deps.edn`, `tests.edn`, and `psi-owned-extension-catalog`; `rewrite-clj/rewrite-clj 1.1.47` added to runtime+test deps; 19 tests, 73 assertions, 0 failures; 1776+169 broader suite green.
- `140-workflow-ir-compilation-errors-actionable` is complete and closed: `"invalid value"` fallback test added to `format-structural-errors-test`; all 4 AC and 8 verification expectations met; 16 formatter tests, 63 assertions, 0 failures.
- `145` is complete and closed: logprob data moved out-of-band into `extensions.logprobs`.
- `140` is complete and closed: logprob collection flag for OpenAI chat-completions endpoint;
  schema + request building + SSE extraction (OpenAI + llama.cpp) + turn accumulation +
  journal append/projection + EQL resolver + /logprobs command; 1702 tests, 0 failures.
- `139` is complete and closed: `:psi.agent-session/active-session-id` root resolver in `resolvers/session.clj`; single-seed input `[:psi.agent-session/session-id]`; appears in `root-queryable-attrs`; nil passthrough for present-but-nil; 3 focused tests; live query verified.
- `138` is complete and closed: github extension has `find-pr`, `add-label`, `remove-label`; all nine listed workflows migrated to deterministic discover and label-ops `:invoke` steps.
- `137` is complete and closed: `psi/github` extension with deterministic `github/find-issue` operation; `gh-issue-refine` discover step replaced with `:invoke`; blocked smoke tests deferred (require real labeled GH issues).
- `munera/plan.md` is the active project-wide orchestration surface.
- These munera tasks split the active work into executable task directories.
- Completed tasks should live under `munera/closed/`; open-task ordering should reflect only directories still active under `munera/open/`.
- The previous TUI parity umbrella (`047`) and discoverable navigation slice (`049`) are complete and should live under `munera/closed/`.
- `003` is the broader prompt-lifecycle convergence umbrella; `006` is the concrete remaining skill-prelude/cache-breakpoint slice that currently drives its unfinished acceptance.
- `070` tracks the `/delegate` slash-command UX gap so delegated workflow completion comes back into the originating conversation transcript.
- Tasks `089`, `091`, `092`, `093`, and `094` are now complete and live under `munera/closed/`.
- `134` is complete and closed: psi-tool mutation surface + context-session-summaries resolver.
- `142` is complete and closed: workflow session logprobs control.
- `140` adds a runtime behavioural flag for logprob collection on the OpenAI chat-completions endpoint: session flag → options projection → request builder → `:logprobs-delta` stream event.
- `154` is complete and closed: canonical provider telemetry events (`provider_request_started`, `provider_retry_scheduled`, `provider_request_finished`), shared `provider-error-kind` classification, metrics provider/per-model aggregation, persistence/schema coverage, and `/metrics` provider summaries are implemented; focused verification green including retry-attempt fresh prepared-request/turn-id proof.
- `168` is complete and closed: tool-registry migrated onto root-registry storage; higher extension-detail projections now read canonical tool-registry data rather than legacy extension-local tool state.
- `169` is complete and closed: workflow-registry migrated onto root-registry storage while preserving canonical compatibility path `[:workflows :definitions]`; higher semantic seams route through workflow-registry.
- `176` is complete and closed: prompt-registry now uses canonical string-coerced single-id identity, same-owner duplicate registration replaces, cross-owner same-id registration throws explicit ownership conflict, update/unregister target by `id` with `ext-path` only as ownership assertion when supplied, nullable extension test helpers store canonical single-id keys, and prompt-contribution projections use shared canonical ordering; focused tests + targeted lint passed and later review loops found no actionable feedback.
- `177` is complete and closed: prompt-registry prompt contributions now live in shared `root-registry` storage via the prompt-registry root-storage adapter; sessions own canonical `:prompt-contribution-ids` membership while session-local `:prompt-contributions` vectors are derived compatibility projection only; prompt read/introspection/workflow/bootstrap/nullable-helper seams were migrated to root-backed authority, lifecycle inheritance proofs were reshaped around membership + root-backed reads, targeted lint passed, and full `bb test` was green before close.
- Close or replace tasks as scope sharpens; do not merge task contents.
