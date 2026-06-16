(ns extensions.dev-http.util
  "Small shared helpers for the dev-http extension.")

(defn kget
  "Read a value from `m` under any of `ks`, returning the first present key's
   value. Lets a single accessor serve keyword-keyed (REPL) and string-keyed
   (JSON tool) data."
  [m & ks]
  (some (fn [k] (when (contains? m k) (get m k))) ks))

(def session-route-prefix
  "URL path prefix for the session-route dispatch subtree. Single source of the
   dispatch path: the reitit template (`router`) and the URL builders
   (`route-url`, choices form `action`) all derive from this."
  "/s")

(defn session-route-path
  "Relative URL path (with token query) for a session `route-id`. The single
   source for the `/s/<route-id>?token=<token>` dispatch path shape."
  [route-id token]
  (str session-route-prefix "/" route-id "?token=" token))
