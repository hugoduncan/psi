(ns psi.turn-runtime.retry-provider-test-support-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.turn-runtime.retry-provider-test-support :as retry-provider]))

(deftest response->events-rejects-invalid-script-responses-test
  ;; Script exhaustion and malformed entries must fail at the provider boundary.
  (testing "rejects an exhausted response script"
    (let [error (try
                  (#'retry-provider/response->events nil)
                  nil
                  (catch clojure.lang.ExceptionInfo exception
                    exception))]
      (is (= "Invalid scripted provider response" (ex-message error)))
      (is (= {:response nil
              :supported-shapes [:stream-events :assistant-message]}
             (ex-data error)))))
  (testing "rejects a response without a supported shape"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid scripted provider response"
         (#'retry-provider/response->events {:unexpected :response}))))
  (testing "rejects an ambiguous response with both supported shapes"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid scripted provider response"
         (#'retry-provider/response->events
          {:stream-events []
           :assistant-message {:content [] :stop-reason :end-turn}}))))
  (testing "rejects a nil supported shape"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid scripted provider response"
         (#'retry-provider/response->events {:assistant-message nil})))))
