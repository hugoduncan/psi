(ns psi.agent-session.service-protocol
  "Shared stdio request/response and JSON-RPC helper semantics for managed services."
  (:require [psi.agent-session.dispatch :as dispatch]
            [psi.agent-session.services :as services]))

(defn send-service-request!
  "Send a correlated request to a managed service.
   v1 supports three runtime adapter modes, checked in this order:

   1. :request-fn         synchronous request/response shim
   2. :await-response-fn  explicit async write + correlated wait
   3. :send-fn            fire-and-forget write only

   Responses from request/await modes are surfaced as :response for higher-level
   protocol adapters."
  ([ctx service-key request]
   (send-service-request! ctx service-key request nil))
  ([ctx service-key {:keys [request-id payload timeout-ms] :as request} {:keys [dispatch-id]}]
   (let [svc (services/service-in ctx service-key)]
     (dispatch/append-trace-entry! {:trace/kind  :dispatch/service-request
                                    :dispatch-id dispatch-id
                                    :service-key service-key
                                    :request-id  request-id
                                    :method      (get payload "method")
                                    :payload     payload})
     (let [response (cond
                      (:request-fn svc)
                      ((:request-fn svc) {:request-id request-id
                                          :payload payload
                                          :timeout-ms timeout-ms})

                      (:await-response-fn svc)
                      (do
                        (when (and (:send-fn svc) (not (:await-response-sends? svc)))
                          ((:send-fn svc) payload))
                        ((:await-response-fn svc)
                         (assoc request :send-fn (:send-fn svc)
                                :send-already? true
                                :await-response-sends? (:await-response-sends? svc))))

                      :else
                      (do
                        (when-let [send-fn (:send-fn svc)]
                          (send-fn payload))
                        nil))]
       (dispatch/append-trace-entry! {:trace/kind  :dispatch/service-response
                                      :dispatch-id dispatch-id
                                      :service-key service-key
                                      :request-id  request-id
                                      :method      (get payload "method")
                                      :response    response
                                      :is-error    (boolean (:is-error response))})
       {:service-key service-key
        :request-id request-id
        :payload payload
        :timeout-ms timeout-ms
        :response response}))))

(defn await-jsonrpc-result
  "Project one service-request response to a JSON-RPC result payload.

   Returns the inner `result` when present, the whole payload when no JSON-RPC
   envelope/result is present, or nil when no response is available."
  [request-result]
  (let [response (:response request-result)
        payload  (or (:payload response) response)]
    (cond
      (nil? payload) nil
      (contains? payload "result") (get payload "result")
      :else payload)))

(defn send-service-notification!
  ([ctx service-key payload]
   (send-service-notification! ctx service-key payload nil))
  ([ctx service-key payload {:keys [dispatch-id]}]
   (let [svc (services/service-in ctx service-key)]
     (dispatch/append-trace-entry! {:trace/kind  :dispatch/service-notify
                                    :dispatch-id dispatch-id
                                    :service-key service-key
                                    :method      (get payload "method")
                                    :payload     payload})
     (when-let [send-fn (:send-fn svc)]
       (send-fn payload))
     {:service-key service-key
      :payload payload})))

(defn jsonrpc-request!
  ([ctx service-key {:keys [id method params]} {:keys [timeout-ms] :or {timeout-ms 500}}]
   (jsonrpc-request! ctx service-key {:id id :method method :params params} {:timeout-ms timeout-ms} nil))
  ([ctx service-key {:keys [id method params]} {:keys [timeout-ms] :or {timeout-ms 500}} trace-opts]
   (send-service-request!
    ctx service-key
    {:request-id id
     :payload {"jsonrpc" "2.0"
               "id" id
               "method" method
               "params" params}
     :timeout-ms timeout-ms}
    trace-opts)))

(defn jsonrpc-notify!
  ([ctx service-key {:keys [method params]}]
   (jsonrpc-notify! ctx service-key {:method method :params params} nil))
  ([ctx service-key {:keys [method params]} trace-opts]
   (send-service-notification!
    ctx service-key
    {"jsonrpc" "2.0"
     "method" method
     "params" params}
    trace-opts)))
