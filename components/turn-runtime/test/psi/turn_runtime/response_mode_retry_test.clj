(ns psi.turn-runtime.response-mode-retry-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.retry-provider-test-support :as retry-provider]
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
  ;; retry owns the final assistant content. The :provider-retry-sleep? seam
  ;; flag flows through create-session-context opts (propagated to the ctx by
  ;; create-context*), so the test pays no real backoff time.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :config {:auto-retry-max-retries 1}
                                                  :provider-retry-sleep? false})
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
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (assoc (error-turn "invalid api key")
                                                            :assistant-message {:role "assistant"
                                                                                :content [{:type :error :text "invalid api key"}]
                                                                                :stop-reason :error
                                                                                :error-message "invalid api key"
                                                                                :http-status 401
                                                                                :timestamp (java.time.Instant/now)}))]
      (let [result  (turn-runtime/execute-prepared-request!
                     ai-ctx ctx session-id prepared nil)
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
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (error-turn "mysterious provider failure"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     ai-ctx ctx session-id prepared nil)
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
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
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
                       ai-ctx ctx session-id prepared nil)
            scheduled (first (filter #(= "provider_retry_scheduled" (:type %))
                                     (provider-events ctx session-id)))]
        (is (= 2 @attempts*))
        (is (= :stop (:execution-result/stop-reason result)))
        (is (= :rate-limit (:error-kind scheduled)))
        (is (= "The usage limit has been reached [request-id req_123]"
               (:error-message scheduled)))
        (is (nil? (:http-status scheduled)))))))

(deftest execute-prepared-request-retry-disabled-classifies-without-scheduling-test
  ;; Disabled retry ignores invalid retry delays and returns a skipped-retry outcome.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :provider-retry-sleep? false
                                                  :config {:auto-retry-max-retries 3
                                                           :auto-retry-base-delay-ms 0}})
        _               (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data]
                               (assoc (ss/get-session-data-in ctx session-id)
                                      :auto-retry-enabled false))
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     ai-ctx ctx session-id prepared nil)
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
  ;; loop: termination at the injected-clock deadline, final sleep truncated.
  (let [clock          (atom 0)
        [ctx session-id] (create-session-context {:persist? false
                                                  :config {:auto-retry-total-timeout-ms 5000
                                                           :auto-retry-base-delay-ms 2000
                                                           :auto-retry-max-delay-ms 60000}
                                                  :now-fn #(java.time.Instant/ofEpochMilli @clock)
                                                  :provider-retry-sleep-fn (fn [delay-ms]
                                                                             (swap! clock + (long delay-ms)))})
        prepared       (prepared-request ctx session-id)
        attempts*      (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     ai-ctx ctx session-id prepared nil)
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
  ;; even with the budget active (:exhausted-reason :count-cap, no truncated
  ;; final sleep); the :provider-retry-sleep? seam avoids real backoff time.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :config {:auto-retry-max-retries 1}
                                                  :provider-retry-sleep? false})
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     ai-ctx ctx session-id prepared nil)
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
  ;; count-only fallback of 3 as the sole give-up limiter; the
  ;; :provider-retry-sleep? seam avoids real backoff time.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :config {:auto-retry-total-timeout-ms 0}
                                                  :provider-retry-sleep? false})
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     ai-ctx ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)]
        (is (= 4 @attempts*))
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (= :count-cap (:exhausted-reason outcome)))
        (is (= 3 (:max-retries outcome)))))))

