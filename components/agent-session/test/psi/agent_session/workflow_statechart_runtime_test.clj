(ns psi.agent-session.workflow-statechart-runtime-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.fulcrologic.statecharts :as sc]
   [psi.agent-session.core :as session]
   [psi.agent-session.prompt-control]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-judge]
   [psi.agent-session.workflow-runtime :as workflow-runtime]
   [psi.agent-session.workflow-statechart-runtime :as runtime]))

(def linear-definition
  {:definition-id "plan-build"
   :step-order ["plan" "build"]
   :steps {"plan" {:executor {:type :agent :profile "planner"}
                   :prompt-template "$INPUT"
                   :input-bindings {:input {:source :workflow-input :path [:input]}}
                   :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                   :retry-policy {:max-attempts 1 :retry-on #{}}}
           "build" {:executor {:type :agent :profile "builder"}
                    :prompt-template "$INPUT"
                    :input-bindings {:input {:source :workflow-input :path [:input]}}
                    :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                    :retry-policy {:max-attempts 1 :retry-on #{}}}}})

(def single-step-definition
  {:definition-id "plan-only"
   :step-order ["plan"]
   :steps {"plan" {:executor {:type :agent :profile "planner"}
                   :prompt-template "$INPUT"
                   :input-bindings {:input {:source :workflow-input :path [:input]}}
                   :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                   :retry-policy {:max-attempts 1 :retry-on #{}}}}})

(def judged-definition
  {:definition-id "plan-build-review"
   :step-order ["plan" "build" "review"]
   :steps {"plan" {:executor {:type :agent :profile "planner"}
                   :prompt-template "$INPUT"
                   :input-bindings {:input {:source :workflow-input :path [:input]}}
                   :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                   :retry-policy {:max-attempts 1 :retry-on #{}}}
           "build" {:executor {:type :agent :profile "builder"}
                    :prompt-template "$INPUT"
                    :input-bindings {:input {:source :workflow-input :path [:input]}}
                    :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                    :retry-policy {:max-attempts 2 :retry-on #{:execution-failed}}}
           "review" {:executor {:type :agent :profile "reviewer"}
                     :prompt-template "$INPUT"
                     :input-bindings {:input {:source :workflow-input :path [:input]}}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                     :retry-policy {:max-attempts 1 :retry-on #{}}
                     :judge {:prompt "APPROVED or REVISE?"}
                     :on {"APPROVED" {:goto :next}
                          "REVISE" {:goto "build" :max-iterations 3}}}}})

(defn- create-session-context
  []
  (let [ctx (session/create-context (test-support/safe-context-opts {:persist? false}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(defn- install-run!
  [ctx definition run-id]
  (swap! (:state* ctx)
         (fn [state]
           (let [[s _ _] (workflow-runtime/register-definition state definition)
                 [s _ _] (workflow-runtime/create-run s {:definition-id (:definition-id definition)
                                                         :run-id run-id
                                                         :workflow-input {:input "go"}})]
             s))))

(defmacro with-stubbed-actor-execution
  [& body]
  `(with-redefs [psi.agent-session.prompt-control/prompt-in! (fn [_ctx# _sid# _prompt#] nil)
                 psi.agent-session.prompt-control/last-assistant-message-in (fn [_ctx# _sid#]
                                                                              {:role "assistant"
                                                                               :content [{:type :text :text "ok"}]
                                                                               :stop-reason :stop})
                 psi.agent-session.workflow-judge/execute-judge! (fn [& _args#]
                                                                   {:judge-session-id "judge-stub"
                                                                    :judge-output "APPROVED"
                                                                    :judge-event "APPROVED"
                                                                    :routing-result {:action :complete}})]
     ~@body))

(deftest create-workflow-context-seeds-working-memory-test
  (testing "working memory is seeded from the canonical workflow run"
    (let [[ctx session-id] (create-session-context)
          _ (install-run! ctx linear-definition "run-1")
          wf-ctx (runtime/create-workflow-context ctx session-id "run-1")
          wm @(:working-memory* wf-ctx)]
      (is (= "run-1" (:workflow-run-id wm)))
      (is (= session-id (:parent-session-id wm)))
      (is (= {"plan" 0 "build" 0} (:iteration-counts wm)))
      (is (= {} (:attempt-ids wm)))
      (is (= nil (:blocked-step-id wm)))
      (is (= "plan" (:current-step-id wm))))))

(deftest create-workflow-context-starts-chart-and-projects-pending-test
  (testing "created workflow context starts in pending and projects pending status"
    (let [[ctx session-id] (create-session-context)
          _ (install-run! ctx linear-definition "run-2")
          wf-ctx (runtime/create-workflow-context ctx session-id "run-2")
          run (workflow-runtime/workflow-run-in @(:state* ctx) "run-2")]
      (is (= #{:pending} (::sc/configuration (:wm wf-ctx))))
      (is (= :pending (:status run))))))

(deftest step-entry-allocates-fresh-attempt-and-projects-running-test
  (testing "entering acting allocates a fresh attempt, increments iteration, and projects running"
    (let [[ctx session-id] (create-session-context)
          _ (install-run! ctx linear-definition "run-3")
          wf-ctx (runtime/create-workflow-context ctx session-id "run-3")]
      (with-stubbed-actor-execution
        (let [wm' (runtime/process-event! wf-ctx (:wm wf-ctx) :workflow/start nil)
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-3")
              attempt-id (get-in @(:working-memory* wf-ctx) [:attempt-ids "plan"])]
          (is (= #{:step/plan :step/plan.acting} (::sc/configuration wm')))
          (is (string? attempt-id))
          (is (= 1 (get-in @(:working-memory* wf-ctx) [:iteration-counts "plan"])))
          (is (= :running (:status run)))
          (is (= "plan" (:current-step-id run)))
          (is (= attempt-id (get-in run [:step-runs "plan" :attempts 0 :attempt-id]))))))))

(deftest retry-predicate-reflects-attempt-budget-test
  (testing "retry availability is derived from authoritative attempt counts and max-attempts"
    (let [[ctx session-id] (create-session-context)
          _ (install-run! ctx linear-definition "run-4")
          wf-ctx (runtime/create-workflow-context ctx session-id "run-4")
          actions-fn (:actions-fn wf-ctx)]
      (is (true? (actions-fn :retry-available? {:step-id "plan"})))
      (with-stubbed-actor-execution
        (runtime/process-event! wf-ctx (:wm wf-ctx) :workflow/start nil))
      (is (false? (actions-fn :retry-available? {:step-id "plan"}))))))

(deftest resume-allocates-fresh-attempt-id-test
  (testing "resume re-enters .acting with a fresh attempt id"
    (let [[ctx session-id] (create-session-context)
          _ (install-run! ctx linear-definition "run-4b")
          wf-ctx (runtime/create-workflow-context ctx session-id "run-4b")]
      (with-stubbed-actor-execution
        (let [wm1 (runtime/process-event! wf-ctx (:wm wf-ctx) :workflow/start nil)
              a1 (get-in @(:working-memory* wf-ctx) [:attempt-ids "plan"])
              wm2 (runtime/process-event! wf-ctx wm1 :actor/blocked {:iteration-counts {"plan" 1}})
              wm3 (runtime/process-event! wf-ctx wm2 :workflow/resume {:iteration-counts {"plan" 1}})
              a2 (get-in @(:working-memory* wf-ctx) [:attempt-ids "plan"])]
          (is (= #{:step/plan :step/plan.blocked} (::sc/configuration wm2)))
          (is (= #{:step/plan :step/plan.acting} (::sc/configuration wm3)))
          (is (not= a1 a2)))))))

(deftest judging-preserves-same-attempt-id-test
  (testing "acting -> judging preserves the same attempt id for the step"
    (let [[ctx session-id] (create-session-context)
          _ (install-run! ctx judged-definition "run-5")
          wf-ctx (runtime/create-workflow-context ctx session-id "run-5")]
      (with-stubbed-actor-execution
        (let [wm1 (runtime/process-event! wf-ctx (:wm wf-ctx) :workflow/start nil)
              wm2 (runtime/process-event! wf-ctx wm1 :actor/done {:iteration-counts {"plan" 1}})
              wm3 (runtime/process-event! wf-ctx wm2 :actor/done {:iteration-counts {"plan" 1 "build" 1}})
              build-attempt (get-in @(:working-memory* wf-ctx) [:attempt-ids "build"])
              wm4 (runtime/process-event! wf-ctx wm3 :actor/done {:iteration-counts {"plan" 1 "build" 1 "review" 1}})
              review-attempt-before (get-in @(:working-memory* wf-ctx) [:attempt-ids "review"])
              _ (runtime/process-event! wf-ctx wm4 :judge/no-match {:iteration-counts {"plan" 1 "build" 1 "review" 1}})
              review-attempt-after (get-in @(:working-memory* wf-ctx) [:attempt-ids "review"])]
          (is (string? build-attempt))
          (is (= #{:step/review :step/review.judging} (::sc/configuration wm4)))
          (is (= review-attempt-before review-attempt-after)))))))

(deftest blocked-entry-records-authoritative-blocked-step-test
  (testing "blocked entry records blocked-step-id in authoritative working memory and projects blocked status"
    (let [[ctx session-id] (create-session-context)
          _ (install-run! ctx linear-definition "run-6")
          wf-ctx (runtime/create-workflow-context ctx session-id "run-6")]
      (with-stubbed-actor-execution
        (let [wm1 (runtime/process-event! wf-ctx (:wm wf-ctx) :workflow/start nil)
              wm2 (runtime/process-event! wf-ctx wm1 :actor/blocked {:iteration-counts {"plan" 1}})
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-6")]
          (is (= #{:step/plan :step/plan.blocked} (::sc/configuration wm2)))
          (is (= "plan" (:blocked-step-id @(:working-memory* wf-ctx))))
          (is (= :blocked (:status run)))
          (is (= {:step-id "plan"} (:blocked run))))))))

(deftest linear-success-recording-test
  (testing "successful linear actor completion records accepted result and advances to next step"
    (let [[ctx session-id] (create-session-context)
          _ (install-run! ctx single-step-definition "run-7")
          wf-ctx (runtime/create-workflow-context ctx session-id "run-7")
          _ (with-redefs [psi.agent-session.prompt-control/prompt-in! (fn [_ctx _sid _prompt] nil)
                          psi.agent-session.prompt-control/last-assistant-message-in (fn [_ctx _sid]
                                                                                       {:role "assistant"
                                                                                        :content [{:type :text :text "planned"}]
                                                                                        :stop-reason :stop})]
              (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
          run (workflow-runtime/workflow-run-in @(:state* ctx) "run-7")]
      (is (= :completed (:status run)))
      (is (nil? (:current-step-id run)))
      (is (= {:outcome :ok :outputs {:text "planned"}}
             (get-in run [:step-runs "plan" :accepted-result])))
      (is (= :succeeded (get-in run [:step-runs "plan" :attempts 0 :status])))
      (is (= nil (:pending-actor-result @(:working-memory* wf-ctx)))))))

(deftest linear-failure-recording-test
  (testing "erroring actor completion records failure and transitions terminally when not retryable"
    (let [[ctx session-id] (create-session-context)
          _ (install-run! ctx linear-definition "run-8")
          wf-ctx (runtime/create-workflow-context ctx session-id "run-8")
          _ (with-redefs [psi.agent-session.prompt-control/prompt-in! (fn [_ctx _sid _prompt] nil)
                          psi.agent-session.prompt-control/last-assistant-message-in (fn [_ctx _sid]
                                                                                       {:role "assistant"
                                                                                        :content [{:type :error :text "boom"}]
                                                                                        :stop-reason :error
                                                                                        :error-message "boom"})]
              (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
          run (workflow-runtime/workflow-run-in @(:state* ctx) "run-8")]
      (is (= :failed (:status run)))
      (is (= :execution-failed (get-in run [:step-runs "plan" :attempts 0 :status])))
      (is (= "boom" (get-in run [:step-runs "plan" :attempts 0 :execution-error :message])))
      (is (= nil (:pending-actor-result @(:working-memory* wf-ctx)))))))

(deftest judged-review-approved-recording-test
  (testing "judge APPROVED records verdict metadata and completes the run"
    (let [[ctx session-id] (create-session-context)
          _ (install-run! ctx judged-definition "run-9")
          wf-ctx (runtime/create-workflow-context ctx session-id "run-9")]
      (swap! (:state* ctx)
             (fn [state]
               (-> state
                   (assoc-in [:workflows :runs "run-9" :current-step-id] "review")
                   (assoc-in [:workflows :runs "run-9" :step-runs "build" :iteration-count] 1))))
      (with-redefs [psi.agent-session.prompt-control/prompt-in! (fn [_ctx _sid _prompt] nil)
                    psi.agent-session.prompt-control/last-assistant-message-in (let [calls* (atom 0)]
                                                                                 (fn [_ctx _sid]
                                                                                   (if (= 1 (swap! calls* inc))
                                                                                     {:role "assistant"
                                                                                      :content [{:type :text :text "review-output"}]
                                                                                      :stop-reason :stop}
                                                                                     {:role "assistant"
                                                                                      :content [{:type :text :text "APPROVED"}]
                                                                                      :stop-reason :stop})))]
        (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
      (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-9")
            review-attempt (get-in run [:step-runs "review" :attempts 0])]
        (is (= :completed (:status run)))
        (is (= "APPROVED" (:judge-output review-attempt)))
        (is (= "APPROVED" (:judge-event review-attempt)))
        (is (string? (:judge-session-id review-attempt)))
        (is (nil? (:pending-judge-result @(:working-memory* wf-ctx))))
        (is (nil? (:pending-routing @(:working-memory* wf-ctx)))))))

  (deftest judged-review-revise-routes-to-build-test
    (testing "judge REVISE routes back to build while preserving the same review attempt id"
      (let [[ctx session-id] (create-session-context)
            _ (install-run! ctx judged-definition "run-10")
            wf-ctx (runtime/create-workflow-context ctx session-id "run-10")]
        (swap! (:state* ctx)
               (fn [state]
                 (-> state
                     (assoc-in [:workflows :runs "run-10" :current-step-id] "review")
                     (assoc-in [:workflows :runs "run-10" :step-runs "build" :iteration-count] 1))))
        (with-redefs [psi.agent-session.prompt-control/prompt-in! (fn [_ctx _sid _prompt] nil)
                      psi.agent-session.prompt-control/last-assistant-message-in (let [calls* (atom 0)]
                                                                                   (fn [_ctx _sid]
                                                                                     (if (= 1 (swap! calls* inc))
                                                                                       {:role "assistant"
                                                                                        :content [{:type :text :text "review-output"}]
                                                                                        :stop-reason :stop}
                                                                                       {:role "assistant"
                                                                                        :content [{:type :text :text "REVISE"}]
                                                                                        :stop-reason :stop})))]
          (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
        (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-10")
              review-attempt (get-in run [:step-runs "review" :attempts 0])]
          (is (= :running (:status run)))
          (is (= "build" (:current-step-id run)))
          (is (= "REVISE" (:judge-event review-attempt)))
          (is (string? (:attempt-id review-attempt)))
          (is (nil? (:pending-judge-result @(:working-memory* wf-ctx))))))))

  (deftest cancel-from-blocked-state-test
    (testing "workflow/cancel from blocked reaches cancelled"
      (let [[ctx session-id] (create-session-context)
            _ (install-run! ctx linear-definition "run-11")
            wf-ctx (runtime/create-workflow-context ctx session-id "run-11")
            wm1 (runtime/process-event! wf-ctx (:wm wf-ctx) :workflow/start nil)
            wm2 (runtime/process-event! wf-ctx wm1 :actor/blocked {:iteration-counts {"plan" 1}})
            wm3 (runtime/process-event! wf-ctx wm2 :workflow/cancel nil)
            run (workflow-runtime/workflow-run-in @(:state* ctx) "run-11")]
        (is (= #{:cancelled} (::sc/configuration wm3)))
        (is (= :cancelled (:status run))))))

  (deftest cancel-from-judging-state-test
    (testing "workflow/cancel from judging reaches cancelled"
      (let [[ctx session-id] (create-session-context)
            _ (install-run! ctx judged-definition "run-12")
            wf-ctx (runtime/create-workflow-context ctx session-id "run-12")]
        (swap! (:state* ctx)
               (fn [state]
                 (-> state
                     (assoc-in [:workflows :runs "run-12" :current-step-id] "review")
                     (assoc-in [:workflows :runs "run-12" :step-runs "build" :iteration-count] 1))))
        (with-redefs [psi.agent-session.prompt-control/prompt-in! (fn [_ctx _sid _prompt] nil)
                      psi.agent-session.prompt-control/last-assistant-message-in (fn [_ctx _sid]
                                                                                   {:role "assistant"
                                                                                    :content [{:type :text :text "review-output"}]
                                                                                    :stop-reason :stop})]
          (let [wm1 (runtime/process-event! wf-ctx (:wm wf-ctx) :workflow/start nil)
                wm2 (runtime/process-event! wf-ctx wm1 :workflow/cancel nil)
                run (workflow-runtime/workflow-run-in @(:state* ctx) "run-12")]
            (is (= #{:cancelled} (::sc/configuration wm2)))
            (is (= :cancelled (:status run))))))))

  (deftest terminal-tail-events-are-discarded-test
    (testing "queued tail events are discarded after terminal entry"
      (let [[ctx session-id] (create-session-context)
            _ (install-run! ctx single-step-definition "run-13")
            wf-ctx (runtime/create-workflow-context ctx session-id "run-13")]
        (with-redefs [psi.agent-session.prompt-control/prompt-in! (fn [_ctx _sid _prompt]
                                                                  ;; queue an impossible tail event after success
                                                                    (runtime/queue-event! wf-ctx :workflow/cancel nil)
                                                                    nil)
                      psi.agent-session.prompt-control/last-assistant-message-in (fn [_ctx _sid]
                                                                                   {:role "assistant"
                                                                                    :content [{:type :text :text "done"}]
                                                                                    :stop-reason :stop})]
          (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
        (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-13")]
          (is (= :completed (:status run)))
          (is (empty? @(:event-queue* wf-ctx)))))))

  (deftest queued-cancel-is-fifo-not-interrupt-test
    (testing "externally queued cancel does not interrupt already-running entry work; it is processed later via FIFO"
      (let [[ctx session-id] (create-session-context)
            _ (install-run! ctx single-step-definition "run-14")
            wf-ctx (runtime/create-workflow-context ctx session-id "run-14")
            seen* (atom [])]
        (with-redefs [psi.agent-session.prompt-control/prompt-in! (fn [_ctx _sid _prompt]
                                                                  ;; emulate external cancel arriving while entry work is in flight
                                                                    (swap! seen* conj :entry-work)
                                                                    (runtime/queue-event! wf-ctx :workflow/cancel nil)
                                                                    nil)
                      psi.agent-session.prompt-control/last-assistant-message-in (fn [_ctx _sid]
                                                                                   {:role "assistant"
                                                                                    :content [{:type :text :text "done"}]
                                                                                    :stop-reason :stop})]
          (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
        (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-14")]
          (is (= [:entry-work] @seen*))
          (is (= :cancelled (:status run)))))))

  (deftest status-projection-comes-from-active-chart-state-test
    (testing "status projection is derived from active chart state, not independently invented"
      (is (= :pending (runtime/run-status-from-configuration #{:pending})))
      (is (= :running (runtime/run-status-from-configuration #{:step/plan :step/plan.acting})))
      (is (= :blocked (runtime/run-status-from-configuration #{:step/plan :step/plan.blocked})))
      (is (= :completed (runtime/run-status-from-configuration #{:completed})))
      (is (= :failed (runtime/run-status-from-configuration #{:failed})))
      (is (= :cancelled (runtime/run-status-from-configuration #{:cancelled}))))

    (testing "logical current-step-id projection ignores substate suffixes"
      (is (= "plan" (runtime/step-id-from-configuration #{:step/plan :step/plan.acting})))
      (is (= "plan" (runtime/step-id-from-configuration #{:step/plan :step/plan.blocked})))
      (is (= "review" (runtime/step-id-from-configuration #{:step/review :step/review.judging})))
      (is (nil? (runtime/step-id-from-configuration #{:completed}))))))
