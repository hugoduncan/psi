(ns psi.agent-session.workflow-delegate-failure-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-execution-test-support :as support]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-registry.registry :as workflow-registry]))

(def child-definition
  {:definition-id "child"
   :name "child"
   :steps [{:name "child-step"
            :type :session
            :contributions [{:type :template
                             :text "Do {{input}}"
                             :vars {"input" {:from :workflow-input}}}]}]})

(def grandchild-definition
  {:definition-id "grandchild"
   :name "grandchild"
   :steps [{:name "grandchild-step"
            :type :session
            :contributions [{:type :template
                             :text "Do {{input}}"
                             :vars {"input" {:from :workflow-input}}}]}]})

(def iteration-limited-child-definition
  {:definition-id "iteration-limited-child"
   :name "iteration-limited-child"
   :steps [{:name "loop"
            :type :session
            :contributions [{:type :template
                             :text "Do {{input}}"
                             :vars {"input" {:from :workflow-input}}}]
            :judge {:type :llm
                    :contributions [{:type :template
                                     :text "REPEAT or DONE?"
                                     :vars {}}]}
            :on {"REPEAT" {:goto "loop" :max-iterations 1}
                 "DONE" {:goto :done}}}]})

(def delegating-child-definition
  {:definition-id "delegating-child"
   :name "delegating-child"
   :steps [{:name "delegate-grandchild"
            :type :delegate
            :target "grandchild"
            :prompt-string "Carry out the grandchild workflow."
            :context []}]})

(def secret-delegating-child-definition
  {:definition-id "/secret"
   :name "/secret"
   :steps [{:name "delegate-grandchild"
            :type :delegate
            :target "grandchild"
            :prompt-string "Carry out the grandchild workflow."
            :context []}]})

(defn- parent-definition
  [child-name]
  {:definition-id "parent"
   :name "parent"
   :steps [{:name "delegate-child"
            :type :delegate
            :target child-name
            :prompt-string "Carry out the child workflow."
            :context []}]})

(defn- install-parent-run!
  [ctx definitions child-name]
  (swap! (:state* ctx)
         (fn [state]
           (let [state (reduce (fn [state definition]
                                 (first (workflow-registry/register-definition state definition)))
                               state
                               definitions)
                 [state _ _] (workflow-runtime/create-run
                              state
                              {:definition (parent-definition child-name)
                               :run-id "parent-run"
                               :workflow-input "parent input"})]
             state))))

(defn- failed-turn
  [session-id message]
  {:status :error
   :session-id session-id
   :assistant-message {:role "assistant"
                       :error-message message
                       :content [{:type :error :text message}]}
   :assistant-text ""
   :execution-result {}
   :failure {:reason :provider-unavailable
             :message message}})

(defn- successful-turn
  [session-id message]
  {:status :ok
   :session-id session-id
   :assistant-message {:role "assistant"
                       :content [{:type :text :text message}]}
   :assistant-text message
   :execution-result {}})

(defn- execute-failing-parent!
  ([definitions child-name failure-message]
   (execute-failing-parent! definitions child-name failure-message {}))
  ([definitions child-name failure-message {:keys [actor-turn-fn judge-fn]}]
   (let [[base-ctx session-id]
         (support/create-session-context
          (cond-> {:persist? false}
            judge-fn (assoc :execute-workflow-judge-fn judge-fn)))
         ctx (assoc base-ctx
                    :workflow-execute-actor-turn-fn
                    (or actor-turn-fn
                        (fn [_ctx child-session-id _prompt]
                          (failed-turn child-session-id failure-message))))]
     (install-parent-run! ctx definitions child-name)
     {:ctx ctx
      :result (workflow-execution/execute-run! ctx session-id "parent-run")})))

(defn- parent-error
  [ctx]
  (get-in @(:state* ctx)
          [:workflows :runs "parent-run" :step-runs "delegate-child" :attempts 0 :execution-error]))

(deftest failed-child-delegation-persists-canonical-envelope-test
  ;; A real delegated statechart execution records the lower-runtime envelope
  ;; verbatim on the parent attempt rather than a generic payload or child data.
  (testing "actionable child execution failures become parent attempt envelopes"
    (let [{:keys [ctx result]} (execute-failing-parent! [child-definition] "child" "upstream request rejected")
          parent-run (workflow-runtime/workflow-run-in @(:state* ctx) "parent-run")
          child-run-id (get-in parent-run [:step-runs "delegate-child" :attempts 0 :execution-error :delegate-failure :run-id])
          child-run (workflow-runtime/workflow-run-in @(:state* ctx) child-run-id)
          error (parent-error ctx)]
      (is (= :failed (:status result)))
      (is (= :failed (:status parent-run)))
      (is (= :failed (:status child-run)))
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow 'child' failed at step 'child-step': upstream request rejected"
              :delegate-failure {:source :execution-error
                                 :run-id child-run-id
                                 :target "child"
                                 :reason :provider-unavailable
                                 :step-id "child-step"
                                 :attempt-id (get-in child-run [:step-runs "child-step" :attempts 0 :attempt-id])}}
             error))
      (is (not (contains? error :details)))
      (is (nil? (get-in parent-run [:step-runs "delegate-child" :accepted-result]))))))

