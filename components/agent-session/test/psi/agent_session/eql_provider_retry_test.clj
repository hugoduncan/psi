(ns psi.agent-session.eql-provider-retry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest provider-retry-eql-introspection-test
  (testing "provider retry summaries project from retained lifecycle events"
    (let [[ctx session-id] (create-session-context)]
      (test-support/update-state! ctx :provider-events
                                  into
                                  [{:type "provider_request_started"
                                    :provider-request-id "turn-retry"
                                    :turn-id "turn-retry"
                                    :retry-attempt 0}
                                   {:type "provider_request_finished"
                                    :provider-request-id "turn-retry"
                                    :turn-id "turn-retry"
                                    :retry-attempt 0
                                    :status :failed
                                    :final? false
                                    :error-kind :transport
                                    :error-message "Connection reset by peer"}
                                   {:type "provider_retry_scheduled"
                                    :provider-request-id "turn-retry"
                                    :turn-id "turn-retry"
                                    :failed-attempt 0
                                    :retry-attempt 1
                                    :error-kind :transport
                                    :error-message "Connection reset by peer"
                                    :delay-ms 10
                                    :delay-source :exponential-backoff
                                    :resume-at 42}
                                   {:type "provider_request_finished"
                                    :provider-request-id "turn-retry"
                                    :turn-id "turn-retry"
                                    :retry-attempt 1
                                    :status :succeeded
                                    :final? true}])
      (let [r (session/query-in ctx session-id
                                [:psi.agent-session/provider-retry-count
                                 :psi.agent-session/provider-retried-request-count
                                 {:psi.agent-session/provider-retries
                                  [:psi.provider-request/id
                                   :psi.provider-request/turn-id
                                   :psi.provider-request/retry-count
                                   :psi.provider-request/final-status
                                   {:psi.provider-request/retry-attempts
                                    [:psi.provider-retry/attempt
                                     :psi.provider-retry/failed-attempt
                                     :psi.provider-retry/error-kind
                                     :psi.provider-retry/error-message
                                     :psi.provider-retry/delay-ms
                                     :psi.provider-retry/delay-source
                                     :psi.provider-retry/resume-at]}]}])]
        (is (= 1 (:psi.agent-session/provider-retry-count r)))
        (is (= 1 (:psi.agent-session/provider-retried-request-count r)))
        (is (= "turn-retry" (get-in r [:psi.agent-session/provider-retries 0 :psi.provider-request/id])))
        (is (= :succeeded (get-in r [:psi.agent-session/provider-retries 0 :psi.provider-request/final-status])))
        (is (= [{:psi.provider-retry/attempt 1
                 :psi.provider-retry/failed-attempt 0
                 :psi.provider-retry/error-kind :transport
                 :psi.provider-retry/error-message "Connection reset by peer"
                 :psi.provider-retry/delay-ms 10
                 :psi.provider-retry/delay-source :exponential-backoff
                 :psi.provider-retry/resume-at 42}]
               (get-in r [:psi.agent-session/provider-retries 0 :psi.provider-request/retry-attempts])))))))
