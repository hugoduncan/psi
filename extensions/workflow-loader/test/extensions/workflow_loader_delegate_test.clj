(ns extensions.workflow-loader-delegate-test
  "Tests for the delegate tool async/sync mode, fork_session, and include_result_in_context."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.workflow-loader :as wl]
   [extensions.workflow-loader.orchestration :as orchestration]
   [psi.agent-session.workflow-file-loader :as workflow-file-loader])
  (:import
   [java.util.concurrent Future]))

;;; Test infrastructure — mock extension API

(def ^:private test-state (atom nil))

(defn- make-mock-api
  "Create a mock extension API that captures tool/command registrations
   and provides controllable mutate/query functions."
  [{:keys [mutate-results query-result query-session-result]}]
  (let [tools (atom {})
        commands (atom {})
        logs (atom [])
        notifications (atom [])
        prompt-contributions (atom {})
        mutate-calls (atom [])
        mutate-session-calls (atom [])
        query-session-calls (atom [])
        mutate-results* (atom (or mutate-results {}))]
    (reset! test-state
            {:tools tools
             :commands commands
             :logs logs
             :notifications notifications
             :prompt-contributions prompt-contributions
             :mutate-calls mutate-calls
             :mutate-session-calls mutate-session-calls
             :query-session-calls query-session-calls
             :mutate-results* mutate-results*})
    {:query (fn [_query]
              (or (:query-result @test-state)
                  query-result
                  {:psi.agent-session/worktree-path "/tmp/test-worktree"
                   :psi.agent-session/session-id "test-session-1"
                   :psi.agent-session/session-entries []}))
     :query-session (fn [session-id query]
                      (swap! query-session-calls conj {:session-id session-id :query query})
                      (if (fn? query-session-result)
                        (query-session-result session-id query)
                        (or query-session-result
                            {:psi.agent-session/session-entries []})))
     :mutate (fn [sym params]
               (swap! mutate-calls conj {:sym sym :params params})
               (let [results @mutate-results*
                     result (get results sym)]
                 (if (fn? result)
                   (result params)
                   (or result {}))))
     :mutate-session (fn [session-id sym params]
                       (swap! mutate-session-calls conj {:session-id session-id :sym sym :params params})
                       (let [results @mutate-results*
                             result (get results sym)]
                         (if (fn? result)
                           (result (assoc (or params {}) :session-id session-id))
                           (or result {}))))
     :log (fn [msg] (swap! logs conj msg))
     :notify (fn [msg arg]
               (swap! notifications conj
                      (if (map? arg)
                        {:msg msg :arg arg :level (:level arg)}
                        {:msg msg :level arg})))
     :register-tool (fn [tool-def]
                      (swap! tools assoc (:name tool-def) tool-def))
     :register-command (fn [name cmd-def]
                         (swap! commands assoc name cmd-def))
     :register-prompt-contribution (fn [{:keys [id] :as pc}]
                                     (swap! prompt-contributions assoc id pc))
     :on (fn [_event-name _handler] nil)}))

(defn- execute-tool [args]
  (let [tool-def (get @(:tools @test-state) "delegate")]
    (when tool-def
      ((:execute tool-def) args))))

(defn- get-mutate-session-calls []
  @(:mutate-session-calls @test-state))

(defn- get-query-session-calls []
  @(:query-session-calls @test-state))

;;; Fixtures

