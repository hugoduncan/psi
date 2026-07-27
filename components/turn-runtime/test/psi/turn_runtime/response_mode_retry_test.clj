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
