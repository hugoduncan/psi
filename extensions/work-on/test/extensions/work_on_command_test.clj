(ns extensions.work-on-command-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [extensions.work-on :as sut]
   [extensions.work-on-test-support :as support]
   [psi.agent-session.commands :as commands]
   [psi.extension-test-helpers.nullable-api :as nullable]
   [psi.history.git :as git]))

(defn run-git!
  [repo-dir & args]
  (let [pb   (ProcessBuilder. ^java.util.List (vec (cons "git" args)))
        _    (.directory pb (java.io.File. ^String repo-dir))
        _    (doto (.environment pb)
               (.put "GIT_AUTHOR_NAME" "Test Author")
               (.put "GIT_AUTHOR_EMAIL" "test@example.com")
               (.put "GIT_COMMITTER_NAME" "Test Author")
               (.put "GIT_COMMITTER_EMAIL" "test@example.com"))
        proc (.start pb)
        out  (slurp (.getInputStream proc))
        err  (slurp (.getErrorStream proc))
        exit (.waitFor proc)]
    (when (pos? exit)
      (throw (ex-info "git helper failed" {:args args :err err :exit exit})))
    out))

(defn delete-recursively!
  "Recursively delete `path` (file or directory tree). No-op if it does not
  exist. Local to this namespace: `psi.agent-session.test-support`'s
  equivalent helper lives on agent-session's `test` classpath, which is not
  on this namespace's classpath (only agent-session's `src` path is a
  declared dep here)."
  [path]
  (let [f (java.io.File. (str path))]
    (when (.exists f)
      (doseq [child (reverse (file-seq f))]
        (.delete ^java.io.File child)))))

(deftest mechanical-slug-test
  (testing "slug is mechanical and limited to four significant words"
    (is (= {:raw-description "Fix the footer not displayed after tree session switch"
            :terms ["fix" "footer" "not" "displayed"]
            :slug "fix-footer-not-displayed"
            :branch-name "fix-footer-not-displayed"}
           (sut/mechanical-slug "Fix the footer not displayed after tree session switch"))))

  (testing "all-stopword input falls back to work"
    (is (= "work"
           (:slug (sut/mechanical-slug "the and of to"))))))

(deftest target-worktree-path-test
  (testing "derives sibling-of-main placement when current worktree is not nested under main"
    (is (= "/repos/project/fix-foo"
           (#'sut/target-worktree-path "/repos/project/bare-checkout"
                                       "/repos/project/task-a"
                                       "fix-foo"))))

  (testing "derives sibling-of-main placement when invoked from the sibling-main checkout itself"
    (is (= "/repos/project/fix-foo"
           (#'sut/target-worktree-path "/repos/project/bare-checkout"
                                       "/repos/project/bare-checkout"
                                       "fix-foo"))))

  (testing "derives nested placement when current worktree is an immediate child of main checkout"
    (is (= "/repos/project/fix-foo"
           (#'sut/target-worktree-path "/repos/project"
                                       "/repos/project/task-a"
                                       "fix-foo"))))

  (testing "keeps the defined narrow behavior when invoked from the nested-layout main checkout"
    (is (= "/repos/fix-foo"
           (#'sut/target-worktree-path "/repos/project"
                                       "/repos/project"
                                       "fix-foo")))))

(deftest init-registers-work-commands-and-tool-test
  (testing "extension registers /work-* commands and work-on tool"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"})]
      (sut/init api)
      (is (= #{"work-on" "work-done" "work-rebase" "work-status"}
             (set (keys (:commands @state)))))
      (is (= 1 (count (get-in @state [:handlers "session_switch"]))))
      (is (= "work-on" (get-in @state [:tools "work-on" :name])))
      (is (= "Work On" (get-in @state [:tools "work-on" :label])))
      (is (= ["description"] (get-in @state [:tools "work-on" :parameters :required])))
      (is (= {:type "string"
              :description "Optional base branch to use when creating a new branch/worktree"}
             (get-in @state [:tools "work-on" :parameters :properties "base_branch"])))
      (is (fn? (get-in @state [:tools "work-on" :execute]))))))

