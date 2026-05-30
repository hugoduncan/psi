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

- Define a serialisable UI capability/action model owned by the core/runtime query and provider contract, not by persisted root-state advertisements, a concrete frontend namespace, or individual agent sessions.
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

Unavailable action fields are normative when an action object is returned:

```clojure
{:psi.ui.action/id :psi.ui.action/make-visible
 :psi.ui.action/capability :psi.ui.capability/make-visible
 :psi.ui.action/label "Show Psi UI"
 :psi.ui.action/description "Bring the active Psi UI to the foreground."
 :psi.ui.action/available? false
 :psi.ui.action/unavailable-reason :psi.ui.unavailable.reason/no-attached-ui
 :psi.ui.action/unavailable-message "No attached UI adapter can make itself visible."}
```

Unavailable reasons are machine-readable keywords in the `:psi.ui.unavailable.reason/...` namespace. The minimum reason vocabulary for this slice is:

- `:psi.ui.unavailable.reason/no-provider` — no adapter capability provider is installed.
- `:psi.ui.unavailable.reason/no-attached-ui` — a provider is installed but reports no available UI.
- `:psi.ui.unavailable.reason/unsupported-capability` — a UI is attached but does not support the requested capability.
- `:psi.ui.unavailable.reason/provider-error` — the provider threw or returned invalid data.

The human-readable `:psi.ui.action/unavailable-message` is required whenever `:psi.ui.action/available?` is false. Provider-error cases may also include `:psi.ui/diagnostic` with bounded, serialisable diagnostic text for logs/debugging; diagnostics must not expose frontend objects or stacktrace data by default.

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

Exact unsupported/headless/provider-error semantics:

- Missing provider: `:psi.ui/type nil`, `:psi.ui/available? false`, `:psi.ui/capabilities []`, `:psi.ui/actions []`; `:psi.ui/make-visible-action` returns an unavailable make-visible descriptor with reason `:psi.ui.unavailable.reason/no-provider`.
- Provider reports no attached UI: `:psi.ui/type` may be nil or the known adapter type, `:psi.ui/available? false`, `:psi.ui/capabilities []`, `:psi.ui/actions []`; `:psi.ui/make-visible-action` returns an unavailable make-visible descriptor with reason `:psi.ui.unavailable.reason/no-attached-ui`.
- Attached UI without make-visible support: `:psi.ui/available? true`; `:psi.ui.capability/make-visible` is absent from `:psi.ui/capabilities`; `:psi.ui/actions` omits any make-visible descriptor; `:psi.ui/make-visible-action` returns an unavailable make-visible descriptor with reason `:psi.ui.unavailable.reason/unsupported-capability`.
- Provider error or invalid provider result: resolver catches the failure and returns unavailable data rather than dropping attrs from the EQL result. `:psi.ui/available? false`, `:psi.ui/capabilities []`, `:psi.ui/actions []`, `:psi.ui/make-visible-action` returns an unavailable descriptor with reason `:psi.ui.unavailable.reason/provider-error`, and the root UI map may include bounded `:psi.ui/diagnostic` text.
- Supported make-visible: `:psi.ui.capability/make-visible` is present in `:psi.ui/capabilities`; `:psi.ui/actions` contains the available descriptor; `:psi.ui/make-visible-action` returns that same descriptor.

Canonical `:psi.ui/actions` membership is available-actions-only after resolver normalization. Unavailable descriptors are not exposed in the general actions collection; they are reserved for convenience attrs such as `:psi.ui/make-visible-action`, where a stable action-shaped result is useful even when the action cannot be executed. Therefore missing-provider, no-attached-UI, unsupported-make-visible, and provider-error cases all expose no make-visible descriptor through `:psi.ui/actions`; if there are no other available UI actions, `:psi.ui/actions` is `[]`. A provider may internally return an unavailable descriptor, but the normalized EQL collection must filter unavailable descriptors out unless a future task explicitly adds a separate diagnostics/history action collection.

Provider capability/action coherence rules are strict at the resolver boundary. Every exposed available action's `:psi.ui.action/capability` must be present in the normalized `:psi.ui/capabilities`; an available action whose capability is absent is invalid provider output and maps the whole UI result to provider-error semantics. Not every advertised capability needs an available action descriptor, because some capabilities may describe passive UI facts rather than invokable actions. However, `:psi.ui.capability/make-visible` is action-backed in this slice: if it is present, exactly one available descriptor with `:psi.ui.action/id :psi.ui.action/make-visible` and capability `:psi.ui.capability/make-visible` must be present in `:psi.ui/actions`, and `:psi.ui/make-visible-action` must return that same descriptor. If the provider reports make-visible capability without that available descriptor, reports an available make-visible descriptor while omitting the capability, or reports only an unavailable make-visible descriptor while claiming the capability, the resolver treats the provider output as incoherent provider-error data rather than guessing.

