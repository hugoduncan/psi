Implementation notes:
- Shared backend ownership landed in `psi.agent-session.commands/slash-resolution-in`.
- Resolution order is now explicit and backend-owned:
  1. built-in command
  2. extension command
  3. loaded prompt template
  4. unknown slash input
- `dispatch-in` now delegates through `slash-resolution-in` and still only returns concrete command results; template fallback continues to return nil so execution flows through the existing request-preparation/template-expansion path.
- Prompt-template fallback is driven by the loaded session `:prompt-templates` state plus authoritative command names from `loaded-command-names-in`; command precedence is preserved without duplicating transport-local checks.
- RPC `command` op now consumes `slash-resolution-in` directly so loaded prompt templates no longer produce `[not a command] /name ...`; true unknown slash input still does.
- This keeps RPC transport/result mapping separate from backend slash resolution semantics.
- Non-RPC prompt submission remains converged because CLI/TUI/app-runtime already treat nil command dispatch as prompt submission; template fallback continues to reach canonical request preparation through that existing path.
- Completion surface findings:
  - TUI already sourced backend/session prompt-template state into slash autocomplete; no TUI code change was needed.
  - Emacs CAPF only merged built-ins + extension commands, so loaded prompt templates were missing there.
  - Emacs now refreshes slash completion data from a single query for both `:psi.extension/command-names` and `:psi.agent-session/prompt-templates`, stores prompt templates in frontend state, and merges them into CAPF candidates.
- Collision completion remains deterministic because Emacs completion merges via `seq-uniq` keyed by command string, so a command/template name collision yields one visible candidate.
- Focused tests added for:
  - shared backend slash resolution unknown/template/precedence behavior
  - RPC command-op behavior for template fallback vs true unknown slash input
  - Emacs CAPF prompt-template visibility and collision dedupe
