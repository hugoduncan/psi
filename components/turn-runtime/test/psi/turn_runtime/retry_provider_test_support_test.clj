(ns psi.turn-runtime.retry-provider-test-support-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.turn-runtime.retry-provider-test-support :as retry-provider]))

(defn- scripted-response-error
  [response]
  (let [ctx (retry-provider/nullable-provider-context (constantly response))
        provider (get @(:provider-registry ctx) :anthropic)]
    (try
      ((:stream provider) nil nil nil (fn [_]))
      nil
      (catch clojure.lang.ExceptionInfo exception
        exception))))

(defn- assert-invalid-scripted-response
  [response]
  (let [error (scripted-response-error response)]
    (is (= "Invalid scripted provider response" (ex-message error)))
    (is (= {:response response
            :supported-shapes [:stream-events :assistant-message]}
           (ex-data error)))))

(deftest nullable-provider-rejects-invalid-script-responses-test
  ;; Script shape errors must retain their response context at the provider boundary.
  (doseq [[label response]
          [["exhausted response script" nil]
           ["response without a supported shape" {:unexpected :response}]
           ["ambiguous response with both supported shapes"
            {:stream-events []
             :assistant-message {:content [] :stop-reason :stop}}]
           ["nil supported shape" {:assistant-message nil}]]]
    (testing label
      (assert-invalid-scripted-response response))))

(deftest nullable-provider-rejects-malformed-selected-payload-test
  ;; Selected payloads must satisfy content and stream-topology invariants.
  (doseq [[label response]
          [["assistant message without a stop reason"
            {:assistant-message {}}]
           ["error assistant message without an error message"
            {:assistant-message {:stop-reason :error}}]
           ["successful assistant message without content"
            {:assistant-message {:stop-reason :stop}}]
           ["assistant message with nil content item"
            {:assistant-message {:stop-reason :stop :content [nil]}}]
           ["assistant message with unsupported content item"
            {:assistant-message {:stop-reason :stop
                                 :content [{:type :image :url "image.png"}]}}]
           ["assistant message with malformed text content"
            {:assistant-message {:stop-reason :stop
                                 :content [{:type :text}]}}]
           ["stream events with a non-sequential payload"
            {:stream-events {:type :done :reason :stop}}]
           ["stream events with a malformed event"
            {:stream-events [{:type :start} {}]}]
           ["stream events with an unsupported event type"
            {:stream-events [{:type :start} {:type :unexpected}]}]
           ["stream events without a terminal event"
            {:stream-events [{:type :start}]}]
           ["done event without a stop reason"
            {:stream-events [{:type :start} {:type :done}]}]
           ["error event without an error message"
            {:stream-events [{:type :start} {:type :error}]}]
           ["stream events before start"
            {:stream-events [{:type :text-delta :content-index 0 :delta "early"}
                             {:type :done :reason :stop}]}]
           ["duplicate start event"
            {:stream-events [{:type :start}
                             {:type :start}
                             {:type :done :reason :stop}]}]
           ["misplaced start event"
            {:stream-events [{:type :text-delta :content-index 0 :delta "early"}
                             {:type :start}
                             {:type :done :reason :stop}]}]
           ["terminal event before last event"
            {:stream-events [{:type :start}
                             {:type :done :reason :stop}
                             {:type :text-delta :content-index 0 :delta "late"}
                             {:type :done :reason :stop}]}]
           ["duplicate terminal event"
            {:stream-events [{:type :start}
                             {:type :done :reason :stop}
                             {:type :done :reason :stop}]}]]]
    (testing label
      (assert-invalid-scripted-response response))))
