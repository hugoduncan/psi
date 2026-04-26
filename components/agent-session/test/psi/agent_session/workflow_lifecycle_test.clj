(ns psi.agent-session.workflow-lifecycle-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.prompt-control]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-attempts :as workflow-attempts]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-progression-recording :as workflow-recording]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(def plan-build-review-definition
  {:definition-id "plan-build-review"
   :name "Plan Build Review"
   :summary "Representative chain-like workflow proof"
   :step-order ["plan" "build" "review"]
   :steps {"plan" {:label "Plan"
                   :executor {:type :agent :profile "planner" :mode :sync}
                   :input-bindings {:task {:source :workflow-input :path [:task]}}
                   :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                   :retry-policy {:max-attempts 2 :retry-on #{:execution-failed :validation-failed}}
                   :capability-policy {:tools #{"read" "bash"}}}
           "build" {:label "Build"
                    :executor {:type :agent :profile "builder" :mode :async}
                    :input-bindings {:plan {:source :step-output :path ["plan" :outputs :plan]}}
                    :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                    :retry-policy {:max-attempts 2 :retry-on #{:execution-failed :validation-failed}}
                    :capability-policy {:tools #{"read" "edit" "write"}}}
           "review" {:label "Review"
                     :executor {:type :agent :profile "reviewer" :mode :sync}
                     :input-bindings {:build {:source :step-output :path ["build" :outputs :build]}}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                     :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}
                     :capability-policy {:tools #{"read"}}}}})

(defn- install-definition-and-run!
  [ctx]
  (let [[state1 definition-id _]
        (workflow-runtime/register-definition @(:state* ctx) plan-build-review-definition)
        [state2 run-id _]
        (workflow-runtime/create-run state1 {:definition-id definition-id
                                             :run-id "run-1"
                                             :workflow-input {:task "ship it"}})]
    (reset! (:state* ctx) state2)
    run-id))

(defn- create-and-start-attempt!
  [ctx parent-session-id run-id step-id attempt-id]
  (let [{:keys [attempt execution-session]}
        (workflow-attempts/create-step-attempt-session!
         ctx
         parent-session-id
         {:workflow-run-id run-id
          :workflow-step-id step-id
          :attempt-id attempt-id
          :session-name (str step-id " attempt " attempt-id)
          :tool-defs []
          :thinking-level :off})]
    (swap! (:state* ctx)
           (fn [state]
             (-> state
                 (update-in [:workflows :runs run-id]
                            #(workflow-attempts/append-attempt-to-run % step-id attempt))
                 (workflow-recording/start-latest-attempt run-id step-id))))
    {:attempt attempt
     :execution-session execution-session}))

(deftest representative-sequential-workflow-lifecycle-test
  (testing "plan -> build -> review executes deterministically with introspectable session linkage"
    (let [[ctx session-id] (create-session-context {:persist? false})
          run-id          (install-definition-and-run! ctx)
          child-sessions* (atom [])]
      (with-redefs [psi.agent-session.workflow-attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [sid (str (:workflow-step-id opts) "-" (:attempt-id opts) "-session")
                            attempt {:attempt-id (:attempt-id opts)
                                     :status :pending
                                     :execution-session-id sid}
                            execution-session {:session-id sid
                                               :name sid
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
                                               :statechart {:phase :idle}}]
                        (swap! child-sessions* conj sid)
                        {:attempt attempt
                         :execution-session execution-session}))
                    psi.agent-session.prompt-control/prompt-in!
                    (fn [_ctx _sid _prompt] nil)
                    psi.agent-session.prompt-control/last-assistant-message-in
                    (let [responses* (atom ["plan-output" "build-output" "review-output"])]
                      (fn [_ctx _sid]
                        {:role "assistant"
                         :content [(let [resp (first @responses*)]
                                     (swap! responses* subvec 1)
                                     {:type :text :text resp})]
                         :stop-reason :stop}))]
        (let [result             (workflow-execution/execute-run! ctx session-id run-id)
              run                (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
              detail-result      (session/query-in ctx
                                                   [:psi.workflow.run/detail]
                                                   {:psi.workflow.run/id run-id})
              run-detail         (:psi.workflow.run/detail detail-result)
              actual-session-ids (mapv #(get-in % [:psi.workflow.attempt/execution-session-id])
                                       (mapcat :psi.workflow.step-run/attempts
                                               (:psi.workflow.run/step-runs run-detail)))]
          (is (= :completed (:status result)))
          (is (true? (:terminal? result)))
          (is (= :completed (:status run)))
          (is (nil? (:current-step-id run)))
          (is (= {:outcome :ok :outputs {:text "plan-output"}}
                 (get-in run [:step-runs "plan" :accepted-result])))
          (is (= {:outcome :ok :outputs {:text "build-output"}}
                 (get-in run [:step-runs "build" :accepted-result])))
          (is (= {:outcome :ok :outputs {:text "review-output"}}
                 (get-in run [:step-runs "review" :accepted-result])))
          (is (= 3 (count actual-session-ids)))
          (is (= (set @child-sessions*)
                 (set actual-session-ids)))
          (is (= :completed (:psi.workflow.run/status run-detail)))
          (is (= ["plan" "build" "review"]
                 (mapv :psi.workflow.step-run/id
                       (:psi.workflow.run/step-runs run-detail))))
          (is (= [:succeeded :succeeded :succeeded]
                 (mapv :psi.workflow.step-run/status
                       (:psi.workflow.run/step-runs run-detail))))
          (is (= [:workflow/run-created
                  :workflow/attempt-started
                  :workflow/result-received
                  :workflow/attempt-started
                  :workflow/result-received
                  :workflow/attempt-started
                  :workflow/result-received]
                 (take 7 (mapv :psi.workflow.history/event
                               (:psi.workflow.run/history run-detail)))))))
      (session/shutdown-context! ctx))))

(deftest blocked-resume-creates-new-attempt-proof-test
  (testing "blocked workflows resume by creating a new attempt while preserving prior blocked attempt history"
    (let [[ctx session-id] (create-session-context {:persist? false})
          run-id            (install-definition-and-run! ctx)
          created-sessions* (atom [])]
      (with-redefs [psi.agent-session.workflow-attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [sid (str (:workflow-step-id opts) "-" (:attempt-id opts) "-session")
                            attempt {:attempt-id (:attempt-id opts)
                                     :status :pending
                                     :execution-session-id sid}
                            execution-session {:session-id sid
                                               :name sid
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
                                               :statechart {:phase :idle}}]
                        (swap! created-sessions* conj sid)
                        {:attempt attempt
                         :execution-session execution-session}))]
        (let [first-run (create-and-start-attempt! ctx session-id run-id "plan" "plan-1")
              _ (swap! (:state* ctx)
                       (fn [state]
                         (-> state
                             (assoc-in [:workflows :runs run-id :step-runs "plan" :accepted-result]
                                       {:outcome :blocked
                                        :blocked {:question "ship it?" :choices [:yes :no]}})
                             (assoc-in [:workflows :runs run-id :step-runs "plan" :attempts 0 :status] :blocked)
                             (assoc-in [:workflows :runs run-id :step-runs "plan" :attempts 0 :blocked]
                                       {:question "ship it?" :choices [:yes :no]})
                             (assoc-in [:workflows :runs run-id :status] :blocked)
                             (assoc-in [:workflows :runs run-id :blocked]
                                       {:question "ship it?" :choices [:yes :no]})
                             (update-in [:workflows :runs run-id :history] conj
                                        {:event :workflow/result-received}
                                        {:event :workflow/block}))))
              _ (swap! (:state* ctx)
                       (fn [state]
                         (first (workflow-runtime/resume-run state run-id))))
              second-run (create-and-start-attempt! ctx session-id run-id "plan" "plan-2")
              _ (swap! (:state* ctx)
                       (fn [state]
                         (-> state
                             (assoc-in [:workflows :runs run-id :step-runs "plan" :accepted-result]
                                       {:outcome :ok :outputs {:text "approved plan"}})
                             (assoc-in [:workflows :runs run-id :step-runs "plan" :attempts 1 :status] :succeeded)
                             (assoc-in [:workflows :runs run-id :step-runs "plan" :attempts 1 :result-envelope]
                                       {:outcome :ok :outputs {:text "approved plan"}})
                             (assoc-in [:workflows :runs run-id :current-step-id] "build")
                             (assoc-in [:workflows :runs run-id :status] :running)
                             (assoc-in [:workflows :runs run-id :blocked] nil)
                             (update-in [:workflows :runs run-id :history] conj
                                        {:event :workflow/result-received}
                                        {:event :workflow/step-succeeded}))))
              run           (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
              plan-attempts (get-in run [:step-runs "plan" :attempts])
              detail-result (session/query-in ctx
                                              [:psi.workflow.run/detail]
                                              {:psi.workflow.run/id run-id})
              run-detail    (:psi.workflow.run/detail detail-result)
              eql-attempts  (get-in run-detail [:psi.workflow.run/step-runs 0 :psi.workflow.step-run/attempts])
              events        (mapv :psi.workflow.history/event
                                  (:psi.workflow.run/history run-detail))]
          (is (= :running (:status run)))
          (is (= "build" (:current-step-id run)))
          (is (= 2 (count plan-attempts)))
          (is (= [:blocked :succeeded]
                 (mapv :status plan-attempts)))
          (is (= [(get-in first-run [:execution-session :session-id])
                  (get-in second-run [:execution-session :session-id])]
                 (mapv :execution-session-id plan-attempts)))
          (is (= (set @created-sessions*)
                 (set (mapv :execution-session-id plan-attempts))))
          (is (= [:blocked :succeeded]
                 (mapv :psi.workflow.attempt/status eql-attempts)))
          (is (= [:workflow/run-created
                  :workflow/attempt-started
                  :workflow/result-received
                  :workflow/block
                  :workflow/resume
                  :workflow/attempt-started
                  :workflow/result-received
                  :workflow/step-succeeded]
                 events)))
        (session/shutdown-context! ctx)))))