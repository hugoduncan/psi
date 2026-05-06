(ns psi.agent-session.resolvers.telemetry-basics
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.background-job-runtime :as bg-runtime]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.resolvers.support :as support]
   [psi.state-kernel.dispatch :as kernel]
   [psi.agent-session.scheduler-runtime :as scheduler-runtime]
   [psi.session-state.state :as session]
   [psi.agent-session.state-accessors :as accessors]
   [psi.agent-session.tool-output :as tool-output]))

(def ^:private background-job-status-order
  [:running :pending-cancel :completed :failed :cancelled :timed-out])

(def ^:private background-job-output
  bg-jobs/eql-attrs)

(defn- session-thread-id
  [_agent-session-ctx session-id]
  session-id)

(defn- reconcile-workflow-background-jobs!
  [agent-session-ctx]
  (bg-runtime/reconcile-workflow-background-jobs-in! agent-session-ctx))

(pco/defresolver agent-session-background-jobs
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/background-job-count
                 :psi.agent-session/background-job-statuses
                 :psi.agent-session/background-jobs
                 {:psi.agent-session/background-jobs background-job-output}]}
  (reconcile-workflow-background-jobs! agent-session-ctx)
  (let [thread-id (session-thread-id agent-session-ctx session-id)
        store     (session/get-state-value-in agent-session-ctx (session/state-path :background-jobs))
        jobs      (if thread-id
                    (bg-jobs/list-jobs-in store thread-id background-job-status-order)
                    [])
        scheduler-jobs (scheduler-runtime/scheduler-jobs-in agent-session-ctx session-id)
        all-jobs  (->> (concat jobs scheduler-jobs)
                       (sort-by (juxt :thread-id :started-at :job-id))
                       vec)]
    {:psi.agent-session/background-job-count    (count all-jobs)
     :psi.agent-session/background-job-statuses background-job-status-order
     :psi.agent-session/background-jobs         (mapv bg-jobs/job->eql all-jobs)}))

(pco/defresolver agent-session-dispatch-registry
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/registered-dispatch-event-count
                 {:psi.agent-session/registered-dispatch-events
                  [:psi.dispatch-handler/event-type]}]}
  (let [_       agent-session-ctx
        types   (sort (kernel/registered-event-types))
        entries (mapv (fn [event-type]
                        {:psi.dispatch-handler/event-type event-type})
                      types)]
    {:psi.agent-session/registered-dispatch-event-count (count entries)
     :psi.agent-session/registered-dispatch-events entries}))

(defn- dispatch-event->eql
  [entry]
  {:psi.dispatch-event/event-type        (:event-type entry)
   :psi.dispatch-event/event-data        (:event-data entry)
   :psi.dispatch-event/origin            (:origin entry)
   :psi.dispatch-event/ext-id            (:ext-id entry)
   :psi.dispatch-event/blocked?          (:blocked? entry)
   :psi.dispatch-event/block-reason      (:block-reason entry)
   :psi.dispatch-event/replaying?        (:replaying? entry)
   :psi.dispatch-event/validation-error  (:validation-error entry)
   :psi.dispatch-event/pure-result-kind  (:pure-result-kind entry)
   :psi.dispatch-event/declared-effects  (:declared-effects entry)
   :psi.dispatch-event/applied-effects   (:applied-effects entry)
   :psi.dispatch-event/db-summary-before (:db-summary-before entry)
   :psi.dispatch-event/db-summary-after  (:db-summary-after entry)
   :psi.dispatch-event/timestamp         (:timestamp entry)
   :psi.dispatch-event/duration-ms       (:duration-ms entry)})

(pco/defresolver agent-session-dispatch-event-log
  [{_ctx :psi/agent-session-ctx}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/dispatch-event-log-count
                 {:psi.agent-session/dispatch-event-log
                  [:psi.dispatch-event/event-type
                   :psi.dispatch-event/event-data
                   :psi.dispatch-event/origin
                   :psi.dispatch-event/ext-id
                   :psi.dispatch-event/blocked?
                   :psi.dispatch-event/block-reason
                   :psi.dispatch-event/replaying?
                   :psi.dispatch-event/validation-error
                   :psi.dispatch-event/pure-result-kind
                   :psi.dispatch-event/declared-effects
                   :psi.dispatch-event/applied-effects
                   :psi.dispatch-event/db-summary-before
                   :psi.dispatch-event/db-summary-after
                   :psi.dispatch-event/timestamp
                   :psi.dispatch-event/duration-ms]}]}
  (let [entries (kernel/event-log-entries)]
    {:psi.agent-session/dispatch-event-log-count (count entries)
     :psi.agent-session/dispatch-event-log       (mapv dispatch-event->eql entries)}))

