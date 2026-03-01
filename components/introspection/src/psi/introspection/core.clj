(ns psi.introspection.core
  "Introspection component — makes the Psi system queryable via EQL.

   The introspection component bridges the engine (statechart state,
   transitions, system readiness), the query graph (registered
   resolvers/mutations), and optionally the agent-session into a uniform
   EQL query surface.

   Usage pattern — isolated (tests):

     (let [ctx (introspection/create-context)]
       (engine/bootstrap-system-in! (:engine-ctx ctx))
       (introspection/register-resolvers-in! ctx)
       (introspection/query-system-state-in ctx))

   With agent-session:

     (let [session-ctx (session/create-context)
           ctx         (introspection/create-context {:agent-session-ctx session-ctx})]
       (introspection/register-resolvers-in! ctx)
       (introspection/query-agent-session-in ctx [:psi.agent-session/phase]))

   Usage pattern — global (production):

     (introspection/register-resolvers!)        ; once at startup
     (introspection/query-system-state)         ; thereafter

   Public API:
     register-resolvers!           — register engine + agent-session resolvers into
                                     the global query graph (call once at startup)
     register-resolvers-in!        — same but for an isolated IntrospectionContext
     create-context                — create isolated context (Nullable pattern)
     query-system-state-in         — system state + derived properties (ctx)
     query-transitions-in          — full transition log (ctx)
     query-recent-transitions-in   — last 20 transitions (ctx)
     query-graph-summary-in        — query graph statistics (ctx)
     query-all-engines-in          — all engines + count (ctx)
     query-engine-detail-in        — single-engine diagnostics (ctx)
     query-agent-session-in        — EQL query over :psi.agent-session/* (ctx, q)"
  (:require
   [psi.introspection.graph :as graph]
   [psi.introspection.resolvers :as resolvers]
   [psi.engine.core :as engine]
   [psi.query.core :as query]
   [psi.query.registry :as registry]
   [psi.ai.core :as ai]
   [psi.history.resolvers :as history-resolvers]
   [psi.memory.core :as memory]
   [psi.memory.resolvers :as memory-resolvers]
   [psi.agent-session.core :as agent-session]
   [psi.agent-session.resolvers :as as-resolvers]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Global registration
;; ─────────────────────────────────────────────────────────────────────────────

(defn register-resolvers!
  "Register startup domains into the global query graph and rebuild once.

   Domains:
   - AI resolvers
   - History resolvers
   - Introspection resolvers
   - Memory resolvers
   - Agent-session resolvers + mutations"
  []
  ;; AI resolvers (register directly to avoid per-domain env rebuilds)
  (query/register-resolver! ai/ai-model-resolver)
  (query/register-resolver! ai/ai-model-list-resolver)
  (query/register-resolver! ai/ai-provider-models-resolver)
  (query/register-resolver! ai/ai-provider-registry-resolver)

  ;; History + Introspection + Memory + Agent-session resolvers
  (doseq [r history-resolvers/all-resolvers]
    (query/register-resolver! r))
  (doseq [r resolvers/all-resolvers]
    (query/register-resolver! r))
  (doseq [r memory-resolvers/all-resolvers]
    (query/register-resolver! r))
  (doseq [r as-resolvers/all-resolvers]
    (query/register-resolver! r))

  ;; Agent-session mutations
  (doseq [m agent-session/all-mutations]
    (query/register-mutation! m))

  ;; Single env rebuild after all operations are registered.
  (query/rebuild-env!))

;; ─────────────────────────────────────────────────────────────────────────────
;; Isolated introspection context (Nullable pattern)
;; ─────────────────────────────────────────────────────────────────────────────

(defrecord IntrospectionContext [engine-ctx query-ctx memory-ctx agent-session-ctx])

(defn create-context
  "Create an isolated introspection context with its own engine and query atoms.
   Use in tests (pass instead of global context) to avoid shared state.

   Options (map, all optional):
     :engine-ctx        — an isolated engine context
                          (default: fresh from engine/create-context)
     :query-ctx         — an isolated query context
                          (default: fresh from query/create-query-context)
     :memory-ctx        — a memory context to expose via EQL
                          (default: fresh from memory/create-context)
     :agent-session-ctx — an agent-session context to expose via EQL
                          (default: nil — agent-session resolvers not registered)"
  ([]
   (create-context {}))
  ([{:keys [engine-ctx query-ctx memory-ctx agent-session-ctx]}]
   (->IntrospectionContext
    (or engine-ctx (engine/create-context))
    (or query-ctx (query/create-query-context))
    (or memory-ctx (memory/create-context))
    agent-session-ctx)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Context-aware helpers
;; ─────────────────────────────────────────────────────────────────────────────

(defn reconcile-graph-readiness-in!
  "Derive Step 7 graph readiness from computed graph shape and update engine state.

   Ready gate:
   - node-count > 0
   - edge-count > 0

   Stage semantics:
   - ready => :integrating
   - not ready => :developing"
  [ctx]
  (let [qctx    (:query-ctx ctx)
        ectx    (:engine-ctx ctx)
        cgraph  (graph/derive-capability-graph
                 {:resolver-ops (mapv #(graph/operation->metadata :resolver %)
                                      (registry/all-resolvers-in (:reg qctx)))
                  :mutation-ops (mapv #(graph/operation->metadata :mutation %)
                                      (registry/all-mutations-in (:reg qctx)))})
        ready?  (and (pos? (count (:nodes cgraph)))
                     (pos? (count (:edges cgraph))))
        stage   (if ready? :integrating :developing)]
    (engine/update-system-component-in! ectx :query-ready true)
    (engine/update-system-component-in! ectx :graph-ready ready?)
    (engine/set-evolution-stage-in! ectx stage)
    {:graph-ready ready?
     :evolution-stage stage
     :node-count (count (:nodes cgraph))
     :edge-count (count (:edges cgraph))}))

(defn register-resolvers-in!
  "Register startup domains into `ctx`'s query context and rebuild once.

   Domains:
   - AI resolvers
   - History resolvers
   - Introspection resolvers
   - Memory resolvers
   - Agent-session resolvers + mutations (when :agent-session-ctx is present)

   If `ctx` carries an :agent-session-ctx, agent-session resolvers + mutations
   are also registered so :psi.agent-session/* and mutation-backed workflows are
   queryable/executable.

   Also derives and applies runtime Step 7 readiness:
   - :graph-ready true when graph has nodes and edges, else false
   - :evolution-stage set to :integrating when ready, else :developing"
  [ctx]
  (let [qctx (:query-ctx ctx)]
    ;; AI resolvers
    (query/register-resolver-in! qctx ai/ai-model-resolver)
    (query/register-resolver-in! qctx ai/ai-model-list-resolver)
    (query/register-resolver-in! qctx ai/ai-provider-models-resolver)
    (query/register-resolver-in! qctx ai/ai-provider-registry-resolver)

    ;; History + Introspection + Memory resolvers
    (doseq [r history-resolvers/all-resolvers]
      (query/register-resolver-in! qctx r))
    (doseq [r resolvers/all-resolvers]
      (query/register-resolver-in! qctx r))
    (memory/register-resolvers-in! qctx false)

    (when (:agent-session-ctx ctx)
      ;; Pass rebuild?=false — we rebuild once below after all operations are in.
      (agent-session/register-resolvers-in! qctx false)
      (agent-session/register-mutations-in! qctx false))

    ;; Single env rebuild after all operations are registered.
    (query/rebuild-env-in! qctx)
    (reconcile-graph-readiness-in! ctx)))

(defn query-system-state-in
  "Return system state + derived properties via EQL using `ctx`."
  [ctx]
  (let [{:keys [engine-ctx query-ctx]} ctx]
    (query/query-in query-ctx {:psi/engine-ctx engine-ctx}
                    [:psi.system/state
                     :psi.system/mode
                     :psi.system/evolution-stage
                     :psi.system/readiness
                     :psi.system/has-interface
                     :psi.system/has-substrate
                     :psi.system/has-memory-layer
                     :psi.system/is-ai-complete])))

(defn query-transitions-in
  "Return the full transition log via EQL using `ctx`."
  [ctx]
  (let [{:keys [engine-ctx query-ctx]} ctx]
    (query/query-in query-ctx {:psi/engine-ctx engine-ctx}
                    [:psi.engine/transitions
                     :psi.engine/transition-count])))

(defn query-recent-transitions-in
  "Return the last 20 transitions (newest-first) via EQL using `ctx`."
  [ctx]
  (let [{:keys [engine-ctx query-ctx]} ctx]
    (query/query-in query-ctx {:psi/engine-ctx engine-ctx}
                    [:psi.engine/recent-transitions])))

(defn query-graph-summary-in
  "Return query graph statistics and Step 7 capability graph attrs via EQL using `ctx`."
  [ctx]
  (let [{:keys [query-ctx]} ctx]
    (query/query-in query-ctx {:psi/query-ctx query-ctx}
                    [:psi.graph/resolver-count
                     :psi.graph/mutation-count
                     :psi.graph/resolver-syms
                     :psi.graph/mutation-syms
                     :psi.graph/env-built
                     :psi.graph/nodes
                     :psi.graph/edges
                     :psi.graph/capabilities
                     :psi.graph/domain-coverage])))

(defn query-all-engines-in
  "Return all registered engines and count via EQL using `ctx`."
  [ctx]
  (let [{:keys [engine-ctx query-ctx]} ctx]
    (query/query-in query-ctx {:psi/engine-ctx engine-ctx}
                    [:psi.engine/all-engines
                     :psi.engine/engine-count])))

(defn query-engine-detail-in
  "Return diagnostics for a single engine by `engine-id` using `ctx`."
  [ctx engine-id]
  (let [{:keys [engine-ctx query-ctx]} ctx]
    (query/query-in query-ctx {:psi/engine-ctx engine-ctx
                               :psi.engine/id  engine-id}
                    [:psi.engine/status
                     :psi.engine/active-states
                     :psi.engine/diagnostics])))

(defn query-agent-session-in
  "Run EQL query `q` over the live graph using `ctx`.
   Requires ctx to have been created with an :agent-session-ctx option.
   Also seeds :psi/memory-ctx so :psi.memory/* attrs are queryable in-session."
  [ctx q]
  (let [{:keys [agent-session-ctx memory-ctx query-ctx]} ctx]
    (when-not agent-session-ctx
      (throw (ex-info "No :agent-session-ctx in introspection context" {})))
    (query/query-in query-ctx {:psi/agent-session-ctx agent-session-ctx
                               :psi/memory-ctx memory-ctx}
                    q)))
