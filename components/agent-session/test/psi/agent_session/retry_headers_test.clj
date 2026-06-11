(ns psi.agent-session.retry-headers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.session-state.model :as session]))

(deftest retry-after-delay-ms-test
  (testing "parses delta seconds"
    (is (= 8000 (session/retry-after-delay-ms "8" 1000))))

  (testing "parses HTTP date"
    (let [now-ms 10000
          future (-> (java.time.Instant/ofEpochMilli (+ now-ms 5000))
                     (.atZone java.time.ZoneOffset/UTC)
                     (.format java.time.format.DateTimeFormatter/RFC_1123_DATE_TIME))]
      (is (= 5000
             (session/retry-after-delay-ms future now-ms)))))

  (testing "returns nil for invalid values"
    (is (nil? (session/retry-after-delay-ms "later" 1000)))))

(deftest rate-limit-reset->timing-test
  (testing "interprets large values as epoch milliseconds"
    (is (= {:reset-at 1700000000000}
           (session/rate-limit-reset->timing "1700000000000" 1000))))

  (testing "interprets billion-scale values as epoch seconds"
    (is (= {:reset-at 1700000000000}
           (session/rate-limit-reset->timing "1700000000" 1000))))

  (testing "interprets smaller values as relative seconds"
    (is (= {:reset-after-ms 32000
            :reset-at 42000}
           (session/rate-limit-reset->timing "32" 10000)))))

(deftest retry-metadata-test
  (testing "prefers Retry-After over exponential backoff and normalizes rate-limit headers case-insensitively"
    (is (= {:active? true
            :attempt 1
            :delay-ms 8000
            :delay-source :retry-after
            :resume-at 18000
            :rate-limit {:limit 5000
                         :remaining 0
                         :reset-after-ms 32000
                         :reset-at 42000}}
           (session/retry-metadata {"Retry-After" "8"
                                    "X-RateLimit-Limit" "5000"
                                    "ratelimit-remaining" "0"
                                    "RateLimit-Reset" "32"}
                                   1
                                   4000
                                   10000))))

  (testing "accepts keyword header names from parsed SSE metadata"
    (is (= {:active? true
            :attempt 1
            :delay-ms 8000
            :delay-source :retry-after
            :resume-at 18000
            :rate-limit {:remaining 0}}
           (session/retry-metadata {:Retry-After "8"
                                    :RateLimit-Remaining "0"}
                                   1
                                   4000
                                   10000))))

  (testing "falls back to exponential backoff when retry-after is invalid"
    (is (= {:active? true
            :attempt 2
            :delay-ms 250
            :delay-source :exponential-backoff
            :resume-at 1250
            :rate-limit nil}
           (session/retry-metadata {"Retry-After" "later"}
                                   2
                                   250
                                   1000)))))
