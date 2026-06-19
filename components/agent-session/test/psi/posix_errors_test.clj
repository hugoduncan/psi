(ns psi.posix-errors-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.posix-errors :as posix-errors]))

(deftest error-string-test
  (testing "known errno returns string"
    (is (= "No such file or directory" (posix-errors/error-string 2)))
    (is (= "Permission denied" (posix-errors/error-string 13)))
    (is (= "Operation not permitted" (posix-errors/error-string 1)))
    (is (= "Broken pipe" (posix-errors/error-string 32)))
    (is (= "Connection refused" (posix-errors/error-string 61))))

  (testing "zero and unknown return nil"
    (is (nil? (posix-errors/error-string 0)))
    (is (nil? (posix-errors/error-string -1)))
    (is (nil? (posix-errors/error-string 999))))

  (testing "errno-map covers 1-108"
    (is (every? #(some? (posix-errors/error-string %)) (range 1 108)))))
