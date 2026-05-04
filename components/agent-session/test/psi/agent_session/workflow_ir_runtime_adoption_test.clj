(ns psi.agent-session.workflow-ir-runtime-adoption-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.prompt-control]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-attempts]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(def runtime-ref-definition
  {:definition-id "runtime-ref"
   :name "runtime-ref"
   :step-order ["step-1"]
   :steps {"step-1" {:executor {:type :agent :profile "planner"}
                     :prompt-template "Status: $STATUS"
                     :input-bindings {:status {:source :workflow-runtime
                                               :path [:status]}}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                     :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}})

(def plan-build-definition
  {:definition-id "plan-build"
   :name "plan-build"
   :step-order ["plan" "build"]
   :steps {"plan" {:executor {:type :agent :profile "planner"}
                   :prompt-template "$INPUT"
                   :input-bindings {:input {:source :workflow-input :path [:input]}}
                   :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                   :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}
           "build" {:executor {:type :agent :profile "builder"}
                    :prompt-template "Build: $INPUT"
                    :input-bindings {:input {:source :step-output :path ["plan" :outputs :text]}}
                    :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                    :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}})

(defn- valid-child-session
  [child-session-id]
  {:session-id child-session-id
   :name child-session-id
   :messages []
   :message-history []
   :is-streaming false
   :tool-results []
   :tool-defs []
   :skills []
   :thinking-level :off
   :cwd "/tmp"
   :worktree-path "/tmp"
   :context []
   :agent {:messages []}
   :statechart {:phase :idle}})

(deftest create-run-rejects-compiled-runtime-refs-test
  (testing "run creation fails fast when current grammar compiles to non-canonical workflow-runtime refs"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"execution-valid canonical IR"
         (workflow-runtime/create-run
          {:workflows {:definitions {} :runs {} :run-order []}}
          {:definition runtime-ref-definition
           :run-id "runtime-ref-run"}))))

  (testing "registered definitions with runtime refs also fail at run creation seam"
    (let [[state1 definition-id _]
          (workflow-runtime/register-definition {:workflows {:definitions {} :runs {} :run-order []}}
                                                runtime-ref-definition)]
      (is (= "runtime-ref" definition-id))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"execution-valid canonical IR"
           (workflow-runtime/create-run state1 {:definition-id definition-id
                                                :run-id "runtime-ref-run"}))))))

(deftest execution-uses-ir-session-template-vars-test
  (testing "execution materializes prompts from canonical IR contributions rather than authored step maps"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state plan-build-definition)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "plan-build"
                                                                   :run-id "run-ir"
                                                                   :workflow-input {:input "ship it"}})]
                       s)))
          prompts* (atom [])
          responses* (atom ["plan output" "build output"])]
      (with-redefs [psi.agent-session.workflow-attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [sid (str (:workflow-step-id opts) "-child")]
                        {:attempt {:attempt-id (str sid "-attempt")
                                   :status :pending
                                   :execution-session-id sid}
                         :execution-session (valid-child-session sid)}))
                    psi.agent-session.prompt-control/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      {:execution-result/assistant-message
                       {:content (let [resp (first @responses*)]
                                   (swap! responses* subvec 1)
                                   resp)}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-ir")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-ir")]
          (is (= :completed (:status result)))
          (is (= {:outcome :ok :outputs {:text "build output"}}
                 (get-in run [:step-runs "build" :accepted-result])))
          (is (= ["ship it" "Build: plan output"]
                 (mapv :prompt @prompts*))))))))