(defn- tool-output-call->eql
  [call]
  {:psi.tool-output.call/tool-call-id        (:tool-call-id call)
   :psi.tool-output.call/tool-name           (:tool-name call)
   :psi.tool-output.call/timestamp           (:timestamp call)
   :psi.tool-output.call/limit-hit?          (:limit-hit call)
   :psi.tool-output.call/truncated-by        (:truncated-by call)
   :psi.tool-output.call/effective-max-lines (:effective-max-lines call)
   :psi.tool-output.call/effective-max-bytes (:effective-max-bytes call)
   :psi.tool-output.call/output-bytes        (:output-bytes call)
   :psi.tool-output.call/context-bytes-added (:context-bytes-added call)})

(pco/defresolver tool-output-policy
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.tool-output/default-max-lines
                 :psi.tool-output/default-max-bytes
                 :psi.tool-output/overrides]}
  {:psi.tool-output/default-max-lines tool-output/default-max-lines
   :psi.tool-output/default-max-bytes tool-output/default-max-bytes
   :psi.tool-output/overrides         (or (:tool-output-overrides
                                           (support/session-data agent-session-ctx session-id))
                                          {})})

(pco/defresolver tool-output-calls
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [{:psi.tool-output/calls
                  [:psi.tool-output.call/tool-call-id
                   :psi.tool-output.call/tool-name
                   :psi.tool-output.call/timestamp
                   :psi.tool-output.call/limit-hit?
                   :psi.tool-output.call/truncated-by
                   :psi.tool-output.call/effective-max-lines
                   :psi.tool-output.call/effective-max-bytes
                   :psi.tool-output.call/output-bytes
                   :psi.tool-output.call/context-bytes-added]}]}
  {:psi.tool-output/calls
   (let [sid session-id]
     (mapv tool-output-call->eql
           (or (:calls (session/get-state-value-in agent-session-ctx (session/state-path :tool-output-stats sid))) [])))})

(pco/defresolver tool-output-stats
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.tool-output/stats]}
  {:psi.tool-output/stats
   (let [sid        session-id
         aggregates (:aggregates (session/get-state-value-in agent-session-ctx (session/state-path :tool-output-stats sid)))]
     {:total-context-bytes (or (:total-context-bytes aggregates) 0)
      :by-tool             (or (:by-tool aggregates) {})
      :limit-hits-by-tool  (or (:limit-hits-by-tool aggregates) {})})})

(pco/defresolver agent-session-journal
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/session-entry-count
                 :psi.agent-session/journal-flushed?
                 {:psi.agent-session/session-entries
                  [:psi.session-entry/id
                   :psi.session-entry/parent-id
                   :psi.session-entry/timestamp
                   :psi.session-entry/kind
                   :psi.session-entry/data]}]}
  (let [entries  (accessors/journal-state-in agent-session-ctx session-id)
        flushed? (:flushed? (accessors/flush-state-in agent-session-ctx session-id))]
    {:psi.agent-session/session-entry-count (count entries)
     :psi.agent-session/journal-flushed?    flushed?
     :psi.agent-session/session-entries
     (mapv (fn [e]
             {:psi.session-entry/id        (:id e)
              :psi.session-entry/parent-id (:parent-id e)
              :psi.session-entry/timestamp (:timestamp e)
              :psi.session-entry/kind      (:kind e)
              :psi.session-entry/data      (:data e)})
           entries)}))

(def resolvers
  [agent-session-background-jobs
   agent-session-dispatch-registry
   agent-session-dispatch-event-log
   tool-output-policy
   tool-output-calls
   tool-output-stats
   agent-session-journal])
