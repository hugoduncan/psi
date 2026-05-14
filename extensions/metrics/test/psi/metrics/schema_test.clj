(ns psi.metrics.schema-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.metrics.schema :as schema]))

(deftest valid?-accepts-empty-metrics-test
  ;; A freshly-initialised metrics map with all empty sub-maps is valid.
  (is (schema/valid? {:tools      {}
                      :workflows  {}
                      :commands   {}
                      :operations {}
                      :tokens     {}
                      :updated-at nil})))

(deftest valid?-accepts-populated-metrics-test
  ;; Fully-populated metrics with realistic data is valid.
  (is (schema/valid?
       {:tools      {"read" {:invocations 10 :errors 1 :error-reasons {"timeout" 1}}}
        :workflows  {"builder" {:invocations 3}}
        :commands   {"metrics" {:invocations 2}}
        :operations {"metrics/summary" {:invocations 5}}
        :tokens     {"claude-sonnet-4" {:input 1000 :output 200 :cache-read 500 :cache-write 100}}
        :updated-at "2026-05-14T10:00:00Z"})))

(deftest valid?-rejects-missing-required-key-test
  ;; A map missing :tools fails validation.
  (is (not (schema/valid? {:workflows  {}
                           :commands   {}
                           :operations {}
                           :tokens     {}
                           :updated-at nil}))))

(deftest valid?-rejects-wrong-type-for-invocations-test
  ;; :invocations must be an integer, not a string.
  (is (not (schema/valid?
            {:tools      {"bash" {:invocations "not-a-number" :errors 0 :error-reasons {}}}
             :workflows  {}
             :commands   {}
             :operations {}
             :tokens     {}
             :updated-at nil}))))

(deftest valid?-rejects-wrong-token-shape-test
  ;; Token totals must have all four keys as ints.
  (is (not (schema/valid?
            {:tools      {}
             :workflows  {}
             :commands   {}
             :operations {}
             :tokens     {"gpt-4o" {:input 100}}
             :updated-at nil}))))

(deftest explain-returns-non-nil-for-invalid-test
  ;; explain provides diagnostic info for invalid maps.
  (is (some? (schema/explain {:not "valid"}))))
