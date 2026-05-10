(ns psi.github.find-issue-integration-test
  "Integration test: verify the github/find-issue operation executes through
   the workflow-runtime `:invoke` step without spawning an agent session.

   Tagged ^:integration — runs under the :integration Kaocha suite
   (focus-meta [:integration]), skipped by :extensions suite (skip-meta [:integration])."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.deterministic-operation-registry.registry :as op-reg]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.github.find-issue :as find-issue]))

(def ^:private discover-workflow-definition
  {:definition-id "github-find-issue-proof"
   :name          "github-find-issue-proof"
   :steps         [{:name      "discover"
                    :type      :invoke
                    :operation "github/find-issue"
                    :args      {:labels ["enhancement" "refine"]
                                :input  {:from :workflow-input :path [:input]}}
                    :outputs   {:summary {:source :invoke/summary}}
                    :yields    {:type :text :text :summary}}]})

(defn- stub-issues-json
  [issues]
  (json/generate-string issues))

(defn- stub-shell-fn
  [issues]
  (fn [& _args]
    {:exit 0
     :out  (stub-issues-json issues)
     :err  ""}))

(defn- create-session-context
  []
  (let [ctx (session/create-context (test-support/safe-context-opts {:persist? false}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(deftest ^:integration invoke-step-with-github-find-issue-completes-without-session-test
  (testing "github/find-issue `:invoke` step completes without spawning a session"
    ;; Proof that no session is spawned:
    ;; `create-session-context` calls `session/create-context` which always wires
    ;; `:workflow-execution-adapter` via `create-context*` — the adapter IS present.
    ;; The actual proof is: (a) @calls* count = 1 (handler invoked exactly once),
    ;; (b) run reaches :completed status (no session-allocation error), and
    ;; (c) the :invoke step calls invoke-step-runtime-result directly without
    ;; calling create-step-attempt-session!.
    (let [[ctx session-id] (create-session-context)
          calls*           (atom [])
          stub-issues      [{"number" 42
                             "title"  "Add dark mode"
                             "url"    "https://github.com/org/repo/issues/42"
                             "state"  "open"
                             "labels" []}]
          _                (op-reg/register-operation-in!
                            (:deterministic-operation-registry ctx)
                            {:id      "github/find-issue"
                             :handler (fn [invocation]
                                        (swap! calls* conj invocation)
                                        (find-issue/invoke
                                         (assoc invocation
                                                :ctx (assoc (:ctx invocation)
                                                            :github-shell-fn
                                                            (stub-shell-fn stub-issues)))))})
          _                (swap! (:state* ctx)
                                  (fn [state]
                                    (let [[s _ _] (workflow-runtime/create-run
                                                   state
                                                   {:definition    discover-workflow-definition
                                                    :run-id        "run-github-find-issue"
                                                    :workflow-input {:input nil}})]
                                      s)))
          result           (workflow-execution/execute-run! ctx session-id "run-github-find-issue")
          run              (workflow-runtime/workflow-run-in @(:state* ctx) "run-github-find-issue")
          accepted         (get-in run [:step-runs "discover" :accepted-result])]
      (is (= :completed (:status result)))
      ;; CC: invocation shape — proves operation was called with correctly resolved args
      (is (= 1 (count @calls*)))
      (let [invocation (first @calls*)]
        (is (= {:labels ["enhancement" "refine"] :input nil} (:args invocation)))
        (is (= "discover" (:step-id invocation))))
      ;; AA: effective-args — proves resolve-invoke-args resolved {:from :workflow-input :path [:input]} → nil
      (is (= {:labels ["enhancement" "refine"] :input nil}
             (get-in run [:step-runs "discover" :attempts 0 :effective-args])))
      (is (= :ok (get-in accepted [:outcome])))
      (is (string? (get-in accepted [:outputs :summary])))
      (is (str/includes? (get-in accepted [:outputs :summary]) "## Handoff Data"))
      (is (str/includes? (get-in accepted [:outputs :summary]) "issue_number: 42"))
      (is (str/includes? (get-in accepted [:outputs :summary]) "issue_title: Add dark mode"))
      ;; BB: issue_url included in handoff
      (is (str/includes? (get-in accepted [:outputs :summary]) "issue_url: https://github.com/org/repo/issues/42"))
      (is (str/includes? (get-in accepted [:outputs :summary]) "worktree_description: add-dark-mode")))))
