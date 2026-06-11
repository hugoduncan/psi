(ns psi.agent-session.workflow-statechart-runtime-cancellation-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.turn]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-judge]
   [psi.deterministic-operation-registry.registry]
   [psi.workflow-registry.registry :as workflow-registry]
   [psi.workflow-runtime.attempts]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.execution-adapter]
   [psi.workflow-runtime.progression-recording]
   [psi.workflow-runtime.statechart-runtime :as runtime]
   [psi.workflow-runtime.turn-execution-contract]))

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

(deftest step-entry-attempt-start-write-is-cancellation-safe-test
  ;; Regression for task 225 implementation review: cancellation racing after the
  ;; pre-check but before the attempt-start swap! must not let :step/enter
  ;; resurrect the run to :running or record a post-cancel attempt.
  (let [[ctx0 session-id] (create-session-context)
        aborted* (atom [])
        ctx (test-support/with-workflow-execution-adapter-overrides
              ctx0
              {:abort-session! (fn [_ctx session-id]
                                 (swap! aborted* conj session-id))})
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
      (is (= ["plan-child"] @aborted*)
          "the child session created before failed live-run attachment must be aborted")
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

(deftest post-entry-ordinary-result-write-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 2: cancellation racing
  ;; after actor result admission but before :step/record-result commits must
  ;; not record ordinary success or advance/complete a cancelled run.
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx linear-definition "run-record-result-cancel-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-record-result-cancel-race")]
    (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                  (fn [_ctx _parent-session-id opts]
                    {:attempt {:attempt-id (:attempt-id opts)
                               :status :pending
                               :execution-session-id "plan-child"}
                     :execution-session {:session-id "plan-child"}})
                  psi.workflow-runtime.turn-execution-contract/execute-actor-turn!
                  (fn [& _]
                    {:status :ok
                     :session-id "plan-child"
                     :assistant-message {:role "assistant"
                                         :content [{:type :text :text "late success"}]}
                     :assistant-text "late success"
                     :execution-result {}})
                  psi.workflow-runtime.progression-recording/record-step-result
                  (let [real-record psi.workflow-runtime.progression-recording/record-step-result]
                    (fn [state run-id step-id payload]
                      (swap! (:state* ctx)
                             (fn [current-state]
                               (-> current-state
                                   (assoc-in [:workflows :runs run-id :status] :cancelled)
                                   (assoc-in [:workflows :runs run-id :finished-at] (java.time.Instant/now))
                                   (assoc-in [:workflows :runs run-id :terminal-outcome]
                                             {:outcome :cancelled
                                              :reason "result race"
                                              :step-id step-id}))))
                      (real-record state run-id step-id payload)))]
      (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-record-result-cancel-race")]
      (is (= :cancelled (:status run)))
      (is (nil? (get-in run [:step-runs "plan" :accepted-result]))
          "late actor success must not be recorded after cancellation wins the CAS")
      (is (empty? (get-in run [:step-runs "build" :attempts]))
          "ordinary advancement to the next step must not occur after cancellation"))))

(deftest post-entry-ordinary-failure-write-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 2: cancellation racing
  ;; after actor failure admission but before :step/record-failure commits must
  ;; not record ordinary execution failure or rewrite the cancelled run.
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx linear-definition "run-record-failure-cancel-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-record-failure-cancel-race")]
    (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                  (fn [_ctx _parent-session-id opts]
                    {:attempt {:attempt-id (:attempt-id opts)
                               :status :pending
                               :execution-session-id "plan-child"}
                     :execution-session {:session-id "plan-child"}})
                  psi.workflow-runtime.turn-execution-contract/execute-actor-turn!
                  (fn [& _]
                    {:status :error
                     :session-id "plan-child"
                     :assistant-message {:role "assistant"
                                         :error-message "boom"
                                         :content [{:type :error :text "boom"}]}
                     :assistant-text ""
                     :execution-result {}
                     :failure {:reason :boom
                               :message "boom"}})
                  psi.workflow-runtime.progression-recording/record-attempt-execution-failure
                  (let [real-record psi.workflow-runtime.progression-recording/record-attempt-execution-failure]
                    (fn [state run-id step-id payload]
                      (swap! (:state* ctx)
                             (fn [current-state]
                               (-> current-state
                                   (assoc-in [:workflows :runs run-id :status] :cancelled)
                                   (assoc-in [:workflows :runs run-id :finished-at] (java.time.Instant/now))
                                   (assoc-in [:workflows :runs run-id :terminal-outcome]
                                             {:outcome :cancelled
                                              :reason "failure race"
                                              :step-id step-id}))))
                      (real-record state run-id step-id payload)))]
      (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-record-failure-cancel-race")
          attempt (get-in run [:step-runs "plan" :attempts 0])]
      (is (= :cancelled (:status run)))
      (is (= :running (:status attempt))
          "late ordinary failure must not rewrite the already-started attempt after cancellation")
      (is (nil? (:execution-error attempt))))))

