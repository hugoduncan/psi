(ns psi.agent-session.workflow-delegate-result-boundary-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tool-runtime-adapter :as tool-runtime-adapter]
   [psi.agent-session.workflow.bootstrap :as workflow-bootstrap]
   [psi.agent-session.workflow.runtime-state :as workflow-runtime-state]))

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

(defn- create-delegate-boundary-context
  []
  (let [[ctx session-id] (test-support/create-test-session
                          {:persist? false
                           :mutations mutations/all-mutations})]
    (workflow-bootstrap/init-built-in! ctx session-id)
    [ctx session-id]))

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
