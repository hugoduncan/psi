(ns psi.introspection.resolvers
  "Pathom3 resolvers exposing engine state and query graph to the EQL surface.

   Domain attributes:

   Engine / System
     :psi/engine-ctx            — an EngineContext (required input seed)
     :psi.engine/all-engines    — map of all registered engine snapshots
     :psi.engine/engine-count   — number of registered engines
     :psi.system/state          — current system state map
     :psi.system/mode           — current-mode keyword
     :psi.system/evolution-stage — evolution-stage keyword
     :psi.system/readiness      — map of all *-ready flags
     :psi.system/has-interface  — derived: engine + query ready
     :psi.system/has-substrate  — derived: engine ready
     :psi.system/has-memory-layer — derived: query + history + knowledge ready
     :psi.system/is-ai-complete — derived: all components ready

   Statechart transitions
     :psi.engine/transitions    — full vector of recorded state transitions
     :psi.engine/transition-count — number of recorded transitions
     :psi.engine/recent-transitions — last 20 transitions (newest first)

   Single-engine detail
     :psi.engine/id             — engine-id string (input seed)
     :psi.engine/status         — :engine-status keyword for that engine
     :psi.engine/active-states  — set of active state name strings
     :psi.engine/diagnostics    — full diagnostics map for that engine

   Query graph
     :psi.query-ctx             — a QueryContext (required input seed)
     :psi.graph/resolver-count  — number of registered resolvers
     :psi.graph/mutation-count  — number of registered mutations
     :psi.graph/resolver-syms   — set of registered resolver qualified symbols
     :psi.graph/mutation-syms   — set of registered mutation qualified symbols
     :psi.graph/env-built       — boolean, true if env has been compiled

   Agent-session startup bootstrap
     :psi/agent-session-ctx              — session context seed (optional)
     :psi.startup/bootstrap-summary      — bootstrap summary map
     :psi.startup/bootstrap-timestamp    — bootstrap finished timestamp
     :psi.startup/prompt-count           — prompts loaded at startup
     :psi.startup/skill-count            — skills loaded at startup
     :psi.startup/tool-count             — tools loaded at startup
     :psi.startup/extension-loaded-count — successful extension loads
     :psi.startup/extension-error-count  — failed extension loads
     :psi.startup/extension-errors       — [{:path :error}] failures
     :psi.startup/mutations              — mutation symbols used"
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.engine.core :as engine]
   [psi.introspection.graph :as graph]
   [psi.query.core :as query]
   [psi.query.registry :as registry]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Engine / System resolvers
;; ─────────────────────────────────────────────────────────────────────────────

(pco/defresolver engine-all-engines
  "Resolve :psi.engine/all-engines — snapshot map of every registered engine."
  [{:keys [psi/engine-ctx]}]
  {::pco/input  [:psi/engine-ctx]
   ::pco/output [:psi.engine/all-engines
                 :psi.engine/engine-count]}
  (let [engines (engine/get-all-engines-in engine-ctx)]
    {:psi.engine/all-engines  engines
     :psi.engine/engine-count (count engines)}))

(pco/defresolver engine-system-state
  "Resolve :psi.system/state and all derived system fields."
  [{:keys [psi/engine-ctx]}]
  {::pco/input  [:psi/engine-ctx]
   ::pco/output [:psi.system/state
                 :psi.system/mode
                 :psi.system/evolution-stage
                 :psi.system/readiness
                 :psi.system/has-interface
                 :psi.system/has-substrate
                 :psi.system/has-memory-layer
                 :psi.system/is-ai-complete]}
  (let [state (engine/get-system-state-in engine-ctx)]
    {:psi.system/state           state
     :psi.system/mode            (:current-mode state)
     :psi.system/evolution-stage (:evolution-stage state)
     :psi.system/readiness       (select-keys state
                                               [:engine-ready :query-ready :graph-ready
                                                :introspection-ready :history-ready
                                                :knowledge-ready :memory-ready])
     :psi.system/has-interface   (engine/system-has-interface-in? engine-ctx)
     :psi.system/has-substrate   (engine/system-has-substrate-in? engine-ctx)
     :psi.system/has-memory-layer (engine/system-has-memory-layer-in? engine-ctx)
     :psi.system/is-ai-complete  (engine/system-is-ai-complete-in? engine-ctx)}))

