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

(defn- response->events
  [response]
  (let [response-map? (map? response)
        supported-shapes (cond-> []
                           (and response-map?
                                (contains? response :stream-events))
                           (conj :stream-events)

                           (and response-map?
                                (contains? response :assistant-message))
                           (conj :assistant-message))]
    (when-not (and response-map?
                   (= 1 (count supported-shapes))
                   (some? (get response (first supported-shapes))))
      (throw (ex-info "Invalid scripted provider response"
                      {:response response
                       :supported-shapes [:stream-events :assistant-message]})))
    (case (first supported-shapes)
      :stream-events (:stream-events response)
      :assistant-message (assistant-message->events
                          (:assistant-message response)))))

(defn nullable-provider-context
  "Return an isolated AI context whose provider executes `response-fn` through
   the real streaming boundary. Each response may provide explicit
   `:stream-events` or the same `:assistant-message` maps formerly supplied by
   direct execution-function replacements."
  [response-fn]
  (let [provider {:name :nullable-retry-provider
                  :stream (fn [_conversation _model _options consume-fn]
                            (doseq [event (response->events (response-fn))]
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
