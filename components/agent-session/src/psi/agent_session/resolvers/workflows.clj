(ns psi.agent-session.resolvers.workflows
  "Pathom3 resolvers for deterministic workflow definitions, runs, and workflow↔session relationships."
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(def ^:private workflow-step-output
  [:psi.workflow.step/id
   :psi.workflow.step/label
   :psi.workflow.step/description
   :psi.workflow.step/executor
   :psi.workflow.step/input-bindings
   :psi.workflow.step/result-schema
   :psi.workflow.step/retry-policy
   :psi.workflow.step/capability-policy])

(def ^:private workflow-definition-output
  [:psi.workflow.definition/id
   :psi.workflow.definition/name
   :psi.workflow.definition/summary
   :psi.workflow.definition/description
   :psi.workflow.definition/step-order
   :psi.workflow.definition/step-count
   {:psi.workflow.definition/steps workflow-step-output}])

(def ^:private workflow-history-output
  [:psi.workflow.history/event
   :psi.workflow.history/timestamp
   :psi.workflow.history/data])

(declare workflow-run-output)

(def ^:private workflow-attempt-output
  [:psi.workflow.attempt/id
   :psi.workflow.attempt/status
   :psi.workflow.attempt/execution-session-id
   :psi.workflow.attempt/result-envelope
   :psi.workflow.attempt/validation-outcome
   :psi.workflow.attempt/execution-error
   :psi.workflow.attempt/blocked
   :psi.workflow.attempt/created-at
   :psi.workflow.attempt/updated-at
   :psi.workflow.attempt/finished-at])

(def ^:private workflow-step-run-output
  [:psi.workflow.step-run/id
   :psi.workflow.step-run/status
   :psi.workflow.step-run/attempt-count
   :psi.workflow.step-run/accepted-result
   {:psi.workflow.step-run/attempts workflow-attempt-output}])

(def ^:private workflow-run-output
  [:psi.workflow.run/id
   :psi.workflow.run/status
   :psi.workflow.run/source-definition-id
   :psi.workflow.run/workflow-input
   :psi.workflow.run/current-step-id
   :psi.workflow.run/blocked
   :psi.workflow.run/terminal-outcome
   :psi.workflow.run/created-at
   :psi.workflow.run/updated-at
   :psi.workflow.run/finished-at
   {:psi.workflow.run/effective-definition workflow-definition-output}
   {:psi.workflow.run/step-runs workflow-step-run-output}
   {:psi.workflow.run/history workflow-history-output}
   :psi.workflow.run/execution-session-ids])

(defn- root-workflow-state
  [agent-session-ctx]
  (or (:workflows @(:state* agent-session-ctx)) {}))

(defn- definition-map
  [agent-session-ctx]
  (or (:definitions (root-workflow-state agent-session-ctx)) {}))

(defn- ordered-definitions
  [agent-session-ctx]
  (->> (vals (definition-map agent-session-ctx))
       (sort-by :definition-id)
       vec))

(defn- run-map
  [agent-session-ctx]
  (or (:runs (root-workflow-state agent-session-ctx)) {}))

(defn- ordered-runs
  [agent-session-ctx]
  (workflow-runtime/list-workflow-runs @(:state* agent-session-ctx)))

(defn- step-definition->eql
  [[step-id step-def]]
  {:psi.workflow.step/id                step-id
   :psi.workflow.step/label             (:label step-def)
   :psi.workflow.step/description       (:description step-def)
   :psi.workflow.step/executor          (:executor step-def)
   :psi.workflow.step/input-bindings    (:input-bindings step-def)
   :psi.workflow.step/result-schema     (:result-schema step-def)
   :psi.workflow.step/retry-policy      (:retry-policy step-def)
   :psi.workflow.step/capability-policy (:capability-policy step-def)})

(defn- definition->eql
  [definition]
  (let [steps (->> (:step-order definition)
                   (map (fn [step-id]
                          [step-id (get-in definition [:steps step-id])]))
                   (mapv step-definition->eql))]
    {:psi.workflow.definition/id          (:definition-id definition)
     :psi.workflow.definition/name        (:name definition)
     :psi.workflow.definition/summary     (:summary definition)
     :psi.workflow.definition/description (:description definition)
     :psi.workflow.definition/step-order  (:step-order definition)
     :psi.workflow.definition/step-count  (count (:step-order definition))
     :psi.workflow.definition/steps       steps}))

(defn- history-entry->eql
  [entry]
  {:psi.workflow.history/event     (:event entry)
   :psi.workflow.history/timestamp (:timestamp entry)
   :psi.workflow.history/data      (:data entry)})

(defn- attempt->eql
  [attempt]
  {:psi.workflow.attempt/id                   (:attempt-id attempt)
   :psi.workflow.attempt/status               (:status attempt)
   :psi.workflow.attempt/execution-session-id (:execution-session-id attempt)
   :psi.workflow.attempt/result-envelope      (:result-envelope attempt)
   :psi.workflow.attempt/validation-outcome   (:validation-outcome attempt)
   :psi.workflow.attempt/execution-error      (:execution-error attempt)
   :psi.workflow.attempt/blocked              (:blocked attempt)
   :psi.workflow.attempt/created-at           (:created-at attempt)
   :psi.workflow.attempt/updated-at           (:updated-at attempt)
   :psi.workflow.attempt/finished-at          (:finished-at attempt)})

(defn- derived-step-run-status
  [step-run]
  (or (some-> step-run :attempts last :status)
      (when (:accepted-result step-run) :succeeded)
      :pending))

(defn- step-run->eql
  [[step-id step-run]]
  {:psi.workflow.step-run/id              step-id
   :psi.workflow.step-run/status          (derived-step-run-status step-run)
   :psi.workflow.step-run/attempt-count   (count (:attempts step-run))
   :psi.workflow.step-run/accepted-result (:accepted-result step-run)
   :psi.workflow.step-run/attempts        (mapv attempt->eql (:attempts step-run))})

(defn- execution-session-ids
  [workflow-run]
  (->> (:step-runs workflow-run)
       vals
       (mapcat :attempts)
       (keep :execution-session-id)
       vec))

(defn- workflow-run->eql
  [workflow-run]
  (let [session-ids (execution-session-ids workflow-run)
        ordered-step-runs (->> (get-in workflow-run [:effective-definition :step-order])
                               (map (fn [step-id]
                                      [step-id (get-in workflow-run [:step-runs step-id])]))
                               (mapv step-run->eql))]
    {:psi.workflow.run/id                    (:run-id workflow-run)
     :psi.workflow.run/status                (:status workflow-run)
     :psi.workflow.run/source-definition-id  (:source-definition-id workflow-run)
     :psi.workflow.run/workflow-input        (:workflow-input workflow-run)
     :psi.workflow.run/current-step-id       (:current-step-id workflow-run)
     :psi.workflow.run/blocked               (:blocked workflow-run)
     :psi.workflow.run/terminal-outcome      (:terminal-outcome workflow-run)
     :psi.workflow.run/created-at            (:created-at workflow-run)
     :psi.workflow.run/updated-at            (:updated-at workflow-run)
     :psi.workflow.run/finished-at           (:finished-at workflow-run)
     :psi.workflow.run/effective-definition  (definition->eql (:effective-definition workflow-run))
     :psi.workflow.run/step-runs             ordered-step-runs
     :psi.workflow.run/history               (mapv history-entry->eql (:history workflow-run))
     :psi.workflow.run/execution-session-ids session-ids}))

(pco/defresolver workflow-definitions-root
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.workflow/definition-count
                 :psi.workflow/definition-ids
                 {:psi.workflow/definitions workflow-definition-output}]}
  (let [definitions (ordered-definitions agent-session-ctx)]
    {:psi.workflow/definition-count (count definitions)
     :psi.workflow/definition-ids   (mapv :definition-id definitions)
     :psi.workflow/definitions      (mapv definition->eql definitions)}))

