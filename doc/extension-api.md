# Extension API

Extension-facing runtime/query surfaces and operational notes.

## Extension system

Extensions are Clojure namespaces loaded at runtime. Each extension
receives an API map with:

- Tool registration (`register-tool!`)
- EQL query access (`query`)
- UI hooks (`dialogs`, `widgets`, `status`, `notifications`, `renderers`)

Extensions can also call shared library namespaces directly when they need
common deterministic behavior that should not be reimplemented per extension.
Current example: `psi.ai.model-selection` for role/policy-based model choice.

## Queryable UI capabilities and actions

Use EQL `:psi.ui/...` attrs when an extension needs to discover UI behaviour.
The `:ui` API contributes dialogs/widgets/status/notifications/renderers to an
attached UI; it is not the discovery contract for whether UI actions can be
performed.

Recommended query:

```clojure
((:query api) [:psi.ui/type
               :psi.ui/available?
               :psi.ui/capabilities
               :psi.ui/actions
               :psi.ui/make-visible-action
               :psi.ui/diagnostic])
```

Core UI capability/action data is runtime-scoped and derived on demand from the
active UI adapter.  Extensions should branch on capability keywords and action
descriptor availability, not on concrete UI type:

```clojure
(let [ui-state ((:query api) [:psi.ui/capabilities
                             :psi.ui/actions
                             :psi.ui/make-visible-action])
      make-visible (:psi.ui/make-visible-action ui-state)]
  (when (and (contains? (set (:psi.ui/capabilities ui-state))
                        :psi.ui.capability/make-visible)
             (:psi.ui.action/available? make-visible))
    ;; Task 190 exposes descriptor discovery only. Until
    ;; 191-ui-action-invocation lands, callers may present or record this
    ;; descriptor, but must not submit it as an executable UI request.
    ;; Do not call Emacs/TUI/frontend namespaces directly.
    make-visible))
```

UI capability attrs:

- `:psi.ui/type` — active UI adapter identity for diagnostics/compatibility.
- `:psi.ui/available?` — true when a concrete UI adapter is attached.
- `:psi.ui/capabilities` — capability keywords such as `:psi.ui.capability/make-visible`.
- `:psi.ui/actions` — currently available pure-data action descriptors.
- `:psi.ui/make-visible-action` — stable make-visible descriptor; unavailable when unsupported/headless/error.
- `:psi.ui/diagnostic` — optional bounded provider-error troubleshooting text.

A supported make-visible descriptor is pure serialisable data:

```clojure
{:psi.ui.action/id :psi.ui.action/make-visible
 :psi.ui.action/capability :psi.ui.capability/make-visible
 :psi.ui.action/label "Show Psi UI"
 :psi.ui.action/description "Bring the active Psi UI to the foreground."
 :psi.ui.action/available? true
 :psi.ui.action/invocation {:psi.ui.invocation/kind :emacs-command
                            :psi.ui.invocation/command "psi-emacs-show-active"}}
```

`:psi.ui/actions` contains only currently available actions.  The convenience
attr `:psi.ui/make-visible-action` always returns a descriptor-shaped value; in
unavailable cases it has `:psi.ui.action/available? false`, a machine-readable
`:psi.ui.action/unavailable-reason`, and a human-readable
`:psi.ui.action/unavailable-message`:

```clojure
{:psi.ui.action/id :psi.ui.action/make-visible
 :psi.ui.action/capability :psi.ui.capability/make-visible
 :psi.ui.action/label "Show Psi UI"
 :psi.ui.action/description "Bring the active Psi UI to the foreground."
 :psi.ui.action/available? false
 :psi.ui.action/unavailable-reason :psi.ui.unavailable.reason/no-attached-ui
 :psi.ui.action/unavailable-message "No attached UI adapter can make itself visible."}
```

Known unavailable reasons:

- `:psi.ui.unavailable.reason/no-provider`
- `:psi.ui.unavailable.reason/no-attached-ui`
- `:psi.ui.unavailable.reason/unsupported-capability`
- `:psi.ui.unavailable.reason/provider-error`

