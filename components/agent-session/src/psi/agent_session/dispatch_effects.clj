(ns psi.agent-session.dispatch-effects
  "Effect executor for the dispatch pipeline.
   Dispatches on :effect/type via defmulti.

   All back-references to core.clj private helpers are routed through ctx
   keys. This avoids a circular ns dependency between dispatch-effects and core."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.dispatch :as dispatch]
   [psi.workflow-runtime.cancellation-entry :as cancellation-entry]
   [psi.agent-session.extensions :as ext]
   [psi.provider-auth.oauth.core :as oauth]
   [psi.ai.model-registry :as model-registry]
   [psi.memory.core :as memory]
   [psi.memory.runtime :as memory-runtime]
   [psi.session-persistence.core :as persist]
   [psi.shared-config.project :as project-prefs]
   [psi.session-state.model :as session-data]
   [psi.shared-config.user :as user-cfg]
   [psi.session-state.state :as ss]
   [psi.agent-session.statechart :as sc]
   [psi.agent-session.scheduler-time :as scheduler-time]
   [psi.turn-statechart.core :as turn-sc]))

(defonce ^:private scheduler-timer-handles* (atom {}))

(defn scheduler-timer-handle-count [] (count @scheduler-timer-handles*))

(defn cancel-scheduler-timer! [schedule-id]
  (let [handle (get @scheduler-timer-handles* schedule-id)]
    (when handle
      (try
        (cond
          (map? handle)
          (when-let [thread (:thread handle)]
            (.interrupt ^Thread thread))

          (instance? Thread handle)
          (.interrupt ^Thread handle)

          :else nil)
        (catch Exception _ nil))
      (swap! scheduler-timer-handles* dissoc schedule-id))
    {:schedule-id schedule-id :cancelled? true}))

(defn cancel-all-scheduler-timers! []
  (let [ids (vec (keys @scheduler-timer-handles*))]
    (doseq [schedule-id ids]
      (cancel-scheduler-timer! schedule-id))
    {:cancelled-count (count ids)
     :remaining (count @scheduler-timer-handles*)}))

(defmulti execute-effect! (fn [_ctx effect] (:effect/type effect)))
(defmethod execute-effect! :default [_ctx _effect] nil)

(defn- publish-projection-change! [ctx effect]
  (when-let [publish-fn (:publish-projection-change-fn ctx)]
    (publish-fn ctx (dissoc effect :effect/type))))

(defn- effect-session-id [_ctx effect] (:session-id effect))
(defn- effect-agent-ctx [ctx effect] (ss/agent-ctx-in ctx (effect-session-id ctx effect)))
(defn- effect-sc-session-id [ctx effect] (ss/sc-session-id-in ctx (effect-session-id ctx effect)))

(defn- workflow-run-stop-signal
  [ctx run-id]
  (let [state* (:state* ctx)
        run (when (and state* run-id)
              (get-in @state* [:workflows :runs run-id]))]
    (when (and state* run-id)
      (cond
        (nil? run) :removed
        (= :cancelled (:status run)) :cancelled))))

(defn- workflow-session-stop-signal
  [ctx session-id]
  (let [session-data (ss/get-session-data-in ctx session-id)]
    (when (:workflow-owned? session-data)
      (workflow-run-stop-signal ctx (:workflow-run-id session-data)))))

(defn- memory-recover-for-query!
  [ctx query-text]
  (when query-text
    ((or (:memory-recover-query-fn ctx) memory-runtime/recover-for-query!) query-text)))

(defn- workflow-effect-stop-signal
  [ctx effect]
  (or (when-let [run-id (:workflow-run-id effect)]
        (workflow-run-stop-signal ctx run-id))
      (when-let [session-id (effect-session-id ctx effect)]
        (workflow-session-stop-signal ctx session-id))))

(defn- stopped-workflow-execution-result
  [session-id reason]
  {:execution-result/session-id session-id
   :execution-result/assistant-message {:role "assistant"
                                        :content [{:type :error
                                                   :text "Workflow execution stopped before provider request"}]
                                        :stop-reason :error
                                        :error-message "Workflow execution stopped before provider request"
                                        :workflow-stop-reason reason}
   :execution-result/turn-outcome :turn.outcome/error
   :execution-result/tool-calls []
   :execution-result/error-message "Workflow execution stopped before provider request"
   :execution-result/stop-reason :error})

