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

(deftest builtin-command-specs-well-formed-test
  ;; TT3: lock the per-entry well-formedness invariant of the single source
  ;; (design "Spec-entry field set" / AC2). The projections silently assume
  ;; the entry shape: an empty-`:kinds` entry is named-but-unroutable, and an
  ;; `:exact`-without-`:handler` entry projects into exact-command-handlers as
  ;; `"/name" → nil`. R1/R2 only lock the projection↔`case` seam, not the entry
  ;; shape itself, so both malformations are currently representable in the
  ;; single source yet caught by no test. Assert directly on every entry.
  (testing ":kinds is a non-empty subset of #{:exact :prefixed}"
    (doseq [[k spec] bspec/builtin-command-specs]
      (is (set? (:kinds spec)) (str k " :kinds must be a set"))
      (is (seq (:kinds spec)) (str k " :kinds must be non-empty"))
      (is (every? #{:exact :prefixed} (:kinds spec))
          (str k " :kinds must be ⊆ #{:exact :prefixed}"))))
  (testing ":exact ∈ :kinds ⇒ :handler is present"
    (doseq [[k spec] bspec/builtin-command-specs
            :when (contains? (:kinds spec) :exact)]
      (is (some? (:handler spec))
          (str k " is :exact and must carry a :handler"))))
  (testing ":description is a non-blank string on every entry"
    (doseq [[k spec] bspec/builtin-command-specs]
      (is (string? (:description spec)) (str k " :description must be a string"))
      (is (not (str/blank? (:description spec)))
          (str k " :description must be non-blank")))))

(deftest exact-command-handlers-projection-unchanged-test
  ;; Static snapshot lock: proves the *derived* exact-command-handlers map
  ;; matches the pre-task literal. This is NOT a live-`case` coherence check —
  ;; the dispatch* exact-command `case` seam is locked separately by
  ;; exact-case-branch-coherence-test (reading the live exact-case-branches def).
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
    (testing "non-routed /skill:name helper line stays literal"
      (is (str/includes? message "/skill:name — invoke a skill")))))

(deftest format-help-block-line-order-test
  ;; TT5: lock the *full* built-in help-block line order to the single source
  ;; (AC3 "unchanged in order"). format-help-derived-from-spec-table-test only
  ;; asserts the leading (quit < status < help) ordering, leaving the ~18
  ;; interleaved lines — including every `:usage`-bearing prefixed entry — with
  ;; no positional assertion; a middle-of-table reorder would pass every other
  ;; test. Assert the rendered block's full line sequence equals the
  ;; spec-table-ordered, `:hide-in-help?`-filtered projection.
  (let [line-for (fn [k {:keys [description usage]}]
                   (str "  " k " " (when usage (str usage " ")) "— " description))
        expected (for [[k spec] bspec/builtin-command-specs
                       :when (not (:hide-in-help? spec))]
                   (line-for k spec))]
    (is (= (vec expected)
           (str/split-lines (bspec/builtin-help-block)))
        "built-in help block lines must equal the spec-table-ordered, hidden-filtered projection")))

(deftest project-repl-exact-first-precedence-test
  ;; TT6: lock dual-kind `/project-repl` *exact-first* dispatch precedence
  ;; (design "Dispatch-kind representation"), not merely behaviour.
  ;; project-repl-dual-kind-test cannot prove the bare form is served by the
  ;; exact path because both the exact handler and the prefixed `case` route to
  ;; the same dispatch-project-nrepl-command — a behaviour assertion is
  ;; path-blind. Lock the seam instead.
  ;;
  ;; NOTE: `commands/prefixed-command` DOES match the bare form
  ;; (`(= trimmed prefix)` is an explicit branch), so exact-first precedence is
  ;; NOT decided by the prefixed matcher declining the bare form; it is decided
  ;; by dispatch*'s `(or (case (exact-command-handler …) …)
  ;; (dispatch-prefixed-command …))` short-circuiting on the bare form's exact
  ;; handler. The discriminating seam fact is that the bare form HAS an exact
  ;; handler (so the `or` serves it via the exact path) while the `<args>` form
  ;; does NOT (so it is exclusively prefixed-routed).
  (testing "bare /project-repl has an exact handler (exact path serves it first)"
    (is (= :project-repl (@#'commands/exact-command-handler "/project-repl"))))
  (testing "/project-repl <args> has no exact handler (exclusively prefixed-routed)"
    (is (nil? (@#'commands/exact-command-handler "/project-repl start"))))
  (testing "the prefixed matcher reaches the /project-repl <args> form"
    (is (= "/project-repl" (@#'commands/prefixed-command "/project-repl start")))))

(deftest builtin-help-block-hide-in-help-projection-test
  ;; Lock the `:hide-in-help?` projection against the built-in block directly
  ;; (not the whole `/help` message): hidden entries' lines are absent, shown
  ;; entries' lines are present. Asserting against `builtin-help-block` proves
  ;; *built-in-block omission* rather than mere global absence — a substring
  ;; check on the full message would false-fail if a future description carried
  ;; the literal token, and only proves the token appears nowhere at all.
  (let [block (bspec/builtin-help-block)
        line-for (fn [k {:keys [description usage]}]
                   (str "  " k " " (when usage (str usage " ")) "— " description))]
    (testing ":hide-in-help? entries' lines are absent from the block"
      (doseq [[k spec] bspec/builtin-command-specs
              :when (:hide-in-help? spec)]
        (is (not (str/includes? block (line-for k spec)))
            (str k " line must be omitted from the built-in help block"))))
    (testing "shown entries' lines are present in the block"
      (doseq [[k spec] bspec/builtin-command-specs
              :when (not (:hide-in-help? spec))]
        (is (str/includes? block (line-for k spec))
            (str k " line must be present in the built-in help block"))))))

(deftest prefixed-case-branch-coherence-test
  (testing "prefixed spec-table keys equal the live dispatch-prefixed-command case branch keys"
    (let [prefixed-keys (set bspec/prefixed-command-prefixes)
          ;; Read the live branch set authored adjacent to the `case` form
          ;; (the single literal source of its branch keys), so this test locks
          ;; the real seam rather than a second static snapshot.
          case-keys @#'commands/prefixed-case-branches]
      (is (= prefixed-keys case-keys)
          "every prefixed table entry has a dispatch-prefixed-command case branch"))))

(deftest exact-case-branch-coherence-test
  (testing "exact spec-table handler values equal the live dispatch* case branch keys"
    (let [handler-values (set (vals bspec/exact-command-handlers))
          ;; Read the live branch set authored adjacent to the exact-command
          ;; `case` in dispatch* (the single literal source of its branch keys),
          ;; so this test locks the real seam rather than a static snapshot —
          ;; symmetric with prefixed-case-branch-coherence-test.
          case-keys @#'commands/exact-case-branches]
      (is (= handler-values case-keys)
          "every exact table entry's :handler has a dispatch* case branch"))))
