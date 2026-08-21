(ns psi.turn-runtime.retry-config-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.model :as session-model]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.retry-provider-test-support :as retry-provider]
   [psi.turn-runtime.core :as turn-runtime]
   [psi.turn-runtime.retry :as retry]))

(defn- create-session-context
  ([]
   (create-session-context {:auto-retry-base-delay-ms 0}))
  ([config]
   (let [ctx (session/create-context
              (test-support/safe-context-opts
               {:persist? false
                :config config}))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- prepared-request
  [ctx session-id]
  (let [turn-id "turn-1"]
    (swap! (:state* ctx) assoc-in
           [:agent-session :sessions session-id :data :turn-augmentations turn-id]
           {:session-id session-id
            :turn-id turn-id
            :workflow-run-id nil
            :status :no-op
            :replay? false
            :accepted-operation-count 0
            :operations []
            :providers []})
    (prompt-request/build-prepared-request
     ctx session-id {:turn-id turn-id
                     :user-message {:role "user"
                                    :content [{:type :text :text "hello"}]}
                     :runtime-model (:model (ss/get-session-data-in ctx session-id))})))

(defn- provider-events
  [ctx session-id]
  (get-in @(:state* ctx)
          [:agent-session :sessions session-id :telemetry :provider-events]))

(defn- provider-result
  [assistant-message]
  {:turn-id "turn-1"
   :model {:provider "openai" :id "gpt-test"}
   :ai-options {}
   :turn-ctx nil
   :assistant-message assistant-message})

(deftest successful-request-ignores-invalid-retry-delay-config-test
  ;; Retry delay config is inactive when the initial provider request succeeds.
  (let [[ctx session-id] (create-session-context)
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (provider-result {:role "assistant"
                                                                       :content [{:type :text :text "done"}]
                                                                       :stop-reason :stop
                                                                       :timestamp (java.time.Instant/now)}))]
      (let [result (turn-runtime/execute-prepared-request!
                    ai-ctx ctx session-id prepared nil)]
        (is (= 1 @attempts*))
        (is (= :stop (:execution-result/stop-reason result)))
        (is (= ["provider_request_started" "provider_request_finished"]
               (mapv :type (provider-events ctx session-id))))))))

(deftest non-retryable-failure-ignores-invalid-retry-delay-config-test
  ;; Retry delay config is inactive for a terminal provider failure.
  (let [[ctx session-id] (create-session-context)
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (provider-result {:role "assistant"
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
        (is (= ["provider_request_started" "provider_request_finished"]
               (mapv :type (provider-events ctx session-id))))))))

(deftest successful-request-ignores-malformed-retry-policy-test
  ;; Raw retry policy is inactive when no retryable failure reaches scheduling.
  (let [[ctx session-id] (create-session-context {:auto-retry-total-timeout-ms "600000"
                                                  :auto-retry-max-retries {:attempts 3}
                                                  :auto-retry-base-delay-ms 1.5
                                                  :auto-retry-max-delay-ms "60000"})
        prepared         (prepared-request ctx session-id)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (provider-result {:role "assistant"
                                                                       :content [{:type :text :text "done"}]
                                                                       :stop-reason :stop
                                                                       :timestamp (java.time.Instant/now)}))]
      (is (= :stop
             (:execution-result/stop-reason
              (turn-runtime/execute-prepared-request!
               ai-ctx ctx session-id prepared nil)))))))