(deftest session-switch-handler-returns-nil-test
  (testing "session_switch handler returns nil (safe for extension dispatch)"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path  "/test/work_on.clj"
                                :query-fn (support/with-session-query {})})]
      (sut/init api)
      (let [handler (first (get-in @state [:handlers "session_switch"]))]
        (is (nil? (handler {:reason :new})))))))

(deftest work-on-command-happy-path-test
  (testing "/work-on creates worktree, updates session worktree-path, and appends one AI-visible assistant summary"
    (let [created-session (atom nil)
          mutate-calls    (atom [])
          printed         (atom nil)
          created-op      (atom nil)
          created-params  (atom nil)
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
                                               psi.extension/append-message {:psi.extension/message params}
                                               psi.extension/create-session {:psi.agent-session/session-id "s-created"
                                                                             :psi.agent-session/session-name (:session-name params)
                                                                             :psi.agent-session/worktree-path (:worktree-path params)}
                                               nil))})]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))]
        (sut/init api)
        (let [handler (get-in @state [:commands "work-on" :handler])]
          (handler "Fix footer not displayed")
          (let [[op params] (nth @mutate-calls 2)]
            (reset! created-op op)
            (reset! created-params params)
            (reset! created-session {:session-id "s-created"
                                     :session-name (:session-name params)
                                     :worktree-path (:worktree-path params)
                                     :system-prompt (:system-prompt params)}))
          (is (= 'git.worktree/add! (ffirst @mutate-calls)))
          (is (= "/repo/fix-footer-not-displayed"
                 (get-in (second (first @mutate-calls)) [:input :path])))
          (is (= "fix-footer-not-displayed"
                 (get-in (second (first @mutate-calls)) [:input :branch])))
          (is (nil? (get-in (second (first @mutate-calls)) [:input :base_ref])))
          (is (= ['git.worktree/add!
                  'psi.extension/set-worktree-path
                  'psi.extension/create-session
                  'psi.extension/append-message]
                 (mapv first @mutate-calls)))
          (is (= {:session-id "s-main"
                  :worktree-path "/repo/fix-footer-not-displayed"
                  :ext-path "/test/work_on.clj"}
                 (second (second @mutate-calls))))
          (is (= 'psi.extension/create-session @created-op))
          (is (= "s-created" (:session-id @created-session)))
          (is (= "/repo/fix-footer-not-displayed" (:worktree-path @created-session)))
          (is (= "Fix footer not displayed" (:session-name @created-session)))
          (is (nil? (:system-prompt @created-session)))
          (is (= {:role "assistant"
                  :content "Working in `/repo/fix-footer-not-displayed` on branch `fix-footer-not-displayed`"
                  :ext-path "/test/work_on.clj"}
                 (second (last @mutate-calls))))
          (is (nil? @printed))))))

  (testing "/work-on --base <branch> <description> threads the base branch into creation"
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
                                               psi.extension/append-message {:psi.extension/message params}
                                               psi.extension/create-session {:psi.agent-session/session-id "s-created"
                                                                             :psi.agent-session/session-name (:session-name params)
                                                                             :psi.agent-session/worktree-path (:worktree-path params)}
                                               nil))})]
      (sut/init api)
      ((get-in @state [:commands "work-on" :handler]) "--base release/1.2 Fix footer not displayed")
      (is (= "release/1.2"
             (get-in (second (first @mutate-calls)) [:input :base_ref])))
      (is (= {:role "assistant"
              :content "Working in `/repo/fix-footer-not-displayed` on branch `fix-footer-not-displayed`"
              :ext-path "/test/work_on.clj"}
             (second (last @mutate-calls)))))))

