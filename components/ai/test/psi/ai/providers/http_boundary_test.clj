(ns psi.ai.providers.http-boundary-test
  (:require [clojure.test :refer [deftest is testing]]
            [psi.ai.providers.http-boundary :as http-boundary]))

(deftest nullable-returns-scripted-responses-and-records-requests-test
  ;; Proves the nullable's public state-based contract without network I/O.
  (testing "responses are consumed in order and requests remain observable"
    (let [boundary (http-boundary/nullable
                    [{:status 200 :body "first"}
                     (fn [{:keys [url]}]
                       {:status 201 :body url})])]
      (is (= {:status 200 :body "first"}
             (http-boundary/post! boundary "https://one.test" {:body "a"})))
      (is (= {:status 201 :body "https://two.test"}
             (http-boundary/post! boundary "https://two.test" {:body "b"})))
      (is (= [{:url "https://one.test" :request {:body "a"}}
              {:url "https://two.test" :request {:body "b"}}]
             (http-boundary/requests boundary))))))

(deftest nullable-surfaces-scripted-and-exhaustion-errors-test
  ;; Infrastructure failures remain deterministic and request state is retained.
  (testing "a scripted Throwable is thrown"
    (let [failure  (ex-info "connection reset" {:kind :reset})
          boundary (http-boundary/nullable [failure])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"connection reset"
                            (http-boundary/post! boundary "https://one.test" {})))
      (is (= 1 (count (http-boundary/requests boundary))))))
  (testing "an exhausted script fails clearly"
    (let [boundary (http-boundary/nullable [])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"no scripted response"
                            (http-boundary/post! boundary "https://one.test" {})))
      (is (= 1 (count (http-boundary/requests boundary)))))))
