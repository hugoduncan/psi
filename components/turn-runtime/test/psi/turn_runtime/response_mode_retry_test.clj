(ns psi.turn-runtime.response-mode-retry-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.core :as turn-runtime]))

(defn- create-session-context
  [opts]
  (let [ctx (session/create-context (test-support/safe-context-opts opts))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(defn- prepared-request
  [ctx session-id]
  (let [turn-id "turn-1"
        augmentation-record {:session-id session-id
                             :turn-id turn-id
                             :workflow-run-id nil
                             :status :no-op
                             :replay? false
                             :accepted-operation-count 0
                             :operations []
                             :providers []}]
    (swap! (:state* ctx) assoc-in
           [:agent-session :sessions session-id :data :turn-augmentations turn-id]
           augmentation-record)
    (prompt-request/build-prepared-request
     ctx session-id {:turn-id turn-id
                     :user-message {:role "user"
                                    :content [{:type :text :text "hello"}]}
                     :runtime-model (:model (ss/get-session-data-in ctx session-id))})))

(defn- provider-events
  [ctx session-id]
  (get-in @(:state* ctx) [:agent-session :sessions session-id :telemetry :provider-events]))

(defn- error-turn
  [message]
  {:turn-id "turn-1"
   :model {:provider "openai" :id "gpt-test"}
   :ai-options {}
   :turn-ctx nil
   :assistant-message {:role "assistant"
                       :content [{:type :error :text message}]
                       :stop-reason :error
                       :error-message message
                       :timestamp (java.time.Instant/now)}})

(deftest execute-prepared-request-streaming-retry-discards-failed-partial-output-test
  ;; Failed streaming-attempt partial output is attempt-local; the successful
  ;; retry owns the final assistant content.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :provider-retry-sleep? false
                                                  :config {:auto-retry-max-retries 1}})
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (with-redefs [psi.turn-runtime.core/do-stream!
                  (fn [_ai-ctx _conv _model _opts consume-fn]
                    (if (= 1 (swap! attempts* inc))
                      (do
                        (consume-fn {:type :start})
                        (consume-fn {:type :text-delta :content-index 0 :delta "partial failed "})
                        (consume-fn {:type :error
                                     :error-message "Connection reset by peer"}))
                      (do
                        (consume-fn {:type :start})
                        (consume-fn {:type :text-delta :content-index 0 :delta "final answer"})
                        (consume-fn {:type :done :reason :stop}))))]
      (let [result (turn-runtime/execute-prepared-request!
                    {:provider-registry (atom {})} ctx session-id prepared nil)]
        (is (= 2 @attempts*))
        (is (= :stop (:execution-result/stop-reason result)))
        (is (= [{:type :text :text "final answer"}]
               (get-in result [:execution-result/assistant-message :content])))
        (is (not (re-find #"partial failed"
                          (pr-str (:execution-result/assistant-message result)))))
        (is (= ["provider_request_started" "provider_request_finished"
                "provider_retry_scheduled" "provider_request_started"
                "provider_request_finished"]
               (mapv :type (provider-events ctx session-id))))))))

(deftest execute-prepared-request-terminal-provider-error-is-not-retried-test
  ;; Terminal provider/client failures are classified and returned without retry scheduling.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :provider-retry-sleep? false})
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (assoc (error-turn "invalid api key")
                           :assistant-message {:role "assistant"
                                               :content [{:type :error :text "invalid api key"}]
                                               :stop-reason :error
                                               :error-message "invalid api key"
                                               :http-status 401
                                               :timestamp (java.time.Instant/now)}))]
      (let [result  (turn-runtime/execute-prepared-request!
                     {:provider-registry (atom {})} ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)]
        (is (= 1 @attempts*))
        (is (= :non-retryable (:failure-reason outcome)))
        (is (= :auth (:error-kind outcome)))
        (is (false? (:retryable? outcome)))
        (is (= :non-retryable
               (get-in result [:execution-result/assistant-message :retry/outcome :failure-reason])))
        (is (= ["provider_request_started" "provider_request_finished"]
               (mapv :type (provider-events ctx session-id))))
        (is (nil? (:retry (ss/get-session-data-in ctx session-id))))))))