(deftest execute-prepared-request-budget-disabled-ignores-leftover-future-deadline-test
  ;; Count-only mode (budget disabled) must not be deadline-bounded by a leftover
  ;; FUTURE canonical :retry-deadline-ms from a prior budget-active window
  ;; (rehydrated with the budget disabled). Without the loop-entry budget-active?
  ;; gate, the persisted future deadline binds count-only mode (overshoot gives
  ;; up :deadline after 1 attempt); with the gate it is cleared at entry. The
  ;; stale :retry-attempt/:retry residue resets alongside (8th-turn follow-up).
  (let [clock          (atom 0)
        [ctx session-id] (create-session-context {:persist? false
                                                  :config {:auto-retry-total-timeout-ms 0
                                                           :auto-retry-base-delay-ms 2000}
                                                  :provider-retry-sleep? false
                                                  :now-fn #(java.time.Instant/ofEpochMilli @clock)})
        _              (swap! (:state* ctx) assoc-in
                              [:agent-session :sessions session-id :data]
                              ;; future-but-close deadline (1000) + stale attempt
                              ;; 3 / :retry residue: without the fix, the 2000 ms
                              ;; backoff overshoots → :deadline, attempt 3 >= 3
                              (assoc (ss/get-session-data-in ctx session-id)
                                     :retry-deadline-ms 1000
                                     :retry-attempt 3
                                     :retry {:resume-at 999999
                                             :delay-ms 8000
                                             :delay-source :exponential-backoff}))
        prepared       (prepared-request ctx session-id)
        attempts*      (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     ai-ctx ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)
            sd      (ss/get-session-data-in ctx session-id)]
        ;; count-only fallback 3 bounds the loop: 1 initial + 3 retries (the
        ;; stale attempt 3 residue was reset to 0 at entry, not honored)
        (is (= 4 @attempts*))
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (= :count-cap (:exhausted-reason outcome)))
        (is (= 3 (:max-retries outcome)))
        ;; the leftover future deadline AND the stale attempt/resume residue
        ;; were cleared at loop entry, not bound / not visible
        (is (nil? (:retry-deadline-ms sd)))
        (is (zero? (:retry-attempt sd)))
        (is (nil? (:retry sd)))))))

(deftest execute-prepared-request-retry-after-deadline-bounded-test
  ;; A provider Retry-After delay is respected per attempt but an oversized one is
  ;; truncated to the remaining window: give-up :deadline at the deadline.
  (let [clock          (atom 0)
        [ctx session-id] (create-session-context {:persist? false
                                                  :config {:auto-retry-total-timeout-ms 5000}
                                                  :now-fn #(java.time.Instant/ofEpochMilli @clock)
                                                  :provider-retry-sleep-fn (fn [delay-ms]
                                                                             (swap! clock + (long delay-ms)))})
        prepared       (prepared-request ctx session-id)
        attempts*      (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
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
                       ai-ctx ctx session-id prepared nil)
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
  ;; Cancellation during a pending backoff returns :retry-cancelled and clears
  ;; the deadline via its own unconditional clear (not the per-sleep preserve).
  (let [cancelled?      (atom false)
        [ctx session-id] (create-session-context {:persist? false
                                                  :config {:auto-retry-total-timeout-ms 600000}
                                                  :provider-retry-cancelled? (fn [_session-id] @cancelled?)
                                                  :provider-retry-sleep-fn
                                                  (fn [_delay-ms]
                                                    (reset! cancelled? true))})
        prepared        (prepared-request ctx session-id)
        attempts*       (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     ai-ctx ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)]
        (is (= :retry-cancelled (:failure-reason outcome)))
        (is (true? (:cancelled? outcome)))
        (is (nil? (:retry (ss/get-session-data-in ctx session-id))))
        (is (nil? (:retry-deadline-ms (ss/get-session-data-in ctx session-id))))))))

