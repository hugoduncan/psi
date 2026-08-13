(ns psi.agent-session.mutations.canonical-workflows-delegated-failure-test
  "Public delegated-failure projection tests for canonical workflow mutations."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations.canonical-workflows :as mutations]
   [psi.agent-session.mutations.canonical-workflows-test :as core-test]))

(def delegated-envelope
  {:reason :delegated-workflow-failed
   :message "Delegated workflow 'child' failed at step 'build': tool timed out"
   :delegate-failure {:source :execution-error
                      :run-id "child-run"
                      :target "child"
                      :step-id "build"
                      :attempt-id "build-2"}})

(defn- failed-execution-result
  [steps-executed]
  {:status :failed
   :terminal? true
   :blocked? false
   :steps-executed steps-executed
   :terminal-execution-error delegated-envelope})

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

(deftest resume-workflow-run-projects-terminal-delegated-error-through-retention-test
  ;; Resume has the same private handoff, but its established response contract
  ;; deliberately does not expose a result field.
  (testing "resume ignores pre-resume attempt history and retains no result field"
    (let [ctx (assoc (core-test/make-test-ctx)
                     :config {:completed-workflow-run-retention-count 0}
                     :resume-and-execute-workflow-run-fn
                     (fn [ctx* _session-id run-id]
                       (terminalize-failed-run! ctx* run-id)
                       (failed-execution-result
                        [{:step-id "prepare" :error "pre-resume failure"}
                         {:step-id "build" :error "superseded retry failure"}
                         {:step-id "build" :error (:message delegated-envelope)}])))
          parent-id (create-parent-run! ctx "resume-failed")
          _ (swap! (:state* ctx) assoc-in [:workflows :runs "resume-failed" :status] :blocked)
          result (mutations/resume-workflow-run
                  {} {:psi/agent-session-ctx ctx
                      :session-id parent-id
                      :run-id "resume-failed"})]
      (is (= :failed (:psi.workflow/status result)))
      (is (= (:message delegated-envelope) (:psi.workflow/error result)))
      (is (not (contains? result :psi.workflow/result)))
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "resume-failed"]))))))