The convenience attr `:psi.ui/make-visible-action` must return a descriptor object in all cases, available or unavailable, so extension code can inspect one stable shape. Capability presence remains stricter than descriptor presence: `:psi.ui.capability/make-visible` is present only when the active UI currently supports making itself visible.

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

Provider installation is owned by the adapter/runtime wiring layer that creates the frontend attachment, not by extension code and not by the resolver. Agent-session ctx creation may precede UI attachment, so the provider slot must support late installation or replacement after the ctx exists. The preferred shape is a stable provider lookup in the runtime context (for example an atom-backed `:ui/capability-provider` slot or equivalent runtime component reference) that the resolver dereferences/calls at query time; the resolver must not capture a provider value once at startup. RPC/Emacs installs or updates this slot when an Emacs/RPC connection attaches and has enough connection state to identify the active Psi buffer/session; it clears the slot, marks the attachment unavailable, or replaces it with a no-attached provider when the connection detaches, shuts down, or loses active UI state. TUI installs or updates the same slot during TUI runtime wiring after its UI state/focus atom exists, and clears or marks unavailable on TUI shutdown. Console/headless runtimes either leave the slot absent or install a provider that reports no available make-visible action unless they have a real visibility mechanism.

Only one active UI provider is authoritative in this slice. If more than one adapter/connection could install a provider, the runtime wiring must choose a single active provider using existing active-frontend/focus ownership rather than merging descriptor sets. A later attachment may replace the active provider only through an explicit runtime handoff/update that also determines the active UI identity; stale providers from detached frontends must be removed or made unavailable so they cannot advertise actions for dead UI state. If implementation discovery finds no existing single-active-frontend owner, this task should add a minimal runtime-owned active-provider slot rather than allowing multiple adapters to race or independently advertise `:psi.ui/actions`. Resolver output for no installed active provider remains `:psi.ui.unavailable.reason/no-provider`; resolver output for an installed provider whose adapter is attached but currently has no usable UI/focus state remains `:psi.ui.unavailable.reason/no-attached-ui`.

Runtime-scoped discovery uses the active UI adapter's own active/focused session identity when no `:psi.agent-session/session-id` input is supplied. The resolver is intentionally runtime-scoped and must not infer a session from arbitrary extension caller state. The provider should choose the session/buffer/window that the attached frontend currently considers active, using existing adapter state such as TUI `:focus-session-id` / context snapshot `:active-session-id` or Emacs/RPC current Psi buffer/session state. If the adapter has no focused session but can still make a runtime-global UI visible, it may advertise the action with `:psi.ui.request/session-id nil`; otherwise the make-visible descriptor is unavailable with `:psi.ui.unavailable.reason/no-attached-ui` or `:psi.ui.unavailable.reason/unsupported-capability` as appropriate. Available descriptors whose invocation depends on a concrete session must include correlation data under `:psi.ui.action/invocation`, preferably `:psi.ui.invocation/session-id` and, when available, `:psi.ui.invocation/runtime-id`, so a later `:psi.ui/request-action` copies the same identity into `:psi.ui.request/session-id` / `:psi.ui.request/runtime-id`. Emacs make-visible should target the current/active Psi buffer tracked by the Emacs/RPC frontend; the command name remains declarative, while the Emacs adapter owns resolving that active buffer at invocation time.

Adapters/frontends should provide pure data describing supported actions on demand. Capabilities/actions should not be stored in root state as advertised state; the resolver should derive the current descriptor from the attached UI adapter/request channel by calling the provider. The resolver may normalize/synthesize convenience attrs such as `:psi.ui/make-visible-action` from the provider result, but the provider remains the authoritative adapter-to-core contract for active UI capability data.

The resolver is responsible for normalizing and validating provider output before exposing it to EQL:

- `:psi.ui/type` is defaultable to nil and, when present, must be a keyword.
- `:psi.ui/available?` is defaultable to false and must be coerced only from an explicit boolean; non-boolean values are invalid provider output.
- `:psi.ui/capabilities` is defaultable to `[]`; entries must be keywords in the `:psi.ui.capability/...` namespace. Unknown or shorthand capability namespaces are invalid rather than silently contractual.
- `:psi.ui/actions` is defaultable to `[]`; entries must be maps with namespaced descriptor keys, serialisable scalar/collection values, `:psi.ui.action/id`, `:psi.ui.action/capability`, `:psi.ui.action/label`, `:psi.ui.action/description`, and boolean `:psi.ui.action/available?`.
- Available action descriptors require `:psi.ui.action/invocation`; the invocation map requires `:psi.ui.invocation/kind` and that kind must be one of the supported design kinds (`:emacs-command`, `:ui-event`, `:bash-command`, `:mutation`). Unknown invocation kinds are invalid provider output for this slice, because exposing them would create an uninvokable contract.
- Unavailable action descriptors require `:psi.ui.action/unavailable-reason` as a `:psi.ui.unavailable.reason/...` keyword and `:psi.ui.action/unavailable-message` as bounded text.
- Descriptor maps must be serialisable pure data. Functions, atoms, promises, channels, buffers, terminal handles, exceptions, and arbitrary Java objects are invalid provider output.
- Duplicate action ids are invalid provider output unless implementation discovery finds an existing canonical merge convention; prefer rejecting duplicates for this slice.
- Unavailable descriptors from the provider are valid only as provider-side diagnostics/input to convenience-attr synthesis; the normalized `:psi.ui/actions` attr exposes available descriptors only.
- Available actions must have capabilities that are present in the normalized capability vector. The make-visible capability additionally requires exactly one matching available make-visible action descriptor.

