(ns psi.github.find-issue-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [psi.github.find-issue :as sut]))

;;; ---------------------------------------------------------------------------
;;; Test helpers

(defn- issue
  [number title url]
  {"number" number "title" title "url" url "state" "open" "labels" []})

(defn- stub-shell
  "Returns a shell-fn stub that always returns a successful response with `issues`."
  [issues]
  (fn [& _args]
    {:exit 0
     :out  (json/generate-string issues)
     :err  ""}))

(defn- error-shell
  "Returns a shell-fn stub that simulates a non-zero exit."
  [err-msg]
  (fn [& _args]
    {:exit 1
     :out  ""
     :err  err-msg}))

(defn- capturing-shell
  "Returns [shell-fn calls*] where calls* captures each invocation's arg list."
  [issues]
  (let [calls* (atom [])]
    [(fn [& args]
       (swap! calls* conj (vec args))
       {:exit 0
        :out  (json/generate-string issues)
        :err  ""})
     calls*]))

(defn- invoke
  "Call sut/invoke with a stub ctx and args map."
  [shell-fn args]
  (sut/invoke {:ctx {:github-shell-fn shell-fn}
               :args args}))

;;; ---------------------------------------------------------------------------
;;; gh CLI arg construction

(deftest gh-cli-args-are-constructed-correctly-test
  (testing "gh issue list receives exact --state, --json, and --label args"
    (let [[shell-fn calls*] (capturing-shell [(issue 1 "x" "https://github.com/org/repo/issues/1")])]
      (invoke shell-fn {:labels ["enhancement" "refine"]})
      (is (= 1 (count @calls*)))
      (is (= ["gh" "issue" "list"
              "--state" "open"
              "--json" "number,title,url,state,labels"
              "--label" "enhancement"
              "--label" "refine"]
             (first @calls*))))))

;;; ---------------------------------------------------------------------------
;;; No candidates → error

(deftest no-candidates-returns-error-test
  (testing "no matching issues → :psi.github/no-matching-issue error"
    (let [result (invoke (stub-shell [])
                         {:labels ["enhancement" "refine"]})]
      (is (= :error (:status result)))
      (is (= :psi.github/no-matching-issue (:reason result)))
      (is (string? (:message result))))))

;;; ---------------------------------------------------------------------------
;;; Single candidate → correct structured map + slug + handoff

(deftest single-candidate-returns-correct-map-test
  (testing "single candidate → :ok with correct data map and Markdown summary"
    (let [result (invoke (stub-shell [(issue 42 "Add foo bar" "https://github.com/org/repo/issues/42")])
                         {:labels ["enhancement" "refine"]})]
      (is (= :ok (:status result)))
      (is (= {:issue-number 42
              :issue-title "Add foo bar"
              :issue-url "https://github.com/org/repo/issues/42"
              :worktree-description "add-foo-bar"}
             (:data result)))
      (is (string? (:summary result)))
      (is (str/includes? (:summary result) "## Handoff Data"))
      (is (str/includes? (:summary result) "issue_number: 42"))
      (is (str/includes? (:summary result) "issue_title: Add foo bar"))
      (is (str/includes? (:summary result) "issue_url: https://github.com/org/repo/issues/42"))
      (is (str/includes? (:summary result) "worktree_description: add-foo-bar")))))

;;; ---------------------------------------------------------------------------
;;; Multiple candidates + no narrowing → lowest number selected

(deftest multiple-candidates-no-narrowing-selects-lowest-test
  (testing "multiple candidates → lowest issue number selected"
    (let [result (invoke (stub-shell [(issue 99 "Issue 99" "https://github.com/org/repo/issues/99")
                                      (issue 7  "Issue 7"  "https://github.com/org/repo/issues/7")
                                      (issue 42 "Issue 42" "https://github.com/org/repo/issues/42")])
                         {:labels ["enhancement" "refine"]})]
      (is (= :ok (:status result)))
      (is (= 7 (get-in result [:data :issue-number]))))))

;;; ---------------------------------------------------------------------------
;;; Narrowing by integer

(deftest narrowing-by-integer-test
  (testing "integer input → exact number match"
    (let [result (invoke (stub-shell [(issue 7  "Issue 7"  "https://github.com/org/repo/issues/7")
                                      (issue 42 "Issue 42" "https://github.com/org/repo/issues/42")])
                         {:labels ["enhancement" "refine"]
                          :input "42"})]
      (is (= :ok (:status result)))
      (is (= 42 (get-in result [:data :issue-number])))))

  (testing "integer input with leading zeros → parsed as decimal"
    (let [result (invoke (stub-shell [(issue 7  "Issue 7"  "https://github.com/org/repo/issues/7")])
                         {:labels ["enhancement"]
                          :input "007"})]
      (is (= :ok (:status result)))
      (is (= 7 (get-in result [:data :issue-number]))))))

;;; ---------------------------------------------------------------------------
;;; Narrowing by URL

