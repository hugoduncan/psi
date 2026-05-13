(ns psi.ai.core
  "Core AI provider implementation.

   Integrates with the engine and query components:
   - Registers Pathom resolvers so AI capabilities are queryable via EQL.
   - Notifies the engine when the AI component becomes ready.
   - Keeps direct core.async usage out of this namespace entirely.
   "
  (:require [psi.ai.conversation :as conversation]
            [psi.ai.streaming :as streaming]
            [psi.ai.models :as models]
            [psi.ai.schemas :as schemas]
            [psi.ai.model-registry :as model-registry]
            [psi.ai.providers.anthropic :as anthropic]
            [psi.ai.providers.openai :as openai]
            [psi.engine.core :as engine]
            [psi.query.core :as query]
            [com.wsscode.pathom3.connect.operation :as pco]))

;; ───────────────────────────────────────────────────────────────────────────
;; Provider registry
;; ───────────────────────────────────────────────────────────────────────────

(defonce ^:private provider-registry
  (atom {:anthropic               anthropic/provider
         :openai                  openai/provider
         ;; API-keyed entries: custom providers that declare an api protocol
         ;; resolve through here when no exact provider match exists.
         :anthropic-messages      anthropic/provider
         :openai-completions      openai/provider
         :openai-codex-responses  openai/provider}))

;; ───────────────────────────────────────────────────────────────────────────
;; Isolated AI context (Nullable pattern)
;; ───────────────────────────────────────────────────────────────────────────

(defn create-context
  "Create an isolated AI context with its own provider registry atom.

  Options:
    :providers — map of provider-key → provider-impl to seed the registry
                 (default: empty map; pass {:anthropic stub-provider} for tests)

  Use in tests to stream responses through a stub provider without touching
  the global provider-registry."
  ([] (create-context {}))
  ([{:keys [providers] :or {providers {}}}]
   {:provider-registry (atom providers)}))

;; ───────────────────────────────────────────────────────────────────────────
;; Pathom resolvers — AI capabilities exposed via EQL
;; ───────────────────────────────────────────────────────────────────────────

(pco/defresolver ai-model-resolver
  "Resolve a model map by qualified key, e.g. :ai.model/key → model map."
  [{model-key :ai.model/key}]
  {::pco/input  [:ai.model/key]
   ::pco/output [:ai.model/data]}
  {:ai.model/data (models/get-model model-key)})

(pco/defresolver ai-model-list-resolver
  "Resolve the full model catalogue (built-in + custom providers)."
  [_]
  {::pco/output [:ai/all-models]}
  {:ai/all-models (model-registry/all-models-by-key)})

(pco/defresolver ai-provider-models-resolver
  "Resolve models for a given provider key."
  [{provider :ai/provider}]
  {::pco/input  [:ai/provider]
   ::pco/output [:ai/provider-models]}
  {:ai/provider-models (model-registry/models-for-provider provider)})

(pco/defresolver ai-provider-registry-resolver
  "Resolve the set of registered provider keys."
  [_]
  {::pco/output [:ai/registered-providers]}
  {:ai/registered-providers (set (keys @provider-registry))})

(pco/defresolver model-registry-load-error-resolver
  "Resolve the current model-registry load error string, or nil if clean."
  [_]
  {::pco/output [:psi.model-registry/load-error]}
  {:psi.model-registry/load-error (model-registry/get-load-error)})

;; ───────────────────────────────────────────────────────────────────────────
;; Register resolvers with the query component
;; ───────────────────────────────────────────────────────────────────────────

(def all-resolvers
  "All AI resolvers. Used by introspection to avoid duplicating the list."
  [ai-model-resolver
   ai-model-list-resolver
   ai-provider-models-resolver
   ai-provider-registry-resolver
   model-registry-load-error-resolver])

