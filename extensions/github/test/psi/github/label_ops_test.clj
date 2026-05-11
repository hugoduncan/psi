(ns psi.github.label-ops-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.github.label-ops :as sut]
   [psi.github.test-support :as ts]))

;;; ---------------------------------------------------------------------------
;;; Test helpers

(defn- invoke-add
  [shell-fn args]
  (sut/add-label {:ctx {:github-shell-fn shell-fn} :args args}))

(defn- invoke-remove
  [shell-fn args]
  (sut/remove-label {:ctx {:github-shell-fn shell-fn} :args args}))

;;; ---------------------------------------------------------------------------
;;; add-label: issue target

(deftest add-label-to-issue-invokes-correct-gh-command-test
  (testing "add-label to an issue calls `gh issue edit <N> --add-label <csv>`"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke-add shell-fn {:number 42 :labels ["waiting"] :target "issue"})
      (is (= 1 (count @calls*)))
      (is (= ["gh" "issue" "edit" "42" "--add-label" "waiting"] (first @calls*))))))

(deftest add-label-to-issue-returns-ok-test
  (testing "add-label to issue → :ok with correct :data"
    (let [result (invoke-add (ts/stub-shell-ok) {:number 42 :labels ["waiting"] :target "issue"})]
      (is (= :ok (:status result)))
      (is (= {:number 42 :target "issue" :added-labels ["waiting"]} (:data result)))
      (is (string? (:summary result)))
      (is (str/includes? (:summary result) "issue #42")))))

(deftest add-multiple-labels-to-issue-test
  (testing "multiple labels are joined as CSV"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke-add shell-fn {:number 7 :labels ["fix" "ready"] :target "issue"})
      (is (= ["gh" "issue" "edit" "7" "--add-label" "fix,ready"] (first @calls*))))))

;;; ---------------------------------------------------------------------------
;;; add-label: pr target

(deftest add-label-to-pr-invokes-correct-gh-command-test
  (testing "add-label to a PR calls `gh pr edit <N> --add-label <csv>`"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke-add shell-fn {:number 5 :labels ["review"] :target "pr"})
      (is (= ["gh" "pr" "edit" "5" "--add-label" "review"] (first @calls*))))))

(deftest add-label-to-pr-returns-ok-test
  (testing "add-label to PR → :ok with target pr in :data and summary"
    (let [result (invoke-add (ts/stub-shell-ok) {:number 5 :labels ["review"] :target "pr"})]
      (is (= :ok (:status result)))
      (is (= {:number 5 :target "pr" :added-labels ["review"]} (:data result)))
      (is (string? (:summary result)))
      (is (str/includes? (:summary result) "pr #5")))))

;;; ---------------------------------------------------------------------------
;;; add-label: default target is "issue"

(deftest add-label-defaults-to-issue-target-test
  (testing "add-label with no :target defaults to issue"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke-add shell-fn {:number 10 :labels ["waiting"]})
      (is (= ["gh" "issue" "edit" "10" "--add-label" "waiting"] (first @calls*))))))

;;; ---------------------------------------------------------------------------
;;; add-label: shell error

(deftest add-label-shell-error-test
  (testing "non-zero gh exit → :psi.github/shell-error"
    (let [result (invoke-add (ts/stub-shell-error "gh: not found")
                             {:number 42 :labels ["waiting"] :target "issue"})]
      (is (= :error (:status result)))
      (is (= :psi.github/shell-error (:reason result)))
      (is (= "gh: not found" (:message result))))))

;;; ---------------------------------------------------------------------------
;;; remove-label: issue target

(deftest remove-label-from-issue-invokes-correct-gh-command-test
  (testing "remove-label from an issue calls `gh issue edit <N> --remove-label <csv>`"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke-remove shell-fn {:number 42 :labels ["triage"] :target "issue"})
      (is (= 1 (count @calls*)))
      (is (= ["gh" "issue" "edit" "42" "--remove-label" "triage"] (first @calls*))))))

(deftest remove-label-from-issue-returns-ok-test
  (testing "remove-label from issue → :ok with correct :data"
    (let [result (invoke-remove (ts/stub-shell-ok) {:number 42 :labels ["triage"] :target "issue"})]
      (is (= :ok (:status result)))
      (is (= {:number 42 :target "issue" :removed-labels ["triage"]} (:data result)))
      (is (string? (:summary result)))
      (is (str/includes? (:summary result) "issue #42")))))

(deftest remove-multiple-labels-test
  (testing "multiple labels are joined as CSV for remove"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke-remove shell-fn {:number 42 :labels ["fix" "triage"] :target "issue"})
      (is (= ["gh" "issue" "edit" "42" "--remove-label" "fix,triage"] (first @calls*))))))

;;; ---------------------------------------------------------------------------
;;; remove-label: pr target

(deftest remove-label-from-pr-invokes-correct-gh-command-test
  (testing "remove-label from a PR calls `gh pr edit <N> --remove-label <csv>`"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke-remove shell-fn {:number 5 :labels ["implement"] :target "pr"})
      (is (= ["gh" "pr" "edit" "5" "--remove-label" "implement"] (first @calls*))))))

(deftest remove-label-from-pr-returns-ok-test
  (testing "remove-label from PR → :ok with target pr in :data and summary"
    (let [result (invoke-remove (ts/stub-shell-ok) {:number 5 :labels ["implement"] :target "pr"})]
      (is (= :ok (:status result)))
      (is (= {:number 5 :target "pr" :removed-labels ["implement"]} (:data result)))
      (is (string? (:summary result)))
      (is (str/includes? (:summary result) "pr #5")))))

;;; ---------------------------------------------------------------------------
;;; remove-label: default target is "issue"

(deftest remove-label-defaults-to-issue-target-test
  (testing "remove-label with no :target defaults to issue"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke-remove shell-fn {:number 10 :labels ["triage"]})
      (is (= ["gh" "issue" "edit" "10" "--remove-label" "triage"] (first @calls*))))))

;;; ---------------------------------------------------------------------------
;;; remove-label: shell error

(deftest remove-label-shell-error-test
  (testing "non-zero gh exit → :psi.github/shell-error"
    (let [result (invoke-remove (ts/stub-shell-error "gh: not authenticated")
                                {:number 42 :labels ["triage"] :target "issue"})]
      (is (= :error (:status result)))
      (is (= :psi.github/shell-error (:reason result)))
      (is (= "gh: not authenticated" (:message result))))))