(pco/defresolver workflow-definition-detail
  [{:keys [psi/agent-session-ctx psi.workflow.definition/id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.workflow.definition/id]
   ::pco/output [:psi.workflow.definition/detail]}
  {:psi.workflow.definition/detail
   (some-> (get (definition-map agent-session-ctx) id)
           definition->eql)})

(pco/defresolver workflow-runs-root
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.workflow/run-count
                 :psi.workflow/run-ids
                 :psi.workflow/run-statuses
                 {:psi.workflow/runs workflow-run-output}]}
  (let [runs (ordered-runs agent-session-ctx)]
    {:psi.workflow/run-count    (count runs)
     :psi.workflow/run-ids      (mapv :run-id runs)
     :psi.workflow/run-statuses (mapv :status runs)
     :psi.workflow/runs         (mapv workflow-run->eql runs)}))

(pco/defresolver workflow-run-detail
  [{:keys [psi/agent-session-ctx psi.workflow.run/id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.workflow.run/id]
   ::pco/output [:psi.workflow.run/detail]}
  {:psi.workflow.run/detail
   (some-> (get (run-map agent-session-ctx) id)
           workflow-run->eql)})

(def resolvers
  [workflow-definitions-root
   workflow-definition-detail
   workflow-runs-root
   workflow-run-detail])
