(ns psi.app-runtime.cli
  "CLI runtime helpers extracted from `psi.app-runtime` to keep the runtime
   entrypoint focused on orchestration."
  (:require
   [clojure.string :as str]
   [psi.agent-session.commands :as commands]
   [psi.app-runtime.output :as output]
   [psi.provider-auth.oauth.core :as oauth]))

(defn complete-login!
  [oauth-ctx {:keys [provider url login-state uses-callback-server]}]
  (println "\n── OAuth Login ────────────────────────")
  (println (str "  Open: " url "\n"))
  (if uses-callback-server
    (do
      (println "  Waiting for browser callback…")
      (try
        (oauth/complete-login! oauth-ctx (:id provider) nil login-state)
        (println (str "\n  ✓ Logged in to " (:name provider) "\n"))
        (catch Exception e
          (println (str "\n  ✗ Login failed: " (ex-message e) "\n")))))
    (do
      (print "  Paste authorization code: ")
      (flush)
      (when-let [code (try (read-line) (catch Exception _ nil))]
        (try
          (oauth/complete-login! oauth-ctx (:id provider) (str/trim code) login-state)
          (println (str "\n  ✓ Logged in to " (:name provider) "\n"))
          (catch Exception e
            (println (str "\n  ✗ Login failed: " (ex-message e) "\n"))))))))

(defn run-cli-prompt!
  [run-prompt-fn ctx sid ai-ctx ai-model trimmed]
  (try
    (run-prompt-fn ctx sid ai-ctx ai-model trimmed)
    (catch Exception e
      (println (str "\n[Error: " (ex-message e) "]\n")))))

(defn handle-cli-command-result!
  [oauth-ctx result]
  (case (:type result)
    :quit false
    :resume (do (output/print-command-message! "  /resume is only available in TUI mode (--tui).") true)
    (:text :new-session :logout)
    (do
      (when (= :new-session (:type result))
        (output/print-initial-transcript! (:rehydrate result)))
      (output/print-command-message! (:message result))
      true)
    :login-start (do (complete-login! oauth-ctx result) true)
    :login-error (do (output/print-command-message! (str "  " (:message result))) true)
    :extension-cmd
    (do
      (try
        (when-let [handler (:handler result)]
          (let [result*  (atom nil)
                captured (with-out-str
                           (reset! result* (handler (:args result))))
                returned @result*]
            (cond
              (and (string? returned) (not (str/blank? returned)))
              (output/print-command-message! returned)

              (and (map? returned)
                   (string? (:message returned))
                   (not (str/blank? (:message returned))))
              (output/print-command-message! (:message returned))

              (not (str/blank? captured))
              (output/print-command-message! (str/trimr captured)))))
        (catch Exception e
          (println (str "\n[Command error: " (ex-message e) "]\n"))))
      true)
    (do
      (output/print-command-message! result)
      true)))

(defn cli-command-opts
  [start-new-session-fn ctx cli-focus* ai-ctx ai-model oauth-ctx]
  {:oauth-ctx oauth-ctx
   :ai-model ai-model
   :supports-session-tree? false
   :on-new-session! (fn [_source-session-id]
                      (let [source-session-id @cli-focus*
                            result             (start-new-session-fn ctx source-session-id ai-ctx ai-model)]
                        (reset! cli-focus* (:session-id result))
                        result))})

(defn run-cli-loop!
  [run-prompt-fn journal-user-message-fn! ctx cli-focus* ai-ctx ai-model oauth-ctx cmd-opts]
  (loop []
    (print "刀: ")
    (flush)
    (when-let [line (try (read-line) (catch Exception _ nil))]
      (let [trimmed (str/trim line)
            sid     @cli-focus*
            result  (when-not (str/blank? trimmed)
                      (commands/dispatch-in ctx sid trimmed cmd-opts))]
        (when result
          (journal-user-message-fn! ctx sid trimmed nil))
        (cond
          (str/blank? trimmed)
          (recur)

          (nil? result)
          (do
            (run-cli-prompt! run-prompt-fn ctx sid ai-ctx ai-model trimmed)
            (recur))

          (handle-cli-command-result! oauth-ctx result)
          (recur)

          :else
          (println "\nψ: Goodbye.\n"))))))