(defn reset-extension-state [f]
  ;; Reset the module-level atoms between tests to avoid leakage
  (reset! @#'wl/inflight-runs {})
  (reset! @#'wl/state nil)
  (f)
  ;; Clean up any lingering futures
  (doseq [[_ {:keys [future]}] @(deref #'wl/inflight-runs)]
    (when (instance? Future future)
      (future-cancel future)))
  (reset! @#'wl/inflight-runs {}))

(use-fixtures :each reset-extension-state)

;;; Tests

(deftest delegate-run-async-default-test
  (testing "action=run defaults to async mode and returns immediately"
    (let [run-created (atom false)
          execute-called (atom false)
          api (make-mock-api
               {:definitions {}
                :mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/definition-id "planner"
                          :psi.workflow/registered? true})
                 'psi.workflow/create-run
                 (fn [params]
                   (reset! run-created true)
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/status :pending})
                 'psi.extension/start-background-job
                 (fn [params]
                   {:psi.background-job/job-id (:job-id params)
                    :psi.background-job/status :running})
                 'psi.extension/mark-background-job-terminal
                 (fn [_]
                   {:psi.background-job/job-id "job-1"
                    :psi.background-job/status :completed})
                 'psi.workflow/execute-run
                 (fn [_]
                   (reset! execute-called true)
                   ;; Simulate some work
                   (Thread/sleep 50)
                   {:psi.workflow/run-id "planner-123"
                    :psi.workflow/status :completed
                    :psi.workflow/result "plan output"})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})}})]
      ;; Stub the loader to return a known definition
      (with-redefs [workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans tasks"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (let [result (execute-tool {:action "run"
                                    :workflow "planner"
                                    :prompt "plan something"})]
          (is (string? result))
          (is (.contains ^String result "started asynchronously"))
          (is (true? @run-created))
          ;; Wait for async execution to complete
          (Thread/sleep 200)
          (is (true? @execute-called)))))))

(deftest delegate-run-sync-test
  (testing "action=run with mode=sync blocks until completion and returns result"
    (let [api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/create-run
                 (fn [params]
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/status :pending})
                 'psi.extension/start-background-job
                 (fn [params]
                   {:psi.background-job/job-id (:job-id params)
                    :psi.background-job/status :running})
                 'psi.extension/mark-background-job-terminal
                 (fn [_]
                   {:psi.background-job/job-id "job-sync"
                    :psi.background-job/status :completed})
                 'psi.workflow/execute-run
                 (fn [_]
                   (Thread/sleep 50)
                   {:psi.workflow/run-id "planner-sync"
                    :psi.workflow/status :completed
                    :psi.workflow/result "sync plan output"})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})}})]
      (with-redefs [workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans tasks"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (let [result (execute-tool {:action "run"
                                    :workflow "planner"
                                    :prompt "plan something"
                                    :mode "sync"})]
          (is (string? result)))))))

