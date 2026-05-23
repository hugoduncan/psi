(ns psi.agent-session.scheduler-runtime
  "Projection helpers from scheduler state into public/query/UI surfaces."
  (:require
   [psi.agent-session.scheduler :as scheduler]
   [psi.session-state.state :as ss]))

(def scheduler-eql-attrs
  [:psi.scheduler/schedule-id
   :psi.scheduler/kind
   :psi.scheduler/label
   :psi.scheduler/message
   :psi.scheduler/source
   :psi.scheduler/created-at
   :psi.scheduler/fire-at
   :psi.scheduler/status
   :psi.scheduler/origin-session-id
   :psi.scheduler/created-session-id
   :psi.scheduler/delivery-phase
   :psi.scheduler/error-summary
   :psi.scheduler/session-config-summary])

(defn scheduler-state-in
  [ctx session-id]
  (or (ss/get-state-value-in ctx (ss/state-path :scheduler session-id))
      (scheduler/empty-state)))

(defn scheduler-schedules-in
  ([ctx session-id]
   (scheduler-schedules-in ctx session-id [:pending :queued :delivered :cancelled :failed]))
  ([ctx session-id statuses]
   (scheduler/list-schedules (scheduler-state-in ctx session-id) statuses)))

(defn session-config-summary
  [session-config]
  (when session-config
    {:session-name (get session-config :session-name)
     :model (get session-config :model)
     :thinking-level (get session-config :thinking-level)
     :skill-count (count (distinct (or (get session-config :skill-ids)
                                       (some->> (get session-config :skills)
                                                (map :name)
                                                vec)
                                       [])))
     :tool-count (count (or (get session-config :tool-defs) []))
     :has-system-prompt? (boolean (get session-config :system-prompt))
     :has-developer-prompt? (boolean (get session-config :developer-prompt))
     :preloaded-message-count (count (or (get session-config :preloaded-messages) []))
     :has-prompt-component-selection? (boolean (get session-config :prompt-component-selection))}))

(defn schedule->eql
  [schedule]
  {:psi.scheduler/schedule-id (:schedule-id schedule)
   :psi.scheduler/kind (:kind schedule)
   :psi.scheduler/label (:label schedule)
   :psi.scheduler/message (:message schedule)
   :psi.scheduler/source (:source schedule)
   :psi.scheduler/created-at (:created-at schedule)
   :psi.scheduler/fire-at (:fire-at schedule)
   :psi.scheduler/status (:status schedule)
   :psi.scheduler/origin-session-id (:origin-session-id schedule)
   :psi.scheduler/created-session-id (:created-session-id schedule)
   :psi.scheduler/delivery-phase (:delivery-phase schedule)
   :psi.scheduler/error-summary (:error-summary schedule)
   :psi.scheduler/session-config-summary (or (:session-config-summary schedule)
                                             (session-config-summary (:session-config schedule)))})

(defn eql->schedule
  [schedule]
  {:schedule-id (:psi.scheduler/schedule-id schedule)
   :kind (:psi.scheduler/kind schedule)
   :label (:psi.scheduler/label schedule)
   :message (:psi.scheduler/message schedule)
   :source (:psi.scheduler/source schedule)
   :created-at (:psi.scheduler/created-at schedule)
   :fire-at (:psi.scheduler/fire-at schedule)
   :status (:psi.scheduler/status schedule)
   :origin-session-id (:psi.scheduler/origin-session-id schedule)
   :created-session-id (:psi.scheduler/created-session-id schedule)
   :delivery-phase (:psi.scheduler/delivery-phase schedule)
   :error-summary (:psi.scheduler/error-summary schedule)
   :session-config-summary (:psi.scheduler/session-config-summary schedule)})

(defn schedule->psi-tool-summary
  [schedule]
  {:schedule-id (:schedule-id schedule)
   :kind (:kind schedule)
   :label (:label schedule)
   :message (:message schedule)
   :status (:status schedule)
   :created-at (:created-at schedule)
   :fire-at (:fire-at schedule)
   :origin-session-id (:origin-session-id schedule)
   :created-session-id (:created-session-id schedule)
   :delivery-phase (:delivery-phase schedule)
   :error-summary (:error-summary schedule)
   :session-config-summary (or (:session-config-summary schedule)
                               (session-config-summary (:session-config schedule)))})

(defn scheduler-list->psi-tool-summary
  [schedules]
  {:schedule-count (count schedules)
   :schedules (mapv schedule->psi-tool-summary schedules)})

(defn schedule->background-job
  [session-id schedule]
  {:job-id (str "schedule/" (:schedule-id schedule))
   :thread-id session-id
   :tool-call-id nil
   :tool-name (or (:label schedule) "scheduler")
   :job-kind (if (= :session (:kind schedule)) :scheduled-session :scheduled-prompt)
   :workflow-ext-path nil
   :workflow-id nil
   :job-seq nil
   :started-at (:created-at schedule)
   :completed-at nil
   :completed-seq nil
   :status (case (:status schedule)
             :pending :running
             :queued :running
             :delivered :completed
             :cancelled :cancelled
             :failed :failed
             :running)
   :scheduler-status (:status schedule)
   :terminal-payload nil
   :terminal-payload-file nil
   :cancel-requested-at nil
   :terminal-message-emitted false
   :terminal-message-emitted-at nil
   :schedule-id (:schedule-id schedule)
   :kind (:kind schedule)
   :origin-session-id (:origin-session-id schedule)
   :created-session-id (:created-session-id schedule)
   :delivery-phase (:delivery-phase schedule)
   :error-summary (:error-summary schedule)
   :fire-at (:fire-at schedule)
   :message (:message schedule)
   :label (:label schedule)})

(defn scheduler-jobs-in
  [ctx session-id]
  (mapv (partial schedule->background-job session-id)
        (scheduler-schedules-in ctx session-id [:pending :queued :delivered :cancelled :failed])))
