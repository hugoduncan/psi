(ns extensions.work-on-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [extensions.work-on :as sut]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.mutations :as mutations]
   [psi.extension-test-helpers.nullable-api :as nullable]
   [psi.history.git :as git]))

(def ^:private session-query
  [:psi.agent-session/session-id
   :psi.agent-session/session-name
   :psi.agent-session/session-file
   :psi.agent-session/worktree-path
   :psi.agent-session/system-prompt
   :psi.agent-session/host-sessions
   :git.worktree/current
   :git.worktree/list])

(defn- with-session-query
  [result-map]
  (fn [q]
    (cond
      (= session-query q) result-map
      (= [:git.branch/default-branch] q)
      {:git.branch/default-branch {:branch "main" :source :fallback}}
      :else {})))

(defn- worktree-ff-state
  [state]
  (fn [ctx branch]
    (cond
      (= "/repo/feature-x" (:repo-dir ctx))
      (if (instance? clojure.lang.IDeref state)
        (and (= branch "main") (= :after @state))
        (= branch "main"))

      (= "/repo/main" (:repo-dir ctx))
      (= branch "feature-x")

      :else false)))

(defn- run-work-command! [state command]
  ((get-in @state [:commands command :handler]) ""))

(defn- appended-message-texts [calls]
  (->> calls
       (filter #(= 'psi.extension/append-message (first %)))
       (map (comp :content second))
       vec))

(defn- create-two-session-context []
  (let [ctx (session/create-context {:persist? false
                                     :mutations mutations/all-mutations})
        s1  (session/new-session-in! ctx nil {:session-name "one"})
        s2  (session/new-session-in! ctx (:session-id s1) {:session-name "two"})]
    [ctx (:session-id s1) (:session-id s2)]))

(defn- run-git!
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
                                :query-fn (with-session-query {})})]
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
                                :query-fn (with-session-query
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
                                :query-fn (with-session-query
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
                                :query-fn (with-session-query
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
                                :query-fn (with-session-query
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
                                :query-fn (with-session-query
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
                                :query-fn (with-session-query
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

(deftest work-on-tool-happy-path-test
  (testing "work-on tool shares the operational path, returns tool shape, and does not append transcript messages"
    (let [mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
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
                                :query-fn (with-session-query
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
                                :query-fn (with-session-query
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
                                :query-fn (with-session-query
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

(defn- make-runtime-work-on-api
  [ctx load-session-id active-session-id ext-path mutate-calls]
  (let [reg (:extension-registry ctx)
        _   (ext/register-extension-in! reg ext-path)
        runtime-fns* (assoc (runtime-fns/make-extension-runtime-fns ctx load-session-id ext-path)
                            :query-fn
                            (fn [req]
                              (let [sid0  (or runtime-fns/*active-extension-session-id* load-session-id)
                                    sid   (if (and (map? req) (contains? req :query))
                                            (or (:session-id req) sid0)
                                            sid0)
                                    query (if (and (map? req) (contains? req :query))
                                            (:query req)
                                            req)]
                                (cond
                                  (= query session-query)
                                  {:psi.agent-session/session-id sid
                                   :psi.agent-session/worktree-path (if (= sid active-session-id) "/repo/two" "/repo/one")
                                   :psi.agent-session/system-prompt "prompt"
                                   :psi.agent-session/host-sessions []
                                   :git.worktree/current {:git.worktree/path (if (= sid active-session-id) "/repo/two" "/repo/one")
                                                          :git.worktree/branch-name "main"
                                                          :git.worktree/current? true}
                                   :git.worktree/list [{:git.worktree/path (if (= sid active-session-id) "/repo/two" "/repo/one")
                                                        :git.worktree/branch-name "main"
                                                        :git.worktree/current? true}]}

                                  (= query [:git.branch/default-branch])
                                  {:git.branch/default-branch {:branch "main" :source :fallback}}

                                  :else {})))
                            :mutate-fn
                            (fn [op params]
                              (swap! mutate-calls conj [op params])
                              (cond
                                (= op 'psi.extension/register-command)
                                (do
                                  (ext/register-command-in! reg ext-path (assoc (:opts params) :name (:name params)))
                                  {:psi.extension/command-names (vec (ext/command-names-in reg))})

                                (= op 'psi.extension/register-tool)
                                (do
                                  (ext/register-tool-in! reg ext-path (:tool params))
                                  {:psi.extension/registered-tool? true})

                                (= op 'git.worktree/add!)
                                {:success true
                                 :path (get-in params [:input :path])
                                 :branch (get-in params [:input :branch])
                                 :head "abc123"}

                                (= op 'psi.extension/set-worktree-path)
                                {:psi.agent-session/worktree-path (:worktree-path params)}

                                (= op 'psi.extension/create-session)
                                {:psi.agent-session/session-id "s-created"
                                 :psi.agent-session/session-name (:session-name params)
                                 :psi.agent-session/worktree-path (:worktree-path params)}

                                (= op 'psi.extension/append-message)
                                {:psi.extension/message params}

                                :else nil))
                            :notify-fn nil)]
    {:reg reg
     :api (ext/create-extension-api reg ext-path runtime-fns*)}))

(deftest work-on-command-follows-active-session-after-new-test
  (testing "/work-on dispatched from a new session mutates that active session, not the extension load session"
    (let [[ctx s1 s2] (create-two-session-context)
          ext-path     "/ext/test/work_on.clj"
          mutate-calls (atom [])
          {:keys [api]} (make-runtime-work-on-api ctx s1 s2 ext-path mutate-calls)
          _            (sut/init api)
          result       (commands/dispatch-in ctx s2 "/work-on active session target" {:supports-session-tree? false})]
      (is (= :extension-cmd (:type result)))
      ((:handler result) (:args result))
      (let [set-worktree-call (some #(when (= 'psi.extension/set-worktree-path (first %)) %) @mutate-calls)]
        (is (= 'psi.extension/set-worktree-path (first set-worktree-call)))
        (is (= s2 (get-in set-worktree-call [1 :session-id]))
            "work-on must update the active session worktree-path after /new")))))

(deftest work-on-tool-follows-active-session-after-new-test
  (testing "work-on tool mutates the active session, not the extension load session"
    (let [[ctx s1 s2] (create-two-session-context)
          ext-path     "/ext/test/work_on.clj"
          mutate-calls (atom [])
          {:keys [api reg]} (make-runtime-work-on-api ctx s1 s2 ext-path mutate-calls)
          _            (sut/init api)
          tool         (ext/get-tool-in reg "work-on")]
      (binding [runtime-fns/*active-extension-session-id* s2]
        ((:execute tool) {"description" "active session target"}))
      (let [set-worktree-call (some #(when (= 'psi.extension/set-worktree-path (first %)) %) @mutate-calls)]
        (is (= 'psi.extension/set-worktree-path (first set-worktree-call)))
        (is (= s2 (get-in set-worktree-call [1 :session-id]))
            "work-on tool must update the active session worktree-path after /new")))))

(deftest work-on-command-with-remote-base-ref-integration-test
  (testing "/work-on --base origin/master should create the new branch from the remote-tracking ref, not current HEAD"
    (let [base-dir     (str (java.nio.file.Files/createTempDirectory "psi-work-on-remote-base-"
                                                                     (make-array java.nio.file.attribute.FileAttribute 0)))
          remote-dir   (str (java.io.File. base-dir "remote.git"))
          seed-dir     (str (java.io.File. base-dir "seed"))
          clone-dir    (str (java.io.File. base-dir "clone"))
          repo-dir     (str (java.io.File. clone-dir))
          mutate-calls (atom [])]
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
                                  :query-fn (with-session-query
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
              "new branch should not auto-track the base ref when created from origin/master"))))))

(deftest work-done-and-rebase-commands-test
  (testing "/work-done fast-forwards onto the cached default branch, switches sessions, and /work-rebase emits notifications"
    (let [mutate-calls (atom [])
          switched     (atom [])
          merge-params (atom nil)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
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
      (with-redefs [git/branch-tip-merged-into-current? (worktree-ff-state nil)
                    git/current-branch (fn [_ctx] "main")
                    git/current-commit (fn [_ctx] "main-sha")]
        (sut/init api)
        (run-work-command! state "work-done")
        (run-work-command! state "work-rebase"))
      (is (= ["main-s"] @switched))
      (is (= "/repo/main" (get-in @merge-params [:git/context :cwd]))
          "merge must execute in the main worktree context")
      (is (re-find #"Fast-forwarded `feature-x` into `main`" (first (appended-message-texts @mutate-calls))))
      (is (re-find #"Rebased `feature-x` onto `main`" (second (appended-message-texts @mutate-calls))))))

  (testing "/work-done creates a main-worktree session when none exists"
    (let [mutate-calls (atom [])
          created      (atom [])
          switched     (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
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
      (with-redefs [git/branch-tip-merged-into-current? (worktree-ff-state nil)
                    git/current-branch (fn [_ctx] "main")
                    git/current-commit (fn [_ctx] "main-sha")]
        (sut/init api)
        (run-work-command! state "work-done"))
      (is (= [{:psi.agent-session/session-id "s-main-created"
               :psi.agent-session/session-name "main"
               :psi.agent-session/worktree-path "/repo/main"}]
             @created))
      (is (= ["s-main-created"] @switched))
      (is (re-find #"Fast-forwarded `feature-x` into `main`" (first (appended-message-texts @mutate-calls)))))))

(deftest work-done-auto-rebase-success-test
  (testing "/work-done auto-rebases with a forked sync agent when ff is not yet possible"
    (let [mutate-calls (atom [])
          chain-calls  (atom [])
          remove-calls (atom 0)
          ff-state     (atom :before)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
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
      (with-redefs [git/branch-tip-merged-into-current? (worktree-ff-state ff-state)
                    git/current-branch (fn [_ctx] "main")
                    git/current-commit (fn [_ctx] "main-sha")]
        (sut/init api)
        (run-work-command! state "work-done"))
      (is (= 1 (count @chain-calls)))
      (is (= "agent" (get-in (first @chain-calls) [:steps 0 :tool])))
      (is (= "create" (get-in (first @chain-calls) [:steps 0 :args "action"])))
      (is (= "sync" (get-in (first @chain-calls) [:steps 0 :args "mode"])))
      (is (= true (get-in (first @chain-calls) [:steps 0 :args "fork_session"])))
      (is (= 1 @remove-calls))
      (is (re-find #"after automatic rebase" (first (appended-message-texts @mutate-calls)))))))

(deftest work-done-auto-rebase-failure-test
  (testing "/work-done stops with an informative message when automatic rebase fails"
    (let [mutate-calls (atom [])
          remove-calls (atom 0)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
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
        (run-work-command! state "work-done"))
      (is (= 0 @remove-calls))
      (is (= "automatic rebase onto `main` failed: agent failed"
             (first (appended-message-texts @mutate-calls)))))))

(deftest work-done-merge-verification-failure-test
  (testing "/work-done preserves the worktree when merge verification fails"
    (let [mutate-calls (atom [])
          remove-calls (atom 0)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
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
        (run-work-command! state "work-done"))
      (is (= 0 @remove-calls) "worktree removal must not run when merge is not verified")
      (let [msg (first (appended-message-texts @mutate-calls))]
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
                                :query-fn (with-session-query
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
      (run-work-command! state "work-done")
      (is (= "already on main worktree; nothing to do"
             (first (appended-message-texts @mutate-calls)))))))

(deftest work-main-worktree-guards-and-status-test
  (testing "/work-rebase rejects execution on the main worktree"
    (let [mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
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
             (first (appended-message-texts @mutate-calls))))))

  (testing "/work-status renders linked worktrees and marks the current linked worktree"
    (let [mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
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
      (let [msg (first (appended-message-texts @mutate-calls))]
        (is (re-find #"Active worktrees:" msg))
        (is (re-find #"- /repo/feature-x \[feature-x\] \(current\)" msg))
        (is (re-find #"- /repo/bug-y \[bug-y\]" msg))
        (is (not (re-find #"- /repo/main \[main\]" msg))))))

  (testing "/work-status renders none when no linked worktrees exist"
    (let [mutate-calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/work_on.clj"
                                :query-fn (with-session-query
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
      (is (= "Active worktrees:\n(none)" (first (appended-message-texts @mutate-calls)))))))
