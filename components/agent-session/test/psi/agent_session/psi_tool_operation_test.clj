(ns psi.agent-session.psi-tool-operation-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.deterministic-operation-action :as op-action]
   [psi.agent-session.psi-tool-operation :as psi-tool-operation]
   [psi.deterministic-operation-registry.registry :as registry]))

(defn- make-ctx
  [reg]
  {:deterministic-operation-registry reg
   :state* (atom {:agent-session {:sessions {}}})})

(defn- ok-op
  [id]
  {:id id
   :description (str "desc " id)
   :handler (fn [invocation] {:status :ok :data {:echo (:args invocation)}})})

(deftest list-returns-sorted-operations
  (let [reg (registry/create-registry)]
    (registry/register-operation-in! reg (ok-op "zeta/op"))
    (registry/register-operation-in! reg (ok-op "alpha/op"))
    (let [report (psi-tool-operation/execute-psi-tool-operation-report
                  {:ctx (make-ctx reg) :session-id "s1"} {:op "list"})]
      (is (= :ok (:psi-tool/overall-status report)))
      (is (= [{:id "alpha/op" :description "desc alpha/op"}
              {:id "zeta/op" :description "desc zeta/op"}]
             (:psi-tool/operations report))))))

(deftest list-empty-registry
  (let [report (psi-tool-operation/execute-psi-tool-operation-report
                {:ctx (make-ctx (registry/create-registry)) :session-id "s1"}
                {:op "list"})]
    (is (= [] (:psi-tool/operations report)))
    (is (= :ok (:psi-tool/overall-status report)))))

(deftest invoke-ok-projects-all-keys
  (let [reg (registry/create-registry)]
    (registry/register-operation-in!
     reg {:id "alpha/op"
          :description "ok op"
          :handler (fn [_] {:status :ok :data {:n 1} :summary "did it"})})
    (let [report (psi-tool-operation/execute-psi-tool-operation-report
                  {:ctx (make-ctx reg) :session-id "s1"}
                  {:op "invoke" :operation-id "alpha/op" :args {}})]
      (is (= :ok (:psi-tool/overall-status report)))
      (is (= #{:status :data :summary} (set (keys (:psi-tool/result report)))))
      (is (= ":ok" (-> report :psi-tool/result :status))))))

(deftest invoke-error-sets-overall-status
  (let [reg (registry/create-registry)]
    (registry/register-operation-in!
     reg {:id "alpha/fail"
          :description "errors"
          :handler (fn [_] {:status :error :reason :boom :message "no"})})
    (let [report (psi-tool-operation/execute-psi-tool-operation-report
                  {:ctx (make-ctx reg) :session-id "s1"}
                  {:op "invoke" :operation-id "alpha/fail" :args {}})]
      (is (= :error (:psi-tool/overall-status report)))
      (is (= #{:status :reason :message} (set (keys (:psi-tool/result report))))))))

(deftest invoke-unknown-id-renders-missing-distinctly
  (let [report (psi-tool-operation/execute-psi-tool-operation-report
                {:ctx (make-ctx (registry/create-registry)) :session-id "s1"}
                {:op "invoke" :operation-id "no/such" :args {}})]
    (is (= :error (:psi-tool/overall-status report)))
    (is (= :missing-operation (-> report :psi-tool/error :kind)))
    (is (= "no/such" (-> report :psi-tool/error :operation-id)))))

(deftest invoke-malformed-result-renders-distinctly
  (let [reg (registry/create-registry)]
    (registry/register-operation-in!
     reg {:id "alpha/bad"
          :description "returns garbage"
          :handler (fn [_] {:nonsense true})})
    (let [report (psi-tool-operation/execute-psi-tool-operation-report
                  {:ctx (make-ctx reg) :session-id "s1"}
                  {:op "invoke" :operation-id "alpha/bad" :args {}})]
      (is (= :error (:psi-tool/overall-status report)))
      (is (= :malformed-result (-> report :psi-tool/error :kind)))
      (is (not= :missing-operation (-> report :psi-tool/error :kind))))))

(deftest invoke-side-effecting-op-runs
  (let [reg (registry/create-registry)
        sink (atom nil)]
    (registry/register-operation-in!
     reg {:id "side/effect"
          :description "writes a sink"
          :handler (fn [invocation]
                     (reset! sink (:args invocation))
                     {:status :ok :data :done})})
    (psi-tool-operation/execute-psi-tool-operation-report
     {:ctx (make-ctx reg) :session-id "s1"}
     {:op "invoke" :operation-id "side/effect" :args {:wrote 42}})
    (is (= {:wrote 42} @sink))))

(deftest invoke-over-2000-char-value-truncated-identically
  (let [big (apply str (repeat 3000 "z"))
        reg (registry/create-registry)]
    (registry/register-operation-in!
     reg {:id "big/op"
          :description "huge"
          :handler (fn [_] {:status :ok :data big})})
    (let [report (psi-tool-operation/execute-psi-tool-operation-report
                  {:ctx (make-ctx reg) :session-id "s1"}
                  {:op "invoke" :operation-id "big/op" :args {}})]
      (is (= (op-action/truncate-value (pr-str big))
             (-> report :psi-tool/result :data))))))

(deftest report-includes-duration
  (let [report (psi-tool-operation/execute-psi-tool-operation-report
                {:ctx (make-ctx (registry/create-registry)) :session-id "s1"}
                {:op "list"})]
    (is (number? (:psi-tool/duration-ms report)))))

(deftest missing-ctx-renders-error
  (testing "no ctx → structured error, not crash"
    (let [report (psi-tool-operation/execute-psi-tool-operation-report
                  {:ctx nil :session-id "s1"} {:op "list"})]
      (is (= :error (:psi-tool/overall-status report))))))
