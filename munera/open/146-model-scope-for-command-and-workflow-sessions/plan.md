# Plan

## Approach

Implement this in four small vertical slices:

1. **Expose optional scope through core helpers and RPC**
   - thread optional scope through canonical model-setting helper(s)
   - align direct interactive setters that already bypass slash-command parsing (Emacs `set_model` caller and TUI submit handling) with the same helper/API contract
   - update RPC `set_model` to accept and validate optional scope
   - preserve existing omitted-scope behavior

2. **Extend `/model` command grammar and help text**
   - accept zero args, two args, or three args
   - validate `session|project|user`
   - update usage/help text and any command-completion/help surfaces that describe `/model`

3. **Make workflow-owned model changes explicitly transient**
   - update the workflow execution adapter/runtime path so workflow child-session model switching always uses `:scope :session`
   - treat workflow-owned judge sessions as governed by the same transient/no-persistence rule if they set models through the shared helper/API path, without broadening this task into new judge-only model selection behaviour
   - verify both initial workflow child-session model setup and fallback model switching remain session-local and non-persistent

4. **Add focused proofs for scope semantics**
   - command/helper/RPC path tests for session/project/user scope
   - workflow regression test proving workflow model changes do not emit persistence writes

## Decisions

- **Keep omitted scope semantics unchanged**: `/model <provider> <model-id>` and RPC callers that omit scope preserve the current default behavior. This minimizes user-facing surprise and keeps the task focused on adding explicit control rather than changing defaults.

- **Use existing lower mutation scope semantics**: do not create new persistence logic in command/RPC/workflow layers. They should only validate and forward `:scope` to `:session/set-model`.

- **Workflow path always opts into `:session`**: workflow-owned model changes are not user preference changes; they are execution-local runtime state.

- **Minimal UI expansion**: command text and RPC transport gain scope support; richer picker UI for scope selection is optional and not required for this task.

## Risks

- **Partial surface drift**: if command, RPC, and helper layers do not all support scope consistently, behavior becomes confusing. Treat parity as part of acceptance.

- **Workflow persistence leakage**: a hidden workflow path may still call plain `set-model` without scope. Search all workflow-owned model-set call sites and make the `:session` intent explicit.

- **Help/documentation mismatch**: command text must be updated anywhere `/model` usage is shown, not only in the parser error message.