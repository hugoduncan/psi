(ns psi.agent-session.mutations.canonical-workflows-delegated-failure-test
  "Public delegated-failure projection tests for canonical workflow mutations."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.mutations.canonical-workflows :as mutations]
   [psi.agent-session.workflow-execution-test-support :as support]))

(def resumable-child-definition
  {:definition-id "resumable-child"
   :name "resumable-child"
   :steps [{:name "child-step"
            :type :session
            :outputs {:final-llm-reply {:source :session/final-llm-reply}
                      :decision {:source :session/structured-output
                                 :mode :structured
                                 :schema-id :psi.workflow/test-decision
                                 :schema-version 1
                                 :schema [:map [:decision [:enum :approve]]]
                                 :json-schema {:type "object"
                                               :required ["decision"]
                                               :properties {"decision" {:type "string"}}}}}
            :contributions [{:type :template
                             :text "Decide {{input}}"
                             :vars {"input" {:from :workflow-input}}}]}]})

(def resumable-parent-definition
  {:definition-id "resumable-parent"
   :name "resumable-parent"
   :steps [{:name "delegate-child"
            :type :delegate
            :target "resumable-child"
            :prompt-string "Carry out the child workflow."
            :context []}]})

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

(def iteration-limited-parent-definition
  {:definition-id "iteration-limited-parent"
   :name "iteration-limited-parent"
   :steps [{:name "delegate-child"
            :type :delegate
            :target "iteration-limited-child"
            :prompt-string "Carry out the child workflow."
            :context []}]})

(defn- workflow-turn
  [session-id status message]
  {:status status
   :session-id session-id
   :assistant-message {:role "assistant"
                       :error-message (when (= :error status) message)
                       :content [{:type (if (= :error status) :error :text)
                                  :text message}]}
   :assistant-text (if (= :error status) "" message)
   :execution-result {}
   :failure (when (= :error status)
              {:reason :provider-unavailable
               :message message})})

(deftest execute-workflow-run-projects-terminal-delegated-error-through-retention-test
  ;; A real parent/child statechart execution must select the persisted terminal
  ;; envelope before retention removes the canonical parent run.
  (testing "execute returns the canonical delegated message after retention removes the run"
    (let [[base-ctx parent-id] (support/create-session-context {:persist? false})
          ctx (assoc base-ctx
                     :config {:completed-workflow-run-retention-count 0}
                     :workflow-execute-actor-turn-fn
                     (fn [_ctx session-id _prompt & _]
                       (workflow-turn session-id :error "upstream request rejected")))]
      (mutations/register-workflow-definition
       {} {:psi/agent-session-ctx ctx :definition resumable-child-definition})
      (mutations/register-workflow-definition
       {} {:psi/agent-session-ctx ctx :definition resumable-parent-definition})
      (mutations/create-workflow-run
       {} {:psi/agent-session-ctx ctx
           :session-id parent-id
           :definition-id "resumable-parent"
           :workflow-input "execute proof"
           :run-id "execute-failed"})
      (let [result (mutations/execute-workflow-run
                    {} {:psi/agent-session-ctx ctx
                        :session-id parent-id
                        :run-id "execute-failed"})]
        (is (= :failed (:psi.workflow/status result)))
        (is (nil? (:psi.workflow/result result)))
        (is (= "Delegated workflow 'resumable-child' failed at step 'child-step': upstream request rejected"
               (:psi.workflow/error result)))
        (is (nil? (get-in @(:state* ctx) [:workflows :runs "execute-failed"])))))))

