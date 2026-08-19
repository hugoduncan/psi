(ns psi.agent-session.eql-provider-retry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]))

(defn- retry-query
  []
  [:psi.provider-request/id
   :psi.provider-request/turn-id
   :psi.provider-request/retry-count
   :psi.provider-request/final-status
   :psi.provider-request/exhausted-reason
   {:psi.provider-request/retry-attempts
    [:psi.provider-retry/attempt
     :psi.provider-retry/failed-attempt
     :psi.provider-retry/error-kind
     :psi.provider-retry/error-message
     :psi.provider-retry/delay-ms
     :psi.provider-retry/delay-source
     :psi.provider-retry/resume-at
     :psi.provider-retry/rate-limit
     :psi.provider-retry/final?]}])

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
                                    :resume-at 42
                                    :rate-limit {:limit 100
                                                 :remaining 0
                                                 :reset-after-ms 3000
                                                 :reset-at 3042}}
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
                                   :psi.provider-request/exhausted-reason
                                   {:psi.provider-request/retry-attempts
                                    [:psi.provider-retry/attempt
                                     :psi.provider-retry/failed-attempt
                                     :psi.provider-retry/error-kind
                                     :psi.provider-retry/error-message
                                     :psi.provider-retry/delay-ms
                                     :psi.provider-retry/delay-source
                                     :psi.provider-retry/resume-at
                                     :psi.provider-retry/rate-limit
                                     :psi.provider-retry/final?]}]}])]
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
                 :psi.provider-retry/resume-at 42
                 :psi.provider-retry/rate-limit {:limit 100
                                                 :remaining 0
                                                 :reset-after-ms 3000
                                                 :reset-at 3042}
                 :psi.provider-retry/final? true}]
               (get-in r [:psi.agent-session/provider-retries 0 :psi.provider-request/retry-attempts])))))))

(deftest provider-retry-direct-eql-introspection-test
  (testing "provider retry detail resolves from explicit provider request id or turn id"
    (let [[ctx session-id] (create-session-context)]
      (test-support/update-state! ctx :provider-events
                                  into
                                  [{:type "provider_request_started"
                                    :provider-request-id "request-1"
                                    :turn-id "turn-1"
                                    :retry-attempt 0}
                                   {:type "provider_retry_scheduled"
                                    :provider-request-id "request-1"
                                    :turn-id "turn-1"
                                    :failed-attempt 0
                                    :retry-attempt 1
                                    :error-kind :rate-limited
                                    :error-message "rate limited"
                                    :delay-ms 50
                                    :delay-source :retry-after
                                    :resume-at 100}
                                   {:type "provider_request_finished"
                                    :provider-request-id "request-1"
                                    :turn-id "turn-1"
                                    :retry-attempt 1
                                    :status :failed
                                    :final? true
                                    :failure-reason :retry-exhausted
                                    :error-kind :rate-limited}])
      (let [by-request (session/query-in ctx session-id
                                         (retry-query)
                                         {:psi.provider-request/id "request-1"})
            by-turn    (session/query-in ctx session-id
                                         (retry-query)
                                         {:psi.provider-request/turn-id "turn-1"})]
        (is (= "turn-1" (:psi.provider-request/turn-id by-request)))
        (is (= "request-1" (:psi.provider-request/id by-turn)))
        (is (= :retry-exhausted (:psi.provider-request/final-status by-request)))
        (is (= 1 (:psi.provider-request/retry-count by-turn)))
        (is (= [{:psi.provider-retry/attempt 1
                 :psi.provider-retry/failed-attempt 0
                 :psi.provider-retry/error-kind :rate-limited
                 :psi.provider-retry/error-message "rate limited"
                 :psi.provider-retry/delay-ms 50
                 :psi.provider-retry/delay-source :retry-after
                 :psi.provider-retry/resume-at 100
                 :psi.provider-retry/rate-limit nil
                 :psi.provider-retry/final? true}]
               (:psi.provider-request/retry-attempts by-request)))))))

