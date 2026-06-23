(ns extensions.context-manager
  "Context manager extension scaffold.

   Subscribes to `session_turn_finished` events and logs session-id and turn-id.
   Pure scaffold — no commands, tools, or operations yet.")

(defn- on-turn-finished
  [log-fn payload]
  (let [session-id (or (:session-id payload) "nil")
        turn-id (or (:turn-id payload) "nil")]
    (log-fn (str "context-manager: session_turn_finished "
                 "session-id=" session-id
                 " turn-id=" turn-id))))

(def initialized? (atom nil))

(defn init
  "Initialize the context-manager extension.

   Subscribes to `session_turn_finished` events via the extension API.
   Idempotent — repeated calls (e.g. on reload) are no-ops."
  [api]
  (if (and (map? api)
           (:on api)
           (compare-and-set! initialized? nil true))
    (do
      ((:on api) "session_turn_finished"
                 (fn [payload]
                   (when (:log api)
                     (on-turn-finished (:log api) payload))
                   nil))
      true)
    (if (and (map? api) (:on api))
      nil ; already initialized
      (do
        (reset! initialized? nil) ; ensure we don't block future attempts if this one failed
        nil))))
