(ns psi.agent-session.commands-builtin-specs-test
  "Task 205: regression locks that the dispatch-routing projections and
   `/help` rendering are derived coherently from the single built-in command
   spec table. Split out of `commands-test` to keep that file under the
   file-length limit."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.commands.builtin-specs :as bspec]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]))

;; ── Test helper ─────────────────────────────────────────────
(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- make-test-ctx
  "Create a minimal session context for testing commands.
   Returns [ctx session-id]."
  ([] (make-test-ctx {}))
  ([opts]
   (create-session-context
    {:session-defaults (merge {:model {:provider "anthropic"
                                       :id       "test-model"
                                       :reasoning false}
                               :system-prompt "test prompt"}
                              opts)
     :cwd (test-support/temp-cwd)
     :persist? false})))

(def ^:private test-ai-model
  {:provider :anthropic :id "test-model" :name "Test"})

(def ^:private cmd-opts
  {:oauth-ctx nil
   :ai-model test-ai-model
   :supports-session-tree? true})

;; ── Slice 1: single spec-table projections (task 205) ───────

(def ^:private snapshot-exact-command-handlers
  "Snapshot of the literal exact-command-handler map prior to deriving it from
   the single spec table — regression lock that the projection is unchanged."
  {"/quit" :quit
   "/exit" :quit
   "/new" :new
   "/resume" :resume
   "/status" :status
   "/history" :history
   "/help" :help
   "/?" :help
   "/prompts" :prompts
   "/skills" :skills
   "/worktree" :worktree
   "/logout" :logout
   "/reload-models" :reload-models
   "/reload-prompts" :reload-prompts
   "/reload-extension-installs" :reload-extension-installs
   "/project-repl" :project-repl})

(def ^:private snapshot-prefixed-command-prefixes
  "Snapshot of the literal prefixed-command prefix set prior to deriving it from
   the single spec table. Order is not load-bearing (no prefix is a prefix of
   another under the dispatch matcher), so the regression lock compares as a
   set."
  #{"/tree" "/jobs" "/job" "/cancel-job" "/remember" "/model" "/thinking"
    "/speed" "/effort" "/login" "/project-repl"})

(def ^:private snapshot-builtin-command-names
  "Snapshot of the derived built-in command name set (bare names)."
  #{"quit" "exit" "new" "resume" "status" "history" "help" "?" "prompts"
    "skills" "worktree" "logout" "reload-models" "reload-prompts"
    "reload-extension-installs" "project-repl" "tree" "jobs" "job" "cancel-job"
    "remember" "model" "thinking" "speed" "effort" "login"})

(deftest exact-command-handlers-projection-unchanged-test
  (testing "derived exact-command-handlers equals the prior literal map"
    (is (= snapshot-exact-command-handlers
           bspec/exact-command-handlers))))

(deftest prefixed-command-prefixes-projection-unchanged-test
  (testing "derived prefixed-command-prefixes equals the prior literal set"
    (is (= snapshot-prefixed-command-prefixes
           (set bspec/prefixed-command-prefixes)))))

(deftest builtin-command-names-projection-unchanged-test
  (testing "derived builtin-command-names equals the prior set"
    (is (= snapshot-builtin-command-names
           bspec/builtin-command-names))))

(deftest project-repl-dual-kind-test
  (testing "/project-repl appears in BOTH derived projections"
    (is (contains? bspec/exact-command-handlers "/project-repl"))
    (is (contains? (set bspec/prefixed-command-prefixes) "/project-repl")))
  (testing "bare /project-repl dispatches via the exact handler"
    (let [[ctx session-id] (make-test-ctx)
          result (commands/dispatch-in ctx session-id "/project-repl" cmd-opts)]
      (is (= :text (:type result)))
      (is (str/includes? (:message result) "Project nREPL"))))
  (testing "/project-repl <args> dispatches via the prefixed case"
    (let [[ctx session-id] (make-test-ctx)
          result (commands/dispatch-in ctx session-id "/project-repl start" cmd-opts)]
      (is (= :text (:type result))))))

(deftest format-help-derived-from-spec-table-test
  (let [[ctx session-id] (make-test-ctx)
        message (:message (commands/dispatch-in ctx session-id "/help" cmd-opts))]
    (testing "built-in lines render in table order"
      (is (< (str/index-of message "  /quit — exit the session")
             (str/index-of message "  /status — show session diagnostics")))
      (is (< (str/index-of message "  /status — show session diagnostics")
             (str/index-of message "  /help — show this help"))))
    (testing ":usage arg-hints render inline before the em-dash"
      (is (str/includes? message "  /model [provider model-id [session|project|user]] — show current model or set model"))
      (is (str/includes? message "  /speed [normal|fast [session|project|user]] — show or set speed mode"))
      (is (str/includes? message "  /effort [low|medium|high|xhigh|none [session|project|user]] — show or set effort override")))
    (testing ":hide-in-help? entries are absent from help (aliases + /project-repl)"
      (is (not (str/includes? message "/?")))
      (is (not (str/includes? message "/exit")))
      (is (not (str/includes? message "/project-repl"))))
    (testing "non-routed /skill:name helper line stays literal"
      (is (str/includes? message "/skill:name — invoke a skill")))))

(deftest prefixed-case-branch-coherence-test
  (testing "prefixed spec-table keys equal the live dispatch-prefixed-command case branch keys"
    (let [prefixed-keys (set bspec/prefixed-command-prefixes)
          ;; Read the live branch set authored adjacent to the `case` form
          ;; (the single literal source of its branch keys), so this test locks
          ;; the real seam rather than a second static snapshot.
          case-keys @#'commands/prefixed-case-branches]
      (is (= prefixed-keys case-keys)
          "every prefixed table entry has a dispatch-prefixed-command case branch"))))