(defn- workflow-abort-guarded? [effect]
  (some #(contains? effect %)
        [:workflow-run-id :workflow-step-id :workflow-attempt-id :expected-session-id]))

(defn- latest-workflow-attempt-in [ctx run-id step-id]
  (last (get-in @(:state* ctx) [:workflows :runs run-id :step-runs step-id :attempts])))

(defn- judge-attempt-active?
  "A judge session is abortable only until its result is recorded.

   Judged actor attempts remain `:succeeded` while judge routing is processed, so
   attempt status alone cannot distinguish an in-flight judge turn from an
   already-completed judge session. `record-judge-result` always records the
   `:judge-output` key, including nil outputs, making key presence the durable
   completion marker for stale/duplicate guarded abort effects."
  [attempt]
  (not (contains? attempt :judge-output)))

(defn- live-workflow-attempt? [attempt session-kind]
  (and (contains? (case session-kind
                    :judge #{:running :validating :succeeded}
                    #{:running :validating})
                  (:status attempt))
       (or (not= :judge session-kind)
           (judge-attempt-active? attempt))))

(defn- workflow-abort-guard-matches? [ctx effect]
  (let [attempt (latest-workflow-attempt-in ctx (:workflow-run-id effect) (:workflow-step-id effect))
        session-kind (or (:workflow-session-kind effect) :attempt)
        session-key (case session-kind
                      :judge :judge-session-id
                      :attempt :execution-session-id)]
    (and attempt
         (= (:workflow-attempt-id effect) (:attempt-id attempt))
         (= (:expected-session-id effect) (get attempt session-key))
         (= (:session-id effect) (get attempt session-key))
         (live-workflow-attempt? attempt session-kind))))

(defn- abort-session! [ctx effect]
  (when-let [turn-ctx (ss/get-state-value-in ctx (ss/state-path :turn-ctx (effect-session-id ctx effect)))]
    (when-let [stream-handle (:stream-handle @(:turn-data turn-ctx))]
      (when-let [f (:future stream-handle)] (future-cancel f))
      (swap! (:turn-data turn-ctx) assoc :stream-handle (assoc stream-handle :cancelled? true)))
    (when-not (:final-message @(:turn-data turn-ctx))
      (turn-sc/send-event! turn-ctx :turn/error {:stop-reason :aborted :error-message "Aborted"})))
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/abort-in! ac)))

(defn drop-trailing-overflow-error! [ctx session-id]
  (let [ac (ss/agent-ctx-in ctx session-id)
        messages (:messages (agent/get-data-in ac))
        last-msg (last messages)
        stop-reason (or (:stop-reason last-msg) (:stopReason last-msg))
        err (str (or (:error-message last-msg) ""))]
    (when (and (map? last-msg)
               (or (= :error stop-reason) (= "error" stop-reason))
               (session-data/context-overflow-error? err))
      (agent/replace-messages-in! ac (vec (butlast messages))))))

(defmethod execute-effect! :runtime/agent-abort [ctx effect]
  (if (workflow-abort-guarded? effect)
    (when (workflow-abort-guard-matches? ctx effect)
      (abort-session! ctx effect)
      {:aborted? true :session-id (:session-id effect) :guarded? true})
    (do
      (abort-session! ctx effect)
      {:aborted? true :session-id (effect-session-id ctx effect) :guarded? false})))

(defmethod execute-effect! :runtime/cancel-inflight-run [ctx effect]
  (let [run-id (:run-id effect)
        inflight-runs (:workflow-inflight-runs-handle ctx)
        entry (when inflight-runs (get @inflight-runs run-id))
        fut (:future entry)]
    (when fut (future-cancel fut))
    {:run-id run-id :found? (boolean entry) :cancelled? (boolean fut)}))

(defmethod execute-effect! :runtime/drop-inflight-run [ctx effect]
  (let [run-id (:run-id effect)
        inflight-runs (:workflow-inflight-runs-handle ctx)
        found? (boolean (when inflight-runs (get @inflight-runs run-id)))]
    (when inflight-runs (swap! inflight-runs dissoc run-id))
    {:run-id run-id :found? found? :dropped? true}))

(defmethod execute-effect! :runtime/drop-workflow-cancellation-entry-lock [ctx effect]
  (cancellation-entry/drop-lock! ctx (:run-id effect)))