Invalid provider output maps the whole UI capability result to the provider-error unavailable semantics rather than partially exposing a suspect descriptor: `:psi.ui/available? false`, empty capabilities/actions, a provider-error make-visible descriptor, and bounded `:psi.ui/diagnostic` text. Missing/defaultable fields are normalized only when their defaults preserve a safe headless/unavailable meaning; invalid typed fields, unknown invocation kinds, non-serialisable values, bad namespaces, malformed action descriptors, duplicate action ids, and capability/action incoherence are provider errors.

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

Concrete UI action request contract for this slice, if side-effecting invocation is implemented:

- Dispatch event name: `:psi.ui/request-action`.
- Required payload keys:
  - `:psi.ui.request/id` — unique request id for acknowledgement correlation.
  - `:psi.ui.request/action-id` — action keyword, for example `:psi.ui.action/make-visible`.
  - `:psi.ui.request/invocation` — the descriptor's `:psi.ui.action/invocation` map, copied as data.
  - `:psi.ui.request/session-id` — active agent session id when known; nil is allowed only for runtime-global actions.
  - `:psi.ui.request/source` — keyword identifying the requester, for example `:extension` or `:psi-tool`.
- Optional payload keys:
  - `:psi.ui.request/capability` — requested capability keyword.
  - `:psi.ui.request/runtime-id` — runtime correlation id if the runtime already exposes one.
  - `:psi.ui.request/timeout-ms` — requester timeout for acknowledgement waits.
  - `:psi.ui.request/metadata` — bounded serialisable metadata for diagnostics.
- Extension-facing submission API: extensions submit through a dedicated core-owned UI action request helper exposed at the same extension boundary as graph queries, not through frontend namespaces. The helper constructs and submits only `:psi.ui/request-action` events from previously discovered descriptor data. It is intentionally permission-free for this slice and is not governed by extension manifest `allowed-events`, because the request surface is a constrained core UI affordance rather than arbitrary extension-origin dispatch. Permission-free does not mean unvalidated: the helper/resolver path must reject unknown action ids, unavailable descriptors, malformed invocation data, unsupported invocation kinds, stale session/runtime correlation, and requests that no longer match the active provider's current capabilities. If this dedicated helper is not practical during implementation, this task must expose descriptors only and open a follow-up for permission-aware side-effecting invocation rather than routing extensions through the generic permission-gated dispatch API.
- Acknowledgement/result shape:

```clojure
{:psi.ui.result/request-id request-id
 :psi.ui.result/action-id :psi.ui.action/make-visible
 :psi.ui.result/status :accepted} ; one of :accepted :completed :rejected :unsupported :failed :timeout
```

Rejected/unsupported/failed results include `:psi.ui.result/reason` as a machine-readable keyword and `:psi.ui.result/message` as bounded human-readable text. The active UI adapter owns translating accepted requests into frontend effects and may emit completion asynchronously. The query descriptor remains authoritative for discoverability; the request result is authoritative only for that invocation attempt.

## Acceptance criteria

- A runtime-scoped, core-owned, serialisable UI capability/action model exists and is documented in code or task implementation notes; core owns the query/provider contract, while advertised capabilities/actions are derived on demand rather than stored or cached in root state.
- EQL exposes UI availability, capabilities, and actions through `:psi.ui/...` attrs unless implementation discovery justifies different names.
- Graph discovery surfaces include the new UI attrs/resolvers.
- Capabilities are exposed separately from action descriptors.
- The "make UI visible" capability/action is represented as pure data when supported and as an explicit absent/unavailable state when unsupported/headless.
- Emacs advertises a supported make-visible action.
- TUI and console advertise make-visible only when a real mechanism exists, such as tmux; otherwise they are capability-driven unavailable cases.
- Querying does not require extension permissions in this slice. Side-effecting invocation requires no manifest `allowed-events` permission only if implemented through the dedicated constrained `:psi.ui/request-action` submission helper described above; otherwise invocation remains descriptor-only and a follow-up task owns permission-aware submission.
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
