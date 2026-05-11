(ns psi.github.narrowing-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.github.narrowing :as sut]))

;;; ---------------------------------------------------------------------------
;;; Test helpers

(defn- item
  [number title]
  {"number" number "title" title})

(def ^:private issue-url-pattern #"/issues/(\d+)")
(def ^:private pr-url-pattern    #"/pull/(\d+)")

(defn- narrow
  ([candidates input]
   (narrow candidates input issue-url-pattern "Cannot extract issue number from URL"))
  ([candidates input url-pattern err-msg]
   (sut/narrow-candidates candidates input url-pattern err-msg)))

;;; ---------------------------------------------------------------------------
;;; extract-url-number

(deftest extract-url-number-issue-test
  (testing "extracts number from /issues/NNN URL"
    (is (= 42 (sut/extract-url-number issue-url-pattern "https://github.com/org/repo/issues/42"))))
  (testing "returns nil for /pull/NNN URL when using issue pattern"
    (is (nil? (sut/extract-url-number issue-url-pattern "https://github.com/org/repo/pull/42")))))

(deftest extract-url-number-pr-test
  (testing "extracts number from /pull/NNN URL"
    (is (= 5 (sut/extract-url-number pr-url-pattern "https://github.com/org/repo/pull/5"))))
  (testing "returns nil for /issues/NNN URL when using pr pattern"
    (is (nil? (sut/extract-url-number pr-url-pattern "https://github.com/org/repo/issues/5")))))

;;; ---------------------------------------------------------------------------
;;; narrow-candidates: nil input → no narrowing

(deftest nil-input-returns-all-candidates-test
  (testing "nil input → all candidates returned unchanged"
    (let [items [(item 1 "A") (item 2 "B")]]
      (is (= {:status :ok :candidates items}
             (narrow items nil))))))

;;; ---------------------------------------------------------------------------
;;; narrow-candidates: integer narrowing

(deftest integer-narrowing-test
  (testing "integer string → exact number match"
    (let [result (narrow [(item 7 "A") (item 42 "B")] "42")]
      (is (= :ok (:status result)))
      (is (= [(item 42 "B")] (:candidates result)))))

  (testing "integer with leading zeros → parsed as decimal"
    (let [result (narrow [(item 7 "A") (item 42 "B")] "007")]
      (is (= :ok (:status result)))
      (is (= [(item 7 "A")] (:candidates result))))))

;;; ---------------------------------------------------------------------------
;;; narrow-candidates: URL narrowing

(deftest url-narrowing-valid-issue-url-test
  (testing "valid issue URL → number extracted and matched"
    (let [result (narrow [(item 7 "A") (item 42 "B")]
                         "https://github.com/org/repo/issues/42"
                         issue-url-pattern "err")]
      (is (= :ok (:status result)))
      (is (= [(item 42 "B")] (:candidates result))))))

(deftest url-narrowing-valid-pr-url-test
  (testing "valid PR URL → number extracted and matched"
    (let [result (narrow [(item 7 "A") (item 5 "B")]
                         "https://github.com/org/repo/pull/5"
                         pr-url-pattern "err")]
      (is (= :ok (:status result)))
      (is (= [(item 5 "B")] (:candidates result))))))

(deftest url-narrowing-wrong-pattern-returns-error-test
  (testing "issue URL with PR pattern → error"
    (let [result (narrow [(item 42 "B")]
                         "https://github.com/org/repo/issues/42"
                         pr-url-pattern "Cannot extract PR number from URL")]
      (is (= :error (:status result)))
      (is (= :psi.github/invalid-url-input (:reason result)))
      (is (= "Cannot extract PR number from URL" (:message result))))))

;;; ---------------------------------------------------------------------------
;;; narrow-candidates: text substring narrowing

(deftest text-narrowing-test
  (testing "text substring match (case-insensitive)"
    (let [result (narrow [(item 7 "Fix the LOGIN bug") (item 42 "Add Dark Mode")]
                         "dark mode")]
      (is (= :ok (:status result)))
      (is (= [(item 42 "Add Dark Mode")] (:candidates result)))))

  (testing "no text match → empty candidates"
    (let [result (narrow [(item 7 "Fix the login bug")] "dark mode")]
      (is (= :ok (:status result)))
      (is (empty? (:candidates result))))))
