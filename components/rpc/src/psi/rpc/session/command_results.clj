(ns psi.rpc.session.command-results
  (:require
   [clojure.string :as str]
   [psi.rpc.session.emit :as emit]))

(defn extension-command-output
  [cmd-result]
  (try
    (let [result* (atom nil)
          out     (with-out-str
                    (reset! result* ((:handler cmd-result) (:args cmd-result))))
          result  @result*]
      (cond
        (and (string? result) (not (str/blank? result)))
        result

        (and (map? result)
             (string? (:message result))
             (not (str/blank? (:message result))))
        (:message result)

        (not (str/blank? out))
        out

        :else
        nil))
    (catch Throwable e
      (str "[extension command error: " (ex-message e) "]"))))

(defn handle-prompt-command-result!
  "Legacy prompt-path slash command event mapping.
   Keeps existing prompt RPC behavior stable while the new `command` op
   uses `command-result` and `ui/frontend-action-requested`."
  [cmd-result emit!]
  (let [result-type (:type cmd-result)]
    (case result-type
      (:text :logout :login-error :new-session)
      (emit! "assistant/message"
             {:role    "assistant"
              :content [{:type :text :text (str (:message cmd-result))}]})

      :login-start
      (emit! "assistant/message"
             {:role    "assistant"
              :content [{:type :text
                         :text (str "Login: " (get-in cmd-result [:provider :name])
                                    " — open URL: " (:url cmd-result))}]})

      :quit
      (emit! "assistant/message"
             {:role    "assistant"
              :content [{:type :text
                         :text "[/quit is not supported over RPC prompt — use the abort op or close the connection]"}]})

      :resume
      (emit! "assistant/message"
             {:role    "assistant"
              :content [{:type :text
                         :text "[/resume is not supported over RPC prompt — use switch_session op]"}]})

      :tree-open
      (emit! "assistant/message"
             {:role "assistant"
              :content [{:type :text
                         :text "[/tree is only available in TUI mode (--tui)]"}]})

      :tree-switch
      (emit! "assistant/message"
             {:role "assistant"
              :content [{:type :text
                         :text (str "[session switch requested: " (:session-id cmd-result) "]")}]})

      :extension-cmd
      (when-let [output (extension-command-output cmd-result)]
        (emit! "assistant/message"
               {:role    "assistant"
                :content [{:type :text :text output}]}))

      (emit! "assistant/message"
             {:role    "assistant"
              :content [{:type :text :text (str "[command result: " result-type "]")}]})
      nil)))

(defn emit-text-command-result!
  [emit! message]
  (emit/emit-command-result! emit! {:type "text" :message message}))

(defn handle-command-result!
  "Map a commands/dispatch result to canonical RPC event emissions.
   Emits command-result or ui/frontend-action-requested without invoking the agent loop."
  [request-id cmd-result emit!]
  (let [result-type (:type cmd-result)]
    (case result-type
      (:text :logout :login-error :new-session)
      (emit/emit-command-result! emit! {:type (if (= :new-session result-type)
                                                "new_session"
                                                (name result-type))
                                        :message (str (:message cmd-result))})

      :login-start
      (emit/emit-command-result! emit! {:type "login_start"
                                        :message (str "Login: " (get-in cmd-result [:provider :name])
                                                      " — open URL: " (:url cmd-result))
                                        :provider-name (get-in cmd-result [:provider :name])
                                        :login-url (:url cmd-result)
                                        :uses-callback-server (boolean (:uses-callback-server cmd-result))})

      :quit
      (emit/emit-command-result! emit! {:type "quit"})

      :resume
      (emit-text-command-result! emit! "[/resume requires frontend action handling]")

      :tree-open
      (emit-text-command-result! emit! "[/tree requires frontend action handling]")

      :tree-rename
      (emit/emit-command-result! emit! {:type "text"
                                        :message (str "Renamed session " (:session-id cmd-result)
                                                      " to " (pr-str (:session-name cmd-result)))})

      (:tree-switch :session-switch)
      (emit/emit-command-result! emit! {:type "session_switch"
                                        :session-id (:session-id cmd-result)})

      :frontend-action
      (emit/emit-frontend-action-request! emit! request-id cmd-result)

      :extension-cmd
      (when-let [output (extension-command-output cmd-result)]
        (emit-text-command-result! emit! output))

      (emit-text-command-result! emit! (str "[command result: " result-type "]"))
      nil)))
