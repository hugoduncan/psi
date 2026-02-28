(ns extensions.mcp-tasks-run-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.mcp-tasks-run :as sut]
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
          (is (re-find #"run-1" out-list)))))))

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
    (let [ctrl (atom {:pause? false :cancel? false :merge? false :answer nil})]
      (with-redefs [sut/derive-current! (fn [_project-dir _worktree-dir _task-id]
                                          {:task {:id 42 :type :task}
                                           :children []
                                           :entity-type :task
                                           :state :refined
                                           :completed-count nil})
                    sut/resolve-step-prompt (fn [_project-dir _prompt-name] "prompt")
                    sut/run-step-subagent! (fn [_]
                                             {:ok? true
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
          (is (= "Continue?" (get-in res [:user-confirmation :question]))))))))

(deftest active-running-excludes-paused-test
  ;; active-running-workflow only gates start-run!; resume/retry check own phase.
  (testing "active-running-workflow"
    (testing "does not match a paused workflow"
      (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                 {:path "/test/mcp_tasks_run.clj"})]
        (sut/init api)
        (let [handler (get-in @state [:commands "mcp-tasks-run" :handler])]
          ;; Start a run, then mark it paused (running? true, phase :paused)
          (with-out-str (handler "42"))
          (swap! state update-in [:workflows "run-1"]
                 assoc
                 :psi.extension.workflow/running? true
                 :psi.extension.workflow/phase :paused)
          ;; Starting a second run should succeed, not be blocked
          (let [out (with-out-str (handler "99"))]
            (is (re-find #"Started mcp-tasks run run-2" out)
                "paused run should not block starting a new run"))))))

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

  (testing "widget-lines truncates long question text to 200 chars"
    (let [long-q (apply str (repeat 300 "x"))
          wf {:psi.extension.workflow/id "run-1"
              :psi.extension.workflow/phase :paused
              :psi.extension.workflow/running? false
              :psi.extension.workflow/data
              {:run/task-id 42
               :run/entity-type :task
               :run/current-state :wait-user-confirmation
               :run/last-step "execute-task"
               :run/pause-reason :wait-user-confirmation
               :run/user-confirmation {:question long-q}}}
          lines (#'sut/widget-lines wf)
          q-line (some #(when (re-find #"❓" %) %) lines)]
      (is (some? q-line) "should have a question line")
      (is (<= (count q-line) 210) "question line should be truncated"))))

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
              "should print the question text"))))))

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
