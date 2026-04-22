(ns psi.rpc.session.emit
  "Canonical RPC session event emission helpers."
  (:require
   [clojure.string :as str]
   [psi.rpc.events :as events]))

(defn make-request-emitter
  [emit-frame! state request-id]
  (fn emit! [event payload]
    (events/emit-event! emit-frame! state {:event event
                                           :data payload
                                           :id request-id})))

(defn emit-session-updated!
  [emit! ctx session-id]
  (emit! "session/updated" (events/session-updated-payload ctx session-id)))

(defn emit-footer-updated!
  [emit! ctx session-id]
  (emit! "footer/updated"
         (assoc (events/footer-updated-payload ctx session-id)
                :session-id session-id)))

(defn emit-context-updated!
  [emit! ctx state session-id]
  (emit! "context/updated" (events/context-updated-payload ctx state session-id)))

(defn emit-session-snapshots!
  [emit! ctx _state session-id]
  (emit-session-updated! emit! ctx session-id)
  (emit-footer-updated! emit! ctx session-id))

(defn emit-session-resumed!
  [emit! {:keys [session-id session-file message-count]}]
  (emit! "session/resumed"
         {:session-id   session-id
          :session-file session-file
          :message-count message-count}))

(defn emit-session-rehydrated!
  [emit! {:keys [session-id messages tool-calls tool-order]}]
  (emit! "session/rehydrated"
         {:session-id session-id
          :messages   messages
          :tool-calls (or tool-calls {})
          :tool-order (or tool-order [])}))

(defn emit-session-rehydration!
  [emit! {:keys [session-id session-file message-count
                 messages tool-calls tool-order]}]
  (emit-session-resumed! emit! {:session-id session-id
                                :session-file session-file
                                :message-count message-count})
  (emit-session-rehydrated! emit! {:session-id session-id
                                   :messages messages
                                   :tool-calls tool-calls
                                   :tool-order tool-order}))

(defn emit-assistant-message!
  [emit! session-id result]
  (let [content (or (:content result) [])
        text    (events/assistant-content-text content)]
    (emit! "assistant/message"
           (cond-> {:session-id session-id
                    :role       (:role result)
                    :content    content}
             (and (string? text) (not (str/blank? text)))
             (assoc :text text)

             (contains? result :stop-reason)
             (assoc :stop-reason (:stop-reason result))

             (contains? result :error-message)
             (assoc :error-message (:error-message result))

             (contains? result :usage)
             (assoc :usage (:usage result))))))

(defn emit-assistant-text!
  [emit! session-id text]
  (let [text* (str text)]
    (emit! "assistant/message"
           {:session-id session-id
            :role       "assistant"
            :text       text*
            :content    [{:type :text :text text*}]})))

(defn emit-command-result!
  [emit! payload]
  (emit! "command-result" payload))

(defn emit-navigation-result!
  [emit! ctx state {:keys [nav/session-id nav/session-file nav/rehydration]}]
  (events/set-focus-session-id! state session-id)
  (emit-session-rehydration! emit! rehydration)
  (emit-session-updated! emit! ctx session-id)
  (emit-footer-updated! emit! ctx session-id)
  (emit-context-updated! emit! ctx state session-id)
  {:session-id session-id
   :session-file session-file})

(defn emit-frontend-action-request!
  [emit! request-id action]
  (emit! "ui/frontend-action-requested"
         {:request-id request-id
          :prompt (:ui/prompt action)
          :ui/action action}))