(deftest resume-workflow-run-projects-terminal-delegated-error-through-retention-test
  ;; The real facade/runtime first blocks the parent through a blocked delegated
  ;; child, then resumes the delegate step and records a terminal child failure.
  (testing "resume selects the new terminal attempt before retention and exposes no result"
    (let [[base-ctx parent-id] (support/create-session-context {:persist? false})
          turn-count* (atom 0)
          ctx (assoc base-ctx
                     :config {:completed-workflow-run-retention-count 0}
                     :workflow-execute-actor-turn-fn
                     (fn [_ctx session-id _prompt & _]
                       (if (= 1 (swap! turn-count* inc))
                         (workflow-turn session-id :ok "not valid structured output")
                         (workflow-turn session-id :error "upstream request rejected"))))]
      (mutations/register-workflow-definition
       {} {:psi/agent-session-ctx ctx :definition resumable-child-definition})
      (mutations/register-workflow-definition
       {} {:psi/agent-session-ctx ctx :definition resumable-parent-definition})
      (mutations/create-workflow-run
       {} {:psi/agent-session-ctx ctx
           :session-id parent-id
           :definition-id "resumable-parent"
           :workflow-input "resume proof"
           :run-id "resume-failed"})
      (let [blocked (mutations/execute-workflow-run
                     {} {:psi/agent-session-ctx ctx
                         :session-id parent-id
                         :run-id "resume-failed"})]
        (is (= :blocked (:psi.workflow/status blocked))))
      (swap! (:state* ctx)
             assoc-in
             [:workflows :runs "resume-failed" :step-runs "delegate-child"
              :attempts 0 :execution-error]
             {:reason :delegated-workflow-failed
              :message "superseded pre-resume failure"
              :delegate-failure {:source :fallback
                                 :run-id "stale-child"
                                 :target "resumable-child"}})
      (let [result (mutations/resume-workflow-run
                    {} {:psi/agent-session-ctx ctx
                        :session-id parent-id
                        :run-id "resume-failed"})]
        (is (= :failed (:psi.workflow/status result)))
        (is (= "Delegated workflow 'resumable-child' failed at step 'child-step': upstream request rejected"
               (:psi.workflow/error result)))
        (is (not (contains? result :psi.workflow/result)))
        (is (nil? (get-in @(:state* ctx) [:workflows :runs "resume-failed"])))))))

(deftest execute-workflow-run-projects-terminal-outcome-through-retention-test
  ;; A real iteration-exhausted child must be normalized, persisted, selected by
  ;; the facade, and projected after retention removes the parent run.
  (testing "execute carries a terminal-outcome delegated failure through retention"
    (let [judge-fn (fn [& _]
                     {:judge-session-id "judge-loop"
                      :judge-output "REPEAT"
                      :judge-event "REPEAT"
                      :routing-result {:action :goto :target "loop"}})
          [base-ctx parent-id] (support/create-session-context
                                {:persist? false
                                 :execute-workflow-judge-fn judge-fn})
          ctx (assoc base-ctx
                     :config {:completed-workflow-run-retention-count 0}
                     :workflow-execute-actor-turn-fn
                     (fn [_ctx session-id _prompt & _]
                       (workflow-turn session-id :ok "loop output")))]
      (mutations/register-workflow-definition
       {} {:psi/agent-session-ctx ctx :definition iteration-limited-child-definition})
      (mutations/register-workflow-definition
       {} {:psi/agent-session-ctx ctx :definition iteration-limited-parent-definition})
      (mutations/create-workflow-run
       {} {:psi/agent-session-ctx ctx
           :session-id parent-id
           :definition-id "iteration-limited-parent"
           :workflow-input "terminal outcome proof"
           :run-id "terminal-outcome-failed"})
      (let [result (mutations/execute-workflow-run
                    {} {:psi/agent-session-ctx ctx
                        :session-id parent-id
                        :run-id "terminal-outcome-failed"})]
        (is (= :failed (:psi.workflow/status result)))
        (is (nil? (:psi.workflow/result result)))
        (is (= "Delegated workflow 'iteration-limited-child' failed at step 'loop': terminal outcome :iteration-limit-reached (iteration 1 of 1)"
               (:psi.workflow/error result)))
        (is (nil? (get-in @(:state* ctx)
                          [:workflows :runs "terminal-outcome-failed"])))))))

(deftest execute-workflow-run-projects-fallback-through-retention-test
  ;; A real redact-only child failure must become the exact generic envelope and
  ;; survive facade selection plus retention-zero cleanup without fabrication.
  (testing "execute carries a fallback delegated failure through retention"
    (let [[base-ctx parent-id] (support/create-session-context {:persist? false})
          ctx (assoc base-ctx
                     :config {:completed-workflow-run-retention-count 0}
                     :workflow-execute-actor-turn-fn
                     (fn [_ctx session-id _prompt & _]
                       (workflow-turn session-id :error "token=secret")))]
      (mutations/register-workflow-definition
       {} {:psi/agent-session-ctx ctx :definition resumable-child-definition})
      (mutations/register-workflow-definition
       {} {:psi/agent-session-ctx ctx :definition resumable-parent-definition})
      (mutations/create-workflow-run
       {} {:psi/agent-session-ctx ctx
           :session-id parent-id
           :definition-id "resumable-parent"
           :workflow-input "fallback proof"
           :run-id "fallback-failed"})
      (let [result (mutations/execute-workflow-run
                    {} {:psi/agent-session-ctx ctx
                        :session-id parent-id
                        :run-id "fallback-failed"})]
        (is (= :failed (:psi.workflow/status result)))
        (is (nil? (:psi.workflow/result result)))
        (is (= "Delegated workflow failed" (:psi.workflow/error result)))
        (is (nil? (get-in @(:state* ctx)
                          [:workflows :runs "fallback-failed"])))))))
