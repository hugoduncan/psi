(ns psi.rpc.session.commands
  "Slash command/result helpers for RPC session workflows."
  (:require
   [clojure.string :as str]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
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
        resolution (commands/slash-resolution-in ctx session-id text {:oauth-ctx oauth-ctx
                                                                      :ai-model ai-model
                                                                      :supports-session-tree? false
                                                                      :on-new-session! (:on-new-session! session-deps)})
        cmd-result (:result resolution)]
    (runtime/journal-user-message-in! ctx session-id text nil)
    (cond
      (or (= trimmed "/resume") (str/starts-with? trimmed "/resume "))
      (do
        (command-resume/handle-resume-command! ctx state request-id emit! trimmed session-id)
        (emit/emit-session-snapshots! emit! ctx state session-id)
        (response-frame (:id request) "command" true {:accepted true
                                                      :handled true}))

      (or (= trimmed "/tree") (str/starts-with? trimmed "/tree "))
      (do
        (command-tree/handle-tree-command! ctx state request-id emit! trimmed session-id)
        (emit/emit-session-snapshots! emit! ctx state session-id)
        (response-frame (:id request) "command" true {:accepted true
                                                      :handled true}))

      (or (= trimmed "/model") (= trimmed "/thinking"))
      (do
        (command-pickers/handle-picker-command! request-id emit! trimmed)
        (emit/emit-session-snapshots! emit! ctx state session-id)
        (response-frame (:id request) "command" true {:accepted true
                                                      :handled true}))

      (= :command (:kind resolution))
      (do
        (handle-dispatched-command! ctx state emit-frame! request-id start-daemon-thread! login-handler cmd-result emit!)
        (emit/emit-session-snapshots! emit! ctx state session-id)
        (response-frame (:id request) "command" true {:accepted true
                                                      :handled true}))

      (= :template (:kind resolution))
      (let [session-model {:provider  (some-> (:provider ai-model) name)
                           :id        (:id ai-model)
                           :reasoning (boolean (:supports-reasoning ai-model))}
            _           (emit/emit-session-snapshots! emit! ctx state session-id)
            _           (session/set-model-in! ctx session-id session-model)
            api-key     (runtime/resolve-api-key-in ctx session-id ai-model)
            _           (session/prompt-in! ctx session-id text nil
                                            {:runtime-opts (cond-> {}
                                                             api-key (assoc :api-key api-key))})
            assistant   (session/last-assistant-message-in ctx session-id)]
        (when assistant
          (emit/emit-assistant-message! emit! session-id assistant))
        (emit/emit-session-snapshots! emit! ctx state session-id)
        (response-frame (:id request) "command" true {:accepted true
                                                      :handled true
                                                      :fallback :template}))

      (= :unknown (:kind resolution))
      (do
        (command-results/emit-text-command-result! emit! (str "[not a command] " text))
        (emit/emit-session-snapshots! emit! ctx state session-id)
        (response-frame (:id request) "command" true {:accepted true
                                                      :handled true}))

      :else
      (do
        (command-results/emit-text-command-result! emit! (str "[not a command] " text))
        (emit/emit-session-snapshots! emit! ctx state session-id)
        (response-frame (:id request) "command" true {:accepted true
                                                      :handled true})))))
