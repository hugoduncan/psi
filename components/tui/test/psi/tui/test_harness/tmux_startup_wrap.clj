(ns psi.tui.test-harness.tmux-startup-wrap
  "Startup transcript wrapping scenario for TUI tmux integration tests."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.tui.test-harness.tmux :as tmux]))

(def ^:private default-startup-message-marker "This startup paragraph should wrap")
(def ^:private default-minimum-wrap-lines 3)
(def ^:private default-terminal-width 40)

(defn- demo-launch-command []
  "exec clojure -M:tui-demo")

(defn- failure
  [target reason]
  {:status        :failed
   :reason        reason
   :session-name  (:session-name target)
   :pane-id       (:pane-id target)
   :pane-snapshot (tmux/sanitize-pane-text (tmux/capture-pane target))})

(defn- wrapped-startup-lines
  [pane message-fragment]
  (->> (str/split-lines pane)
       (filter #(or (str/includes? % message-fragment)
                    (str/starts-with? % "   ")))
       vec))

(defn run-startup-wrap-scenario!
  "Prove that assistant output present on startup is wrapped on a narrow terminal.

   Scenario:
   1. Boot a demo TUI with a long assistant message preloaded into the transcript.
   2. Resize the tmux pane to a narrow width.
   3. Wait for the startup message marker to appear.
   4. Verify the message spans multiple wrapped continuation lines and that no
      visible line exceeds the current pane width.
   5. Exit cleanly with /quit."
  [{:keys [session-name
           working-dir
           launch-command
           startup-timeout-ms
           step-timeout-ms
           ready-markers
           keep-session-on-failure?
           terminal-width
           startup-message
           startup-message-marker
           minimum-wrap-lines]
    :or {working-dir             (str (.getCanonicalPath (io/file ".")))
         startup-timeout-ms      tmux/default-startup-timeout-ms
         step-timeout-ms         tmux/default-step-timeout-ms
         ready-markers           tmux/default-ready-markers
         keep-session-on-failure? false
         terminal-width          default-terminal-width
         startup-message-marker  default-startup-message-marker
         minimum-wrap-lines      default-minimum-wrap-lines
         startup-message         "This startup paragraph should wrap across multiple visible lines when the TUI boots inside a narrow terminal pane, rather than being cut off at the right edge."}}]
  (let [preflight (tmux/tmux-preflight-result)]
    (if (not= :ok (:status preflight))
      preflight
      (let [session-name* (or session-name (tmux/unique-session-name))
            launch        (or launch-command (demo-launch-command))
            initial-msgs  [{:role :assistant :text startup-message}]
            env-prefix    (str "PSI_TUI_DEMO_INITIAL_MESSAGES=" (pr-str (pr-str initial-msgs))
                               " ")]
        (try
          (let [target (tmux/start-session!
                        {:session-name   session-name*
                         :working-dir    working-dir
                         :launch-command (str env-prefix launch)})
                result (cond
                         (not (tmux/wait-for-any-marker target ready-markers startup-timeout-ms))
                         (failure target :startup-timeout)

                         :else
                         (do
                           (tmux/resize-pane-width! target terminal-width)
                           (cond
                             (not (tmux/wait-for-marker target startup-message-marker step-timeout-ms))
                             (failure target :startup-message-marker-timeout)

                             :else
                             (let [pane          (tmux/sanitize-pane-text (tmux/capture-pane-visible target))
                                   lines         (wrapped-startup-lines pane startup-message-marker)
                                   visible-width (or (tmux/pane-width target) terminal-width)]
                               (cond
                                 (< (count lines) minimum-wrap-lines)
                                 (assoc (failure target :startup-message-not-wrapped)
                                        :detail (str "Expected at least " minimum-wrap-lines
                                                     " wrapped lines, found " (count lines))
                                        :wrapped-lines lines)

                                 (some #(< visible-width (count %)) lines)
                                 (assoc (failure target :startup-line-exceeds-width)
                                        :detail (str "Expected wrapped lines to fit within width " visible-width)
                                        :wrapped-lines lines)

                                 :else
                                 (do
                                   (tmux/send-line! target "/quit")
                                   (if (tmux/wait-for-java-exit target step-timeout-ms)
                                     {:status       :passed
                                      :session-name session-name*
                                      :pane-id      (:pane-id target)}
                                     (failure target :quit-timeout))))))))]
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
              result)))))))