(deftest malformed-active-retry-policy-fails-informatively-test
  ;; Each documented integer setting rejects wrong types, fractions, and
  ;; negative count caps at the retry-policy boundary rather than leaking a
  ;; coercion/comparison exception.
  (doseq [[config-key value requirement]
          [[:auto-retry-total-timeout-ms "600000" "must be an integer or nil"]
           [:auto-retry-total-timeout-ms 1.5 "must be an integer or nil"]
           [:auto-retry-max-retries {:attempts 3} "must be a non-negative integer or nil"]
           [:auto-retry-max-retries 1.5 "must be a non-negative integer or nil"]
           [:auto-retry-max-retries -1 "must be a non-negative integer or nil"]
           [:auto-retry-base-delay-ms "2000" "must be a positive integer"]
           [:auto-retry-base-delay-ms 1.5 "must be a positive integer"]
           [:auto-retry-max-delay-ms {} "must be a positive integer"]
           [:auto-retry-max-delay-ms 1.5 "must be a positive integer"]]]
    (let [[ctx session-id] (create-session-context {config-key value})
          prepared         (prepared-request ctx session-id)]
      (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                       (provider-result {:role "assistant"
                                                                         :content [{:type :error :text "Connection reset"}]
                                                                         :stop-reason :error
                                                                         :error-message "Connection reset"
                                                                         :timestamp (java.time.Instant/now)}))]
        (let [error (try
                      (turn-runtime/execute-prepared-request!
                       ai-ctx ctx session-id prepared nil)
                      nil
                      (catch clojure.lang.ExceptionInfo error
                        error))]
          (is (re-find #"^Invalid retry configuration:" (ex-message error)))
          (is (= {:config-key config-key
                  :value value
                  :requirement requirement}
                 (ex-data error))))))))

(deftest near-long-delay-is-inactive-for-non-retryable-failure-test
  ;; A terminal failure never constructs exponential metadata from inactive
  ;; near-Long delay settings.
  (let [[ctx session-id] (create-session-context {:auto-retry-base-delay-ms Long/MAX_VALUE
                                                  :auto-retry-max-delay-ms Long/MAX_VALUE})
        prepared         (prepared-request ctx session-id)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (provider-result {:role "assistant"
                                                                       :content [{:type :error :text "invalid api key"}]
                                                                       :stop-reason :error
                                                                       :error-message "invalid api key"
                                                                       :http-status 401
                                                                       :timestamp (java.time.Instant/now)}))]
      (is (= :non-retryable
             (get-in (turn-runtime/execute-prepared-request!
                      ai-ctx ctx session-id prepared nil)
                     [:execution-result/retry-outcome :failure-reason]))))))

(deftest near-long-delay-metadata-saturates-test
  ;; Valid near-Long delay settings cap both exponential multiplication and
  ;; resume-time addition instead of overflowing before scheduling.
  (let [[ctx session-id] (create-session-context {:auto-retry-total-timeout-ms 0
                                                  :auto-retry-max-retries 1
                                                  :auto-retry-base-delay-ms Long/MAX_VALUE
                                                  :auto-retry-max-delay-ms Long/MAX_VALUE})
        ctx              (assoc ctx
                                :provider-retry-sleep? false
                                :now-fn (constantly (java.time.Instant/ofEpochMilli 1)))
        prepared         (prepared-request ctx session-id)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (provider-result {:role "assistant"
                                                                       :content [{:type :error :text "Connection reset"}]
                                                                       :stop-reason :error
                                                                       :error-message "Connection reset"
                                                                       :timestamp (java.time.Instant/now)}))]
      (let [result    (turn-runtime/execute-prepared-request!
                       ai-ctx ctx session-id prepared nil)
            scheduled (some #(when (= "provider_retry_scheduled" (:type %)) %)
                            (provider-events ctx session-id))]
        (is (= :count-cap
               (get-in result [:execution-result/retry-outcome :exhausted-reason])))
        (is (= Long/MAX_VALUE (:delay-ms scheduled)))
        (is (= Long/MAX_VALUE (:resume-at scheduled)))))))

(deftest near-long-production-sleep-is-overflow-safe-and-cancellable-test
  ;; The real interruptible-sleep path saturates its deadline before observing
  ;; cancellation, so a near-Long delay neither overflows nor waits.
  (let [[ctx session-id] (create-session-context)
        cancellation-checks* (atom 0)
        ctx                   (assoc ctx :provider-retry-cancelled?
                                     (fn [_]
                                       (swap! cancellation-checks* inc)
                                       true))]
    (is (nil? (retry/interruptible-sleep-for-retry!
               ctx session-id Long/MAX_VALUE)))
    (is (= 1 @cancellation-checks*))))