(deftest judge-result-write-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 2: cancellation racing
  ;; after judge output is admitted but before :judge/record commits must not
  ;; record judge output or rewrite :cancelled to :completed/:failed/:running.
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx judged-definition "run-judge-record-cancel-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-judge-record-cancel-race")]
    (with-stubbed-runtime {:assistant-text "review-output"
                           :judge-result {:judge-session-id "judge-race"
                                          :judge-output "APPROVED"
                                          :judge-event "APPROVED"
                                          :routing-result {:action :complete}}}
      #(with-redefs [psi.workflow-runtime.progression-recording/record-judge-result
                     (let [real-record psi.workflow-runtime.progression-recording/record-judge-result]
                       (fn [state run-id step-id judge-result]
                         (swap! (:state* ctx)
                                (fn [current-state]
                                  (-> current-state
                                      (assoc-in [:workflows :runs run-id :status] :cancelled)
                                      (assoc-in [:workflows :runs run-id :finished-at] (java.time.Instant/now))
                                      (assoc-in [:workflows :runs run-id :terminal-outcome]
                                                {:outcome :cancelled
                                                 :reason "judge race"
                                                 :step-id step-id}))))
                         (real-record state run-id step-id judge-result)))]
         (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil)))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-judge-record-cancel-race")
          review-attempt (get-in run [:step-runs "review" :attempts 0])]
      (is (= :cancelled (:status run)))
      (is (nil? (:judge-session-id review-attempt))
          "late judge output must not be recorded after cancellation wins")
      (is (nil? (:judge-output review-attempt))))))

(deftest iteration-exhausted-write-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 2: cancellation racing
  ;; before :iteration/exhausted commits must not rewrite :cancelled to :failed.
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx judged-definition "run-iteration-exhausted-cancel-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-iteration-exhausted-cancel-race")
        actions-fn (:actions-fn wf-ctx)]
    (swap! (:working-memory* wf-ctx)
           (fn [wm]
             (-> wm
                 (assoc :judge-results {"review" {:judge-session-id "judge-r"
                                                  :judge-output "REVISE"
                                                  :judge-event "REVISE"}}
                        :step-outputs {"review" {:outputs {:final-llm-reply "needs work"}}}
                        :iteration-counts {"review" 3})
                 (assoc-in [:attempt-ids "review"] "attempt-review"))))
    (swap! (:state* ctx)
           (fn [state]
             (-> state
                 (assoc-in [:workflows :runs "run-iteration-exhausted-cancel-race" :status] :running)
                 (assoc-in [:workflows :runs "run-iteration-exhausted-cancel-race" :current-step-id] "review")
                 (assoc-in [:workflows :runs "run-iteration-exhausted-cancel-race" :step-runs "review" :attempts]
                           [{:attempt-id "attempt-review"
                             :status :running
                             :execution-session-id "review-child"}]))))
    (with-redefs [psi.workflow-runtime.progression-recording/record-judge-result
                  (let [real-record psi.workflow-runtime.progression-recording/record-judge-result]
                    (fn [state run-id step-id judge-result]
                      (swap! (:state* ctx)
                             (fn [current-state]
                               (-> current-state
                                   (assoc-in [:workflows :runs run-id :status] :cancelled)
                                   (assoc-in [:workflows :runs run-id :finished-at] (java.time.Instant/now))
                                   (assoc-in [:workflows :runs run-id :terminal-outcome]
                                             {:outcome :cancelled
                                              :reason "iteration race"
                                              :step-id step-id}))))
                      (real-record state run-id step-id judge-result)))]
      (actions-fn :iteration/exhausted {:step-id "review"}))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-iteration-exhausted-cancel-race")
          review-attempt (get-in run [:step-runs "review" :attempts 0])]
      (is (= :cancelled (:status run)))
      (is (nil? (:judge-session-id review-attempt)))
      (is (not= :iteration-limit-reached (get-in run [:terminal-outcome :reason]))))))

