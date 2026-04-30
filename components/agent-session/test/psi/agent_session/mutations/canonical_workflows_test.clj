(ns psi.agent-session.mutations.canonical-workflows-test
  "Tests for canonical workflow Pathom mutations."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.mutations.canonical-workflows :as cwf-mutations]
   [psi.agent-session.workflow-model :as workflow-model]))

;;; Test helpers

(defn- make-test-ctx
  "Create a minimal ctx with a state atom for testing pure mutations."
  ([] (make-test-ctx {}))
  ([initial-state]
   (let [state* (atom (merge {:workflows (workflow-model/initial-workflow-state)} initial-state))]
     {:state* state*
      ;; Stub execution fns (not needed for pure state mutations)
      :execute-workflow-run-fn (fn [_ _ _] {:status :completed :terminal? true :blocked? false :steps-executed []})
      :resume-and-execute-workflow-run-fn (fn [_ _ _] {:status :completed :terminal? true :blocked? false :steps-executed []})})))

(def sample-definition
  {:definition-id "test-workflow"
   :name "test-workflow"
   :summary "A test workflow"
   :description "For testing"
   :step-order ["step-1"]
   :steps {"step-1" {:label "step-1"
                     :executor {:type :agent :profile "test"}
                     :prompt-template "$INPUT"
                     :input-bindings {:input {:source :workflow-input :path [:input]}
                                      :original {:source :workflow-input :path [:original]}}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                     :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}}}})

;;; Tests

(deftest register-workflow-definition-test
  (testing "registers a valid definition"
    (let [ctx (make-test-ctx)
          result (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                                 :definition sample-definition})]
      (is (true? (:psi.workflow/registered? result)))
      (is (= "test-workflow" (:psi.workflow/definition-id result)))
      (is (nil? (:psi.workflow/error result)))
      ;; Verify in state
      (is (some? (get-in @(:state* ctx) [:workflows :definitions "test-workflow"])))))

  (testing "returns error for invalid definition"
    (let [ctx (make-test-ctx)
          result (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                                 :definition {:bad "data"}})]
      (is (false? (:psi.workflow/registered? result)))
      (is (string? (:psi.workflow/error result))))))

(deftest create-workflow-run-test
  (testing "creates a run from a registered definition"
    (let [ctx (make-test-ctx)
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          result (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :definition-id "test-workflow"
                                                        :workflow-input {:input "hello" :original "hello"}
                                                        :run-id "run-1"})]
      (is (= "run-1" (:psi.workflow/run-id result)))
      (is (= :pending (:psi.workflow/status result)))
      (is (nil? (:psi.workflow/error result)))
      ;; Verify run exists in state
      (is (some? (get-in @(:state* ctx) [:workflows :runs "run-1"])))))

  (testing "returns error for unknown definition"
    (let [ctx (make-test-ctx)
          result (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :definition-id "nonexistent"
                                                        :workflow-input {}})]
      (is (nil? (:psi.workflow/run-id result)))
      (is (string? (:psi.workflow/error result))))))

(deftest execute-workflow-run-test
  (testing "executes a pending run to completion"
    (let [ctx (assoc (make-test-ctx)
                     :execute-workflow-run-fn
                     (fn [ctx* _session-id run-id]
                       (swap! (:state* ctx*) assoc-in [:workflows :runs run-id :status] :completed)
                       {:status :completed :terminal? true :blocked? false :steps-executed []}))
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :definition-id "test-workflow"
                                                   :workflow-input {:input "hello" :original "hello"}
                                                   :run-id "run-1"})
          _ (swap! (:state* ctx) assoc-in [:workflows :runs "run-1" :step-runs "step-1" :accepted-result]
                   {:outcome :ok :outputs {:text "final reply"}})
          result (cwf-mutations/execute-workflow-run {} {:psi/agent-session-ctx ctx
                                                         :session-id "parent-session"
                                                         :run-id "run-1"})]
      (is (= "run-1" (:psi.workflow/run-id result)))
      (is (= :completed (:psi.workflow/status result)))
      (is (true? (:psi.workflow/terminal? result)))
      (is (= "final reply" (:psi.workflow/result result)))
      (is (nil? (:psi.workflow/error result)))))

  (testing "blank accepted-result text is treated as missing"
    (let [ctx (assoc (make-test-ctx)
                     :execute-workflow-run-fn
                     (fn [ctx* _session-id run-id]
                       (swap! (:state* ctx*) assoc-in [:workflows :runs run-id :status] :completed)
                       {:status :completed :terminal? true :blocked? false :steps-executed []}))
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :definition-id "test-workflow"
                                                   :workflow-input {:input "hello" :original "hello"}
                                                   :run-id "run-1"})
          _ (swap! (:state* ctx) assoc-in [:workflows :runs "run-1" :step-runs "step-1" :accepted-result]
                   {:outcome :ok :outputs {:text "   "}})
          result (cwf-mutations/execute-workflow-run {} {:psi/agent-session-ctx ctx
                                                         :session-id "parent-session"
                                                         :run-id "run-1"})]
      (is (= :completed (:psi.workflow/status result)))
      (is (nil? (:psi.workflow/result result))))))

