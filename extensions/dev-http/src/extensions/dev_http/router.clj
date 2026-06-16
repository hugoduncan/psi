(ns extensions.dev-http.router
  "Reitit-ring router builder. One immutable router combines two token-gated
   dynamic subtrees:

   1. Persisted routes loaded from the extension-local `dev/` source.
   2. A stable `/s/:route-id` dispatch subtree that resolves against the
      session-route registry atom at request time — so session-route churn never
      rebuilds the router."
  (:require
   [extensions.dev-http.middleware :as mw]
   [extensions.dev-http.registry :as registry]
   [reitit.ring :as ring]))

(defn- session-dispatch-handler
  [reg]
  (fn [request]
    (let [route-id (get-in request [:path-params :route-id])]
      (if-let [handler (:handler (registry/get-entry reg route-id))]
        (handler request)
        {:status  404
         :headers {"content-type" "text/plain; charset=utf-8"}
         :body    (str "404 no such session route: " route-id)}))))

(defn build-handler
  "Build a reitit-ring handler from a `:registry` atom, a per-launch `:token`,
   and a vector of `:persisted-routes`. Every dynamic subtree is token-gated."
  [{:keys [registry token persisted-routes]}]
  (let [gated-root (into ["" {:middleware [[mw/wrap-token token]]}
                          ["/s/:route-id" {:handler (session-dispatch-handler registry)}]]
                         (or persisted-routes []))]
    (ring/ring-handler
     (ring/router [gated-root])
     (ring/create-default-handler))))
