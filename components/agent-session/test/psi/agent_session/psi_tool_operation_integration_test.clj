(ns psi.agent-session.psi-tool-operation-integration-test
  "End-to-end psi-tool `operation` action: validate → dispatch → outer-catch."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tools :as tools]
   [psi.deterministic-operation-registry.registry :as registry]))

(defn- create-session-context
  []
  (let [ctx (session/create-context (test-support/safe-context-opts {:persist? false}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(defn- register-op!
  [ctx op]
  (registry/register-operation-in! (:deterministic-operation-registry ctx) op))

(deftest operation-list-end-to-end
  (let [[ctx session-id] (create-session-context)]
    (register-op! ctx {:id "zeta/op" :description "z" :handler (fn [_] {:status :ok :data 1})})
    (register-op! ctx {:id "alpha/op" :description "a" :handler (fn [_] {:status :ok :data 1})})
    (let [tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "operation" "op" "list"})
          parsed (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :operation (:psi-tool/action parsed)))
      (is (= :list (:psi-tool/operation-op parsed)))
      (is (= [{:id "alpha/op" :description "a"}
              {:id "zeta/op" :description "z"}]
             (:psi-tool/operations parsed))))))

(deftest operation-list-ignores-args-and-id
  (let [[ctx session-id] (create-session-context)]
    (register-op! ctx {:id "alpha/op" :description "a" :handler (fn [_] {:status :ok :data 1})})
    (let [tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "operation" "op" "list"
                                   "operation-id" "ignored" "args" "not-edn-at-all ["})
          parsed (read-string (:content result))]
      (testing "list with malformed args still lists, not an error (D5)"
        (is (false? (:is-error result)))
        (is (= :ok (:psi-tool/overall-status parsed)))))))

(deftest operation-invoke-ok-end-to-end
  (let [[ctx session-id] (create-session-context)]
    (register-op! ctx {:id "alpha/op" :description "a"
                       :handler (fn [invocation] {:status :ok :data (:args invocation)})})
    (let [tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "operation" "op" "invoke"
                                   "operation-id" "alpha/op" "args" "{:x 7}"})
          parsed (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (= "{:x 7}" (-> parsed :psi-tool/result :data))))))

(deftest operation-invoke-default-args
  (let [[ctx session-id] (create-session-context)]
    (register-op! ctx {:id "alpha/op" :description "a"
                       :handler (fn [invocation] {:status :ok :data (:args invocation)})})
    (let [tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "operation" "op" "invoke"
                                   "operation-id" "alpha/op"})
          parsed (read-string (:content result))]
      (is (= "{}" (-> parsed :psi-tool/result :data))))))

(deftest operation-invoke-malformed-args-validate-error
  (let [[ctx session-id] (create-session-context)]
    (register-op! ctx {:id "alpha/op" :description "a" :handler (fn [_] {:status :ok :data 1})})
    (let [tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "operation" "op" "invoke"
                                   "operation-id" "alpha/op" "args" "[1 2 3]"})
          parsed (read-string (:content result))]
      (testing "non-map args → structured operation error, not generic fallback"
        (is (true? (:is-error result)))
        (is (= :operation (:psi-tool/action parsed)))
        (is (= :error (:psi-tool/overall-status parsed)))))))

(deftest operation-invoke-unreadable-args-validate-error
  (let [[ctx session-id] (create-session-context)]
    (register-op! ctx {:id "alpha/op" :description "a" :handler (fn [_] {:status :ok :data 1})})
    (let [tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "operation" "op" "invoke"
                                   "operation-id" "alpha/op" "args" "{:x"})
          parsed (read-string (:content result))]
      (testing "unreadable EDN args → structured operation error, not a crash (D11 surface-parity)"
        (is (true? (:is-error result)))
        (is (= :operation (:psi-tool/action parsed)))
        (is (= :error (:psi-tool/overall-status parsed)))))))

(deftest operation-invoke-unknown-id-error
  (let [[ctx session-id] (create-session-context)
        tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
        result ((:execute tool) {"action" "operation" "op" "invoke"
                                 "operation-id" "no/such" "args" "{}"})
        parsed (read-string (:content result))]
    (is (true? (:is-error result)))
    (is (= :missing-operation (-> parsed :psi-tool/error :kind)))))

(deftest operation-invoke-blank-id-rejected
  (let [[ctx session-id] (create-session-context)
        tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
        result ((:execute tool) {"action" "operation" "op" "invoke"
                                 "operation-id" "" "args" "{}"})
        parsed (read-string (:content result))]
    (is (true? (:is-error result)))
    (is (= :operation (:psi-tool/action parsed)))))

(deftest operation-invalid-op-rejected
  (let [[ctx session-id] (create-session-context)
        tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
        result ((:execute tool) {"action" "operation" "op" "bogus"})
        parsed (read-string (:content result))]
    (is (true? (:is-error result)))
    (is (= :operation (:psi-tool/action parsed)))))
