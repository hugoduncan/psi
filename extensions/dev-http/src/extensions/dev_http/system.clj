(ns extensions.dev-http.system
  "Integrant system for the dev-http extension. Keys form a linear dependency
   `:dev-http/config → :dev-http/registry → :dev-http/router → :dev-http/server`.
   `halt!` cleanly stops http-kit so no orphaned server survives a reload."
  (:require
   [extensions.dev-http.config :as config]
   [extensions.dev-http.registry :as registry]
   [extensions.dev-http.router :as router]
   [extensions.dev-http.routes :as routes]
   [integrant.core :as ig]
   [org.httpkit.server :as http]))

(defn system-config
  "Build the integrant config map for a launch, capturing the `api` map and
   optional `opts` (`:host`, `:port`)."
  [api opts]
  {:dev-http/config   (assoc opts :api api)
   :dev-http/registry {}
   :dev-http/router   {:config   (ig/ref :dev-http/config)
                       :registry (ig/ref :dev-http/registry)}
   :dev-http/server   {:config  (ig/ref :dev-http/config)
                       :handler (ig/ref :dev-http/router)}})

(defmethod ig/init-key :dev-http/config
  [_ opts]
  (config/build-config opts))

(defmethod ig/init-key :dev-http/registry
  [_ _]
  (registry/create-registry))

(defmethod ig/init-key :dev-http/router
  [_ {:keys [config registry]}]
  (router/build-handler {:registry         registry
                         :token            (:token config)
                         :persisted-routes (routes/load-persisted-routes)}))

(defmethod ig/init-key :dev-http/server
  [_ {:keys [config handler]}]
  (let [server (http/run-server handler {:ip                   (:host config)
                                         :port                 (:port config)
                                         :legacy-return-value? false})]
    {:server server
     :host   (:host config)
     :port   (http/server-port server)
     :token  (:token config)}))

(defmethod ig/halt-key! :dev-http/server
  [_ {:keys [server]}]
  (when server
    ;; `server-stop!` returns a promise delivered once the server thread
    ;; completes (and the listening socket is released). Deref it so halt is
    ;; synchronous: a restart cannot leave the prior server still bound to its
    ;; port (AC-1, no orphaned server).
    (when-let [stopped (http/server-stop! server)]
      @stopped)))