(deftest execute-prepared-request-deadline-preserved-inter-attempt-test
  ;; The window deadline survives the inter-attempt (per-sleep) clear: present
  ;; during a retry sleep, cleared by a later success window close. The
  ;; :provider-retry-sleep-fn must close over the ctx (opts are evaluated before
  ;; the ctx exists), so it is assoc'd after creation; :now-fn flows via opts.
  (let [deadline-in-sleep* (atom nil)
        [ctx0 session-id] (create-session-context {:persist? false
                                                   :config {:auto-retry-total-timeout-ms 600000}
                                                   :now-fn #(java.time.Instant/ofEpochMilli 0)})
        ctx             (assoc ctx0
                               :provider-retry-sleep-fn
                               (fn [_delay-ms]
                                 (reset! deadline-in-sleep*
                                         (:retry-deadline-ms (ss/get-session-data-in ctx0 session-id)))))
        prepared        (prepared-request ctx session-id)
        attempts*       (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
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
                    ai-ctx ctx session-id prepared nil)]
        (is (= 600000 @deadline-in-sleep*))
        (is (= :stop (:execution-result/stop-reason result)))
        (is (nil? (:retry-deadline-ms (ss/get-session-data-in ctx session-id))))))))

(deftest execute-prepared-request-stale-deadline-at-loop-entry-opens-fresh-window-test
  ;; A persisted :retry-deadline-ms already past at loop entry is stale:
  ;; cleared, so the first retryable failure opens a fresh window instead of an
  ;; instant :deadline give-up. The expired window's :retry-attempt/:retry reset
  ;; alongside (process death during a retry sleep leaves attempt > 0 and a
  ;; stale :retry map), so the fresh window starts at attempt 0.
  (let [clock           (atom 5000)
        [ctx session-id] (create-session-context {:persist? false
                                                  :config {:auto-retry-total-timeout-ms 5000
                                                           :auto-retry-base-delay-ms 2000}
                                                  :now-fn #(java.time.Instant/ofEpochMilli @clock)
                                                  :provider-retry-sleep-fn (fn [delay-ms]
                                                                             (swap! clock + (long delay-ms)))})
        _               (swap! (:state* ctx) assoc-in
                               [:agent-session :sessions session-id :data]
                               ;; stale mid-window state: deadline past (now 5000),
                               ;; attempt 3 with a stale :retry map left by a
                               ;; process death during a retry sleep
                               (assoc (ss/get-session-data-in ctx session-id)
                                      :retry-deadline-ms 1000
                                      :retry-attempt 3
                                      :retry {:delay-ms 16000
                                              :delay-source :exponential-backoff
                                              :resume-at 20000}))
        prepared        (prepared-request ctx session-id)
        attempts*       (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (error-turn "Connection reset by peer"))]
      (let [result    (turn-runtime/execute-prepared-request!
                       ai-ctx ctx session-id prepared nil)
            outcome   (:execution-result/retry-outcome result)
            events    (provider-events ctx session-id)
            scheduled (filter #(= "provider_retry_scheduled" (:type %)) events)]
        ;; fresh window at now 5000 + 5000 = 10000; attempt 0 retries (2000),
        ;; attempt 1 final-sleeps the remainder (3000) at the deadline — NOT
        ;; resuming at the stale attempt 3 (which would immediately overshoot
        ;; with a 16000 next-delay and give up after a single attempt)
        (is (= 2 @attempts*))
        (is (= [2000 3000] (mapv :delay-ms scheduled)))
        (is (= [7000 10000] (mapv :resume-at scheduled)))
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (= :deadline (:exhausted-reason outcome)))
        ;; no stale retry metadata / window deadline after the run
        (is (nil? (:retry-deadline-ms (ss/get-session-data-in ctx session-id))))
        (is (nil? (:retry (ss/get-session-data-in ctx session-id))))))))

