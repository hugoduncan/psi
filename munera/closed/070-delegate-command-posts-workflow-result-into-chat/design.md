Goal: make the `/delegate` command launch delegated workflows so their final result is posted back into the originating conversation as normal chat content.

Context:
- `workflow-loader` already supports `include_result_in_context` on the `delegate` tool path.
- In `extensions/workflow_loader.clj`, `delegate-run` accepts `:include_result_in_context`, threads it into `execute-async!`, and `on-async-completion!` then calls `inject-result-into-context!` when that flag is true.
- `inject-result-into-context!` lives in `extensions/workflow_loader/delivery.clj` and explicitly appends chat messages into the originating session, preserving user/assistant alternation.
- The `/delegate` command handler currently calls `(delegate-run {:workflow workflow :prompt prompt :mode "async"})` and does not pass `:include_result_in_context true`.
- When `include_result_in_context` is false, async completion falls back to `psi.extension/append-entry` with custom type `delegate-result`, which is not the same as posting the final result back into chat.
- Focused tests already cover the tool path (`delegate-run-include-result-test`), but there is not yet command-path proof that `/delegate` opts into the same behavior.

Problem:
- From the operator point of view, `/delegate` behaves like a chat command, so the eventual workflow outcome should come back into the same conversation transcript.
- Today the command path misses that behavior because it launches `delegate-run` in async mode without opting into `include_result_in_context`.
- That means `/delegate` currently gets only the immediate acknowledgement string plus the non-chat `delegate-result` completion entry path, instead of transcript message injection.
- This creates a concrete mismatch between the slash-command UX and the intended conversational model for delegated work.

Required behavior:
- `/delegate <workflow> <prompt>` launches the workflow so a successful final result is appended back into the originating session transcript.
- For this task, posting back into chat means reusing the existing `delivery.clj` bridge shape: a synthetic user message of the form `Workflow run <id> result:` followed by the workflow result as an assistant message.
- The final result should appear in the conversation transcript rather than only as a background-job artifact or custom completion entry.
- The immediate acknowledgement for `/delegate` may remain, but it must not be the only conversational artifact.
- Result injection should happen once per delegated completion and should target the originating session explicitly.
- Scope is limited to successful delegated completions that produce result text; failed/cancelled/timeout chat injection is not part of this task unless inspection during implementation shows the command path already needs a small associated correction.

Acceptance:
- `/delegate` launches async delegated runs by passing `:include_result_in_context true` through the existing `delegate-run` path.
- Successful delegated completion with result text appends the existing bridge-shaped chat messages into the originating conversation transcript via the canonical session-targeted `append-message` flow in `delivery.clj`.
- The command path continues to use `delegate-run` / `execute-async!` rather than inventing a separate slash-command-only completion path.
- The immediate `/delegate` acknowledgement string still returns.
- Background-job behavior remains intact in the concrete sense that the delegated run still starts a background job and still marks it terminal on completion.
- `/delegate` uses exactly one terminal delivery path for successful result posting: enabling chat injection suppresses the fallback `delegate-result` `append-entry` path for that run.
- Focused proof covers the `/delegate` command handler specifically; test-file placement can follow existing command-oriented test organization.

Constraints:
- preserve canonical backend ownership of session/transcript mutation
- avoid duplicating result-posting logic if the tool path already provides the correct primitive
- avoid double delivery by reusing the existing `include_result_in_context` switch between transcript injection and fallback `delegate-result` append-entry emission
