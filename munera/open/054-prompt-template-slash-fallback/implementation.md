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

Review follow-on notes:
- Review found that the RPC `command` op currently detects `:template` fallback but does not execute it; this means the primary Emacs slash path suppresses `[not a command]` without yet running the template-backed prompt.
- Review also found that Emacs prompt-template completion is refreshed at startup hydration time but not yet clearly refreshed from later prompt-template session-state changes such as registration/reload.
- A further shaping pass should therefore:
  - route RPC `command` template fallback into canonical prompt execution semantics
  - add an end-to-end test for the real Emacs/RPC slash path
  - refresh Emacs slash completion on later prompt-template state changes
  - reduce drift risk around the duplicated builtin-command authority set

Follow-on implementation landed:
- RPC `command` template fallback now executes through canonical prompt submission semantics instead of silently no-oping.
- The RPC path reuses backend-owned slash resolution, then for `:template` fallback:
  - journals the raw slash input once
  - sets the resolved session model canonically
  - routes the original slash text through `session/prompt-in!`
  - relies on canonical prepared-request expansion to turn `/name ...` into template-expanded prompt text
  - emits the resulting assistant message and refreshed session/footer snapshots
- Focused RPC coverage now proves command-op template fallback reaches canonical prepared-request expansion by asserting the prepared request sees expanded template content (`Template body for 27`) rather than raw slash text.
- Emacs-side regression coverage now proves the real slash send path still dispatches `/gh-issue-work-on 27` via RPC `command`, preserves local user echo, and renders backend `assistant/message` output without any `[not a command]` detour.
- Emacs slash completion refresh is now triggered from canonical `session/updated` handling in addition to startup hydration, so runtime prompt-template state changes can refresh cached completion data without reconnect.
- Command-name drift risk was reduced by deriving `builtin-command-names` from the actual built-in exact/prefixed command catalogs instead of maintaining a duplicate manual set.
- Focused backend precedence coverage now also guards that `loaded-command-names-in` still contains built-in names needed for command-over-template precedence.
