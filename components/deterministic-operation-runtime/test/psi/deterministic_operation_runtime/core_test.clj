(ns psi.deterministic-operation-runtime.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.deterministic-operation-runtime.core :as runtime]
   [psi.workflow-coordination.cancellation-entry :as cancellation-entry]))

(deftest invoke-operation-test
  (testing "handler receives injected operation-id"
    (let [received* (atom nil)]
      (is (= {:status :ok :data {:ok true}}
             (runtime/invoke-operation {:id "github/search"
                                        :handler (fn [invocation]
                                                   (reset! received* invocation)
                                                   {:status :ok :data {:ok true}})}
                                       {:args {:repo 1}})))
      (is (= {:operation-id "github/search"
              :args {:repo 1}}
             (select-keys @received* [:operation-id :args])))))

  (testing "successful operation results pass through unchanged"
    (let [result {:status :ok
                  :data {:issues [1]}
                  :summary "1 issue"}]
      (is (= result
             (runtime/invoke-operation {:id "github/search"
                                        :handler (fn [_]
                                                   result)}
                                       {:args {:repo 1}})))))

  (testing "thrown exceptions are canonicalized into tagged error results"
    (is (= {:status :error
            :reason :operation-threw
            :message "boom"
            :details {:operation-id "github/search"}}
           (runtime/invoke-operation {:id "github/search"
                                      :handler (fn [_]
                                                 (throw (ex-info "boom" {})))}
                                     {:args {}}))))

  (testing "malformed returned values are rejected with structured ex-info"
    (let [ex (try
               (runtime/invoke-operation {:id "github/search"
                                          :handler (fn [_] {:status :succeeded :data {}})}
                                         {:args {}
                                          :ctx :opaque
                                          :step-id "discover"})
               (catch clojure.lang.ExceptionInfo e
                 e))]
      (is (= "Deterministic operation returned malformed result"
             (ex-message ex)))
      (is (= {:type :malformed-operation-result
              :operation-id "github/search"
              :invocation {:args {}
                           :step-id "discover"}
              :result {:status :succeeded :data {}}}
             (select-keys (ex-data ex)
                          [:type :operation-id :invocation :result])))
      (is (= [{:path [:status]
               :in [:status]
               :type :malli.core/invalid-dispatch-value}]
             (mapv #(select-keys % [:path :in :type])
                   (:errors (:explanation (ex-data ex)))))))))

(deftest invoke-operation-honors-workflow-cancellation-test
  ;; Regression for task 225 implementation review pass 6: the deterministic
  ;; operation runtime boundary consumes the durable workflow stop marker, so an
  ;; operation cannot start after the canonical cancel CAS wins.
  (testing "cancelled workflow run prevents handler invocation"
    (let [handler-calls* (atom 0)
          result (runtime/invoke-operation
                  {:id "workflow/op"
                   :handler (fn [_]
                              (swap! handler-calls* inc)
                              {:status :ok :data {:started? true}})}
                  {:ctx {:state* (atom {:workflows {:runs {"run-cancelled" {:run-id "run-cancelled"
                                                                            :status :cancelled}}}})}
                   :workflow-run-id "run-cancelled"
                   :step-id "invoke"})]
      (is (= 0 @handler-calls*)
          "the operation handler must not be invoked after cancellation")
      (is (= {:status :error
              :reason :workflow-stopped
              :message "Workflow execution stopped before deterministic operation start"
              :details {:operation-id "workflow/op"
                        :workflow-run-id "run-cancelled"
                        :step-id "invoke"
                        :stop-reason :cancelled}}
             result)))))

(deftest invoke-operation-uses-shared-workflow-cancellation-primitives-test
  ;; Regression for task 225 code-shaper follow-up: deterministic-operation
  ;; entry uses the shared workflow-coordination stop predicate and
  ;; cancellation-entry lock, not a private duplicate implementation.
  (testing "removed workflow run prevents handler invocation"
    (let [handler-calls* (atom 0)
          result (runtime/invoke-operation
                  {:id "workflow/op"
                   :handler (fn [_]
                              (swap! handler-calls* inc)
                              {:status :ok :data {:started? true}})}
                  {:ctx {:state* (atom {:workflows {:runs {}}})}
                   :workflow-run-id "removed-run"
                   :step-id "invoke"})]
      (is (= 0 @handler-calls*))
      (is (= :workflow-stopped (:reason result)))
      (is (= :removed (get-in result [:details :stop-reason])))))

  (testing "handler entry is linearized by the shared cancellation-entry lock"
    (let [state* (atom {:workflows {:runs {"run-1" {:run-id "run-1"
                                                    :status :running
                                                    :step-runs {"invoke" {:attempts [{:attempt-id "attempt-1"
                                                                                      :operation-call-state :committed}]}}}}}})
          locks* (atom {})
          ctx {:state* state*
               :workflow-cancellation-entry-locks-handle locks*}
          shared-lock (cancellation-entry/lock-for ctx "run-1")
          handler-calls* (atom 0)
          result (runtime/invoke-operation
                  {:id "workflow/op"
                   :handler (fn [_]
                              (swap! handler-calls* inc)
                              {:status :ok :data {:started? true}})}
                  {:ctx ctx
                   :workflow-run-id "run-1"
                   :workflow-attempt-id "attempt-1"
                   :step-id "invoke"})]
      (is (identical? shared-lock (get @locks* "run-1"))
          "deterministic operation runtime must reuse the shared cancellation-entry lock entry")
      (is (= 1 @handler-calls*))
      (is (= :ok (:status result)))
      (is (= :entered (get-in @state* [:workflows :runs "run-1" :step-runs "invoke" :attempts 0 :operation-handler-entry-state]))))))

