(ns psi.agent-session.resolvers-test
  "Tests for canonical telemetry resolvers (Step 7a).

   Each test asserts that a direct EQL query against a fresh session context
   returns a well-typed value for the target attribute — no resolver error."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]))

;; ── helpers ─────────────────────────────────────────────

(defn- q
  "Run EQL query against a fresh session context."
  [eql]
  (session/query-in (session/create-context {:persist? false}) eql))

;; ── :psi.agent-session/messages-count ───────────────────

(deftest messages-count-resolver-test
  (testing "messages-count is an integer for a fresh session"
    (let [result (q [:psi.agent-session/messages-count])]
      (is (integer? (:psi.agent-session/messages-count result)))
      (is (zero? (:psi.agent-session/messages-count result))
          "fresh session has no messages"))))

;; ── :psi.agent-session/tool-call-count ──────────────────

(deftest tool-call-count-resolver-test
  (testing "tool-call-count is an integer for a fresh session"
    (let [result (q [:psi.agent-session/tool-call-count])]
      (is (integer? (:psi.agent-session/tool-call-count result)))
      (is (zero? (:psi.agent-session/tool-call-count result))
          "fresh session has no tool calls"))))

;; ── :psi.agent-session/start-time ───────────────────────

(deftest start-time-resolver-test
  (testing "start-time is an Instant"
    (let [result (q [:psi.agent-session/start-time])]
      (is (instance? java.time.Instant (:psi.agent-session/start-time result)))))

  (testing "start-time is before or equal to current-time"
    (let [result (q [:psi.agent-session/start-time
                     :psi.agent-session/current-time])
          start  (:psi.agent-session/start-time result)
          now    (:psi.agent-session/current-time result)]
      (is (not (.isAfter ^java.time.Instant start ^java.time.Instant now))
          "start-time should not be after current-time"))))

;; ── :psi.agent-session/current-time ─────────────────────

(deftest current-time-resolver-test
  (testing "current-time is an Instant"
    (let [result (q [:psi.agent-session/current-time])]
      (is (instance? java.time.Instant (:psi.agent-session/current-time result)))))

  (testing "current-time is recent (within 60 seconds)"
    (let [result (q [:psi.agent-session/current-time])
          now    (java.time.Instant/now)
          ct     (:psi.agent-session/current-time result)
          diff   (Math/abs (- (.toEpochMilli now) (.toEpochMilli ^java.time.Instant ct)))]
      (is (< diff 60000) "current-time should be within 60s of system clock"))))

;; ── Combined query (mirrors the failing eql_query pattern) ──

(deftest combined-telemetry-query-test
  (testing "all four canonical telemetry attrs succeed in one query"
    (let [result (q [:psi.agent-session/messages-count
                     :psi.agent-session/tool-call-count
                     :psi.agent-session/start-time
                     :psi.agent-session/current-time])]
      (is (integer? (:psi.agent-session/messages-count result)))
      (is (integer? (:psi.agent-session/tool-call-count result)))
      (is (instance? java.time.Instant (:psi.agent-session/start-time result)))
      (is (instance? java.time.Instant (:psi.agent-session/current-time result))))))

;; ── Mixed with existing attrs (regression) ──────────────

(deftest mixed-attrs-query-test
  (testing "new attrs compose with existing stable attrs"
    (let [result (q [:psi.agent-session/phase
                     :psi.agent-session/model
                     :psi.agent-session/session-id
                     :psi.agent-session/messages-count
                     :psi.agent-session/tool-call-count
                     :psi.agent-session/start-time
                     :psi.agent-session/current-time])]
      (is (keyword? (:psi.agent-session/phase result)))
      (is (integer? (:psi.agent-session/messages-count result)))
      (is (integer? (:psi.agent-session/tool-call-count result)))
      (is (instance? java.time.Instant (:psi.agent-session/start-time result)))
      (is (instance? java.time.Instant (:psi.agent-session/current-time result))))))
