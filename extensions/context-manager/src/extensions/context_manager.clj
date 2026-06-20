(ns extensions.context-manager
  "Context manager extension scaffold.

   Subscribes to `session_turn_finished` events and logs session-id and turn-id.
   Pure scaffold — no commands, tools, or operations yet.")

(defn- on-turn-finished
  [log-fn payload]
  (log-fn (str "context-manager: session_turn_finished "
               "session-id=" (:session-id payload)
               " turn-id=" (:turn-id payload))))

(defn init
  "Initialize the context-manager extension.

   Subscribes to `session_turn_finished` events via the extension API."
  [api]
  ((:on api) "session_turn_finished"
             (fn [payload]
               (on-turn-finished (:log api) payload)
               nil)))