(deftest execute-prepared-request-non-positive-retry-after-floors-to-backoff-test
  ;; A provider Retry-After of 0 (or negative integer) floors to the
  ;; exponential backoff under the budget-active default (count-cap nil, no
  ;; immediate give-up) — never an immediate 0-delay back-to-back retry.
  (let [clock          (atom 0)
        [ctx session-id] (create-session-context {:persist? false
                                                  :config {:auto-retry-total-timeout-ms 600000
                                                           :auto-retry-base-delay-ms 2000
                                                           :auto-retry-max-delay-ms 60000}
                                                  :now-fn #(java.time.Instant/ofEpochMilli @clock)
                                                  :provider-retry-sleep-fn (fn [delay-ms]
                                                                             (swap! clock + (long delay-ms)))})
        prepared       (prepared-request ctx session-id)
        attempts*      (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
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
                       ai-ctx ctx session-id prepared nil)
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

(deftest execute-prepared-request-oversized-retry-after-floors-to-backoff-test
  ;; A numeric Retry-After outside Long range (e.g. a 20-digit value) is
  ;; unparsable and must not crash the turn: it floors to the exponential
  ;; backoff and the window still runs to :deadline — no NumberFormatException.
  (let [clock          (atom 0)
        [ctx session-id] (create-session-context {:persist? false
                                                  :config {:auto-retry-base-delay-ms 2000
                                                           :auto-retry-max-delay-ms 60000}
                                                  :now-fn #(java.time.Instant/ofEpochMilli @clock)
                                                  :provider-retry-sleep-fn (fn [delay-ms]
                                                                             (swap! clock + (long delay-ms)))})
        prepared       (prepared-request ctx session-id)
        attempts*      (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (assoc (error-turn "rate limit exceeded")
                                                            :assistant-message {:role "assistant"
                                                                                :content [{:type :error :text "rate limit exceeded"}]
                                                                                :stop-reason :error
                                                                                :error-message "rate limit exceeded"
                                                                                :http-status 429
                                                                                :provider-error/headers {"Retry-After" "99999999999999999999"}
                                                                                :timestamp (java.time.Instant/now)}))]
      (let [result    (turn-runtime/execute-prepared-request!
                       ai-ctx ctx session-id prepared nil)
            outcome   (:execution-result/retry-outcome result)
            events    (provider-events ctx session-id)
            scheduled (first (filter #(= "provider_retry_scheduled" (:type %)) events))]
        ;; oversized Retry-After floors to the exponential backoff, no throw
        (is (= :exponential-backoff (:delay-source scheduled)))
        (is (= 2000 (:delay-ms scheduled)))
        (is (= 2000 (:resume-at scheduled)))
        ;; budget-active default (timeout/cap keys omitted -> 600000 / nil):
        ;; give-up happens at the deadline, not a count cap
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (= :deadline (:exhausted-reason outcome)))
        (is (nil? (:max-retries outcome)))))))

(deftest execute-prepared-request-near-long-retry-after-floors-to-backoff-test
  ;; A PARSEABLE near-Long/MAX integer Retry-After (16 digits, seconds >=
  ;; 9223372036854775) previously crashed the turn with an uncaught
  ;; ArithmeticException: the `* 1000` overflowed, and the largest fitting
  ;; product overflowed `:resume-at`. It now floors to the exponential backoff
  ;; and the window still runs to :deadline — no crash.
  (doseq [retry-after ["9223372036854775" "9223372036854776"]]
    (let [clock          (atom 0)
          [ctx session-id] (create-session-context {:persist? false
                                                    :config {:auto-retry-base-delay-ms 2000
                                                             :auto-retry-max-delay-ms 60000}
                                                    :now-fn #(java.time.Instant/ofEpochMilli @clock)
                                                    :provider-retry-sleep-fn (fn [delay-ms]
                                                                               (swap! clock + (long delay-ms)))})
          prepared       (prepared-request ctx session-id)
          attempts*      (atom 0)]
      (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                       (swap! attempts* inc)
                                                       (assoc (error-turn "rate limit exceeded")
                                                              :assistant-message {:role "assistant"
                                                                                  :content [{:type :error :text "rate limit exceeded"}]
                                                                                  :stop-reason :error
                                                                                  :error-message "rate limit exceeded"
                                                                                  :http-status 429
                                                                                  :provider-error/headers {"Retry-After" retry-after}
                                                                                  :timestamp (java.time.Instant/now)}))]
        (let [result    (turn-runtime/execute-prepared-request!
                         ai-ctx ctx session-id prepared nil)
              outcome   (:execution-result/retry-outcome result)
              events    (provider-events ctx session-id)
              scheduled (first (filter #(= "provider_retry_scheduled" (:type %)) events))]
          ;; near-Long Retry-After floors to the exponential backoff, no throw
          (is (= :exponential-backoff (:delay-source scheduled)))
          (is (= 2000 (:delay-ms scheduled)))
          (is (= 2000 (:resume-at scheduled)))
          ;; budget-active default (timeout/cap keys omitted -> 600000 / nil):
          ;; give-up happens at the deadline, not a count cap
          (is (= :retry-exhausted (:failure-reason outcome)))
          (is (= :deadline (:exhausted-reason outcome)))
          (is (nil? (:max-retries outcome))))))))

(deftest execute-prepared-request-saturates-overflowing-retry-deadline-test
  ;; A near-Long/MAX timeout saturates; a zero count cap keeps this deterministic.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :config {:auto-retry-total-timeout-ms Long/MAX_VALUE
                                                           :auto-retry-max-retries 0}
                                                  :now-fn #(java.time.Instant/ofEpochMilli 1)})
        prepared         (prepared-request ctx session-id)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _] (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     ai-ctx ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)]
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (= :count-cap (:exhausted-reason outcome)))
        (is (= 0 (:max-retries outcome)))))))

