(ns psi.tui.test-harness.tmux-streaming
  "Scripted streaming display scenario for TUI tmux integration tests.

   Uses PSI_TUI_DEMO_SCRIPT to inject pre-scripted events — no live LLM needed."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.tui.test-harness.tmux :as tmux]))

(def ^:private default-tool-done-marker "✓")
(def ^:private default-thinking-prefix "· ")
(def ^:private default-tool-started-markers ["⠋" "✓"])

(defn- edn-str [v] (pr-str v))

(defn- demo-launch-command []
  "exec clojure -M:tui-demo")

(defn- failure
  [target reason]
  {:status        :failed
   :reason        reason
   :session-name  (:session-name target)
   :pane-id       (:pane-id target)
   :pane-snapshot (tmux/sanitize-pane-text (tmux/capture-pane target))})

(defn- visible-tool-body-line?
  [pane marker]
  (boolean
   (some #(and (str/starts-with? % "    ")
               (str/includes? % marker))
         (str/split-lines pane))))

(defn run-streaming-display-scenario!
  "Prove that the TUI renders thinking blocks, tool streaming, and tool result
   truncation correctly through a real terminal, without a live LLM.

   Uses PSI_TUI_DEMO_SCRIPT to inject pre-scripted events — no live LLM needed.

   Scenario steps:
   1. Boot → ready marker
   2. Submit 'think' → wait for '· ' (thinking prefix)
   3. Submit 'tool'  → wait for spinner (⠋) OR done marker (✓); then wait for ✓
   4. Assert content NOT visible in collapsed mode on the visible screen (no 'output-line-1')
   5. Press ctrl+o → assert expanded content visible ('output-line-10')
   6. End successfully once expansion is visible."
  [{:keys [session-name
           working-dir
           launch-command
           startup-timeout-ms
           step-timeout-ms
           ready-markers
           keep-session-on-failure?]
    :or {working-dir            (str (.getCanonicalPath (io/file ".")))
         startup-timeout-ms     tmux/default-startup-timeout-ms
         step-timeout-ms        tmux/default-step-timeout-ms
         ready-markers          tmux/default-ready-markers
         keep-session-on-failure? false}}]
  (let [preflight (tmux/tmux-preflight-result)]
    (if (not= :ok (:status preflight))
      preflight
      (let [long-result   (str/join "\n" (map #(str "output-line-" %) (range 1 25)))
            script        (edn-str
                           [{:trigger "think"
                             :delay-ms 80
                             :events [{:type :agent-event :event-kind :thinking-delta
                                       :content-index 0 :text "Reasoning about the question."}]
                             :done {:role "assistant"
                                    :content [{:type :thinking :text "Reasoning about the question."}
                                              {:type :text :text "Here is the answer."}]}}
                            {:trigger "tool"
                             :delay-ms 80
                             :events [{:type :agent-event :event-kind :tool-call-assembly
                                       :phase :end :content-index 0
                                       :tool-id "t1" :tool-name "bash"
                                       :arguments "{\"command\":\"ls\"}"}
                                      {:type :agent-event :event-kind :tool-start
                                       :tool-id "t1" :tool-name "bash"}
                                      {:type :agent-event :event-kind :tool-executing
                                       :tool-id "t1" :tool-name "bash"
                                       :parsed-args {:command "ls"}}
                                      {:type :agent-event :event-kind :tool-result
                                       :tool-id "t1" :tool-name "bash"
                                       :content [{:type :text :text long-result}]
                                       :is-error false}]
                             :done {:role "assistant"
                                    :content [{:type :text :text "Done."}]}}])
            session-name* (or session-name (tmux/unique-session-name))
            launch        (or launch-command (demo-launch-command))]
        (try
          (let [target (tmux/start-session!
                        {:session-name   session-name*
                         :working-dir    working-dir
                         :launch-command (str "PSI_TUI_DEMO_SCRIPT=" (pr-str script)
                                              " " launch)})
                result (cond
                         (not (tmux/wait-for-any-marker target ready-markers startup-timeout-ms))
                         (failure target :startup-timeout)

                         :else
                         (do
                           (tmux/send-line! target "think")
                           (cond
                             (not (tmux/wait-for-marker target default-thinking-prefix step-timeout-ms))
                             (failure target :thinking-prefix-not-visible)

                             :else
                             (do
                               (tmux/send-line! target "tool")
                               (cond
                                 (not (tmux/wait-for-any-marker target default-tool-started-markers step-timeout-ms))
                                 (failure target :tool-spinner-not-visible)

                                 (not (tmux/wait-for-marker target default-tool-done-marker step-timeout-ms))
                                 (failure target :tool-done-marker-not-visible)

                                 :else
                                 (let [pane (tmux/sanitize-pane-text (tmux/capture-pane-visible target))]
                                   (cond
                                     (visible-tool-body-line? pane "output-line-1")
                                     (assoc (failure target :content-visible-when-collapsed)
                                            :detail "Tool body should not be visible in collapsed mode")

                                     :else
                                     (do
                                       (tmux/send-key! target "C-o")
                                       (if (not (tmux/wait-for-marker target "output-line-10" step-timeout-ms))
                                         (failure target :expand-not-visible)
                                         {:status       :passed
                                          :session-name session-name*
                                          :pane-id      (:pane-id target)})))))))))]
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
