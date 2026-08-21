(ns psi.turn-runtime.retry-provider-test-support
  "Scripted nullable provider support for retry-runtime tests."
  (:require
   [psi.ai.core :as ai]))

(defn- assistant-message->events
  [{:keys [content stop-reason error-message http-status provider-error/headers]}]
  (if (= :error stop-reason)
    [(cond-> {:type :error
              :error-message error-message}
       http-status (assoc :http-status http-status)
       headers (assoc :provider-error/headers headers))]
    (into [{:type :start}]
          (concat
           (keep-indexed (fn [content-index item]
                           (when (= :text (:type item))
                             {:type :text-delta
                              :content-index content-index
                              :delta (:text item)}))
                         content)
           [{:type :done :reason stop-reason}]))))

(defn nullable-provider-context
  "Return an isolated AI context whose provider executes `response-fn` through
   the real streaming boundary. `response-fn` returns the same turn-result maps
   formerly supplied by direct execution-function replacements."
  [response-fn]
  (let [provider {:name :nullable-retry-provider
                  :stream (fn [_conversation _model _options consume-fn]
                            (doseq [event (assistant-message->events
                                           (:assistant-message (response-fn)))]
                              (consume-fn event)))}]
    (ai/create-context
     {:providers {:anthropic provider
                  "anthropic" provider
                  :openai provider
                  "openai" provider}})))

(defmacro with-nullable-provider
  "Bind an AI context to a scripted nullable provider for `body`."
  [[binding response-fn] & body]
  `(let [~binding (nullable-provider-context ~response-fn)]
     ~@body))