(deftest execute-prepared-request-unknown-provider-error-is-not-retried-test
  ;; Unknown provider failures use the conservative terminal/non-retryable default.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :provider-retry-sleep? false})
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (error-turn "mysterious provider failure"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     {:provider-registry (atom {})} ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)]
        (is (= 1 @attempts*))
        (is (= :non-retryable (:failure-reason outcome)))
        (is (= :unknown (:error-kind outcome)))
        (is (false? (:retryable? outcome)))
        (is (= ["provider_request_started" "provider_request_finished"]
               (mapv :type (provider-events ctx session-id))))))))

(deftest execute-prepared-request-openai-usage-limit-schedules-retry-test
  ;; OpenAI usage-limit wording is a rate-limit failure even if the provider
  ;; stream failed to preserve a numeric HTTP status.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :provider-retry-sleep? false
                                                  :config {:auto-retry-base-delay-ms 10
                                                           :auto-retry-max-retries 1}})
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (if (= 1 (swap! attempts* inc))
                      (error-turn "The usage limit has been reached [request-id req_123]")
                      {:turn-id "turn-1"
                       :model {:provider "openai" :id "gpt-test"}
                       :ai-options {}
                       :turn-ctx nil
                       :assistant-message {:role "assistant"
                                           :content [{:type :text :text "recovered"}]
                                           :stop-reason :stop
                                           :timestamp (java.time.Instant/now)}}))]
      (let [result    (turn-runtime/execute-prepared-request!
                       {:provider-registry (atom {})} ctx session-id prepared nil)
            scheduled (first (filter #(= "provider_retry_scheduled" (:type %))
                                     (provider-events ctx session-id)))]
        (is (= 2 @attempts*))
        (is (= :stop (:execution-result/stop-reason result)))
        (is (= :rate-limit (:error-kind scheduled)))
        (is (= "The usage limit has been reached [request-id req_123]"
               (:error-message scheduled)))
        (is (nil? (:http-status scheduled)))))))

(deftest execute-prepared-request-retry-disabled-classifies-without-scheduling-test
  ;; Disabled retry records one failed attempt and exposes a skipped-retry outcome.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :provider-retry-sleep? false
                                                  :config {:auto-retry-max-retries 3}})
        _               (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data]
                               (assoc (ss/get-session-data-in ctx session-id)
                                      :auto-retry-enabled false))
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     {:provider-registry (atom {})} ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)]
        (is (= 1 @attempts*))
        (is (= :retry-disabled (:failure-reason outcome)))
        (is (= :transport (:error-kind outcome)))
        (is (true? (:retryable? outcome)))
        (is (false? (:retry-enabled? outcome)))
        (is (= 1 (:attempt-count outcome)))
        (is (= 0 (:retry-attempt outcome)))
        (is (= 3 (:max-retries outcome)))
        (is (= ["provider_request_started" "provider_request_finished"]
               (mapv :type (provider-events ctx session-id))))
        (is (nil? (:retry (ss/get-session-data-in ctx session-id))))))))

(deftest execute-prepared-request-total-time-window-governs-termination-test
  ;; The total-time window (sentinel-nil count cap, budget active) bounds the
  ;; retry loop: termination happens at the injected-clock deadline, not at a
  ;; default count cap, and the final sleep is truncated to the remaining window.
  (let [clock          (atom 0)
        [ctx0 session-id] (create-session-context {:persist? false
                                                   :config {:auto-retry-total-timeout-ms 5000
                                                            :auto-retry-base-delay-ms 2000
                                                            :auto-retry-max-delay-ms 60000}})
        ctx            (assoc ctx0
                              :now-fn #(java.time.Instant/ofEpochMilli @clock)
                              :provider-retry-sleep-fn (fn [delay-ms]
                                                         (swap! clock + (long delay-ms))))
        prepared       (prepared-request ctx session-id)
        attempts*      (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     {:provider-registry (atom {})} ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)
            events  (provider-events ctx session-id)
            scheduled (filter #(= "provider_retry_scheduled" (:type %)) events)]
        (is (= 2 @attempts*))
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (= :deadline (:exhausted-reason outcome)))
        (is (true? (:exhausted? outcome)))
        ;; budget-active default path has no count limiter to report
        (is (nil? (:max-retries outcome)))
        ;; full first sleep, then truncated final sleep to the deadline (5000)
        (is (= [2000 3000] (mapv :delay-ms scheduled)))
        (is (= [2000 5000] (mapv :resume-at scheduled)))
        (is (= :exponential-backoff (:delay-source (last scheduled))))
        ;; authoritative terminal event after the truncated sleep
        (is (= :retry-exhausted (:failure-reason (last events))))
        (is (= :deadline (:exhausted-reason (last events))))
        (is (true? (:final? (last events))))
        (is (nil? (:retry (ss/get-session-data-in ctx session-id))))
        (is (nil? (:retry-deadline-ms (ss/get-session-data-in ctx session-id))))))))

