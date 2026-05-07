# 110 — Prompt assets component extraction

## Goal

Extract the prompt composition / prompt assets slice out of `components/agent-session/` into its own component, so prompt-template discovery, skill discovery/formatting, and system-prompt assembly have a clear authoritative owner below the residual `agent-session` orchestration layer.

## Why

Task `105-agent-session-component-extraction-map` identified prompt composition / prompt assets as a coherent extractable subsystem inside `agent-session`, but the live namespace dependency graph sharpens that boundary further:

- `prompt_templates.clj` is a clean leaf
- `skills.clj` depends only on `prompt-templates` for shared frontmatter parsing
- `system_prompt.clj` depends only on `skills` and owns system-prompt assembly and prompt-component filtering
- `conversation.clj`, `tool_defs.clj`, and `message_text.clj` are adjacent but do not fit the same ownership boundary cleanly

Without an explicit extraction task, prompt assets remain mixed into `agent-session`, which keeps session initialization, child-session shaping, prompt request preparation, app-runtime bootstrap, and discovery/introspection consumers coupled to a historical placement rather than a coherent component owner.

## Problem

The current `agent-session` namespace placement blurs three different concerns:

- prompt assets and prompt assembly
- provider/request projection
- shared tool/message utility surfaces

A live namespace review shows that the true prompt-assets pipeline is narrower than the earlier umbrella phrasing suggested:

```text
prompt-templates -> skills -> system-prompt
```

The adjacent namespaces should not be pulled into the same extraction by default:

- `tool_defs.clj` is used broadly by runtime, workflow, extensions, scheduler, and ai/provider projection paths
- `conversation.clj` is a provider/request projection seam that depends on `system-prompt`, `tool-defs`, `psi.ai.conversation`, and `psi.tool-runtime.args`
- `message_text.clj` is a shared transcript/display helper consumed by app-runtime, rpc, tui, and session resolvers

If those boundaries are not made explicit, a prompt-component extraction risks becoming another half-boundary that moves unrelated utility and provider-shaping concerns under the wrong owner.

## Intent

Extract the prompt-assets subsystem narrowly and explicitly.

This task should establish an authoritative component that owns:

- prompt template discovery / parsing / invocation
- skill discovery / validation / prompt formatting / invocation
- system-prompt assembly
- prompt contribution formatting and filtering
- prompt-component selection/filtering for child-session/system-prompt shaping
- context-file discovery if it remains part of prompt assembly ownership

This task should not automatically include:

- provider conversation projection
- canonical tool-definition normalization for runtime-wide use
- transcript/display text helpers

## Proposed component boundary

### In scope for extraction

Authoritative first-cut namespaces:

- `psi.agent-session.prompt-templates`
- `psi.agent-session.skills`
- `psi.agent-session.system-prompt`

Expected extracted component shape:

- `components/prompt-assets/`
- authoritative namespaces under `psi.prompt-assets.*`

### Explicitly out of scope for this extraction

#### `psi.agent-session.conversation`

Reason:
- this is better understood as provider/request projection than prompt-asset ownership
- it consumes `system-prompt`, but also depends on `tool-defs`, `psi.ai.conversation`, and `psi.tool-runtime.args`
- it should remain outside this extraction unless a later task creates a dedicated request/projection component

#### `psi.agent-session.tool-defs`

Reason:
- live consumers span runtime, dispatch, workflow step prep, extensions, scheduler, and ai/provider projection
- this looks like a shared tool-definition substrate, not a prompt-assets-local namespace
- prompt-facing tool projection may eventually move or split, but that is not required for this task

#### `psi.agent-session.message-text`

Reason:
- live consumers are primarily app-runtime/rpc/tui/session-summary/transcript/display paths
- this is a presentation/transcript utility, not prompt assembly

## Current dependency graph

### Internal prompt-assets chain

```text
psi.agent-session.prompt-templates
  -> no internal psi deps

psi.agent-session.skills
  -> psi.agent-session.prompt-templates

psi.agent-session.system-prompt
  -> psi.agent-session.skills
```

### Adjacent but non-member graph

```text
psi.agent-session.conversation
  -> psi.agent-session.system-prompt
  -> psi.agent-session.tool-defs
  -> psi.ai.conversation
  -> psi.tool-runtime.args

psi.agent-session.tool-defs
  -> no internal psi deps

psi.agent-session.message-text
  -> no internal psi deps
```

## Live consumers to preserve

The extraction should preserve or deliberately update these current consumer paths.
These lists are the minimum known direct consumer inventory at task creation time, not a scope limit; any additional direct consumers discovered during implementation are also in scope and must migrate to the extracted component.

### `prompt-templates`

Observed consumers:
- `components/app-runtime/src/psi/app_runtime.clj`
- `components/agent-session/src/psi/agent_session/prompt_request.clj`
- `components/agent-session/src/psi/agent_session/commands.clj`
- `components/agent-session/src/psi/agent_session/resolvers/discovery.clj`
- `components/agent-session/src/psi/agent_session/workflow_file_parser.clj`

### `skills`

Observed consumers:
- `components/app-runtime/src/psi/app_runtime.clj`
- `components/app-runtime/src/psi/app_runtime/output.clj`
- `components/agent-session/src/psi/agent_session/prompt_request.clj`
- `components/agent-session/src/psi/agent_session/system_prompt.clj`
- `components/agent-session/src/psi/agent_session/resolvers/discovery.clj`
- `components/agent-session/src/psi/agent_session/workflow_step_prep.clj`

