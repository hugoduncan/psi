Initialized from user request on 2026-04-20.

Inspection notes

- `extensions/workflow_loader.clj` already contains the canonical pieces needed for the fix:
  - `delegate-run` accepts `:include_result_in_context`
  - `execute-async!` threads that flag into async completion
  - `on-async-completion!` injects transcript messages when the flag is true
- The `/delegate` command currently does not opt into that path. Its handler calls:
  - `(delegate-run {:workflow workflow :prompt prompt :mode "async"})`
- `extensions/workflow_loader/delivery.clj` shows the intended transcript behavior clearly:
  - query last role in the originating session
  - maintain user/assistant alternation
  - append `"Workflow run <id> result:"` as a user message
  - append the workflow result text as an assistant message
- `extensions/workflow_loader/orchestration.clj` also confirms an important invariant for this task:
  - when `include_result?` is true, async completion injects messages into chat
  - when `include_result?` is false, it instead appends a custom `delegate-result` entry
  - so the command-path fix should reuse the existing flag to avoid double delivery

Coordination note

- This task is narrowly about the `/delegate` slash-command UX.
- The lower-level `delegate` tool already supports result injection through `include_result_in_context`; this task should converge the command path onto that existing capability rather than inventing a parallel mechanism.
- Focus proof on the command handler path so future regressions cannot hide behind tool-only coverage.
- Best likely regression-test home is alongside existing `/delegate` command tests in `extensions/workflow_loader_test.clj`.
