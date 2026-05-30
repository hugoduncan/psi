(ns psi.turn-runtime.core
  "Live turn execution runtime.

   Owns prepared-request execution, provider stream consumption, turn-context
   construction, wait/timeout/abort handling, and canonical execution-result
   shaping for one prepared turn."
  (:require
   [psi.ai.core :as ai]
   [psi.ai.models :as models]
   [psi.ai.structured-output :as structured-output]
   [psi.agent-session.extensions :as ext]
   [psi.session-state.model :as session-model]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.accumulator :as accum]
   [psi.turn-runtime.recording :as recording]
   [psi.turn-runtime.state :as trs]
   [psi.turn-runtime.stream :as stream]
   [psi.turn-statechart.core :as turn-sc]))

(def ^:dynamic llm-stream-idle-timeout-ms stream/llm-stream-idle-timeout-ms)
(def ^:dynamic llm-stream-wait-poll-ms stream/llm-stream-wait-poll-ms)

(defn classify-assistant-message
  [assistant-msg]
  (recording/classify-assistant-message assistant-msg))

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
   Cancels the stream handle and marks pending provider-retry backoff as
   cancelled so retry sleep can stop before the next attempt."
  [ctx session-id]
  (ss/apply-root-state-update-in!
   ctx
   (ss/session-update session-id #(assoc % :provider-retry-abort-requested? true)))
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
          :logprob-delta            (call-action! :on-logprob-delta
                                                  {:tokens (:tokens event)})
          :structured-output-strategy
          (call-action! :on-structured-output-strategy
                        {:structured-output (:structured-output event)})
          :structured-output-result
          (call-action! :on-structured-output-result
                        {:structured-output (:structured-output event)})
          :done                     (turn-sc/send-event! turn-ctx :turn/done
                                                         {:reason (:reason event)
                                                          :usage  (:usage event)})
          :error                    (turn-sc/send-event! turn-ctx :turn/error
                                                         (cond-> {:error-message (:error-message event)}
                                                           (:http-status event) (assoc :http-status (:http-status event))
                                                           (:headers event) (assoc :headers (:headers event))))
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
        turn-data       @(:turn-data turn-ctx)
        _               (swap! (:turn-data turn-ctx) dissoc :stream-handle)
        logprobs        (:logprobs turn-data)
        structured-output (or (:structured-output-result turn-data)
                              (:structured-output-strategy turn-data))]
    {:turn-id           turn-id
     :model             ai-model
     :ai-options        ai-options
     :turn-ctx          turn-ctx
     :assistant-message assistant-msg
     :logprobs          logprobs
     :structured-output structured-output}))

(defn- response-mode-for
  [ctx session-id prepared-request]
  (or (get-in prepared-request [:prepared-request/session-snapshot :response-mode])
      (:response-mode (ss/get-session-data-in ctx session-id))
      :streaming))

(defn- execute-non-streaming-turn!
  [ai-ctx ctx session-id {:keys [ai-conv ai-model base-ai-options turn-id]}]
  (let [ai-options (capture-aware-ai-options ctx session-id turn-id base-ai-options)
        result     (if ai-ctx
                     (ai/execute-response-in ai-ctx ai-conv ai-model ai-options)
                     (ai/execute-response ai-conv ai-model ai-options))]
    (if (= :error (:type result))
      {:turn-id turn-id
       :model ai-model
       :ai-options ai-options
       :assistant-message (cond-> {:role "assistant"
                                   :content [{:type :error :text (:error-message result)}]
                                   :stop-reason :error
                                   :error-message (:error-message result)
                                   :timestamp (java.time.Instant/now)}
                            (:http-status result) (assoc :http-status (:http-status result))
                            (:headers result) (assoc :provider-error/headers (:headers result)))
       :logprobs nil
       :structured-output (:structured-output result)}
      {:turn-id turn-id
       :model ai-model
       :ai-options ai-options
       :assistant-message (:assistant-message result)
       :logprobs (:logprobs result)
       :structured-output (:structured-output result)})))

