# Implementation plan

## Approach

Implement the task as a queryability-first vertical slice: define the core UI capability/action data contract, expose it through root/runtime-scoped EQL attrs, then wire one real frontend provider for Emacs while keeping TUI/console/headless honest about unsupported make-visible behaviour.

Key decisions:

- Add a dedicated UI capability resolver if discovery confirms it keeps existing extension UI contribution snapshot attrs separate; extend the existing resolver only if that is the simpler one-way path.
- Store no advertised capabilities/actions in root state. Resolve `:psi.ui/...` attrs by looking up and calling the current runtime UI capability provider on demand.
- Treat provider output as untrusted adapter data. Normalize safe defaults, validate descriptor/coherence/invocation shapes, filter `:psi.ui/actions` to available descriptors only, and map invalid provider output to provider-error unavailable semantics.
- Make `:psi.ui/make-visible-action` a stable descriptor in every state. It is available only when `:psi.ui.capability/make-visible` is present and exactly one matching available action is exposed.
- Implement the Emacs make-visible advertisement and command (`psi-emacs-show-active`) in this task. TUI/console advertise make-visible only if existing runtime state already provides a real safe reveal mechanism; otherwise they report attached-but-unsupported or no-attached/no-provider semantics as designed.
- Keep generic side-effecting invocation small. Record the `:psi.ui/request-action` event/submission contract in implementation notes/docs; implement the constrained helper only if it fits without broadening the slice. Otherwise leave invocation descriptor-only and create a follow-up task.
- Preserve legacy `:ui-type` / `:psi.agent-session/ui-type` surfaces as compatibility diagnostics while making `:psi.ui/...` capability/action queries normative in extension docs.

## Risks

- Runtime-context/provider ownership may be spread across app-runtime, TUI, RPC, and Emacs wiring. The provider slot must support late install/replacement and avoid stale detached frontend providers.
- Pathom resolver discovery/root-queryable attr registration can drift from resolver implementation if the new attrs are not added to the same indexes used by `psi-tool` and extension queries.
- Descriptor validation can become too permissive (leaking frontend objects or incoherent actions) or too strict (rejecting valid serialisable data). Keep validation small, explicit, and tested by invalid-provider cases.
- Implementing `:psi.ui/request-action` may touch permission/dispatch/frontend event boundaries. If it stops being small, split it out rather than expanding this task beyond queryability plus descriptor contract.
- Emacs focus behaviour may depend on live buffer/frame state that is hard to unit test. Keep the Lisp command small and test the Clojure advertisement separately.
- Existing documentation currently mentions UI-type branching; missed references could leave conflicting extension guidance.

## Slice order

1. **Discovery and seam selection** — locate current UI/query/runtime seams, root-queryable attr registration, extension query path, frontend UI-type surfaces, and Emacs/TUI/console attachment state.
2. **Core model and validation** — add constants/helpers/schemas for UI capabilities, action descriptors, unavailable descriptors, invocation validation, serialisability checks, and provider-result normalization.
3. **EQL resolver and discovery** — expose `:psi.ui/type`, `:psi.ui/available?`, `:psi.ui/capabilities`, `:psi.ui/actions`, `:psi.ui/make-visible-action`, and `:psi.ui/diagnostic` from `:psi/agent-session-ctx` with missing-provider/headless/provider-error semantics and root-queryable discovery.
4. **Runtime provider installation** — add the runtime-context active UI capability provider slot and wire provider lookup so adapters can install/replace/clear providers after ctx creation without caching advertised results in root state.
5. **Frontend providers** — wire Emacs provider and `psi-emacs-show-active`; wire TUI/console/headless providers or absence semantics so they do not claim unsupported make-visible capability.
6. **Invocation contract boundary** — document and, only if small, implement the constrained `:psi.ui/request-action` helper/event path with request/result validation; otherwise create a follow-up task and keep descriptors invokable only by contract.
7. **Tests** — add nullable/provider-normalization tests, root discovery/index tests, extension-query tests, Emacs available descriptor tests, and TUI/console/headless unsupported semantics tests.
8. **Documentation and coherence** — update extension-authoring docs and architecture guidance, record implementation decisions/follow-up in `implementation.md`, and run focused verification plus lint.
