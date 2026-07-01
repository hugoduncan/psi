(ns extensions.work-on-tool-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.work-on :as sut]
   [extensions.work-on-test-support :as support]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.extension-test-helpers.nullable-api :as nullable]
   [psi.tool-registry.registry :as tool-registry]))

(deftest work-on-tool-happy-path-test
  (testing "work-on tool shares the operational path, returns tool shape, and does not append transcript messages"
    (let [mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (support/with-session-query
                                            {:psi.agent-session/session-id "s-main"
                                             :psi.agent-session/worktree-path "/repo/main"
                                             :psi.agent-session/system-prompt "prompt"
                                             :psi.agent-session/host-sessions [{:psi.session-info/id "s-main"
                                                                                :psi.session-info/worktree-path "/repo/main"
                                                                                :psi.session-info/name "main"}]
                                             :git.worktree/current {:git.worktree/path "/repo/main"
                                                                    :git.worktree/branch-name "main"}
                                             :git.worktree/list [{:git.worktree/path "/repo/main"
                                                                  :git.worktree/branch-name "main"
                                                                  :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.worktree/add! {:success true
                                                                  :path "/repo/fix-footer-not-displayed"
                                                                  :branch "fix-footer-not-displayed"
                                                                  :head "abc123"}
                                               psi.extension/set-worktree-path {:psi.agent-session/worktree-path (:worktree-path params)}
                                               psi.extension/create-session {:psi.agent-session/session-id "s-created"
                                                                             :psi.agent-session/session-name (:session-name params)
                                                                             :psi.agent-session/worktree-path (:worktree-path params)}
                                               psi.extension/append-message {:psi.extension/message params}
                                               nil))})]
      (sut/init api)
      (let [tool   (get-in @state [:tools "work-on"])
            result ((:execute tool) {"description" "Fix footer not displayed"})]
        (is (= ['git.worktree/add!
                'psi.extension/set-worktree-path
                'psi.extension/create-session]
               (mapv first @mutate-calls)))
        (is (= nil
               (get-in (second (first @mutate-calls)) [:input :base_ref])))
        (is (= "Working in `/repo/fix-footer-not-displayed` on branch `fix-footer-not-displayed`"
               (:content result)))
        (is (false? (:is-error result)))
        (is (= {:ok? true
                :action :work-on
                :reused? false
                :worktree-path "/repo/fix-footer-not-displayed"
                :branch-name "fix-footer-not-displayed"
                :session-id "s-created"
                :session-name "Fix footer not displayed"}
               (:details result)))
        (is (not-any? #(= 'psi.extension/append-message (first %)) @mutate-calls)))))

  (testing "work-on tool threads an explicit base_branch into new worktree creation"
    (let [mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (support/with-session-query
                                            {:psi.agent-session/session-id "s-main"
                                             :psi.agent-session/worktree-path "/repo/main"
                                             :psi.agent-session/system-prompt "prompt"
                                             :psi.agent-session/host-sessions [{:psi.session-info/id "s-main"
                                                                                :psi.session-info/worktree-path "/repo/main"
                                                                                :psi.session-info/name "main"}]
                                             :git.worktree/current {:git.worktree/path "/repo/main"
                                                                    :git.worktree/branch-name "main"}
                                             :git.worktree/list [{:git.worktree/path "/repo/main"
                                                                  :git.worktree/branch-name "main"
                                                                  :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.worktree/add! {:success true
                                                                  :path "/repo/fix-footer-not-displayed"
                                                                  :branch "fix-footer-not-displayed"
                                                                  :head "abc123"}
                                               psi.extension/set-worktree-path {:psi.agent-session/worktree-path (:worktree-path params)}
                                               psi.extension/create-session {:psi.agent-session/session-id "s-created"
                                                                             :psi.agent-session/session-name (:session-name params)
                                                                             :psi.agent-session/worktree-path (:worktree-path params)}
                                               nil))})]
      (sut/init api)
      (let [tool   (get-in @state [:tools "work-on"])
            result ((:execute tool) {"description" "Fix footer not displayed"
                                     "base_branch" "release/1.2"})]
        (is (= "release/1.2"
               (get-in (second (first @mutate-calls)) [:input :base_ref])))
        (is (= {:ok? true
                :action :work-on
                :reused? false
                :worktree-path "/repo/fix-footer-not-displayed"
                :branch-name "fix-footer-not-displayed"
                :session-id "s-created"
                :session-name "Fix footer not displayed"
                :requested-base-branch "release/1.2"
                :base-branch-applied? true}
               (:details result)))))))

(deftest work-on-tool-usage-error-test
  (testing "work-on tool returns canonical error shape and does not append transcript messages"
    (let [mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               psi.extension/append-message {:psi.extension/message params}
                                               nil))})]
      (sut/init api)
      (let [tool   (get-in @state [:tools "work-on"])
            result ((:execute tool) {"description" "   "})]
        (is (= "usage: /work-on <description>\n       /work-on --base <branch> <description>" (:content result)))
        (is (true? (:is-error result)))
        (is (= {:ok? false
                :action :work-on
                :error "usage: /work-on <description>\n       /work-on --base <branch> <description>"}
               (:details result)))
        (is (empty? @mutate-calls)))))

  (testing "blank tool base_branch is invalid"
    (let [mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             nil)})]
      (sut/init api)
      (let [tool   (get-in @state [:tools "work-on"])
            result ((:execute tool) {"description" "Fix footer not displayed"
                                     "base_branch" "   "})]
        (is (= "base_branch must be a non-blank string" (:content result)))
        (is (true? (:is-error result)))
        (is (= {:ok? false
                :action :work-on
                :error "base_branch must be a non-blank string"}
               (:details result)))
        (is (empty? @mutate-calls))))))