(deftest work-on-command-nested-linked-layout-test
  (testing "/work-on derives nested target placement when current worktree is directly under the main checkout"
    (let [mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (support/with-session-query
                                            {:psi.agent-session/session-id "s-task-a"
                                             :psi.agent-session/worktree-path "/repo/project/task-a"
                                             :psi.agent-session/system-prompt "prompt"
                                             :psi.agent-session/host-sessions [{:psi.session-info/id "s-task-a"
                                                                                :psi.session-info/worktree-path "/repo/project/task-a"
                                                                                :psi.session-info/name "task-a"}]
                                             :git.worktree/current {:git.worktree/path "/repo/project/task-a"
                                                                    :git.worktree/branch-name "task-a"
                                                                    :git.worktree/current? true}
                                             :git.worktree/list [{:git.worktree/path "/repo/project"
                                                                  :git.worktree/branch-name "main"}
                                                                 {:git.worktree/path "/repo/project/task-a"
                                                                  :git.worktree/branch-name "task-a"
                                                                  :git.worktree/current? true}]})
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.worktree/add! {:success true
                                                                  :path "/repo/project/fix-footer-not-displayed"
                                                                  :branch "fix-footer-not-displayed"
                                                                  :head "abc123"}
                                               psi.extension/set-worktree-path {:psi.agent-session/worktree-path (:worktree-path params)}
                                               psi.extension/append-message {:psi.extension/message params}
                                               psi.extension/create-session {:psi.agent-session/session-id "s-created"
                                                                             :psi.agent-session/session-name (:session-name params)
                                                                             :psi.agent-session/worktree-path (:worktree-path params)}
                                               nil))})]
      (sut/init api)
      ((get-in @state [:commands "work-on" :handler]) "Fix footer not displayed")
      (is (= "/repo/project/fix-footer-not-displayed"
             (get-in (second (first @mutate-calls)) [:input :path])))
      (is (= "/repo/project/fix-footer-not-displayed"
             (get-in (second (nth @mutate-calls 1)) [:worktree-path])))
      (is (= "/repo/project/fix-footer-not-displayed"
             (get-in (second (nth @mutate-calls 2)) [:worktree-path]))))))

