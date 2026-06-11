(ns psi.agent-session.journal-append-effect
  "Shared helper for the canonical append-journal dispatch effect envelope."
  (:require
   [psi.session-persistence.core :as persist]))

(defn append-journal-entry-effect
  ([session-id entry]
   (append-journal-entry-effect session-id entry nil))
  ([session-id entry workflow-run-id]
   (cond-> {:effect/type :runtime/dispatch-event
            :event-type :session/append-journal-entry
            :event-data {:session-id session-id
                         :entry entry}
            :origin :core}
     workflow-run-id (assoc :workflow-run-id workflow-run-id))))

(defn append-message-effect
  ([session-id message]
   (append-message-effect session-id message nil))
  ([session-id message workflow-run-id]
   (append-journal-entry-effect session-id (persist/message-entry message) workflow-run-id)))

(defn append-model-effect
  [session-id provider model-id]
  (append-journal-entry-effect session-id (persist/model-entry provider model-id)))

(defn append-thinking-level-effect
  [session-id level]
  (append-journal-entry-effect session-id (persist/thinking-level-entry level)))

(defn append-session-info-effect
  [session-id name]
  (append-journal-entry-effect session-id (persist/session-info-entry name)))

(defn append-logprobs-effect
  ([session-id turn-id tokens]
   (append-logprobs-effect session-id turn-id tokens nil))
  ([session-id turn-id tokens workflow-run-id]
   (append-journal-entry-effect session-id (persist/logprobs-entry turn-id tokens) workflow-run-id)))
