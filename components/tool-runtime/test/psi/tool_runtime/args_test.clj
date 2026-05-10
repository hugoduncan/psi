(ns psi.tool-runtime.args-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.tool-runtime.args :as args]))

(deftest parse-args-strict-test
  (testing "valid json object parses with ok? true"
    (is (= {:ok? true :value {"a" 1}}
           (args/parse-args-strict "{\"a\":1}"))))

  (testing "invalid json returns ok? false"
    (is (= {:ok? false :value nil}
           (args/parse-args-strict "not json"))))

  (testing "non-map json returns ok? false"
    (is (= {:ok? false :value nil}
           (args/parse-args-strict "[1,2,3]")))))

(deftest parse-args-test
  (testing "parse-args returns parsed map or empty map"
    (is (= {"a" 1} (args/parse-args "{\"a\":1}")))
    (is (= {} (args/parse-args "[1,2,3]")))
    (is (= {} (args/parse-args "oops")))))
