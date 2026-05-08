(ns psi.agent-session.workflow-invoke-runtime-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.deterministic-operation-registry.registry :as op-reg]
   [psi.session-persistence.core]
   [psi.agent-session.turn]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-attempts]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(defn- create-session-context
  []
  (let [ctx (session/create-context (test-support/safe-context-opts {:persist? false}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(def invoke-definition
  {:definition-id "invoke-proof"
   :name "invoke-proof"
   :steps [{:name "discover"
            :type :invoke
            :operation "github/search-issues-by-label"
            :args {:repo {:from :workflow-input :path [:repo]}
                   :labels {:from :workflow-input :path [:labels]}}
            :outputs {:data {:source :invoke/data}
                      :summary {:source :invoke/summary}
                      :result {:source :invoke/result}}
            :yields {:type :data :data :data}}]})

(def invoke-session-definition
  {:steps [{:name "discover"
            :type :invoke
            :operation "github/search-issues-by-label"
            :args {:repo {:from :workflow-input :path [:repo]}
                   :labels {:from :workflow-input :path [:labels]}}
            :outputs {:data {:source :invoke/data}
                      :summary {:source :invoke/summary}
                      :result {:source :invoke/result}}
            :yields {:type :data :data :data}}
           {:name "report"
            :type :session
            :contributions [{:type :template
                             :text "Review {{issues}} / {{summary}}"
                             :vars {"issues" {:from {:step "discover" :output :data}
                                              :path [:issues]}
                                    "summary" {:from {:step "discover" :yield :data}
                                               :path [:summary]}}}]}]})

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

(deftest invoke-step-executes-through-deterministic-operation-registry-test
  (let [[ctx session-id] (create-session-context)
        calls* (atom [])
        _ (op-reg/register-operation-in!
           (:deterministic-operation-registry ctx)
           {:id "github/search-issues-by-label"
            :handler (fn [{:keys [args workflow-run-id step-id]}]
                       (swap! calls* conj {:args args :run-id workflow-run-id :step-id step-id})
                       {:status :ok
                        :data {:issues [{:id 1 :repo (:repo args)}]}
                        :summary "1 issue"})})
        _ (swap! (:state* ctx)
                 (fn [state]
                   (let [[s _ _] (workflow-runtime/create-run state {:definition invoke-definition
                                                                     :run-id "run-invoke"
                                                                     :workflow-input {:repo "psi"
                                                                                      :labels ["bug"]}})]
                     s)))
        result (workflow-execution/execute-run! ctx session-id "run-invoke")
        run (workflow-runtime/workflow-run-in @(:state* ctx) "run-invoke")
        accepted (get-in run [:step-runs "discover" :accepted-result])]
    (is (= :completed (:status result)))
    (is (= [{:args {:repo "psi" :labels ["bug"]}
             :run-id "run-invoke"
             :step-id "discover"}]
           @calls*))
    (is (= {:repo "psi" :labels ["bug"]}
           (get-in run [:step-runs "discover" :attempts 0 :effective-args])))
    (is (= {:outcome :ok
            :outputs {:data {:issues [{:id 1 :repo "psi"}]}
                      :summary "1 issue"
                      :result {:status :ok
                               :data {:issues [{:id 1 :repo "psi"}]}
                               :summary "1 issue"}}}
           accepted))))

(deftest invoke-step-operation-error-fails-run-test
  (let [[ctx session-id] (create-session-context)
        _ (op-reg/register-operation-in!
           (:deterministic-operation-registry ctx)
           {:id "github/search-issues-by-label"
            :handler (fn [_]
                       {:status :error
                        :reason :not-found
                        :message "repo missing"
                        :details {:repo "psi"}})})
        _ (swap! (:state* ctx)
                 (fn [state]
                   (let [[s _ _] (workflow-runtime/create-run state {:definition invoke-definition
                                                                     :run-id "run-invoke-error"
                                                                     :workflow-input {:repo "psi"
                                                                                      :labels ["bug"]}})]
                     s)))
        result (workflow-execution/execute-run! ctx session-id "run-invoke-error")
        run (workflow-runtime/workflow-run-in @(:state* ctx) "run-invoke-error")]
    (is (= :failed (:status result)))
    (is (= :execution-failed (get-in run [:step-runs "discover" :attempts 0 :status])))
    (is (= {:repo "psi" :labels ["bug"]}
           (get-in run [:step-runs "discover" :attempts 0 :effective-args])))
    (is (= {:reason :not-found
            :message "repo missing"
            :operation-result {:status :error
                               :reason :not-found
                               :message "repo missing"
                               :details {:repo "psi"}}
            :operation-details {:repo "psi"}}
           (get-in run [:step-runs "discover" :attempts 0 :execution-error])))
    (is (nil? (get-in run [:step-runs "discover" :accepted-result])))))

(deftest invoke-to-session-workflow-executes-and-exposes-cross-form-results-test
  (testing "invoke outputs and yields feed downstream session execution through the canonical runtime path"
    (let [[ctx session-id] (create-session-context)
          prompts* (atom [])
          _ (op-reg/register-operation-in!
             (:deterministic-operation-registry ctx)
             {:id "github/search-issues-by-label"
              :handler (fn [{:keys [args]}]
                         {:status :ok
                          :data {:issues [{:id 1 :repo (:repo args)}]
                                 :summary "1 issue found"}
                          :summary "1 issue found"})})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/create-run state {:definition invoke-session-definition
                                                                       :run-id "run-invoke-session"
                                                                       :workflow-input {:repo "psi"
                                                                                        :labels ["bug"]}})]
                       s)))]
      (with-redefs [psi.agent-session.workflow-attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [sid (str (:workflow-step-id opts) "-child")]
                        {:attempt {:attempt-id (str sid "-attempt")
                                   :status :pending
                                   :execution-session-id sid}
                         :execution-session (valid-child-session sid)}))
                    psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      {:execution-result/assistant-message
                       {:role "assistant"
                        :content [{:type :text :text "report output"}]
                        :stop-reason :stop}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-invoke-session")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-invoke-session")
              discover-accepted (get-in run [:step-runs "discover" :accepted-result])
              report-accepted (get-in run [:step-runs "report" :accepted-result])]
          (is (= :completed (:status result)))
          (is (true? (:terminal? result)))
          (is (= 2 (count (:steps-executed result))))
          (is (= {:outcome :ok
                  :outputs {:data {:issues [{:id 1 :repo "psi"}]
                                   :summary "1 issue found"}
                            :summary "1 issue found"
                            :result {:status :ok
                                     :data {:issues [{:id 1 :repo "psi"}]
                                            :summary "1 issue found"}
                                     :summary "1 issue found"}}}
                 discover-accepted))
          (is (= {:outcome :ok
                  :outputs {:text "report output"
                            :final-llm-reply "report output"
                            :transcript nil
                            :result {:outcome :ok
                                     :outputs {:final-llm-reply "report output"
                                               :text "report output"}}}}
                 report-accepted))
          (is (= ["Review [{:id 1, :repo \"psi\"}] / 1 issue found"]
                 (mapv :prompt @prompts*))))))))