(deftest work-on-tool-reuses-existing-worktree-session-test
  (testing "work-on tool reuses an existing worktree/session and returns parity details without appending transcript messages"
    (let [switched     (atom [])
          mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (support/with-session-query
                                            {:psi.agent-session/session-id "s-main"
                                             :psi.agent-session/worktree-path "/repo/main"
                                             :psi.agent-session/system-prompt "prompt"
                                             :psi.agent-session/host-sessions [{:psi.session-info/id "s-main"
                                                                                :psi.session-info/worktree-path "/repo/main"
                                                                                :psi.session-info/name "main"}
                                                                               {:psi.session-info/id "s-existing"
                                                                                :psi.session-info/worktree-path "/repo/fix-repeated-thinking-output"
                                                                                :psi.session-info/name "Fix repeated thinking output in emacs"}]
                                             :git.worktree/current {:git.worktree/path "/repo/main"
                                                                    :git.worktree/branch-name "main"}
                                             :git.worktree/list [{:git.worktree/path "/repo/main"
                                                                  :git.worktree/branch-name "main"
                                                                  :git.worktree/current? true}
                                                                 {:git.worktree/path "/repo/fix-repeated-thinking-output"
                                                                  :git.worktree/branch-name "fix-repeated-thinking-output"}]})
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.worktree/add! {:success false
                                                                  :error "worktree path already exists"}
                                               psi.extension/set-worktree-path {:psi.agent-session/worktree-path (:worktree-path params)}
                                               psi.extension/switch-session (do (swap! switched conj "s-existing")
                                                                                {:psi.agent-session/session-id "s-existing"})
                                               psi.extension/append-message {:psi.extension/message params}
                                               nil))})]
      (sut/init api)
      (let [tool   (get-in @state [:tools "work-on"])
            result ((:execute tool) {"description" "fix repeated thinking output in emacs"})]
        (is (= ["s-existing"] @switched))
        (is (= ['git.worktree/add!
                'psi.extension/set-worktree-path
                'psi.extension/switch-session]
               (mapv first @mutate-calls)))
        (is (= {:ok? true
                :action :work-on
                :reused? true
                :worktree-path "/repo/fix-repeated-thinking-output"
                :branch-name "fix-repeated-thinking-output"
                :session-id "s-existing"
                :session-name "Fix repeated thinking output in emacs"}
               (:details result)))
        (is (not-any? #(= 'psi.extension/append-message (first %)) @mutate-calls)))))

  (testing "requested base branch is recorded but not applied when reusing an existing worktree/session"
    (let [switched     (atom [])
          mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (support/with-session-query
                                            {:psi.agent-session/session-id "s-main"
                                             :psi.agent-session/worktree-path "/repo/main"
                                             :psi.agent-session/system-prompt "prompt"
                                             :psi.agent-session/host-sessions [{:psi.session-info/id "s-main"
                                                                                :psi.session-info/worktree-path "/repo/main"
                                                                                :psi.session-info/name "main"}
                                                                               {:psi.session-info/id "s-existing"
                                                                                :psi.session-info/worktree-path "/repo/fix-repeated-thinking-output"
                                                                                :psi.session-info/name "Fix repeated thinking output in emacs"}]
                                             :git.worktree/current {:git.worktree/path "/repo/main"
                                                                    :git.worktree/branch-name "main"}
                                             :git.worktree/list [{:git.worktree/path "/repo/main"
                                                                  :git.worktree/branch-name "main"
                                                                  :git.worktree/current? true}
                                                                 {:git.worktree/path "/repo/fix-repeated-thinking-output"
                                                                  :git.worktree/branch-name "fix-repeated-thinking-output"}]})
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.worktree/add! {:success false
                                                                  :error "worktree path already exists"}
                                               psi.extension/set-worktree-path {:psi.agent-session/worktree-path (:worktree-path params)}
                                               psi.extension/switch-session (do (swap! switched conj "s-existing")
                                                                                {:psi.agent-session/session-id "s-existing"})
                                               nil))})]
      (sut/init api)
      (let [tool   (get-in @state [:tools "work-on"])
            result ((:execute tool) {"description" "fix repeated thinking output in emacs"
                                     "base_branch" "release/1.2"})]
        (is (= ["s-existing"] @switched))
        (is (= "release/1.2"
               (get-in (second (first @mutate-calls)) [:input :base_ref])))
        (is (= {:ok? true
                :action :work-on
                :reused? true
                :worktree-path "/repo/fix-repeated-thinking-output"
                :branch-name "fix-repeated-thinking-output"
                :session-id "s-existing"
                :session-name "Fix repeated thinking output in emacs"
                :requested-base-branch "release/1.2"
                :base-branch-applied? false}
               (:details result)))))))

(deftest work-on-tool-follows-active-session-after-new-test
  (testing "work-on tool mutates the active session, not the extension load session"
    (let [[ctx s1 s2] (support/create-two-session-context)
          ext-path     "/ext/test/work_on.clj"
          mutate-calls (atom [])
          {:keys [api reg]} (support/make-runtime-work-on-api ctx s1 s2 ext-path mutate-calls)
          _            (sut/init api)
          tool         (tool-registry/get-tool-in reg "work-on")]
      (binding [runtime-fns/*active-extension-session-id* s2]
        ((:execute tool) {"description" "active session target"}))
      (let [set-worktree-call (some #(when (= 'psi.extension/set-worktree-path (first %)) %) @mutate-calls)]
        (is (= 'psi.extension/set-worktree-path (first set-worktree-call)))
        (is (= s2 (get-in set-worktree-call [1 :session-id]))
            "work-on tool must update the active session worktree-path after /new")))))

