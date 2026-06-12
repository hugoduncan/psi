💡 In `prompt_request/journal->provider-messages`, de-dup of duplicate `toolResult` messages (by `tool-call-id`, first-occurrence-wins) MUST wrap the **output** of `repair-dangling-tool-uses`, not the pre-repair list. Order is load-bearing.

Reason: `repair-dangling-tool-uses` only scans the *contiguous* toolResult run after each assistant block (`split-with tool-result-message?`). A real toolResult that is **non-contiguous** with its tool-use block is treated as *missing*, so repair appends a **synthetic** `interrupted-tool-result` for the same id. De-dup-before-repair would therefore still leave two results for one id on a malformed/already-wedged journal; de-dup-after-repair guarantees ≤1 unconditionally (drops extras including repair's synthetics; repair still fills genuinely dangling blocks).

A recovery/regression test must include a **non-contiguous** duplicate — a contiguous-only test passes under either order and won't lock the placement.

This is the single upstream chokepoint: the conversation rebuild (`add-tool-result`) emits one `tool_result` block per toolResult message and is NOT a second de-dup site.
