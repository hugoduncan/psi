(ns psi.agent-session.workflow-statechart-runtime-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.turn]
   [psi.workflow-runtime.turn-execution-contract]
   [psi.agent-session.test-support :as test-support]
   [psi.workflow-runtime.attempts]
   [psi.agent-session.workflow-judge]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-registry.registry :as workflow-registry]
   [psi.workflow-runtime.statechart-runtime :as runtime]))

(defn- create-session-context
  []
  (let [ctx (session/create-context (test-support/safe-context-opts {:persist? false}))
        sd (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(def linear-definition
  {:definition-id "linear"
   :steps [{:name "plan"
            :type :session
            :contributions [{:type :template
                             :text "Plan {{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]}
           {:name "build"
            :type :session
            :contributions [{:type :template
                             :text "Build {{plan}}"
                             :vars {"plan" {:from {:step "plan" :yield :text}}}}]}]})

(def judged-definition
  {:definition-id "judged"
   :steps [{:name "plan"
            :type :session
            :contributions [{:type :template
                             :text "Plan {{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]}
           {:name "review"
            :type :session
            :contributions [{:type :template
                             :text "Review {{plan}}"
                             :vars {"plan" {:from {:step "plan" :yield :text}}}}]
            :judge {:type :llm
                    :contributions [{:type :template
                                     :text "APPROVED or REVISE?"
                                     :vars {}}]}
            :on {"APPROVED" {:goto :done}
                 "REVISE" {:goto "build" :max-iterations 3}}}
           {:name "build"
            :type :session
            :contributions [{:type :template
                             :text "Build {{review}}"
                             :vars {"review" {:from {:step "review" :yield :text}}}}]}]})

(defn- install-run!
  [ctx definition run-id]
  (swap! (:state* ctx)
         (fn [state]
           (let [[s _ _] (workflow-registry/register-definition state definition)
                 [s _ _] (workflow-runtime/create-run s {:definition-id (:definition-id definition)
                                                         :run-id run-id
                                                         :workflow-input {:input "ship it"
                                                                          :original {:ticket 123}}})]
             s))))

(defn- with-stubbed-runtime
  [{:keys [assistant-text judge-result]} f]
  (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                (fn [_ctx _parent-session-id opts]
                  (let [sid (str (:workflow-step-id opts) "-child")]
                    {:attempt {:attempt-id (:attempt-id opts)
                               :status :pending
                               :execution-session-id sid}
                     :execution-session {:session-id sid}}))
                psi.agent-session.turn/prompt-execution-result-in!
                (fn [_ctx _sid _prompt]
                  {:execution-result/assistant-message
                   {:role "assistant"
                    :content [{:type :text :text assistant-text}]
                    :stop-reason :stop}})
                psi.agent-session.workflow-judge/execute-judge!
                (fn [& _] judge-result)]
    (f)))

(deftest create-working-memory-test
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx linear-definition "run-1")
        wm (runtime/create-working-memory ctx session-id "run-1")]
    (is (= "run-1" (:workflow-run-id wm)))
    (is (= session-id (:parent-session-id wm)))
    (is (= "plan" (:current-step-id wm)))
    (is (= {"plan" 0 "build" 0} (:iteration-counts wm)))))

(deftest start-linear-run-completes-test
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx linear-definition "run-2")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-2")
        prompts* (atom [])]
    (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                  (fn [_ctx _parent-session-id opts]
                    (let [sid (str (:workflow-step-id opts) "-child")]
                      {:attempt {:attempt-id (:attempt-id opts)
                                 :status :pending
                                 :execution-session-id sid}
                       :execution-session {:session-id sid}}))
                  psi.agent-session.turn/prompt-execution-result-in!
                  (fn [_ctx sid prompt]
                    (swap! prompts* conj {:session-id sid :prompt prompt})
                    {:execution-result/assistant-message
                     {:role "assistant"
                      :content [{:type :text :text (if (= sid "plan-child") "plan text" "build text")}]
                      :stop-reason :stop}})]
      (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-2")]
      (is (= :completed (:status run)))
      (is (= ["Plan ship it" "Build plan text"]
             (mapv :prompt @prompts*)))
      (is (= "build text"
             (get-in run [:step-runs "build" :accepted-result :outputs :final-llm-reply]))))))

(deftest statechart-runtime-materializes-canonical-session-contributions-test
  (let [[ctx session-id] (create-session-context)
        definition {:definition-id "statechart-session-contributions"
                    :steps [{:name "plan"
                             :type :session
                             :contributions [{:type :template
                                              :text "Plan {{input}}"
                                              :vars {"input" {:from :workflow-input :path [:input]}}}]}
                            {:name "review"
                             :type :session
                             :contributions [{:type :source
                                              :from :workflow-original}
                                             {:type :source
                                              :from {:step "plan" :yield :text}}
                                             {:type :template
                                              :text "Review {{reply}}"
                                              :vars {"reply" {:from {:step "plan" :output :final-llm-reply}}}}]}]}
        created* (atom [])
        prompts* (atom [])]
    (install-run! ctx definition "run-statechart-session-contributions")
    (swap! (:state* ctx)
           (fn [state]
             (-> state
                 (assoc-in [:workflows :runs "run-statechart-session-contributions" :workflow-input]
                           {:input "Ship it" :original {:ticket 123}})
                 (assoc-in [:workflows :runs "run-statechart-session-contributions" :current-step-id] "review")
                 (assoc-in [:workflows :runs "run-statechart-session-contributions" :step-runs "plan" :accepted-result]
                           {:outcome :ok
                            :outputs {:final-llm-reply "plan text"
                                      :text "plan text"}})
                 (assoc-in [:workflows :runs "run-statechart-session-contributions" :step-runs "plan" :attempts]
                           [{:attempt-id "a1" :status :succeeded :execution-session-id session-id}]))))
    (let [wf-ctx (runtime/create-workflow-context ctx session-id "run-statechart-session-contributions")]
      (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (swap! created* conj {:workflow-step-id (:workflow-step-id opts)
                                            :preloaded-messages (:preloaded-messages opts)})
                      (let [sid (str (:workflow-step-id opts) "-child")]
                        {:attempt {:attempt-id (:attempt-id opts)
                                   :status :pending
                                   :execution-session-id sid}
                         :execution-session {:session-id sid}}))
                    psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx sid prompt]
                      (swap! prompts* conj {:session-id sid :prompt prompt})
                      {:execution-result/assistant-message
                       {:role "assistant"
                        :content [{:type :text :text (if (= sid "plan-child")
                                                       "plan text"
                                                       "review output")}]
                        :stop-reason :stop}})]
        (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
      (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-statechart-session-contributions")
            review-created (last @created*)
            review-prompts (filterv #(= "review-child" (:session-id %)) @prompts*)]
        (is (= {:workflow-step-id "review"
                :preloaded-messages [{:role "user" :content "{:ticket 123}"}
                                     {:role "user" :content "plan text"}]}
               review-created))
        (is (= [{:session-id "review-child"
                 :prompt "Review plan text"}]
               review-prompts))
        (is (= "review output"
               (get-in run [:step-runs "review" :accepted-result :outputs :final-llm-reply])))))))

(deftest judged-review-records-routing-result-test
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx judged-definition "run-3")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-3")]
    (with-stubbed-runtime {:assistant-text "review-output"
                           :judge-result {:judge-session-id "judge-r"
                                          :judge-output "APPROVED"
                                          :judge-event "APPROVED"
                                          :routing-result {:action :complete}}}
      #(runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-3")
          review-attempt (get-in run [:step-runs "review" :attempts 0])]
      (is (= :completed (:status run)))
      (is (= "APPROVED" (:judge-output review-attempt)))
      (is (= "APPROVED" (:judge-event review-attempt)))
      (is (= "judge-r" (:judge-session-id review-attempt))))))

