# Core-queryable UI

## Intent

Make UI state and UI commands queryable from the core graph so extensions can discover what UI is available and request UI-facing behaviour without depending on a concrete frontend implementation.

The motivating example is an extension that observes an agent session becoming idle and asks the system for a command/action that will make the active UI visible, then invokes or presents that command.

## Problem

Extensions currently receive UI affordances through the extension UI context for direct contribution of dialogs, widgets, status, notifications, and renderer hooks. That supports writing to the UI, but it does not give extensions a core-owned, queryable description of the UI itself.

As a result, an extension cannot reliably answer questions such as:

- Is there an attached/active UI for this session/runtime?
- What kind of UI is attached (`:tui`, `:emacs`, `:console`, or headless/no UI)?
- What UI actions are available from core, such as "make the UI visible"?
- How should an extension request such an action without hard-coding frontend-specific commands?

This creates an architectural gap: extensions are meant to integrate through core contracts, but UI visibility and similar UI behaviours are only implicit in adapters/frontends.

## Scope

This task adds a runtime-scoped, core-owned query surface for UI capabilities and UI action descriptors.

In scope:

- Define a serialisable UI capability/action model owned by core/runtime state, not by a concrete frontend namespace and not by individual agent sessions.
- Expose UI information through the Pathom/EQL graph used by extensions and `psi-tool`, using a `:psi.ui/...` attribute namespace unless implementation discovery shows a stronger existing convention.
- Include at least one discoverable action descriptor for making the UI visible when a frontend supports it.
- Keep capabilities separate from actions so extensions can reason about what is possible without branching on concrete UI type.
- Represent unsupported capability/action cases explicitly enough that extensions can distinguish "capability absent" from query failure.
- Support a single active UI model for this slice.
- Ensure frontend adapters can provide or derive their supported UI action descriptors on demand without extensions depending on adapter internals and without storing advertised capabilities/actions in root state.
- Preserve existing extension UI contribution APIs (`:ui` context functions for widgets/dialogs/status/etc.).
- Add nullable and frontend-specific tests proving a core/extension query can discover UI capabilities/actions and the "make visible" descriptor when available.
- Update extension-authoring documentation for the new queryable UI surface.

Out of scope:

- Implementing idle detection or an idle-triggering extension.
- Reworking existing widget/dialog/status contribution APIs.
- Modelling multiple simultaneously attached UIs.
- Adding permissions for UI capability/action query or invocation.
- Changing frontend rendering behaviour except where needed to advertise an already-supported make-visible action.

## Design requirements

### Core-owned UI model

The model must be pure data and safe to return from EQL. It should describe UI availability without exposing functions, promises, frontend objects, buffers, or terminal handles.

Minimum fields:

- A single active UI type/identity when known, but extension logic must be capability-driven rather than UI-type-driven.
- A runtime-level attached/available boolean, if this is distinct from the UI identity.
- A collection of capability keywords in the resolved `:psi.ui.capability/...` namespace, for example `:psi.ui.capability/visible` and `:psi.ui.capability/make-visible`. The shorthand `:ui.capability/...` form is non-normative and must not be used as a contract in implementation, tests, or extension guidance.
- A collection of action descriptors.

Capabilities and actions are separate concepts:

- Capabilities describe what the active UI/runtime can in principle support.
- Actions describe concrete side-effecting invocations that can be executed or presented.

Action descriptors should be stable pure data with fully namespaced keys. The make-visible descriptor should use:

```clojure
{:psi.ui.action/id :psi.ui.action/make-visible
 :psi.ui.action/capability :psi.ui.capability/make-visible
 :psi.ui.action/label "Show Psi UI"
 :psi.ui.action/description "Bring the active Psi UI to the foreground."
 :psi.ui.action/available? true
 :psi.ui.action/invocation {:psi.ui.invocation/kind :emacs-command
                            :psi.ui.invocation/command "psi-emacs-show-active"}}
```

Optional unavailable fields:

