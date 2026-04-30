Approach:
- Treat this as a command-path convergence fix, not a new workflow result delivery mechanism.
- The inspected code shows the smallest fix: change the `/delegate` command handler in `extensions/workflow_loader.clj` so its `delegate-run` call passes `:include_result_in_context true` alongside `:mode "async"`.
- Reuse the existing `delegate-run` → `execute-async!` → `on-async-completion!` → `inject-result-into-context!` path.
- Keep the `/delegate` UX conversational: immediate acknowledgement now, final delegated result later in the same transcript.
- Keep task scope on successful completions with result text; do not broaden into redesign of failure/cancellation transcript delivery unless a tiny command-path-adjacent correction is required.

Likely steps:
1. update the `/delegate` command handler to opt into `include_result_in_context`
2. add focused proof that invoking the registered `delegate` command causes async completion to append the existing bridge-shaped chat messages into the originating session
3. prove that the command-path fix still leaves the immediate acknowledgement string and background-job startup/terminal tracking intact
4. prove that enabling chat injection suppresses the fallback `psi.extension/append-entry` custom `delegate-result` completion path for `/delegate`
5. prove the successful completion uses exactly one transcript-delivery path for the final result

Suggested proof shape:
- keep tool-path coverage in `workflow_loader_delegate_test.clj` as-is
- add a command-path regression test in `workflow_loader_test.clj` or a nearby command-focused test namespace
- assert the immediate command return string
- assert the later session-targeted append-message calls in the originating session
- assert the result text is delivered through the assistant message side of the existing bridge shape
- assert no fallback `delegate-result` append-entry is emitted for that successful command-path run

Risks:
- fixing the command path by bypassing the existing canonical delegate execution flow
- introducing duplicate terminal delivery when both chat injection and fallback append-entry are allowed to fire
- proving only the tool path again instead of the actual `/delegate` command behavior
- accidentally widening the task into broader workflow terminal-state UX decisions unrelated to the command-path flag omission
