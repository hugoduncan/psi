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
      (is (instance? java.util.Date (get-in parsed [:data :message :content 0 :timestamp])))))

  (testing "entry->line and parse-line preserve plain scalar/map shapes alongside instants"
    (let [entry {:type :session
                 :id "sess-1"
                 :version 4
                 :timestamp (Instant/parse "2026-01-02T03:04:05Z")
                 :worktree-path "/tmp/project"
                 :parent-session-id nil}
          parsed (codec/parse-line (codec/entry->line entry))]
      (is (= :session (:type parsed)))
      (is (= "sess-1" (:id parsed)))
      (is (= 4 (:version parsed)))
      (is (= "/tmp/project" (:worktree-path parsed)))
      (is (contains? parsed :parent-session-id))
      (is (nil? (:parent-session-id parsed)))
      (is (instance? java.util.Date (:timestamp parsed))))))

(deftest parse-line-guards-test
  (testing "parse-line returns nil for blank, malformed, and non-map lines"
    (is (nil? (codec/parse-line "")))
    (is (nil? (codec/parse-line "   \n")))
    (is (nil? (codec/parse-line "not edn")))
    (is (nil? (codec/parse-line "[:not-a-map]")))))
