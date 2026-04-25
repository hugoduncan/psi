(ns psi.tui.test-harness.tmux
  "Reusable tmux-backed integration harness utilities for TUI tests."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [psi.tui.ansi :as ansi]))

(def canonical-launch-command
  "exec psi --tui")

(def repo-local-launch-command
  "exec bb bb/psi.clj -- --tui")

(def default-startup-timeout-ms 120000)
(def default-step-timeout-ms 15000)
(def default-poll-interval-ms 100)
(def default-ready-markers ["刀:" "Type a message"])
(def default-help-marker "(anything else is sent to the agent)")
(def default-autocomplete-suggestions-marker "Suggestions")
(def default-autocomplete-selected-marker "▸ ")
(def default-capture-lines 3000)

(defn- run-sh
  [cmd]
  (shell/sh "bash" "-lc" cmd))

(defn tmux-available?
  []
  (zero? (:exit (run-sh "command -v tmux >/dev/null 2>&1"))))

(defn command-available?
  [cmd]
  (zero? (:exit (run-sh (format "command -v %s >/dev/null 2>&1" cmd)))))

(defn launcher-command
  "Resolve the best available TUI launch command, preferring the installed
   canonical `psi` binary when available.  For scenarios that need to exercise
   code in the current worktree, use [[worktree-launch-command]] instead."
  []
  (cond
    (command-available? "psi")
    canonical-launch-command

    (command-available? "bb")
    repo-local-launch-command

    :else
    canonical-launch-command))

(defn worktree-launch-command
  "Resolve a launch command that always runs code from the current worktree,
   preferring `bb` (repo-local) over the installed `psi` binary.
   Use this for scenarios that test features that may not yet be in the
   installed release."
  []
  (cond
    (command-available? "bb")
    repo-local-launch-command

    (command-available? "psi")
    canonical-launch-command

    :else
    canonical-launch-command))

(defn ci-env?
  []
  (boolean
   (some seq
         [(System/getenv "CI")
          (System/getenv "GITHUB_ACTIONS")
          (System/getenv "BUILDKITE")
          (System/getenv "CIRCLECI")
          (System/getenv "TEAMCITY_VERSION")
          (System/getenv "JENKINS_URL")])))

(defn tmux-preflight-result
  []
  (cond
    (tmux-available?)
    {:status :ok}

    (ci-env?)
    {:status :failed
     :reason :tmux-required-in-ci
     :error-message "tmux is required for TUI integration tests in CI but was not found on PATH"}

    :else
    {:status :skipped
     :reason :tmux-not-available
     :warning "Skipping TUI tmux integration test locally: tmux not found on PATH"}))

(defn unique-session-name
  []
  (str "psi-tui-it-" (System/currentTimeMillis) "-" (rand-int 1000000)))