(deftest actor-success-does-not-retain-execution-result-in-pending-state-test
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx linear-definition "run-no-pending-execution-result")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-no-pending-execution-result")
        pending-snapshots* (atom [])]
    (add-watch (:working-memory* wf-ctx) ::capture-pending-actor-result
               (fn [_ _ _ new-state]
                 (when-let [pending (:pending-actor-result new-state)]
                   (swap! pending-snapshots* conj pending))))
    (try
      (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [sid (str (:workflow-step-id opts) "-child")]
                        {:attempt {:attempt-id (:attempt-id opts)
                                   :status :pending
                                   :execution-session-id sid}
                         :execution-session {:session-id sid}}))
                    psi.workflow-runtime.turn-execution-contract/execute-actor-turn!
                    (fn [_ctx _sid _prompt]
                      {:status :ok
                       :session-id "actor-session"
                       :turn-outcome :turn.outcome/stop
                       :assistant-message {:role "assistant"
                                           :content [{:type :text :text "actor output"}]}
                       :assistant-text "actor output"
                       :execution-result {:execution-result/turn-id "turn-1"}})]
        (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
      (finally
        (remove-watch (:working-memory* wf-ctx) ::capture-pending-actor-result)))
    (is (seq @pending-snapshots*))
    (is (every? #(not (contains? % :execution-result)) @pending-snapshots*))))

(deftest child-session-creation-failure-records-execution-failure-test
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx linear-definition "run-child-session-failure")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-child-session-failure")]
    (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                  (fn [& _]
                    (throw (ex-info "Invalid initial agent state"
                                    {:errors {:model "bad-model"}})))]
      (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-child-session-failure")
          attempt (get-in run [:step-runs "plan" :attempts 0])]
      (is (= :failed (:status run)))
      (is (= :execution-failed (:status attempt)))
      (is (= "Invalid initial agent state"
             (get-in attempt [:execution-error :message]))))))

(deftest cancel-from-blocked-state-test
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx linear-definition "run-4")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-4")]
    (swap! (:state* ctx)
           assoc-in [:workflows :runs "run-4" :status] :blocked)
    (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/cancel nil)
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-4")]
      (is (= :cancelled (:status run))))))
