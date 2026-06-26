(ns extensions.context-manager
  "Context manager extension scaffold.

   Subscribes to `session_turn_finished` events and logs session-id and turn-id.
   Pure scaffold — no commands, tools, or operations yet.")

(defn- on-turn-finished
  [log-fn payload]
  (try
    (let [session-id (get payload :session-id "nil")
          turn-id (get payload :turn-id "nil")]
      (log-fn (str "context-manager: session_turn_finished "
                   "session-id=" session-id
                   " turn-id=" turn-id)))
    (catch Exception e
      (try
        (log-fn (str "context-manager: handler error: " (.getMessage e)))
        (catch Exception _ nil))
      nil)))

(defonce initialized? (atom nil))

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
