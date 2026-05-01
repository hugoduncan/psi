(ns extensions.workflow-loader.delivery)

(defn append-message-in-session!
  [{:keys [mutate-fn mutate-session-fn]} session-id role content]
  (try
    (cond
      (and session-id mutate-session-fn)
      (mutate-session-fn session-id 'psi.extension/append-message
                         {:role role :content content})

      mutate-fn
      (mutate-fn 'psi.extension/append-message
                 {:role role :content content})

      :else nil)
    (catch Exception _ nil)))

(defn inject-result-into-context!
  "Inject workflow result text into the parent session context as user+assistant messages.
   Explicitly targets the originating parent session when session-targeting APIs
   are available."
  [deps parent-session-id run-id result-text]
  (let [user-content (str "Workflow run " run-id " result:")
        asst-content (or result-text "")]
    (append-message-in-session! deps parent-session-id "user" user-content)
    (append-message-in-session! deps parent-session-id "assistant" asst-content)))
