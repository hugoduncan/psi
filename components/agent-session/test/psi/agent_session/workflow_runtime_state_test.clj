(ns psi.agent-session.workflow-runtime-state-test
  "Unit tests for built-in lifecycle callback registration and invocation
   in psi.agent-session.workflow.runtime-state."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [psi.agent-session.workflow.runtime-state :as runtime-state]))

(defn reset-lifecycle-callbacks-fixture
  [f]
  (reset! runtime-state/built-in-lifecycle-callbacks {})
  (f)
  (reset! runtime-state/built-in-lifecycle-callbacks {}))

(use-fixtures :each reset-lifecycle-callbacks-fixture)

;; ── register-built-in-lifecycle-callback! ──────────────────────────────────

(deftest register-built-in-lifecycle-callback-test
  (testing "registers handler for event name"
    (let [handler (fn [_] :ok)]
      (runtime-state/register-built-in-lifecycle-callback! "session_switch" handler)
      (is (= handler (get @runtime-state/built-in-lifecycle-callbacks "session_switch")))))

  (testing "replaces prior handler for same event name"
    (let [h1 (fn [_] :first)
          h2 (fn [_] :second)]
      (runtime-state/register-built-in-lifecycle-callback! "session_switch" h1)
      (runtime-state/register-built-in-lifecycle-callback! "session_switch" h2)
      (is (= h2 (get @runtime-state/built-in-lifecycle-callbacks "session_switch")))))

  (testing "independent event names are stored separately"
    (let [h1 (fn [_] :one)
          h2 (fn [_] :two)]
      (runtime-state/register-built-in-lifecycle-callback! "session_switch" h1)
      (runtime-state/register-built-in-lifecycle-callback! "other_event" h2)
      (is (= h1 (get @runtime-state/built-in-lifecycle-callbacks "session_switch")))
      (is (= h2 (get @runtime-state/built-in-lifecycle-callbacks "other_event"))))))

;; ── invoke-built-in-lifecycle! ─────────────────────────────────────────────

(deftest invoke-built-in-lifecycle-delivers-payload-test
  (testing "invokes registered handler with event payload"
    (let [received (atom nil)]
      (runtime-state/register-built-in-lifecycle-callback!
       "session_switch"
       (fn [event] (reset! received event) :invoked))
      (runtime-state/invoke-built-in-lifecycle! "session_switch" {:reason :new})
      (is (= {:reason :new} @received)))))

(deftest invoke-built-in-lifecycle-returns-handler-value-test
  (testing "returns handler return value"
    (runtime-state/register-built-in-lifecycle-callback!
     "session_switch"
     (fn [_] :handler-result))
    (is (= :handler-result
           (runtime-state/invoke-built-in-lifecycle! "session_switch" {})))))

(deftest invoke-built-in-lifecycle-no-handler-test
  (testing "returns nil when no handler registered for event"
    (is (nil? (runtime-state/invoke-built-in-lifecycle! "unregistered_event" {:reason :new}))))

  (testing "returns nil when callbacks atom is empty"
    (is (nil? (runtime-state/invoke-built-in-lifecycle! "session_switch" {:reason :new})))))

(deftest invoke-built-in-lifecycle-error-test
  (testing "catches handler exceptions and returns error map"
    (runtime-state/register-built-in-lifecycle-callback!
     "session_switch"
     (fn [_] (throw (Exception. "handler boom"))))
    (let [result (runtime-state/invoke-built-in-lifecycle! "session_switch" {})]
      (is (map? result))
      (is (= "handler boom" (:error result))))))

(deftest invoke-built-in-lifecycle-wrong-event-test
  (testing "does not invoke handler for a different event name"
    (let [called? (atom false)]
      (runtime-state/register-built-in-lifecycle-callback!
       "session_switch"
       (fn [_] (reset! called? true)))
      (runtime-state/invoke-built-in-lifecycle! "other_event" {})
      (is (false? @called?)))))