# Design follow-up steps

- [x] Clarify the authoritative full-call data source and precedence when both parsed/structured arguments and raw argument strings are available, including how invalid or partially parsed JSON should render without silently dropping raw content.
- [x] Define the expanded-detail layout contract for both Emacs and TUI: section labels/order, whether the header summary remains separate from `Call` details, how empty/nil arguments render, and how multiline/nested arguments preserve full content without truncation.
- [x] Decide how tool-specific/extension call renderers interact with the new expanded full-call details, especially whether a renderer may replace the full call, must be accompanied by raw/structured fallback details, or applies only to the collapsed header.
- [x] Create `plan.md` and `steps.md` before implementation so the task has a concrete approach and checklist aligned with the finalized design acceptance criteria, including the required Emacs and TUI verification coverage.