(deftest delegate-run-sync-timeout-test
  (testing "sync mode returns timeout error when execution exceeds timeout_ms"
    (let [api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/create-run
                 (fn [params]
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/status :pending})
                 'psi.extension/start-background-job
                 (fn [params]
                   {:psi.background-job/job-id (:job-id params)
                    :psi.background-job/status :running})
                 'psi.extension/mark-background-job-terminal
                 (fn [_]
                   {:psi.background-job/job-id "job-slow"
                    :psi.background-job/status :completed})
                 'psi.workflow/execute-run
                 (fn [_]
                   ;; Simulate slow execution
                   (Thread/sleep 5000)
                   {:psi.workflow/status :completed})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})}})]
      (with-redefs [workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"slow" {:definition-id "slow"
                                             :name "slow"
                                             :summary "Slow workflow"
                                             :step-order ["step-1"]
                                             :steps {"step-1" {:label "slow"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (let [result (execute-tool {:action "run"
                                    :workflow "slow"
                                    :prompt "do something"
                                    :mode "sync"
                                    :timeout_ms 100})]
          (is (string? result))
          (is (.contains ^String result "Error"))
          (is (.contains ^String result "Timed out")))))))

(deftest delegate-run-include-result-test
  (testing "include_result_in_context injects messages after async completion into the originating session"
    (let [appended-messages (atom [])
          api (make-mock-api
               {:query-result {:psi.agent-session/worktree-path "/tmp/test-worktree"
                               :psi.agent-session/session-id "origin-session"
                               :psi.agent-session/session-entries []}
                :query-session-result (fn [session-id _query]
                                        {:psi.agent-session/session-entries
                                         (if (= session-id "origin-session")
                                           [{:psi.session-entry/data {:role "assistant"}}]
                                           [{:psi.session-entry/data {:role "user"}}])})
                :mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/create-run
                 (fn [params]
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/status :pending})
                 'psi.extension/start-background-job
                 (fn [params]
                   {:psi.background-job/job-id (:job-id params)
                    :psi.background-job/status :running})
                 'psi.extension/mark-background-job-terminal
                 (fn [_]
                   {:psi.background-job/job-id "job-include"
                    :psi.background-job/status :completed})
                 'psi.workflow/execute-run
                 (fn [_]
                   {:psi.workflow/status :completed
                    :psi.workflow/result "injected output"})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})
                 'psi.extension/append-message
                 (fn [params]
                   (swap! appended-messages conj params)
                   {})
                 'psi.extension/append-entry
                 (fn [_] {})}})]
      (with-redefs [workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (let [result (execute-tool {:action "run"
                                    :workflow "planner"
                                    :prompt "plan it"
                                    :include_result_in_context true})]
          (is (.contains ^String result "started asynchronously"))
          ;; Wait for async completion
          (Thread/sleep 200)
          ;; Should have injected into the originating session explicitly
          (let [session-calls (get-mutate-session-calls)
                roles (mapv #(get-in % [:params :role]) session-calls)]
            (is (>= (count session-calls) 2) "should inject at least user + assistant")
            (is (every? #(= "origin-session" (:session-id %)) session-calls))
            (is (some #(= "user" %) roles))
            (is (some #(= "assistant" %) roles))
            (is (some #(= "injected output" (get-in % [:params :content])) session-calls)))
          (is (seq (get-query-session-calls))))))))

(deftest delegate-run-fork-session-test
  (testing "fork_session passes through in workflow-input"
    (let [create-params (atom nil)
          api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/create-run
                 (fn [params]
                   (reset! create-params params)
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/status :pending})
                 'psi.extension/start-background-job
                 (fn [params]
                   {:psi.background-job/job-id (:job-id params)
                    :psi.background-job/status :running})
                 'psi.extension/mark-background-job-terminal
                 (fn [_]
                   {:psi.background-job/job-id "job-fork"
                    :psi.background-job/status :completed})
                 'psi.workflow/execute-run
                 (fn [_]
                   {:psi.workflow/status :completed})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})}})]
      (with-redefs [workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (execute-tool {:action "run"
                       :workflow "planner"
                       :prompt "plan it"
                       :fork_session true})
        ;; Wait for async
        (Thread/sleep 100)
        (is (some? @create-params))
        (is (true? (get-in @create-params [:workflow-input :fork-session])))))))

(deftest delegate-run-unknown-workflow-test
  (testing "run with unknown workflow returns error"
    (let [api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})}})]
      (with-redefs [workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (let [result (execute-tool {:action "run"
                                    :workflow "nonexistent"
                                    :prompt "do something"})]
          (is (.contains ^String result "Error"))
          (is (.contains ^String result "Unknown workflow")))))))

(deftest delegate-remove-test
  (testing "remove deletes the run rather than cancelling it"
    (let [remove-params (atom nil)
          api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/remove-run
                 (fn [params]
                   (reset! remove-params params)
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/removed? true})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})}})]
      (with-redefs [workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (let [result (execute-tool {:action "remove"
                                    :id "run-1"})]
          (is (.contains ^String result "Removed run run-1"))
          (is (= {:run-id "run-1"} @remove-params)))))))

(deftest delegate-remove-hides-terminal-background-job-projection-test
  (testing "remove/list does not keep removed runs visible from terminal delegate background-job history"
    (let [removed? (atom false)
          api (make-mock-api
               {:query-result {:psi.agent-session/worktree-path "/tmp/test-worktree"
                               :psi.agent-session/session-id "test-session-1"
                               :psi.agent-session/background-jobs
                               [{:psi.background-job/tool-name "delegate"
                                 :psi.background-job/workflow-id "run-1"
                                 :psi.background-job/status :completed}]}
                :mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/remove-run
                 (fn [_]
                   (reset! removed? true)
                   {:psi.workflow/run-id "run-1"
                    :psi.workflow/removed? true})
                 'psi.workflow/list-runs
                 (fn [_]
                   {:psi.workflow/runs (if @removed?
                                         []
                                         [{:run-id "run-1"
                                           :status :completed
                                           :source-definition-id "planner"}])})}})]
      (with-redefs [workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (is (.contains ^String (execute-tool {:action "list"}) "run-1 — completed (planner)"))
        (is (.contains ^String (execute-tool {:action "remove" :id "run-1"}) "Removed run run-1"))
        (is (.contains ^String (execute-tool {:action "list"}) "No active runs."))))))