(deftest cancel-during-judged-step-judge-turn-does-not-record-judge-output-test
  ;; Regression for task 225 implementation review pass 2: cancellation during a
  ;; judged step's judge turn must stop before ordinary judge output is queued or
  ;; recorded.
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx judged-definition "run-judge-turn-cancel")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-judge-turn-cancel")
        judge-prompts* (atom [])]
    (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                  (fn [_ctx _parent-session-id opts]
                    (let [sid (str (:workflow-step-id opts) "-child")]
                      {:attempt {:attempt-id (:attempt-id opts)
                                 :status :pending
                                 :execution-session-id sid}
                       :execution-session {:session-id sid}}))
                  psi.workflow-runtime.turn-execution-contract/execute-actor-turn!
                  (fn [_ctx sid _prompt]
                    {:status :ok
                     :session-id sid
                     :assistant-message {:role "assistant"
                                         :content [{:type :text :text (if (= sid "plan-child")
                                                                        "plan text"
                                                                        "review output")}]}
                     :assistant-text (if (= sid "plan-child") "plan text" "review output")
                     :execution-result {}})
                  psi.workflow-runtime.execution-adapter/create-child-session!
                  (let [real-create psi.workflow-runtime.execution-adapter/create-child-session!]
                    (fn [ctx' parent-session-id opts]
                      (real-create ctx' parent-session-id opts)))
                  psi.workflow-runtime.turn-execution-contract/execute-judge-turn!
                  (fn [_ctx sid text]
                    (swap! judge-prompts* conj {:session-id sid :text text})
                    (swap! (:state* ctx)
                           (fn [state]
                             (-> state
                                 (assoc-in [:workflows :runs "run-judge-turn-cancel" :status] :cancelled)
                                 (assoc-in [:workflows :runs "run-judge-turn-cancel" :finished-at] (java.time.Instant/now))
                                 (assoc-in [:workflows :runs "run-judge-turn-cancel" :terminal-outcome]
                                           {:outcome :cancelled
                                            :reason "judge turn race"
                                            :step-id "review"}))))
                    {:status :ok
                     :session-id sid
                     :assistant-message {:role "assistant" :content [{:type :text :text "APPROVED"}]}
                     :assistant-text "APPROVED"
                     :execution-result {}})]
      (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-judge-turn-cancel")
          review-attempt (get-in run [:step-runs "review" :attempts 0])]
      (is (= :cancelled (:status run)))
      (is (= 1 (count @judge-prompts*))
          "the race is cancellation during the in-flight judge turn")
      (is (string? (:judge-session-id review-attempt))
          "the in-flight judge session remains addressable for guarded cancellation abort")
      (is (nil? (:judge-output review-attempt))
          "ordinary judge output must not be recorded after cancellation during judge turn")
      (is (empty? (get-in run [:step-runs "build" :attempts]))))))

(deftest cancel-from-blocked-state-test
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx linear-definition "run-4")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-4")]
    (swap! (:state* ctx)
           assoc-in [:workflows :runs "run-4" :status] :blocked)
    (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/cancel nil)
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-4")]
      (is (= :cancelled (:status run))))))