(deftest resume-workflow-run-test
  (testing "resume-workflow-run updates workflow input before resuming when provided"
    (let [captured-run (atom nil)
          ctx (assoc (make-test-ctx)
                     :resume-and-execute-workflow-run-fn
                     (fn [ctx* _session-id run-id]
                       (reset! captured-run (get-in @(:state* ctx*) [:workflows :runs run-id]))
                       {:status :completed :terminal? true :blocked? false :steps-executed []}))
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :definition-id "test-workflow"
                                                   :workflow-input {:input "old" :original "old"}
                                                   :run-id "run-1"})
          _ (swap! (:state* ctx) assoc-in [:workflows :runs "run-1" :status] :blocked)
          result (cwf-mutations/resume-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :session-id "parent-session"
                                                        :run-id "run-1"
                                                        :workflow-input {:input "new" :original "new"}})]
      (is (= "run-1" (:psi.workflow/run-id result)))
      (is (= :completed (:psi.workflow/status result)))
      (is (nil? (:psi.workflow/error result)))
      (is (= {:input "new" :original "new"}
             (:workflow-input @captured-run))))))

(deftest cancel-workflow-run-test
  (testing "cancels a pending run"
    (let [ctx (make-test-ctx)
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :definition-id "test-workflow"
                                                   :workflow-input {:input "hello" :original "hello"}
                                                   :run-id "run-1"})
          result (cwf-mutations/cancel-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :run-id "run-1"
                                                        :reason "test cancel"})]
      (is (= "run-1" (:psi.workflow/run-id result)))
      (is (= :cancelled (:psi.workflow/status result)))
      (is (nil? (:psi.workflow/error result)))))

  (testing "returns error for nonexistent run"
    (let [ctx (make-test-ctx)
          result (cwf-mutations/cancel-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :run-id "ghost"})]
      (is (string? (:psi.workflow/error result)))))

  (testing "returns error for already-terminal run"
    (let [ctx (make-test-ctx)
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :definition-id "test-workflow"
                                                   :workflow-input {}
                                                   :run-id "run-1"})
          _ (cwf-mutations/cancel-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :run-id "run-1"})
          result (cwf-mutations/cancel-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :run-id "run-1"})]
      (is (string? (:psi.workflow/error result))))))

(deftest remove-workflow-run-test
  (testing "removes an existing run from canonical state"
    (let [ctx (make-test-ctx)
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :definition-id "test-workflow"
                                                   :workflow-input {}
                                                   :run-id "run-1"})
          result (cwf-mutations/remove-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :run-id "run-1"})]
      (is (= "run-1" (:psi.workflow/run-id result)))
      (is (true? (:psi.workflow/removed? result)))
      (is (nil? (:psi.workflow/error result)))
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "run-1"])))
      (is (= [] (get-in @(:state* ctx) [:workflows :run-order])))))

  (testing "returns error for nonexistent run"
    (let [ctx (make-test-ctx)
          result (cwf-mutations/remove-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :run-id "ghost"})]
      (is (false? (:psi.workflow/removed? result)))
      (is (string? (:psi.workflow/error result))))))

(deftest list-workflow-definitions-test
  (testing "lists registered definitions"
    (let [ctx (make-test-ctx)
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          result (cwf-mutations/list-workflow-definitions {} {:psi/agent-session-ctx ctx})]
      (is (= 1 (:psi.workflow/definition-count result)))
      (is (= ["test-workflow"] (mapv :definition-id (:psi.workflow/definitions result)))))))

(deftest list-workflow-runs-test
  (testing "lists created runs"
    (let [ctx (make-test-ctx)
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :definition-id "test-workflow"
                                                   :workflow-input {}
                                                   :run-id "run-1"})
          result (cwf-mutations/list-workflow-runs {} {:psi/agent-session-ctx ctx})]
      (is (= 1 (:psi.workflow/run-count result)))
      (is (= ["run-1"] (mapv :run-id (:psi.workflow/runs result)))))))