(deftest delegate-run-missing-params-test
  (testing "run without workflow or prompt returns appropriate errors"
    (let [api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})}})]
      (with-redefs [workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (is (.contains ^String (execute-tool {:action "run" :prompt "x"})
                       "workflow is required"))
        (is (.contains ^String (execute-tool {:action "run" :workflow "planner"})
                       "prompt is required"))))))

(deftest delegate-list-shows-async-tag-test
  (testing "list action shows [async] tag for tracked runs"
    (let [created-run-id (atom nil)
          api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/create-run
                 (fn [params]
                   (reset! created-run-id (:run-id params))
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/status :pending})
                 'psi.extension/start-background-job
                 (fn [params]
                   {:psi.background-job/job-id (:job-id params)
                    :psi.background-job/status :running})
                 'psi.extension/mark-background-job-terminal
                 (fn [_]
                   {:psi.background-job/job-id "job-list"
                    :psi.background-job/status :completed})
                 'psi.workflow/execute-run
                 (fn [_]
                   (Thread/sleep 5000)
                   {:psi.workflow/status :completed})
                 'psi.workflow/list-runs
                 (fn [_]
                   ;; Return the actual run-id that was created
                   {:psi.workflow/runs (if @created-run-id
                                         [{:run-id @created-run-id
                                           :status :running
                                           :source-definition-id "planner"}]
                                         [])})}})]
      (with-redefs [workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        ;; Start an async run with explicit name to populate canonical background-job state
        (execute-tool {:action "run"
                       :workflow "planner"
                       :prompt "plan it"
                       :name "my-plan-run"})
        ;; Simulate canonical background-job visibility for the list query
        (swap! test-state assoc :query-result {:psi.agent-session/worktree-path "/tmp/test-worktree"
                                               :psi.agent-session/session-id "test-session-1"
                                               :psi.agent-session/background-jobs
                                               [{:psi.background-job/tool-name "delegate"
                                                 :psi.background-job/workflow-id "my-plan-run"
                                                 :psi.background-job/status :running}]})
        ;; Now list — should show [async] tag
        (let [list-result (execute-tool {:action "list"})]
          (is (string? list-result))
          (is (.contains ^String list-result "planner"))
          (is (.contains ^String list-result "[async]")))))))

