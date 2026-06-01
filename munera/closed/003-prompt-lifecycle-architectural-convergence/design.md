Goal: complete the prompt lifecycle convergence so request preparation is the explicit home for prompt assembly and related shaping.

Context:
- The prepare → execute → record → finish scaffold is wired end-to-end.
- Shared prompt submission paths now route through the lifecycle.
- Remaining work centers on cache-breakpoint shaping, skill prelude handling, and removal of residual prompt-path seams.

Acceptance:
- request preparation clearly owns prompt layer assembly and provider request shaping
- skill prelude handling has the intended cache-breakpoint behavior
- remaining prompt-path seams/comments/test hooks are simplified or removed