(deftest execute-prepared-request-cancel-during-truncated-final-sleep-test
  ;; Cancellation arriving during the truncated final sleep (overshoot path)
  ;; returns :retry-cancelled, emits the truncated provider_retry_scheduled then
  ;; provider_request_cancelled, and clears the window deadline (plan test 6).
  (let [clock          (atom 0)
        sleep-calls*   (atom 0)
        cancelled?     (atom false)
        [ctx session-id] (create-session-context {:persist? false
                                                  :config {:auto-retry-total-timeout-ms 5000
                                                           :auto-retry-base-delay-ms 2000
                                                           :auto-retry-max-delay-ms 60000}
                                                  :now-fn #(java.time.Instant/ofEpochMilli @clock)
                                                  :provider-retry-cancelled? (fn [_session-id] @cancelled?)
                                                  :provider-retry-sleep-fn
                                                  (fn [delay-ms]
                                                    (swap! sleep-calls* inc)
                                                    (swap! clock + (long delay-ms))
                                                    ;; flip cancellation during the truncated final sleep (2nd)
                                                    (when (= 2 @sleep-calls*)
                                                      (reset! cancelled? true)))})
        prepared       (prepared-request ctx session-id)
        attempts*      (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (error-turn "Connection reset by peer"))]
      (let [result    (turn-runtime/execute-prepared-request!
                       ai-ctx ctx session-id prepared nil)
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

(deftest execute-prepared-request-hot-loop-test-seam-guard-test
  ;; A persistent retryable failure under the sleep-disabled, budget-active,
  ;; cap-free test seam with a non-advancing clock (default wall-clock :now-fn)
  ;; fails fast as a test-config error instead of hot-looping to the real
  ;; 10-minute wall-clock deadline (pre-change 3-attempt cap bounded it).
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :provider-retry-sleep? false})
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (error-turn "Connection reset by peer"))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Test-seam misconfiguration"
           (turn-runtime/execute-prepared-request!
            ai-ctx ctx session-id prepared nil)))
      ;; the guard needs two consecutive clock reads to detect the
      ;; non-advancing clock, so it fires at the 2nd scheduled retry — fast,
      ;; not a 10-minute spin
      (is (= 2 @attempts*)))))

(deftest execute-prepared-request-sleep-fn-seam-guard-test
  ;; The seam guard also covers an injected no-op :provider-retry-sleep-fn
  ;; WITHOUT the :provider-retry-sleep? false flag: budget active, nil cap,
  ;; non-advancing clock, persistent failure — the waits are no-ops so the
  ;; loop would hot-loop unless the guard fires (the sleep? flag alone skips).
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :provider-retry-sleep-fn (fn [_delay-ms])})
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (error-turn "Connection reset by peer"))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Test-seam misconfiguration"
           (turn-runtime/execute-prepared-request!
            ai-ctx ctx session-id prepared nil)))
      ;; the guard needs two consecutive clock reads to detect the
      ;; non-advancing clock, so it fires at the 2nd scheduled retry — fast,
      ;; not a 10-minute spin
      (is (= 2 @attempts*)))))

