(ns psi.turn-runtime.core
  "Live turn execution runtime.

   Owns prepared-request execution, provider stream consumption, turn-context
   construction, wait/timeout/abort handling, and canonical execution-result
   shaping for one prepared turn."
  (:require
   [psi.ai.core :as ai]
   [psi.ai.model-registry :as model-registry]
   [psi.ai.models :as models]
   [psi.ai.structured-output :as structured-output]
   [psi.ai.textual-tool-calls :as textual-tool-calls]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.accumulator :as accum]
   [psi.turn-runtime.recording :as recording]
   [psi.turn-runtime.retry :as retry]
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
        _                (swap! (:turn-data turn-ctx) assoc :turn-id turn-id :ai-model ai-model)
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
          :error                    (let [headers (or (:provider-error/headers event)
                                                      (:headers event))]
                                      (turn-sc/send-event! turn-ctx :turn/error
                                                           (cond-> {:error-message (:error-message event)}
                                                             (:http-status event) (assoc :http-status (:http-status event))
                                                             headers (assoc :headers headers
                                                                            :provider-error/headers headers))))
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

(defn- streaming-exception-error-data
  [^Throwable t]
  (let [data        (ex-data t)
        headers     (or (:provider-error/headers data) (:headers data))
        http-status (or (:http-status data) (:status data))]
    (cond-> {:error-message (or (ex-message t) "Provider streaming request failed")}
      http-status (assoc :http-status http-status)
      headers     (assoc :headers headers))))

(defn execute-live-turn!
  "Execute one live provider turn against an already prepared conversation.
   Returns {:turn-id :model :assistant-message :ai-options :turn-ctx}."
  [ai-ctx ctx session-id {:keys [ai-conv ai-model base-ai-options progress-queue turn-id]}]
  (let [{:keys [done-p actions-fn turn-ctx last-progress-ms timed-out?]}
        (create-live-turn-context ctx session-id ai-model progress-queue turn-id)
        ai-options      (capture-aware-ai-options ctx session-id turn-id base-ai-options)
        cancelled-pred  #(stream/cancelled-stream-handle? (:stream-handle @(:turn-data turn-ctx)))
        stream-handle   (try
                          (do-stream! ai-ctx ai-conv ai-model ai-options
                                      (make-provider-event-consumer
                                       turn-ctx actions-fn last-progress-ms timed-out?
                                       {:cancelled-pred cancelled-pred
                                        :now-fn stream/now-ms}))
                          (catch Throwable t
                            (turn-sc/send-event! turn-ctx :turn/error
                                                 (streaming-exception-error-data t))
                            nil))
        _               (when stream-handle
                          (stream/mark-turn-stream-handle! turn-ctx stream-handle))
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
       :assistant-message (textual-tool-calls/normalize-assistant-message
                           turn-id ai-model (:assistant-message result))
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

(defn- unsupported-runtime-model-result
  [turn-id ai-model]
  (let [message (model-registry/unsupported-runtime-model-message ai-model)]
    {:turn-id turn-id
     :model ai-model
     :ai-options nil
     :turn-ctx nil
     :assistant-message {:role "assistant"
                         :content [{:type :error :text message}]
                         :stop-reason :error
                         :error-message message
                         :timestamp (java.time.Instant/now)}
     :logprobs nil
     :runtime/unsupported-reason (:runtime/unsupported-reason ai-model)}))

(defn- provider-id-for
  [ai-model]
  (or (some-> ai-model :provider name)
      (some-> ai-model :provider str)
      "unknown"))

(defn- model-id-for
  [ai-model]
  (or (:id ai-model) "unknown"))

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
        attempt-id       (retry/attempt-id-for turn-id retry-attempt)
        preflight-result (or (when (:runtime/unsupported? ai-model)
                               (unsupported-runtime-model-result turn-id ai-model))
                             (unsupported-structured-output-before-generation turn-id ai-model base-ai-options))
        _                (when-not preflight-result
                           (retry/dispatch-provider-event!
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
     :execution-result/runtime-unsupported-reason (:runtime/unsupported-reason attempt-result)
     :execution-result/retry-outcome       retry-outcome*}))

(defn- failed-attempt-finished-event
  "Shared provider_request_finished payload for a failed attempt, used by both
   the immediate-final error-branch dispatch and the truncated-final-sleep
   finalize dispatch of execute-prepared-request!: the two build identical base
   fields (session, turn, attempt, provider, model, retry-attempt, status
   failed, retryability, error fields); only :final? and the failure fields
   (:failure-reason / :exhausted? / :exhausted-reason) differ."
  [session-id turn-id attempt-data* attempt-result retry-attempt
   {:keys [retryable? error-kind stop-reason error-message http-status]}
   final? failure-fields]
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
    (seq failure-fields) (merge failure-fields)))

(defn- schedule-and-sleep!
  "Shared retry scheduling + interruptible sleep used by both the full-delay
   retry branch and the truncated-final-sleep (overshoot) branch of
   execute-prepared-request!: dispatch provider_retry_scheduled, mark the
   active retry (persisting the deadline), optionally run the inter-attempt
   preserve-clear (full-delay path only), sleep `sleep-ms`, and return whether
   the sleep was cancelled."
  [ctx session-id turn-id progress-queue attempt-data retry-attempt next-attempt
   meta error-fields deadline-ms sleep-ms preserve-clear?]
  (let [retry-state (merge meta
                           {:active? true
                            :failed-attempt retry-attempt
                            :retry-attempt next-attempt
                            :error-kind (:error-kind error-fields)
                            :error-message (:error-message error-fields)
                            :http-status (:http-status error-fields)})]
    (retry/dispatch-provider-event!
     ctx
     "provider_retry_scheduled"
     (cond-> {:session-id session-id
              :turn-id turn-id
              :provider-request-id turn-id
              :provider (:provider-id attempt-data)
              :model-id (:model-id attempt-data)
              :failed-attempt retry-attempt
              :retry-attempt next-attempt
              :delay-ms (:delay-ms meta)
              :delay-source (:delay-source meta)
              :resume-at (:resume-at meta)
              :rate-limit (:rate-limit meta)
              :error-kind (:error-kind error-fields)
              :error-message (:error-message error-fields)
              :retryable? true}
       (:http-status error-fields) (assoc :http-status (:http-status error-fields))))
    (retry/mark-active-retry! ctx session-id retry-state next-attempt deadline-ms progress-queue)
    (let [cancelled? (retry/sleep-for-retry! ctx session-id sleep-ms)]
      (when preserve-clear?
        (when-not (= false (:provider-retry-sleep? ctx))
          (retry/clear-active-retry! ctx session-id progress-queue false)))
      cancelled?)))

(defn- cancelled-retry-path!
  "Shared cancellation emission for a retry / final-sleep interrupted before its
   continuation: the cancelled-retry outcome, the unconditional window-close
   clear (incl. deadline), the provider_request_cancelled event, and the shaped
   execution result."
  [ctx session-id turn-id progress-queue prepared-request count-cap retry-enabled?
   attempt-data attempt-result retry-attempt next-attempt error-fields]
  (let [retry-outcome (retry/cancelled-retry-outcome turn-id retry-attempt next-attempt
                                                     count-cap retry-enabled? error-fields)]
    (retry/clear-active-retry! ctx session-id progress-queue true)
    (retry/dispatch-provider-event!
     ctx
     "provider_request_cancelled"
     (cond-> {:session-id session-id
              :turn-id turn-id
              :provider-request-id turn-id
              :provider (:provider-id attempt-data)
              :model-id (:model-id attempt-data)
              :failed-attempt retry-attempt
              :retry-attempt next-attempt
              :final? true
              :cancelled? true
              :failure-reason :retry-cancelled
              :retryable? true
              :error-kind (:error-kind error-fields)
              :error-message (:error-message error-fields)}
       (:http-status error-fields) (assoc :http-status (:http-status error-fields))))
    (execution-result ctx session-id prepared-request attempt-data attempt-result retry-outcome)))

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
        budget-timeout-ms (long (or (get-in ctx [:config :auto-retry-total-timeout-ms]) 0))
        budget-active?  (pos? budget-timeout-ms)
        explicit-cap    (get-in ctx [:config :auto-retry-max-retries])
        count-cap       (cond
                          (some? explicit-cap) explicit-cap
                          (not budget-active?) 3
                          :else nil)]
    (ss/apply-root-state-update-in!
     ctx
     (ss/session-update session-id #(dissoc % :provider-retry-abort-requested?)))
    ;; Read the deadline first: retry-deadline-for's stale branch resets
    ;; :retry-attempt/:retry alongside an expired deadline (and its
    ;; budget-disabled branch clears any leftover future deadline), so the
    ;; attempt read-back must observe the fresh-window state.
    (loop [retry-deadline-ms (retry/retry-deadline-for ctx session-id budget-active?)
           retry-attempt     (retry/retry-attempt-for ctx session-id)
           last-retry-now    nil]
      (let [attempt-data*  (assoc attempt-data :retry-attempt retry-attempt)
            attempt-result (execute-provider-attempt! ai-ctx ctx session-id prepared-request progress-queue attempt-data*)
            assistant-msg  (:assistant-message attempt-result)
            preflight?     (:preflight-result? attempt-result)
            error?         (= :error (:stop-reason assistant-msg))]
        (if-not (and (not preflight?) error?)
          (do
            (when-not preflight?
              (retry/dispatch-provider-event!
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
            (retry/clear-active-retry! ctx session-id progress-queue true)
            (execution-result ctx session-id prepared-request attempt-data* attempt-result nil))
          (let [{:keys [retryable? error-message] :as error-fields}
                (retry/provider-error-fields assistant-msg)
                now              (retry/now-ms ctx)
                retry-metadata   (retry/retry-metadata-for ctx assistant-msg retry-attempt)
                next-delay-ms    (:delay-ms retry-metadata)
                deadline-ms      (or retry-deadline-ms
                                     (when (and retryable? retry-enabled? budget-active?)
                                       (retry/deadline-ms now budget-timeout-ms)))
                decision         (retry/give-up-decision {:retryable? retryable?
                                                          :retry-enabled? retry-enabled?
                                                          :retry-attempt retry-attempt
                                                          :count-cap count-cap
                                                          :deadline-ms deadline-ms
                                                          :next-delay-ms next-delay-ms
                                                          :now now})
                failure-reason   (:failure-reason decision)
                final-sleep-ms   (:final-sleep-ms decision)
                exhausted?       (= :retry-exhausted failure-reason)
                immediate-final? (and (boolean failure-reason) (nil? final-sleep-ms))
                retry-outcome    (cond-> (merge error-fields
                                                {:failure-reason failure-reason
                                                 :provider-request-id turn-id
                                                 :turn-id turn-id
                                                 :retry-attempt retry-attempt
                                                 :attempt-count (inc retry-attempt)
                                                 :max-retries count-cap
                                                 :retry-enabled? (boolean retry-enabled?)
                                                 :last-error-message error-message})
                                   exhausted? (assoc :exhausted? true
                                                     :exhausted-reason (:exhausted-reason decision)))]
            ;; Delay config becomes active only when this enabled, retryable
            ;; failure will schedule a full or truncated retry sleep. Validate
            ;; before emitting the ordinary non-final attempt event; when the
            ;; config is invalid, close the provider lifecycle before rethrowing.
            (when-not immediate-final?
              (try
                (retry/validate-retry-config! ctx)
                (catch clojure.lang.ExceptionInfo error
                  (retry/dispatch-provider-event!
                   ctx
                   "provider_request_finished"
                   (failed-attempt-finished-event
                    session-id turn-id attempt-data* attempt-result retry-attempt
                    error-fields true {}))
                  (throw error))))
            (retry/dispatch-provider-event!
             ctx
             "provider_request_finished"
             (failed-attempt-finished-event
              session-id turn-id attempt-data* attempt-result retry-attempt
              error-fields immediate-final?
              (cond-> {}
                immediate-final? (assoc :failure-reason failure-reason)
                (and immediate-final? exhausted?) (assoc :exhausted? true
                                                         :exhausted-reason (:exhausted-reason decision)))))
            (cond
              ;; Immediate final (no sleep): non-retryable / retry-disabled /
              ;; count-cap / deadline-reached.
              immediate-final?
              (do
                (retry/clear-active-retry! ctx session-id progress-queue true)
                (execution-result ctx session-id prepared-request attempt-data* attempt-result retry-outcome))

              ;; Final-sleep (overshoot): the next full delay would push past the
              ;; deadline, so route the non-final retry path exactly once with the
              ;; truncated remainder (recorded/emitted), sleep it, then finalize
              ;; with the authoritative retry-exhausted :deadline signal.
              (and (boolean failure-reason) (some? final-sleep-ms))
              (let [next-attempt   (inc retry-attempt)
                    truncated-meta (assoc retry-metadata
                                          :delay-ms final-sleep-ms
                                          :resume-at (+ now final-sleep-ms))
                    cancelled?     (schedule-and-sleep! ctx session-id turn-id progress-queue attempt-data*
                                                        retry-attempt next-attempt truncated-meta
                                                        error-fields deadline-ms final-sleep-ms false)]
                (if cancelled?
                  (cancelled-retry-path! ctx session-id turn-id progress-queue prepared-request count-cap
                                         retry-enabled? attempt-data* attempt-result retry-attempt next-attempt
                                         error-fields)
                  (do
                    (retry/dispatch-provider-event!
                     ctx
                     "provider_request_finished"
                     (failed-attempt-finished-event
                      session-id turn-id attempt-data* attempt-result retry-attempt
                      error-fields true
                      {:failure-reason :retry-exhausted
                       :exhausted? true
                       :exhausted-reason :deadline}))
                    (retry/clear-active-retry! ctx session-id progress-queue true)
                    (execution-result ctx session-id prepared-request attempt-data* attempt-result retry-outcome))))

              ;; Retry with the full next delay.
              :else
              (let [next-attempt (inc retry-attempt)]
                ;; Fail fast on a non-advancing-clock hot loop under the
                ;; sleep-disabled, budget-active, cap-free test seam (review
                ;; follow-up, 4th turn): the loop cannot reach its deadline.
                (retry/assert-test-seam-no-hot-loop! ctx budget-active? count-cap last-retry-now now)
                (let [cancelled? (schedule-and-sleep! ctx session-id turn-id progress-queue attempt-data*
                                                      retry-attempt next-attempt retry-metadata
                                                      error-fields deadline-ms (:delay-ms retry-metadata) true)]
                  (if cancelled?
                    (cancelled-retry-path! ctx session-id turn-id progress-queue prepared-request count-cap
                                           retry-enabled? attempt-data* attempt-result retry-attempt next-attempt
                                           error-fields)
                    (recur deadline-ms next-attempt now)))))))))))
