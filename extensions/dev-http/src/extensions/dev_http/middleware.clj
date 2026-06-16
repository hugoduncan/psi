(ns extensions.dev-http.middleware
  "Per-launch token gating. Every dynamic subtree is wrapped so that a request
   without the matching token is rejected with 403 before reaching a handler."
  (:require
   [clojure.string :as str]
   [extensions.dev-http.util :as util]))

(defn- decode
  [s]
  (try
    (java.net.URLDecoder/decode (str s) "UTF-8")
    (catch Exception _ s)))

(defn urlencoded-param
  "Extract and URL-decode the value of `param` from a urlencoded string `s`
   (a query string or form body), or nil when absent."
  [s param]
  (some->> s
           (re-seq #"([^&=]+)=([^&]*)")
           (some (fn [[_ k v]] (when (= k param) (decode v))))))

(defn- query-token
  [request]
  (urlencoded-param (:query-string request) "token"))

(defn request-token
  "Extract the supplied token from the request query string or the
   `x-dev-http-token` header, or nil."
  [request]
  (or (query-token request)
      (some-> (get-in request [:headers "x-dev-http-token"]) str/trim not-empty)))

(defn wrap-token
  "Ring middleware: pass through only when the request carries the matching
   per-launch `token`; otherwise respond 403."
  [handler token]
  (fn [request]
    (if (= token (request-token request))
      (handler request)
      (util/text-response
       403 "403 forbidden: invalid or missing dev-http token"))))