(defmethod execute-effect! :runtime/agent-queue-steering [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/queue-steering-in! ac (:message effect))))
(defmethod execute-effect! :runtime/agent-queue-follow-up [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/queue-follow-up-in! ac (:message effect))))
(defmethod execute-effect! :runtime/agent-clear-steering-queue [ctx effect]
  (when-not (workflow-effect-stop-signal ctx effect)
    (when-let [ac (effect-agent-ctx ctx effect)] (agent/clear-steering-queue-in! ac))))
(defmethod execute-effect! :runtime/agent-clear-follow-up-queue [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/clear-follow-up-queue-in! ac)))
(defmethod execute-effect! :runtime/agent-drain-follow-up-queue [ctx effect]
  (when-not (workflow-effect-stop-signal ctx effect)
    (when-let [ac (effect-agent-ctx ctx effect)] (agent/drain-follow-up-in! ac (:messages effect)))))
(defmethod execute-effect! :runtime/agent-start-loop [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/start-loop-in! ac [])))
(defmethod execute-effect! :runtime/agent-start-loop-with-messages [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/start-loop-in! ac (vec (:messages effect)))))
(defmethod execute-effect! :runtime/agent-set-model [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/set-model-in! ac (:model effect))))
(defmethod execute-effect! :runtime/agent-set-thinking-level [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/set-thinking-level-in! ac (:level effect))))
(defmethod execute-effect! :runtime/agent-set-speed-mode [ctx effect]
  (when-let [data* (some-> (effect-agent-ctx ctx effect) :data-atom)]
    (swap! data* assoc :speed-mode (:mode effect))))
(defmethod execute-effect! :runtime/agent-set-effort-override [ctx effect]
  (when-let [data* (some-> (effect-agent-ctx ctx effect) :data-atom)]
    (swap! data* assoc :effort-override (:effort effect))))
(defmethod execute-effect! :runtime/agent-set-system-prompt [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/set-system-prompt-in! ac (:prompt effect))))
(defmethod execute-effect! :runtime/agent-set-tools [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/set-tools-in! ac (vec (:tool-maps effect)))))

(defmethod execute-effect! :runtime/agent-reset [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/reset-agent-in! ac)))
(defmethod execute-effect! :runtime/agent-replace-messages [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/replace-messages-in! ac (vec (:messages effect)))))
(defmethod execute-effect! :runtime/agent-append-message [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/append-message-in! ac (:message effect))))
(defmethod execute-effect! :runtime/agent-emit [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/emit-in! ac (:event effect))))
(defmethod execute-effect! :runtime/agent-emit-tool-start [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/emit-tool-start-in! ac (:tool-call effect))))
(defmethod execute-effect! :runtime/agent-emit-tool-end [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/emit-tool-end-in! ac (:tool-call effect) (:result effect) (:is-error? effect))))
(defmethod execute-effect! :runtime/agent-record-tool-result [ctx effect]
  (when-let [ac (effect-agent-ctx ctx effect)] (agent/record-tool-result-in! ac (:tool-result-msg effect))))

