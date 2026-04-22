(ns psi.agent-session.resolvers.project-nrepl
  "Pathom3 resolvers for managed project nREPL projection state."
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.project-nrepl-config :as project-nrepl-config]
   [psi.agent-session.project-nrepl-runtime :as project-nrepl-runtime]))

(def ^:private project-nrepl-output
  [:psi.project-nrepl/id
   :psi.project-nrepl/worktree-path
   :psi.project-nrepl/acquisition-mode
   :psi.project-nrepl/transport-kind
   :psi.project-nrepl/lifecycle-state
   :psi.project-nrepl/readiness
   :psi.project-nrepl/endpoint
   :psi.project-nrepl/command-vector
   :psi.project-nrepl/session-mode
   :psi.project-nrepl/active-session-id
   :psi.project-nrepl/can-eval?
   :psi.project-nrepl/can-interrupt?
   :psi.project-nrepl/last-error
   :psi.project-nrepl/last-eval
   :psi.project-nrepl/last-interrupt
   :psi.project-nrepl/started-at
   :psi.project-nrepl/updated-at])

(defn- instance->eql
  [instance]
  {:psi.project-nrepl/id               (:id instance)
   :psi.project-nrepl/worktree-path    (:worktree-path instance)
   :psi.project-nrepl/acquisition-mode (:acquisition-mode instance)
   :psi.project-nrepl/transport-kind   (:transport-kind instance)
   :psi.project-nrepl/lifecycle-state  (:lifecycle-state instance)
   :psi.project-nrepl/readiness        (:readiness instance)
   :psi.project-nrepl/endpoint         (:endpoint instance)
   :psi.project-nrepl/command-vector   (:command-vector instance)
   :psi.project-nrepl/session-mode     (:session-mode instance)
   :psi.project-nrepl/active-session-id (:active-session-id instance)
   :psi.project-nrepl/can-eval?        (boolean (:can-eval? instance))
   :psi.project-nrepl/can-interrupt?   (boolean (:can-interrupt? instance))
   :psi.project-nrepl/last-error       (:last-error instance)
   :psi.project-nrepl/last-eval        (:last-eval instance)
   :psi.project-nrepl/last-interrupt   (:last-interrupt instance)
   :psi.project-nrepl/started-at       (:started-at instance)
   :psi.project-nrepl/updated-at       (:updated-at instance)})

(pco/defresolver project-nrepl-registry
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.project-nrepl/count
                 :psi.project-nrepl/worktree-paths
                 {:psi.project-nrepl/instances project-nrepl-output}]}
  (let [instances (->> (project-nrepl-runtime/instances-in agent-session-ctx)
                       (sort-by :worktree-path)
                       (mapv instance->eql))]
    {:psi.project-nrepl/count          (count instances)
     :psi.project-nrepl/worktree-paths (mapv :psi.project-nrepl/worktree-path instances)
     :psi.project-nrepl/instances      instances}))

(pco/defresolver session-project-nrepl
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [{:psi.agent-session/project-nrepl project-nrepl-output}]}
  (let [worktree-path (project-nrepl-config/resolve-target-worktree
                       {:session-worktree-path (:worktree-path (get-in @(:state* agent-session-ctx)
                                                                       [:agent-session :sessions session-id :data]))})
        instance      (project-nrepl-runtime/instance-in agent-session-ctx worktree-path)]
    {:psi.agent-session/project-nrepl (when instance
                                        (instance->eql instance))}))

(def resolvers
  [project-nrepl-registry
   session-project-nrepl])