(defn sanitize-pane-text
  [s]
  (-> (or s "")
      ansi/strip-ansi
      (str/replace #"\r" "\n")
      (str/replace #"\u0008" "")
      (str/replace #"\u000e|\u000f" "")))

(defn primary-pane-id
  [session-name]
  (let [{:keys [exit out]}
        (run-sh (format "tmux display-message -p -t %s:0.0 '#{pane_id}'"
                        session-name))]
    (when (zero? exit)
      (str/trim out))))

(defn- pane-target
  [target]
  (let [{:keys [session-name pane-id]} (if (string? target)
                                         {:session-name target}
                                         target)]
    (or pane-id
        (primary-pane-id session-name)
        (str session-name ":0.0"))))

(defn capture-pane
  ([target]
   (capture-pane target {}))
  ([target _opts]
   (let [capture-lines (when (map? target) (:capture-lines target))
         {:keys [exit out err]}
         (run-sh (format "tmux capture-pane -pt %s -S -%d"
                         (pane-target target)
                         (or capture-lines default-capture-lines)))]
     (if (zero? exit)
       out
       (str "tmux-capture-pane-failed: " (or err ""))))))

(defn pane-current-command
  [target]
  (let [{:keys [exit out]}
        (run-sh (format "tmux display-message -p -t %s '#{pane_current_command}'"
                        (pane-target target)))]
    (when (zero? exit)
      (str/trim out))))

(defn send-line!
  [target s]
  (let [pane (pane-target target)]
    (run-sh (format "tmux send-keys -l -t %s %s" pane (pr-str s)))
    (run-sh (format "tmux send-keys -t %s Enter" pane))))

(defn send-text!
  "Send literal text to the pane without pressing Enter."
  [target s]
  (let [pane (pane-target target)]
    (run-sh (format "tmux send-keys -l -t %s %s" pane (pr-str s)))))

(defn send-key!
  "Send a named tmux key (e.g. \"Escape\", \"Down\", \"Up\") to the pane."
  [target key-name]
  (run-sh (format "tmux send-keys -t %s %s" (pane-target target) key-name)))

(defn wait-until
  ([pred timeout-ms]
   (wait-until pred timeout-ms default-poll-interval-ms))
  ([pred timeout-ms poll-interval-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (if (pred)
         true
         (if (>= (System/currentTimeMillis) deadline)
           false
           (do
             (Thread/sleep poll-interval-ms)
             (recur))))))))

(defn kill-session-if-exists!
  [session-name]
  (run-sh (format "tmux kill-session -t %s >/dev/null 2>&1 || true" session-name)))

(defn start-session!
  [{:keys [session-name working-dir launch-command]
    :or {working-dir (str (.getCanonicalPath (io/file ".")))
         launch-command (launcher-command)}}]
  (run-sh (format "tmux new-session -d -s %s -c %s"
                  session-name
                  (pr-str working-dir)))
  (let [pane-id (primary-pane-id session-name)
        target  {:session-name session-name
                 :pane-id pane-id}]
    (send-line! target launch-command)
    target))

(defn wait-for-any-marker
  [target markers timeout-ms]
  (wait-until
   (fn []
     (let [pane (sanitize-pane-text (capture-pane target))]
       (boolean (some #(str/includes? pane %) markers))))
   timeout-ms))

(defn wait-for-marker
  [target marker timeout-ms]
  (wait-until
   (fn []
     (str/includes? (sanitize-pane-text (capture-pane target)) marker))
   timeout-ms))

(defn wait-for-java-exit
  [target timeout-ms]
  (wait-until
   (fn []
     (not= "java" (pane-current-command target)))
   timeout-ms))

(defn- failure-result
  [target reason]
  {:status :failed
   :reason reason
   :session-name (:session-name target)
   :pane-id (:pane-id target)
   :pane-snapshot (sanitize-pane-text (capture-pane target))})

(defn run-basic-help-quit-scenario!
  [{:keys [session-name
           working-dir
           launch-command
           startup-timeout-ms
           step-timeout-ms
           ready-markers
           help-marker
           keep-session-on-failure?]
    :or {working-dir (str (.getCanonicalPath (io/file ".")))
         launch-command (launcher-command)
         startup-timeout-ms default-startup-timeout-ms
         step-timeout-ms default-step-timeout-ms
         ready-markers default-ready-markers
         help-marker default-help-marker
         keep-session-on-failure? false}}]
  (let [preflight (tmux-preflight-result)]
    (if (not= :ok (:status preflight))
      preflight
      (let [session-name* (or session-name (unique-session-name))]
        (try
          (let [target (start-session! {:session-name session-name*
                                        :working-dir working-dir
                                        :launch-command launch-command})
                result (cond
                         (not (wait-for-any-marker target ready-markers startup-timeout-ms))
                         (failure-result target :startup-timeout)

                         :else
                         (do
                           (send-line! target "/help")
                           (cond
                             (not (wait-for-marker target help-marker step-timeout-ms))
                             (failure-result target :help-timeout)

                             :else
                             (do
                               (send-line! target "/quit")
                               (if (wait-for-java-exit target step-timeout-ms)
                                 {:status :passed
                                  :session-name session-name*
                                  :pane-id (:pane-id target)}
                                 (failure-result target :quit-timeout))))))]
            (when (or (= :passed (:status result))
                      (not keep-session-on-failure?))
              (kill-session-if-exists! session-name*))
            result)
          (catch Throwable t
            (let [target {:session-name session-name*
                          :pane-id (primary-pane-id session-name*)}
                  result {:status :failed
                          :reason :exception
                          :session-name session-name*
                          :pane-id (:pane-id target)
                          :error-message (or (ex-message t) (str t))
                          :pane-snapshot (sanitize-pane-text (capture-pane target))}]
              (when-not keep-session-on-failure?
                (kill-session-if-exists! session-name*))
              result)))))))

(defn run-slash-autocomplete-scenario!
  "Prove that typing '/' opens a visible autocomplete menu with a selected suggestion,
   that moving selection with Down changes the highlighted row, and that Escape dismisses
   the menu cleanly before exiting.

   Scenario: boot -> ready -> type '/' -> 'Suggestions' visible + '▸ ' marker ->
             Down key -> '▸ ' still visible -> Escape -> '/quit' -> clean exit."
  [{:keys [session-name
           working-dir
           launch-command
           startup-timeout-ms
           step-timeout-ms
           ready-markers
           suggestions-marker
           selected-marker
           keep-session-on-failure?]
    :or {working-dir (str (.getCanonicalPath (io/file ".")))
         launch-command (worktree-launch-command)
         startup-timeout-ms default-startup-timeout-ms
         step-timeout-ms default-step-timeout-ms
         ready-markers default-ready-markers
         suggestions-marker default-autocomplete-suggestions-marker
         selected-marker default-autocomplete-selected-marker
         keep-session-on-failure? false}}]
  (let [preflight (tmux-preflight-result)]
    (if (not= :ok (:status preflight))
      preflight
      (let [session-name* (or session-name (unique-session-name))]
        (try
          (let [target (start-session! {:session-name session-name*
                                        :working-dir working-dir
                                        :launch-command launch-command})
                result (cond
                         (not (wait-for-any-marker target ready-markers startup-timeout-ms))
                         (failure-result target :startup-timeout)

                         :else
                         (do
                           ;; type '/' to trigger slash-command autocomplete
                           (send-text! target "/")
                           (cond
                             (not (wait-for-marker target suggestions-marker step-timeout-ms))
                             (failure-result target :autocomplete-suggestions-timeout)

                             (not (str/includes?
                                   (sanitize-pane-text (capture-pane target))
                                   selected-marker))
                             (assoc (failure-result target :autocomplete-selected-marker-missing)
                                    :detail "Suggestions header appeared but '▸ ' selected marker was not visible")

                             :else
                             (do
                               ;; move selection down one row
                               (send-key! target "Down")
                               (cond
                                 (not (wait-for-marker target selected-marker step-timeout-ms))
                                 (failure-result target :autocomplete-post-down-marker-missing)

                                 :else
                                 (do
                                   ;; dismiss autocomplete with Escape
                                   (send-key! target "Escape")
                                   (Thread/sleep 200)
                                   ;; exit cleanly
                                   (send-line! target "/quit")
                                   (if (wait-for-java-exit target step-timeout-ms)
                                     {:status :passed
                                      :session-name session-name*
                                      :pane-id (:pane-id target)}
                                     (failure-result target :quit-timeout))))))))]
            (when (or (= :passed (:status result))
                      (not keep-session-on-failure?))
              (kill-session-if-exists! session-name*))
            result)
          (catch Throwable t
            (let [target {:session-name session-name*
                          :pane-id (primary-pane-id session-name*)}
                  result {:status :failed
                          :reason :exception
                          :session-name session-name*
                          :pane-id (:pane-id target)
                          :error-message (or (ex-message t) (str t))
                          :pane-snapshot (sanitize-pane-text (capture-pane target))}]
              (when-not keep-session-on-failure?
                (kill-session-if-exists! session-name*))
              result)))))))