(defn register-resolvers-in!
  "Register all AI resolvers into an isolated `qctx` query context and rebuild its env."
  [qctx]
  (run! #(query/register-resolver-in! qctx %) all-resolvers)
  (query/rebuild-env-in! qctx))

(defn register-resolvers!
  "Register all AI resolvers into the global query graph and rebuild the environment."
  []
  (run! query/register-resolver! all-resolvers)
  (query/rebuild-env!))

;; ───────────────────────────────────────────────────────────────────────────
;; Initialisation
;; ───────────────────────────────────────────────────────────────────────────

(defn init!
  "Initialise the AI component: register resolvers and mark engine ready."
  []
  (register-resolvers!)
  (engine/update-system-component! :engine-ready true)
  :ok)

;; ───────────────────────────────────────────────────────────────────────────
;; Public API — conversations
;; ───────────────────────────────────────────────────────────────────────────

(defn create-conversation
  "Create a new conversation.

   Accepts either:
   - a system prompt string
   - an options map supported by psi.ai.conversation/create"
  ([system-prompt-or-options]
   (conversation/create system-prompt-or-options))
  ([]
   (conversation/create nil)))

(defn send-message
  "Add a user message to a conversation."
  [conversation content]
  {:pre [(schemas/valid? schemas/Conversation conversation)
         (string? content)]}
  (conversation/add-user-message conversation content))

(defn add-tool
  "Add a tool to conversation."
  [conversation tool]
  (conversation/add-tool conversation tool))

(defn get-conversation-cost
  "Get total cost for conversation."
  [conversation]
  (conversation/total-cost conversation))

(defn get-conversation-usage
  "Get total token usage for conversation."
  [conversation]
  (conversation/total-usage conversation))

;; ───────────────────────────────────────────────────────────────────────────
;; Streaming — context-aware core (used by public API and tests)
;; ───────────────────────────────────────────────────────────────────────────

(defn- context-provider-registry
  [ctx]
  (:provider-registry ctx))

(defn- resolve-provider
  "Look up the provider impl for `model` from `registry-atom`.

   Resolution order:
   1. Exact match on (:provider model) — built-in and explicitly registered providers
   2. Fallback to (:api model) — custom providers inherit the transport impl for their wire protocol

   Throws if neither matches."
  [registry-atom model]
  (let [reg           @registry-atom
        provider-impl (or (get reg (:provider model))
                          (get reg (:api model)))]
    (when-not provider-impl
      (throw (ex-info "Unknown provider"
                      {:provider (:provider model)
                       :api      (:api model)})))
    provider-impl))

(defn- provider-stream
  [ctx model]
  (resolve-provider (context-provider-registry ctx) model))

(defn- provider-execute
  [ctx model]
  (let [provider-impl (resolve-provider (context-provider-registry ctx) model)]
    (or (:execute provider-impl)
        (throw (ex-info "Provider does not support non-streaming execution"
                        {:provider (:provider model)
                         :api (:api model)})))))

(defn- stream-with
  [stream-fn ctx conversation model options & [consume-fn]]
  (let [provider-impl (provider-stream ctx model)]
    (if consume-fn
      (stream-fn provider-impl conversation model options consume-fn)
      (stream-fn provider-impl conversation model options))))

(defn stream-response-in
  "Stream assistant response in an isolated `ctx`, calling `consume-fn` for each event.

  Returns {:future ... :session atom}.  The provider is resolved from `ctx`'s
  provider registry, so tests can supply a stub without touching global state."
  [ctx conversation model options consume-fn]
  {:pre [(schemas/valid? schemas/Conversation conversation)
         (schemas/valid? schemas/Model model)
         (schemas/valid? schemas/StreamOptions options)]}
  (stream-with streaming/stream-response
               ctx
               conversation
               model
               options
               consume-fn))

(defn stream-response-seq-in
  "Stream assistant response as a lazy sequence of events in an isolated `ctx`.

  Returns {:events lazy-seq :session atom}."
  [ctx conversation model options]
  {:pre [(schemas/valid? schemas/Conversation conversation)
         (schemas/valid? schemas/Model model)
         (schemas/valid? schemas/StreamOptions options)]}
  (stream-with streaming/stream-response-seq
               ctx
               conversation
               model
               options))

(defn execute-response-in
  "Execute assistant response in an isolated `ctx` without streaming.

  Returns either {:assistant-message ... :logprobs ...} on success or
  {:type :error ...} on provider/runtime failure."
  [ctx conversation model options]
  {:pre [(schemas/valid? schemas/Conversation conversation)
         (schemas/valid? schemas/Model model)
         (schemas/valid? schemas/StreamOptions options)]}
  ((provider-execute ctx model) conversation model options))

;; ───────────────────────────────────────────────────────────────────────────
;; Public API — streaming (global wrappers)
;; ───────────────────────────────────────────────────────────────────────────

(defn- global-ctx
  "Build a context map backed by the global provider registry."
  []
  {:provider-registry provider-registry})

(defn stream-response
  "Stream assistant response using `consume-fn` callback.

   Runs the provider on a background thread; `consume-fn` is called for
   every event.  Returns {:future ... :session atom}.

   See `psi.ai.streaming/stream-response` for event shapes."
  [conversation model options consume-fn]
  (stream-response-in (global-ctx) conversation model options consume-fn))

(defn stream-response-seq
  "Stream assistant response as a lazy sequence of events.

   Returns {:events lazy-seq :session atom}.
   Blocks per element until the next event arrives.

   See `psi.ai.streaming/stream-response-seq` for event shapes."
  [conversation model options]
  (stream-response-seq-in (global-ctx) conversation model options))

(defn execute-response
  "Execute assistant response without streaming.

   Returns either {:assistant-message ... :logprobs ...} on success or
   {:type :error ...} on provider/runtime failure."
  [conversation model options]
  (execute-response-in (global-ctx) conversation model options))

;; ───────────────────────────────────────────────────────────────────────────
;; Public API — models
;; ───────────────────────────────────────────────────────────────────────────

(defn list-models
  "List available models for provider."
  [provider]
  (models/list-for-provider provider))

;; ───────────────────────────────────────────────────────────────────────────
;; Provider registration
;; ───────────────────────────────────────────────────────────────────────────

(defn register-provider!
  "Register a new provider implementation."
  [provider-key provider-impl]
  (swap! provider-registry assoc provider-key provider-impl))

(defn get-provider
  "Get provider implementation."
  [provider-key]
  (get @provider-registry provider-key))