(defmethod execute-effect! :runtime/record-pending-tool-call-interrupts [ctx effect]
  (when-not (workflow-effect-stop-signal ctx effect)
    (let [session-id (:session-id effect)
          reason     (:reason effect)
          agent-ctx  (effect-agent-ctx ctx effect)
          pending    (vec (or (some-> agent-ctx agent/get-data-in :pending-tool-calls) #{}))]
      (doseq [tool-call-id pending]
        (dispatch/dispatch! ctx
                            :session/tool-agent-record-result
                            {:session-id session-id
                             :tool-result-msg {:role "toolResult"
                                               :tool-call-id tool-call-id
                                               :tool-name "interrupted"
                                               :content [{:type :text
                                                          :text (str "Tool execution interrupted before completion."
                                                                     (when reason
                                                                       (str " Reason: " (name reason) ".")))}]
                                               :is-error true
                                               :details {:interruption {:reason reason}}
                                               :timestamp (java.time.Instant/now)}}
                            {:origin :core}))
      {:recorded-count (count pending) :reason reason})))

(defmethod execute-effect! :runtime/tool-execute [ctx effect]
  (try
    (try ((:execute-tool-runtime-fn ctx) ctx (:session-id effect) (:tool-name effect) (:args effect) (:opts effect))
         (catch clojure.lang.ArityException _
           ((:execute-tool-runtime-fn ctx) ctx (:tool-name effect) (:args effect) (:opts effect))))
    (catch Exception e {:content (str "Error: " (ex-message e)) :is-error true})))

(defmethod execute-effect! :runtime/prompt-execute-and-record [ctx effect]
  (let [session-id (effect-session-id ctx effect)
        prepared-request (:prepared-request effect)
        progress-queue (:progress-queue effect)]
    (if-let [reason (workflow-effect-stop-signal ctx effect)]
      (stopped-workflow-execution-result session-id reason)
      (let [execution-result ((:execute-prepared-request-fn ctx) (:ai-ctx ctx) ctx session-id prepared-request progress-queue)]
        (if-let [reason (workflow-effect-stop-signal ctx effect)]
          (stopped-workflow-execution-result session-id reason)
          (let [record-result (dispatch/dispatch! ctx :session/prompt-record-response {:session-id session-id :execution-result execution-result :progress-queue progress-queue} {:origin :core})]
            (if-let [reason (or (:reason record-result)
                                (workflow-effect-stop-signal ctx effect))]
              (stopped-workflow-execution-result session-id reason)
              (let [latest-summary (:last-execution-result-summary (ss/get-session-data-in ctx session-id))]
                (if (= (:execution-result/turn-id execution-result)
                       (:turn-id latest-summary))
                  execution-result
                  {:execution-result/turn-id (:turn-id latest-summary)
                   :execution-result/session-id session-id
                   :execution-result/assistant-message (or (some (fn [entry]
                                                                   (let [message (get-in entry [:data :message])]
                                                                     (when (= "assistant" (:role message))
                                                                       message)))
                                                                 (rseq (vec (persist/all-entries-in ctx session-id))))
                                                           (:execution-result/assistant-message execution-result))
                   :execution-result/turn-outcome (:turn-outcome latest-summary)
                   :execution-result/tool-calls []
                   :execution-result/stop-reason (:stop-reason latest-summary)})))))))))

(defmethod execute-effect! :runtime/recover-query-prompt-execute-and-record [ctx effect]
  (let [session-id (effect-session-id ctx effect)]
    (if-let [reason (workflow-effect-stop-signal ctx effect)]
      (stopped-workflow-execution-result session-id reason)
      (do
        (memory-recover-for-query! ctx (:query-text effect))
        (execute-effect! ctx (assoc effect :effect/type :runtime/prompt-execute-and-record))))))

(defmethod execute-effect! :runtime/prompt-continue-chain [ctx effect]
  (when-not (workflow-effect-stop-signal ctx effect)
    ((:continue-prompt-chain-fn ctx) ctx (effect-session-id ctx effect) (:execution-result effect) (:progress-queue effect))))

(defn- workflow-guarded-event-data
  [effect]
  (let [event-data (or (:event-data effect) {})]
    (cond-> event-data
      (and (:workflow-run-id effect)
           (not (contains? event-data :workflow-run-id)))
      (assoc :workflow-run-id (:workflow-run-id effect)))))

(defmethod execute-effect! :runtime/dispatch-event [ctx effect]
  (when-not (workflow-effect-stop-signal ctx effect)
    (dispatch/dispatch! ctx (:event-type effect) (workflow-guarded-event-data effect) {:origin (or (:origin effect) :core)})))
(defmethod execute-effect! :runtime/dispatch-event-with-effect-result [ctx effect]
  (when-not (workflow-effect-stop-signal ctx effect)
    (dispatch/dispatch! ctx (:event-type effect) (workflow-guarded-event-data effect) {:origin (or (:origin effect) :core)})))

(defmethod execute-effect! :runtime/mark-workflow-jobs-terminal [ctx effect]
  (when-not (workflow-effect-stop-signal ctx effect)
    ((:mark-workflow-jobs-terminal-fn ctx) ctx)))
(defmethod execute-effect! :runtime/emit-background-job-terminal-messages [ctx effect]
  (when-not (workflow-effect-stop-signal ctx effect)
    ((:emit-background-job-terminal-messages-fn ctx) ctx (effect-session-id ctx effect))))
(defmethod execute-effect! :runtime/reconcile-and-emit-background-job-terminals [ctx effect]
  (when-not (workflow-effect-stop-signal ctx effect)
    ((:reconcile-and-emit-background-job-terminals-fn ctx) ctx (effect-session-id ctx effect))))

(defmethod execute-effect! :runtime/event-queue-offer [ctx effect]
  (when-let [q (:event-queue ctx)] (.offer ^java.util.concurrent.LinkedBlockingQueue q (:event effect))))
(defmethod execute-effect! :projection/context-changed [ctx effect]
  (publish-projection-change! ctx (assoc effect :projection/type :context-changed)))
(defmethod execute-effect! :projection/ui-changed [ctx effect]
  (publish-projection-change! ctx (assoc effect :projection/type :ui-changed)))

(defmethod execute-effect! :runtime/refresh-system-prompt [ctx effect]
  (let [session-id (effect-session-id ctx effect)]
    ((:refresh-system-prompt-fn ctx) ctx session-id)))

(defmethod execute-effect! :runtime/schedule-thread-sleep-send-event [ctx effect]
  (let [sc-session-id (effect-sc-session-id ctx effect)]
    ((:daemon-thread-fn ctx)
     (fn []
       (Thread/sleep ^long (:delay-ms effect))
       (sc/send-event! (:sc-env ctx) sc-session-id (:event effect))))))

(defmethod execute-effect! :scheduler/start-timer [ctx effect]
  (let [schedule-id (:schedule-id effect)
        fire-at (:fire-at effect)
        scheduler-now (scheduler-time/now (:scheduler-time-source ctx))
        run-after-delay! (or (:scheduler-run-after-delay-fn ctx)
                             (fn [ctx* delay-ms f]
                               ((:daemon-thread-fn ctx*)
                                (fn []
                                  (Thread/sleep ^long delay-ms)
                                  (f)))))
        delay-ms (max 0 (.toMillis (java.time.Duration/between scheduler-now fire-at)))
        callback (fn []
                   (try
                     (dispatch/dispatch! ctx :scheduler/fired {:session-id (effect-session-id ctx effect)
                                                               :schedule-id schedule-id}
                                         {:origin :core})
                     (catch InterruptedException _ nil)
                     (finally
                       (swap! scheduler-timer-handles* dissoc schedule-id)
                       (when-let [timers* (:scheduler-timers* ctx)]
                         (swap! timers* dissoc schedule-id)))))]
    ((fn [handle]
       (swap! scheduler-timer-handles* assoc schedule-id handle)
       (when-let [timers* (:scheduler-timers* ctx)]
         (swap! timers* assoc schedule-id handle))
       handle)
     (run-after-delay! ctx delay-ms callback))))

(defmethod execute-effect! :scheduler/cancel-timer [ctx effect]
  (let [schedule-id (:schedule-id effect)]
    (when-let [handle (or (get @scheduler-timer-handles* schedule-id)
                          (some-> ctx :scheduler-timers* deref (get schedule-id)))]
      (try
        (cond
          (instance? Thread handle) (.interrupt ^Thread handle)
          (:scheduler-cancel-delay-fn ctx) ((:scheduler-cancel-delay-fn ctx) ctx handle)
          :else nil)
        (catch Exception _ nil)))
    (swap! scheduler-timer-handles* dissoc schedule-id)
    (when-let [timers* (:scheduler-timers* ctx)]
      (swap! timers* dissoc schedule-id))
    {:schedule-id schedule-id :cancelled? true}))

(defmethod execute-effect! :scheduler/drain-queue [ctx effect]
  (when-not (workflow-effect-stop-signal ctx effect)
    (dispatch/dispatch! ctx :scheduler/drain-queue {:session-id (effect-session-id ctx effect)} {:origin :core})))

(defmethod execute-effect! :statechart/send-event [ctx effect]
  (when-not (workflow-effect-stop-signal ctx effect)
    (sc/send-event! (:sc-env ctx) (effect-sc-session-id ctx effect) (:event effect))))

(defn- execute-session-journal-io!
  [_ctx {:keys [request]}]
  (let [{:keys [op session-file session-id worktree-path parent-session-id parent-session-path entry entries]} request
        session-file (if (instance? java.io.File session-file)
                       session-file
                       (java.io.File. (str session-file)))]
    (case op
      :append-entry
      (persist/append-entry-to-disk! session-file entry)

      :flush-journal
      (persist/flush-journal! session-file session-id worktree-path parent-session-id parent-session-path entries)

      nil)))

(defmethod execute-effect! :persist/session-journal-io [ctx effect]
  (when-not (workflow-effect-stop-signal ctx effect)
    (let [request (:request effect)
          result  (execute-session-journal-io! ctx effect)]
      (when (= :flush-journal (:op request))
        (ss/apply-root-state-update-in! ctx (persist/mark-flushed-root-update (effect-session-id ctx effect))))
      result)))

(defmethod execute-effect! :persist/project-prefs-update [ctx effect]
  (try
    (let [session-id (effect-session-id ctx effect)]
      (project-prefs/update-agent-session! ((:effective-cwd-fn ctx) ctx session-id) (:prefs effect)))
    (catch Exception _ nil)))
(defmethod execute-effect! :persist/user-config-update [_ctx effect]
  (try (user-cfg/update-agent-session! (:prefs effect)) (catch Exception _ nil)))

(defmethod execute-effect! :notify/extension-dispatch [ctx effect]
  (when-not (workflow-effect-stop-signal ctx effect)
    (ext/dispatch-in (:extension-registry ctx) (:event-name effect) (:payload effect))))
(defmethod execute-effect! :runtime/schedule-extension-dispatch [ctx effect]
  ((:daemon-thread-fn ctx)
   (fn []
     (Thread/sleep ^long (:delay-ms effect))
     (ext/dispatch-in (:extension-registry ctx) (:event-name effect) (:payload effect)))))

(defmethod execute-effect! :oauth/begin-login [_ctx effect]
  (oauth/begin-login! (:oauth-ctx effect) (:provider-id effect)))
(defmethod execute-effect! :oauth/logout [_ctx effect]
  (doseq [provider-id (:provider-ids effect)] (oauth/logout! (:oauth-ctx effect) provider-id))
  nil)

(defmethod execute-effect! :memory/capture [_ctx effect]
  (memory/remember-in! (:memory-ctx effect) {:content-type :note :content (:text effect) :tags [:remember :manual] :provenance (:provenance effect)}))
(defmethod execute-effect! :memory/recover-query [ctx effect]
  (when-not (workflow-effect-stop-signal ctx effect)
    (memory-recover-for-query! ctx (:query-text effect))))

(defmethod execute-effect! :background-job/cancel [ctx effect]
  (let [job (:job effect)]
    (when (= :workflow (:job-kind job))
      (try
        (when (and (:workflow-ext-path job) (:workflow-id job))
          (let [wf-reg (:workflow-registry ctx)]
            (when wf-reg
              ((requiring-resolve 'psi.agent-session.extension-workflow-runtime/abort-workflow-in!) wf-reg (:workflow-ext-path job) (:workflow-id job) "cancel requested"))))
        (catch Exception _ nil)))
    (when-let [refresh-fn (some-> ctx :background-job-ui-refresh-fn deref)]
      (refresh-fn ctx (:session-id effect)))
    job))

(defmethod execute-effect! :model-registry/reload [_ctx effect]
  (let [cwd (:cwd effect)]
    (model-registry/load-project-models! (str cwd "/.psi/models.edn") (model-registry/default-user-models-path))
    {:error (model-registry/get-load-error) :count (count (model-registry/all-models-seq))}))

(defn- execute-auto-compact-workflow! [ctx effect]
  (let [session-id (effect-session-id ctx effect)
        sc-sid (effect-sc-session-id ctx effect)
        reason (:reason effect)
        will-retry? (boolean (:will-retry? effect))
        continue? (atom false)
        reg (:extension-registry ctx)]
    (ext/dispatch-in reg "auto_compaction_start" {:reason reason})
    (when will-retry?
      ((:drop-trailing-overflow-error-fn ctx) ctx session-id))
    ((:daemon-thread-fn ctx)
     (fn []
       (try
         (let [result ((:execute-compaction-fn ctx) ctx session-id nil)]
           (if result
             (do
               (ext/dispatch-in reg "auto_compaction_end" {:result result :aborted false :will-retry will-retry?})
               (let [sd (ss/get-session-data-in ctx session-id)]
                 (when (or will-retry? (seq (:steering-messages sd)) (seq (:follow-up-messages sd)))
                   (reset! continue? true))))
             (ext/dispatch-in reg "auto_compaction_end" {:result nil :aborted true :will-retry false})))
         (catch Exception e
           (ext/dispatch-in reg "auto_compaction_end" {:result nil :aborted false :will-retry false :error-message (ex-message e)}))
         (finally
           (sc/send-event! (:sc-env ctx) sc-sid :session/compact-done)
           (when @continue?
             (sc/send-event! (:sc-env ctx) sc-sid :session/prompt)
             (when-let [ac (ss/agent-ctx-in ctx session-id)]
               (agent/start-loop-in! ac [])))))))))

(defmethod execute-effect! :runtime/auto-compact-workflow [ctx effect]
  (execute-auto-compact-workflow! ctx effect))
