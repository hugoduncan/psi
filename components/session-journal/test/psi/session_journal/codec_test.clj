(ns psi.session-journal.codec-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.session-journal.codec :as codec])
  (:import
   (java.time Instant)))

(deftest entry-line-round-trip-test
  (testing "entry->line and parse-line round-trip nested instants"
    (let [entry {:kind :message
                 :timestamp (Instant/parse "2026-01-02T03:04:05Z")
                 :data {:message {:role "assistant"
                                  :content [{:type :text
                                             :text "hi"
                                             :timestamp (Instant/parse "2026-01-02T03:04:06Z")}]}}}
          parsed (codec/parse-line (codec/entry->line entry))]
      (is (= :message (:kind parsed)))
      (is (instance? java.util.Date (:timestamp parsed)))
      (is (= "assistant" (get-in parsed [:data :message :role])))
      (is (= "hi" (get-in parsed [:data :message :content 0 :text])))
      (is (instance? java.util.Date (get-in parsed [:data :message :content 0 :timestamp]))))))

(deftest parse-line-guards-test
  (testing "parse-line returns nil for blank, malformed, and non-map lines"
    (is (nil? (codec/parse-line "")))
    (is (nil? (codec/parse-line "   \n")))
    (is (nil? (codec/parse-line "not edn")))
    (is (nil? (codec/parse-line "[:not-a-map]")))))
