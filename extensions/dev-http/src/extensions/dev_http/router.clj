(ns extensions.dev-http.router
  "Reitit-ring router builder. One immutable router combines two token-gated
   dynamic subtrees:

   1. Persisted routes loaded from the extension-local `dev/` source.
   2. A stable `/s/:route-id` dispatch subtree that resolves against the
      session-route registry atom at request time — so session-route churn never
      rebuilds the router.

   The builder is pure platform mechanism: it bakes in no specific content
   route. Demonstrated content (the persisted `/demo` page, the `/s/registry`
   SSE feed) is registered as content — under `dev/` or as a session route at
   `start!` time — never hardcoded here."
  (:require
   [extensions.dev-http.middleware :as mw]
   [extensions.dev-http.registry :as registry]
   [extensions.dev-http.renderers :as renderers]
   [extensions.dev-http.util :as util]
   [reitit.ring :as ring]))

(defn- session-dispatch-handler
  [reg]
  (fn [request]
    (let [route-id (get-in request [:path-params :route-id])]
      (if-let [handler (:handler (registry/get-entry reg route-id))]
        (handler request)
        (util/text-response 404 (str "404 no such session route: " route-id))))))

(defn build-handler
  "Build a reitit-ring handler from a `:registry` atom, a per-launch `:token`,
   and a vector of `:persisted-routes`. Every dynamic subtree is token-gated."
  [{:keys [registry token persisted-routes]}]
  (let [gated-root (into ["" {:middleware [[mw/wrap-token token]]}
                          [(str util/session-route-prefix "/:route-id")
                           {:handler (session-dispatch-handler registry)}]]
                         (or persisted-routes []))
        ;; Vendored client JS is public third-party library content with no
        ;; session data; served ungated so token-less browser <script> requests
        ;; resolve. Every *dynamic* subtree remains token-gated.
        assets-root [(str renderers/asset-prefix "/:asset")
                     {:get renderers/asset-handler}]]
    (ring/ring-handler
     (ring/router [gated-root assets-root])
     (ring/create-default-handler))))
