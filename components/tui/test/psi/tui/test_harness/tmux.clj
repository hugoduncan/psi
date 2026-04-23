(ns psi.tui.test-harness.tmux
  "Reusable tmux-backed integration harness utilities for TUI tests."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [psi.tui.ansi :as ansi]))

(def default-launch-command
  "exec clojure -M:psi --tui")

(def default-startup-timeout-ms 120000)
(def default-step-timeout-ms 15000)
(def default-poll-interval-ms 100)
(def default-ready-markers ["刀:" "Type a message"])
(def default-help-marker "(anything else is sent to the agent)")
(def default-capture-lines 3000)

(defn- run-sh
  [cmd]
  (shell/sh "bash" "-lc" cmd))

(defn tmux-available?
  []
  (zero? (:exit (run-sh "command -v tmux >/dev/null 2>&1"))))

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
  [{:keys [session-name pane-id]}]
  (or pane-id
      (primary-pane-id session-name)
      (str session-name ":0.0")))

(defn capture-pane
  ([session-name]
   (if (string? session-name)
     (capture-pane {:session-name session-name})
     (capture-pane session-name {})))
  ([{:keys [capture-lines] :as target} _opts]
   (let [{:keys [exit out err]}
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
                        (pane-target (if (string? target)
                                       {:session-name target}
                                       target))))]
    (when (zero? exit)
      (str/trim out))))

(defn send-line!
  [target s]
  (let [pane (pane-target (if (string? target)
                            {:session-name target}
                            target))]
    (run-sh (format "tmux send-keys -l -t %s %s"
                    pane
                    (pr-str s)))
    (run-sh (format "tmux send-keys -t %s Enter" pane))))

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
         launch-command default-launch-command}}]
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
         launch-command default-launch-command
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
