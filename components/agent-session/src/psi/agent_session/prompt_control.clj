(ns psi.agent-session.prompt-control
  (:require
   [psi.turn :as turn]))

(defn prompt-in!
  "Compatibility facade over `psi.turn/prompt-in!`."
  ([ctx session-id text]
   (turn/prompt-in! ctx session-id text))
  ([ctx session-id text images]
   (turn/prompt-in! ctx session-id text images))
  ([ctx session-id text images opts]
   (turn/prompt-in! ctx session-id text images opts)))

(defn prompt-execution-result-in!
  "Compatibility facade over `psi.turn/prompt-execution-result-in!`."
  ([ctx session-id text]
   (turn/prompt-execution-result-in! ctx session-id text))
  ([ctx session-id text images]
   (turn/prompt-execution-result-in! ctx session-id text images))
  ([ctx session-id text images opts]
   (turn/prompt-execution-result-in! ctx session-id text images opts)))

(defn last-assistant-message-in
  [ctx session-id]
  (turn/last-assistant-message-in ctx session-id))

(defn steer-in!
  [ctx session-id text]
  (turn/steer-in! ctx session-id text))

(defn follow-up-in!
  [ctx session-id text]
  (turn/follow-up-in! ctx session-id text))

(defn queue-while-streaming-in!
  [ctx session-id text behavior]
  (turn/queue-while-streaming-in! ctx session-id text behavior))

(defn request-interrupt-in!
  [ctx session-id]
  (turn/request-interrupt-in! ctx session-id))

(defn abort-in!
  [ctx session-id]
  (turn/abort-in! ctx session-id))

(defn consume-queued-input-text-in!
  [ctx session-id]
  (turn/consume-queued-input-text-in! ctx session-id))
