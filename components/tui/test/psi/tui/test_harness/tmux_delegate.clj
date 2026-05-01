(ns psi.tui.test-harness.tmux-delegate
  "Scripted /delegate live scenario for TUI tmux integration tests."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.tui.test-harness.tmux :as tmux]))

(def default-ack-marker "Delegated to lambda-compiler — run ")
(def default-user-bridge-prefix "Workflow run lambda-compiler-")
(def default-user-bridge-suffix " result:")

(defn- assistant-result-after-user-bridge?
  [pane]
  (let [lines (str/split-lines pane)
        bridge-index (first (keep-indexed (fn [idx line]
                                            (when (and (str/includes? line default-user-bridge-prefix)
                                                       (str/includes? line default-user-bridge-suffix))
                                              idx))
                                          lines))]
    (boolean
     (when (some? bridge-index)
       (some #(and (str/includes? % "ψ: ")
                   (not (str/includes? % default-ack-marker))
                   (not (str/includes? % "workflow-loader: ")))
             (drop (inc bridge-index) lines))))))

(defn- failure-result
  [target reason]
  {:status :failed
   :reason reason
   :session-name (:session-name target)
   :pane-id (:pane-id target)
   :pane-snapshot (tmux/sanitize-pane-text (tmux/capture-pane target))})

(defn- pane-text
  [target]
  (tmux/sanitize-pane-text (tmux/capture-pane target)))

(defn- wait-for-user-bridge
  [target timeout-ms]
  (tmux/wait-until
   (fn []
     (let [pane (pane-text target)]
       (and (str/includes? pane default-user-bridge-prefix)
            (str/includes? pane default-user-bridge-suffix))))
   timeout-ms))

(defn run-delegate-live-sequence-scenario!
  [{:keys [session-name
           working-dir
           launch-command
           startup-timeout-ms
           step-timeout-ms
           ready-markers
           keep-session-on-failure?]
    :or {working-dir (str (.getCanonicalPath (io/file ".")))
         launch-command (str "PSI_NULLABLE_EXECUTION_MODE=deterministic " (tmux/worktree-launch-command))
         startup-timeout-ms tmux/default-startup-timeout-ms
         step-timeout-ms 20000
         ready-markers tmux/default-ready-markers
         keep-session-on-failure? false}}]
  (let [preflight (tmux/tmux-preflight-result)]
    (if (not= :ok (:status preflight))
      preflight
      (let [session-name* (or session-name (tmux/unique-session-name))]
        (try
          (let [target (tmux/start-session! {:session-name session-name*
                                             :working-dir working-dir
                                             :launch-command launch-command})
                result (cond
                         (not (tmux/wait-for-any-marker target ready-markers startup-timeout-ms))
                         (failure-result target :startup-timeout)

                         :else
                         (do
                           (tmux/send-line! target "/delegate lambda-compiler munera tracks work, mementum tracks state and knowledge")
                           (cond
                             (not (tmux/wait-for-marker target default-ack-marker step-timeout-ms))
                             (failure-result target :delegate-ack-timeout)

                             (not (wait-for-user-bridge target step-timeout-ms))
                             (failure-result target :delegate-user-bridge-timeout)

                             (not (tmux/wait-until #(assistant-result-after-user-bridge? (pane-text target)) step-timeout-ms))
                             (failure-result target :delegate-assistant-result-timeout)

                             (str/includes? (pane-text target) "(workflow context bridge)")
                             (failure-result target :delegate-visible-bridge-filler)

                             :else
                             (do
                               (tmux/send-line! target "/quit")
                               (if (tmux/wait-for-java-exit target step-timeout-ms)
                                 {:status :passed
                                  :session-name session-name*
                                  :pane-id (:pane-id target)}
                                 (failure-result target :quit-timeout))))))]
            (when (or (= :passed (:status result))
                      (not keep-session-on-failure?))
              (tmux/kill-session-if-exists! session-name*))
            result)
          (catch Throwable t
            (let [target {:session-name session-name*
                          :pane-id (tmux/primary-pane-id session-name*)}
                  result {:status :failed
                          :reason :exception
                          :session-name session-name*
                          :pane-id (:pane-id target)
                          :error-message (or (ex-message t) (str t))
                          :pane-snapshot (tmux/sanitize-pane-text (tmux/capture-pane target))}]
              (when-not keep-session-on-failure?
                (tmux/kill-session-if-exists! session-name*))
              result)))))))
