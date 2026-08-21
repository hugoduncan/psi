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

(def ^:private stop-reasons
  #{:stop :length :tool-use :error :aborted})

(defn- content-item-valid?
  [{:keys [type text] :as item}]
  (and (map? item)
       (= :text type)
       (string? text)))

(defn- assistant-message-valid?
  [{:keys [content stop-reason error-message] :as assistant-message}]
  (and (map? assistant-message)
       (contains? stop-reasons stop-reason)
       (if (= :error stop-reason)
         (string? error-message)
         (and (sequential? content)
              (every? content-item-valid? content)))))

(defn- stream-event-valid?
  [{:keys [type content-index delta reason error-message] :as event}]
  (and (map? event)
       (case type
         :start true
         :text-delta (and (nat-int? content-index) (string? delta))
         :done (contains? stop-reasons reason)
         :error (string? error-message)
         false)))

(defn- stream-events-valid?
  [events]
  (and (sequential? events)
       (seq events)
       (= :start (:type (first events)))
       (every? #(= :text-delta (:type %)) (butlast (rest events)))
       (#{:done :error} (:type (last events)))
       (every? stream-event-valid? events)))

(defn- response-payload-valid?
  [shape payload]
  (case shape
    :stream-events (stream-events-valid? payload)
    :assistant-message (assistant-message-valid? payload)
    false))

(defn- response->events
  [response]
  (let [response-map? (map? response)
        supported-shapes (cond-> []
                           (and response-map?
                                (contains? response :stream-events))
                           (conj :stream-events)

                           (and response-map?
                                (contains? response :assistant-message))
                           (conj :assistant-message))
        selected-shape (first supported-shapes)
        payload (get response selected-shape)]
    (when-not (and response-map?
                   (= 1 (count supported-shapes))
                   (response-payload-valid? selected-shape payload))
      (throw (ex-info "Invalid scripted provider response"
                      {:response response
                       :supported-shapes [:stream-events :assistant-message]})))
    (case selected-shape
      :stream-events payload
      :assistant-message (assistant-message->events payload))))

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
