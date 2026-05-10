(ns psi.agent-session.extension-workflow-mutations
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.extension-workflow-runtime :as extension-workflow-runtime]))

(defn- elapsed-ms
  [created-at finished-at]
  (when created-at
    (let [end (or finished-at (java.time.Instant/now))]
      (- (.toEpochMilli ^java.time.Instant end)
         (.toEpochMilli ^java.time.Instant created-at)))))

(defn- workflow->attrs
  [workflow]
  (if-not workflow
    {}
    {:psi.extension.workflow/id            (:id workflow)
     :psi.extension/path                   (:ext-path workflow)
     :psi.extension.workflow/type          (:type workflow)
     :psi.extension.workflow/phase         (:phase workflow)
     :psi.extension.workflow/configuration (:configuration workflow)
     :psi.extension.workflow/running?      (:running? workflow)
     :psi.extension.workflow/done?         (:done? workflow)
     :psi.extension.workflow/error?        (:error? workflow)
     :psi.extension.workflow/error-message (:error-message workflow)
     :psi.extension.workflow/input         (:input workflow)
     :psi.extension.workflow/meta          (:meta workflow)
     :psi.extension.workflow/data          (:data workflow)
     :psi.extension.workflow/result        (:result workflow)
     :psi.extension.workflow/created-at    (:created-at workflow)
     :psi.extension.workflow/started-at    (:started-at workflow)
     :psi.extension.workflow/updated-at    (:updated-at workflow)
     :psi.extension.workflow/finished-at   (:finished-at workflow)
     :psi.extension.workflow/elapsed-ms    (elapsed-ms (:created-at workflow)
                                                       (:finished-at workflow))
     :psi.extension.workflow/event-count   (:event-count workflow)
     :psi.extension.workflow/last-event    (:last-event workflow)
     :psi.extension.workflow/events        (:events workflow)}))

(pco/defmutation register-workflow-type
  "Register or replace an extension workflow type."
  [_ {:keys [psi/agent-session-ctx ext-path type description chart start-event initial-data-fn public-data-fn]}]
  {::pco/op-name 'psi.extension.workflow/register-type
   ::pco/params  [:psi/agent-session-ctx :ext-path :type :chart]
   ::pco/output  [:psi.extension/path
                  :psi.extension.workflow.type/name
                  :psi.extension.workflow.type/registered?
                  :psi.extension.workflow.type/names
                  :psi.extension.workflow/error]}
  (let [{:keys [registered? type type-names error]}
        (extension-workflow-runtime/register-type-in! (:workflow-registry agent-session-ctx)
                                                      ext-path
                                                      {:type            type
                                                       :description     description
                                                       :chart           chart
                                                       :start-event     start-event
                                                       :initial-data-fn initial-data-fn
                                                       :public-data-fn  public-data-fn})]
    {:psi.extension/path                      ext-path
     :psi.extension.workflow.type/name        type
     :psi.extension.workflow.type/registered? registered?
     :psi.extension.workflow.type/names       type-names
     :psi.extension.workflow/error            error}))

(pco/defmutation create-workflow
  "Create a workflow instance for an extension."
  [_ {:keys [psi/agent-session-ctx session-id ext-path type id input meta auto-start? start-event
             track-background-job?]}]
  {::pco/op-name 'psi.extension.workflow/create
   ::pco/params  [:psi/agent-session-ctx :session-id :ext-path :type]
   ::pco/output  [:psi.extension.workflow/created?
                  :psi.extension.workflow/error
                  :psi.extension.workflow/id
                  :psi.extension/path
                  :psi.extension.workflow/type
                  :psi.extension.workflow/phase
                  :psi.extension.workflow/configuration
                  :psi.extension.workflow/running?
                  :psi.extension.workflow/done?
                  :psi.extension.workflow/error?
                  :psi.extension.workflow/error-message
                  :psi.extension.workflow/input
                  :psi.extension.workflow/meta
                  :psi.extension.workflow/data
                  :psi.extension.workflow/result
                  :psi.extension.workflow/created-at
                  :psi.extension.workflow/started-at
                  :psi.extension.workflow/updated-at
                  :psi.extension.workflow/finished-at
                  :psi.extension.workflow/elapsed-ms
                  :psi.extension.workflow/event-count
                  :psi.extension.workflow/last-event
                  :psi.extension.workflow/events
                  :psi.extension.background-job/id]}
  (let [{:keys [created? workflow error]}
        (extension-workflow-runtime/create-workflow-in! (:workflow-registry agent-session-ctx)
                                                        ext-path
                                                        {:type        type
                                                         :id          id
                                                         :input       input
                                                         :meta        meta
                                                         :auto-start? auto-start?
                                                         :start-event start-event})
        payload (merge {:psi.extension.workflow/created? created?
                        :psi.extension.workflow/error    error}
                       (workflow->attrs workflow))
        job     (when (and created? session-id)
                  (bg-rt/maybe-track-background-workflow-job!
                   agent-session-ctx
                   session-id
                   'psi.extension.workflow/create
                   (cond-> {:ext-path ext-path :type type :id id :input input :meta meta
                            :auto-start? auto-start? :start-event start-event}
                     (some? track-background-job?)
                     (assoc :track-background-job? track-background-job?))
                   payload))]
    (cond-> payload
      (:job-id job) (assoc :psi.extension.background-job/id (:job-id job)))))

