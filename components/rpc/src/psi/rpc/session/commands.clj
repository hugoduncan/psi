(ns psi.rpc.session.commands
  "Slash command/result helpers for RPC session workflows."
  (:require
   [clojure.string :as str]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.session-state :as ss]
   [psi.app-runtime.messages :as app-messages]
   [psi.rpc.events :as events]
   [psi.rpc.session.command-pickers :as command-pickers]
   [psi.rpc.session.command-resume :as command-resume]
   [psi.rpc.session.command-results :as command-results]
   [psi.rpc.session.command-tree :as command-tree]
   [psi.rpc.session.emit :as emit]
   [psi.rpc.transport :refer [response-frame]]))

(def handle-prompt-command-result!
  command-results/handle-prompt-command-result!)

(def handle-command-result!
  command-results/handle-command-result!)

(defn- handle-dispatched-command!
  [ctx state emit-frame! request-id start-daemon-thread! login-handler cmd-result emit!]
  (if (= :login-start (:type cmd-result))
    (login-handler {:ctx ctx
                    :state state
                    :emit-frame! emit-frame!
                    :request-id request-id
                    :cmd-result cmd-result
                    :emit! emit!
                    :start-daemon-thread! start-daemon-thread!})
    (do
      (when (= :new-session (:type cmd-result))
        (let [rehydrate (:rehydrate cmd-result)
              new-sid   (:session-id rehydrate)
              _         (when new-sid (events/set-focus-session-id! state new-sid))
              sd        (when new-sid (ss/get-session-data-in ctx new-sid))
              msgs      (or (:agent-messages rehydrate)
                            (when new-sid
                              (app-messages/session-messages ctx new-sid)))]
          (emit/emit-session-rehydration!
           emit!
           {:session-id   (:session-id sd)
            :session-file (:session-file sd)
            :message-count (count msgs)
            :messages msgs
            :tool-calls (or (:tool-calls rehydrate) {})
            :tool-order (or (:tool-order rehydrate) [])})))
      (command-results/handle-command-result! request-id cmd-result emit!))))

(defn run-command!
  [{:keys [ctx request emit-frame! state session-id session-deps current-ai-model start-daemon-thread! login-handler]}]
  (let [text       (get-in request [:params :text])
        request-id (:id request)
        emit!      (emit/make-request-emitter emit-frame! state request-id)
        ai-model   (current-ai-model ctx session-deps session-id)
        oauth-ctx  (:oauth-ctx ctx)
        trimmed    (str/trim text)
        cmd-result (commands/dispatch-in ctx session-id text {:oauth-ctx oauth-ctx
                                                              :ai-model ai-model
                                                              :supports-session-tree? false
                                                              :on-new-session! (:on-new-session! session-deps)})]
    (runtime/journal-user-message-in! ctx session-id text nil)
    (cond
      (or (= trimmed "/resume") (str/starts-with? trimmed "/resume "))
      (command-resume/handle-resume-command! ctx state request-id emit! trimmed session-id)

      (or (= trimmed "/tree") (str/starts-with? trimmed "/tree "))
      (command-tree/handle-tree-command! ctx state request-id emit! trimmed session-id)

      (or (= trimmed "/model") (= trimmed "/thinking"))
      (command-pickers/handle-picker-command! request-id emit! trimmed)

      cmd-result
      (handle-dispatched-command! ctx state emit-frame! request-id start-daemon-thread! login-handler cmd-result emit!)

      :else
      (command-results/emit-text-command-result! emit! (str "[not a command] " text)))
    (emit/emit-session-snapshots! emit! ctx state session-id)
    (response-frame (:id request) "command" true {:accepted true
                                                  :handled true})))
