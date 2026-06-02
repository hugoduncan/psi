(ns psi.agent-session.operation-command-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.deterministic-operation-registry.registry :as registry]))

(defn- create-session-context
  []
  (let [ctx (session/create-context (test-support/safe-context-opts {:persist? false}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(defn- register-op!
  [ctx op]
  (registry/register-operation-in! (:deterministic-operation-registry ctx) op))

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
      (is (str/includes? (:message result) ":status :ok"))
      (is (str/includes? (:message result) ":data {:x 1}")))))

(deftest operation-invoke-status-line-first
  (let [[ctx session-id] (create-session-context)]
    (register-op! ctx {:id "alpha/op" :description "a"
                       :handler (fn [_] {:status :ok :data 7 :summary "yep"})})
    (let [result (dispatch ctx session-id "/operation alpha/op")
          lines (str/split-lines (:message result))]
      (testing ":status line first, remaining keys sorted by pr-str"
        (is (= ":status :ok" (first lines)))
        (is (= [":data 7" ":summary \"yep\""] (rest lines)))))))

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
