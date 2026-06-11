(ns psi.agent-session.workflow-judge-cancellation-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.workflow-judge :as workflow-judge]
   [psi.session-persistence.core]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.execution-adapter :as workflow-execution-adapter]
   [psi.workflow-runtime.turn-execution-contract]))

(deftest execute-judge-aborts-created-session-when-attachment-loses-cancellation-race-test
  ;; Regression for task 225 implementation review pass 4: cancellation after
  ;; judge child-session creation but before guarded attachment must abort the
  ;; untracked judge session instead of leaving it alive and unaddressable.
  (let [created* (atom [])
        aborted* (atom [])
        ctx {workflow-execution-adapter/adapter-key
             (workflow-execution-adapter/create
              {:create-child-session! (fn [ctx _parent opts]
                                        (swap! created* conj (:child-session-id opts))
                                        (swap! (:state* ctx)
                                               assoc-in [:workflows :runs "run-judge-attach-race" :status] :cancelled)
                                        {:psi.agent-session/session-id (:child-session-id opts)})
               :abort-session! (fn [_ctx session-id]
                                 (swap! aborted* conj session-id))})
             :state* (atom {})}
        initial-state (let [[s _ _] (workflow-runtime/create-run
                                     {}
                                     {:definition {:steps [{:name "review"
                                                            :type :session}]}
                                      :run-id "run-judge-attach-race"})]
                        (-> s
                            (assoc-in [:workflows :runs "run-judge-attach-race" :current-step-id] "review")
                            (assoc-in [:workflows :runs "run-judge-attach-race" :step-runs "review" :attempts]
                                      [{:attempt-id "attempt-review"
                                        :status :succeeded
                                        :execution-session-id "actor-review"}])))]
    (reset! (:state* ctx) initial-state)
    (with-redefs [psi.session-persistence.core/messages-from-entries-in (fn [& _] [])]
      (let [ex (try
                 (workflow-judge/execute-judge!
                  ctx
                  "parent"
                  "actor-review"
                  {:prompt "APPROVED?" :projection :none}
                  {"APPROVED" {:goto :next}}
                  {:current-step-id "review"
                   :step-order ["review"]
                   :step-runs {}
                   :workflow-run-id "run-judge-attach-race"
                   :workflow-attempt-id "attempt-review"})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow-stopped (:reason (ex-data ex))))
        (is (= 1 (count @created*))
            "the race is after judge child-session creation and before guarded attachment")
        (is (= @created* @aborted*)
            "the judge child session created before failed attachment must be aborted")
        (is (nil? (get-in @(:state* ctx)
                          [:workflows :runs "run-judge-attach-race" :step-runs "review" :attempts 0 :judge-session-id]))
            "the judge session was never attached to the cancelled workflow attempt")))))

(deftest execute-judge-final-read-to-call-race-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 10: cancellation after
  ;; the final workflow-state read but before the judge prompt adapter call must
  ;; not initiate the ordinary judge turn.
  (let [prompt-calls* (atom 0)
        ctx {workflow-execution-adapter/adapter-key
             (workflow-execution-adapter/create
              {:create-child-session! (fn [_ctx _parent opts]
                                        {:psi.agent-session/session-id (:child-session-id opts)})})
             :state* (atom {})
             :before-workflow-judge-start-fn
             (fn [ctx _judge-sid {:keys [workflow-run-id workflow-step-id]}]
               (swap! (:state* ctx)
                      (fn [state]
                        (-> state
                            (assoc-in [:workflows :runs workflow-run-id :status] :cancelled)
                            (assoc-in [:workflows :runs workflow-run-id :finished-at] (java.time.Instant/now))
                            (assoc-in [:workflows :runs workflow-run-id :terminal-outcome]
                                      {:outcome :cancelled
                                       :reason "judge final-start race"
                                       :step-id workflow-step-id})))))}
        initial-state (let [[s _ _] (workflow-runtime/create-run
                                     {}
                                     {:definition {:steps [{:name "review"
                                                            :type :session}]}
                                      :run-id "run-judge-final-start-race"})]
                        (-> s
                            (assoc-in [:workflows :runs "run-judge-final-start-race" :current-step-id] "review")
                            (assoc-in [:workflows :runs "run-judge-final-start-race" :step-runs "review" :attempts]
                                      [{:attempt-id "attempt-review"
                                        :status :succeeded
                                        :execution-session-id "actor-review"}])))]
    (reset! (:state* ctx) initial-state)
    (with-redefs [psi.session-persistence.core/messages-from-entries-in (fn [& _] [])]
      (let [ex (try
                 (workflow-judge/execute-judge!
                  ctx
                  "parent"
                  "actor-review"
                  {:prompt "APPROVED?" :projection :none}
                  {"APPROVED" {:goto :next}}
                  {:current-step-id "review"
                   :step-order ["review"]
                   :step-runs {}
                   :workflow-run-id "run-judge-final-start-race"
                   :workflow-attempt-id "attempt-review"
                   :stopped? #(let [run (get-in @(:state* ctx)
                                                [:workflows :runs "run-judge-final-start-race"])]
                                (or (nil? run) (= :cancelled (:status run))))})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow-stopped (:reason (ex-data ex))))
        (is (= 0 @prompt-calls*)
            "the judge turn adapter must not be called after cancellation wins the final read->call window")
        (is (= :cancelled (get-in @(:state* ctx)
                                  [:workflows :runs "run-judge-final-start-race" :status])))))))
