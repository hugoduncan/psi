(ns psi.session-state.model-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.session-state.model :as session]))

(deftest initial-session-test
  (testing "initial-session passes schema"
    (is (session/valid-session? (session/initial-session))))

  (testing "initial-session overrides are merged"
    (let [s (session/initial-session {:session-name "test" :auto-compaction-enabled true})]
      (is (= "test" (:session-name s)))
      (is (true? (:auto-compaction-enabled s)))))

  (testing "initial-session has expected defaults"
    (let [s (session/initial-session)]
      (is (string? (:session-id s)))
      (is (nil? (:session-file s)))
      (is (= :off (:thinking-level s)))
      (is (false? (:is-streaming s)))
      (is (false? (:is-compacting s)))
      (is (= "" (:base-system-prompt s)))
      (is (= "" (:system-prompt s)))
      (is (= #{:system} (:cache-breakpoints s)))
      (is (= :console (:ui-type s)))
      (is (= [] (:steering-messages s)))
      (is (= [] (:follow-up-messages s)))
      (is (= 0 (:retry-attempt s)))
      (is (nil? (:retry s)))
      (is (= {:schedules {}
              :queue []}
             (:scheduler s)))))

  (testing "initial-session accepts canonical retry metadata shape when populated"
    (let [s (session/initial-session {:retry {:active? true
                                              :attempt 2
                                              :delay-ms 8000
                                              :delay-source :retry-after
                                              :resume-at 18000
                                              :rate-limit {:limit 5000
                                                           :remaining 0
                                                           :reset-after-ms 32000
                                                           :reset-at 42000}}})]
      (is (session/valid-session? s))
      (is (= {:active? true
              :attempt 2
              :delay-ms 8000
              :delay-source :retry-after
              :resume-at 18000
              :rate-limit {:limit 5000
                           :remaining 0
                           :reset-after-ms 32000
                           :reset-at 42000}}
             (:retry s))))))

(deftest agent-session-schema-temperature-test
  (testing "agent-session-schema accepts optional :temperature"
    (is (session/valid-session?
         (session/initial-session {:temperature 1.0}))))

  (testing "agent-session-schema accepts 0.0 temperature"
    (is (session/valid-session?
         (session/initial-session {:temperature 0.0}))))

  (testing "agent-session-schema accepts 2.0 temperature (upper bound)"
    (is (session/valid-session?
         (session/initial-session {:temperature 2.0}))))

  (testing "agent-session-schema accepts absent temperature"
    (is (session/valid-session? (session/initial-session))))

  (testing "agent-session-schema rejects temperature below 0.0"
    (is (not (session/valid-session?
              (assoc (session/initial-session) :temperature -0.1)))))

  (testing "agent-session-schema rejects temperature above 2.0"
    (is (not (session/valid-session?
              (assoc (session/initial-session) :temperature 2.1))))))

(deftest scheduler-schema-test
  (testing "schedule schema accepts canonical scheduled prompt record"
    (is (session/valid-schedule?
         {:schedule-id "sch-1"
          :label "check-build"
          :message "Check build status"
          :source :scheduled
          :created-at (java.time.Instant/now)
          :fire-at (java.time.Instant/now)
          :status :pending
          :session-id "sid-1"})))

  (testing "scheduler state schema accepts canonical store shape"
    (is (session/valid-scheduler-state?
         {:schedules {"sch-1" {:schedule-id "sch-1"
                               :label nil
                               :message "wake up"
                               :source :scheduled
                               :created-at (java.time.Instant/now)
                               :fire-at (java.time.Instant/now)
                               :status :queued
                               :session-id "sid-1"}}
          :queue ["sch-1"]}))))

(deftest idle-predicate-test
  (testing "idle? true when not streaming or compacting"
    (is (session/idle? (session/initial-session))))

  (testing "idle? false when streaming"
    (is (not (session/idle? (assoc (session/initial-session) :is-streaming true)))))

  (testing "idle? false when compacting"
    (is (not (session/idle? (assoc (session/initial-session) :is-compacting true))))))

(deftest pending-messages-test
  (testing "pending-message-count sums both queues"
    (let [s (assoc (session/initial-session)
                   :steering-messages ["a" "b"]
                   :follow-up-messages ["c"])]
      (is (= 3 (session/pending-message-count s)))))

  (testing "has-pending-messages? false when both queues empty"
    (is (not (session/has-pending-messages? (session/initial-session)))))

  (testing "has-pending-messages? true when steering queue has messages"
    (is (session/has-pending-messages?
         (assoc (session/initial-session) :steering-messages ["x"])))))

(deftest context-fraction-test
  (testing "nil when context-tokens nil"
    (is (nil? (session/context-fraction-used (session/initial-session)))))

  (testing "nil when context-window nil"
    (is (nil? (session/context-fraction-used
               (assoc (session/initial-session) :context-tokens 1000)))))

  (testing "computes fraction correctly"
    (let [s (assoc (session/initial-session)
                   :context-tokens 8000
                   :context-window 10000)]
      (is (= 0.8 (session/context-fraction-used s)))))

  (testing "above-compaction-threshold? false below 0.8"
    (let [s (assoc (session/initial-session)
                   :context-tokens 5000
                   :context-window 10000)]
      (is (not (session/above-compaction-threshold? s)))))

  (testing "above-compaction-threshold? true at exactly 0.8"
    (let [s (assoc (session/initial-session)
                   :context-tokens 8000
                   :context-window 10000)]
      (is (session/above-compaction-threshold? s)))))

(deftest thinking-level-test
  (let [reasoning-model    {:provider "x" :id "y" :reasoning true}
        no-reasoning-model {:provider "x" :id "z" :reasoning false}]

    (testing "clamp-thinking-level passes through for reasoning model"
      (is (= :high (session/clamp-thinking-level :high reasoning-model))))

    (testing "clamp-thinking-level forces :off for non-reasoning model"
      (is (= :off (session/clamp-thinking-level :high no-reasoning-model))))

    (testing "next-thinking-level cycles forward"
      (is (= :minimal (session/next-thinking-level :off reasoning-model)))
      (is (= :low     (session/next-thinking-level :minimal reasoning-model)))
      (is (= :off     (session/next-thinking-level :xhigh reasoning-model))))

    (testing "next-thinking-level always :off for non-reasoning model"
      (is (= :off (session/next-thinking-level :high no-reasoning-model))))))

(deftest model-cycling-test
  (let [m1   {:provider "a" :id "m1"}
        m2   {:provider "a" :id "m2"}
        m3   {:provider "a" :id "m3"}
        cands [{:model m1 :thinking-level :off}
               {:model m2 :thinking-level :off}
               {:model m3 :thinking-level :off}]]

    (testing "next-model forward from m1 → m2"
      (is (= m2 (session/next-model cands m1 :forward))))

    (testing "next-model forward from m3 wraps to m1"
      (is (= m1 (session/next-model cands m3 :forward))))

    (testing "next-model backward from m1 wraps to m3"
      (is (= m3 (session/next-model cands m1 :backward))))

    (testing "next-model with nil current → first model"
      (is (= m1 (session/next-model cands nil :forward))))))

(deftest retry-helpers-test
  (testing "retry-error? true for rate limit"
    (is (session/retry-error? :error "rate limit exceeded")))

  (testing "retry-error? true for OpenAI usage limit wording"
    (is (session/retry-error? :error "The usage limit has been reached (status 429) [request-id req_123]"))
    (is (session/retry-error? :error "The usage limit has been reached")))

  (testing "retry-error? true for overloaded"
    (is (session/retry-error? :error "Service Overloaded")))

  (testing "retry-error? true for chunked stream termination failure"
    (is (session/retry-error? :error "Premature end of chunk coded message body: closing chunk expected")))

  (testing "retry-error? true for canonical OpenAI transient server error"
    (is (session/retry-error? :error "An error occurred while processing your request. You can retry your request, or contact us through our help center at help.openai.com if the error persists. Please include the request ID abc in your message.")))

  (testing "retry-error? false for stop reason"
    (is (not (session/retry-error? :stop nil))))

  (testing "retry-error? false for nil error"
    (is (not (session/retry-error? :error nil))))

  (testing "retry-error? false for auth failure"
    (is (not (session/retry-error? :error "401 unauthorized api key invalid"))))

  (testing "context-overflow-error? true for context length"
    (is (session/context-overflow-error? "context length exceeded")))

  (testing "context-overflow-error? false for random string"
    (is (not (session/context-overflow-error? "timeout error"))))

  (testing "exponential-backoff-ms doubles with attempt"
    (is (= 2000 (session/exponential-backoff-ms 0 2000 60000)))
    (is (= 4000 (session/exponential-backoff-ms 1 2000 60000)))
    (is (= 8000 (session/exponential-backoff-ms 2 2000 60000))))

  (testing "exponential-backoff-ms caps at max"
    (is (= 60000 (session/exponential-backoff-ms 10 2000 60000))))

  (testing "provider-error-kind classifies canonical auth failures"
    (is (= :auth (session/provider-error-kind :error "401 unauthorized api key invalid" 401)))
    (is (= :auth (session/provider-error-kind :error "forbidden" 403))))

  (testing "provider-error-kind classifies rate limits"
    (is (= :rate-limit (session/provider-error-kind :error "rate limit exceeded" 429)))
    (is (= :rate-limit
           (session/provider-error-kind :error "The usage limit has been reached" nil))))

  (testing "provider-error-kind classifies timeout"
    (is (= :timeout (session/provider-error-kind :error "Timeout waiting for LLM response" nil))))

  (testing "provider-error-kind classifies overloaded"
    (is (= :overloaded (session/provider-error-kind :error "Service Overloaded" nil))))

  (testing "provider-error-kind classifies invalid request"
    (is (= :invalid-request (session/provider-error-kind :error "invalid request body" 400))))

  (testing "provider-error-kind classifies provider unavailable"
    (is (= :provider-unavailable (session/provider-error-kind :error "status 503" 503))))

  (testing "provider-error-kind classifies canonical OpenAI transient server error without http status"
    (is (= :provider-unavailable
           (session/provider-error-kind
            :error
            "An error occurred while processing your request. You can retry your request, or contact us through our help center at help.openai.com if the error persists. Please include the request ID 76b82c17-3fa8-433f-bfd9-54b4d2eafb7f in your message."
            nil)))
    (is (= :provider-unavailable (session/provider-error-kind :error "server_error" nil))))

  (testing "provider-error-kind classifies transport"
    (is (= :transport (session/provider-error-kind :error "Premature end of chunk coded message body: closing chunk expected" nil))))

  (testing "provider-error-kind falls back to unknown"
    (is (= :unknown (session/provider-error-kind :error "mystery failure" nil))))

  (testing "provider-error-kind nil for non-error stop reason"
    (is (nil? (session/provider-error-kind :stop "ignored" nil)))))

(deftest session-entry-test
  (testing "make-entry produces valid entry"
    (let [e (session/make-entry :model {:provider "a" :model-id "m"})]
      (is (session/valid-session-entry? e))
      (is (= :model (:kind e)))
      (is (string? (:id e)))
      (is (inst? (:timestamp e)))))

  (testing "append-entry grows session-entries"
    (let [s (session/initial-session)
          e (session/make-entry :thinking-level {:thinking-level :off})
          s' (session/append-entry s e)]
      (is (= [e] (:session-entries s'))))))
