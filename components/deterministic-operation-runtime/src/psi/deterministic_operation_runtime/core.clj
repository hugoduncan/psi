(ns psi.deterministic-operation-runtime.core
  "Canonical deterministic-operation runtime boundary.

   Owns invoke execution plus returned-result validation/error shaping.
   Formal deterministic-operation contracts live in
   `psi.deterministic-operation-registry.defs`."
  (:require
   [psi.deterministic-operation-registry.defs :as defs]
   [psi.workflow-coordination.cancellation-entry :as cancellation-entry]
   [psi.workflow-coordination.ordinary-entry :as ordinary-entry]
   [psi.workflow-coordination.stop-signal :as stop-signal]))

(defn malformed-operation-result-ex
  [operation invocation result]
  (ex-info "Deterministic operation returned malformed result"
           {:type :malformed-operation-result
            :operation-id (:id operation)
            :invocation (dissoc invocation :ctx)
            :result result
            :explanation (defs/explain-operation-result result)}))

(defn- workflow-stop-signal
  [invocation]
  (stop-signal/workflow-stop-signal (:ctx invocation) (:workflow-run-id invocation)))

(defn- workflow-operation-start-required?
  [invocation]
  (and (get-in invocation [:ctx :state*])
       (:workflow-run-id invocation)
       (:step-id invocation)))

(defn- transition-workflow-operation-phase!
  [invocation success-key phase-opts]
  (if-not (workflow-operation-start-required? invocation)
    {success-key true}
    (-> (ordinary-entry/transition-latest-attempt!
         (get-in invocation [:ctx :state*])
         (merge {:workflow-run-id (:workflow-run-id invocation)
                 :workflow-step-id (:step-id invocation)
                 :workflow-attempt-id (:workflow-attempt-id invocation)
                 :attempt-id-required? false}
                phase-opts))
        (ordinary-entry/keyed-result success-key))))

(defn- reserve-workflow-operation-start!
  [invocation]
  (transition-workflow-operation-phase!
   invocation :reserved?
   {:phase-key :operation-start-state
    :phase-value :reserved
    :timestamp-key :operation-start-reserved-at}))

(defn- commit-workflow-operation-start!
  [invocation]
  (transition-workflow-operation-phase!
   invocation :committed?
   {:phase-key :operation-start-state
    :phase-value :started
    :timestamp-key :operation-started-at
    :count-key :operation-start-count}))

(defn- begin-workflow-operation-call!
  [invocation]
  (transition-workflow-operation-phase!
   invocation :begun?
   {:phase-key :operation-call-state
    :phase-value :begun
    :timestamp-key :operation-call-begun-at}))

(defn- commit-workflow-operation-call!
  [invocation]
  (transition-workflow-operation-phase!
   invocation :committed?
   {:required-phases [{:key :operation-call-state
                       :value :begun
                       :reason :call-state-mismatch}]
    :phase-key :operation-call-state
    :phase-value :committed
    :timestamp-key :operation-call-committed-at}))

(defn- prepare-workflow-operation-handler-entry!
  [invocation]
  (transition-workflow-operation-phase!
   invocation :prepared?
   {:required-phases [{:key :operation-call-state
                       :value :committed
                       :reason :call-state-mismatch}]
    :phase-key :operation-handler-entry-state
    :phase-value :pending
    :timestamp-key :operation-handler-entry-pending-at
    :ok-states #{:pending :entered}
    :blocked-states {:closed :handler-entry-closed}}))

(defn- enter-workflow-operation-handler!
  [invocation]
  (transition-workflow-operation-phase!
   invocation :entered?
   {:required-phases [{:key :operation-handler-entry-state
                       :value :pending
                       :reason :handler-entry-state-mismatch}]
    :phase-key :operation-handler-entry-state
    :phase-value :entered
    :timestamp-key :operation-handler-entered-at}))

(defn- call-workflow-operation-start-hook!
  [operation invocation phase]
  (when (:workflow-run-id invocation)
    (when-let [f (get-in invocation [:ctx :before-workflow-operation-start-fn])]
      (f (:ctx invocation)
         {:operation-id (:id operation)
          :workflow-run-id (:workflow-run-id invocation)
          :step-id (:step-id invocation)
          :phase phase}))))

(defn- workflow-stopped-result
  [operation invocation reason]
  {:status :error
   :reason :workflow-stopped
   :message "Workflow execution stopped before deterministic operation start"
   :details {:operation-id (:id operation)
             :workflow-run-id (:workflow-run-id invocation)
             :step-id (:step-id invocation)
             :stop-reason reason}})

(defn- workflow-operation-entry-lock
  [invocation f]
  (if (workflow-operation-start-required? invocation)
    (cancellation-entry/with-run-read-lock (:ctx invocation) (:workflow-run-id invocation) f)
    (f)))

(defn- enter-workflow-operation-handler-call!
  [operation invocation]
  (let [{:keys [entered? reason]}
        (workflow-operation-entry-lock
         invocation
         (fn []
           (if-let [reason (workflow-stop-signal invocation)]
             {:entered? false :reason reason}
             (enter-workflow-operation-handler! invocation))))]
    (if-not entered?
      (workflow-stopped-result operation invocation reason)
      ((:handler operation) (assoc invocation :operation-id (:id operation))))))

(defn- invoke-operation-entry-result
  [operation invocation]
  (ordinary-entry/run-linear-entry-phases!
   {:stop-signal-fn #(workflow-stop-signal invocation)
    :stopped-result-fn #(workflow-stopped-result operation invocation %)
    :hook-fn #(call-workflow-operation-start-hook! operation invocation %)
    :phases [{:transition #(reserve-workflow-operation-start! invocation)
              :success-key :reserved?
              :before-hook :before-reserve
              :after-hook :after-reserve}
             {:transition #(commit-workflow-operation-start! invocation)
              :success-key :committed?
              :after-hook :after-commit}
             {:transition #(begin-workflow-operation-call! invocation)
              :success-key :begun?
              :after-hook :after-call-begin}
             {:transition #(commit-workflow-operation-call! invocation)
              :success-key :committed?
              :after-hook :after-call-commit}]}))

(defn- invoke-operation-result
  [operation invocation]
  (try
    (let [{:keys [ok? result]} (invoke-operation-entry-result operation invocation)]
      (if-not ok?
        result
        (let [{:keys [prepared? reason]} (prepare-workflow-operation-handler-entry! invocation)]
          (if-not prepared?
            (workflow-stopped-result operation invocation reason)
            (do
              (call-workflow-operation-start-hook! operation invocation :before-handler-entry)
              (enter-workflow-operation-handler-call! operation invocation))))))
    (catch Throwable t
      {:status :error
       :reason :operation-threw
       :message (or (ex-message t) (str t))
       :details {:operation-id (:id operation)}})))

(defn- invoke-operation*
  [operation invocation]
  (invoke-operation-result operation invocation))

(defn invoke-operation
  "Invoke a normalized deterministic operation.

   Implementations receive one invocation map. Current first-cut keys may include:
   - :operation-id
   - :args
   - :ctx
   - :session-id
   - :workflow-run-id
   - :step-id
   - :parent-session-id

   Implementations must return one tagged operation result:
   - success => {:status :ok :data ... :summary? string :details? map}
   - failure => {:status :error :reason keyword :message string :details? map}

   Thrown exceptions are canonicalized into tagged `:error` results.
   Malformed returned values are rejected with ex-info."
  [operation invocation]
  (let [result (invoke-operation* operation invocation)]
    (when-not (defs/valid-operation-result? result)
      (throw (malformed-operation-result-ex operation invocation result)))
    result))
