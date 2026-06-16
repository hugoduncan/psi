(ns extensions.dev-http.registry
  "Session-route registry: an in-memory atom mapping route-id → entry.

   Entries are throwaway session routes registered at runtime (via the
   `dev-present` tool or `register-route!`). They live only as long as the
   running server; re-registering an existing route-id replaces the prior
   entry (last-write-wins).")

(defn create-registry
  "Create a fresh session-route registry atom (`route-id → entry`)."
  []
  (atom {}))

(defn register-entry!
  "Register or replace a session-route entry by `route-id` (last-write-wins).
   Returns the `route-id`."
  [registry route-id entry]
  (swap! registry assoc route-id (assoc entry :route-id route-id))
  route-id)

(defn get-entry
  "Look up the entry for `route-id`, or nil."
  [registry route-id]
  (get @registry route-id))

(defn entries
  "Return the current `route-id → entry` map."
  [registry]
  @registry)
