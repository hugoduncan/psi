(ns psi.agent-session.workflow-statechart-runtime-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations.canonical-workflows :as cwf-mutations]
   [psi.agent-session.turn]
   [psi.workflow-runtime.turn-execution-contract]
   [psi.agent-session.test-support :as test-support]
   [psi.workflow-runtime.attempts]
   [psi.workflow-runtime.execution-adapter]
   [psi.agent-session.workflow-judge]
   [psi.session-state.state :as ss]
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
  ([ctx definition run-id]
   (install-run! ctx definition run-id {}))
  ([ctx definition run-id run-opts]
   (swap! (:state* ctx)
          (fn [state]
            (let [[s _ _] (workflow-registry/register-definition state definition)
                  [s _ _] (workflow-runtime/create-run s (merge {:definition-id (:definition-id definition)
                                                                 :run-id run-id
                                                                 :workflow-input {:input "ship it"
                                                                                  :original {:ticket 123}}}
                                                                run-opts))]
              s)))))

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

(deftest judged-review-failing-judge-result-fails-run-instead-of-stranding-it-test
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx judged-definition "run-judge-fail")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-judge-fail")]
    (with-stubbed-runtime {:assistant-text "review-output"
                           :judge-result {:judge-session-id "judge-fail"
                                          :judge-output {:routing-result {:structured-output {:status :invalid
                                                                                              :errors [{:type :invalid-structured-output
                                                                                                        :message "bad judge output"}]}}}
                                          :judge-event nil
                                          :routing-result {:action :fail
                                                           :reason :invalid-structured-output
                                                           :output-key :routing-result}}}
      #(runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-judge-fail")
          review-attempt (get-in run [:step-runs "review" :attempts 0])]
      (is (= :failed (:status run)))
      (is (= :invalid-structured-output (get-in run [:terminal-outcome :reason])))
      (is (= "judge-fail" (:judge-session-id review-attempt)))
      (is (nil? (:judge-event review-attempt)))
      (is (= {:routing-result {:structured-output {:status :invalid
                                                   :errors [{:type :invalid-structured-output
                                                             :message "bad judge output"}]}}}
             (:judge-output review-attempt))))))

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

(deftest running-top-level-workflow-uses-original-session-profile-snapshot-test
  ;; Tests a still-running top-level multi-step workflow resolves a later
  ;; :session-profile step from the run's original snapshot after mutable config
  ;; changes, not from the edited project config.
  (let [[ctx session-id] (create-session-context)
        cwd (ss/session-worktree-path-in ctx session-id)
        profile-file (java.io.File. cwd ".psi/project.edn")
        definition {:definition-id "profile-mid-run"
                    :name "profile-mid-run"
                    :steps [{:name "first"
                             :type :session
                             :contributions [{:type :template
                                              :text "First {{input}}"
                                              :vars {"input" {:from :workflow-input :path [:input]}}}]}
                            {:name "later"
                             :type :session
                             :session-profile :coding
                             :contributions [{:type :template
                                              :text "Later {{first}}"
                                              :vars {"first" {:from {:step "first" :yield :text}}}}]}]}
        child-requests* (atom [])]
    (.mkdirs (.getParentFile profile-file))
    (spit profile-file
          (pr-str {:agent-session
                   {:session-profiles
                    {:coding {:model-provider "openai"
                              :model-id "gpt-5.5"
                              :thinking-level :low
                              :speed-mode :fast
                              :effort-override :xhigh}}}}))
    (cwf-mutations/register-workflow-definition
     {} {:psi/agent-session-ctx ctx :definition definition})
    (cwf-mutations/create-workflow-run
     {} {:psi/agent-session-ctx ctx
         :session-id session-id
         :definition-id "profile-mid-run"
         :workflow-input {:input "ship"}
         :run-id "run-profile-mid-run"})
    (let [wf-ctx (runtime/create-workflow-context ctx session-id "run-profile-mid-run")]
      (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [child-session-id (str (:workflow-step-id opts) "-child")]
                        (swap! child-requests* conj
                               (select-keys opts [:workflow-step-id
                                                  :model
                                                  :thinking-level
                                                  :speed-mode
                                                  :effort-override]))
                        {:attempt {:attempt-id (:attempt-id opts)
                                   :status :pending
                                   :execution-session-id child-session-id}
                         :execution-session {:session-id child-session-id}}))
                    psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx sid _prompt]
                      (when (= "first-child" sid)
                        (spit profile-file
                              (pr-str {:agent-session
                                       {:session-profiles
                                        {:coding {:model-provider "anthropic"
                                                  :model-id "claude-opus-4-8"
                                                  :thinking-level :high
                                                  :speed-mode :normal
                                                  :effort-override :low}}}})))
                      {:execution-result/assistant-message
                       {:role "assistant"
                        :content [{:type :text
                                   :text (if (= "first-child" sid)
                                           "first output"
                                           "later output")}]
                        :stop-reason :stop}})]
        (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil)))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-profile-mid-run")
          later-request (some #(when (= "later" (:workflow-step-id %)) %)
                              @child-requests*)]
      (is (= :completed (:status run)))
      (is (= ["first" "later"] (mapv :workflow-step-id @child-requests*)))
      (is (= {:provider "openai" :id "gpt-5.5"}
             (select-keys (:model later-request) [:provider :id]))
          "later step uses the model captured in the original run snapshot")
      (is (= :low (:thinking-level later-request))
          "later step uses original snapshot thinking, not edited config")
      (is (= :fast (:speed-mode later-request))
          "later step uses original snapshot speed, not edited config")
      (is (= :xhigh (:effort-override later-request))
          "later step uses original snapshot effort, not edited config"))))