```clojure
{:psi.ui.action/available? false
 :psi.ui.action/unavailable-reason "No attached UI adapter can make itself visible."}
```

Invocation is a tagged union under `:psi.ui.action/invocation`. Supported design kinds are:

- `:emacs-command` — command name string to send to/evaluate in Emacs UI.
- `:ui-event` — adapter-neutral event/action payload to send back to the active UI adapter.
- `:bash-command` — argv-style command for tmux-style reveal actions.
- `:mutation` — Pathom mutation symbol plus params, if an existing mutation is the cleanest path.

The query and invocation paths may share the same descriptor model: querying returns the descriptor as pure data; invoking the descriptor is the side-effecting half. The implementation should prefer existing command/mutation/event/effect mechanisms over introducing a one-off UI callback path.

### Query surface

The EQL graph must make runtime-scoped UI information discoverable using existing graph discovery conventions:

- new attributes appear in root-queryable attrs and/or resolver/attr indexes as appropriate
- resolver input/output is explicit
- no session id is required for the canonical UI capability surface
- session-scoped code may still query the same runtime UI attrs through the normal extension query path
- headless/console cases return explicit empty/unavailable data, not missing attrs due to exceptions

UI capability attrs use the existing `:psi.ui/...` namespace already used by extension UI snapshot attrs:

- `:psi.ui/type`
- `:psi.ui/available?`
- `:psi.ui/capabilities`
- `:psi.ui/actions`
- `:psi.ui/make-visible-action`

Resolver input should be only `:psi/agent-session-ctx` so these attrs are root-queryable and runtime-scoped. Implementation may extend the existing extension UI resolver or add a dedicated UI capability resolver; prefer a dedicated resolver if it keeps contribution snapshot state separate from capability/action state.

### Frontend registration/adaptation

Frontend-specific knowledge belongs at adapter boundaries. Core should not know how to focus an Emacs frame or reveal a TUI window directly.

Adapters/frontends provide UI capabilities through a runtime-context capability provider, not by writing advertised capabilities into root state. The runtime context should contain an optional provider function under a stable adapter boundary key such as `:ui/capability-provider` (final key may follow the existing runtime-context naming convention discovered during implementation). The provider contract is:

```clojure
(fn [ctx]
  {:psi.ui/type :emacs              ;; keyword or nil
   :psi.ui/available? true          ;; boolean
   :psi.ui/capabilities [...]       ;; vector of capability keywords
   :psi.ui/actions [...]})          ;; vector of pure action descriptors
```

The provider receives the runtime/session context needed to derive current adapter facts and returns only serialisable pure data. It must not return executable functions, Emacs buffers, terminal handles, channels, promises, or cached mutable frontend objects. A missing provider is interpreted as headless/no UI and returns `:psi.ui/available? false`, empty capabilities/actions, and an unavailable make-visible descriptor when `:psi.ui/make-visible-action` is queried. Provider exceptions are caught at the resolver boundary and mapped to explicit unavailable data plus diagnostic reason text rather than causing the root UI attrs to disappear from EQL results.

Adapters/frontends should provide pure data describing supported actions on demand. Capabilities/actions should not be stored in root state as advertised state; the resolver should derive the current descriptor from the attached UI adapter/request channel by calling the provider. The resolver may normalize/synthesize convenience attrs such as `:psi.ui/make-visible-action` from the provider result, but the provider remains the authoritative adapter-to-core contract for active UI capability data.

Requests to perform UI actions use a core-owned dispatch event/subscription path. The authoritative invocation route is a dispatchable UI request event (for example `:psi.ui/request-action`) carrying the action id and, when needed, the descriptor invocation data. Active UI adapters subscribe to that event through their existing runtime/RPC/frontend event channel and execute it only if they currently support the request. Extension code must not call Emacs commands, frontend namespaces, or adapter functions directly. For the Emacs make-visible descriptor, `:psi.ui.action/invocation` carries `{:psi.ui.invocation/kind :emacs-command :psi.ui.invocation/command "psi-emacs-show-active"}` as declarative data; the Emacs adapter receives the core UI request and is the only component that turns that command name into an Emacs-side command invocation. If generic side-effecting invocation is split to a follow-up, this event name/payload contract remains the required design contract and the current slice may expose descriptors without implementing the submitter.