(deftest execute-prepared-request-explicit-count-cap-still-bounds-test
  ;; An explicitly configured small :auto-retry-max-retries remains a hard cap
  ;; even with the total-time budget active (:exhausted-reason :count-cap, no
  ;; truncated final sleep).
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :provider-retry-sleep? false
                                                  :config {:auto-retry-max-retries 1}})
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     {:provider-registry (atom {})} ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)
            events  (provider-events ctx session-id)]
        (is (= 2 @attempts*))
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (= :count-cap (:exhausted-reason outcome)))
        (is (= 1 (:max-retries outcome)))
        (is (= :retry-exhausted (:failure-reason (last events))))
        (is (= :count-cap (:exhausted-reason (last events))))))))

(deftest execute-prepared-request-count-only-fallback-three-test
  ;; Budget disabled (total-timeout <= 0) with no explicit cap uses the preserved
  ;; count-only fallback of 3 as the sole give-up limiter.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :provider-retry-sleep? false
                                                  :config {:auto-retry-total-timeout-ms 0}})
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     {:provider-registry (atom {})} ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)]
        (is (= 4 @attempts*))
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (= :count-cap (:exhausted-reason outcome)))
        (is (= 3 (:max-retries outcome)))))))

(deftest execute-prepared-request-retry-after-deadline-bounded-test
  ;; A provider Retry-After delay is respected per attempt but an oversized one is
  ;; truncated to the remaining window: give-up :deadline at the deadline.
  (let [clock          (atom 0)
        [ctx0 session-id] (create-session-context {:persist? false
                                                   :config {:auto-retry-total-timeout-ms 5000}})
        ctx            (assoc ctx0
                              :now-fn #(java.time.Instant/ofEpochMilli @clock)
                              :provider-retry-sleep-fn (fn [delay-ms]
                                                         (swap! clock + (long delay-ms))))
        prepared       (prepared-request ctx session-id)
        attempts*      (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (assoc (error-turn "rate limit exceeded")
                           :assistant-message {:role "assistant"
                                               :content [{:type :error :text "rate limit exceeded"}]
                                               :stop-reason :error
                                               :error-message "rate limit exceeded"
                                               :http-status 429
                                               :provider-error/headers {"Retry-After" "10"}
                                               :timestamp (java.time.Instant/now)}))]
      (let [result    (turn-runtime/execute-prepared-request!
                       {:provider-registry (atom {})} ctx session-id prepared nil)
            outcome   (:execution-result/retry-outcome result)
            events    (provider-events ctx session-id)
            scheduled (first (filter #(= "provider_retry_scheduled" (:type %)) events))]
        (is (= 1 @attempts*))
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (= :deadline (:exhausted-reason outcome)))
        (is (= :retry-after (:delay-source scheduled)))
        ;; oversized 10s Retry-After truncated to the 5s remaining window
        (is (= 5000 (:delay-ms scheduled)))
        (is (= 5000 (:resume-at scheduled)))
        (is (= :retry-exhausted (:failure-reason (last events))))
        (is (= :deadline (:exhausted-reason (last events))))
        (is (true? (:final? (last events))))))))

(deftest execute-prepared-request-cancel-clears-deadline-test
  ;; Cancellation during a pending backoff returns :retry-cancelled and clears the
  ;; window deadline via its own unconditional clear (not the per-sleep preserve).
  (let [cancelled?      (atom false)
        [ctx0 session-id] (create-session-context {:persist? false
                                                   :config {:auto-retry-total-timeout-ms 600000}})
        ctx             (assoc ctx0
                               :provider-retry-cancelled? (fn [_session-id] @cancelled?)
                               :provider-retry-sleep-fn
                               (fn [_delay-ms]
                                 (reset! cancelled? true)))
        prepared        (prepared-request ctx session-id)
        attempts*       (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     {:provider-registry (atom {})} ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)]
        (is (= :retry-cancelled (:failure-reason outcome)))
        (is (true? (:cancelled? outcome)))
        (is (nil? (:retry (ss/get-session-data-in ctx session-id))))
        (is (nil? (:retry-deadline-ms (ss/get-session-data-in ctx session-id))))))))