(deftest widget-refresh-on-async-run-test
  (testing "widgets are refreshed when async runs start and complete"
    (let [widget-calls (atom [])
          clear-calls (atom [])
          api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/create-run
                 (fn [params]
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/status :pending})
                 'psi.extension/start-background-job
                 (fn [params]
                   {:psi.background-job/job-id (:job-id params)
                    :psi.background-job/status :running})
                 'psi.extension/mark-background-job-terminal
                 (fn [_]
                   {:psi.background-job/job-id "job-widget"
                    :psi.background-job/status :completed})
                 'psi.workflow/execute-run
                 (fn [_]
                   (Thread/sleep 50)
                   {:psi.workflow/status :completed
                    :psi.workflow/result "done"})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})
                 'psi.extension/append-entry
                 (fn [_] {})}})]
      ;; Inject mock UI
      (swap! @#'wl/state assoc :ui
             {:set-widget (fn [wid placement lines]
                            (swap! widget-calls conj {:wid wid :placement placement :lines lines}))
              :clear-widget (fn [wid]
                              (swap! clear-calls conj wid))})
      (with-redefs [workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        ;; Inject UI again after init (init resets state)
        (swap! @#'wl/state assoc :ui
               {:set-widget (fn [wid placement lines]
                              (swap! widget-calls conj {:wid wid :placement placement :lines lines}))
                :clear-widget (fn [wid]
                                (swap! clear-calls conj wid))})
        (swap! test-state assoc :query-result {:psi.agent-session/worktree-path "/tmp/test-worktree"
                                               :psi.agent-session/session-id "test-session-1"
                                               :psi.agent-session/background-jobs
                                               [{:psi.background-job/tool-name "delegate"
                                                 :psi.background-job/workflow-id "widget-test-run"
                                                 :psi.background-job/status :running
                                                 :psi.background-job/started-at (java.time.Instant/now)}]})
        (execute-tool {:action "run"
                       :workflow "planner"
                       :prompt "plan it"
                       :name "widget-test-run"})
        ;; Widget should have been set for the new run
        (Thread/sleep 100)
        (is (pos? (count @widget-calls)) "should have set at least one widget")
        (is (some #(= "delegate-widget-test-run" (:wid %)) @widget-calls)
            "widget id should be delegate-<run-id>")
        ;; Wait for completion
        (Thread/sleep 300)
        ;; After completion, only non-authoritative inflight wait state is cleared
        (is (empty? @(deref #'wl/inflight-runs)))))))

(deftest delegate-continue-blocked-run-test
  (testing "continue on blocked run uses the supplied prompt and resumes asynchronously"
    (let [resume-params (atom nil)
          api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.extension/start-background-job
                 (fn [params]
                   {:psi.background-job/job-id (:job-id params)
                    :psi.background-job/status :running})
                 'psi.extension/mark-background-job-terminal
                 (fn [_]
                   {:psi.background-job/job-id "job-resume"
                    :psi.background-job/status :completed})
                 'psi.workflow/resume-run
                 (fn [params]
                   (reset! resume-params params)
                   {:psi.workflow/run-id "run-1"
                    :psi.workflow/status :completed})
                 'psi.workflow/list-runs
                 (fn [_]
                   {:psi.workflow/runs [{:run-id "run-1"
                                         :status :blocked
                                         :source-definition-id "planner"}]})
                 'psi.extension/append-entry
                 (fn [_] {})}})]
      (with-redefs [workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (let [result (execute-tool {:action "continue"
                                    :id "run-1"
                                    :prompt "continue with this"})]
          (is (.contains ^String result "Resuming"))
          (Thread/sleep 200)
          (is (= {:run-id "run-1"
                  :session-id "test-session-1"
                  :workflow-input {:input "continue with this"
                                   :original "continue with this"}}
                 @resume-params)))))))

(deftest delegate-continue-terminal-run-test
  (testing "continue on terminal run creates and executes a fresh run from the original definition"
    (let [create-params (atom nil)
          execute-params (atom nil)
          api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/create-run
                 (fn [params]
                   (reset! create-params params)
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/status :pending})
                 'psi.extension/start-background-job
                 (fn [params]
                   {:psi.background-job/job-id (:job-id params)
                    :psi.background-job/status :running})
                 'psi.extension/mark-background-job-terminal
                 (fn [_]
                   {:psi.background-job/job-id "job-continue"
                    :psi.background-job/status :completed})
                 'psi.workflow/execute-run
                 (fn [params]
                   (reset! execute-params params)
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/status :completed
                    :psi.workflow/result "continued output"})
                 'psi.workflow/list-runs
                 (fn [_]
                   {:psi.workflow/runs [{:run-id "run-1"
                                         :status :completed
                                         :source-definition-id "planner"}]})
                 'psi.extension/append-entry
                 (fn [_] {})}})]
      (with-redefs [workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (let [result (execute-tool {:action "continue"
                                    :id "run-1"
                                    :prompt "plan the next slice"})]
          (is (.contains ^String result "Resuming"))
          (Thread/sleep 200)
          (is (= "planner" (:definition-id @create-params)))
          (is (= {:input "plan the next slice"
                  :original "plan the next slice"}
                 (:workflow-input @create-params)))
          (is (= "test-session-1" (:session-id @execute-params)))
          (is (string? (:run-id @execute-params))))))))