Requests to perform UI actions should prefer this core-owned event/subscription path: core emits a declarative UI request, the active UI adapter subscribes to that event and executes it if it currently supports the request, and an optional acknowledgement/result is recorded or emitted for diagnostics. Core should not store frontend executable handles or capability/action caches as the primary mechanism.

Expected frontend handling:

- Emacs UI should advertise a supported command/action that makes the Psi UI buffer/frame visible. Proposed command: `psi-emacs-show-active`, implemented by locating the active/current Psi buffer, calling `pop-to-buffer`, focusing the prompt with existing focus helpers, and selecting/focusing the frame/window as needed.
- TUI may advertise a supported make-visible action if a real mechanism exists, for example safe tmux target metadata plus an argv-style `tmux switch-client ...` command; otherwise it should omit the capability or return an explicit unavailable action.
- Console follows the same capability-driven rule as TUI: if a real visibility mechanism exists, advertise it; otherwise do not pretend support.
- Headless/no-UI mode should advertise no available make-visible capability/action with a clear unavailable reason where an action object is returned.

Extensions must not branch on `:emacs`, `:tui`, or `:console` to decide behaviour. They should branch on capabilities and action availability.

### Extension usage

An extension should be able to query the graph and decide:

1. whether a UI is available,
2. whether `:psi.ui.capability/make-visible` is present,
3. whether a make-visible action exists and is available,
4. what invocation data to use or present.

The task is complete when that decision can be made without extension code importing frontend namespaces or assuming Emacs/TUI details.

### Invocation

This task should design the invocation path enough to avoid a dead descriptor, but implementation may stay minimal.

Preferred shape: the same action descriptor returned by query can be sent to a core-owned side-effecting request path. That path should emit a declarative UI request event. The active UI adapter subscribes to the event and performs the adapter-specific action. The request may carry invocation data such as:

- a UI adapter event/action payload,
- a bash command descriptor for adapters that intentionally expose one,
- an existing command or mutation reference.

If implementing generic action invocation would broaden the task too far, this task must still define the descriptor shape, the event/request contract, and create a follow-up task for side-effecting invocation.

## Acceptance criteria

- A runtime-scoped, core-owned, serialisable UI capability/action model exists and is documented in code or task implementation notes; capabilities/actions are derived on demand rather than cached in root state.
- EQL exposes UI availability, capabilities, and actions through `:psi.ui/...` attrs unless implementation discovery justifies different names.
- Graph discovery surfaces include the new UI attrs/resolvers.
- Capabilities are exposed separately from action descriptors.
- The "make UI visible" capability/action is represented as pure data when supported and as an explicit absent/unavailable state when unsupported/headless.
- Emacs advertises a supported make-visible action.
- TUI and console advertise make-visible only when a real mechanism exists, such as tmux; otherwise they are capability-driven unavailable cases.
- Querying/invoking does not require extension permissions in this slice.
- The design or implementation records the UI request event/subscription contract and whether side-effecting invocation is completed in this task or split to a follow-up.
- Tests cover:
  - nullable extension API/query behaviour,
  - available UI action discovery for the Emacs-supported case,
  - frontend-specific TUI/console/headless capability behaviour,
  - resolver discovery/index visibility for the new attrs.
- Extension-authoring docs describe how extensions query UI capabilities/actions and avoid UI-type branching.
- Existing UI contribution APIs and existing UI snapshot/query behaviour continue to pass their tests.

## Notes

This task is about making UI capabilities queryable from core and defining a coherent descriptor/invocation contract, not about letting extensions mutate arbitrary UI internals. If implementation reveals that invoking UI actions needs a larger effect/mutation contract, create a follow-up task rather than expanding this task beyond queryability plus action description.
