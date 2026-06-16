(ns extensions.dev-http.dev.demo
  "A persisted demo route exercising the dev-http platform end-to-end. Committed,
   dev-only; loaded from the extension-local `dev/` source at router-build time."
  (:require
   [hiccup2.core :as h]))

(defn- demo-handler
  [_request]
  {:status  200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body    (str (h/html
                  [:html
                   [:head [:title "dev-http demo"]]
                   [:body
                    [:h1 "dev-http demo"]
                    [:p "This persisted route is served by the dev-http extension."]]]))})

(def routes
  [["/demo" {:get demo-handler}]])