(deftest invoke-step-operation-then-judge-operation-share-one-attempt-test
  ;; Regression for task 228: an :invoke step that carries both an :operation and
  ;; an invoke :judge runs TWO deterministic operations against the SAME step
  ;; attempt. Without per-operation phase-key namespacing, the second (judge)
  ;; operation sees the residual :operation-handler-entry-state :entered left by
  ;; the first (step) operation and aborts with :handler-entry-state-mismatch.
  ;; The judge operation must carry :operation-role :judge so it drives a
  ;; distinct :judge-operation-*-state key namespace.
  (testing "step operation then judge operation both enter against one attempt"
    (let [state* (atom {:workflows {:runs {"run-1" {:run-id "run-1"
                                                    :status :running
                                                    :step-runs {"clarity-status"
                                                                {:attempts [{:attempt-id "attempt-1"}]}}}}}})
          ctx {:state* state*}
          step-calls* (atom 0)
          judge-calls* (atom 0)
          base-invocation {:ctx ctx
                           :workflow-run-id "run-1"
                           :workflow-attempt-id "attempt-1"
                           :step-id "clarity-status"}
          step-result (runtime/invoke-operation
                       {:id "workflow/clarity-status"
                        :handler (fn [_]
                                   (swap! step-calls* inc)
                                   {:status :ok :data {:step? true}})}
                       base-invocation)
          judge-result (runtime/invoke-operation
                        {:id "workflow/pass-feedback-routing"
                         :handler (fn [_]
                                    (swap! judge-calls* inc)
                                    {:status :ok :data {:judge? true}})}
                        (assoc base-invocation :operation-role :judge))
          attempt (get-in @state* [:workflows :runs "run-1" :step-runs
                                   "clarity-status" :attempts 0])]
      (is (= 1 @step-calls*) "the step operation handler runs once")
      (is (= :ok (:status step-result)))
      (is (= 1 @judge-calls*) "the judge operation handler runs once")
      (is (= :ok (:status judge-result))
          "the judge operation must succeed, not abort with :handler-entry-state-mismatch")
      (is (= :entered (:operation-handler-entry-state attempt))
          "the step operation's :operation-*-state keys are untouched by the judge")
      (is (= :entered (:judge-operation-handler-entry-state attempt))
          "the judge operation drives its own :judge-operation-*-state namespace"))))

(deftest judge-role-operation-honors-workflow-cancellation-test
  ;; Task 228 regression: per-operation phase-key namespacing must not weaken the
  ;; task-225 cooperative cancellation guard. A judge-role operation against a
  ;; cancelled run must still refuse to start and yield a clean :workflow-stopped
  ;; terminal without invoking its handler.
  (testing "cancelled run stops a judge-role operation before handler invocation"
    (let [handler-calls* (atom 0)
          result (runtime/invoke-operation
                  {:id "workflow/pass-feedback-routing"
                   :handler (fn [_]
                              (swap! handler-calls* inc)
                              {:status :ok :data {:started? true}})}
                  {:ctx {:state* (atom {:workflows {:runs {"run-cancelled" {:run-id "run-cancelled"
                                                                            :status :cancelled}}}})}
                   :workflow-run-id "run-cancelled"
                   :operation-role :judge
                   :step-id "clarity-status"})]
      (is (= 0 @handler-calls*)
          "the judge operation handler must not run after cancellation")
      (is (= {:status :error
              :reason :workflow-stopped
              :message "Workflow execution stopped before deterministic operation start"
              :details {:operation-id "workflow/pass-feedback-routing"
                        :workflow-run-id "run-cancelled"
                        :step-id "clarity-status"
                        :stop-reason :cancelled}}
             result)))))
