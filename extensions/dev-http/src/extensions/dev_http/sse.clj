(ns extensions.dev-http.sse
  "Server-Sent Events live-update transport. A route may expose a
   `text/event-stream` feed; browser pages subscribe via `EventSource` and
   receive pushed events without manual refresh.

   Kept minimal — one demonstrated feed (the session-route registry snapshot),
   not a general pub/sub framework. SSE feeds live inside the token-gated
   subtree."
  (:require
   [extensions.dev-http.registry :as registry]
   [org.httpkit.server :as http]))

(defn event
  "Format `text` as a single SSE `data:` event block."
  [text]
  (str "data: " text "\n\n"))

(defn make-handler
  "Build an SSE ring handler backed by an http-kit async channel. `emit-fn` is
   invoked `(emit-fn send! close!)` once the event stream is open, where
   `send!` pushes one SSE event (a string) and `close!` ends the stream."
  [emit-fn]
  (fn [request]
    (http/as-channel
     request
     {:on-open
      (fn [ch]
        ;; The first send sets the event-stream response headers.
        (http/send! ch
                    {:status  200
                     :headers {"content-type"  "text/event-stream; charset=utf-8"
                               "cache-control" "no-cache"}
                     :body    (event "open")}
                    false)
        (emit-fn (fn [text] (http/send! ch (event text) false))
                 (fn [] (http/close ch))))})))

(defn registry-feed-handler
  "Demonstrated live feed: emits a snapshot of the current session-route count
   then closes. `registry-atom` is the session-route registry atom."
  [registry-atom]
  (make-handler
   (fn [send! close!]
     (send! (str "routes " (count (registry/entries registry-atom))))
     (close!))))
