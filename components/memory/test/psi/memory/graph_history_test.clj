(ns psi.memory.graph-history-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.memory.graph-history :as graph-history]))

(deftest trim-window-keeps-latest-n
  (testing "entries beyond the limit are trimmed from the front"
    (is (= [3 4 5]
           (graph-history/trim-window [1 2 3 4 5] 3)))))

(deftest trim-window-keeps-seq-when-under-limit
  (testing "entries at or under limit are unchanged"
    (is (= [1 2]
           (graph-history/trim-window [1 2] 3)))))