(deftest invalid-session-profile-fails-before-child-session-creation-test
  ;; Tests statechart/runtime failure for unavailable workflow profiles before
  ;; any workflow-owned child execution session is created.
  (doseq [{:keys [label profile-name snapshot expected-reason]}
          [{:label "unknown profile"
            :profile-name :missing
            :snapshot {:profiles {:coding {:name :coding
                                           :status :valid
                                           :valid? true
                                           :settings {:speed-mode :fast}
                                           :readable-settings ["speed fast"]
                                           :diagnostics []}}
                       :valid-profile-names [:coding]
                       :invalid-profile-names []}
            :expected-reason :unknown-session-profile}
           {:label "invalid profile"
            :profile-name :empty
            :snapshot {:profiles {:empty {:name :empty
                                          :status :invalid
                                          :valid? false
                                          :settings {}
                                          :readable-settings []
                                          :diagnostics [{:field :settings
                                                         :reason :no-concrete-settings
                                                         :message "profile has no supported concrete settings"}]}}
                       :valid-profile-names []
                       :invalid-profile-names [:empty]}
            :expected-reason :invalid-session-profile}]]
    (testing label
      (let [[ctx session-id] (create-session-context)
            run-id (str "run-" label "-profile-runtime")
            definition {:definition-id run-id
                        :steps [{:name "plan"
                                 :type :session
                                 :session-profile profile-name
                                 :contributions [{:type :template
                                                  :text "Plan {{input}}"
                                                  :vars {"input" {:from :workflow-input :path [:input]}}}]}]}
            _ (install-run! ctx definition run-id
                            {:session-profile-snapshot snapshot})
            wf-ctx (runtime/create-workflow-context ctx session-id run-id)]
        (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil)
        (let [run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
              attempt (get-in run [:step-runs "plan" :attempts 0])]
          (is (= :failed (:status run)))
          (is (= :execution-failed (:status attempt)))
          (is (nil? (:execution-session-id attempt)))
          (is (= expected-reason
                 (get-in attempt [:execution-error :reason])))
          (is (= profile-name
                 (get-in attempt [:execution-error :profile-name])))
          (is (contains? (:execution-error attempt) :available-profile-names)
              "statechart preserves actionable profile failure data")
          (is (empty? (filter (fn [[_ {:keys [data]}]]
                                (:workflow-owned? data))
                              (get-in @(:state* ctx) [:agent-session :sessions])))
              "no workflow-owned child execution session is present in canonical state"))))))

