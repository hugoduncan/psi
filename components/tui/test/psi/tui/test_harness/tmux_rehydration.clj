(ns psi.tui.test-harness.tmux-rehydration
  "Thinking rehydration scenario for TUI tmux integration tests."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.tui.test-harness.tmux :as tmux]))

(def ^:private default-thinking-marker "· ")
(def ^:private default-selector-marker "Enter=select")

(defn- encode-path-for-session-dir
  "Encode a filesystem path the same way persistence/session-dir-for does:
   strip leading slash, replace / and : with -."
  [path]
  (-> (str path)
      (str/replace #"^[/\\]" "")
      (str/replace #"[/\\:]" "-")))

(defn- sessions-root
  []
  (str (System/getProperty "user.home") "/.psi/agent/sessions"))

(defn write-thinking-fixture!
  "Write a minimal .ndedn journal file to the session dir for `tmpdir`.
   Returns the fixture file path string."
  [tmpdir]
  (let [encoded     (encode-path-for-session-dir tmpdir)
        session-dir (io/file (sessions-root) (str "--" encoded "--"))
        _           (.mkdirs session-dir)
        session-id  (str (java.util.UUID/randomUUID))
        ts          (java.util.Date/from (java.time.Instant/parse "2024-01-01T00:00:00Z"))
        filename    (str (System/currentTimeMillis) "_" session-id ".ndedn")
        fixture     (io/file session-dir filename)
        header      {:type :session :version 4 :id session-id
                     :timestamp ts
                     :worktree-path tmpdir
                     :parent-session-id nil :parent-session nil}
        user-id     (str (java.util.UUID/randomUUID))
        user-entry  {:id user-id :parent-id nil
                     :timestamp ts
                     :kind :message
                     :data {:message {:role "user"
                                      :content [{:type :text :text "explain recursion"}]}}}
        asst-id     (str (java.util.UUID/randomUUID))
        asst-entry  {:id asst-id :parent-id user-id
                     :timestamp ts
                     :kind :message
                     :data {:message {:role "assistant"
                                      :content [{:type :thinking :text "Let me think about this carefully."}
                                                {:type :text :text "Recursion is when a function calls itself."}]}}}]
    (spit fixture (str (pr-str header) "\n"
                       (pr-str user-entry) "\n"
                       (pr-str asst-entry) "\n"))
    (str (.getAbsolutePath fixture))))

(defn delete-thinking-fixture!
  "Delete the fixture file and the session dir if empty."
  [fixture-path]
  (when fixture-path
    (let [f   (io/file fixture-path)
          dir (.getParentFile f)]
      (.delete f)
      (when (and dir (.isDirectory dir) (empty? (.list dir)))
        (.delete dir)))))

(defn run-thinking-rehydration-scenario!
  "Prove that thinking rehydration and the · style are observable through a real
   terminal boundary.

   Scenario: write fixture → boot TUI (working-dir = tmpdir) → ready →
   /resume → selector → Enter → wait for '· ' → assert → /quit → clean exit."
  [{:keys [session-name
           working-dir
           launch-command
           startup-timeout-ms
           step-timeout-ms
           ready-markers
           thinking-marker
           selector-marker
           keep-session-on-failure?]
    :or {;; Launch from the repo/worktree so babashka picks up the repo bb.edn
         ;; classpath, but pass --cwd <tmpdir> through the launcher so the TUI
         ;; session itself is scoped to the fixture directory.
         launch-command     nil
         startup-timeout-ms tmux/default-startup-timeout-ms
         step-timeout-ms    tmux/default-step-timeout-ms
         ready-markers      tmux/default-ready-markers
         thinking-marker    default-thinking-marker
         selector-marker    default-selector-marker
         keep-session-on-failure? false}}]
  (let [preflight (tmux/tmux-preflight-result)]
    (cond
      (not= :ok (:status preflight))
      preflight

      (tmux/ci-env?)
      {:status :skipped
       :reason :ci-flaky-rehydration
       :warning "Skipping tmux rehydration scenario in CI: live /resume transcript projection is timing-sensitive there; unit tests cover transcript rehydration semantics."}

      :else
      (let [tmpdir        (or working-dir
                              (str (System/getProperty "java.io.tmpdir")
                                   "/psi-thinking-it-" (System/currentTimeMillis)))
            _             (.mkdirs (io/file tmpdir))
            fixture-path  (write-thinking-fixture! tmpdir)
            session-name* (or session-name (tmux/unique-session-name))
            launch        (or launch-command
                              (str (tmux/worktree-launch-command)
                                   " --cwd " (pr-str tmpdir)))]
        (try
          (let [target (tmux/start-session! {:session-name   session-name*
                                             :working-dir    (str (.getCanonicalPath (io/file ".")))
                                             :launch-command launch})
                result (cond
                         (not (tmux/wait-for-any-marker target ready-markers startup-timeout-ms))
                         {:status :failed :reason :startup-timeout
                          :session-name session-name* :pane-id (:pane-id target)
                          :pane-snapshot (tmux/sanitize-pane-text (tmux/capture-pane target))}

                         :else
                         (do
                           (tmux/send-line! target "/resume")
                           (cond
                             (not (tmux/wait-for-marker target selector-marker step-timeout-ms))
                             {:status :failed :reason :selector-timeout
                              :session-name session-name* :pane-id (:pane-id target)
                              :pane-snapshot (tmux/sanitize-pane-text (tmux/capture-pane target))}

                             :else
                             (do
                               (tmux/send-key! target "Enter")
                               (cond
                                 (not (tmux/wait-for-marker target thinking-marker step-timeout-ms))
                                 {:status :failed :reason :thinking-marker-timeout
                                  :session-name session-name* :pane-id (:pane-id target)
                                  :pane-snapshot (tmux/sanitize-pane-text (tmux/capture-pane target))}

                                 :else
                                 (do
                                   (tmux/send-line! target "/quit")
                                   (if (tmux/wait-for-java-exit target step-timeout-ms)
                                     {:status       :passed
                                      :session-name session-name*
                                      :pane-id      (:pane-id target)}
                                     {:status :failed :reason :quit-timeout
                                      :session-name session-name* :pane-id (:pane-id target)
                                      :pane-snapshot (tmux/sanitize-pane-text (tmux/capture-pane target))})))))))]
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
            (delete-thinking-fixture! fixture-path)))))))
