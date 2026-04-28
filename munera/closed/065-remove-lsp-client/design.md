Goal: remove the built-in LSP client integration from psi.

Problem:
- psi currently carries an LSP extension/client surface with runtime wiring, commands, tests, and related documentation.
- If the project direction is to remove that client entirely, the current LSP-specific code and user-facing surfaces become maintenance burden and architectural noise.
- The removal should be deliberate so that no dead commands, extension registrations, tests, or docs remain, while preserving the reusable generic sub-process lifecycle/registry infrastructure that still has non-LSP value.

Intent:
- Eliminate the LSP client as a supported psi capability.
- Replace the existing LSP follow-on task with a removal task that matches the new direction.
- Leave the repository in a coherent state with code, tests, docs, and task surfaces aligned to that removal.

Scope:
- Remove the LSP extension/client implementation and its registration/wiring.
- Remove user-facing LSP commands and capability surfaces.
- Remove LSP-specific tests, fixtures, helpers, and proof surfaces.
- Recast any retained tests so they prove preserved generic infrastructure without LSP semantics, names, or fixtures.
- Remove or update active docs/meta/task references that describe LSP as an active capability or active workstream.
- Preserve reusable generic sub-process lifecycle/registry infrastructure where it remains independently valuable.
- Mark the existing open task `004-lsp-integration-managed-services-post-tool-processing` superseded by `065` during implementation, and close it as superseded when `065` lands.

Out of scope:
- Replacing the LSP client with a different editor/service integration.
- Broad redesign of extension or managed-service architecture beyond the minimum extraction needed to preserve reusable generic sub-process lifecycle/registry infrastructure.
- Preserving LSP-shaped protocol/client behavior merely because it could be useful later.
- Adding new language intelligence features.
- Writing historical removal notes into current user docs beyond ceasing to advertise LSP support.
- Scrubbing historical/archive references in already-closed tasks or changelog history unless they actively mislead current supported capability.

Minimum concepts:
- LSP client capability
- extension/runtime registration
- user-facing command surface
- tests as proof of supported behavior
- active docs/task/meta surfaces as the declared current project story
- reusable generic sub-process lifecycle/registry infrastructure versus LSP-specific mechanism

Preferred implementation shape:
1. Extract-and-remove
   - identify and preserve reusable generic sub-process lifecycle/registry pieces
   - remove the LSP-specific layer, wiring, commands, tests, and docs/task references
   - this best matches the requirement to fully remove the client while keeping independently valuable infrastructure

Rejected shapes:
1. Direct removal
   - too blunt if it risks deleting sub-process infrastructure that should remain
2. Deactivate-then-delete
   - not preferred because the goal is full removal, not a staged dormant surface

Task supersession:
- Task `004-lsp-integration-managed-services-post-tool-processing` is not a parallel active direction once `065` implementation begins.
- During implementation, mark `004` superseded by `065`.
- When `065` lands, close `004` as superseded.

Architecture guidance:
- Prefer one-way removal over compatibility shims.
- Preserve canonical ownership boundaries: shared runtime infrastructure stays only if it remains independently justified.
- The preservation target is limited to generic sub-process lifecycle/registry mechanics that are not inherently LSP-specific.
- Do not preserve JSON-RPC, diagnostics, workspace-sync, or other LSP-shaped protocol/client behavior unless it already has a current non-LSP consumer and can be described generically.
- Preserve generic sub-process lifecycle/registry infrastructure as general infrastructure, not as a hidden LSP remnant.
- Remove dead capability surfaces completely rather than hiding them behind inert flags.
- Keep the change vertically coherent across active meta/spec/tests/code/docs/tasks.

Acceptance:
- psi no longer exposes an LSP client capability, command surface, or extension registration.
- No LSP-specific namespace, file, fixture, helper, or test remains in active repository surfaces; retained generic infrastructure is named and described generically rather than as dormant LSP support.
- repository tests and verification surfaces no longer depend on LSP behavior; any retained tests prove generic sub-process infrastructure without LSP semantics, names, or fixtures.
- reusable generic sub-process lifecycle/registry infrastructure remains only where it has explicit non-LSP justification.
- active docs/meta/task surfaces no longer present LSP client work as active supported functionality.
- current docs simply stop advertising LSP support; no separate removal announcement is required.
- historical/archive references in closed tasks or changelog history may remain unless they misstate current support.
- open task `004-lsp-integration-managed-services-post-tool-processing` is marked superseded by `065` during implementation and is closed as superseded when `065` lands.
- the repository is green after the removal.
