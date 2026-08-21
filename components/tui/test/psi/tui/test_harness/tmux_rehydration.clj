(ns psi.tui.test-harness.tmux-rehydration
  "Thinking rehydration scenario for TUI tmux integration tests."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.session-journal.codec :as codec]
   [psi.test-support.fs :as test-fs]
   [psi.tui.test-harness.tmux :as tmux])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

(def ^:private default-thinking-prefix "· Let me think about this carefully.")
(def ^:private default-answer-text "Recursion is when a function calls itself.")
(def ^:private default-empty-selector-text "(no sessions found)")

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "psi-tui-rehydrate-"
                                      (into-array FileAttribute []))))

(defn write-thinking-fixture!
  "Write a minimal .ndedn journal file to the session dir for `worktree-dir`
   under explicit `session-root`. Returns the fixture file path string."
  [session-root worktree-dir]
  (let [worktree-path (.getCanonicalPath (io/file worktree-dir))
        session-dir   (io/file session-root (str "--"
                                                 (-> worktree-path
                                                     (str/replace #"^[/\\]" "")
                                                     (str/replace #"[/\\:]" "-"))
                                                 "--"))
        _             (.mkdirs session-dir)
        session-id    (str (java.util.UUID/randomUUID))
        ts            (Instant/parse "2024-01-01T00:00:00Z")
        filename      (str (System/currentTimeMillis) "_" session-id ".ndedn")
        fixture       (io/file session-dir filename)
        header        {:type :session :version 4 :id session-id
                       :timestamp ts
                       :worktree-path worktree-path
                       :parent-session-id nil :parent-session nil}
        user-id       (str (java.util.UUID/randomUUID))
        user-entry    {:id user-id :parent-id nil
                       :timestamp ts
                       :kind :message
                       :data {:message {:role "user"
                                        :content [{:type :text :text "explain recursion"}]}}}
        asst-id       (str (java.util.UUID/randomUUID))
        asst-entry    {:id asst-id :parent-id user-id
                       :timestamp ts
                       :kind :message
                       :data {:message {:role "assistant"
                                        :content [{:type :thinking :text "Let me think about this carefully."}
                                                  {:type :text :text default-answer-text}]}}}]
    (spit fixture (str (codec/entry->line header) "\n"
                       (codec/entry->line user-entry) "\n"
                       (codec/entry->line asst-entry) "\n"))
    (.getAbsolutePath fixture)))

(defn- failure
  [target reason]
  {:status        :failed
   :reason        reason
   :session-name  (:session-name target)
   :pane-id       (:pane-id target)
   :pane-snapshot (tmux/sanitize-pane-text (tmux/capture-pane target))})

(defn run-thinking-rehydration-scenario!
  "Launch a real TUI in tmux from a temp fixture worktree, open `/resume`,
   select the persisted session fixture, and assert that thinking/text content
   is rehydrated into the visible transcript."
  [{:keys [session-name
           working-dir
           launch-command
           startup-timeout-ms
           step-timeout-ms
           ready-markers
           keep-session-on-failure?]
    :or {startup-timeout-ms tmux/default-startup-timeout-ms
         step-timeout-ms    tmux/default-step-timeout-ms
         ready-markers      tmux/default-ready-markers
         keep-session-on-failure? false}}]
  (let [preflight (tmux/tmux-preflight-result)]
    (if (not= :ok (:status preflight))
      preflight
      (let [worktree-dir   (or working-dir (.getAbsolutePath (tmp-dir)))
            tmux-cwd       (.getCanonicalPath (io/file "."))
            session-root   (str (io/file worktree-dir ".sessions-root"))
            fixture-path   (write-thinking-fixture! session-root worktree-dir)
            session-name*  (or session-name (tmux/unique-session-name))
            launch         (or launch-command
                               (str "PSI_SESSION_ROOT=" (pr-str session-root) " "
                                    (tmux/launcher-command)
                                    " --cwd "
                                    (pr-str worktree-dir)
                                    " -- --tui"))]
        (try
          (let [target (tmux/start-session! {:session-name   session-name*
                                             :working-dir    tmux-cwd
                                             :launch-command launch})
                result (cond
                         (not (tmux/wait-for-any-marker target ready-markers startup-timeout-ms))
                         (assoc (failure target :startup-timeout)
                                :fixture-path fixture-path
                                :working-dir worktree-dir
                                :session-root session-root)

                         :else
                         (do
                           (tmux/send-line! target "/resume")
                           (cond
                             (not (tmux/wait-for-marker-absent target default-empty-selector-text step-timeout-ms))
                             (assoc (failure target :resume-selector-missing-session)
                                    :fixture-path fixture-path
                                    :working-dir worktree-dir
                                    :session-root session-root)

                             :else
                             (do
                               (tmux/send-key! target "Enter")
                               (cond
                                 (not (tmux/wait-for-marker target default-thinking-prefix step-timeout-ms))
                                 (assoc (failure target :rehydrated-thinking-not-visible)
                                        :fixture-path fixture-path)

                                 (not (tmux/wait-for-marker target default-answer-text step-timeout-ms))
                                 (assoc (failure target :rehydrated-answer-not-visible)
                                        :fixture-path fixture-path)

                                 :else
                                 {:status       :passed
                                  :session-name session-name*
                                  :pane-id      (:pane-id target)})))))]
            (when (or (= :passed (:status result))
                      (not keep-session-on-failure?))
              (tmux/kill-session-if-exists! session-name*))
            result)
          (catch Throwable t
            (let [target {:session-name session-name*
                          :pane-id      (tmux/primary-pane-id session-name*)}
                  result {:status        :failed
                          :reason        :exception
                          :session-name  session-name*
                          :pane-id       (:pane-id target)
                          :error-message (or (ex-message t) (str t))
                          :pane-snapshot (tmux/sanitize-pane-text (tmux/capture-pane target))}]
              (when-not keep-session-on-failure?
                (tmux/kill-session-if-exists! session-name*))
              result))
          (finally
            (test-fs/delete-recursively! (io/file session-root))
            (test-fs/delete-recursively! (io/file worktree-dir))))))))
