(ns extensions.mcp-tasks-run-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.mcp-tasks-run :as sut]
   [psi.agent-session.executor :as executor]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(deftest derive-task-state-test
  (testing "derive-task-state"
    (is (= :terminated
           (sut/derive-task-state {:status :closed})))
    (is (= :complete
           (sut/derive-task-state {:status :open :pr-num 12 :pr-merged? true})))
    (is (= :wait-pr-merge
           (sut/derive-task-state {:status :open :pr-num 12})))
    (is (= :awaiting-pr
           (sut/derive-task-state {:status :done :code-reviewed "2026-01-01"})))
    (is (= :done
           (sut/derive-task-state {:status :done})))
    (is (= :refined
           (sut/derive-task-state {:status :open :meta {:refined "true"}})))
    (is (= :unrefined
           (sut/derive-task-state {:status :open :meta {:x 1}})))))

(deftest derive-story-state-test
  (testing "derive-story-state"
    (is (= :terminated
           (sut/derive-story-state {:status :closed} [])))
    (is (= :complete
           (sut/derive-story-state {:status :open :pr-num 10 :pr-merged? true}
                                   [{:status :closed}])))
    (is (= :has-tasks
           (sut/derive-story-state {:status :open :meta {:refined "true"}}
                                   [{:status :open} {:status :closed}])))
    (is (= :wait-pr-merge
           (sut/derive-story-state {:status :open :pr-num 10 :meta {:refined "true"}}
                                   [{:status :closed}])))
    (is (= :awaiting-pr
           (sut/derive-story-state {:status :open
                                    :meta {:refined "true"}
                                    :code-reviewed "2026-01-01"}
                                   [{:status :closed} {:status :done}])))
    (is (= :done
           (sut/derive-story-state {:status :open :meta {:refined "true"}}
                                   [{:status :closed} {:status :done}])))
    (is (= :refined
           (sut/derive-story-state {:status :open :meta {:refined "true"}} [])))
    (is (= :unrefined
           (sut/derive-story-state {:status :open} [])))))

(deftest init-registers-surface-test
  (testing "init registers workflow type, command, and lifecycle handler"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/mcp_tasks_run.clj"})]
      (sut/init api)
      (is (contains? (:workflow-types @state) :mcp-tasks-run))
      (is (contains? (:commands @state) "mcp-tasks-run"))
      (is (= 1 (count (get-in @state [:handlers "session_switch"]))))
      (is (= "mcp-tasks-run loaded"
             (-> @state :notifications last :text))))))

