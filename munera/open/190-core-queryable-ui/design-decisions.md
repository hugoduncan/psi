# Design decisions

## 1. Attribute names and resolver shape

Use root/runtime-scoped `:psi.ui/...` attrs. Existing extension UI snapshot attrs already use `:psi.ui/...`, so the capability surface should extend that namespace rather than introduce `:psi.agent-session/...` attrs.

Initial attr set:

- `:psi.ui/type` — keyword or nil, adapter identity/debugging only; extensions should not branch on it.
- `:psi.ui/available?` — boolean, true when a concrete UI adapter is attached.
- `:psi.ui/capabilities` — vector of capability keywords supported by the active UI/runtime.
- `:psi.ui/actions` — vector of action descriptors.
- `:psi.ui/make-visible-action` — always an action descriptor for `:psi.ui.capability/make-visible`; the descriptor is available when supported and unavailable with a machine-readable reason/message when unsupported, headless, or provider-error.

The resolver should require only `:psi/agent-session-ctx` as input so these attrs are root-queryable. It should not require `:psi.agent-session/session-id`.

The existing `extension-ui-resolver` may either be extended or split into a dedicated UI capability resolver. Prefer a dedicated resolver if it keeps extension-contribution snapshot attrs separate from UI capability/action attrs.

## 2. Capability and action vocabulary

Use namespaced keywords for capabilities:

- `:psi.ui.capability/make-visible`

Use namespaced keywords for action ids:

- `:psi.ui.action/make-visible`

Capabilities are present only when the active UI/runtime can honestly support them. Extensions should check `:psi.ui/capabilities` first, then find a matching action descriptor.

## 3. Action descriptor schema

Action descriptors are pure data maps with this shape:

```clojure
{:psi.ui.action/id :psi.ui.action/make-visible
 :psi.ui.action/capability :psi.ui.capability/make-visible
 :psi.ui.action/label "Show Psi UI"
 :psi.ui.action/description "Bring the active Psi UI to the foreground."
 :psi.ui.action/available? true
 :psi.ui.action/invocation {:psi.ui.invocation/kind :emacs-command
                            :psi.ui.invocation/command "psi-emacs-show-active"}}
```

Unavailable fields are required whenever `:psi.ui.action/available?` is false:

```clojure
{:psi.ui.action/available? false
 :psi.ui.action/unavailable-reason :psi.ui.unavailable.reason/no-attached-ui
 :psi.ui.action/unavailable-message "No attached UI adapter can make itself visible."}
```

`:psi.ui.action/unavailable-reason` values are machine-readable keywords in the `:psi.ui.unavailable.reason/...` namespace. The minimum vocabulary is:

- `:psi.ui.unavailable.reason/no-provider`
- `:psi.ui.unavailable.reason/no-attached-ui`
- `:psi.ui.unavailable.reason/unsupported-capability`
- `:psi.ui.unavailable.reason/provider-error`

Provider-error cases may also include bounded serialisable diagnostic text on the root UI result, but descriptors must not expose frontend objects or stacktrace data by default.

Keep descriptor keys fully namespaced so descriptors are self-describing in EQL results and docs.

## 4. Invocation representation

Use a tagged union in `:psi.ui.action/invocation`.

Supported invocation kinds for the design:

- `:emacs-command` — command name string to send to/evaluate in Emacs UI.
- `:ui-event` — adapter-neutral event/action payload to send back to the active UI adapter.
- `:bash-command` — shell command vector or string for tmux-style reveal actions.
- `:mutation` — Pathom mutation symbol plus params, if an existing mutation is the cleanest path.

Implementation may start with only `:emacs-command`. The schema must leave room for tmux or adapter-event invocation without changing the query attrs.

## 5. Invocation scope for this task

This task should implement queryability first and define the invocation descriptor contract. Side-effecting invocation is optional in this slice.

If generic invocation is not small, create a follow-up task for `psi.ui/invoke-action` or equivalent. Do not let invocation broaden this task into arbitrary UI mutation.

## 6. Emacs make-visible action

Emacs is the required supported frontend.

Existing Emacs entry points already use `pop-to-buffer` in `psi-emacs-start` / `psi-emacs-project`, and `psi-emacs--focus-input-area` focuses the prompt in the shown buffer. The implementation should add or reuse a small interactive command that:

1. locates the active/current Psi buffer,
2. calls `pop-to-buffer` on it,
3. calls `psi-emacs--focus-input-area`,
4. selects/focuses the frame/window as needed.

Proposed command name: `psi-emacs-show-active`.

The descriptor invocation can then be:

```clojure
{:psi.ui.invocation/kind :emacs-command
 :psi.ui.invocation/command "psi-emacs-show-active"}
```

## 7. TUI/console make-visible

Do not claim TUI/console support unless there is real runtime state identifying a tmux target and a safe command to reveal it.

Tmux support can be a future or optional path. If implemented, the descriptor should use `:bash-command` with a safe argv-style command, not an interpolated shell string, for example:

```clojure
{:psi.ui.invocation/kind :bash-command
 :psi.ui.invocation/argv ["tmux" "switch-client" "-t" session-name]}
```

For this task, TUI/console may return no `:psi.ui.capability/make-visible` and no make-visible action unless such target metadata already exists.

## 8. Unsupported states

Use both patterns:

- Absence from `:psi.ui/capabilities` means the extension should not expect that behaviour.
- `:psi.ui/make-visible-action` always returns a descriptor. When the active UI cannot currently perform the action, the descriptor has `:psi.ui.action/available? false`, a `:psi.ui.action/unavailable-reason` keyword from the `:psi.ui.unavailable.reason/...` namespace, and a bounded human-readable `:psi.ui.action/unavailable-message`.

This gives extensions a simple capability-driven path while preserving explanatory introspection through one stable action shape.

## 9. UI capabilities are derived on demand; requests use event subscription

Do not store UI capabilities/actions in root state. They are runtime-adapter facts that can always be queried on demand.

The core query surface should derive pure capability/action descriptors from the currently attached UI adapter/request channel. Requests to the UI should be delivered through a core-owned event that active UI adapters subscribe to. This avoids both stale advertised capability state and frontend executable handles in core state, while matching the existing adapter boundary: UI frontends already consume runtime/RPC events such as frontend action requests.

Recommended split:

1. Query path: resolver derives pure data describing currently available UI capabilities/actions.
2. Request path: extension/core submits a UI request event, for example a request for `:psi.ui.action/make-visible`.
3. Adapter path: the active UI subscribes to the event and decides how to execute it, e.g. Emacs runs `psi-emacs-show-active`, TUI uses a tmux command if it has target metadata.
4. Result path: optional acknowledgement/result event records accepted/completed/unsupported for diagnostics.

Root state should not contain an advertised capabilities/actions cache. If state is used, it should be limited to actual extension UI contribution state that already exists and optional in-flight/request-result diagnostics. The executable behaviour lives at the subscribing UI adapter boundary.

The important invariant is: capabilities are queried/derived fresh; core emits declarative UI requests; adapters execute requests if they currently support them.

## 10. Docs

Update extension-authoring docs in:

- `doc/extensions.md`
- `doc/extension-api.md`
- `doc/architecture.md`, replacing or qualifying the existing guidance that extensions branch on `:psi.agent-session/ui-type`

README only needs a pointer if the extension-facing section already references queryable runtime surfaces.