(deftest work-on-command-reuses-existing-worktree-test
  (testing "/work-on creates a worktree from an existing branch when the slug branch already exists"
    (let [printed      (atom nil)
          create-calls (atom [])
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
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.worktree/add! (let [input (:input params)]
                                                                   (swap! create-calls conj input)
                                                                   (if (:create-branch input)
                                                                     {:success false
                                                                      :error "branch already exists"}
                                                                     {:success true
                                                                      :path (:path input)
                                                                      :branch (:branch input)
                                                                      :head "abc123"}))
                                               psi.extension/create-session {:psi.agent-session/session-id "s-branch-existing"
                                                                             :psi.agent-session/session-name (:session-name params)
                                                                             :psi.agent-session/worktree-path (:worktree-path params)}
                                               nil))})]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))]
        (sut/init api)
        ((get-in @state [:commands "work-on" :handler]) "fix repeated thinking output")
        (is (= [{:path "/repo/fix-repeated-thinking-output"
                 :branch "fix-repeated-thinking-output"
                 :base_ref nil
                 :create-branch true}
                {:path "/repo/fix-repeated-thinking-output"
                 :branch "fix-repeated-thinking-output"
                 :base_ref nil
                 :create-branch false}]
               @create-calls))
        (is (nil? @printed)))))

  (testing "existing-branch attach with explicit base branch records requested base branch but does not apply it"
    (let [tool-results  (atom nil)
          create-calls  (atom [])
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
                                             (case op
                                               git.branch/default {:branch "main" :source :fallback}
                                               git.worktree/add! (let [input (:input params)]
                                                                   (swap! create-calls conj input)
                                                                   (if (:create-branch input)
                                                                     {:success false
                                                                      :error "branch already exists"}
                                                                     {:success true
                                                                      :path (:path input)
                                                                      :branch (:branch input)
                                                                      :head "abc123"}))
                                               psi.extension/create-session {:psi.agent-session/session-id "s-branch-existing"
                                                                             :psi.agent-session/session-name (:session-name params)
                                                                             :psi.agent-session/worktree-path (:worktree-path params)}
                                               psi.extension/set-worktree-path {:psi.agent-session/worktree-path (:worktree-path params)}
                                               nil))})]
      (sut/init api)
      (let [tool (get-in @state [:tools "work-on"])
            result ((:execute tool) {"description" "fix repeated thinking output"
                                     "base_branch" "release/1.2"})]
        (reset! tool-results result)
        (is (= [{:path "/repo/fix-repeated-thinking-output"
                 :branch "fix-repeated-thinking-output"
                 :base_ref "release/1.2"
                 :create-branch true}
                {:path "/repo/fix-repeated-thinking-output"
                 :branch "fix-repeated-thinking-output"
                 :base_ref nil
                 :create-branch false}]
               @create-calls))
        (is (= {:ok? true
                :action :work-on
                :reused? true
                :worktree-path "/repo/fix-repeated-thinking-output"
                :branch-name "fix-repeated-thinking-output"
                :session-id "s-branch-existing"
                :session-name "fix repeated thinking output"
                :requested-base-branch "release/1.2"
                :base-branch-applied? false}
               (:details @tool-results))))))

  (testing "/work-on reuses an existing worktree, updates worktree-path, switches session, and appends one AI-visible assistant summary"
    (let [printed      (atom nil)
          switched     (atom [])
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
                                               psi.extension/append-message {:psi.extension/message params}
                                               psi.extension/switch-session (do (swap! switched conj "s-existing")
                                                                                {:psi.agent-session/session-id "s-existing"})
                                               nil))})]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))]
        (sut/init api)
        ((get-in @state [:commands "work-on" :handler]) "fix repeated thinking output in emacs")
        (is (= ["s-existing"] @switched))
        (is (= ['git.worktree/add!
                'psi.extension/set-worktree-path
                'psi.extension/switch-session
                'psi.extension/append-message]
               (mapv first @mutate-calls)))
        (is (= {:session-id "s-main"
                :worktree-path "/repo/fix-repeated-thinking-output"
                :ext-path "/test/work_on.clj"}
               (second (second @mutate-calls))))
        (is (= {:role "assistant"
                :content "Working in `/repo/fix-repeated-thinking-output` on branch `fix-repeated-thinking-output`"
                :ext-path "/test/work_on.clj"}
               (second (last @mutate-calls))))
        (is (nil? @printed))))))