(deftest retry-policy-sources-agree-test
  ;; Preview, typed resolution, and the hot-loop guard derive one policy from
  ;; canonical defaults plus explicit operator overrides.
  (doseq [overrides [{}
                     {:auto-retry-total-timeout-ms 100
                      :auto-retry-base-delay-ms 10
                      :auto-retry-max-delay-ms 500}]]
    (let [[ctx _]         (create-session-context overrides)
          expected-config (merge session-model/default-config overrides)
          preview         (retry/retry-policy-preview ctx)
          limiters        (retry/resolve-retry-limiters! ctx)
          policy          (retry/resolve-retry-delays! ctx limiters)
          expected-min    (min (:auto-retry-base-delay-ms expected-config)
                               (:auto-retry-max-delay-ms expected-config))
          guard-error     (try
                            (retry/assert-test-seam-no-hot-loop!
                             (assoc ctx :provider-retry-sleep? false)
                             policy
                             0
                             (dec expected-min))
                            nil
                            (catch clojure.lang.ExceptionInfo error
                              error))]
      (is (= (select-keys limiters [:budget-active? :count-cap])
             preview))
      (is (= {:budget-timeout-ms (:auto-retry-total-timeout-ms expected-config)
              :base-delay-ms (:auto-retry-base-delay-ms expected-config)
              :max-delay-ms (:auto-retry-max-delay-ms expected-config)}
             (select-keys policy
                          [:budget-timeout-ms :base-delay-ms :max-delay-ms])))
      (is (re-find #"^Test-seam misconfiguration"
                   (ex-message guard-error)))
      (is (= expected-min
             (:min-retry-clock-advance-ms (ex-data guard-error)))))))

(deftest retry-clock-advance-override-is-validated-test
  ;; An explicit guard threshold is a positive long integer; malformed seam
  ;; configuration fails at the retry boundary with the offending key/value.
  (let [[ctx _] (create-session-context session-model/default-config)
        policy  (->> (retry/resolve-retry-limiters! ctx)
                     (retry/resolve-retry-delays! ctx))]
    (doseq [override [-1 0 1.5 "10"]]
      (let [error (try
                    (retry/assert-test-seam-no-hot-loop!
                     (assoc ctx
                            :provider-retry-sleep? false
                            :retry-min-clock-advance-ms override)
                     policy
                     nil
                     0)
                    nil
                    (catch clojure.lang.ExceptionInfo caught
                      caught))]
        (is (= "Invalid retry configuration: retry-min-clock-advance-ms must be a positive integer"
               (ex-message error)))
        (is (= {:config-key :retry-min-clock-advance-ms
                :value override
                :requirement "must be a positive integer"}
               (ex-data error)))))))