(deftest command-basic-behavior-test
  (testing "command prints usage and can start/list runs"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/mcp_tasks_run.clj"})]
      (sut/init api)
      (let [handler (get-in @state [:commands "mcp-tasks-run" :handler])]
        (is (string? (with-out-str (handler ""))))

        (let [out-start (with-out-str (handler "42"))]
          (is (re-find #"Started mcp-tasks run run-1" out-start))
          (is (contains? (:workflows @state) "run-1")))

        (let [out-list (with-out-str (handler "list"))]
          (is (re-find #"run-1" out-list))))))

  (testing "widget status aggregates mixed phases across multiple runs"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/mcp_tasks_run.clj"})]
      (sut/init api)
      (let [wf-running {:psi.extension.workflow/id "run-1"
                        :psi.extension.workflow/phase :running
                        :psi.extension.workflow/running? true
                        :psi.extension.workflow/error? false
                        :psi.extension.workflow/data {:run/task-id 42
                                                      :run/entity-type :task
                                                      :run/current-state :refined
                                                      :run/last-step "execute-task"}}
            wf-paused {:psi.extension.workflow/id "run-2"
                       :psi.extension.workflow/phase :paused
                       :psi.extension.workflow/running? false
                       :psi.extension.workflow/error? false
                       :psi.extension.workflow/data {:run/task-id 99
                                                     :run/entity-type :story
                                                     :run/current-state :wait-user-confirmation
                                                     :run/last-step "execute-story-child"
                                                     :run/pause-reason :wait-user-confirmation}}
            wf-error {:psi.extension.workflow/id "run-3"
                      :psi.extension.workflow/phase :error
                      :psi.extension.workflow/running? false
                      :psi.extension.workflow/error? true
                      :psi.extension.workflow/data {:run/task-id 100
                                                    :run/entity-type :task
                                                    :run/current-state :derive-state
                                                    :run/last-step "execute-task"}}]
        (with-redefs [sut/mcp-run-workflows (fn [] [wf-running wf-paused wf-error])]
          (#'sut/refresh-widgets!))
        (is (= "mcp-tasks-run: 3 run(s) · 1 running · 1 paused · 1 error"
               (last (:status-lines @state))))))))

(deftest workflow-native-step-progression-test
  (testing "run-loop-job advances a single orchestration unit per invocation"
    (let [ctrl            (atom {:pause? false :cancel? false :merge? false})
          derived-states  (atom [:refined :done :done :terminated])]
      (with-redefs [sut/ensure-worktree! (fn [_project-dir _task-id]
                                           {:worktree-dir "/tmp/wt"})
                    sut/derive-current! (fn [_project-dir _worktree-dir _task-id]
                                          (let [s (or (first @derived-states) :terminated)]
                                            (swap! derived-states #(if (seq %) (vec (rest %)) %))
                                            {:task {:id 42 :type :task}
                                             :children []
                                             :entity-type :task
                                             :state s
                                             :completed-count nil}))
                    sut/resolve-step-prompt (fn [_project-dir _prompt-name] "prompt")
                    sut/run-step-subagent! (fn [_] {:ok? true :text "ok"})]
        (let [step-1 (#'sut/run-loop-job {:run-id "run-1"
                                          :task-id 42
                                          :project-dir "/tmp"
                                          :worktree-dir nil
                                          :control ctrl
                                          :current-state :ensure-worktree
                                          :steps 0
                                          :history []
                                          :max-steps 10})
              step-2 (#'sut/run-loop-job {:run-id "run-1"
                                          :task-id 42
                                          :project-dir "/tmp"
                                          :worktree-dir (:worktree-dir step-1)
                                          :control ctrl
                                          :current-state (:current-state step-1)
                                          :steps (:steps-completed step-1)
                                          :history (:history step-1)
                                          :max-steps 10})
              step-3 (#'sut/run-loop-job {:run-id "run-1"
                                          :task-id 42
                                          :project-dir "/tmp"
                                          :worktree-dir (:worktree-dir step-2)
                                          :control ctrl
                                          :current-state (:current-state step-2)
                                          :steps (:steps-completed step-2)
                                          :history (:history step-2)
                                          :max-steps 10})]
          (is (= :running (:status step-1)))
          (is (= :derive-state (:current-state step-1)))
          (is (= :running (:status step-2)))
          (is (= :derive-state (:current-state step-2)))
          (is (= 1 (:steps-completed step-2)))
          (is (= 1 (count (:history step-2))))
          (is (= :running (:status step-3)))
          (is (= :derive-state (:current-state step-3)))
          (is (= 2 (:steps-completed step-3))))))))

(deftest workflow-native-legacy-phase-normalization-test
  (testing "run-loop-job recovers from legacy derived phase values"
    (let [ctrl           (atom {:pause? false :cancel? false :merge? false})
          derived-states (atom [{:state :has-tasks :completed-count 0}
                                {:state :done :completed-count 1}])]
      (with-redefs [sut/derive-current! (fn [_project-dir _worktree-dir _task-id]
                                          (let [{:keys [state completed-count]}
                                                (or (first @derived-states)
                                                    {:state :done :completed-count 1})]
                                            (swap! derived-states #(if (seq %) (vec (rest %)) %))
                                            {:task {:id 42 :type :story}
                                             :children [{:id 100 :status :open}]
                                             :entity-type :story
                                             :state state
                                             :completed-count completed-count}))
                    sut/resolve-step-prompt (fn [_project-dir _prompt-name] "prompt")
                    sut/run-step-subagent! (fn [_] {:ok? true :text "ok"})]
        (let [res (#'sut/run-loop-job {:run-id "run-1"
                                       :task-id 42
                                       :project-dir "/tmp"
                                       :worktree-dir "/tmp/wt"
                                       :control ctrl
                                       :current-state "has-tasks"
                                       :steps 0
                                       :history []
                                       :max-steps 10})]
          (is (= :running (:status res)))
          (is (= :derive-state (:current-state res)))
          (is (= 1 (:steps-completed res))))))))

(deftest workflow-story-child-snapshot-progress-test
  (testing "run-loop-job accepts story progress while state remains :has-tasks"
    (let [ctrl           (atom {:pause? false :cancel? false :merge? false})
          derived-states (atom [{:task {:id 42 :type :story :status :open :meta {:refined "true"}}
                                 :children [{:id 100 :status :open :meta {}}]
                                 :entity-type :story
                                 :state :has-tasks
                                 :completed-count 0}
                                {:task {:id 42 :type :story :status :open :meta {:refined "true"}}
                                 :children [{:id 100 :status :open :meta {:refined "true"}}]
                                 :entity-type :story
                                 :state :has-tasks
                                 :completed-count 0}])]
      (with-redefs [sut/derive-current! (fn [_project-dir _worktree-dir _task-id]
                                          (let [entry (or (first @derived-states)
                                                          {:task {:id 42 :type :story :status :open :meta {:refined "true"}}
                                                           :children [{:id 100 :status :open :meta {:refined "true"}}]
                                                           :entity-type :story
                                                           :state :has-tasks
                                                           :completed-count 0})]
                                            (swap! derived-states #(if (seq %) (vec (rest %)) %))
                                            entry))
                    sut/resolve-step-prompt (fn [_project-dir _prompt-name] "prompt")
                    sut/run-step-subagent! (fn [_] {:ok? true :text "ok"})]
        (let [res (#'sut/run-loop-job {:run-id "run-1"
                                       :task-id 42
                                       :project-dir "/tmp"
                                       :worktree-dir "/tmp/wt"
                                       :control ctrl
                                       :current-state :derive-state
                                       :steps 0
                                       :history []
                                       :max-steps 10})]
          (is (= :running (:status res)))
          (is (= :derive-state (:current-state res)))
          (is (= 1 (:steps-completed res))))))))

(deftest workflow-story-child-transient-no-progress-test
  (testing "run-loop-job tolerates transient no-progress at :has-tasks"
    (let [ctrl (atom {:pause? false :cancel? false :merge? false})
          snapshot {:task {:id 42 :type :story :status :open :meta {:refined "true"}}
                    :children [{:id 100 :status :open :meta {:refined "true"}}]
                    :entity-type :story
                    :state :has-tasks
                    :completed-count 0}]
      (with-redefs [sut/derive-current! (fn [_project-dir _worktree-dir _task-id]
                                          snapshot)
                    sut/resolve-step-prompt (fn [_project-dir _prompt-name] "prompt")
                    sut/run-step-subagent! (fn [_] {:ok? true :text "ok"})]
        (let [res (#'sut/run-loop-job {:run-id "run-1"
                                       :task-id 42
                                       :project-dir "/tmp"
                                       :worktree-dir "/tmp/wt"
                                       :control ctrl
                                       :current-state :derive-state
                                       :steps 0
                                       :history []
                                       :max-steps 10})]
          (is (= :running (:status res)))
          (is (= :derive-state (:current-state res)))
          (is (= 1 (:steps-completed res)))
          (is (= false (:progress? (last (:history res))))))))))

(deftest workflow-story-child-no-progress-threshold-test
  (testing "run-loop-job errors after repeated no-progress at :has-tasks"
    (let [ctrl (atom {:pause? false :cancel? false :merge? false})
          snapshot {:task {:id 42 :type :story :status :open :meta {:refined "true"}}
                    :children [{:id 100 :status :open :meta {:refined "true"}}]
                    :entity-type :story
                    :state :has-tasks
                    :completed-count 0}
          history (vec (repeat 4 {:state :has-tasks :next-state :has-tasks :progress? false}))]
      (with-redefs [sut/derive-current! (fn [_project-dir _worktree-dir _task-id]
                                          snapshot)
                    sut/resolve-step-prompt (fn [_project-dir _prompt-name] "prompt")
                    sut/run-step-subagent! (fn [_] {:ok? true :text "ok"})]
        (let [res (#'sut/run-loop-job {:run-id "run-1"
                                       :task-id 42
                                       :project-dir "/tmp"
                                       :worktree-dir "/tmp/wt"
                                       :control ctrl
                                       :current-state :derive-state
                                       :steps 4
                                       :history history
                                       :max-steps 10})]
          (is (= :error (:status res)))
          (is (re-find #"consecutive attempt" (:error-message res))))))))

(deftest workflow-native-wait-pr-merge-test
  (testing "run-loop-job pauses explicitly at wait-pr-merge without merge authorization"
    (let [ctrl (atom {:pause? false :cancel? false :merge? false})]
      (with-redefs [sut/derive-current! (fn [_project-dir _worktree-dir _task-id]
                                          {:task {:id 42 :type :task}
                                           :children []
                                           :entity-type :task
                                           :state :wait-pr-merge
                                           :completed-count nil})]
        (let [res (#'sut/run-loop-job {:run-id "run-1"
                                       :task-id 42
                                       :project-dir "/tmp"
                                       :worktree-dir "/tmp/wt"
                                       :control ctrl
                                       :current-state :derive-state
                                       :steps 0
                                       :history []
                                       :max-steps 10})]
          (is (= :paused (:status res)))
          (is (= :wait-pr-merge (:current-state res)))
          (is (= :wait-pr-merge (:pause-reason res))))))))

(deftest workflow-merge-gate-guard-test
  (testing "merge resume guard only matches when merge intent is outside wait-pr-merge gate"
    (is (true? (#'sut/merge-resume-outside-gate?
                nil
                {:_event {:data {:merge? true}}
                 :run/pause-reason :user-paused})))
    (is (false? (#'sut/merge-resume-outside-gate?
                 nil
                 {:_event {:data {:merge? true}}
                  :run/pause-reason :wait-pr-merge})))
    (is (false? (#'sut/merge-resume-outside-gate?
                 nil
                 {:_event {:data {:merge? false}}
                  :run/pause-reason :user-paused}))))

  (testing "reject script writes explicit merge authorization failure context"
    (let [ctrl (atom {:pause? false :cancel? false :merge? true})
          ops  (#'sut/reject-merge-resume-script
                nil
                {:run/control ctrl
                 :run/id "run-1"})]
      (is (= false (:merge? @ctrl)))
      (is (= [{:op :assign
               :data {:run/pause-reason :merge-not-authorized
                      :run/last-output "Merge authorization is only valid at :wait-pr-merge gate. Resume without merge until gate is reached."
                      :workflow/error-message nil}}]
             ops)))))

(deftest user-confirmation-parsing-test
  (testing "parse-user-confirmation reads sentinel payload"
    (is (= {:question "Proceed with destructive change?"
            :context {:task-id 18}
            :expected :yes-no
            :raw {:question "Proceed with destructive change?"
                  :context {:task-id 18}
                  :expected-answer :yes-no}}
           (#'sut/parse-user-confirmation
            "text before\nMCP_TASKS_RUN_USER_CONFIRMATION: {:question \"Proceed with destructive change?\" :context {:task-id 18} :expected-answer :yes-no}\ntext after"))))

  (testing "parse-user-confirmation returns nil when sentinel missing"
    (is (nil? (#'sut/parse-user-confirmation "no confirmation marker")))))

(deftest workflow-user-confirmation-wait-and-resume-test
  (testing "run-loop-job pauses explicitly on user confirmation marker"
    (let [pending-session {:agent-ctx :ctx
                           :session-ctx :session
                           :model {:id "test"}
                           :step-name "review-story-implementation"
                           :step-state :done}
          ctrl (atom {:pause? false :cancel? false :merge? false :answer nil})]
      (with-redefs [sut/derive-current! (fn [_project-dir _worktree-dir _task-id]
                                          {:task {:id 42 :type :task}
                                           :children []
                                           :entity-type :task
                                           :state :refined
                                           :completed-count nil})
                    sut/resolve-step-prompt (fn [_project-dir _prompt-name] "prompt")
                    sut/run-step-subagent! (fn [_]
                                             {:ok? true
                                              :step-session pending-session
                                              :text "MCP_TASKS_RUN_USER_CONFIRMATION: {:question \"Continue?\" :expected-answer :yes-no}"})]
        (let [res (#'sut/run-loop-job {:run-id "run-1"
                                       :task-id 42
                                       :project-dir "/tmp"
                                       :worktree-dir "/tmp/wt"
                                       :control ctrl
                                       :current-state :derive-state
                                       :steps 0
                                       :history []
                                       :user-confirmation nil
                                       :user-answer nil
                                       :max-steps 10})]
          (is (= :paused (:status res)))
          (is (= :wait-user-confirmation (:current-state res)))
          (is (= :wait-user-confirmation (:pause-reason res)))
          (is (= "Continue?" (get-in res [:user-confirmation :question])))
          (is (= pending-session (:pending-step-session @ctrl))))))))

(deftest workflow-user-confirmation-resume-routes-to-asking-session-test
  (testing "resume answer is sent back to the session that asked"
    (let [pending-session {:agent-ctx :ctx
                           :session-ctx :session
                           :model {:id "test"}
                           :step-name "review-story-implementation"
                           :step-state :done}
          ctrl            (atom {:pause? false
                                 :cancel? false
                                 :merge? false
                                 :answer "create follow-up tasks"
                                 :pending-step-session pending-session})
          captured        (atom nil)
          derived-states  (atom [{:task {:id 42 :type :story :status :open :meta {:refined "true"}}
                                  :children [{:id 1 :status :done :meta {:refined "true"}}]
                                  :entity-type :story
                                  :state :done
                                  :completed-count 1}
                                 {:task {:id 42 :type :story :status :open :meta {:refined "true"}}
                                  :children [{:id 1 :status :done :meta {:refined "true"}}
                                             {:id 2 :status :open :meta {}}]
                                  :entity-type :story
                                  :state :has-tasks
                                  :completed-count 1}])]
      (with-redefs [sut/derive-current! (fn [_project-dir _worktree-dir _task-id]
                                          (let [entry (first @derived-states)]
                                            (swap! derived-states #(if (seq %) (vec (rest %)) %))
                                            entry))
                    sut/resolve-step-prompt (fn [_project-dir _prompt-name] "prompt")
                    sut/run-step-subagent! (fn [args]
                                             (reset! captured args)
                                             {:ok? true
                                              :step-session pending-session
                                              :text "Processed answer and created new story tasks."})]
        (let [res (#'sut/run-loop-job {:run-id "run-1"
                                       :task-id 42
                                       :project-dir "/tmp"
                                       :worktree-dir "/tmp/wt"
                                       :control ctrl
                                       :current-state :wait-user-confirmation
                                       :steps 1
                                       :history []
                                       :user-confirmation {:question "Which review items should be added?"}
                                       :user-answer nil
                                       :max-steps 10})]
          (is (= :running (:status res)))
          (is (= :derive-state (:current-state res)))
          (is (= pending-session (:resume-step-session @captured)))
          (is (= "create follow-up tasks" (:user-answer @captured)))
          (is (= "review-story-implementation" (:step-name @captured)))
          (is (nil? (:pending-step-session @ctrl)))
          (is (nil? (:answer @ctrl))))))))

(deftest start-run-admission-policy-test
  (testing "allows concurrent runs for distinct task IDs"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/mcp_tasks_run.clj"})]
      (sut/init api)
      (let [handler (get-in @state [:commands "mcp-tasks-run" :handler])]
        (let [out-1 (with-out-str (handler "42"))
              out-2 (with-out-str (handler "99"))]
          (is (re-find #"Started mcp-tasks run run-1" out-1))
          (is (re-find #"Started mcp-tasks run run-2" out-2))
          (is (contains? (:workflows @state) "run-1"))
          (is (contains? (:workflows @state) "run-2"))))))

  (testing "rejects duplicate active run for same task when existing run is running"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/mcp_tasks_run.clj"})]
      (sut/init api)
      (let [handler (get-in @state [:commands "mcp-tasks-run" :handler])]
        (with-out-str (handler "42"))
        (swap! state update-in [:workflows "run-1"]
               assoc
               :psi.extension.workflow/running? true
               :psi.extension.workflow/phase :running)
        (let [out (with-out-str (handler "42"))]
          (is (re-find #"Task #42 already has active run run-1 \(running\)\." out))
          (is (= #{"run-1"}
                 (set (keys (:workflows @state)))))))))

  (testing "rejects duplicate active run for same task when existing run is paused"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/mcp_tasks_run.clj"})]
      (sut/init api)
      (let [handler (get-in @state [:commands "mcp-tasks-run" :handler])]
        (with-out-str (handler "42"))
        (swap! state update-in [:workflows "run-1"]
               assoc
               :psi.extension.workflow/running? false
               :psi.extension.workflow/phase :paused)
        (let [out (with-out-str (handler "42"))]
          (is (re-find #"Task #42 already has active run run-1 \(paused\)\." out))
          (is (= #{"run-1"}
                 (set (keys (:workflows @state))))))))))

(deftest resume-run-phase-guard-test
  (testing "resume checks only its own run phase"
    (testing "allows resuming a paused run"
      (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                 {:path "/test/mcp_tasks_run.clj"})]
        (sut/init api)
        (let [handler (get-in @state [:commands "mcp-tasks-run" :handler])]
          (with-out-str (handler "42"))
          (swap! state update-in [:workflows "run-1"]
                 assoc
                 :psi.extension.workflow/running? true
                 :psi.extension.workflow/phase :paused
                 :psi.extension.workflow/data {:run/pause-reason :wait-pr-merge})
          (let [out (with-out-str (handler "resume run-1 merge"))]
            (is (not (re-find #"already running" out))
                "paused run should be resumable")))))

    (testing "rejects resuming an already running run"
      (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                 {:path "/test/mcp_tasks_run.clj"})]
        (sut/init api)
        (let [handler (get-in @state [:commands "mcp-tasks-run" :handler])]
          (with-out-str (handler "42"))
          (swap! state update-in [:workflows "run-1"]
                 assoc
                 :psi.extension.workflow/running? true
                 :psi.extension.workflow/phase :running)
          (let [out (with-out-str (handler "resume run-1"))]
            (is (re-find #"already running" out)
                "running run should not be resumable")))))))

(deftest run-controls-remain-run-id-scoped-with-concurrency-test
  (testing "pause updates only targeted run control"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/mcp_tasks_run.clj"})]
      (sut/init api)
      (let [handler (get-in @state [:commands "mcp-tasks-run" :handler])]
        (with-out-str (handler "42"))
        (with-out-str (handler "99"))
        (#'sut/ensure-control! "run-1")
        (#'sut/ensure-control! "run-2")
        (swap! state update-in [:workflows "run-1"]
               assoc
               :psi.extension.workflow/running? true
               :psi.extension.workflow/phase :running)
        (swap! state update-in [:workflows "run-2"]
               assoc
               :psi.extension.workflow/running? true
               :psi.extension.workflow/phase :running)
        (with-out-str (handler "pause run-1"))
        (is (= true (:pause? @(#'sut/control-for "run-1"))))
        (is (= false (:pause? @(#'sut/control-for "run-2")))))))

  (testing "cancel updates only targeted running run control"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/mcp_tasks_run.clj"})]
      (sut/init api)
      (let [handler (get-in @state [:commands "mcp-tasks-run" :handler])]
        (with-out-str (handler "42"))
        (with-out-str (handler "99"))
        (#'sut/ensure-control! "run-1")
        (#'sut/ensure-control! "run-2")
        (swap! state update-in [:workflows "run-1"]
               assoc
               :psi.extension.workflow/running? true
               :psi.extension.workflow/phase :running)
        (swap! state update-in [:workflows "run-2"]
               assoc
               :psi.extension.workflow/running? true
               :psi.extension.workflow/phase :running)
        (with-out-str (handler "cancel run-1"))
        (is (= true (:cancel? @(#'sut/control-for "run-1"))))
        (is (= false (:cancel? @(#'sut/control-for "run-2")))))))

  (testing "resume and retry send events only for targeted run-id"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/mcp_tasks_run.clj"})
          send-events (fn []
                        (->> (:mutations @state)
                             (filter #(= 'psi.extension.workflow/send-event (:op %)))
                             vec))]
      (sut/init api)
      (let [handler (get-in @state [:commands "mcp-tasks-run" :handler])]
        (with-out-str (handler "42"))
        (with-out-str (handler "99"))

        (swap! state update-in [:workflows "run-1"]
               assoc
               :psi.extension.workflow/running? false
               :psi.extension.workflow/phase :paused
               :psi.extension.workflow/data {:run/pause-reason :wait-user-confirmation})
        (swap! state update-in [:workflows "run-2"]
               assoc
               :psi.extension.workflow/running? false
               :psi.extension.workflow/phase :paused
               :psi.extension.workflow/data {:run/pause-reason :wait-user-confirmation})
        (let [before (count (send-events))]
          (with-out-str (handler "resume run-1 add review-item-1 and review-item-2"))
          (let [events (drop before (send-events))]
            (is (= 1 (count events)))
            (is (= "run-1" (get-in (first events) [:params :id])))
            (is (= "add review-item-1 and review-item-2"
                   (get-in (first events) [:params :data :answer])))))

        (swap! state update-in [:workflows "run-1"]
               assoc
               :psi.extension.workflow/running? false
               :psi.extension.workflow/phase :error)
        (swap! state update-in [:workflows "run-2"]
               assoc
               :psi.extension.workflow/running? false
               :psi.extension.workflow/phase :error)
        (let [before (count (send-events))]
          (with-out-str (handler "retry run-1"))
          (let [events (drop before (send-events))]
            (is (= 1 (count events)))
            (is (= "run-1" (get-in (first events) [:params :id])))))))))

(deftest workflow-user-confirmation-requires-answer-test
  (testing "run-loop-job remains paused when confirmation answer is missing"
    (let [ctrl (atom {:pause? false :cancel? false :merge? false :answer nil})
          res  (#'sut/run-loop-job {:run-id "run-1"
                                    :task-id 42
                                    :project-dir "/tmp"
                                    :worktree-dir "/tmp/wt"
                                    :control ctrl
                                    :current-state :wait-user-confirmation
                                    :steps 1
                                    :history []
                                    :user-confirmation {:question "Continue?"}
                                    :user-answer nil
                                    :max-steps 10})]
      (is (= :paused (:status res)))
      (is (= :wait-user-confirmation (:current-state res)))
      (is (= :wait-user-confirmation (:pause-reason res)))
      (is (re-find #"requires explicit answer" (:error-message res))))))

;;; Category prompt pre-resolution tests

(deftest build-step-request-with-category-prompt-test
  (testing "build-step-request includes category prompt section when provided"
    (let [result (#'sut/build-step-request
                  {:step-name "execute-task"
                   :prompt-body "Do the thing"
                   :task-id 42
                   :entity-type :task
                   :project-dir "/tmp"
                   :worktree-dir "/tmp/wt"
                   :task {:id 42 :category "simple"}
                   :children []
                   :state :refined
                   :category-prompt "Do the simple thing"})]
      (is (re-find #"Pre-resolved category instructions" result))
      (is (re-find #"Do the simple thing" result))
      ;; Category section should appear between prompt body and task snapshot
      (let [cat-idx  (.indexOf result "Pre-resolved category instructions")
            body-idx (.indexOf result "-----\nDo the thing")
            task-idx (.indexOf result "Current task snapshot")]
        (is (< body-idx cat-idx))
        (is (< cat-idx task-idx))))))

(deftest build-step-request-without-category-prompt-test
  (testing "build-step-request omits category section when nil"
    (let [result (#'sut/build-step-request
                  {:step-name "execute-task"
                   :prompt-body "Do the thing"
                   :task-id 42
                   :entity-type :task
                   :project-dir "/tmp"
                   :worktree-dir "/tmp/wt"
                   :task {:id 42}
                   :children []
                   :state :refined
                   :category-prompt nil})]
      (is (not (re-find #"Pre-resolved category instructions" result))))))

(deftest resolve-step-prompt-overrides-story-child-test
  (testing "resolve-step-prompt prefers local execute-story-child override"
    (let [result (#'sut/resolve-step-prompt "/tmp" "execute-story-child")]
      (is (re-find #"(?i)in-progress" result))
      (is (re-find #"Never mark the parent story done" result)))))

(deftest resolve-category-for-step-execute-task-test
  (testing "resolve-category-for-step extracts category from task for execute-task"
    (with-redefs [sut/load-mcp-prompt! (fn [_dir cat]
                                         (when (= "simple" cat)
                                           "Simple category instructions"))]
      (let [result (#'sut/resolve-category-for-step
                    "execute-task"
                    {:id 42 :category "simple"}
                    []
                    :task
                    "/tmp")]
        (is (= "Simple category instructions" result))))))

(deftest resolve-category-for-step-story-child-test
  (testing "resolve-category-for-step picks first open unblocked child for execute-story-child"
    (with-redefs [sut/load-mcp-prompt! (fn [_dir cat]
                                         (str cat " instructions"))]
      (let [children [{:status "open" :is-blocked true :category "large"}
                      {:status "open" :is-blocked false :category "medium"}
                      {:status "open" :is-blocked false :category "simple"}]
            result   (#'sut/resolve-category-for-step
                      "execute-story-child"
                      {:id 20 :type "story"}
                      children
                      :story
                      "/tmp")]
        (is (= "medium instructions" result)))))

  (testing "resolve-category-for-step handles keyword status for children"
    (with-redefs [sut/load-mcp-prompt! (fn [_dir cat]
                                         (str cat " instructions"))]
      (let [children [{:status :open :is-blocked false :category "medium"}]
            result   (#'sut/resolve-category-for-step
                      "execute-story-child"
                      {:id 20 :type "story"}
                      children
                      :story
                      "/tmp")]
        (is (= "medium instructions" result))))))

(deftest resolve-category-for-step-missing-category-test
  (testing "resolve-category-for-step returns nil when task has no category"
    (with-redefs [sut/load-mcp-prompt! (fn [_dir _cat]
                                         (throw (ex-info "should not be called" {})))]
      (let [result (#'sut/resolve-category-for-step
                    "execute-task"
                    {:id 42}
                    []
                    :task
                    "/tmp")]
        (is (nil? result))))))

(deftest build-step-request-subagent-constraint-test
  (testing "build-step-request instructs subagents to use confirmation marker for material questions"
    (let [result (#'sut/build-step-request
                  {:step-name "execute-task"
                   :prompt-body "Do the thing"
                   :task-id 42
                   :entity-type :task
                   :project-dir "/tmp"
                   :worktree-dir "/tmp/wt"
                   :task {:id 42 :category "medium"}
                   :children []
                   :state :refined
                   :category-prompt nil})]
      (is (re-find #"MCP_TASKS_RUN_USER_CONFIRMATION" result)
          "should instruct subagent to use the confirmation marker")
      (is (not (re-find #"choose a deterministic" result))
          "old instruction should be removed")
      (is (re-find #"(?i)trivial" result)
          "should mention trivial/mechanical questions")
      (is (re-find #"(?i)material" result)
          "should mention material questions")
      (is (re-find #":question" result)
          "should mention :question field")
      (is (re-find #"safe default" result)
          "should instruct choosing safe defaults for trivial questions"))))

(deftest widget-lines-shows-question-on-user-confirmation-test
  ;; Tests that widget-lines includes the question text when paused for user confirmation
  (testing "widget-lines includes question line when paused at :wait-user-confirmation"
    (let [wf {:psi.extension.workflow/id "run-1"
              :psi.extension.workflow/phase :paused
              :psi.extension.workflow/running? false
              :psi.extension.workflow/data
              {:run/task-id 42
               :run/entity-type :task
               :run/current-state :wait-user-confirmation
               :run/last-step "execute-task"
               :run/pause-reason :wait-user-confirmation
               :run/user-confirmation {:question "Should we proceed with the destructive migration?"}}}
          lines (#'sut/widget-lines wf)]
      (is (some #(re-find #"❓.*Should we proceed with the destructive migration?" %) lines)
          "should contain the question line with ❓ prefix")))

  (testing "widget-lines omits question line when no user-confirmation data"
    (let [wf {:psi.extension.workflow/id "run-1"
              :psi.extension.workflow/phase :paused
              :psi.extension.workflow/running? false
              :psi.extension.workflow/data
              {:run/task-id 42
               :run/entity-type :task
               :run/current-state :wait-user-confirmation
               :run/last-step "execute-task"
               :run/pause-reason :wait-user-confirmation}}
          lines (#'sut/widget-lines wf)]
      (is (not (some #(re-find #"❓" %) lines))
          "should not contain a question line")))

  (testing "widget-lines shows all lines for a multi-line question"
    (let [wf {:psi.extension.workflow/id "run-1"
              :psi.extension.workflow/phase :paused
              :psi.extension.workflow/running? false
              :psi.extension.workflow/data
              {:run/task-id 42
               :run/entity-type :task
               :run/current-state :wait-user-confirmation
               :run/last-step "execute-task"
               :run/pause-reason :wait-user-confirmation
               :run/user-confirmation {:question "Should we proceed?\n- Option A\n- Option B"}}}
          lines (#'sut/widget-lines wf)]
      (is (some #(re-find #"❓.*Should we proceed\?" %) lines)
          "should include the first question line")
      (is (some #(re-find #"- Option A" %) lines)
          "should include follow-up lines")
      (is (some #(re-find #"- Option B" %) lines)
          "should include all question lines"))))

(deftest list-runs-shows-question-on-user-confirmation-test
  ;; Tests that list-runs! includes the question text in output
  (testing "list-runs! prints question line when paused at :wait-user-confirmation"
    (let [wf {:psi.extension.workflow/id "run-1"
              :psi.extension.workflow/phase :paused
              :psi.extension.workflow/running? false
              :psi.extension.workflow/data
              {:run/task-id 42
               :run/entity-type :task
               :run/current-state :wait-user-confirmation
               :run/last-step "execute-task"
               :run/pause-reason :wait-user-confirmation
               :run/user-confirmation {:question "Should we use approach A or B?"}}}]
      (with-redefs [sut/mcp-run-workflows (fn [] [wf])
                    sut/elapsed-seconds (fn [_] 10)]
        (let [output (with-out-str (#'sut/list-runs!))]
          (is (re-find #"❓.*Should we use approach A or B\?" output)
              "should print the question text")))))

  (testing "list-runs! prints every line for a multi-line question"
    (let [wf {:psi.extension.workflow/id "run-1"
              :psi.extension.workflow/phase :paused
              :psi.extension.workflow/running? false
              :psi.extension.workflow/data
              {:run/task-id 42
               :run/entity-type :task
               :run/current-state :wait-user-confirmation
               :run/last-step "execute-task"
               :run/pause-reason :wait-user-confirmation
               :run/user-confirmation {:question "Pick one:\nA) fast path\nB) safe path"}}}]
      (with-redefs [sut/mcp-run-workflows (fn [] [wf])
                    sut/elapsed-seconds (fn [_] 10)]
        (let [output (with-out-str (#'sut/list-runs!))]
          (is (re-find #"Pick one:" output)
              "should print first question line")
          (is (re-find #"A\) fast path" output)
              "should print second question line")
          (is (re-find #"B\) safe path" output)
              "should print third question line")))))

  (testing "list-runs! prints multiple runs with task IDs and phases"
    (let [wf-running {:psi.extension.workflow/id "run-1"
                      :psi.extension.workflow/phase :running
                      :psi.extension.workflow/running? true
                      :psi.extension.workflow/data {:run/task-id 42
                                                    :run/entity-type :task
                                                    :run/current-state :refined
                                                    :run/last-step "execute-task"}}
          wf-paused {:psi.extension.workflow/id "run-2"
                     :psi.extension.workflow/phase :paused
                     :psi.extension.workflow/running? false
                     :psi.extension.workflow/data {:run/task-id 99
                                                   :run/entity-type :story
                                                   :run/current-state :wait-user-confirmation
                                                   :run/last-step "execute-story-child"
                                                   :run/pause-reason :wait-user-confirmation}}]
      (with-redefs [sut/mcp-run-workflows (fn [] [wf-running wf-paused])
                    sut/elapsed-seconds (fn [_] 10)]
        (let [output (with-out-str (#'sut/list-runs!))]
          (is (re-find #"run-1 \[RUNNING\] task #42" output))
          (is (re-find #"run-2 \[PAUSED\] story #99" output)))))))

(deftest resolve-category-for-step-non-matching-step-test
  (testing "resolve-category-for-step returns nil for non-matching step names"
    (with-redefs [sut/load-mcp-prompt! (fn [_dir _cat]
                                         (throw (ex-info "should not be called" {})))]
      (is (nil? (#'sut/resolve-category-for-step
                 "refine-task"
                 {:id 42 :category "simple"}
                 []
                 :task
                 "/tmp")))
      (is (nil? (#'sut/resolve-category-for-step
                 "create-story-tasks"
                 {:id 42 :category "simple"}
                 []
                 :task
                 "/tmp"))))))

(deftest run-step-subagent-executor-arg-order-test
  (testing "run-step-subagent passes executor args in run-agent-loop order"
    (let [captured          (atom nil)
          fake-agent-ctx    {:fake :agent-ctx}
          fake-session-ctx  {:agent-ctx fake-agent-ctx
                             :session-data-atom (atom {:tool-output-overrides {}})
                             :tool-output-stats-atom (atom {:calls []
                                                            :aggregates {:total-context-bytes 0
                                                                         :by-tool {}
                                                                         :limit-hits-by-tool {}}})}
          fake-model        {:provider "anthropic" :id "sonnet"}]
      (with-redefs [sut/resolve-active-model (fn [] fake-model)
                    sut/create-step-agent-ctx (fn [_] fake-agent-ctx)
                    sut/create-step-session-ctx (fn [agent-ctx]
                                                  (is (= fake-agent-ctx agent-ctx))
                                                  fake-session-ctx)
                    sut/set-live-progress! (fn [& _])
                    executor/run-agent-loop! (fn [& args]
                                               (reset! captured args)
                                               {:role "assistant"
                                                :stop-reason :stop
                                                :content [{:type :text :text "ok"}]})]
        (let [result (#'sut/run-step-subagent!
                      {:run-id "run-1"
                       :step-name "refine-task"
                       :prompt-body "prompt"
                       :task-id 42
                       :entity-type :task
                       :project-dir "/tmp"
                       :worktree-dir "/tmp/wt"
                       :task {:id 42}
                       :children []
                       :state :unrefined})]
          (is (:ok? result))
          (is (= 6 (count @captured)))
          (is (nil? (nth @captured 0)))
          (is (= fake-session-ctx (nth @captured 1)))
          (is (= fake-agent-ctx (nth @captured 2)))
          (is (= fake-model (nth @captured 3)))
          (is (= "user" (get-in (nth @captured 4) [0 :role])))
          (is (= {:turn-ctx-atom nil} (nth @captured 5))))))))

(deftest run-step-subagent-resume-session-test
  (testing "run-step-subagent reuses the pending asking session on resume"
    (let [captured         (atom nil)
          fake-agent-ctx   {:fake :agent-ctx}
          fake-session-ctx {:agent-ctx fake-agent-ctx
                            :session-data-atom (atom {:tool-output-overrides {}})
                            :tool-output-stats-atom (atom {:calls []
                                                           :aggregates {:total-context-bytes 0
                                                                        :by-tool {}
                                                                        :limit-hits-by-tool {}}})}
          fake-model       {:provider "anthropic" :id "sonnet"}
          resume-session   {:agent-ctx fake-agent-ctx
                            :session-ctx fake-session-ctx
                            :model fake-model
                            :step-name "review-story-implementation"
                            :step-state :done}]
      (with-redefs [sut/set-live-progress! (fn [& _])
                    sut/create-step-agent-ctx (fn [_]
                                                (throw (ex-info "should not create a new agent ctx" {})))
                    sut/create-step-session-ctx (fn [_]
                                                  (throw (ex-info "should not create a new session ctx" {})))
                    executor/run-agent-loop! (fn [& args]
                                               (reset! captured args)
                                               {:role "assistant"
                                                :stop-reason :stop
                                                :content [{:type :text :text "ok"}]})]
        (let [result (#'sut/run-step-subagent!
                      {:run-id "run-1"
                       :step-name "review-story-implementation"
                       :prompt-body "prompt"
                       :task-id 42
                       :entity-type :story
                       :project-dir "/tmp"
                       :worktree-dir "/tmp/wt"
                       :task {:id 42 :type :story}
                       :children [{:id 1 :status :done}]
                       :state :done
                       :user-answer "Create two follow-up tasks first."
                       :resume-step-session resume-session})]
          (is (:ok? result))
          (is (= 6 (count @captured)))
          (is (= fake-session-ctx (nth @captured 1)))
          (is (= fake-agent-ctx (nth @captured 2)))
          (is (= fake-model (nth @captured 3)))
          (is (= "Create two follow-up tasks first."
                 (get-in (nth @captured 4) [0 :content 0 :text])))
          (is (= resume-session (:step-session result))))))))
