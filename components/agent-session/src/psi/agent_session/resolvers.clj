(ns psi.agent-session.resolvers
  "Pathom3 EQL resolvers for the agent-session component.

   Attribute namespace: :psi.agent-session/
   Seed key:           :psi/agent-session-ctx   — the session context map

   All resolvers receive the session context via :psi/agent-session-ctx
   in the Pathom entity map and delegate to pure read fns.

   Resolvers are organized into domain-specific sub-namespaces:
   - resolvers.session     — core session state, model, usage, git, startup, auth
   - resolvers.extensions  — extension registry, workflows, agent chains, UI state
   - resolvers.telemetry   — tool lifecycle, provider captures, stats, journal, errors
   - resolvers.discovery   — prompt templates, skills, tools, session listing

   This namespace assembles the full resolver surface, provides the graph bridge
   resolver, and owns the Pathom env + query-in entry point."
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [psi.graph.analysis :as graph]
   [psi.history.resolvers :as history-resolvers]
   [psi.engine.core :as engine]
   [psi.memory.core :as memory]
   [psi.memory.resolvers :as memory-resolvers]
   [psi.query.registry :as registry]
   [psi.recursion.core :as recursion]
   [psi.recursion.resolvers :as recursion-resolvers]
   [psi.agent-session.resolvers.session :as session-resolvers]
   [psi.agent-session.resolvers.extensions :as extension-resolvers]
   [psi.agent-session.resolvers.project-nrepl :as project-nrepl-resolvers]
   [psi.agent-session.resolvers.services :as service-resolvers]
   [psi.agent-session.resolvers.telemetry :as telemetry-resolvers]
   [psi.agent-session.resolvers.discovery :as discovery-resolvers]
   [psi.agent-session.session-state :as ss]))

;; ── Query graph bridge ──────────────────────────────────
;;
;; Must live here because it references `all-resolvers` (the assembled surface).

(declare all-resolvers)

(defn session-resolver-surface
  "Canonical resolver set used by agent-session/query-in.

   Kept in sync with `build-env` so graph introspection reflects what is
   actually queryable from session root, independent of global registry state."
  []
  (->> (concat all-resolvers
               history-resolvers/all-resolvers
               memory-resolvers/all-resolvers
               recursion-resolvers/all-resolvers)
       vec))

(defn- operation-metadata
  []
  {:resolver-ops (mapv #(graph/operation->metadata :resolver %)
                       (session-resolver-surface))
   :mutation-ops (mapv #(graph/operation->metadata :mutation %)
                       (registry/all-mutations))})

(pco/defresolver query-graph-bridge
  "Resolve all :psi.graph/* attrs from :psi/agent-session-ctx."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.graph/resolver-count
                 :psi.graph/mutation-count
                 :psi.graph/resolver-syms
                 :psi.graph/mutation-syms
                 :psi.graph/env-built
                 :psi.graph/nodes
                 :psi.graph/edges
                 :psi.graph/capabilities
                 :psi.graph/domain-coverage
                 :psi.graph/root-seeds
                 :psi.graph/root-queryable-attrs
                 :psi.graph/resolver-index
                 :psi.graph/attr-index]}
  (let [_                    agent-session-ctx
        op-meta              (operation-metadata)
        cgraph               (graph/derive-capability-graph op-meta)
        resolver-syms        (->> (:resolver-ops op-meta)
                                  (map :symbol)
                                  set)
        mutation-syms        (->> (:mutation-ops op-meta)
                                  (map :symbol)
                                  set)
        root-queryable-attrs (graph/derive-root-queryable-attrs
                              (:resolver-ops op-meta)
                              #{:psi/agent-session-ctx
                                :psi.agent-session/session-id
                                :psi/memory-ctx
                                :psi/recursion-ctx
                                :psi/engine-ctx})
        resolver-index       (graph/derive-resolver-index (:resolver-ops op-meta))
        attr-index           (graph/derive-attr-index resolver-index)]
    {:psi.graph/resolver-count       (count resolver-syms)
     :psi.graph/mutation-count       (count mutation-syms)
     :psi.graph/resolver-syms        resolver-syms
     :psi.graph/mutation-syms        mutation-syms
     :psi.graph/env-built            (boolean (seq resolver-syms))
     :psi.graph/nodes                (:nodes cgraph)
     :psi.graph/edges                (:edges cgraph)
     :psi.graph/capabilities         (:capabilities cgraph)
     :psi.graph/domain-coverage      (:domain-coverage cgraph)
     :psi.graph/root-seeds           [:psi/agent-session-ctx
                                      :psi.agent-session/session-id
                                      :psi/memory-ctx
                                      :psi/recursion-ctx
                                      :psi/engine-ctx]
     :psi.graph/root-queryable-attrs root-queryable-attrs
     :psi.graph/resolver-index       resolver-index
     :psi.graph/attr-index           attr-index}))

;; ── All resolvers ───────────────────────────────────────

(def all-resolvers
  "Assembled resolver surface from all domain sub-namespaces."
  (vec (concat session-resolvers/resolvers
               extension-resolvers/resolvers
               project-nrepl-resolvers/resolvers
               service-resolvers/resolvers
               telemetry-resolvers/resolvers
               discovery-resolvers/resolvers
               [query-graph-bridge])))

;; ── Local Pathom env (for component-local queries) ──────

(defn build-env
  "Build a Pathom3 environment for querying an agent-session context.
   Includes memory + recursion resolvers locally so :psi.memory/* and
   :psi.recursion/* attrs are queryable via agent-session/query-in."
  []
  (-> (session-resolver-surface)
      pci/register))

(def ^:private query-env (atom nil))

(defn- ensure-query-env! []
  (or @query-env (reset! query-env (build-env))))

(defn- snapshot-engine-context
  "Build a read-only engine context snapshot from global engine wrappers."
  []
  {:engines           (atom (or (engine/get-all-engines) {}))
   :system-state      (atom (engine/get-system-state))
   :state-transitions (atom (vec (or (engine/get-state-transitions) [])))
   :sc-env            (atom nil)})

(defn query-in
  "Run EQL `q` against `ctx` using this component's Pathom graph.
   Session-scoped reads require explicit :psi.agent-session/session-id in
   `extra-entity`; only non-session roots may be queried without it."
  ([ctx q] (query-in ctx q {}))
  ([ctx q extra-entity]
   (let [memory-ctx    (or (:memory-ctx ctx)
                           (memory/global-context))
         recursion-ctx (or (:recursion-ctx ctx)
                           (recursion/global-context))
         engine-ctx    (or (:engine-ctx ctx)
                           (snapshot-engine-context))
         session-id    (:psi.agent-session/session-id extra-entity)]
     (p.eql/process (ensure-query-env!)
                    (merge extra-entity
                           {:psi/agent-session-ctx        ctx
                            :psi.agent-session/session-id session-id
                            :psi/memory-ctx               memory-ctx
                            :psi/recursion-ctx            recursion-ctx
                            :psi/engine-ctx               engine-ctx})
                    q))))