Task 190 is query/descriptor-only: it does not implement side-effecting
submission of a descriptor through the core UI action request path. The planned
request path is owned by `191-ui-action-invocation`; until that lands,
extensions may inspect, display, or store descriptor data, but must not assume a
supported API exists to execute `:psi.ui.action/invocation` values.

Legacy UI-type surfaces remain supported only as diagnostics/compatibility data:
`:ui-type`, `:psi.agent-session/ui-type`, and `:psi.ui/type`.  Do not use them
as the normative extension-authoring contract for invokable UI behaviour.

## Workflow public-data display convention

For workflow-backed extensions, prefer projecting reusable display/read-model
data from `:public-data-fn` rather than formatting separately in every widget
or command consumer.

Preferred display-map keys:
- `:top-line`
- `:detail-line`
- `:question-lines`
- `:action-line`

Store that map under an extension-specific public key such as `:run/display`,
`:chain/display`, or `:agent/display`, then let consumers merge/render that
public surface via shared helpers such as `psi.agent-session.workflow.display`.

Preferred helper usage:
- widget/UI consumers: `psi.agent-session.workflow.display/merged-display` + `display-lines`
- CLI/list consumers: `psi.agent-session.workflow.display/text-lines` over the rendered workflow lines

## Memory durability operations

Inspect provider selection/fallback + failure telemetry via EQL:

```clojure
[:psi.memory.store/active-provider-id
 :psi.memory.store/selection
 :psi.memory.store/health
 :psi.memory.store/active-provider-telemetry
 :psi.memory.store/last-failure
 :psi.memory.store/providers]
```

Telemetry fields (per provider map):
- `:telemetry :write-count`
- `:telemetry :read-count`
- `:telemetry :failure-count`
- `:telemetry :last-error`

Operational notes:
- Fallback decisions are visible at `:psi.memory.store/selection` (`:used-fallback`, `:reason`).
- Graph history retention is fixed-window (`snapshots`, `deltas`): newest N kept, oldest trimmed.
- The built-in memory store is in-memory only.

## Session-targeted helpers

When an extension needs to inspect or mutate a specific session rather than the
ambient runtime session, prefer explicit session-targeted helpers:

- `(:query-session api) session-id eql-query`
- `(:mutate-session api) session-id op-sym params`

This is the recommended pattern for delayed/background extension work. It keeps
session routing explicit and avoids relying on implicit adapter focus.

For slash-command handlers invoked through the shared command dispatcher, implicit
`(:query api)` / `(:mutate api)` calls are now rebound to the active invoking
session during handler execution. That implicit routing is intended for immediate
command handling only; cross-session and deferred/background work should still use
`query-session` / `mutate-session` explicitly.

Example:

```clojure
(let [model-ctx ((:query-session api) source-session-id
                 [:psi.agent-session/model-provider
                  :psi.agent-session/model-id])]
  ((:mutate-session api) child-session-id 'psi.extension/run-agent-loop-in-session
   {:prompt "..."
    :model  {:provider (keyword (:psi.agent-session/model-provider model-ctx))
             :id       (:psi.agent-session/model-id model-ctx)}}))
```

## Mid-conversation system-message injection

Extensions can append a provider-safe mid-conversation system instruction to the
active session with:

```clojure
((:inject-mid-system-message api) "Use the updated budget for the next reply")
((:inject-mid-system-message api)
 "Prefer concise answers for the next reply"
 {:source :my-extension})
```

The helper invokes `psi.extension/inject-mid-system-message` and returns a compact
result:

```clojure
{:ok true}
{:ok false :error :capability-not-supported}
{:ok false :error :invalid-placement :reason :no-preceding-user}
{:ok false :error :invalid-placement :reason :after-assistant}
{:ok false :error :invalid-placement :reason :pending-mid-system}
```

