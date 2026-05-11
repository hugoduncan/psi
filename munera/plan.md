# Munera plan

Open tasks in suggested execution order:

Backlog:

`munera/open/021-emacs-session-tree-buffer-with-magit-sections/`
`munera/open/108-project-nrepl-testing-without-mocks/`
`munera/open/105-agent-session-component-extraction-map/`
`munera/open/136-built-in-registration-path-for-workflow/`
`munera/open/134-psi-tool-mutation-surface-and-active-session-introspection/`
`munera/open/001-post-wave-b-gordian-follow-on/`
`munera/open/002-compatibility-scaffold-removal/`
`munera/open/077-custom-provider-string-provider-auth-normalization/`
`munera/open/003-prompt-lifecycle-architectural-convergence/`
`munera/open/006-agent-tool-skill-prelude-follow-on/`
`munera/open/005-canonical-dispatch-pipeline-trace-observability/`

Notes:
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
- `140` adds a runtime behavioural flag for logprob collection on the OpenAI chat-completions endpoint: session flag → options projection → request builder → `:logprobs-delta` stream event.
- Close or replace tasks as scope sharpens; do not merge task contents.
