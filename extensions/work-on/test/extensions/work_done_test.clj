(ns extensions.work-done-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.work-on :as sut]
   [extensions.work-on-test-support :as support]
   [psi.extension-test-helpers.nullable-api :as nullable]
   [psi.history.git :as git]))

(deftest work-done-and-rebase-commands-test
  (testing "/work-done fast-forwards onto the cached default branch, switches sessions, and /work-rebase emits notifications"
    (let [mutate-calls (atom [])
          switched     (atom [])
          merge-params (atom nil)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (support/with-session-query
                                            {:psi.agent-session/session-id "s2"
                                             :psi.agent-session/host-sessions [{:psi.session-info/id "main-s"
                                                                                :psi.session-info/worktree-path "/repo/main"
                                                                                :psi.session-info/name "main"}]
                                             :git.worktree/current {:git.worktree/path "/repo/feature-x"
                                                                    :git.worktree/branch-name "feature-x"}
                                             :git.worktree/list [{:git.worktree/path "/repo/main"
                                                                  :git.worktree/branch-name "main"}
                                                                 {:git.worktree/path "/repo/feature-x"
                                                                  :git.worktree/branch-name "feature-x"
                                                                  :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.branch/merge! (do (reset! merge-params params)
                                                                     {:merged true})
                                               git.worktree/remove! {:success true}
                                               git.branch/delete! {:deleted true}
                                               git.branch/rebase! {:success true}
                                               psi.extension/switch-session (do (swap! switched conj (:session-id params))
                                                                                {:psi.agent-session/session-id (:session-id params)})
                                               psi.extension/append-message {:psi.extension/message params}
                                               nil))})]
      (with-redefs [git/branch-tip-merged-into-current? (support/worktree-ff-state nil)
                    git/current-branch (fn [_ctx] "main")
                    git/current-commit (fn [_ctx] "main-sha")]
        (sut/init api)
        (support/run-work-command! state "work-done")
        (support/run-work-command! state "work-rebase"))
      (is (= ["main-s"] @switched))
      (is (= "/repo/main" (get-in @merge-params [:git/context :cwd]))
          "merge must execute in the main worktree context")
      (is (re-find #"Fast-forwarded `feature-x` into `main`" (first (support/appended-message-texts @mutate-calls))))
      (is (re-find #"Rebased `feature-x` onto `main`" (second (support/appended-message-texts @mutate-calls))))))

  (testing "/work-done creates a main-worktree session when none exists"
    (let [mutate-calls (atom [])
          created      (atom [])
          switched     (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (support/with-session-query
                                            {:psi.agent-session/session-id "s-feature"
                                             :psi.agent-session/system-prompt "prompt"
                                             :psi.agent-session/host-sessions []
                                             :git.worktree/current {:git.worktree/path "/repo/feature-x"
                                                                    :git.worktree/branch-name "feature-x"}
                                             :git.worktree/list [{:git.worktree/path "/repo/main"
                                                                  :git.worktree/branch-name "main"}
                                                                 {:git.worktree/path "/repo/feature-x"
                                                                  :git.worktree/branch-name "feature-x"
                                                                  :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.branch/merge! {:merged true}
                                               git.worktree/remove! {:success true}
                                               git.branch/delete! {:deleted true}
                                               psi.extension/create-session (let [sd {:psi.agent-session/session-id "s-main-created"
                                                                                      :psi.agent-session/session-name (:session-name params)
                                                                                      :psi.agent-session/worktree-path (:worktree-path params)}]
                                                                              (swap! created conj sd)
                                                                              sd)
                                               psi.extension/switch-session (do (swap! switched conj (:session-id params))
                                                                                {:psi.agent-session/session-id (:session-id params)})
                                               psi.extension/append-message {:psi.extension/message params}
                                               nil))})]
      (with-redefs [git/branch-tip-merged-into-current? (support/worktree-ff-state nil)
                    git/current-branch (fn [_ctx] "main")
                    git/current-commit (fn [_ctx] "main-sha")]
        (sut/init api)
        (support/run-work-command! state "work-done"))
      (is (= [{:psi.agent-session/session-id "s-main-created"
               :psi.agent-session/session-name "main"
               :psi.agent-session/worktree-path "/repo/main"}]
             @created))
      (is (= ["s-main-created"] @switched))
      (is (re-find #"Fast-forwarded `feature-x` into `main`" (first (support/appended-message-texts @mutate-calls)))))))

(deftest work-done-auto-rebase-success-test
  (testing "/work-done auto-rebases with a forked sync agent when ff is not yet possible"
    (let [mutate-calls (atom [])
          chain-calls  (atom [])
          remove-calls (atom 0)
          ff-state     (atom :before)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (support/with-session-query
                                            {:psi.agent-session/session-id "s-feature"
                                             :psi.agent-session/host-sessions [{:psi.session-info/id "main-s"
                                                                                :psi.session-info/worktree-path "/repo/main"
                                                                                :psi.session-info/name "main"}]
                                             :git.worktree/current {:git.worktree/path "/repo/feature-x"
                                                                    :git.worktree/branch-name "feature-x"}
                                             :git.worktree/list [{:git.worktree/path "/repo/main"
                                                                  :git.worktree/branch-name "main"}
                                                                 {:git.worktree/path "/repo/feature-x"
                                                                  :git.worktree/branch-name "feature-x"
                                                                  :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               psi.extension.tool/chain (do
                                                                          (swap! chain-calls conj params)
                                                                          (reset! ff-state :after)
                                                                          {:psi.extension.tool-plan/succeeded? true
                                                                           :psi.extension.tool-plan/results [{:id "work-done-rebase"
                                                                                                              :result {:content "rebase ok"
                                                                                                                       :is-error false}}]})
                                               git.branch/merge! {:merged true}
                                               git.worktree/remove! (do (swap! remove-calls inc)
                                                                        {:success true})
                                               git.branch/delete! {:deleted true}
                                               psi.extension/switch-session {:psi.agent-session/session-id "main-s"}
                                               psi.extension/append-message {:psi.extension/message params}
                                               nil))})]
      (with-redefs [git/branch-tip-merged-into-current? (support/worktree-ff-state ff-state)
                    git/current-branch (fn [_ctx] "main")
                    git/current-commit (fn [_ctx] "main-sha")]
        (sut/init api)
        (support/run-work-command! state "work-done"))
      (is (= 1 (count @chain-calls)))
      (is (= "agent" (get-in (first @chain-calls) [:steps 0 :tool])))
      (is (= "create" (get-in (first @chain-calls) [:steps 0 :args "action"])))
      (is (= "sync" (get-in (first @chain-calls) [:steps 0 :args "mode"])))
      (is (= true (get-in (first @chain-calls) [:steps 0 :args "fork_session"])))
      (is (= 1 @remove-calls))
      (is (re-find #"after automatic rebase" (first (support/appended-message-texts @mutate-calls)))))))

(deftest work-done-auto-rebase-failure-test
  (testing "/work-done stops with an informative message when automatic rebase fails"
    (let [mutate-calls (atom [])
          remove-calls (atom 0)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (support/with-session-query
                                            {:psi.agent-session/session-id "s-feature"
                                             :git.worktree/current {:git.worktree/path "/repo/feature-x"
                                                                    :git.worktree/branch-name "feature-x"}
                                             :git.worktree/list [{:git.worktree/path "/repo/main"
                                                                  :git.worktree/branch-name "main"}
                                                                 {:git.worktree/path "/repo/feature-x"
                                                                  :git.worktree/branch-name "feature-x"
                                                                  :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               psi.extension.tool/chain {:psi.extension.tool-plan/succeeded? false
                                                                         :psi.extension.tool-plan/error "agent failed"
                                                                         :psi.extension.tool-plan/results [{:id "work-done-rebase"
                                                                                                            :result {:content "rebase conflict"
                                                                                                                     :is-error true}}]}
                                               git.worktree/remove! (do (swap! remove-calls inc)
                                                                        {:success true})
                                               psi.extension/append-message {:psi.extension/message params}
                                               nil))})]
      (with-redefs [git/branch-tip-merged-into-current? (fn [_ctx _branch] false)]
        (sut/init api)
        (support/run-work-command! state "work-done"))
      (is (= 0 @remove-calls))
      (is (= "automatic rebase onto `main` failed: agent failed"
             (first (support/appended-message-texts @mutate-calls)))))))

(deftest work-done-merge-verification-failure-test
  (testing "/work-done preserves the worktree when merge verification fails"
    (let [mutate-calls (atom [])
          remove-calls (atom 0)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (support/with-session-query
                                            {:psi.agent-session/session-id "s-feature"
                                             :psi.agent-session/host-sessions [{:psi.session-info/id "main-s"
                                                                                :psi.session-info/worktree-path "/repo/main"
                                                                                :psi.session-info/name "main"}]
                                             :git.worktree/current {:git.worktree/path "/repo/feature-x"
                                                                    :git.worktree/branch-name "feature-x"}
                                             :git.worktree/list [{:git.worktree/path "/repo/main"
                                                                  :git.worktree/branch-name "main"}
                                                                 {:git.worktree/path "/repo/feature-x"
                                                                  :git.worktree/branch-name "feature-x"
                                                                  :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.branch/merge! {:merged true}
                                               git.worktree/remove! (do (swap! remove-calls inc)
                                                                        {:success true})
                                               git.branch/delete! {:deleted true}
                                               psi.extension/append-message {:psi.extension/message params}
                                               nil))})]
      (with-redefs [git/branch-tip-merged-into-current? (fn [ctx branch]
                                                          (cond
                                                            (= "/repo/feature-x" (:repo-dir ctx))
                                                            (= branch "main")
                                                            (= "/repo/main" (:repo-dir ctx)) false
                                                            :else false))
                    git/current-branch (fn [_ctx] "main")
                    git/current-commit (let [calls (atom -1)]
                                         (fn [_ctx]
                                           (case (swap! calls inc)
                                             0 "before-sha"
                                             1 "after-sha"
                                             "after-sha")))]
        (sut/init api)
        (support/run-work-command! state "work-done"))
      (is (= 0 @remove-calls) "worktree removal must not run when merge is not verified")
      (let [msg (first (support/appended-message-texts @mutate-calls))]
        (is (re-find #"merge did not update main; worktree preserved for safety" msg))
        (is (re-find #"source=feature-x" msg))
        (is (re-find #"merge-reported=true" msg))
        (is (re-find #"before-branch=main" msg))
        (is (re-find #"after-branch=main" msg))
        (is (re-find #"before-head=before-sha" msg))
        (is (re-find #"after-head=after-sha" msg))
        (is (re-find #"head-changed=true" msg))
        (is (re-find #"verification=branch tip not ancestor of target HEAD" msg))))))

(deftest work-done-main-worktree-guard-test
  (testing "/work-done rejects execution on the main worktree"
    (let [mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (support/with-session-query
                                            {:psi.agent-session/session-id "s-main"
                                             :git.worktree/current {:git.worktree/path "/repo/main"
                                                                    :git.worktree/branch-name "main"
                                                                    :git.worktree/current? true}
                                             :git.worktree/list [{:git.worktree/path "/repo/main"
                                                                  :git.worktree/branch-name "main"
                                                                  :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               psi.extension/append-message {:psi.extension/message params}
                                               nil))})]
      (sut/init api)
      (support/run-work-command! state "work-done")
      (is (= "already on main worktree; nothing to do"
             (first (support/appended-message-texts @mutate-calls)))))))

(deftest work-main-worktree-guards-and-status-test
  (testing "/work-rebase rejects execution on the main worktree"
    (let [mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (support/with-session-query
                                            {:psi.agent-session/session-id "s-main"
                                             :git.worktree/current {:git.worktree/path "/repo/main"
                                                                    :git.worktree/branch-name "main"
                                                                    :git.worktree/current? true}
                                             :git.worktree/list [{:git.worktree/path "/repo/main"
                                                                  :git.worktree/branch-name "main"
                                                                  :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               psi.extension/append-message {:psi.extension/message params}
                                               nil))})]
      (sut/init api)
      ((get-in @state [:commands "work-rebase" :handler]) "")
      (is (= "already on main worktree; nothing to rebase"
             (first (support/appended-message-texts @mutate-calls))))))

  (testing "/work-status renders linked worktrees and marks the current linked worktree"
    (let [mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (support/with-session-query
                                            {:psi.agent-session/session-id "s-feature"
                                             :git.worktree/current {:git.worktree/path "/repo/feature-x"
                                                                    :git.worktree/branch-name "feature-x"
                                                                    :git.worktree/current? true}
                                             :git.worktree/list [{:git.worktree/path "/repo/main"
                                                                  :git.worktree/branch-name "main"}
                                                                 {:git.worktree/path "/repo/feature-x"
                                                                  :git.worktree/branch-name "feature-x"
                                                                  :git.worktree/current? true}
                                                                 {:git.worktree/path "/repo/bug-y"
                                                                  :git.worktree/branch-name "bug-y"}]})
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               psi.extension/append-message {:psi.extension/message params}
                                               nil))})]
      (sut/init api)
      ((get-in @state [:commands "work-status" :handler]) "")
      (let [msg (first (support/appended-message-texts @mutate-calls))]
        (is (re-find #"Active worktrees:" msg))
        (is (re-find #"- /repo/feature-x \[feature-x\] \(current\)" msg))
        (is (re-find #"- /repo/bug-y \[bug-y\]" msg))
        (is (not (re-find #"- /repo/main \[main\]" msg))))))

  (testing "/work-status renders none when no linked worktrees exist"
    (let [mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (support/with-session-query
                                            {:psi.agent-session/session-id "s-main"
                                             :git.worktree/current {:git.worktree/path "/repo/main"
                                                                    :git.worktree/branch-name "main"
                                                                    :git.worktree/current? true}
                                             :git.worktree/list [{:git.worktree/path "/repo/main"
                                                                  :git.worktree/branch-name "main"
                                                                  :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               psi.extension/append-message {:psi.extension/message params}
                                               nil))})]
      (sut/init api)
      ((get-in @state [:commands "work-status" :handler]) "")
      (is (= "Active worktrees:\n(none)" (first (support/appended-message-texts @mutate-calls)))))))