(deftest workflow-model-query-ranked-fallback-success-test
  (testing "first-ranked connection-refused failure falls back to the next ranked candidate and completes the step"
    (let [[ctx session-id] (create-session-context)
          definition {:definition-id "model-query-fallback"
                      :steps [{:name "plan"
                               :type :session
                               :model {:type :model-query
                                       :require [{:criterion :supports-text :match :true}]}
                               :contributions [{:type :template
                                                :text "Plan {{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}]}
          _ (install-run! ctx definition "run-model-fallback-success")
          wf-ctx (runtime/create-workflow-context ctx session-id "run-model-fallback-success")
          model-calls* (atom [])
          current-model* (atom nil)
          turn-count* (atom 0)]
      (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      {:attempt {:attempt-id (:attempt-id opts)
                                 :status :pending
                                 :execution-session-id "plan-child"}
                       :execution-session {:session-id "plan-child"
                                           :model (:model opts)
                                           :model-fallback {:type :ranked-model-candidates
                                                            :candidates [{:provider "local" :id "first"}
                                                                         {:provider "openai" :id "second"}]}}})
                    psi.workflow-runtime.execution-adapter/set-session-model!
                    (fn [_ctx _sid model scope]
                      (swap! model-calls* conj {:model model :scope scope})
                      (reset! current-model* model)
                      {:ok true})
                    psi.workflow-runtime.turn-execution-contract/execute-actor-turn!
                    (fn [_ctx _sid _prompt]
                      (swap! turn-count* inc)
                      (if (= {:provider "local" :id "first"}
                             (or @current-model* {:provider "local" :id "first"}))
                        {:status :error
                         :session-id "plan-child"
                         :assistant-message {:role "assistant"
                                             :error-message "Connection refused"
                                             :content [{:type :error :text "Connection refused"}]}
                         :assistant-text ""
                         :execution-result {}
                         :failure {:reason :provider-unavailable
                                   :message "Connection refused"
                                   :fallback-worthy? true}}
                        {:status :ok
                         :session-id "plan-child"
                         :assistant-message {:role "assistant"
                                             :content [{:type :text :text "fallback success"}]}
                         :assistant-text "fallback success"
                         :execution-result {}}))]
        (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
      (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-model-fallback-success")
            attempt (get-in run [:step-runs "plan" :attempts 0])]
        (is (= :completed (:status run)))
        (is (= "fallback success"
               (get-in run [:step-runs "plan" :accepted-result :outputs :final-llm-reply])))
        (is (= [{:model {:provider "openai" :id "second"}
                 :scope :session}]
               @model-calls*))
        (is (= 2 @turn-count*))
        (is (= :succeeded (:status attempt)))))))

(deftest workflow-concrete-model-does-not-fallback-test
  (testing "explicit concrete workflow models remain single-shot without ranked fallback"
    (let [[ctx session-id] (create-session-context)
          definition {:definition-id "concrete-model-no-fallback"
                      :steps [{:name "plan"
                               :type :session
                               :model {:provider "openai" :id "gpt-5"}
                               :contributions [{:type :template
                                                :text "Plan {{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}]}
          _ (install-run! ctx definition "run-concrete-model-no-fallback")
          wf-ctx (runtime/create-workflow-context ctx session-id "run-concrete-model-no-fallback")
          model-calls* (atom [])
          turn-count* (atom 0)]
      (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      {:attempt {:attempt-id (:attempt-id opts)
                                 :status :pending
                                 :execution-session-id "plan-child"}
                       :execution-session {:session-id "plan-child"
                                           :model (:model opts)}})
                    psi.workflow-runtime.execution-adapter/set-session-model!
                    (fn [_ctx _sid model scope]
                      (swap! model-calls* conj {:model model :scope scope})
                      {:ok true})
                    psi.workflow-runtime.turn-execution-contract/execute-actor-turn!
                    (fn [_ctx _sid _prompt]
                      (swap! turn-count* inc)
                      {:status :error
                       :session-id "plan-child"
                       :assistant-message {:role "assistant"
                                           :error-message "Connection refused"
                                           :content [{:type :error :text "Connection refused"}]}
                       :assistant-text ""
                       :execution-result {}
                       :failure {:reason :provider-unavailable
                                 :message "Connection refused"
                                 :fallback-worthy? true}})]
        (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
      (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-concrete-model-no-fallback")
            attempt (get-in run [:step-runs "plan" :attempts 0])]
        (is (= :failed (:status run)))
        (is (= [] @model-calls*))
        (is (= 1 @turn-count*))
        (is (= "Connection refused"
               (get-in attempt [:execution-error :message])))))))

(deftest workflow-model-query-non-fallback-failure-is-terminal-test
  (testing "non-fallback-worthy failures remain terminal for the ranked candidate sequence"
    (let [[ctx session-id] (create-session-context)
          definition {:definition-id "model-query-terminal-failure"
                      :steps [{:name "plan"
                               :type :session
                               :model {:type :model-query
                                       :require [{:criterion :supports-text :match :true}]}
                               :contributions [{:type :template
                                                :text "Plan {{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}]}
          _ (install-run! ctx definition "run-model-fallback-terminal")
          wf-ctx (runtime/create-workflow-context ctx session-id "run-model-fallback-terminal")
          model-calls* (atom [])
          current-model* (atom nil)
          turn-count* (atom 0)]
      (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      {:attempt {:attempt-id (:attempt-id opts)
                                 :status :pending
                                 :execution-session-id "plan-child"}
                       :execution-session {:session-id "plan-child"
                                           :model (:model opts)
                                           :model-fallback {:type :ranked-model-candidates
                                                            :candidates [{:provider "local" :id "first"}
                                                                         {:provider "openai" :id "second"}]}}})
                    psi.workflow-runtime.execution-adapter/set-session-model!
                    (fn [_ctx _sid model scope]
                      (swap! model-calls* conj {:model model :scope scope})
                      (reset! current-model* model)
                      {:ok true})
                    psi.workflow-runtime.turn-execution-contract/execute-actor-turn!
                    (fn [_ctx _sid _prompt]
                      (swap! turn-count* inc)
                      {:status :error
                       :session-id "plan-child"
                       :assistant-message {:role "assistant"
                                           :error-message "Validation failed"
                                           :content [{:type :error :text "Validation failed"}]}
                       :assistant-text ""
                       :execution-result {}
                       :failure {:reason :invalid-request
                                 :message "Validation failed"}})]
        (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
      (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-model-fallback-terminal")
            attempt (get-in run [:step-runs "plan" :attempts 0])]
        (is (= :failed (:status run)))
        (is (= [] @model-calls*))
        (is (= 1 @turn-count*))
        (is (= :invalid-request
               (get-in attempt [:execution-error :reason])))))))

(deftest workflow-model-query-ranked-candidates-exhausted-test
  (testing "exhausting ranked model-query candidates records one canonical attempt with aggregate candidate failures"
    (let [[ctx session-id] (create-session-context)
          definition {:definition-id "model-query-exhausted"
                      :steps [{:name "plan"
                               :type :session
                               :model {:type :model-query
                                       :require [{:criterion :supports-text :match :true}]}
                               :contributions [{:type :template
                                                :text "Plan {{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}]}
          _ (install-run! ctx definition "run-model-fallback-exhausted")
          wf-ctx (runtime/create-workflow-context ctx session-id "run-model-fallback-exhausted")
          model-calls* (atom [])
          current-model* (atom nil)]
      (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      {:attempt {:attempt-id (:attempt-id opts)
                                 :status :pending
                                 :execution-session-id "plan-child"}
                       :execution-session {:session-id "plan-child"
                                           :model (:model opts)
                                           :model-fallback {:type :ranked-model-candidates
                                                            :candidates [{:provider "local" :id "first"}
                                                                         {:provider "openai" :id "second"}]}}})
                    psi.workflow-runtime.execution-adapter/set-session-model!
                    (fn [_ctx _sid model scope]
                      (swap! model-calls* conj {:model model :scope scope})
                      (reset! current-model* model)
                      {:ok true})
                    psi.workflow-runtime.turn-execution-contract/execute-actor-turn!
                    (fn [_ctx _sid _prompt]
                      {:status :error
                       :session-id "plan-child"
                       :assistant-message {:role "assistant"
                                           :error-message (str "Connection refused for " (:id (or @current-model* {:provider "local" :id "first"})))
                                           :content [{:type :error :text (str "Connection refused for " (:id (or @current-model* {:provider "local" :id "first"})))}]}
                       :assistant-text ""
                       :execution-result {}
                       :failure {:reason :provider-unavailable
                                 :message (str "Connection refused for " (:id (or @current-model* {:provider "local" :id "first"})))
                                 :fallback-worthy? true}})]
        (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
      (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-model-fallback-exhausted")
            attempt (get-in run [:step-runs "plan" :attempts 0])]
        (is (= :failed (:status run)))
        (is (= [{:model {:provider "openai" :id "second"}
                 :scope :session}]
               @model-calls*))
        (is (= :ranked-candidate-exhausted
               (get-in attempt [:execution-error :reason])))
        (is (= 2 (count (get-in attempt [:execution-error :candidate-failures]))))
        (is (= [{:model {:provider "local" :id "first"}
                 :failure {:reason :provider-unavailable
                           :message "Connection refused for first"
                           :fallback-worthy? true}}
                {:model {:provider "openai" :id "second"}
                 :failure {:reason :provider-unavailable
                           :message "Connection refused for second"
                           :fallback-worthy? true}}]
               (get-in attempt [:execution-error :candidate-failures])))))))

(deftest workflow-model-query-empty-ranked-candidates-fails-coherently-test
  (testing "empty ranked-candidate metadata yields one coherent terminal workflow failure"
    (let [[ctx session-id] (create-session-context)
          definition {:definition-id "model-query-empty-ranked"
                      :steps [{:name "plan"
                               :type :session
                               :model {:type :model-query
                                       :require [{:criterion :supports-text :match :true}]}
                               :contributions [{:type :template
                                                :text "Plan {{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}]}
          _ (install-run! ctx definition "run-model-fallback-empty")
          wf-ctx (runtime/create-workflow-context ctx session-id "run-model-fallback-empty")]
      (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      {:attempt {:attempt-id (:attempt-id opts)
                                 :status :pending
                                 :execution-session-id "plan-child"}
                       :execution-session {:session-id "plan-child"
                                           :model (:model opts)
                                           :model-fallback {:type :ranked-model-candidates
                                                            :candidates []}}})
                    psi.workflow-runtime.turn-execution-contract/execute-actor-turn!
                    (fn [& _]
                      (throw (ex-info "should not execute turn when no candidates exist" {})))]
        (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
      (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-model-fallback-empty")
            attempt (get-in run [:step-runs "plan" :attempts 0])]
        (is (= :failed (:status run)))
        (is (= :execution-failed (:status attempt)))))))

(deftest step-entry-attempt-start-write-is-cancellation-safe-test
  ;; Regression for task 225 implementation review: cancellation racing after the
  ;; pre-check but before the attempt-start swap! must not let :step/enter
  ;; resurrect the run to :running or record a post-cancel attempt.
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx linear-definition "run-step-entry-cancel-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-step-entry-cancel-race")
        created* (atom [])]
    (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                  (fn [_ctx _parent-session-id opts]
                    (swap! created* conj opts)
                    (swap! (:state* ctx)
                           (fn [state]
                             (-> state
                                 (assoc-in [:workflows :runs "run-step-entry-cancel-race" :status] :cancelled)
                                 (assoc-in [:workflows :runs "run-step-entry-cancel-race" :finished-at] (java.time.Instant/now))
                                 (assoc-in [:workflows :runs "run-step-entry-cancel-race" :terminal-outcome]
                                           {:outcome :cancelled
                                            :reason "race"
                                            :step-id "plan"}))))
                    {:attempt {:attempt-id (:attempt-id opts)
                               :status :pending
                               :execution-session-id "plan-child"}
                     :execution-session {:session-id "plan-child"}})
                  psi.workflow-runtime.turn-execution-contract/execute-actor-turn!
                  (fn [& _]
                    (throw (ex-info "turn must not start after cancellation wins attempt-start swap" {})))]
      (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-step-entry-cancel-race")]
      (is (= 1 (count @created*))
          "the race is after child-session creation/pre-check and before attempt-start write")
      (is (= :cancelled (:status run)))
      (is (empty? (get-in run [:step-runs "plan" :attempts]))
          "the guarded attempt-start write must not append an attempt after cancellation")
      (is (not (contains? #{"build" nil} (:current-step-id run)))
          "ordinary advancement must not proceed after the cancel checkpoint"))))

(deftest delegate-sub-run-creation-is-cancellation-safe-test
  ;; Regression for task 225 implementation review: a parent cancel racing after
  ;; delegate-step pre-check but before create-run must preserve the cancelled
  ;; parent and create no child run.
  (let [[ctx session-id] (create-session-context)
        child-definition {:definition-id "delegate-child-race"
                          :steps [{:name "only"
                                   :type :session
                                   :contributions [{:type :template
                                                    :text "Child"
                                                    :vars {}}]}]}
        parent-definition {:definition-id "delegate-parent-race"
                           :steps [{:name "delegate"
                                    :type :delegate
                                    :target "delegate-child-race"
                                    :prompt-string "Go"}]}
        _ (install-run! ctx child-definition "definition-seed")
        _ (swap! (:state* ctx) update-in [:workflows :runs] dissoc "definition-seed")
        _ (swap! (:state* ctx) update-in [:workflows :run-order]
                 (fn [order]
                   (vec (remove #(= "definition-seed" %) order))))
        _ (install-run! ctx parent-definition "run-delegate-parent-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-delegate-parent-race")]
    (with-redefs [psi.workflow-runtime.core/create-run
                  (let [real-create-run psi.workflow-runtime.core/create-run]
                    (fn [state opts]
                      (when (= "delegate-child-race" (:definition-id opts))
                        (swap! (:state* ctx)
                               (fn [current-state]
                                 (-> current-state
                                     (assoc-in [:workflows :runs "run-delegate-parent-race" :status] :cancelled)
                                     (assoc-in [:workflows :runs "run-delegate-parent-race" :finished-at] (java.time.Instant/now))
                                     (assoc-in [:workflows :runs "run-delegate-parent-race" :terminal-outcome]
                                               {:outcome :cancelled
                                                :reason "delegate race"
                                                :step-id "delegate"})))))
                      (real-create-run state opts)))]
      (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
    (let [runs (get-in @(:state* ctx) [:workflows :runs])
          parent (get runs "run-delegate-parent-race")
          delegated-runs (filterv #(= "run-delegate-parent-race" (:delegating-run-id %))
                                  (vals runs))]
      (is (= :cancelled (:status parent)))
      (is (empty? delegated-runs)
          "guarded delegate creation must not add a child run after parent cancellation")
      (is (= :cancelled (:status parent))
          "delegate creation must not resurrect the cancelled parent"))))

(deftest cancel-from-blocked-state-test
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx linear-definition "run-4")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-4")]
    (swap! (:state* ctx)
           assoc-in [:workflows :runs "run-4" :status] :blocked)
    (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/cancel nil)
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-4")]
      (is (= :cancelled (:status run))))))
