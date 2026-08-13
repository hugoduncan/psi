(ns psi.agent-session.workflow-delegate-result-boundary-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tool-runtime-adapter :as tool-runtime-adapter]
   [psi.agent-session.workflow.bootstrap :as workflow-bootstrap]
   [psi.agent-session.workflow.core :as workflow-core]
   [psi.agent-session.workflow.runtime-state :as workflow-runtime-state]
   [psi.workflow-registry.registry :as workflow-registry]))

(defn- clean-workflow-runtime-state
  [f]
  (reset! workflow-runtime-state/state nil)
  (reset! workflow-runtime-state/inflight-runs {})
  (reset! workflow-runtime-state/built-in-lifecycle-callbacks {})
  (try
    (f)
    (finally
      (reset! workflow-runtime-state/state nil)
      (reset! workflow-runtime-state/inflight-runs {})
      (reset! workflow-runtime-state/built-in-lifecycle-callbacks {}))))

(use-fixtures :each clean-workflow-runtime-state)

(def child-definition
  {:definition-id "child"
   :name "child"
   :steps [{:name "child-step"
            :type :session
            :contributions [{:type :template
                             :text "Do {{input}}"
                             :vars {"input" {:from :workflow-input}}}]}]})

(def parent-definition
  {:definition-id "parent"
   :name "parent"
   :steps [{:name "delegate-child"
            :type :delegate
            :target "child"
            :prompt-string "Carry out the child workflow."
            :context []}]})

(defn- failing-actor-turn
  [session-id]
  {:status :error
   :session-id session-id
   :assistant-message {:role "assistant"
                       :error-message "upstream request rejected"
                       :content [{:type :error :text "upstream request rejected"}]}
   :assistant-text ""
   :execution-result {}
   :failure {:reason :provider-unavailable
             :message "upstream request rejected"}})

(defn- create-delegate-boundary-context
  ([]
   (create-delegate-boundary-context {}))
  ([opts]
   (let [[base-ctx session-id] (test-support/create-test-session
                                {:persist? false
                                 :mutations mutations/all-mutations})
         ctx (merge base-ctx opts)
         workflow-ctx (assoc ctx :workflow-execute-actor-turn-fn
                             (:workflow-execute-actor-turn-fn opts))]
     (workflow-bootstrap/init-built-in! workflow-ctx session-id)
     [workflow-ctx session-id])))

(defn- register-workflow-definitions!
  [ctx definitions]
  (swap! (:state* ctx)
         (fn [state]
           (reduce (fn [next-state definition]
                     (first (workflow-registry/register-definition next-state definition)))
                   state
                   definitions)))
  (workflow-runtime-state/assoc-state!
   :loaded-definitions
   (into {} (map (juxt :name identity) definitions))))

(defn- run-delegate-tool-call!
  [ctx session-id arguments]
  (#'tool-runtime-adapter/run-tool-call!
   ctx
   session-id
   {:id (str "delegate-call-" (java.util.UUID/randomUUID))
    :name "delegate"
    :arguments (json/generate-string arguments)}
   nil))

(defn- visible-result-text
  [result-message]
  (:result-text result-message))

(deftest delegate-run-unknown-workflow-empty-registry-is-visible-at-tool-boundary-test
  ;; A registered delegate tool returning a semantic unknown-workflow string must
  ;; survive runtime content normalization into the provider-facing toolResult.
  (testing "unknown workflow with no workflow definitions is transport-successful but semantically visible"
    (let [[ctx session-id] (create-delegate-boundary-context)
          result (run-delegate-tool-call!
                  ctx
                  session-id
                  {:action "run" :workflow "agent" :prompt "hello" :mode "sync"})
          text (visible-result-text result)]
      (is (false? (:is-error result))
          "the tool invocation transport remains successful")
      (is (= "Error: Unknown workflow 'agent'. Use action=list to see available workflows."
             text)
          "the semantic delegate failure is visible to the caller"))))

(deftest delegate-run-unknown-workflow-non-empty-registry-is-visible-at-tool-boundary-test
  ;; Unknown-workflow surfacing must not depend on the registry being empty.
  (testing "unknown workflow with other definitions loaded is visible"
    (let [[ctx session-id] (create-delegate-boundary-context)
          _ (workflow-runtime-state/assoc-state!
             :loaded-definitions
             {"planner" {:definition-id "planner"
                         :summary "Plan work"
                         :step-order ["step-1"]
                         :steps {"step-1" {:label "Plan"}}}})
          result (run-delegate-tool-call!
                  ctx
                  session-id
                  {:action "run" :workflow "agent" :prompt "hello"})
          text (visible-result-text result)]
      (is (false? (:is-error result))
          "the tool invocation transport remains successful")
      (is (= "Error: Unknown workflow 'agent'. Use action=list to see available workflows."
             text)))))