(deftest execute-prepared-request-deadline-preserved-inter-attempt-test
  ;; The window deadline survives the inter-attempt (per-sleep) clear: it is
  ;; present in canonical state during a retry sleep, and a later success window
  ;; close clears it.
  (let [deadline-in-sleep* (atom nil)
        [ctx0 session-id] (create-session-context {:persist? false
                                                   :config {:auto-retry-total-timeout-ms 600000}})
        ctx             (assoc ctx0
                               :now-fn #(java.time.Instant/ofEpochMilli 0)
                               :provider-retry-sleep-fn
                               (fn [_delay-ms]
                                 (reset! deadline-in-sleep*
                                         (:retry-deadline-ms (ss/get-session-data-in ctx0 session-id)))))
        prepared        (prepared-request ctx session-id)
        attempts*       (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (if (= 1 (swap! attempts* inc))
                      (error-turn "Connection reset by peer")
                      {:turn-id "turn-1"
                       :model {:provider "openai" :id "gpt-test"}
                       :ai-options {}
                       :turn-ctx nil
                       :assistant-message {:role "assistant"
                                           :content [{:type :text :text "recovered"}]
                                           :stop-reason :stop
                                           :timestamp (java.time.Instant/now)}}))]
      (let [result (turn-runtime/execute-prepared-request!
                    {:provider-registry (atom {})} ctx session-id prepared nil)]
        (is (= 600000 @deadline-in-sleep*))
        (is (= :stop (:execution-result/stop-reason result)))
        (is (nil? (:retry-deadline-ms (ss/get-session-data-in ctx session-id))))))))

(deftest execute-prepared-request-stale-deadline-at-loop-entry-opens-fresh-window-test
  ;; A persisted :retry-deadline-ms already in the past at loop entry is treated
  ;; as stale and cleared, so the first retryable failure opens a fresh window
  ;; instead of an instant :deadline give-up with zero retries.
  (let [clock           (atom 5000)
        [ctx0 session-id] (create-session-context {:persist? false
                                                   :config {:auto-retry-total-timeout-ms 5000
                                                            :auto-retry-base-delay-ms 2000}})
        _               (swap! (:state* ctx0) assoc-in
                               [:agent-session :sessions session-id :data]
                               (assoc (ss/get-session-data-in ctx0 session-id)
                                      :retry-deadline-ms 1000)) ;; past (now 5000)
        ctx             (assoc ctx0
                               :now-fn #(java.time.Instant/ofEpochMilli @clock)
                               :provider-retry-sleep-fn (fn [delay-ms]
                                                          (swap! clock + (long delay-ms))))
        prepared        (prepared-request ctx session-id)
        attempts*       (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     {:provider-registry (atom {})} ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)]
        ;; fresh window at now 5000 + 5000 = 10000; attempt 0 retries (2000),
        ;; attempt 1 final-sleeps the remainder (3000) at the deadline
        (is (= 2 @attempts*))
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (= :deadline (:exhausted-reason outcome)))
        (is (nil? (:retry-deadline-ms (ss/get-session-data-in ctx session-id))))))))

