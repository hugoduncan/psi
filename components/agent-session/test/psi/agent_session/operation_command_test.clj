(ns psi.agent-session.operation-command-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.deterministic-operation-action :as op-action]
   [psi.agent-session.test-support :as test-support]))

(def ^:private create-session-context test-support/create-op-session-context)
(def ^:private register-op! test-support/register-op!)

(defn- dispatch
  [ctx session-id text]
  (commands/dispatch-in ctx session-id text {}))

(deftest operations-lists-sorted
  (let [[ctx session-id] (create-session-context)]
    (register-op! ctx {:id "zeta/op" :description "z desc" :handler (fn [_] {:status :ok :data 1})})
    (register-op! ctx {:id "alpha/op" :description "a desc" :handler (fn [_] {:status :ok :data 1})})
    (let [result (dispatch ctx session-id "/operations")]
      (is (= :text (:type result)))
      (is (= "alpha/op — a desc\nzeta/op — z desc" (:message result))))))

(deftest operations-empty-message
  (let [[ctx session-id] (create-session-context)
        result (dispatch ctx session-id "/operations")]
    (is (= "No deterministic operations registered." (:message result)))))

(deftest operation-invoke-renders-result
  (let [[ctx session-id] (create-session-context)]
    (register-op! ctx {:id "alpha/op" :description "a"
                       :handler (fn [invocation] {:status :ok :data (:args invocation)})})
    (let [result (dispatch ctx session-id "/operation alpha/op {:x 1}")]
      (is (= :text (:type result)))
      (testing "exact line layout: :status first, then keys sorted by pr-str"
        (is (= [":status :ok" ":data {:x 1}"]
               (str/split-lines (:message result))))))))

(deftest operation-invoke-status-line-first
  (let [[ctx session-id] (create-session-context)]
    (register-op! ctx {:id "alpha/op" :description "a"
                       :handler (fn [_] {:status :ok :data 7 :summary "yep"})})
    (let [result (dispatch ctx session-id "/operation alpha/op")
          lines (str/split-lines (:message result))]
      (testing ":status line first, remaining keys sorted by pr-str"
        (is (= ":status :ok" (first lines)))
        (is (= [":data 7" ":summary \"yep\""] (rest lines)))))))

(deftest operation-invoke-renders-details-nested-map
  (let [[ctx session-id] (create-session-context)]
    (register-op! ctx {:id "alpha/details" :description "a"
                       :handler (fn [_] {:status :ok :data 1 :details {:k :v :n 2}})})
    (testing "optional :details (nested map) line appears in :text output (decision #7)"
      (let [result (dispatch ctx session-id "/operation alpha/details")]
        (is (= :text (:type result)))
        (is (str/includes? (:message result)
                           (str ":details " (pr-str {:k :v :n 2}))))))))

(deftest operation-invoke-over-2000-char-value-truncated-identically
  (let [[ctx session-id] (create-session-context)
        big (apply str (repeat 3000 "z"))]
    (register-op! ctx {:id "big/op" :description "huge"
                       :handler (fn [_] {:status :ok :data big})})
    (testing "command surface per-key truncation matches helper marker (decision #9 surface-parity)"
      (let [result (dispatch ctx session-id "/operation big/op")
            expected (op-action/truncate-value (pr-str big))]
        (is (= :text (:type result)))
        (is (str/includes? expected "… (truncated, 3002 chars total)"))
        (is (str/includes? (:message result) (str ":data " expected)))))))

(deftest operation-invoke-default-args
  (let [[ctx session-id] (create-session-context)]
    (register-op! ctx {:id "alpha/op" :description "a"
                       :handler (fn [invocation] {:status :ok :data (:args invocation)})})
    (let [result (dispatch ctx session-id "/operation alpha/op")]
      (is (str/includes? (:message result) ":data {}")))))

(deftest operation-blank-id-usage
  (let [[ctx session-id] (create-session-context)
        result (dispatch ctx session-id "/operation")]
    (is (= "Usage: /operation <id> {edn-args}" (:message result)))))

(deftest operation-bad-args-error
  (let [[ctx session-id] (create-session-context)]
    (register-op! ctx {:id "alpha/op" :description "a" :handler (fn [_] {:status :ok :data 1})})
    (testing "non-map args → clear error, not crash"
      (let [result (dispatch ctx session-id "/operation alpha/op [1 2]")]
        (is (= :text (:type result)))
        (is (str/includes? (:message result) "EDN map"))))
    (testing "unreadable EDN → clear parse error, not crash"
      (let [result (dispatch ctx session-id "/operation alpha/op {:x")]
        (is (= :text (:type result)))
        (is (str/includes? (:message result) "Could not parse"))))))

(deftest operation-unknown-id-error
  (let [[ctx session-id] (create-session-context)
        result (dispatch ctx session-id "/operation no/such {}")]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Unknown deterministic operation"))))

(deftest operation-malformed-result-distinct
  (let [[ctx session-id] (create-session-context)]
    (register-op! ctx {:id "alpha/bad" :description "bad" :handler (fn [_] {:nonsense true})})
    (let [result (dispatch ctx session-id "/operation alpha/bad {}")]
      (is (= :text (:type result)))
      (is (str/includes? (:message result) "malformed result"))
      (is (not (str/includes? (:message result) "Unknown deterministic operation"))))))

(deftest operation-side-effecting-runs
  (let [[ctx session-id] (create-session-context)
        sink (atom nil)]
    (register-op! ctx {:id "side/effect" :description "writes"
                       :handler (fn [invocation]
                                  (reset! sink (:args invocation))
                                  {:status :ok :data :done})})
    (dispatch ctx session-id "/operation side/effect {:n 9}")
    (is (= {:n 9} @sink))))

(deftest operations-vs-operation-precedence
  (let [[ctx session-id] (create-session-context)]
    (register-op! ctx {:id "alpha/op" :description "a" :handler (fn [_] {:status :ok :data 1})})
    (testing "/operations dispatches as list, not as /operation invoke"
      (let [result (dispatch ctx session-id "/operations")]
        (is (str/includes? (:message result) "alpha/op — a"))
        (is (not (str/includes? (:message result) ":status")))))))
