(ns psi.ai.providers.http-boundary
  "Injectable HTTP boundary for provider requests.

  The default boundary delegates to clj-http. `nullable` supplies scripted
  responses without network access and records requests for state-based tests."
  (:require [clj-http.client :as http]))

(defprotocol HttpBoundary
  (post! [boundary url request]
    "POST request through the boundary and return an HTTP response map.")
  (requests [boundary]
    "Return recorded requests. Real boundaries return nil."))

(defrecord RealHttpBoundary []
  HttpBoundary
  (post! [_ url request]
    (http/post url request))
  (requests [_]
    nil))

(defrecord NullableHttpBoundary [state]
  HttpBoundary
  (post! [_ url request]
    (let [recorded-request {:url url :request request}
          [before _]      (swap-vals! state
                                      (fn [{:keys [responses requests]}]
                                        {:responses (next responses)
                                         :requests (conj requests recorded-request)}))
          response        (first (:responses before))]
      (cond
        (nil? response)
        (throw (ex-info "Nullable HTTP boundary has no scripted response"
                        {:request recorded-request}))

        (fn? response)
        (response recorded-request)

        (instance? Throwable response)
        (throw response)

        :else
        response)))
  (requests [_]
    (:requests @state)))

(def real
  "Production HTTP boundary."
  (->RealHttpBoundary))

(defn nullable
  "Return a zero-network HTTP boundary with responses consumed in order.

  A scripted response is an HTTP response map, a Throwable to throw, or a
  function from `{:url url :request request}` to a response map. Requests are
  available through `requests`."
  [responses]
  (->NullableHttpBoundary (atom {:responses (seq responses) :requests []})))

(defn boundary
  "Return the explicitly configured boundary or the production boundary."
  [options]
  (or (:http-boundary options) real))