(deftest execute-prepared-request-non-positive-retry-after-floors-to-backoff-test
  ;; A provider Retry-After of 0 (or negative integer) is floored to the
  ;; exponential backoff under the budget-active default: the count-cap is nil so
  ;; there is no immediate give-up, and the loop must not retry back-to-back with
  ;; an immediate 0-delay until the deadline.
  (let [clock          (atom 0)
        [ctx0 session-id] (create-session-context {:persist? false
                                                   :config {:auto-retry-total-timeout-ms 600000
                                                            :auto-retry-base-delay-ms 2000
                                                            :auto-retry-max-delay-ms 60000}})
        ctx            (assoc ctx0
                              :now-fn #(java.time.Instant/ofEpochMilli @clock)
                              :provider-retry-sleep-fn (fn [delay-ms]
                                                         (swap! clock + (long delay-ms))))
        prepared       (prepared-request ctx session-id)
        attempts*      (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (assoc (error-turn "rate limit exceeded")
                           :assistant-message {:role "assistant"
                                               :content [{:type :error :text "rate limit exceeded"}]
                                               :stop-reason :error
                                               :error-message "rate limit exceeded"
                                               :http-status 429
                                               :provider-error/headers {"Retry-After" "0"}
                                               :timestamp (java.time.Instant/now)}))]
      (let [result    (turn-runtime/execute-prepared-request!
                       {:provider-registry (atom {})} ctx session-id prepared nil)
            outcome   (:execution-result/retry-outcome result)
            events    (provider-events ctx session-id)
            scheduled (first (filter #(= "provider_retry_scheduled" (:type %)) events))]
        ;; Retry-After 0 floors to the exponential backoff, not an immediate 0-delay retry
        (is (= :exponential-backoff (:delay-source scheduled)))
        (is (= 2000 (:delay-ms scheduled)))
        (is (= 2000 (:resume-at scheduled)))
        ;; still budget-active default: give-up happens at the deadline, not a count cap
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (= :deadline (:exhausted-reason outcome)))
        (is (nil? (:max-retries outcome)))))))

(deftest execute-prepared-request-cancel-during-truncated-final-sleep-test
  ;; Cancellation arriving during the truncated final sleep (overshoot path)
  ;; returns :retry-cancelled, emits the truncated provider_retry_scheduled then
  ;; provider_request_cancelled, and clears the window deadline (plan test 6).
  (let [clock          (atom 0)
        sleep-calls*   (atom 0)
        cancelled?     (atom false)
        [ctx0 session-id] (create-session-context {:persist? false
                                                   :config {:auto-retry-total-timeout-ms 5000
                                                            :auto-retry-base-delay-ms 2000
                                                            :auto-retry-max-delay-ms 60000}})
        ctx            (assoc ctx0
                              :now-fn #(java.time.Instant/ofEpochMilli @clock)
                              :provider-retry-cancelled? (fn [_session-id] @cancelled?)
                              :provider-retry-sleep-fn
                              (fn [delay-ms]
                                (swap! sleep-calls* inc)
                                (swap! clock + (long delay-ms))
                                ;; flip cancellation during the truncated final sleep (2nd)
                                (when (= 2 @sleep-calls*)
                                  (reset! cancelled? true))))
        prepared       (prepared-request ctx session-id)
        attempts*      (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (error-turn "Connection reset by peer"))]
      (let [result    (turn-runtime/execute-prepared-request!
                       {:provider-registry (atom {})} ctx session-id prepared nil)
            outcome   (:execution-result/retry-outcome result)
            events    (provider-events ctx session-id)
            scheduled (filter #(= "provider_retry_scheduled" (:type %)) events)]
        (is (= 2 @attempts*))
        (is (= :retry-cancelled (:failure-reason outcome)))
        (is (true? (:cancelled? outcome)))
        ;; full first backoff (2000), then the truncated final sleep (5000 - 2000)
        (is (= [2000 3000] (mapv :delay-ms scheduled)))
        ;; the truncated scheduled signal is superseded by the cancel event
        (is (= "provider_request_cancelled" (:type (last events))))
        (is (true? (:final? (last events))))
        (is (= :retry-cancelled (:failure-reason (last events))))
        ;; no stale retry state / window deadline after cancel
        (is (nil? (:retry (ss/get-session-data-in ctx session-id))))
        (is (nil? (:retry-deadline-ms (ss/get-session-data-in ctx session-id))))))))
