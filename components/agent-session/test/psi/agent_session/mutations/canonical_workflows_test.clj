(ns psi.agent-session.mutations.canonical-workflows-test
  "Tests for canonical workflow Pathom mutations."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.mutations.canonical-workflows :as cwf-mutations]
   [psi.workflow-runtime.model :as workflow-model]
   [psi.workflow-registry.registry :as workflow-registry]))

(defn- make-test-ctx
  "Create a minimal ctx with a state atom for testing pure mutations."
  ([] (make-test-ctx {}))
  ([initial-state]
   (let [state* (atom (merge {:workflows (workflow-model/initial-workflow-state)} initial-state))]
     {:state* state*
      :execute-workflow-run-fn (fn [_ _ _] {:status :completed :terminal? true :blocked? false :steps-executed []})
      :resume-and-execute-workflow-run-fn (fn [_ _ _] {:status :completed :terminal? true :blocked? false :steps-executed []})})))

(def sample-definition
  {:definition-id "test-workflow"
   :name "test-workflow"
   :summary "A test workflow"
   :description "For testing"
   :steps [{:name "step-1"
            :type :session
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}
                                    "original" {:from :workflow-input :path [:original]}}}]}]})

(deftest register-workflow-definition-test
  (testing "registers a valid definition"
    (let [ctx (make-test-ctx)
          result (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                                 :definition sample-definition})]
      (is (true? (:psi.workflow/registered? result)))
      (is (= "test-workflow" (:psi.workflow/definition-id result)))
      (is (nil? (:psi.workflow/error result)))
      (is (some? (workflow-registry/workflow-definition @(:state* ctx) "test-workflow")))))

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
                                                        :session-id "delegating-session"
                                                        :definition-id "test-workflow"
                                                        :workflow-input {:input "hello" :original "hello"}
                                                        :run-id "run-1"})]
      (is (= "run-1" (:psi.workflow/run-id result)))
      (is (= :pending (:psi.workflow/status result)))
      (is (nil? (:psi.workflow/error result)))
      (is (= "delegating-session"
             (get-in @(:state* ctx) [:workflows :runs "run-1" :parent-session-id])))
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

(deftest terminal-outcome-error-message-test
  (testing "iteration-limit-reached produces actionable error with step, counts, signal, and last result"
    (let [outcome {:outcome :failed
                   :reason :iteration-limit-reached
                   :step-id "compare"
                   :iteration-count 10
                   :max-iterations 10
                   :last-judge-signal "CHANGED"
                   :last-result-text "λx.prefer(compose(transducers))"}
          msg (#'cwf-mutations/terminal-outcome-error-message outcome)]
      (is (string? msg))
      (is (re-find #"Iteration limit reached" msg))
      (is (re-find #"compare" msg))
      (is (re-find #"10 of 10" msg))
      (is (re-find #"CHANGED" msg))
      (is (re-find #"Last result" msg))
      (is (re-find #"transducers" msg))))

  (testing "iteration-limit-reached without optional fields"
    (let [outcome {:outcome :failed
                   :reason :iteration-limit-reached
                   :step-id "check"
                   :iteration-count 5
                   :max-iterations 5}
          msg (#'cwf-mutations/terminal-outcome-error-message outcome)]
      (is (string? msg))
      (is (re-find #"Iteration limit reached" msg))
      (is (not (re-find #"signal" msg)))
      (is (not (re-find #"Last result" msg)))))

  (testing "judge-no-match produces actionable error"
    (let [outcome {:outcome :failed
                   :reason :judge-no-match
                   :step-id "review"
                   :judge-output "MAYBE"}
          msg (#'cwf-mutations/terminal-outcome-error-message outcome)]
      (is (re-find #"did not match" msg))
      (is (re-find #"review" msg))
      (is (re-find #"MAYBE" msg))))

  (testing "unknown failure reason uses generic fallback"
    (let [outcome {:outcome :failed
                   :reason :some-other-reason
                   :step-id "build"}
          msg (#'cwf-mutations/terminal-outcome-error-message outcome)]
      (is (re-find #"some-other-reason" msg))
      (is (re-find #"build" msg))))

  (testing "nil terminal-outcome returns nil"
    (is (nil? (#'cwf-mutations/terminal-outcome-error-message nil))))

  (testing "terminal-outcome with nil :reason uses defensive fallback without NPE"
    (let [outcome {:outcome :failed
                   :reason nil
                   :step-id "build"}
          msg (#'cwf-mutations/terminal-outcome-error-message outcome)]
      (is (string? msg))
      (is (re-find #"build" msg))
      (is (not (re-find #"null" (str msg))))))

  (testing "run-failure-error falls through to terminal-outcome when no step errors"
    (let [exec-result {:status :failed :steps-executed [{:step-id "a" :error nil}]}
          final-run {:terminal-outcome {:outcome :failed
                                        :reason :iteration-limit-reached
                                        :step-id "a"
                                        :iteration-count 3
                                        :max-iterations 3}}
          msg (#'cwf-mutations/run-failure-error exec-result final-run)]
      (is (re-find #"Iteration limit reached" msg))))

  (testing "empty-string last-result-text produces no Last result header"
    (let [outcome {:outcome :failed
                   :reason :iteration-limit-reached
                   :step-id "check"
                   :iteration-count 3
                   :max-iterations 3
                   :last-result-text ""}
          msg (#'cwf-mutations/terminal-outcome-error-message outcome)]
      (is (string? msg))
      (is (not (re-find #"Last result" msg))
          "Empty last-result-text should not produce a dangling 'Last result:' header")))

  (testing "long last-result-text is truncated with marker"
    (let [long-text (apply str (repeat 3000 "x"))
          outcome {:outcome :failed
                   :reason :iteration-limit-reached
                   :step-id "check"
                   :iteration-count 3
                   :max-iterations 3
                   :last-result-text long-text}
          msg (#'cwf-mutations/terminal-outcome-error-message outcome)]
      (is (string? msg))
      (is (re-find #"Last result" msg))
      (is (re-find #"\[truncated\]" msg)
          "Long text should be truncated with [truncated] marker")
      (is (<= (count msg) (+ 2200 100))
          "Total message length should be bounded (2000 chars of text + overhead)")))

  (testing "run-failure-error returns nil when no step errors and no terminal-outcome"
    (let [exec-result {:status :failed :steps-executed [{:step-id "a" :error nil}]}
          final-run {}
          msg (#'cwf-mutations/run-failure-error exec-result final-run)]
      (is (nil? msg)
          "Documents current behaviour: :judge/no-match path produces no terminal-outcome, so run-failure-error returns nil")))

  (testing "run-failure-error prefers step errors over terminal-outcome"
    (let [exec-result {:status :failed :steps-executed [{:step-id "a" :error "step blew up"}]}
          final-run {:terminal-outcome {:outcome :failed
                                        :reason :iteration-limit-reached
                                        :step-id "a"
                                        :iteration-count 3
                                        :max-iterations 3}}
          msg (#'cwf-mutations/run-failure-error exec-result final-run)]
      (is (= "step blew up" msg)))))
