# Design follow-up steps

- [x] Clarify the authoritative full-call data source and precedence when both parsed/structured arguments and raw argument strings are available, including how invalid or partially parsed JSON should render without silently dropping raw content.
- [x] Define the Emacs expanded-detail layout contract: section labels/order, whether the header summary remains separate from `Call` details, how empty/nil arguments render, and how multiline/nested arguments preserve full content without truncation.
- [x] Decide how Emacs tool-specific/extension call renderers interact with the new expanded full-call details, especially whether a renderer may replace the full call, must be accompanied by raw/structured fallback details, or applies only to the collapsed header.
- [x] Create `plan.md` and `steps.md` before implementation so the task has a concrete approach and checklist aligned with the finalized Emacs acceptance criteria.
- [x] Define a concrete Emacs-detectable completeness rule for parsed arguments versus raw arguments in expanded `Call` rendering, including whether parsed arguments from live `tool/executing`/rehydrated summaries are trusted as complete when raw `arguments` is also present, and when both parsed and raw forms must be shown.
- [x] Clarify Emacs tool-detail toggle granularity: `C-c C-t` preserves the existing global tools-expanded mode for all tool rows and does not introduce row-local expansion.
- [x] Align `spec/tool-output-rendering.allium` with the finalized expanded full-call contract used by Emacs: expanded rendering includes generic `Call` details plus response/output details, not response `output.text` only.
