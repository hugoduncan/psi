(ns psi.agent-session.deterministic-operation-action-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.deterministic-operation-action :as op-action]
   [psi.agent-session.test-support :as test-support]
   [psi.deterministic-operation-registry.registry :as registry]))

(def ^:private make-ctx test-support/make-op-ctx)
(def ^:private ok-op test-support/ok-op)

(deftest list-operations-sorted-by-id
  (let [reg (registry/create-registry)]
    (registry/register-operation-in! reg (ok-op "zeta/op"))
    (registry/register-operation-in! reg (ok-op "alpha/op"))
    (registry/register-operation-in! reg (ok-op "mid/op"))
    (let [ctx (make-ctx reg)]
      (is (= [{:id "alpha/op" :description "desc for alpha/op"}
              {:id "mid/op" :description "desc for mid/op"}
              {:id "zeta/op" :description "desc for zeta/op"}]
             (op-action/list-operations ctx))))))

(deftest list-operations-empty-registry
  (let [ctx (make-ctx (registry/create-registry))]
    (is (= [] (op-action/list-operations ctx)))))

(deftest invoke-operation-returns-ok-result
  (let [reg (registry/create-registry)]
    (registry/register-operation-in! reg (ok-op "alpha/op"))
    (let [ctx (make-ctx reg)
          result (op-action/invoke-operation ctx "sess-1" "alpha/op" {:x 1})]
      (is (= :ok (:status result)))
      (is (= {:x 1} (-> result :data :echo :args))))))

(deftest invoke-operation-injects-operation-id-positionally
  (let [reg (registry/create-registry)
        captured (atom nil)]
    (registry/register-operation-in!
     reg {:id "alpha/op"
          :description "captures invocation"
          :handler (fn [invocation]
                     (reset! captured invocation)
                     {:status :ok :data :captured})})
    (let [ctx (make-ctx reg)]
      (op-action/invoke-operation ctx "sess-1" "alpha/op" {:x 1})
      (testing "runtime injects :operation-id"
        (is (= "alpha/op" (:operation-id @captured))))
      (testing "caller map carries no workflow ids"
        (is (not (contains? @captured :workflow-run-id)))
        (is (not (contains? @captured :step-id))))
      (testing "caller map carries identity for direct call"
        (is (= "sess-1" (:session-id @captured)))
        (is (= {:x 1} (:args @captured)))))))

(deftest build-invocation-omits-operation-id
  (let [ctx (make-ctx (registry/create-registry))
        invocation (op-action/build-invocation ctx "sess-1" {:a 1})]
    (is (not (contains? invocation :operation-id)))
    (is (not (contains? invocation :workflow-run-id)))
    (is (not (contains? invocation :step-id)))
    (is (= {:a 1} (:args invocation)))
    (is (= "sess-1" (:session-id invocation)))))

(deftest build-invocation-defaults-args
  (let [ctx (make-ctx (registry/create-registry))]
    (is (= {} (:args (op-action/build-invocation ctx "sess-1" nil))))))

(deftest build-invocation-parent-session-id-conditional
  (testing "absent when session has no parent"
    (let [ctx (make-ctx (registry/create-registry)
                        {"sess-1" {:data {}}})]
      (is (not (contains? (op-action/build-invocation ctx "sess-1" {})
                          :parent-session-id)))))
  (testing "present when session-data has a parent"
    (let [ctx (make-ctx (registry/create-registry)
                        {"sess-1" {:data {:parent-session-id "parent-1"}}})]
      (is (= "parent-1"
             (:parent-session-id (op-action/build-invocation ctx "sess-1" {})))))))

(deftest invoke-operation-error-result-passes-through
  (let [reg (registry/create-registry)]
    (registry/register-operation-in!
     reg {:id "alpha/fail"
          :description "always errors"
          :handler (fn [_]
                     {:status :error :reason :boom :message "kaboom"})})
    (let [ctx (make-ctx reg)
          result (op-action/invoke-operation ctx "sess-1" "alpha/fail" {})]
      (is (= :error (:status result)))
      (is (= :boom (:reason result)))
      (is (= "kaboom" (:message result))))))

(deftest invoke-operation-unknown-id-throws-missing
  (let [ctx (make-ctx (registry/create-registry))
        e (try (op-action/invoke-operation ctx "sess-1" "no/such" {})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :missing-deterministic-operation (:type (ex-data e))))))

(deftest truncate-value-under-limit-unchanged
  (let [s (apply str (repeat 100 "x"))]
    (is (= s (op-action/truncate-value s)))))

(deftest truncate-value-over-limit-marked
  (let [s (apply str (repeat 2500 "x"))
        out (op-action/truncate-value s)]
    (is (= (str (subs s 0 2000) " … (truncated, 2500 chars total)") out))))

(deftest project-result-includes-all-keys-pr-str
  (let [result {:status :ok :data {:a 1} :summary "ok!"}
        projected (op-action/project-result result)]
    (is (= #{:status :data :summary} (set (keys projected))))
    (is (= ":ok" (:status projected)))
    (is (= "{:a 1}" (:data projected)))
    (is (= "\"ok!\"" (:summary projected)))))

(deftest project-result-truncates-oversized-value
  (let [big (apply str (repeat 3000 "y"))
        projected (op-action/project-result {:status :ok :data big})
        rendered (pr-str big)]
    (is (= (op-action/truncate-value rendered) (:data projected)))
    (is (> (count rendered) 2000))))

(deftest project-result-includes-details-nested-map
  (testing "optional :details (nested map) is projected via pr-str (decision #7)"
    (let [result {:status :ok :data {:a 1} :details {:k :v :n 2}}
          projected (op-action/project-result result)]
      (is (contains? projected :details))
      (is (= (pr-str {:k :v :n 2}) (:details projected))))))