(defn- provider-captures-for-turn
  [ctx session-id turn-id]
  {:request-captures  (->> (trs/provider-requests-in ctx session-id)
                           (filter #(= turn-id (:turn-id %)))
                           vec)
   :response-captures (->> (trs/provider-replies-in ctx session-id)
                           (filter #(= turn-id (:turn-id %)))
                           vec)})

(defn- unsupported-structured-output?
  [structured]
  (and (= :unsupported (:strategy structured))
       (contains? #{:structured-output-capability-omitted
                    :structured-output-unsupported
                    :fallback-not-allowed}
                  (:reason structured))))

(defn- unsupported-structured-output-result
  [turn-id ai-model structured]
  (let [structured* (assoc structured :reason :unsupported-structured-output)
        detail-reason (:reason structured)]
    {:turn-id turn-id
     :model ai-model
     :ai-options nil
     :turn-ctx nil
     :assistant-message {:role "assistant"
                         :content [{:type :error
                                    :text "Structured output is not supported by the selected model/transport and fallback is not allowed."}]
                         :stop-reason :error
                         :error-message "Structured output is not supported by the selected model/transport and fallback is not allowed."
                         :timestamp (java.time.Instant/now)}
     :logprobs nil
     :structured-output (cond-> structured*
                          detail-reason (assoc :ai-reason detail-reason))}))

(defn- unsupported-structured-output-before-generation
  [turn-id ai-model base-ai-options]
  (when-let [request (structured-output/structured-output-request base-ai-options)]
    (let [strategy (structured-output/select-strategy ai-model request)]
      (when (unsupported-structured-output? strategy)
        (unsupported-structured-output-result turn-id ai-model strategy)))))

(defn- provider-id-for
  [ai-model]
  (or (some-> ai-model :provider name)
      (some-> ai-model :provider str)
      "unknown"))

(defn- model-id-for
  [ai-model]
  (or (:id ai-model) "unknown"))

(defn- retry-attempt-for
  [ctx session-id]
  (or (:retry-attempt (ss/get-session-data-in ctx session-id)) 0))

(defn- attempt-id-for
  [provider-request-id retry-attempt]
  (str provider-request-id "#attempt-" retry-attempt))

(defn- dispatch-provider-event!
  [ctx event-name payload]
  (let [event (assoc payload :type event-name)]
    (when-let [session-id (:session-id payload)]
      (trs/append-provider-event-in! ctx session-id event))
    (when-let [reg (:extension-registry ctx)]
      (ext/dispatch-in reg event-name event))))

(defn- provider-error-fields
  [assistant-message]
  (let [stop-reason   (:stop-reason assistant-message)
        error-message (:error-message assistant-message)
        http-status   (:http-status assistant-message)
        error-kind    (session-model/provider-error-kind stop-reason error-message http-status)]
    {:stop-reason stop-reason
     :error-message error-message
     :http-status http-status
     :error-kind error-kind
     :retryable? (contains? #{:rate-limit :timeout :overloaded :provider-unavailable :transport} error-kind)}))

(defn- failure-reason-for
  [{:keys [retryable? retry-enabled? retry-attempt max-retries]}]
  (cond
    (not retryable?) :non-retryable
    (not retry-enabled?) :retry-disabled
    (>= retry-attempt max-retries) :retry-exhausted
    :else nil))

(defn- retry-metadata-for
  [ctx assistant-message retry-attempt]
  (let [base-ms              (get-in ctx [:config :auto-retry-base-delay-ms] 2000)
        max-ms               (get-in ctx [:config :auto-retry-max-delay-ms] 60000)
        exponential-delay-ms (session-model/exponential-backoff-ms retry-attempt base-ms max-ms)
        now-fn               (or (:now-fn ctx) #(java.time.Instant/now))
        now-ms               (.toEpochMilli ^java.time.Instant (now-fn))]
    (session-model/retry-metadata (:provider-error/headers assistant-message)
                                  retry-attempt
                                  exponential-delay-ms
                                  now-ms)))

(defn- mark-active-retry!
  [ctx session-id retry-metadata next-retry-attempt]
  (ss/apply-root-state-update-in!
   ctx
   (ss/session-update session-id #(assoc %
                                         :retry-attempt next-retry-attempt
                                         :retry retry-metadata))))

(defn- clear-active-retry!
  [ctx session-id]
  (ss/apply-root-state-update-in!
   ctx
   (ss/session-update session-id #(-> %
                                      (assoc :retry-attempt 0
                                             :retry nil)
                                      (dissoc :provider-retry-abort-requested?)))))

(defn- active-turn-cancelled?
  [ctx session-id]
  (boolean
   (when-let [turn-ctx (trs/turn-context-in ctx session-id)]
     (some-> turn-ctx :turn-data deref :stream-handle stream/cancelled-stream-handle?))))

(defn- provider-retry-cancelled?
  [ctx session-id]
  (boolean
   (or (active-turn-cancelled? ctx session-id)
       (:provider-retry-abort-requested? (ss/get-session-data-in ctx session-id))
       (when-let [cancelled? (:provider-retry-cancelled? ctx)]
         (cancelled? session-id)))))

(defn- retry-sleep-poll-ms
  [ctx delay-ms]
  (long (min (max 1 (long (or delay-ms 0)))
             (max 1 (long (get-in ctx [:config :provider-retry-sleep-poll-ms] 250))))))

(defn- interruptible-sleep-for-retry!
  [ctx session-id delay-ms]
  (let [deadline-ms (+ (System/currentTimeMillis) (long delay-ms))
        poll-ms     (retry-sleep-poll-ms ctx delay-ms)]
    (loop []
      (let [remaining-ms (- deadline-ms (System/currentTimeMillis))]
        (when (and (pos? remaining-ms)
                   (not (provider-retry-cancelled? ctx session-id)))
          (Thread/sleep (long (min poll-ms remaining-ms)))
          (recur))))))

(defn- sleep-for-retry!
  [ctx session-id delay-ms]
  (when (and (not= false (:provider-retry-sleep? ctx))
             (pos? (long (or delay-ms 0)))
             (not (provider-retry-cancelled? ctx session-id)))
    (if-let [sleep-fn (:provider-retry-sleep-fn ctx)]
      (sleep-fn (long delay-ms))
      (interruptible-sleep-for-retry! ctx session-id (long delay-ms))))
  (provider-retry-cancelled? ctx session-id))

(defn- execute-provider-attempt!
  [ai-ctx ctx session-id prepared-request progress-queue attempt-data]
  (let [turn-id          (:prepared-request/id prepared-request)
        ai-conv          (:prepared-request/provider-conversation prepared-request)
        ai-model         (:ai-model attempt-data)
        base-ai-options  (:base-ai-options attempt-data)
        response-mode    (:response-mode attempt-data)
        retry-attempt    (:retry-attempt attempt-data)
        provider-id      (:provider-id attempt-data)
        model-id         (:model-id attempt-data)
        attempt-id       (attempt-id-for turn-id retry-attempt)
        preflight-result (unsupported-structured-output-before-generation turn-id ai-model base-ai-options)
        _                (when-not preflight-result
                           (dispatch-provider-event!
                            ctx
                            "provider_request_started"
                            {:session-id session-id
                             :turn-id turn-id
                             :provider-request-id turn-id
                             :attempt-id attempt-id
                             :provider provider-id
                             :model-id model-id
                             :retry-attempt retry-attempt}))]
    (assoc (or preflight-result
               (if (= :non-streaming response-mode)
                 (execute-non-streaming-turn! ai-ctx ctx session-id
                                              {:ai-conv ai-conv
                                               :ai-model ai-model
                                               :base-ai-options base-ai-options
                                               :turn-id turn-id})
                 (execute-live-turn! ai-ctx ctx session-id
                                     {:ai-conv         ai-conv
                                      :ai-model        ai-model
                                      :base-ai-options base-ai-options
                                      :progress-queue  progress-queue
                                      :turn-id         turn-id})))
           :preflight-result? (boolean preflight-result)
           :attempt-id attempt-id)))

(defn- cancelled-retry-outcome
  [turn-id failed-attempt next-attempt max-retries retry-enabled? error-fields]
  (merge error-fields
         {:failure-reason :retry-cancelled
          :provider-request-id turn-id
          :turn-id turn-id
          :retry-attempt next-attempt
          :failed-attempt failed-attempt
          :attempt-count (inc failed-attempt)
          :max-retries max-retries
          :retry-enabled? (boolean retry-enabled?)
          :cancelled? true
          :last-error-message (:error-message error-fields)}))

(defn- execution-result
  [ctx session-id prepared-request attempt-data attempt-result retry-outcome]
  (let [turn-id           (:prepared-request/id prepared-request)
        assistant-message (:assistant-message attempt-result)
        outcome           (classify-assistant-message assistant-message)
        retry-outcome*    (not-empty retry-outcome)]
    {:execution-result/turn-id             turn-id
     :execution-result/session-id          session-id
     :execution-result/prepared-request-id turn-id
     :execution-result/model               (:ai-model attempt-data)
     :execution-result/assistant-message   (cond-> assistant-message
                                             retry-outcome* (assoc :retry/outcome retry-outcome*))
     :execution-result/usage               (:usage assistant-message)
     :execution-result/provider-captures   (provider-captures-for-turn ctx session-id turn-id)
     :execution-result/turn-outcome        (:turn/outcome outcome)
     :execution-result/tool-calls          (:tool-calls outcome)
     :execution-result/error-message       (:error-message assistant-message)
     :execution-result/http-status         (:http-status assistant-message)
     :execution-result/stop-reason         (:stop-reason assistant-message)
     :execution-result/logprobs            (:logprobs attempt-result)
     :execution-result/structured-output   (:structured-output attempt-result)
     :execution-result/retry-outcome       retry-outcome*}))

(defn execute-prepared-request!
  "Execute one prepared request through the live turn runtime.
   Returns a shaped execution-result map."
  [ai-ctx ctx session-id prepared-request progress-queue]
  (let [turn-id         (:prepared-request/id prepared-request)
        ai-model        (or (:prepared-request/model prepared-request)
                            (:model (ss/get-session-data-in ctx session-id))
                            (models/get-model :sonnet-4.6))
        attempt-data    {:ai-model ai-model
                         :provider-id (provider-id-for ai-model)
                         :model-id (model-id-for ai-model)
                         :base-ai-options (or (:prepared-request/ai-options prepared-request) {})
                         :response-mode (response-mode-for ctx session-id prepared-request)}
        retry-enabled?  (:auto-retry-enabled (ss/get-session-data-in ctx session-id))
        max-retries     (long (get-in ctx [:config :auto-retry-max-retries] 3))]
    (ss/apply-root-state-update-in!
     ctx
     (ss/session-update session-id #(dissoc % :provider-retry-abort-requested?)))
    (loop [retry-attempt (retry-attempt-for ctx session-id)]
      (let [attempt-data*  (assoc attempt-data :retry-attempt retry-attempt)
            attempt-result (execute-provider-attempt! ai-ctx ctx session-id prepared-request progress-queue attempt-data*)
            assistant-msg  (:assistant-message attempt-result)
            preflight?     (:preflight-result? attempt-result)
            error?         (= :error (:stop-reason assistant-msg))]
        (if-not (and (not preflight?) error?)
          (do
            (when-not preflight?
              (dispatch-provider-event!
               ctx
               "provider_request_finished"
               {:session-id session-id
                :turn-id turn-id
                :provider-request-id turn-id
                :attempt-id (:attempt-id attempt-result)
                :provider (:provider-id attempt-data*)
                :model-id (:model-id attempt-data*)
                :retry-attempt retry-attempt
                :status :succeeded
                :final? true}))
            (clear-active-retry! ctx session-id)
            (execution-result ctx session-id prepared-request attempt-data* attempt-result nil))
          (let [{:keys [retryable? error-kind error-message http-status stop-reason] :as error-fields}
                (provider-error-fields assistant-msg)
                failure-reason (failure-reason-for {:retryable? retryable?
                                                    :retry-enabled? retry-enabled?
                                                    :retry-attempt retry-attempt
                                                    :max-retries max-retries})
                final?         (boolean failure-reason)
                retry-outcome  (cond-> (merge error-fields
                                              {:failure-reason failure-reason
                                               :provider-request-id turn-id
                                               :turn-id turn-id
                                               :retry-attempt retry-attempt
                                               :attempt-count (inc retry-attempt)
                                               :max-retries max-retries
                                               :retry-enabled? (boolean retry-enabled?)
                                               :last-error-message error-message})
                                 (= :retry-exhausted failure-reason) (assoc :exhausted? true))]
            (dispatch-provider-event!
             ctx
             "provider_request_finished"
             (cond-> {:session-id session-id
                      :turn-id turn-id
                      :provider-request-id turn-id
                      :attempt-id (:attempt-id attempt-result)
                      :provider (:provider-id attempt-data*)
                      :model-id (:model-id attempt-data*)
                      :retry-attempt retry-attempt
                      :status :failed
                      :final? final?
                      :retryable? retryable?
                      :error-kind error-kind
                      :stop-reason stop-reason
                      :error-message error-message}
               http-status (assoc :http-status http-status)
               failure-reason (assoc :failure-reason failure-reason)
               (= :retry-exhausted failure-reason) (assoc :exhausted? true)))
            (if final?
              (do
                (clear-active-retry! ctx session-id)
                (execution-result ctx session-id prepared-request attempt-data* attempt-result retry-outcome))
              (let [next-attempt   (inc retry-attempt)
                    retry-metadata (retry-metadata-for ctx assistant-msg retry-attempt)
                    retry-state    (merge retry-metadata
                                          {:failed-attempt retry-attempt
                                           :retry-attempt next-attempt
                                           :error-kind error-kind
                                           :error-message error-message
                                           :http-status http-status})]
                (dispatch-provider-event!
                 ctx
                 "provider_retry_scheduled"
                 (cond-> {:session-id session-id
                          :turn-id turn-id
                          :provider-request-id turn-id
                          :provider (:provider-id attempt-data*)
                          :model-id (:model-id attempt-data*)
                          :failed-attempt retry-attempt
                          :retry-attempt next-attempt
                          :delay-ms (:delay-ms retry-metadata)
                          :delay-source (:delay-source retry-metadata)
                          :resume-at (:resume-at retry-metadata)
                          :rate-limit (:rate-limit retry-metadata)
                          :error-kind error-kind
                          :error-message error-message
                          :retryable? true}
                   http-status (assoc :http-status http-status)))
                (mark-active-retry! ctx session-id retry-state next-attempt)
                (let [cancelled? (sleep-for-retry! ctx session-id (:delay-ms retry-metadata))]
                  (when-not (= false (:provider-retry-sleep? ctx))
                    (clear-active-retry! ctx session-id))
                  (if cancelled?
                    (let [retry-outcome (cancelled-retry-outcome turn-id retry-attempt next-attempt
                                                                 max-retries retry-enabled? error-fields)]
                      (dispatch-provider-event!
                       ctx
                       "provider_request_cancelled"
                       (cond-> {:session-id session-id
                                :turn-id turn-id
                                :provider-request-id turn-id
                                :provider (:provider-id attempt-data*)
                                :model-id (:model-id attempt-data*)
                                :failed-attempt retry-attempt
                                :retry-attempt next-attempt
                                :final? true
                                :cancelled? true
                                :failure-reason :retry-cancelled
                                :retryable? true
                                :error-kind error-kind
                                :error-message error-message}
                         http-status (assoc :http-status http-status)))
                      (execution-result ctx session-id prepared-request attempt-data* attempt-result retry-outcome))
                    (recur next-attempt)))))))))))
