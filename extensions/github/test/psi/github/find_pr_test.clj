(ns psi.github.find-pr-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.github.find-pr :as sut]
   [psi.github.test-support :as ts]))

;;; ---------------------------------------------------------------------------
;;; Test helpers

(defn- pr-item
  [number title url head-ref base-ref]
  {"number"      number
   "title"       title
   "url"         url
   "state"       "open"
   "labels"      []
   "headRefName" head-ref
   "baseRefName" base-ref})

(defn- invoke
  "Call sut/invoke with a stub ctx and args map."
  [shell-fn args]
  (sut/invoke {:ctx {:github-shell-fn shell-fn}
               :args args}))

;;; ---------------------------------------------------------------------------
;;; gh CLI arg construction

(deftest gh-cli-args-are-constructed-correctly-test
  (testing "gh pr list receives exact --state, --json, and --label args"
    (let [[shell-fn calls*] (ts/capturing-shell [(pr-item 1 "x" "https://github.com/org/repo/pull/1"
                                                          "feature/x" "master")])]
      (invoke shell-fn {:labels ["implement"]})
      (is (= 1 (count @calls*)))
      (is (= ["gh" "pr" "list"
              "--state" "open"
              "--json" "number,title,url,state,labels,headRefName,baseRefName"
              "--label" "implement"]
             (first @calls*))))))

;;; ---------------------------------------------------------------------------
;;; No candidates → error

(deftest no-candidates-returns-error-test
  (testing "no matching PRs → :psi.github/no-matching-pr error"
    (let [result (invoke (ts/stub-shell [])
                         {:labels ["implement"]})]
      (is (= :error (:status result)))
      (is (= :psi.github/no-matching-pr (:reason result)))
      (is (string? (:message result))))))

;;; ---------------------------------------------------------------------------
;;; Single candidate → correct structured map + slug + handoff

(deftest single-candidate-returns-correct-map-test
  (testing "single candidate → :ok with correct data map and Markdown summary"
    (let [result (invoke (ts/stub-shell [(pr-item 42 "Add dark mode"
                                                  "https://github.com/org/repo/pull/42"
                                                  "feature/add-dark-mode" "master")])
                         {:labels ["implement"]})]
      (is (= :ok (:status result)))
      (is (= {:pr-number           42
              :pr-title            "Add dark mode"
              :pr-url              "https://github.com/org/repo/pull/42"
              :pr-branch           "feature/add-dark-mode"
              :base-branch         "master"
              :worktree-description "feature-add-dark-mode"}
             (:data result)))
      (is (string? (:summary result)))
      (is (str/includes? (:summary result) "## Handoff Data"))
      (is (str/includes? (:summary result) "pr_number: 42"))
      (is (str/includes? (:summary result) "pr_title: Add dark mode"))
      (is (str/includes? (:summary result) "pr_url: https://github.com/org/repo/pull/42"))
      (is (str/includes? (:summary result) "pr_branch: feature/add-dark-mode"))
      (is (str/includes? (:summary result) "base_branch: master"))
      (is (str/includes? (:summary result) "worktree_description: feature-add-dark-mode")))))

;;; ---------------------------------------------------------------------------
;;; Multiple candidates + no narrowing → lowest number selected

(deftest multiple-candidates-no-narrowing-selects-lowest-test
  (testing "multiple candidates → lowest PR number selected"
    (let [result (invoke (ts/stub-shell [(pr-item 99 "PR 99" "https://github.com/org/repo/pull/99"
                                                  "feature/pr-99" "master")
                                         (pr-item 7  "PR 7"  "https://github.com/org/repo/pull/7"
                                                  "feature/pr-7" "master")
                                         (pr-item 42 "PR 42" "https://github.com/org/repo/pull/42"
                                                  "feature/pr-42" "master")])
                         {:labels ["implement"]})]
      (is (= :ok (:status result)))
      (is (= 7 (get-in result [:data :pr-number]))))))

;;; ---------------------------------------------------------------------------
;;; Narrowing by integer

(deftest narrowing-by-integer-test
  (testing "integer input → exact number match"
    (let [result (invoke (ts/stub-shell [(pr-item 7  "PR 7"  "https://github.com/org/repo/pull/7"
                                                  "feature/pr-7" "master")
                                         (pr-item 42 "PR 42" "https://github.com/org/repo/pull/42"
                                                  "feature/pr-42" "master")])
                         {:labels ["implement"]
                          :input "42"})]
      (is (= :ok (:status result)))
      (is (= 42 (get-in result [:data :pr-number]))))))

;;; ---------------------------------------------------------------------------
;;; Narrowing by PR URL (must use /pull/NNN not /issues/NNN)