(deftest narrowing-by-url-test
  (testing "URL input → issue number extracted and matched"
    (let [result (invoke (stub-shell [(issue 7  "Issue 7"  "https://github.com/org/repo/issues/7")
                                      (issue 42 "Issue 42" "https://github.com/org/repo/issues/42")])
                         {:labels ["enhancement" "refine"]
                          :input "https://github.com/org/repo/issues/42"})]
      (is (= :ok (:status result)))
      (is (= 42 (get-in result [:data :issue-number]))))))

;;; ---------------------------------------------------------------------------
;;; Invalid URL (no /issues/NNN segment)

(deftest invalid-url-returns-error-test
  (testing "URL without /issues/NNN → :psi.github/invalid-url-input error"
    (let [result (invoke (stub-shell [(issue 42 "Issue 42" "https://github.com/org/repo/issues/42")])
                         {:labels ["enhancement"]
                          :input "https://github.com/org/repo/pull/5"})]
      (is (= :error (:status result)))
      (is (= :psi.github/invalid-url-input (:reason result)))
      (is (str/includes? (:message result) "Cannot extract issue number from URL")))))

;;; ---------------------------------------------------------------------------
;;; Text narrowing → zero candidates → error

(deftest narrowing-by-text-no-match-returns-error-test
  (testing "text narrowing that filters to zero candidates → :psi.github/no-matching-issue"
    (let [result (invoke (stub-shell [(issue 42 "Add dark mode" "https://github.com/org/repo/issues/42")])
                         {:labels ["enhancement"]
                          :input "login bug"})]
      (is (= :error (:status result)))
      (is (= :psi.github/no-matching-issue (:reason result))))))

;;; ---------------------------------------------------------------------------
;;; Narrowing by text substring (case-insensitive)

(deftest narrowing-by-text-substring-test
  (testing "text substring match selects matching issue"
    (let [result (invoke (stub-shell [(issue 7  "Fix the login bug"  "https://github.com/org/repo/issues/7")
                                      (issue 42 "Add dark mode"      "https://github.com/org/repo/issues/42")])
                         {:labels ["enhancement"]
                          :input "dark mode"})]
      (is (= :ok (:status result)))
      (is (= 42 (get-in result [:data :issue-number])))))

  (testing "text substring match is case-insensitive (case-folding assertion)"
    (let [result (invoke (stub-shell [(issue 7  "Fix the LOGIN bug"  "https://github.com/org/repo/issues/7")
                                      (issue 42 "Add Dark Mode"      "https://github.com/org/repo/issues/42")])
                         {:labels ["enhancement"]
                          :input "dark MODE"})]
      (is (= :ok (:status result)))
      (is (= 42 (get-in result [:data :issue-number]))))))

;;; ---------------------------------------------------------------------------
;;; Non-zero gh CLI exit → :psi.github/shell-error

(deftest non-zero-exit-returns-shell-error-test
  (testing "non-zero gh exit → :psi.github/shell-error with :err message"
    (let [result (invoke (error-shell "gh: not authenticated")
                         {:labels ["enhancement"]})]
      (is (= :error (:status result)))
      (is (= :psi.github/shell-error (:reason result)))
      (is (= "gh: not authenticated" (:message result))))))

;;; ---------------------------------------------------------------------------
;;; nil input → treated as no narrowing

(deftest nil-input-treated-as-no-narrowing-test
  (testing "nil :input → no narrowing applied, lowest candidate selected"
    (let [result (invoke (stub-shell [(issue 99 "Issue 99" "https://github.com/org/repo/issues/99")
                                      (issue 5  "Issue 5"  "https://github.com/org/repo/issues/5")])
                         {:labels ["enhancement" "refine"]
                          :input nil})]
      (is (= :ok (:status result)))
      (is (= 5 (get-in result [:data :issue-number]))))))

;;; ---------------------------------------------------------------------------
;;; Slug derivation edge cases

(deftest slug-derivation-test
  (testing "slug is derived from title: lower-case, extract words, join, truncate at 40"
    (let [result (invoke (stub-shell [(issue 1 "Add foo-bar baz" "https://github.com/org/repo/issues/1")])
                         {:labels ["enhancement"]})]
      (is (= "add-foo-bar-baz" (get-in result [:data :worktree-description])))))

  (testing "slug is hard-truncated at 40 chars and never ends with -"
    (let [long-title "This is a very long issue title that exceeds forty characters easily"
          result (invoke (stub-shell [(issue 1 long-title "https://github.com/org/repo/issues/1")])
                         {:labels ["enhancement"]})]
      (is (= "this-is-a-very-long-issue-title-that-exc"
             (get-in result [:data :worktree-description])))))

  (testing "nil title produces empty slug (documents behavior)"
    (let [result (invoke (stub-shell [(issue 1 nil "https://github.com/org/repo/issues/1")])
                         {:labels ["enhancement"]})]
      (is (= "" (get-in result [:data :worktree-description])))))

  (testing "empty string title produces empty slug (documents behavior)"
    (let [result (invoke (stub-shell [(issue 1 "" "https://github.com/org/repo/issues/1")])
                         {:labels ["enhancement"]})]
      (is (= "" (get-in result [:data :worktree-description]))))))