(deftest on-async-completion-side-effects-do-not-rewrite-clean-exec-result-test
  (testing "async completion side effects should not turn a clean workflow failure into a keyword contains? error"
    (let [inflight-runs (atom {"run-1" {:job-id "job-1"}})
          seen-terminal (atom nil)
          seen-notify (atom [])
          seen-append (atom [])
          refresh-count (atom 0)
          exec-result {:psi.workflow/run-id "run-1"
                       :psi.workflow/status :failed
                       :psi.workflow/result nil
                       :psi.workflow/error "Missing Anthropic API key."}]
      (orchestration/on-async-completion!
       {:mutate! (fn [sym params]
                   (swap! seen-append conj {:sym sym :params params})
                   {})
        :notify! (fn [msg level]
                   (swap! seen-notify conj {:msg msg :level level}))
        :mark-background-job-terminal! (fn [job-id status payload]
                                         (reset! seen-terminal {:job-id job-id :status status :payload payload})
                                         {})
        :inject-result-into-context! (fn [& _] nil)
        :refresh-widgets! (fn [] (swap! refresh-count inc))
        :inflight-runs inflight-runs}
       "run-1" "lambda-build" "session-1" false exec-result)
      (is (= {:job-id "job-1"
              :status :failed
              :payload {:run-id "run-1"
                        :workflow "lambda-build"
                        :status :failed
                        :result nil
                        :error "Missing Anthropic API key."}}
             @seen-terminal))
      (is (= [{:msg "Workflow 'lambda-build' failed (run run-1)"
               :level :warn}]
             @seen-notify))
      (is (= 1 @refresh-count))
      (is (empty? @inflight-runs))
      (is (some #(= 'psi.extension/append-entry (:sym %)) @seen-append)))))

(deftest execute-async-preserves-clean-mutation-error-test
  (testing "execute-async returns the mutation's clean workflow error when side effects succeed"
    (let [inflight-runs (atom {})
          api-ops (atom [])
          result (orchestration/execute-async!
                  {:mutate! (fn [sym params]
                              (swap! api-ops conj {:sym sym :params params})
                              (case sym
                                psi.workflow/execute-run {:psi.workflow/run-id "run-1"
                                                          :psi.workflow/status :failed
                                                          :psi.workflow/error "Missing Anthropic API key."}
                                {}))
                   :start-background-job! (fn [_session-id _run-id _workflow-name]
                                            {:job-id "job-1"})
                   :mark-background-job-terminal! (fn [& _] {})
                   :notify! (fn [& _] nil)
                   :refresh-widgets! (fn [] nil)
                   :inflight-runs inflight-runs
                   :on-async-completion-fn (fn [& _] nil)}
                  "run-1" "session-1" "lambda-build" false)
          fut (get-in @inflight-runs [result :future])
          final @fut]
      (is (= "run-1" result))
      (is (= {:run-id "run-1"
              :workflow "lambda-build"
              :status :failed
              :error "Missing Anthropic API key."}
             final)))))

(deftest exception-summary-includes-stack-frames-test
  (testing "exception-summary includes the message plus a few stack frames"
    (let [summary (try
                    (throw (ex-info "boom" {}))
                    (catch Exception e
                      (orchestration/exception-summary e 2)))]
      (is (string? summary))
      (is (.contains ^String summary "boom"))
      (is (.contains ^String summary "("))
      (is (.contains ^String summary ":")))))

(deftest delegate-continue-running-run-test
  (testing "continue rejects runs that are not stopped"
    (let [api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/list-runs
                 (fn [_]
                   {:psi.workflow/runs [{:run-id "run-1"
                                         :status :running
                                         :source-definition-id "planner"}]})}})]
      (with-redefs [workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (let [result (execute-tool {:action "continue"
                                    :id "run-1"
                                    :prompt "continue with this"})]
          (is (.contains ^String result "Error"))
          (is (.contains ^String result "is not stopped")))))))