(deftest narrowing-by-pull-url-test
  (testing "PR URL input → PR number extracted via /pull/NNN regex and matched"
    (let [result (invoke (ts/stub-shell [(pr-item 7  "PR 7"  "https://github.com/org/repo/pull/7"
                                                  "feature/pr-7" "master")
                                         (pr-item 42 "PR 42" "https://github.com/org/repo/pull/42"
                                                  "feature/pr-42" "master")])
                         {:labels ["implement"]
                          :input "https://github.com/org/repo/pull/42"})]
      (is (= :ok (:status result)))
      (is (= 42 (get-in result [:data :pr-number]))))))

(deftest invalid-url-returns-error-test
  (testing "issue URL (with /issues/NNN) is rejected — regex does not match /issues/"
    (let [result (invoke (ts/stub-shell [(pr-item 42 "PR 42" "https://github.com/org/repo/pull/42"
                                                  "feature/pr-42" "master")])
                         {:labels ["implement"]
                          :input "https://github.com/org/repo/issues/42"})]
      (is (= :error (:status result)))
      (is (= :psi.github/invalid-url-input (:reason result)))
      (is (str/includes? (:message result) "Cannot extract PR number from URL")))))

;;; ---------------------------------------------------------------------------
;;; Narrowing by title substring (case-insensitive)

(deftest narrowing-by-title-substring-test
  (testing "text substring match selects matching PR"
    (let [result (invoke (ts/stub-shell [(pr-item 7  "Fix the login bug"  "https://github.com/org/repo/pull/7"
                                                  "fix/login-bug" "master")
                                         (pr-item 42 "Add dark mode"      "https://github.com/org/repo/pull/42"
                                                  "feature/dark-mode" "master")])
                         {:labels ["implement"]
                          :input "dark mode"})]
      (is (= :ok (:status result)))
      (is (= 42 (get-in result [:data :pr-number]))))))

;;; ---------------------------------------------------------------------------
;;; Text narrowing → zero candidates → error

(deftest narrowing-by-text-no-match-returns-error-test
  (testing "text narrowing that filters to zero candidates → :psi.github/no-matching-pr"
    (let [result (invoke (ts/stub-shell [(pr-item 42 "Add dark mode"
                                                  "https://github.com/org/repo/pull/42"
                                                  "feature/dark-mode" "master")])
                         {:labels ["implement"]
                          :input "login bug"})]
      (is (= :error (:status result)))
      (is (= :psi.github/no-matching-pr (:reason result))))))

;;; ---------------------------------------------------------------------------
;;; Non-zero gh CLI exit → :psi.github/shell-error

(deftest non-zero-exit-returns-shell-error-test
  (testing "non-zero gh exit → :psi.github/shell-error with :err message"
    (let [result (invoke (ts/error-shell "gh: not authenticated")
                         {:labels ["implement"]})]
      (is (= :error (:status result)))
      (is (= :psi.github/shell-error (:reason result)))
      (is (= "gh: not authenticated" (:message result))))))

;;; ---------------------------------------------------------------------------
;;; nil input → treated as no narrowing

(deftest nil-input-treated-as-no-narrowing-test
  (testing "nil :input → no narrowing applied, lowest candidate selected"
    (let [result (invoke (ts/stub-shell [(pr-item 99 "PR 99" "https://github.com/org/repo/pull/99"
                                                  "feature/pr-99" "master")
                                         (pr-item 5  "PR 5"  "https://github.com/org/repo/pull/5"
                                                  "feature/pr-5" "master")])
                         {:labels ["implement"]
                          :input nil})]
      (is (= :ok (:status result)))
      (is (= 5 (get-in result [:data :pr-number]))))))

;;; ---------------------------------------------------------------------------
;;; Slug derivation from headRefName

(deftest slug-derivation-from-branch-name-test
  (testing "slug is derived from headRefName: lower-case, extract words, join, truncate at 40"
    (let [result (invoke (ts/stub-shell [(pr-item 1 "Some PR" "https://github.com/org/repo/pull/1"
                                                  "feature/add-foo-bar" "master")])
                         {:labels ["implement"]})]
      (is (= "feature-add-foo-bar" (get-in result [:data :worktree-description])))))

  (testing "slug is hard-truncated at 40 chars and never ends with -"
    (let [long-branch "feature/this-is-a-very-long-branch-name-that-exceeds-forty-chars"
          result (invoke (ts/stub-shell [(pr-item 1 "PR" "https://github.com/org/repo/pull/1"
                                                  long-branch "master")])
                         {:labels ["implement"]})]
      (is (<= (count (get-in result [:data :worktree-description])) 40))
      (is (not (str/ends-with? (get-in result [:data :worktree-description]) "-"))))))