### `system-prompt`

Observed consumers:
- `components/app-runtime/src/psi/app_runtime.clj`
- `components/agent-session/src/psi/agent_session/prompt_request.clj`
- `components/agent-session/src/psi/agent_session/child_session_state.clj`
- `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_handlers.clj`

## Design guidance

### Preserve the narrow component boundary

The extracted component should remain the owner of prompt assets and prompt assembly only.

Do not let this task silently absorb:
- request/conversation projection
- global tool-definition ownership
- display/transcript helpers

### Prefer whole-namespace moves first

The current graph suggests that `prompt-templates`, `skills`, and `system-prompt` can move as whole namespaces in a first cut.

Do not force premature decomposition unless the extraction exposes a concrete blocker.

### Compatibility shims may be used only as temporary migration aids

If namespace migration requires compatibility wrappers in `psi.agent-session.*`, they should:
- be explicit forwarding shims
- preserve current public call sites only during the in-flight migration
- be removed before task completion

Completion for this task means direct consumers have been cut over to `psi.prompt-assets.*`; retained long-lived shims are not an acceptable end state.

### Keep `agent-session` as orchestration consumer

After extraction, `agent-session` should depend downward on the prompt-assets component for:
- prompt request preparation inputs
- child-session prompt shaping
- prompt/system prompt assembly
- discovery/introspection surfaces that report prompt templates or skills

### Keep context-file ownership explicit

`system_prompt.clj` currently owns `discover-context-files`.

Default first-cut decision for this task:
- move `discover-context-files` with `system-prompt` into the prompt-assets component unchanged

Only split it into a lower shared context-discovery concern if the extraction exposes a concrete blocker or a clearly superior lower owner. If that happens, record the reason explicitly in `implementation.md` rather than letting the boundary drift implicitly.

## Scope

In scope:
- extract `prompt-templates`, `skills`, and `system-prompt` into a new component
- update all live consuming namespaces to depend on the extracted component, including the workflow/discovery/output consumers listed in this task's live-consumer review
- preserve current behavior for prompt template discovery, skill discovery, system-prompt assembly, and child-session prompt filtering/shaping
- move associated focused tests to the new component test tree under `components/prompt-assets/test/` and give them final authoritative test namespaces under `psi.prompt-assets.*-test`
- keep app-runtime and agent-session integration paths green
- document any follow-on splits discovered during extraction

Out of scope:
- extracting `conversation`
- extracting `tool-defs`
- extracting `message-text`
- redesigning prompt runtime semantics
- redesigning provider conversation projection
- changing user-visible prompt/skill/template behavior except where needed to preserve existing semantics after the move

## Acceptance

- a new extracted prompt-assets component exists with authoritative ownership of prompt templates, skills, and system-prompt assembly
- authoritative namespaces live under `psi.prompt-assets.*` for the extracted prompt-assets surface
- all live consumers listed in this task's consumer review now depend downward on the extracted component
- current prompt template discovery/invocation behavior is preserved
- current skill discovery/validation/prompt-formatting/invocation behavior is preserved
- current system-prompt assembly, prompt contribution formatting/filtering, and child-session prompt-component filtering behavior is preserved
- `conversation`, `tool-defs`, and `message_text` remain outside the extracted component
- focused tests for the extracted component are green; after relocation/renaming, the required final-state authoritative suites are:
  - `clojure -M:test --focus psi.prompt-assets.prompt-templates-test`
  - `clojure -M:test --focus psi.prompt-assets.skills-test`
  - `clojure -M:test --focus psi.prompt-assets.system-prompt-test`
- focused consuming-path verification for all relevant prompt-building and child-session prompt-shaping consumers is green, with the required minimum proofs:
  - `clojure -M:test --focus psi.agent-session.child-session-state-test`
  - `clojure -M:test --focus psi.agent-session.child-session-mutation-test`
  Indirect app-runtime coverage through the relocated prompt-assets tests and the migrated shared prompt-building call paths is acceptable for task completion unless implementation reveals a distinct app-runtime-only regression surface that needs its own focused proof.
- no compatibility forwarding shims under `psi.agent-session.*` remain at task completion
- `implementation.md` records any follow-on boundary issues discovered during extraction, especially if a lower split was needed beyond the default context-file move

## Suggested execution order

1. confirm final extracted namespace naming and component directory shape
2. inspect the three source namespaces for any hidden non-prompt dependencies
3. move `prompt-templates`
4. move `skills`
5. move `system-prompt`
6. update all live consumers listed in this task's consumer review
7. relocate/update focused tests
8. run focused extracted-component verification
9. run focused consuming-path verification
10. record any residual follow-on boundary issues

## Related work

- `105-agent-session-component-extraction-map` is the umbrella extraction map
- `106-provider-auth-component-extraction` and `107-project-nrepl-component-extraction` are prior validated child extractions
- this task instantiates the prompt composition / prompt assets child boundary from `105`, but with a narrower scope than the umbrella's first rough candidate list
- `conversation`, `tool-defs`, and `message_text` may become follow-on extraction or split tasks, but are intentionally not part of this task