(deftest parse-work-on-command-args-test
  (testing "parses plain description"
    (is (= {:ok? true
            :request {:description "Fix footer not displayed"}}
           (#'sut/parse-work-on-command-args "Fix footer not displayed"))))

  (testing "parses leading --base branch description form"
    (is (= {:ok? true
            :request {:description "Fix footer not displayed"
                      :base-branch "release/1.2"}}
           (#'sut/parse-work-on-command-args "--base release/1.2 Fix footer not displayed"))))

  (testing "reports specific error when --base is missing a branch and description"
    (is (= {:ok? false
            :error "usage error: --base requires a branch and description\n\nusage: /work-on <description>\n       /work-on --base <branch> <description>"}
           (#'sut/parse-work-on-command-args "--base"))))

  (testing "reports specific error when --base has a branch but no description"
    (is (= {:ok? false
            :error "usage error: --base requires a branch and description\n\nusage: /work-on <description>\n       /work-on --base <branch> <description>"}
           (#'sut/parse-work-on-command-args "--base release/1.2"))))

  (testing "reports usage when description is missing"
    (is (= {:ok? false
            :error "usage: /work-on <description>\n       /work-on --base <branch> <description>"}
           (#'sut/parse-work-on-command-args "   ")))))

(deftest work-on-command-usage-error-test
  (testing "/work-on without description appends usage once into AI-visible conversation"
    (let [mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               psi.extension/append-message {:psi.extension/message params}
                                               nil))})
          printed (atom nil)]
      (with-redefs [println (fn [& xs] (reset! printed (apply str xs)))]
        (sut/init api)
        ((get-in @state [:commands "work-on" :handler]) "   ")
        (is (nil? @printed))
        (is (= [['psi.extension/append-message
                 {:role "assistant"
                  :content "usage: /work-on <description>\n       /work-on --base <branch> <description>"
                  :ext-path "/test/work_on.clj"}]]
               @mutate-calls))))

    (testing "/work-on with malformed --base usage appends a specific parse error once"
      (let [mutate-calls (atom [])
            {:keys [api state]} (nullable/create-nullable-extension-api
                                 {:path "/test/work_on.clj"
                                  :mutate-fn (fn [op params]
                                               (swap! mutate-calls conj [op params])
                                               (case op
                                                 psi.extension/append-message {:psi.extension/message params}
                                                 nil))})]
        (sut/init api)
        ((get-in @state [:commands "work-on" :handler]) "--base")
        (is (= [['psi.extension/append-message
                 {:role "assistant"
                  :content "usage error: --base requires a branch and description\n\nusage: /work-on <description>\n       /work-on --base <branch> <description>"
                  :ext-path "/test/work_on.clj"}]]
               @mutate-calls)))))

  (testing "/work-on --base <branch> without description appends the same specific parse error once"
    (let [mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :mutate-fn (fn [op params]
                                             (swap! mutate-calls conj [op params])
                                             (case op
                                               psi.extension/append-message {:psi.extension/message params}
                                               nil))})]
      (sut/init api)
      ((get-in @state [:commands "work-on" :handler]) "--base release/1.2")
      (is (= [['psi.extension/append-message
               {:role "assistant"
                :content "usage error: --base requires a branch and description\n\nusage: /work-on <description>\n       /work-on --base <branch> <description>"
                :ext-path "/test/work_on.clj"}]]
             @mutate-calls)))))

(deftest work-on-command-follows-active-session-after-new-test
  (testing "/work-on dispatched from a new session mutates that active session, not the extension load session"
    (let [[ctx s1 s2] (support/create-two-session-context)
          ext-path     "/ext/test/work_on.clj"
          mutate-calls (atom [])
          {:keys [api]} (support/make-runtime-work-on-api ctx s1 s2 ext-path mutate-calls)
          _            (sut/init api)
          result       (commands/dispatch-in ctx s2 "/work-on active session target" {:supports-session-tree? false})]
      (is (= :extension-cmd (:type result)))
      ((:handler result) (:args result))
      (let [set-worktree-call (some #(when (= 'psi.extension/set-worktree-path (first %)) %) @mutate-calls)]
        (is (= 'psi.extension/set-worktree-path (first set-worktree-call)))
        (is (= s2 (get-in set-worktree-call [1 :session-id]))
            "work-on must update the active session worktree-path after /new")))))

(deftest work-on-command-with-remote-base-ref-integration-test
  (testing "/work-on --base origin/master should create the new branch from the remote-tracking ref, not current HEAD"
    (let [base-dir     (str (java.nio.file.Files/createTempDirectory "psi-work-on-remote-base-"
                                                                     (make-array java.nio.file.attribute.FileAttribute 0)))
          remote-dir   (str (java.io.File. base-dir "remote.git"))
          seed-dir     (str (java.io.File. base-dir "seed"))
          clone-dir    (str (java.io.File. base-dir "clone"))
          repo-dir     (str (java.io.File. clone-dir))
          mutate-calls (atom [])]
      (try
        (.mkdirs (java.io.File. seed-dir))
        (run-git! base-dir "init" "--bare" remote-dir)
        (run-git! base-dir "clone" remote-dir seed-dir)
        (spit (str seed-dir "/README.md") "# seeded\n")
        (run-git! seed-dir "add" "README.md")
        (run-git! seed-dir "commit" "-m" "seed main")
        (run-git! seed-dir "push" "origin" "HEAD:master")
        (run-git! base-dir "clone" remote-dir clone-dir)
        (run-git! clone-dir "checkout" "-b" "main" "origin/master")
        (run-git! clone-dir "branch" "--set-upstream-to=origin/master" "main")
        ;; Advance local HEAD without updating origin/master so the test can distinguish
        ;; whether work-on actually uses the requested base ref or silently falls back to HEAD.
        (spit (str clone-dir "/LOCAL_ONLY.md") "local only\n")
        (run-git! clone-dir "add" "LOCAL_ONLY.md")
        (run-git! clone-dir "commit" "-m" "advance local main only")
        (let [origin-master-sha (str/trim (run-git! clone-dir "rev-parse" "origin/master"))
              local-head-sha    (str/trim (run-git! clone-dir "rev-parse" "HEAD"))
              {:keys [api state]} (nullable/create-nullable-extension-api
                                   {:path "/test/work_on.clj"
                                    :query-fn (support/with-session-query
                                                {:psi.agent-session/session-id "s-main"
                                                 :psi.agent-session/worktree-path repo-dir
                                                 :psi.agent-session/system-prompt "prompt"
                                                 :psi.agent-session/host-sessions [{:psi.session-info/id "s-main"
                                                                                    :psi.session-info/worktree-path repo-dir
                                                                                    :psi.session-info/name "main"}]
                                                 :git.worktree/current {:git.worktree/path repo-dir
                                                                        :git.worktree/branch-name "main"
                                                                        :git.worktree/current? true}
                                                 :git.worktree/list [{:git.worktree/path repo-dir
                                                                      :git.worktree/branch-name "main"
                                                                      :git.worktree/current? true}]})
                                    :mutate-fn (fn [op params]
                                                 (swap! mutate-calls conj [op params])
                                                 (case op
                                                   git.branch/default {:branch "main" :source :fallback}
                                                   git.worktree/add! (git/worktree-add (git/create-context repo-dir)
                                                                                       (:input params))
                                                   psi.extension/set-worktree-path {:psi.agent-session/worktree-path (:worktree-path params)}
                                                   psi.extension/append-message {:psi.extension/message params}
                                                   psi.extension/create-session {:psi.agent-session/session-id "s-created"
                                                                                 :psi.agent-session/session-name (:session-name params)
                                                                                 :psi.agent-session/worktree-path (:worktree-path params)}
                                                   nil))})]
          (is (not= origin-master-sha local-head-sha)
              "test setup must diverge local HEAD from origin/master")
          (sut/init api)
          ((get-in @state [:commands "work-on" :handler]) "--base origin/master fix flakey test")
          (let [worktree-add-call (first @mutate-calls)
                worktree-path     (get-in worktree-add-call [1 :input :path])
                created-ctx       (git/create-context worktree-path)
                head-sha          (git/current-commit created-ctx)
                branch-name       (git/current-branch created-ctx)]
            (is (= 'git.worktree/add! (first worktree-add-call)))
            (is (= "origin/master" (get-in worktree-add-call [1 :input :base_ref])))
            (is (= "fix-flakey-test" branch-name))
            (is (= origin-master-sha head-sha)
                "fresh worktree branch should start at origin/master, not the current local HEAD")
            (is (not= local-head-sha head-sha)
                "if this equals local HEAD then the requested base ref was ignored")
            (is (= "fatal: no upstream configured for branch 'fix-flakey-test'"
                   (try
                     (str/trim (run-git! worktree-path "rev-parse" "--abbrev-ref" "--symbolic-full-name" "@{u}"))
                     (catch clojure.lang.ExceptionInfo e
                       (-> e ex-data :err str/trim))))
                "new branch should not auto-track the base ref when created from origin/master")))
        (finally
          (delete-recursively! base-dir)))
      (testing "cleanup wiring: base-dir is removed once the try/finally above completes"
        ;; Guards the try/finally cleanup wiring itself, not the work-on
        ;; behaviour above: a regression that dropped this try/finally's
        ;; delete-recursively! call would not otherwise be caught by bb test.
        (is (not (.exists (java.io.File. base-dir)))
            "base-dir should be deleted once the try/finally above completes")))))

