(ns extensions.dev-http.config
  "Resolved settings for a dev-http launch: localhost bind, ephemeral port, a
   freshly generated per-launch token, and the captured ExtensionAPI map.")

(defn generate-token
  "Generate a url-safe per-launch token (dev-grade, not auth)."
  []
  (let [bytes (byte-array 24)]
    (.nextBytes (java.security.SecureRandom.) bytes)
    (-> (java.util.Base64/getUrlEncoder)
        (.withoutPadding)
        (.encodeToString bytes))))

(defn build-config
  "Resolve a launch config from `opts` (`:api`, optional `:host`, `:port`).
   Binds to `127.0.0.1` and requests an ephemeral port by default, and mints a
   fresh per-launch token."
  [{:keys [api host port]}]
  {:host  (or host "127.0.0.1")
   :port  (or port 0)
   :token (generate-token)
   :api   api})
