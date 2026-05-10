(ns psi.agent-session.workflow-display-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow.display :as sut]))

(deftest merged-display-prefers-public-fields-test
  (testing "public display fields override fallback display fields"
    (is (= {:top-line "public top"
            :detail-line "public detail"
            :question-lines ["public q"]
            :action-line "fallback action"}
           (sut/merged-display {:top-line "fallback top"
                                :detail-line "fallback detail"
                                :question-lines ["fallback q"]
                                :action-line "fallback action"}
                               {:top-line "public top"
                                :detail-line "public detail"
                                :question-lines ["public q"]})))))

(deftest merged-display-falls-back-when-public-missing-test
  (testing "fallback display fields are used when public fields are missing"
    (is (= {:top-line "fallback top"
            :detail-line "fallback detail"
            :question-lines ["fallback q"]
            :action-line "fallback action"}
           (sut/merged-display {:top-line "fallback top"
                                :detail-line "fallback detail"
                                :question-lines ["fallback q"]
                                :action-line "fallback action"}
                               {})))))

(deftest display-lines-orders-lines-test
  (testing "display-lines emits top, detail, questions, then action"
    (is (= ["top"
            "detail"
            "q1"
            "q2"
            "action"]
           (sut/display-lines {:top-line "top"
                               :detail-line "detail"
                               :question-lines ["q1" "q2"]
                               :action-line "action"})))))

(deftest display-lines-decorates-top-line-test
  (testing "display-lines can decorate the top line for richer widget entries"
    (is (= [{:text "top" :action {:type :command :command "/rm 1"}}
            "detail"]
           (sut/display-lines {:top-line "top"
                               :detail-line "detail"}
                              :decorate-top-line
                              (fn [top]
                                {:text top
                                 :action {:type :command :command "/rm 1"}}))))))

(deftest text-lines-projects-rendered-lines-to-plain-text-test
  (testing "text-lines converts decorated workflow lines to plain text for CLI/list consumers"
    (is (= ["top" "detail" "action"]
           (vec (sut/text-lines [{:text "top"
                                  :action {:type :command :command "/rm 1"}}
                                 "detail"
                                 {:text "action"
                                  :action {:type :command :command "/retry 1"}}]))))))
