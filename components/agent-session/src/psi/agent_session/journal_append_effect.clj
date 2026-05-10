(ns psi.agent-session.journal-append-effect
  "Shared helper for the canonical append-journal dispatch effect envelope."
  (:require
   [psi.session-persistence.core :as persist]))

(defn append-journal-entry-effect
  [session-id entry]
  {:effect/type :runtime/dispatch-event
   :event-type :session/append-journal-entry
   :event-data {:session-id session-id
                :entry entry}
   :origin :core})

(defn append-message-effect
  [session-id message]
  (append-journal-entry-effect session-id (persist/message-entry message)))

(defn append-model-effect
  [session-id provider model-id]
  (append-journal-entry-effect session-id (persist/model-entry provider model-id)))

(defn append-thinking-level-effect
  [session-id level]
  (append-journal-entry-effect session-id (persist/thinking-level-entry level)))

(defn append-session-info-effect
  [session-id name]
  (append-journal-entry-effect session-id (persist/session-info-entry name)))