(deftest provider-retry-count-cap-exhausted-reason-test
  (testing "a count-cap give-up projects :exhausted-reason :count-cap on the summary"
    (let [[ctx session-id] (create-session-context)]
      (test-support/update-state! ctx :provider-events
                                  into
                                  [{:type "provider_request_started"
                                    :provider-request-id "request-count-cap"
                                    :turn-id "turn-count-cap"
                                    :retry-attempt 0}
                                   {:type "provider_request_finished"
                                    :provider-request-id "request-count-cap"
                                    :turn-id "turn-count-cap"
                                    :retry-attempt 0
                                    :status :failed
                                    :final? false
                                    :error-kind :rate-limit
                                    :error-message "rate limited"}
                                   {:type "provider_retry_scheduled"
                                    :provider-request-id "request-count-cap"
                                    :turn-id "turn-count-cap"
                                    :failed-attempt 0
                                    :retry-attempt 1
                                    :error-kind :rate-limit
                                    :error-message "rate limited"
                                    :delay-ms 25
                                    :delay-source :exponential-backoff
                                    :resume-at 125}
                                   {:type "provider_request_finished"
                                    :provider-request-id "request-count-cap"
                                    :turn-id "turn-count-cap"
                                    :retry-attempt 1
                                    :status :failed
                                    :final? true
                                    :failure-reason :retry-exhausted
                                    :exhausted-reason :count-cap
                                    :error-kind :rate-limit}])
      (let [by-request (session/query-in ctx session-id
                                         (retry-query)
                                         {:psi.provider-request/id "request-count-cap"})]
        (is (= :retry-exhausted (:psi.provider-request/final-status by-request)))
        (is (= :count-cap (:psi.provider-request/exhausted-reason by-request)))
        (is (= 1 (:psi.provider-request/retry-count by-request)))
        (is (= [{:psi.provider-retry/attempt 1
                 :psi.provider-retry/failed-attempt 0
                 :psi.provider-retry/error-kind :rate-limit
                 :psi.provider-retry/error-message "rate limited"
                 :psi.provider-retry/delay-ms 25
                 :psi.provider-retry/delay-source :exponential-backoff
                 :psi.provider-retry/resume-at 125
                 :psi.provider-retry/rate-limit nil
                 :psi.provider-retry/final? true}]
               (:psi.provider-request/retry-attempts by-request)))))))

(deftest provider-retry-cancelled-final-marker-test
  (testing "provider retry detail marks a cancelled suppressed retry attempt as final"
    (let [[ctx session-id] (create-session-context)]
      (test-support/update-state! ctx :provider-events
                                  into
                                  [{:type "provider_request_started"
                                    :provider-request-id "request-cancelled"
                                    :turn-id "turn-cancelled"
                                    :retry-attempt 0}
                                   {:type "provider_request_finished"
                                    :provider-request-id "request-cancelled"
                                    :turn-id "turn-cancelled"
                                    :retry-attempt 0
                                    :status :failed
                                    :final? false
                                    :error-kind :transport
                                    :error-message "connection reset"}
                                   {:type "provider_retry_scheduled"
                                    :provider-request-id "request-cancelled"
                                    :turn-id "turn-cancelled"
                                    :failed-attempt 0
                                    :retry-attempt 1
                                    :error-kind :transport
                                    :error-message "connection reset"
                                    :delay-ms 25
                                    :delay-source :exponential-backoff
                                    :resume-at 125}
                                   {:type "provider_request_cancelled"
                                    :provider-request-id "request-cancelled"
                                    :turn-id "turn-cancelled"
                                    :retry-attempt 1
                                    :last-failed-attempt 0
                                    :cancelled? true
                                    :final? true
                                    :failure-reason :retry-cancelled
                                    :error-kind :transport}])
      (let [by-request (session/query-in ctx session-id
                                         (retry-query)
                                         {:psi.provider-request/id "request-cancelled"})]
        (is (= :retry-cancelled (:psi.provider-request/final-status by-request)))
        (is (= [{:psi.provider-retry/attempt 1
                 :psi.provider-retry/failed-attempt 0
                 :psi.provider-retry/error-kind :transport
                 :psi.provider-retry/error-message "connection reset"
                 :psi.provider-retry/delay-ms 25
                 :psi.provider-retry/delay-source :exponential-backoff
                 :psi.provider-retry/resume-at 125
                 :psi.provider-retry/rate-limit nil
                 :psi.provider-retry/final? true}]
               (:psi.provider-request/retry-attempts by-request)))))))