(deftest retry-clock-advance-extreme-backward-clock-is-safe-test
  ;; A clock moving backward across the full long range is non-advancing; the
  ;; guard reports zero elapsed time rather than overflowing subtraction.
  (let [[ctx _] (create-session-context session-model/default-config)
        policy  (->> (retry/resolve-retry-limiters! ctx)
                     (retry/resolve-retry-delays! ctx))
        error   (try
                  (retry/assert-test-seam-no-hot-loop!
                   (assoc ctx :provider-retry-sleep? false)
                   policy
                   Long/MAX_VALUE
                   Long/MIN_VALUE)
                  nil
                  (catch clojure.lang.ExceptionInfo caught
                    caught))]
    (is (re-find #"^Test-seam misconfiguration" (ex-message error)))
    (is (= 0 (:clock-advance-ms (ex-data error))))))

(deftest retry-clear-mode-is-required-test
  ;; Retry deadline lifecycle cannot be selected by omission or truthiness.
  (let [[ctx session-id] (create-session-context {})]
    (is (thrown? clojure.lang.ArityException
                 (apply retry/clear-active-retry! [ctx session-id nil])))
    (doseq [mode [nil false true :unknown]]
      (let [error (try
                    (retry/clear-active-retry! ctx session-id nil mode)
                    nil
                    (catch clojure.lang.ExceptionInfo error
                      error))]
        (is (= "Invalid retry clear mode" (ex-message error)))
        (is (= mode (:mode (ex-data error))))))))

(deftest count-cap-terminal-ignores-invalid-retry-delay-config-test
  ;; An already-reached count cap terminates before inactive delay validation.
  (let [[ctx session-id] (create-session-context {:auto-retry-max-retries 0
                                                  :auto-retry-base-delay-ms 0})
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                     (swap! attempts* inc)
                                                     (provider-result {:role "assistant"
                                                                       :content [{:type :error :text "Connection reset"}]
                                                                       :stop-reason :error
                                                                       :error-message "Connection reset"
                                                                       :timestamp (java.time.Instant/now)}))]
      (let [result  (turn-runtime/execute-prepared-request!
                     ai-ctx ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)
            events  (provider-events ctx session-id)]
        (is (= 1 @attempts*))
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (= :count-cap (:exhausted-reason outcome)))
        (is (= 0 (:max-retries outcome)))
        (is (= ["provider_request_started" "provider_request_finished"]
               (mapv :type events)))
        (is (true? (:final? (last events))))))))

(deftest retryable-failure-validates-before-scheduling-test
  ;; Invalid active retry delay config rejects after the failed attempt but
  ;; before retry state or a provider_retry_scheduled event is emitted.
  (doseq [config [{:auto-retry-base-delay-ms 0
                   :auto-retry-max-delay-ms 60000}
                  {:auto-retry-base-delay-ms 2000
                   :auto-retry-max-delay-ms 0}]]
    (let [[ctx session-id] (create-session-context config)
          _                (ss/apply-root-state-update-in!
                            ctx
                            (ss/session-update session-id
                                               #(assoc %
                                                       :retry-attempt 2
                                                       :retry {:active? true
                                                               :attempt 2
                                                               :delay-ms 4000
                                                               :delay-source :exponential-backoff
                                                               :resume-at 5000}
                                                       :retry-deadline-ms (+ (System/currentTimeMillis)
                                                                             600000))))
          prepared         (prepared-request ctx session-id)
          attempts*        (atom 0)]
      (retry-provider/with-nullable-provider [ai-ctx (fn [& _]
                                                       (swap! attempts* inc)
                                                       (provider-result {:role "assistant"
                                                                         :content [{:type :error :text "Connection reset by peer"}]
                                                                         :stop-reason :error
                                                                         :error-message "Connection reset by peer"
                                                                         :timestamp (java.time.Instant/now)}))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Invalid retry configuration"
             (turn-runtime/execute-prepared-request!
              ai-ctx ctx session-id prepared nil)))
        (is (= 1 @attempts*))
        (let [events         (provider-events ctx session-id)
              terminal-event (last events)]
          (is (= ["provider_request_started" "provider_request_finished"]
                 (mapv :type events)))
          (is (= {:type "provider_request_finished"
                  :provider-request-id "turn-1"
                  :retry-attempt 2
                  :status :failed
                  :final? true
                  :retryable? true
                  :error-kind :transport
                  :stop-reason :error
                  :error-message "Connection reset by peer"}
                 (select-keys terminal-event
                              [:type :provider-request-id :retry-attempt :status
                               :final? :retryable? :error-kind :stop-reason
                               :error-message]))))
        (is (= {:retry-attempt 0
                :retry nil}
               (select-keys (ss/get-session-data-in ctx session-id)
                            [:retry-attempt :retry :retry-deadline-ms])))))))
