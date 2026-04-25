- [x] Inspect the shared command/prompt boundary and identify the smallest backend-owned place to express slash-command → template fallback
- [x] Define the canonical resolution order explicitly: built-in command → extension command → prompt template → unknown slash input
- [x] Inspect the existing slash autocomplete/completion surfaces and confirm where prompt-template candidates are already sourced vs missing
- [x] Implement the fallback without moving ownership into RPC transport code
- [x] Preserve the existing command-over-template precedence rule
- [x] Ensure loaded prompt templates appear as slash autocomplete/completion candidates on supported surfaces already consuming backend/session prompt-template state
- [x] Add focused tests for loaded prompt-template invocation via `/name ...`
- [x] Add focused tests proving runtime-registered/reloaded prompt templates participate in slash resolution from loaded session state
- [x] Add focused tests proving runtime-registered/reloaded prompt templates appear in slash completion when the relevant surface consumes session prompt-template state
- [x] Add focused tests proving loaded prompt templates appear in slash autocomplete/completion
- [x] Add focused tests proving real commands still win over same-name templates
- [x] Add focused tests proving collision completion does not produce duplicate indistinguishable candidates and remains deterministic
- [x] Add focused tests proving true unknown slash-prefixed input still reports unknown/not-a-command
- [x] Add focused test or verification proving supported non-RPC prompt-submission paths remain converged on the same backend resolution order
- [x] Verify relevant RPC command/prompt tests remain green
- [x] Verify relevant TUI/Emacs completion tests remain green
- [x] Record which supported surfaces already included prompt-template completion vs which required change
- [x] Record implementation notes and any boundary decisions in `implementation.md`

Follow-on review steps:
- [x] Make the RPC `command` op execute template fallback rather than silently no-op when `slash-resolution-in` returns `:template`
- [x] Preserve backend ownership of template fallback when fixing the RPC/Emacs slash path; avoid reintroducing transport-local template resolution logic
- [x] Add an end-to-end test proving the real Emacs/RPC slash path executes a loaded prompt template via `/name ...` rather than merely suppressing `[not a command]`
- [x] Add a focused RPC test proving `command`-op template fallback reaches canonical prompt submission / prepared-request execution semantics
- [x] Refresh Emacs slash completion data when prompt-template session state changes after startup so runtime-registered/reloaded templates become discoverable without reconnect
- [x] Add a focused Emacs test proving runtime-added/reloaded prompt templates appear in slash completion after state refresh
- [x] Reduce command-name drift risk by replacing or justifying the duplicated `builtin-command-names` authority set in `psi.agent-session.commands`
- [x] Add a focused test guarding command/template precedence against future builtin-command catalog drift
- [x] Re-run focused Clojure + Emacs tests after the follow-on fixes

Code-shaper follow-up steps:
- [x] Refactor `components/rpc/src/psi/rpc/session/commands.clj` to extract small helpers from `run-command!` and reduce repeated snapshot/response scaffolding across command/template/unknown branches
- [x] Preserve current backend-owned slash resolution semantics while simplifying `run-command!`; do not reintroduce transport-local template matching or a second template expansion path
- [x] Tighten Emacs slash-completion refresh invalidation so `psi-emacs--refresh-slash-completion-data` is not triggered on every matching `session/updated` when a narrower canonical command/template-state change trigger is available
- [x] Add focused proof for the chosen Emacs refresh trigger so runtime prompt-template/command changes still become discoverable without reconnect
- [x] Update task artifact coherence: mark verification/rerun steps to match the work already completed and keep `steps.md` synchronized with actual verification state
- [x] Clarify wording in task notes/tests/docs where the new Emacs regression test is described, distinguishing a real frontend slash-path regression proof from a full transport-backed end-to-end test
- [x] Consider whether a true transport-backed end-to-end test for prompt-template slash fallback is warranted; if yes, add one focused proof instead of further event-injection-only coverage