(deftest delegate-run-failed-child-workflow-is-visible-at-tool-boundary-test
  ;; Exercises the registered synchronous delegate tool and the real parent/child
  ;; statechart path rather than an adapter-specific failure substitute.
  (testing "canonical delegated failure is rendered once as provider-facing error text"
    (let [[ctx session-id]
          (create-delegate-boundary-context
           {:workflow-execute-actor-turn-fn
            (fn [_ctx child-session-id _prompt]
              (failing-actor-turn child-session-id))})
          _ (register-workflow-definitions! ctx [child-definition parent-definition])
          result (run-delegate-tool-call!
                  ctx
                  session-id
                  {:action "run" :workflow "parent" :prompt "hello" :mode "sync"})]
      (is (false? (:is-error result))
          "the provider transport remains successful for a semantic workflow failure")
      (is (= "Error: Delegated workflow 'child' failed at step 'child-step': upstream request rejected"
             (visible-result-text result))))))

(deftest delegate-list-empty-registry-is-visible-at-tool-boundary-test
  ;; Empty delegate list output is meaningful content, not absence of content.
  (testing "no workflows and no active runs render an explicit empty list"
    (let [[ctx session-id] (create-delegate-boundary-context)
          result (run-delegate-tool-call! ctx session-id {:action "list"})
          text (visible-result-text result)]
      (is (false? (:is-error result))
          "the tool invocation transport remains successful")
      (is (= (str "Available workflows:\n"
                  "No workflows loaded.\n\n"
                  "Active runs:\n"
                  "No active runs.")
             text)))))

(deftest delegate-continue-string-keyed-arguments-reach-continue-behavior-test
  ;; Registered JSON tool calls decode to string-keyed argument maps. Continue
  ;; must read those keys rather than reporting false missing id/prompt errors.
  (testing "string-keyed id and prompt continue a blocked run at the tool boundary"
    (let [[ctx session-id] (create-delegate-boundary-context)
          resumed* (atom [])]
      (with-redefs [workflow-core/mutate!
                    (fn [op args]
                      (case op
                        psi.workflow/list-runs
                        {:psi.workflow/runs [{:run-id "run-continue"
                                              :source-definition-id "planner"
                                              :status :blocked}]}

                        psi.extension/start-background-job
                        {:psi.background-job/job-id (:job-id args)
                         :psi.background-job/status :running}

                        psi.workflow/resume-run
                        (do
                          (swap! resumed* conj args)
                          {:psi.workflow/status :completed
                           :psi.workflow/result "done"})

                        psi.extension/mark-background-job-terminal
                        {:psi.background-job/job-id (:job-id args)
                         :psi.background-job/status (:outcome args)}

                        psi.extension/append-entry
                        {:ok true}))]
        (let [result (run-delegate-tool-call!
                      ctx
                      session-id
                      {"action" "continue"
                       "id" "run-continue"
                       "prompt" "next"})]
          (is (false? (:is-error result))
              "the tool invocation transport remains successful")
          (is (= "Resuming run run-continue asynchronously."
                 (visible-result-text result)))
          (is (= [{:run-id "run-continue"
                   :session-id session-id
                   :workflow-input {:input "next" :original "next"}}]
                 @resumed*)))))))

(deftest delegate-remove-string-keyed-id-reaches-remove-behavior-test
  ;; Registered JSON tool calls decode to string-keyed argument maps. Remove
  ;; must read the supplied id and remove that canonical run rather than
  ;; returning a false missing-id error.
  (testing "string-keyed id removes the intended run at the tool boundary"
    (let [[ctx session-id] (create-delegate-boundary-context)
          removed* (atom [])]
      (with-redefs [workflow-core/mutate!
                    (fn [op args]
                      (case op
                        psi.workflow/remove-run
                        (do
                          (swap! removed* conj args)
                          {:psi.workflow/removed? true
                           :psi.workflow/run-id (:run-id args)})))]
        (let [result (run-delegate-tool-call!
                      ctx
                      session-id
                      {"action" "remove"
                       "id" "run-remove"})]
          (is (false? (:is-error result))
              "the tool invocation transport remains successful")
          (is (= "Removed run run-remove" (visible-result-text result)))
          (is (= [{:run-id "run-remove" :session-id session-id}] @removed*)))))))