(deftest delegated-failure-fallback-persists-selected-location-test
  ;; Redaction-only child diagnostics become the generic public fallback while
  ;; retaining the selected child location on the parent attempt.
  (testing "non-actionable child errors do not expose a cause"
    (let [{:keys [ctx result]} (execute-failing-parent! [child-definition] "child" "token=secret")
          parent-run (workflow-runtime/workflow-run-in @(:state* ctx) "parent-run")
          error (parent-error ctx)
          child-run-id (get-in error [:delegate-failure :run-id])]
      (is (= :failed (:status result)))
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow failed"
              :delegate-failure {:source :fallback
                                 :run-id child-run-id
                                 :target "child"
                                 :step-id "child-step"
                                 :attempt-id (get-in @(:state* ctx)
                                                     [:workflows :runs child-run-id :step-runs "child-step"
                                                      :attempts 0 :attempt-id])}}
             error))
      (is (nil? (get-in parent-run [:step-runs "delegate-child" :accepted-result]))))))

(deftest iteration-limit-child-delegation-persists-terminal-outcome-envelope-test
  ;; A real exhausted child loop has no terminal attempt id, so normalization
  ;; chooses its ordered latest attempt and permits only terminal counts.
  (testing "iteration exhaustion reaches the parent as a terminal-outcome envelope"
    (let [judge-result {:judge-session-id "judge-loop"
                        :judge-output "REPEAT"
                        :judge-event "REPEAT"
                        :routing-result {:action :goto :target "loop"}}
          {:keys [ctx result]}
          (execute-failing-parent!
           [iteration-limited-child-definition] "iteration-limited-child" "unused failure"
           {:actor-turn-fn (fn [_ctx session-id _prompt]
                             (successful-turn session-id "loop output"))
            :judge-fn (fn [& _] judge-result)})
          error (parent-error ctx)
          child-run-id (get-in error [:delegate-failure :run-id])
          child-run (workflow-runtime/workflow-run-in @(:state* ctx) child-run-id)
          attempt-id (get-in child-run [:step-runs "loop" :attempts 0 :attempt-id])]
      (is (= :failed (:status result)))
      (is (= :failed (:status child-run)))
      (is (= {:outcome :failed
              :reason :iteration-limit-reached
              :step-id "loop"
              :iteration-count 1
              :max-iterations 1
              :last-judge-signal "REPEAT"
              :last-result-text "loop output"}
             (:terminal-outcome child-run)))
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow 'iteration-limited-child' failed at step 'loop': terminal outcome :iteration-limit-reached (iteration 1 of 1)"
              :delegate-failure {:source :terminal-outcome
                                 :run-id child-run-id
                                 :target "iteration-limited-child"
                                 :reason :iteration-limit-reached
                                 :step-id "loop"
                                 :attempt-id attempt-id}}
             error))
      (is (not (str/includes? (pr-str error) "last-result-text")))
      (is (not (str/includes? (pr-str error) "judge-loop"))))))

(deftest nested-delegated-failure-persists-one-allowlisted-cause-test
  ;; A child delegate failure is normalized once more at its direct parent;
  ;; only its immediate identity is retained rather than child-run internals.
  (testing "a parent delegation preserves one nested canonical identity"
    (let [{:keys [ctx result]}
          (execute-failing-parent! [grandchild-definition delegating-child-definition]
                                   "delegating-child"
                                   "grandchild request rejected")
          error (parent-error ctx)
          child-run-id (get-in error [:delegate-failure :run-id])
          child-error (get-in @(:state* ctx)
                              [:workflows :runs child-run-id :step-runs "delegate-grandchild"
                               :attempts 0 :execution-error])]
      (is (= :failed (:status result)))
      (is (= :execution-error (get-in error [:delegate-failure :source])))
      (is (= :delegated-workflow-failed (get-in error [:delegate-failure :reason])))
      (is (= (select-keys (get-in child-error [:delegate-failure])
                          [:run-id :target :reason :step-id :attempt-id])
             (get-in error [:delegate-failure :nested-cause])))
      (is (= "Delegated workflow 'delegating-child' failed at step 'delegate-grandchild': Delegated workflow 'grandchild' failed at step 'grandchild-step': grandchild request rejected"
             (:message error))))))

(deftest nonactionable-direct-target-forces-nested-failure-fallback-test
  ;; A real nested child error cannot make an unsafe direct target actionable;
  ;; only selected location survives in the persisted fallback envelope.
  (testing "a direct path target drops nested cause identity and message"
    (let [{:keys [ctx result]}
          (execute-failing-parent! [grandchild-definition secret-delegating-child-definition]
                                   "/secret"
                                   "grandchild request rejected")
          error (parent-error ctx)
          child-run-id (get-in error [:delegate-failure :run-id])
          child-run (workflow-runtime/workflow-run-in @(:state* ctx) child-run-id)
          attempt-id (get-in child-run [:step-runs "delegate-grandchild" :attempts 0 :attempt-id])]
      (is (= :failed (:status result)))
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow failed"
              :delegate-failure {:source :fallback
                                 :run-id child-run-id
                                 :target "/secret"
                                 :step-id "delegate-grandchild"
                                 :attempt-id attempt-id}}
             error))
      (is (not (contains? (:delegate-failure error) :reason)))
      (is (not (contains? (:delegate-failure error) :nested-cause))))))