(deftest execute-prepared-request-advancing-clock-test-seam-not-guarded-test
  ;; The seam guard only fires on a non-advancing clock: an injected clock that
  ;; advances between attempts still drives the budget window to the deadline
  ;; (documented seam contract: an ADVANCING :now-fn is required whenever the
  ;; budget is active).
  (let [clock          (atom 0)
        [ctx session-id] (create-session-context {:persist? false
                                                  :config {:auto-retry-total-timeout-ms 5000
                                                           :auto-retry-base-delay-ms 2000}
                                                  :provider-retry-sleep? false
                                                  :now-fn #(java.time.Instant/ofEpochMilli @clock)})
        prepared       (prepared-request ctx session-id)
        attempts*      (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                    ;; advance the injected clock past the next backoff each attempt
                                                     (swap! clock + 2000)
                                                     (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     ai-ctx ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)]
        (is (= 2 @attempts*))
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (= :deadline (:exhausted-reason outcome)))
        (is (nil? (:max-retries outcome)))))))

(deftest execute-prepared-request-small-base-delay-advancing-clock-not-guarded-test
  ;; The guard's clock-advance threshold is derived from the backoff delays
  ;; ((min base max), floored at 1), so a sub-second base delay with a correctly
  ;; advancing clock does NOT trip a false-positive at the 2nd retry (7th-turn
  ;; follow-up — the old hardcoded 1000 ms fired for any advance < 1000 ms).
  (let [clock          (atom 0)
        [ctx session-id] (create-session-context {:persist? false
                                                  :config {:auto-retry-total-timeout-ms 100
                                                           :auto-retry-base-delay-ms 10
                                                           :auto-retry-max-delay-ms 1000}
                                                  :provider-retry-sleep? false
                                                  :now-fn #(java.time.Instant/ofEpochMilli @clock)})
        prepared       (prepared-request ctx session-id)
        attempts*      (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                    ;; advance the injected clock past the next backoff each attempt
                                                     (swap! clock + 10)
                                                     (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     ai-ctx ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)]
        ;; no throw: the derived threshold (10) is not exceeded by the
        ;; delay-driven 10 ms advance; the window runs to the :deadline
        (is (> @attempts* 2))
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (= :deadline (:exhausted-reason outcome)))))))

(deftest execute-prepared-request-retry-min-clock-advance-opts-propagation-test
  ;; The :retry-min-clock-advance-ms guard-threshold override flows through
  ;; create-session-context opts (8th-turn follow-up), replacing the
  ;; direct-assoc-after-creation workaround (the silent-drop trap the 7th-turn
  ;; seam-key propagation closed for the other four keys). The guard's ex-data
  ;; reports the effective threshold — the override, not the 2000 default — so
  ;; the assertion fails if create-context* still drops it.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :provider-retry-sleep? false
                                                  :retry-min-clock-advance-ms 12345})
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (error-turn "Connection reset by peer"))]
      (try
        (turn-runtime/execute-prepared-request!
         ai-ctx ctx session-id prepared nil)
        (is false "expected Test-seam misconfiguration")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"Test-seam misconfiguration" (ex-message e)))
          ;; the opts-passed override reached the ctx and drives the guard
          ;; threshold (the default derivation would be 2000)
          (is (= 12345 (:min-retry-clock-advance-ms (ex-data e))))
          ;; the guard still fires at the 2nd scheduled retry — fast, not a
          ;; 10-minute spin (wall-clock advance ~0 ms < 12345 ms)
          (is (= 2 @attempts*)))))))