(deftest provider-retry-truncated-final-schedule-marker-test
  (testing "the truncated final schedule of a deadline give-up carries final? true"
    ;; Hand-built sequence mirroring the empirically-verified truncated-final
    ;; flow (budget 5000 / base 2000, injected clock + sleep-fn): the
    ;; authoritative terminal provider_request_finished reports the pre-sleep
    ;; FAILED attempt (retry-attempt 1, the actual executed attempt) while the
    ;; truncated schedule it supersedes carries retry-attempt 2 (delay 3000,
    ;; resume-at == deadline 5000) — the LAST schedule must be marked final,
    ;; not the one whose attempt matches the terminal event's.
    (let [[ctx session-id] (create-session-context)]
      (test-support/update-state! ctx :provider-events
                                  into
                                  [{:type "provider_request_started"
                                    :provider-request-id "request-truncated"
                                    :turn-id "turn-truncated"
                                    :retry-attempt 0}
                                   {:type "provider_request_finished"
                                    :provider-request-id "request-truncated"
                                    :turn-id "turn-truncated"
                                    :retry-attempt 0
                                    :status :failed
                                    :final? false
                                    :error-kind :transport
                                    :error-message "connection reset"}
                                   {:type "provider_retry_scheduled"
                                    :provider-request-id "request-truncated"
                                    :turn-id "turn-truncated"
                                    :failed-attempt 0
                                    :retry-attempt 1
                                    :error-kind :transport
                                    :error-message "connection reset"
                                    :delay-ms 2000
                                    :delay-source :exponential-backoff
                                    :resume-at 2000}
                                   {:type "provider_request_finished"
                                    :provider-request-id "request-truncated"
                                    :turn-id "turn-truncated"
                                    :retry-attempt 1
                                    :status :failed
                                    :final? false
                                    :error-kind :transport
                                    :error-message "connection reset"}
                                   {:type "provider_retry_scheduled"
                                    :provider-request-id "request-truncated"
                                    :turn-id "turn-truncated"
                                    :failed-attempt 1
                                    :retry-attempt 2
                                    :error-kind :transport
                                    :error-message "connection reset"
                                    :delay-ms 3000
                                    :delay-source :exponential-backoff
                                    :resume-at 5000}
                                   {:type "provider_request_finished"
                                    :provider-request-id "request-truncated"
                                    :turn-id "turn-truncated"
                                    :retry-attempt 1
                                    :status :failed
                                    :final? true
                                    :failure-reason :retry-exhausted
                                    :exhausted-reason :deadline
                                    :error-kind :transport}])
      (let [by-request (session/query-in ctx session-id
                                         (retry-query)
                                         {:psi.provider-request/id "request-truncated"})]
        (is (= :retry-exhausted (:psi.provider-request/final-status by-request)))
        (is (= :deadline (:psi.provider-request/exhausted-reason by-request)))
        (is (= 2 (:psi.provider-request/retry-count by-request)))
        (is (= [{:psi.provider-retry/attempt 1
                 :psi.provider-retry/failed-attempt 0
                 :psi.provider-retry/error-kind :transport
                 :psi.provider-retry/error-message "connection reset"
                 :psi.provider-retry/delay-ms 2000
                 :psi.provider-retry/delay-source :exponential-backoff
                 :psi.provider-retry/resume-at 2000
                 :psi.provider-retry/rate-limit nil
                 :psi.provider-retry/final? false}
                {:psi.provider-retry/attempt 2
                 :psi.provider-retry/failed-attempt 1
                 :psi.provider-retry/error-kind :transport
                 :psi.provider-retry/error-message "connection reset"
                 :psi.provider-retry/delay-ms 3000
                 :psi.provider-retry/delay-source :exponential-backoff
                 :psi.provider-retry/resume-at 5000
                 :psi.provider-retry/rate-limit nil
                 :psi.provider-retry/final? true}]
               (:psi.provider-request/retry-attempts by-request)))))))