(pco/defmutation send-workflow-event
  "Send an event to an extension workflow instance."
  [_ {:keys [psi/agent-session-ctx session-id ext-path id event data track-background-job?]}]
  {::pco/op-name 'psi.extension.workflow/send-event
   ::pco/params  [:psi/agent-session-ctx :session-id :ext-path :id :event]
   ::pco/output  [:psi.extension.workflow/event-accepted?
                  :psi.extension.workflow/error
                  :psi.extension.workflow/id
                  :psi.extension/path
                  :psi.extension.workflow/type
                  :psi.extension.workflow/phase
                  :psi.extension.workflow/configuration
                  :psi.extension.workflow/running?
                  :psi.extension.workflow/done?
                  :psi.extension.workflow/error?
                  :psi.extension.workflow/error-message
                  :psi.extension.workflow/input
                  :psi.extension.workflow/meta
                  :psi.extension.workflow/data
                  :psi.extension.workflow/result
                  :psi.extension.workflow/created-at
                  :psi.extension.workflow/started-at
                  :psi.extension.workflow/updated-at
                  :psi.extension.workflow/finished-at
                  :psi.extension.workflow/elapsed-ms
                  :psi.extension.workflow/event-count
                  :psi.extension.workflow/last-event
                  :psi.extension.workflow/events
                  :psi.extension.background-job/id]}
  (let [{:keys [event-accepted? workflow error]}
        (extension-workflow-runtime/send-event-in! (:workflow-registry agent-session-ctx) ext-path id event data)
        payload (merge {:psi.extension.workflow/event-accepted? event-accepted?
                        :psi.extension.workflow/error           error}
                       (workflow->attrs workflow))
        job     (when (and event-accepted? session-id)
                  (bg-rt/maybe-track-background-workflow-job!
                   agent-session-ctx
                   session-id
                   'psi.extension.workflow/send-event
                   (cond-> {:ext-path ext-path
                            :id id
                            :event event
                            :data data}
                     (some? track-background-job?)
                     (assoc :track-background-job? track-background-job?))
                   payload))]
    (cond-> payload
      (:job-id job) (assoc :psi.extension.background-job/id (:job-id job)))))

(pco/defmutation abort-workflow
  "Abort a running extension workflow instance."
  [_ {:keys [psi/agent-session-ctx ext-path id reason]}]
  {::pco/op-name 'psi.extension.workflow/abort
   ::pco/params  [:psi/agent-session-ctx :ext-path :id]
   ::pco/output  [:psi.extension.workflow/aborted?
                  :psi.extension.workflow/error
                  :psi.extension.workflow/id
                  :psi.extension/path
                  :psi.extension.workflow/type
                  :psi.extension.workflow/phase
                  :psi.extension.workflow/configuration
                  :psi.extension.workflow/running?
                  :psi.extension.workflow/done?
                  :psi.extension.workflow/error?
                  :psi.extension.workflow/error-message
                  :psi.extension.workflow/input
                  :psi.extension.workflow/meta
                  :psi.extension.workflow/data
                  :psi.extension.workflow/result
                  :psi.extension.workflow/created-at
                  :psi.extension.workflow/started-at
                  :psi.extension.workflow/updated-at
                  :psi.extension.workflow/finished-at
                  :psi.extension.workflow/elapsed-ms
                  :psi.extension.workflow/event-count
                  :psi.extension.workflow/last-event
                  :psi.extension.workflow/events]}
  (let [{:keys [aborted? workflow error]}
        (extension-workflow-runtime/abort-workflow-in! (:workflow-registry agent-session-ctx) ext-path id reason)]
    (merge {:psi.extension.workflow/aborted? aborted?
            :psi.extension.workflow/error    error}
           (workflow->attrs workflow))))

(pco/defmutation remove-workflow
  "Remove a completed or aborted extension workflow instance."
  [_ {:keys [psi/agent-session-ctx ext-path id]}]
  {::pco/op-name 'psi.extension.workflow/remove
   ::pco/params  [:psi/agent-session-ctx :ext-path :id]
   ::pco/output  [:psi.extension.workflow/removed?
                  :psi.extension.workflow/error
                  :psi.extension/path
                  :psi.extension.workflow/id]}
  (let [{:keys [removed? id error]}
        (extension-workflow-runtime/remove-workflow-in! (:workflow-registry agent-session-ctx) ext-path id)]
    {:psi.extension.workflow/removed? removed?
     :psi.extension.workflow/error    error
     :psi.extension/path              ext-path
     :psi.extension.workflow/id       id}))

(def all-mutations
  [register-workflow-type
   create-workflow
   send-workflow-event
   abort-workflow
   remove-workflow])
