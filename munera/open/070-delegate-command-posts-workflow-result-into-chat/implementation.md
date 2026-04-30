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

Implementation notes

- Updated the `/delegate` command handler in `extensions/workflow-loader/src/extensions/workflow_loader.clj` so its `delegate-run` call now passes `:include_result_in_context true` together with `:mode "async"`.
- This keeps the command path on the existing canonical async path:
  - `delegate-run`
  - `execute-async!`
  - `on-async-completion!`
  - `inject-result-into-context!`
- Added focused command-path regression coverage in `extensions/workflow-loader/test/extensions/workflow_loader_test.clj`.
- The new test proves:
  - `/delegate` still returns its immediate acknowledgement string
  - successful async completion queries the originating session explicitly
  - completion appends the existing bridge-shaped user + assistant messages into that originating session
  - the assistant-side message carries the workflow result text
  - background-job start and terminal marking still occur
  - fallback `psi.extension/append-entry` delivery is not used for this successful command-path case

Verification

- `clojure -M:test --focus extensions.workflow-loader-test --focus extensions.workflow-loader-delegate-test`
- Result: `28 tests, 93 assertions, 0 failures.`

Review note

- Code-shaper review: accept as-is; minor follow-up opportunities only around making the command-path test less brittle and less sleep-based.
- Follow-up shaping now applied:
  - command-path test now captures the created run-id directly from mocked `psi.workflow/create-run`
  - command-path async proof now waits on observed completion conditions via a small local helper instead of fixed sleeping
  - `/delegate` callsite now carries a short intent comment about conversational result return

Follow-up bugfix after live validation

- Live validation showed a deeper propagation bug: `/delegate` had been fixed to inject workflow results into chat, but some successful delegated runs still produced an empty injected assistant message.
- Root cause was not just delivery. Workflow step execution and workflow judge execution were submitting prompts via `prompt-in!` and then rereading the child-session journal with `last-assistant-message-in` to recover the result text.
- That journal reread was the wrong boundary for bounded workflow callers. The canonical prompt path already has the exact turn result available as `:execution-result/assistant-message`; workflow/judge code should consume that directly instead of depending on a later journal read.

Follow-up implementation notes

- Added `prompt-execution-result-in!` in `components/agent-session/src/psi/agent_session/prompt_control.clj`.
- Extended `:session/prompt-prepare-request` handling so callers can opt into receiving the executed turn result (`:return-execution-result? true`) while staying on the same canonical dispatch/runtime path.
- A second live validation exposed one more seam in that opt-in path: the first effect emitted by prompt preparation was still `:memory/recover-query`, so dispatch effect-return semantics were handing workflow callers the memory-recovery result instead of the prompt execution result. Fixed by introducing a combined `:runtime/recover-query-prompt-execute-and-record` effect for the execution-result-returning path so the returned effect result is the actual completed turn.
- Updated `workflow_statechart_runtime.clj` so workflow step execution now uses `prompt-execution-result-in!` and records the assistant message from `:execution-result/assistant-message` directly.
- Updated `workflow_judge.clj` so judge prompts and judge retries also use `prompt-execution-result-in!` instead of rereading the journal.
- Kept the earlier defensive hardening in place:
  - canonical workflow result projection trims blank `:outputs :text` to nil
  - workflow-loader async completion trims blank `:psi.workflow/result` to nil before deciding whether to inject into chat

Follow-up verification

- `clojure -M:test --focus psi.agent-session.workflow-judge-test --focus psi.agent-session.workflow-statechart-runtime-test --focus psi.agent-session.workflow-execution-test --focus psi.agent-session.prompt-lifecycle-test --focus psi.agent-session.mutations.canonical-workflows-test --focus extensions.workflow-loader-delegate-test --focus extensions.workflow-loader-test`
- Result: `91 tests, 370 assertions, 0 failures.`
