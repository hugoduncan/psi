(ns psi.agent-session.mutations.canonical-workflows-delegated-failure-test
  "Public delegated-failure projection tests for canonical workflow mutations."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations.canonical-workflows :as mutations]
   [psi.agent-session.mutations.canonical-workflows-test :as core-test]
   [psi.agent-session.workflow-execution-test-support :as support]))

(def delegated-envelope
  {:reason :delegated-workflow-failed
   :message "Delegated workflow 'child' failed at step 'build': tool timed out"
   :delegate-failure {:source :execution-error
                      :run-id "child-run"
                      :target "child"
                      :step-id "build"
                      :attempt-id "build-2"}})

(defn- failed-execution-result
  [envelope steps-executed]
  {:status :failed
   :terminal? true
   :blocked? false
   :steps-executed steps-executed
   :terminal-execution-error envelope})

(defn- create-parent-run!
  [ctx run-id]
  (let [parent-id (:session-id (session/new-session-in! ctx nil {}))]
    (mutations/register-workflow-definition
     {} {:psi/agent-session-ctx ctx :definition core-test/sample-definition})
    (mutations/create-workflow-run
     {} {:psi/agent-session-ctx ctx
         :session-id parent-id
         :definition-id "test-workflow"
         :workflow-input {:input "delegate"}
         :run-id run-id})
    parent-id))

(defn- terminalize-failed-run!
  [ctx run-id]
  (swap! (:state* ctx)
         (fn [state]
           (-> state
               (assoc-in [:workflows :runs run-id :status] :failed)
               (assoc-in [:workflows :runs run-id :finished-at]
                         (java.time.Instant/parse "2026-08-09T12:00:00Z"))))))

(deftest execute-workflow-run-projects-terminal-delegated-error-through-retention-test
  ;; The terminal facade handoff, rather than the first public attempt error or
  ;; a retained run read, is authoritative after immediate cleanup.
  (testing "execute returns the canonical delegated message after retention removes the run"
    (let [ctx (assoc (core-test/make-test-ctx)
                     :config {:completed-workflow-run-retention-count 0}
                     :execute-workflow-run-fn
                     (fn [ctx* _session-id run-id]
                       (terminalize-failed-run! ctx* run-id)
                       (failed-execution-result
                        delegated-envelope
                        [{:step-id "build" :error "superseded failure"}
                         {:step-id "build" :error (:message delegated-envelope)}])))
          parent-id (create-parent-run! ctx "execute-failed")
          result (mutations/execute-workflow-run
                  {} {:psi/agent-session-ctx ctx
                      :session-id parent-id
                      :run-id "execute-failed"})]
      (is (= :failed (:psi.workflow/status result)))
      (is (nil? (:psi.workflow/result result)))
      (is (= (:message delegated-envelope) (:psi.workflow/error result)))
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "execute-failed"]))))))

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

(deftest execute-workflow-run-projects-every-canonical-delegated-source-test
  ;; The mutation owns only pass-through projection: all source selection and
  ;; message normalization have already happened in the terminal envelope.
  (testing "execution-error, terminal-outcome, and fallback envelopes survive retention unchanged"
    (doseq [[source message]
            [[:execution-error "Delegated workflow 'child' failed at step 'build': tool timed out"]
             [:terminal-outcome "Delegated workflow 'child' failed at step 'loop': terminal outcome :iteration-limit-reached (iteration 4 of 4)"]
             [:fallback "Delegated workflow failed"]]]
      (let [envelope {:reason :delegated-workflow-failed
                      :message message
                      :delegate-failure {:source source
                                         :run-id "child-run"
                                         :target "child"}}
            ctx (assoc (core-test/make-test-ctx)
                       :config {:completed-workflow-run-retention-count 0}
                       :execute-workflow-run-fn
                       (fn [ctx* _session-id run-id]
                         (terminalize-failed-run! ctx* run-id)
                         (failed-execution-result envelope
                                                  [{:step-id "delegate"
                                                    :error "lossy public projection"}])))
            parent-id (create-parent-run! ctx (str (name source) "-failed"))
            result (mutations/execute-workflow-run
                    {} {:psi/agent-session-ctx ctx
                        :session-id parent-id
                        :run-id (str (name source) "-failed")})]
        (is (= :failed (:psi.workflow/status result)))
        (is (nil? (:psi.workflow/result result)))
        (is (= message (:psi.workflow/error result)))
        (is (nil? (get-in @(:state* ctx)
                          [:workflows :runs (str (name source) "-failed")])))))))
