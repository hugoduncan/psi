(ns psi.turn-runtime.core
  "Live turn execution runtime.

   Owns prepared-request execution, provider stream consumption, turn-context
   construction, wait/timeout/abort handling, and canonical execution-result
   shaping for one prepared turn."
  (:require
   [psi.ai.models :as models]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.accumulator :as accum]
   [psi.turn-runtime.state :as trs]
   [psi.turn-runtime.stream :as stream]
   [psi.turn-statechart.core :as turn-sc]))

(def ^:dynamic llm-stream-idle-timeout-ms stream/llm-stream-idle-timeout-ms)
(def ^:dynamic llm-stream-wait-poll-ms stream/llm-stream-wait-poll-ms)

(defn classify-assistant-message
  [assistant-msg]
  (let [tool-calls (vec (filter #(= :tool-call (:type %)) (:content assistant-msg)))]
    (cond
      (= :error (:stop-reason assistant-msg))
      {:turn/outcome :turn.outcome/error
       :assistant-message assistant-msg
       :tool-calls tool-calls}

      (seq tool-calls)
      {:turn/outcome :turn.outcome/tool-use
       :assistant-message assistant-msg
       :tool-calls tool-calls}

      :else
      {:turn/outcome :turn.outcome/stop
       :assistant-message assistant-msg
       :tool-calls tool-calls})))

(defn do-stream!
  [ai-ctx ai-conv ai-model ai-options consume-fn]
  (stream/do-stream! ai-ctx ai-conv ai-model ai-options consume-fn))

(defn wait-for-turn-result
  "Wait for `done-p` with an idle timeout that resets on any stream progress."
  [done-p last-progress-ms {:keys [idle-timeout-ms wait-poll-ms abort-pred]}]
  (let [opts   (cond-> {:idle-timeout-ms llm-stream-idle-timeout-ms
                        :wait-poll-ms    llm-stream-wait-poll-ms}
                 idle-timeout-ms (assoc :idle-timeout-ms idle-timeout-ms)
                 wait-poll-ms    (assoc :wait-poll-ms wait-poll-ms)
                 abort-pred      (assoc :abort-pred abort-pred))
        result (stream/wait-for-turn-result done-p last-progress-ms opts)]
    (case result
      ::stream/timeout ::timeout
      ::stream/aborted ::aborted
      result)))

(defn abort-active-turn-in!
  "Abort the currently active prepared-request turn for `session-id`, if any.
   Cancels the stream handle and forces an aborted terminal turn result."
  [ctx session-id]
  (when-let [turn-ctx (trs/turn-context-in ctx session-id)]
    (stream/abort-turn! turn-ctx)
    true))

(defn capture-aware-ai-options
  "Wrap provider request/response callbacks so captures are recorded with the
   current `turn-id` while preserving any caller-supplied callbacks."
  [ctx session-id turn-id base-ai-options]
  (let [opts (or base-ai-options {})]
    (-> opts
        (assoc :on-provider-request
               (stream/chain-callbacks
                (:on-provider-request opts)
                (fn [capture]
                  (trs/append-provider-request-capture-in!
                   ctx session-id (assoc capture :turn-id turn-id)))))
        (assoc :on-provider-response
               (stream/chain-callbacks
                (:on-provider-response opts)
                (fn [capture]
                  (trs/append-provider-reply-capture-in!
                   ctx session-id (assoc capture :turn-id turn-id))))))))

(defn create-live-turn-context
  "Create and install the canonical live turn execution state used by prompt
   execution paths. Returns the working state needed to drive one provider
   stream to completion."
  [ctx session-id ai-model progress-queue turn-id]
  (let [done-p           (promise)
        thinking-buffers (atom {})
        actions-fn       (accum/make-turn-actions ctx session-id done-p progress-queue
                                                  ai-model thinking-buffers)
        turn-ctx         (turn-sc/create-turn-context actions-fn)
        _                (swap! (:turn-data turn-ctx) assoc :turn-id turn-id)
        last-progress-ms (atom (stream/now-ms))
        timed-out?       (atom false)]
    (trs/set-turn-context-in! ctx session-id turn-ctx)
    (turn-sc/send-event! turn-ctx :turn/start)
    {:done-p done-p
     :actions-fn actions-fn
     :turn-ctx turn-ctx
     :last-progress-ms last-progress-ms
     :timed-out? timed-out?}))

(defn make-provider-event-consumer
  "Build the canonical provider stream event consumer used by prompt execution
   paths. Handles timestamp refresh, accumulation callbacks, statechart events,
   and optional cancellation checks."
  [turn-ctx actions-fn last-progress-ms timed-out? {:keys [cancelled-pred now-fn]}]
  (let [cancelled?   (or cancelled-pred (constantly false))
        now*         (or now-fn stream/now-ms)
        call-action! (fn [action-key extra]
                       (actions-fn action-key (merge {:turn-data (:turn-data turn-ctx)} extra)))]
    (fn [event]
      (when-not (or @timed-out? (cancelled?))
        (reset! last-progress-ms (now*))
        (case (:type event)
          :start                    nil
          :text-start               (call-action! :on-text-start
                                                  {:content-index (:content-index event)})
          :text-delta               (turn-sc/send-event! turn-ctx :turn/text-delta
                                                         {:content-index (:content-index event)
                                                          :delta         (:delta event)})
          :text-end                 (call-action! :on-text-end
                                                  {:content-index (:content-index event)})
          :thinking-start           (call-action! :on-thinking-start
                                                  {:content-index (:content-index event)
                                                   :thinking      (:thinking event)
                                                   :signature     (:signature event)})
          :thinking-delta           (call-action! :on-thinking-delta
                                                  {:content-index (:content-index event)
                                                   :delta         (:delta event)})
          :thinking-signature-delta (call-action! :on-thinking-signature-delta
                                                  {:content-index (:content-index event)
                                                   :signature     (:signature event)})
          :thinking-end             (call-action! :on-thinking-end
                                                  {:content-index (:content-index event)})
          :toolcall-start           (turn-sc/send-event! turn-ctx :turn/toolcall-start
                                                         {:content-index (:content-index event)
                                                          :tool-id       (:id event)
                                                          :tool-name     (:name event)})
          :toolcall-delta           (turn-sc/send-event! turn-ctx :turn/toolcall-delta
                                                         {:content-index (:content-index event)
                                                          :delta         (:delta event)})
          :toolcall-end             (turn-sc/send-event! turn-ctx :turn/toolcall-end
                                                         {:content-index (:content-index event)})
          :done                     (turn-sc/send-event! turn-ctx :turn/done
                                                         {:reason (:reason event)
                                                          :usage  (:usage event)})
          :error                    (turn-sc/send-event! turn-ctx :turn/error
                                                         (cond-> {:error-message (:error-message event)}
                                                           (:http-status event) (assoc :http-status (:http-status event))))
          nil)))))

(defn await-assistant-message!
  "Wait for a live turn to finish and return the final assistant message."
  [turn-ctx done-p last-progress-ms timed-out? {:keys [idle-timeout-ms wait-poll-ms abort-pred]}]
  (let [result (wait-for-turn-result done-p last-progress-ms
                                     (cond-> {:idle-timeout-ms idle-timeout-ms
                                              :wait-poll-ms    wait-poll-ms}
                                       abort-pred (assoc :abort-pred abort-pred)))]
    (cond
      (= ::timeout result)
      (do (reset! timed-out? true)
          (turn-sc/send-event! turn-ctx :turn/error {:error-message "Timeout waiting for LLM response"})
          {:role          "assistant"
           :content       [{:type :error :text "Timeout waiting for LLM response"}]
           :stop-reason   :error
           :error-message "Timeout waiting for LLM response"
           :timestamp     (java.time.Instant/now)})

      (= ::aborted result)
      (do
        (stream/abort-turn! turn-ctx)
        (:final-message @(:turn-data turn-ctx)))

      :else
      result)))

(defn execute-live-turn!
  "Execute one live provider turn against an already prepared conversation.
   Returns {:turn-id :model :assistant-message :ai-options :turn-ctx}."
  [ai-ctx ctx session-id {:keys [ai-conv ai-model base-ai-options progress-queue turn-id]}]
  (let [{:keys [done-p actions-fn turn-ctx last-progress-ms timed-out?]}
        (create-live-turn-context ctx session-id ai-model progress-queue turn-id)
        ai-options      (capture-aware-ai-options ctx session-id turn-id base-ai-options)
        cancelled-pred  #(stream/cancelled-stream-handle? (:stream-handle @(:turn-data turn-ctx)))
        _stream-handle  (stream/mark-turn-stream-handle!
                         turn-ctx
                         (do-stream! ai-ctx ai-conv ai-model ai-options
                                     (make-provider-event-consumer
                                      turn-ctx actions-fn last-progress-ms timed-out?
                                      {:cancelled-pred cancelled-pred
                                       :now-fn stream/now-ms})))
        assistant-msg   (await-assistant-message!
                         turn-ctx done-p last-progress-ms timed-out?
                         {:idle-timeout-ms (:llm-stream-idle-timeout-ms ai-options)
                          :wait-poll-ms    (:llm-stream-wait-poll-ms ai-options)
                          :abort-pred      cancelled-pred})
        _               (swap! (:turn-data turn-ctx) dissoc :stream-handle)]
    {:turn-id           turn-id
     :model             ai-model
     :ai-options        ai-options
     :turn-ctx          turn-ctx
     :assistant-message assistant-msg}))

(defn execute-prepared-request!
  "Execute one prepared request through the live turn runtime.
   Returns a shaped execution-result map."
  [ai-ctx ctx session-id prepared-request progress-queue]
  (let [turn-id         (:prepared-request/id prepared-request)
        ai-conv         (:prepared-request/provider-conversation prepared-request)
        ai-model        (or (:prepared-request/model prepared-request)
                            (:model (ss/get-session-data-in ctx session-id))
                            (models/get-model :sonnet-4.6))
        base-ai-options (or (:prepared-request/ai-options prepared-request) {})
        {:keys [assistant-message]}
        (execute-live-turn! ai-ctx ctx session-id
                            {:ai-conv         ai-conv
                             :ai-model        ai-model
                             :base-ai-options base-ai-options
                             :progress-queue  progress-queue
                             :turn-id         turn-id})
        outcome         (classify-assistant-message assistant-message)]
    {:execution-result/turn-id             turn-id
     :execution-result/session-id          session-id
     :execution-result/prepared-request-id turn-id
     :execution-result/model               ai-model
     :execution-result/assistant-message   assistant-message
     :execution-result/usage               (:usage assistant-message)
     :execution-result/provider-captures   {:request-captures []
                                            :response-captures []}
     :execution-result/turn-outcome        (:turn/outcome outcome)
     :execution-result/tool-calls          (:tool-calls outcome)
     :execution-result/error-message       (:error-message assistant-message)
     :execution-result/http-status         (:http-status assistant-message)
     :execution-result/stop-reason         (:stop-reason assistant-message)}))