Psi exposes the Anthropic-compatible placement subset for all providers: a
mid-system message may be injected only after the latest conversational user turn
and before the assistant response being generated. Non-conversational journal
metadata after that user turn is ignored for placement, so the provider message
sequence still becomes `user → system`.

Capability can be checked before injection with:

```clojure
((:query api) [:psi.agent-session/model-supports-mid-system-messages])
```

Support is true for Claude Opus 4.8, Claude Fable 5, Claude Sonnet 5, and for OpenAI chat-completions models
(including runtime/custom maps inferred from `:provider :openai` and
`:api :openai-completions`). Codex/responses models and older Anthropic models
are reported unsupported. When `:source` is omitted, the mutation infers
provenance from extension path metadata and falls back to `:extension`.

## Child-session helper runs

Extensions can create targeted helper/background child sessions with:

- `psi.extension/create-child-session`
- `psi.extension/run-agent-loop-in-session`

`create-child-session` accepts the existing child runtime controls such as:

- `:session-name`
- `:system-prompt`
- `:tool-defs`
- `:thinking-level`
- `:preloaded-messages`
- `:cache-breakpoints`

It also now accepts prompt-shaping controls for reduced helper runs:

- `:prompt-component-selection`
  - `:agents-md?` — include discovered `AGENTS.md` / context-file content when true
  - `:extension-prompt-contributions` — allowlist of extension prompt-contribution owners; `[]` means none
  - `:tool-names` — caller-declared tool selection metadata for the helper run
  - `:skill-names` — caller-declared skill selection metadata for the helper run
  - `:components` — standard prompt-component set, currently including `:preamble`, `:context-files`, `:skills`, `:runtime-metadata`
- `:system-prompt`
  - may be used as a minimal caller-supplied helper instruction when the selected standard prompt components are disabled

Behavior note:
- when no prompt-component controls are supplied, existing child-session behavior is unchanged
- reduced helper runs should disable capabilities as well as prompt text when the helper does not need them

Current reference example:
- `auto-session-name` creates a helper child with no AGENTS/context prompt content, no extension prompt contributions, no tool defs, no skill prelude content, and one minimal naming-specific system prompt

## Shared model selection for extensions

Extensions that need to choose a model for helper/background work should prefer
`psi.ai.model-selection` over per-extension fallback chains.

Current shared entrypoint:

- `psi.ai.model-selection/resolve-selection`

Typical usage:

```clojure
(let [model-ctx ((:query-session api) source-session-id
                 [:psi.agent-session/model-provider
                  :psi.agent-session/model-id])
      request   {:mode :resolve
                 :required [{:criterion :supports-text
                             :match :true}]
                 :strong-preferences [{:criterion :input-cost
                                       :prefer :lower}]
                 :weak-preferences [{:criterion :same-provider-as-session
                                     :prefer :context-match}]
                 :context {:session-model {:provider (some-> (:psi.agent-session/model-provider model-ctx)
                                                             keyword)
                                           :id       (:psi.agent-session/model-id model-ctx)}}}
      selection (psi.ai.model-selection/resolve-selection
                 {:request request})]
  (when (= :ok (:outcome selection))
    (:candidate selection)))
```

Current request shape:

```clojure
{:request {:mode :resolve | :explicit | :inherit-session
           :role keyword?
           :required [...]
           :strong-preferences [...]
           :weak-preferences [...]
           :context {...}
           :model {:provider :openai :id "gpt-5"}}}
```

Current result shape:

```clojure
{:outcome :ok | :no-winner
 :candidate candidate?
 :ambiguous? boolean?
 :reason keyword?
 :effective-request {...}
 :filtering {...}
 :ranking {...}
 :trace {:short {...}
         :full {...}}}
```

Guidance:
- extensions do not need a built-in role to use the resolver
- an extension may submit a fully explicit request of its own
- if an extension wants reuse, it may define its own local preset/request builder rather than requiring a core-defined role
- pass source-session context explicitly when affinity matters
- treat `:no-winner` as a first-class outcome
- use `:trace` when an extension needs explainability/debug output
- do not silently violate required constraints with caller-local fallbacks
