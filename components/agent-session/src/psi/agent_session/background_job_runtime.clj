(ns psi.agent-session.background-job-runtime
  "Background job orchestration over the canonical background-jobs state.

   Owns workflow-backed job tracking, terminal status reconciliation,
   terminal message emission, and the public list/inspect/cancel API.
   All state writes route through dispatch."
  (:require
   [clojure.string :as str]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.scheduler-runtime :as scheduler-runtime]
   [psi.session-state.state :as ss]
   [psi.agent-session.tool-output :as tool-output]
   [psi.agent-session.extension-workflow-runtime :as extension-workflow-runtime]
   [psi.workflow-runtime.core :as workflow-runtime]))

(defn background-jobs-state-in
  "Return canonical background-jobs state."
  [ctx]
  (ss/get-state-value-in ctx (ss/state-path :background-jobs)))

(defn maybe-track-background-workflow-job!
  "Track a workflow mutation as a background job if applicable."
  [ctx session-id op-sym full-params payload]
  (let [store (ss/get-state-value-in ctx (ss/state-path :background-jobs))]
    (when (and (contains? #{'psi.extension.workflow/create
                            'psi.extension.workflow/send-event}
                          op-sym)
               (map? payload)
               store)
      (let [created-op? (= op-sym 'psi.extension.workflow/create)
            accepted?   (if created-op?
                          (:psi.extension.workflow/created? payload)
                          (:psi.extension.workflow/event-accepted? payload))
            track?      (if (contains? full-params :track-background-job?)
                          (true? (:track-background-job? full-params))
                          created-op?)]
        (when (and accepted? track?)
          (let [wf-type      (or (:type full-params)
                                 (:psi.extension.workflow/type payload))
                wf-id        (or (:id full-params)
                                 (:psi.extension.workflow/id payload))
                ext-path     (:ext-path full-params)
                tool-call-id (or (get-in full-params [:input :tool-call-id])
                                 (get-in full-params [:data :tool-call-id])
                                 (:tool-call-id full-params)
                                 (str (if created-op? "workflow-create-" "workflow-send-event-")
                                      (or ext-path "ext") "-" (or wf-id (java.util.UUID/randomUUID))))
                job-kind     (when wf-type :workflow)
                tool-name    (if wf-type
                               (str "workflow/" (name wf-type))
                               "workflow/create")]
            (try
              (let [job-by-call (bg-jobs/find-job-by-tool-call-in store tool-call-id)
                    job-by-wf   (when created-op?
                                  (bg-jobs/find-job-by-workflow-in
                                   store
                                   {:workflow-ext-path ext-path
                                    :workflow-id       wf-id}))
                    started     (when-not (or job-by-call job-by-wf)
                                  (let [state' (:state (bg-jobs/start-background-job
                                                        store
                                                        {:tool-call-id      (str tool-call-id)
                                                         :thread-id         (str session-id)
                                                         :tool-name         tool-name
                                                         :job-id            (str "job-" (java.util.UUID/randomUUID))
                                                         :job-kind          job-kind
                                                         :workflow-ext-path ext-path
                                                         :workflow-id       (some-> wf-id str)}))]
                                    (dispatch/dispatch! ctx :session/update-background-jobs-state {:update-fn (constantly state')} {:origin :core})
                                    (bg-jobs/find-job-by-tool-call-in state' tool-call-id)))]
                (or started
                    job-by-call
                    job-by-wf))
              (catch Exception _
                nil))))))))

(defn maybe-mark-workflow-jobs-terminal!
  "Reconcile workflow-backed job statuses against current workflow runtime state."
  [ctx]
  (let [store (ss/get-state-value-in ctx (ss/state-path :background-jobs))]
    (when store
      (doseq [job (vals (:jobs-by-id store))]
        (when (and (= :workflow (:job-kind job))
                   (not (bg-jobs/terminal-status? (:status job))))
          (let [wf (when (and (:workflow-ext-path job) (:workflow-id job))
                     (extension-workflow-runtime/workflow-in (:workflow-registry ctx)
                                                             (:workflow-ext-path job)
                                                             (:workflow-id job)))
                canonical-run (when (:workflow-id job)
                                (workflow-runtime/workflow-run-in @(:state* ctx) (:workflow-id job)))]
            (cond
              (= :cancelled (:status canonical-run))
              (let [state' (bg-jobs/mark-terminal
                            store
                            {:job-id (:job-id job)
                             :outcome :cancelled
                             :terminal-history-max-per-thread 20
                             :payload {:workflow-id (:workflow-id job)
                                       :status :cancelled
                                       :reason (get-in canonical-run [:terminal-outcome :reason])}})]
                (dispatch/dispatch! ctx :session/update-background-jobs-state {:update-fn (constantly state')} {:origin :core}))

              (:error? wf)
              (let [state' (bg-jobs/mark-terminal
                            store
                            {:job-id (:job-id job)
                             :outcome :failed
                             :terminal-history-max-per-thread 20
                             :payload {:workflow-id (:id wf)
                                       :result (:result wf)
                                       :error-message (:error-message wf)}})]
                (dispatch/dispatch! ctx :session/update-background-jobs-state {:update-fn (constantly state')} {:origin :core}))

              (:done? wf)
              (let [state' (bg-jobs/mark-terminal
                            store
                            {:job-id (:job-id job)
                             :outcome :completed
                             :terminal-history-max-per-thread 20
                             :payload {:workflow-id (:id wf)
                                       :result (:result wf)}})]
                (dispatch/dispatch! ctx :session/update-background-jobs-state {:update-fn (constantly state')} {:origin :core})))))))))

(defn maybe-emit-background-job-terminal-messages!
  "Emit terminal job messages via the explicit ctx-provided notify callback."
  [ctx session-id]
  (let [store      (ss/get-state-value-in ctx (ss/state-path :background-jobs))
        notify-msg (:notify-extension-fn ctx)]
    (when (and store session-id notify-msg)
      (doseq [job (bg-jobs/pending-terminal-jobs-in store session-id)]
        (let [claim* (atom nil)]
          (dispatch/dispatch! ctx
                              :session/update-background-jobs-state
                              {:update-fn (fn [store]
                                            (let [result (bg-jobs/claim-terminal-message-emission
                                                          store
                                                          {:job-id (:job-id job)})]
                                              (reset! claim* result)
                                              (:state result)))}
                              {:origin :core})
          (when-let [{:keys [state] :as result} @claim*]
            (when (:claimed? result)
              (let [wf-ext-path (:workflow-ext-path job)
                    wf-id       (:workflow-id job)
                    wf          (when (and wf-ext-path wf-id)
                                  (extension-workflow-runtime/workflow-in (:workflow-registry ctx) wf-ext-path wf-id))
                    payload     (or (:terminal-payload job)
                                    {:job-id        (:job-id job)
                                     :status        (:status job)
                                     :result        (:result wf)
                                     :error-message (:error-message wf)})
                    payload-edn (pr-str payload)
                    policy      (tool-output/effective-policy
                                 (or (:tool-output-overrides (ss/get-session-data-in ctx session-id)) {})
                                 (or (:tool-name job) "workflow"))
                    truncation  (tool-output/head-truncate payload-edn policy)
                    spill-path  (when (:truncated truncation)
                                  (tool-output/persist-truncated-output!
                                   (or (:tool-name job) "workflow")
                                   (or (:job-id job) "job")
                                   payload-edn))
                    _           (when spill-path
                                  (let [state' (bg-jobs/set-terminal-payload-file
                                                state
                                                {:job-id (:job-id job)
                                                 :path   spill-path})]
                                    (dispatch/dispatch! ctx
                                                        :session/update-background-jobs-state
                                                        {:update-fn (constantly state')}
                                                        {:origin :core})))
                    content     (if spill-path
                                  (str (:content truncation)
                                       "\n\nTerminal payload exceeded output limits. See temp file: "
                                       spill-path)
                                  payload-edn)]
                (try
                  (notify-msg ctx session-id "assistant" content "background-job-terminal")
                  (catch clojure.lang.ArityException _
                    (notify-msg ctx "assistant" content "background-job-terminal")))))))))))

(defn- maybe-refresh-background-job-ui!
  [ctx session-id]
  (when-let [refresh-fn (some-> ctx :background-job-ui-refresh-fn deref)]
    (refresh-fn ctx session-id)))

(defn reconcile-and-emit-background-job-terminals-in!
  "Reconcile workflow-backed background jobs and emit any newly terminal job messages."
  [ctx session-id]
  (maybe-mark-workflow-jobs-terminal! ctx)
  (maybe-emit-background-job-terminal-messages! ctx session-id)
  (maybe-refresh-background-job-ui! ctx session-id)
  (background-jobs-state-in ctx))

(defn reconcile-workflow-background-jobs-in!
  "Reconcile workflow-backed background jobs against current workflow runtime state."
  [ctx]
  (maybe-mark-workflow-jobs-terminal! ctx)
  (background-jobs-state-in ctx))

(defn list-background-jobs-in!
  "List background jobs for a thread, reconciling stale workflow statuses first."
  [ctx thread-id & [statuses]]
  (reconcile-workflow-background-jobs-in! ctx)
  (let [store (ss/get-state-value-in ctx (ss/state-path :background-jobs))
        jobs  (if statuses
                (bg-jobs/list-jobs-in store thread-id statuses)
                (bg-jobs/list-jobs-in store thread-id))
        scheduler-jobs (scheduler-runtime/scheduler-jobs-in ctx thread-id)
        status-set (when statuses (set statuses))]
    (->> (concat jobs scheduler-jobs)
         (filter (fn [job]
                   (or (nil? status-set)
                       (contains? status-set (:status job))
                       (contains? status-set (:scheduler-status job)))))
         (sort-by (juxt :started-at :job-id))
         vec)))

(defn inspect-background-job-in!
  "Inspect a single background job, reconciling stale workflow statuses first."
  [ctx thread-id job-id]
  (maybe-mark-workflow-jobs-terminal! ctx)
  (if (or (str/starts-with? (str job-id) "schedule/")
          (str/starts-with? (str job-id) "sch-"))
    (let [job-id* (if (str/starts-with? (str job-id) "schedule/")
                    (str job-id)
                    (str "schedule/" job-id))
          job (some (fn [scheduler-job]
                      (when (= job-id* (:job-id scheduler-job))
                        scheduler-job))
                    (scheduler-runtime/scheduler-jobs-in ctx thread-id))]
      (when-not job
        (throw (ex-info "Job not found" {:job-id job-id})))
      job)
    (bg-jobs/inspect-job-in (ss/get-state-value-in ctx (ss/state-path :background-jobs))
                            {:thread-id thread-id
                             :job-id job-id})))

(defn cancel-background-job-in!
  "Cancel a background job, aborting the workflow if applicable."
  [ctx thread-id job-id requested-by]
  (let [schedule-id (cond
                      (str/starts-with? (str job-id) "schedule/")
                      (subs (str job-id) (count "schedule/"))

                      (str/starts-with? (str job-id) "sch-")
                      (str job-id)

                      :else nil)]
    (if schedule-id
      (let [schedule (dispatch/dispatch! ctx :scheduler/cancel
                                         {:session-id thread-id
                                          :schedule-id schedule-id}
                                         {:origin :core})
            job      {:job-id (str "schedule/" schedule-id)
                      :thread-id thread-id
                      :tool-name (or (:label schedule) "scheduler")
                      :job-kind :scheduled-prompt
                      :status (or (:status schedule) :cancelled)
                      :schedule-id schedule-id}]
        (maybe-refresh-background-job-ui! ctx thread-id)
        job)
      (let [state' (bg-jobs/request-cancel
                    (ss/get-state-value-in ctx (ss/state-path :background-jobs))
                    {:thread-id thread-id
                     :job-id job-id
                     :requested-by requested-by})
            job    (get-in state' [:jobs-by-id job-id])]
        (dispatch/dispatch! ctx :session/update-background-jobs-state {:update-fn (constantly state')} {:origin :core})
        (when (= :workflow (:job-kind job))
          (try
            (when (and (:workflow-ext-path job) (:workflow-id job))
              (extension-workflow-runtime/abort-workflow-in! (:workflow-registry ctx)
                                                             (:workflow-ext-path job)
                                                             (:workflow-id job)
                                                             "cancel requested"))
            (catch Exception _
              nil)))
        (maybe-refresh-background-job-ui! ctx thread-id)
        job))))
