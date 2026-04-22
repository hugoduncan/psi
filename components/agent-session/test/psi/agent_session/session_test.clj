(ns psi.agent-session.session-test
  "Tests for pure session data model, schemas, and derived predicates."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.session :as session]))

;; ── Schema validation ───────────────────────────────────────────────────────

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
      (is (= [] (:prompt-contributions s)))
      (is (= [] (:steering-messages s)))
      (is (= [] (:follow-up-messages s)))
      (is (= 0 (:retry-attempt s)))
      (is (= {:schedules {}
              :queue []}
             (:scheduler s))))))

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

;; ── Derived predicates ──────────────────────────────────────────────────────

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

  (testing "retry-error? true for overloaded"
    (is (session/retry-error? :error "Service Overloaded")))

  (testing "retry-error? false for stop reason"
    (is (not (session/retry-error? :stop nil))))

  (testing "retry-error? false for nil error"
    (is (not (session/retry-error? :error nil))))

  (testing "context-overflow-error? true for context length"
    (is (session/context-overflow-error? "context length exceeded")))

  (testing "context-overflow-error? false for random string"
    (is (not (session/context-overflow-error? "timeout error"))))

  (testing "exponential-backoff-ms doubles with attempt"
    (is (= 2000 (session/exponential-backoff-ms 0 2000 60000)))
    (is (= 4000 (session/exponential-backoff-ms 1 2000 60000)))
    (is (= 8000 (session/exponential-backoff-ms 2 2000 60000))))

  (testing "exponential-backoff-ms caps at max"
    (is (= 60000 (session/exponential-backoff-ms 10 2000 60000)))))

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