(pco/defresolver engine-transitions
  "Resolve :psi.engine/transitions — complete transition log."
  [{:keys [psi/engine-ctx]}]
  {::pco/input  [:psi/engine-ctx]
   ::pco/output [:psi.engine/transitions
                 :psi.engine/transition-count
                 :psi.engine/recent-transitions]}
  (let [all (engine/get-state-transitions-in engine-ctx)]
    {:psi.engine/transitions        all
     :psi.engine/transition-count   (count all)
     :psi.engine/recent-transitions (vec (take 20 (reverse all)))}))

(pco/defresolver engine-single-detail
  "Resolve detail for a single engine from :psi.engine/id."
  [{:keys [psi/engine-ctx psi.engine/id]}]
  {::pco/input  [:psi/engine-ctx :psi.engine/id]
   ::pco/output [:psi.engine/status
                 :psi.engine/active-states
                 :psi.engine/diagnostics]}
  (let [status (engine/engine-status-in engine-ctx id)
        diag   (engine/engine-diagnostics-in engine-ctx id)]
    {:psi.engine/status       (:engine-status status)
     :psi.engine/active-states (:active-states status)
     :psi.engine/diagnostics  diag}))

;; ─────────────────────────────────────────────────────────────────────────────
;; Query graph resolvers
;; ─────────────────────────────────────────────────────────────────────────────

(defn- operation-metadata-in
  "Collect normalized resolver/mutation metadata for the current query registry."
  [query-ctx]
  {:resolver-ops (mapv #(graph/operation->metadata :resolver %)
                       (registry/all-resolvers-in (:reg query-ctx)))
   :mutation-ops (mapv #(graph/operation->metadata :mutation %)
                       (registry/all-mutations-in (:reg query-ctx)))})

(defn- capability-graph-in
  "Derive Step 7 capability graph entities from current query registry."
  [query-ctx]
  (graph/derive-capability-graph (operation-metadata-in query-ctx)))

(pco/defresolver query-graph-summary
  "Resolve query graph statistics and Step 7 capability graph attrs from :psi/query-ctx."
  [{:keys [psi/query-ctx]}]
  {::pco/input  [:psi/query-ctx]
   ::pco/output [:psi.graph/resolver-count
                 :psi.graph/mutation-count
                 :psi.graph/resolver-syms
                 :psi.graph/mutation-syms
                 :psi.graph/env-built
                 :psi.graph/nodes
                 :psi.graph/edges
                 :psi.graph/capabilities
                 :psi.graph/domain-coverage]}
  (let [summary (query/graph-summary-in query-ctx)
        cgraph  (capability-graph-in query-ctx)]
    {:psi.graph/resolver-count (:resolver-count summary)
     :psi.graph/mutation-count (:mutation-count summary)
     :psi.graph/resolver-syms  (:resolvers summary)
     :psi.graph/mutation-syms  (:mutations summary)
     :psi.graph/env-built      (:env-built? summary)
     :psi.graph/nodes          (:nodes cgraph)
     :psi.graph/edges          (:edges cgraph)
     :psi.graph/capabilities   (:capabilities cgraph)
     :psi.graph/domain-coverage (:domain-coverage cgraph)}))

;; ─────────────────────────────────────────────────────────────────────────────
;; All resolvers
;; ─────────────────────────────────────────────────────────────────────────────

(pco/defresolver startup-bootstrap-summary
  "Resolve startup bootstrap summary from agent-session context.
   Safe when no bootstrap has run yet: returns nil/zero defaults."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.startup/bootstrap-summary
                 :psi.startup/bootstrap-timestamp
                 :psi.startup/prompt-count
                 :psi.startup/skill-count
                 :psi.startup/tool-count
                 :psi.startup/extension-loaded-count
                 :psi.startup/extension-error-count
                 :psi.startup/extension-errors
                 :psi.startup/mutations]}
  (let [summary (:startup-bootstrap @(:session-data-atom agent-session-ctx))]
    {:psi.startup/bootstrap-summary      summary
     :psi.startup/bootstrap-timestamp    (:timestamp summary)
     :psi.startup/prompt-count           (:prompt-count summary 0)
     :psi.startup/skill-count            (:skill-count summary 0)
     :psi.startup/tool-count             (:tool-count summary 0)
     :psi.startup/extension-loaded-count (:extension-loaded-count summary 0)
     :psi.startup/extension-error-count  (:extension-error-count summary 0)
     :psi.startup/extension-errors       (:extension-errors summary [])
     :psi.startup/mutations              (:mutations summary [])}))

(def all-resolvers
  [engine-all-engines
   engine-system-state
   engine-transitions
   engine-single-detail
   query-graph-summary
   startup-bootstrap-summary])
