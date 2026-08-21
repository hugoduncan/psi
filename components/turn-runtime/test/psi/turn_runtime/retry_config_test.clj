(ns psi.turn-runtime.retry-config-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.core :as turn-runtime]))

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
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (provider-result {:role "assistant"
                                      :content [{:type :text :text "done"}]
                                      :stop-reason :stop
                                      :timestamp (java.time.Instant/now)}))]
      (let [result (turn-runtime/execute-prepared-request!
                    {:provider-registry (atom {})} ctx session-id prepared nil)]
        (is (= 1 @attempts*))
        (is (= :stop (:execution-result/stop-reason result)))
        (is (= ["provider_request_started" "provider_request_finished"]
               (mapv :type (provider-events ctx session-id))))))))

(deftest non-retryable-failure-ignores-invalid-retry-delay-config-test
  ;; Retry delay config is inactive for a terminal provider failure.
  (let [[ctx session-id] (create-session-context)
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (provider-result {:role "assistant"
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
        (is (= ["provider_request_started" "provider_request_finished"]
               (mapv :type (provider-events ctx session-id))))))))

(deftest retryable-failure-validates-before-scheduling-test
  ;; Invalid active retry delay config rejects after the failed attempt but
  ;; before retry state or a provider_retry_scheduled event is emitted.
  (doseq [config [{:auto-retry-base-delay-ms 0
                   :auto-retry-max-delay-ms 60000}
                  {:auto-retry-base-delay-ms 2000
                   :auto-retry-max-delay-ms 0}]]
    (let [[ctx session-id] (create-session-context config)
          prepared         (prepared-request ctx session-id)
          attempts*        (atom 0)]
      (with-redefs [psi.turn-runtime.core/execute-live-turn!
                    (fn [& _]
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
              {:provider-registry (atom {})} ctx session-id prepared nil)))
        (is (= 1 @attempts*))
        (is (= ["provider_request_started" "provider_request_finished"]
               (mapv :type (provider-events ctx session-id))))
        (is (nil? (:retry (ss/get-session-data-in ctx session-id))))))))