(deftest execute-judge-post-reservation-to-call-race-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 11: cancellation after a
  ;; successful judge turn-start reservation but before the judge prompt adapter
  ;; call must still prevent the ordinary judge turn from starting.
  (let [prompt-calls* (atom 0)
        ctx {workflow-execution-adapter/adapter-key
             (workflow-execution-adapter/create
              {:create-child-session! (fn [_ctx _parent opts]
                                        {:psi.agent-session/session-id (:child-session-id opts)})
               :get-session-data (fn [_ctx session-id]
                                   {:session-id session-id
                                    :workflow-owned? true
                                    :workflow-run-id "run-judge-post-reservation-race"
                                    :workflow-step-id "review"
                                    :workflow-attempt-id "attempt-review"})
               :prompt-execution-result! (fn [& _]
                                           (swap! prompt-calls* inc)
                                           {:execution-result/assistant-message
                                            {:role "assistant"
                                             :content [{:type :text :text "APPROVED"}]
                                             :stop-reason :stop}})})
             :state* (atom {})
             :before-workflow-turn-start-fn
             (fn [ctx _judge-sid {:keys [workflow-run-id workflow-step-id phase]}]
               (when (= :after-reserve phase)
                 (swap! (:state* ctx)
                        (fn [state]
                          (-> state
                              (assoc-in [:workflows :runs workflow-run-id :status] :cancelled)
                              (assoc-in [:workflows :runs workflow-run-id :finished-at] (java.time.Instant/now))
                              (assoc-in [:workflows :runs workflow-run-id :terminal-outcome]
                                        {:outcome :cancelled
                                         :reason "judge post-reservation race"
                                         :step-id workflow-step-id}))))))}
        initial-state (let [[s _ _] (workflow-runtime/create-run
                                     {}
                                     {:definition {:steps [{:name "review"
                                                            :type :session}]}
                                      :run-id "run-judge-post-reservation-race"})]
                        (-> s
                            (assoc-in [:workflows :runs "run-judge-post-reservation-race" :current-step-id] "review")
                            (assoc-in [:workflows :runs "run-judge-post-reservation-race" :step-runs "review" :attempts]
                                      [{:attempt-id "attempt-review"
                                        :status :succeeded
                                        :execution-session-id "actor-review"}])))
        stopped? #(let [run (get-in @(:state* ctx)
                                    [:workflows :runs "run-judge-post-reservation-race"])]
                    (or (nil? run) (= :cancelled (:status run))))]
    (reset! (:state* ctx) initial-state)
    (with-redefs [psi.session-persistence.core/messages-from-entries-in (fn [& _] [])]
      (let [ex (try
                 (workflow-judge/execute-judge!
                  ctx
                  "parent"
                  "actor-review"
                  {:prompt "APPROVED?" :projection :none}
                  {"APPROVED" {:goto :next}}
                  {:current-step-id "review"
                   :step-order ["review"]
                   :step-runs {}
                   :workflow-run-id "run-judge-post-reservation-race"
                   :workflow-attempt-id "attempt-review"
                   :stopped? stopped?})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))
            attempt (get-in @(:state* ctx)
                            [:workflows :runs "run-judge-post-reservation-race"
                             :step-runs "review" :attempts 0])]
        (is (= :workflow-stopped (:reason (ex-data ex))))
        (is (= 0 @prompt-calls*)
            "the judge turn adapter must not be called after cancellation wins the post-reservation window")
        (is (= :cancelled (get-in @(:state* ctx)
                                  [:workflows :runs "run-judge-post-reservation-race" :status])))
        (is (= :reserved (:turn-start-state attempt))
            "the race is after successful reservation but before committed ordinary judge turn start")
        (is (nil? (:turn-started-at attempt)))))))

