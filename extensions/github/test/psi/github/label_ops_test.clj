(ns psi.github.label-ops-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.github.label-ops :as sut]
   [psi.github.test-support :as ts]))

;;; ---------------------------------------------------------------------------
;;; Test helper

(defn- invoke
  [shell-fn args]
  (sut/edit-labels {:ctx {:github-shell-fn shell-fn} :args args}))

;;; ---------------------------------------------------------------------------
;;; Add only

(deftest edit-labels-add-only-invokes-correct-gh-command-test
  (testing "add only calls `gh issue edit <N> --add-label <csv>`"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke shell-fn {:number 42 :add ["waiting"] :target "issue"})
      (is (= 1 (count @calls*)))
      (is (= ["gh" "issue" "edit" "42" "--add-label" "waiting"] (first @calls*))))))

(deftest edit-labels-add-only-returns-ok-test
  (testing "add only → :ok with correct :data"
    (let [result (invoke (ts/stub-shell-ok) {:number 42 :add ["waiting"] :target "issue"})]
      (is (= :ok (:status result)))
      (is (= {:number 42 :target "issue" :added-labels ["waiting"] :removed-labels []}
             (:data result)))
      (is (str/includes? (:summary result) "issue #42"))
      (is (str/includes? (:summary result) "+[waiting]")))))

(deftest edit-labels-add-multiple-labels-test
  (testing "multiple add labels are joined as CSV"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke shell-fn {:number 7 :add ["fix" "ready"] :target "issue"})
      (is (= ["gh" "issue" "edit" "7" "--add-label" "fix,ready"] (first @calls*))))))

;;; ---------------------------------------------------------------------------
;;; Remove only

(deftest edit-labels-remove-only-invokes-correct-gh-command-test
  (testing "remove only calls `gh issue edit <N> --remove-label <csv>`"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke shell-fn {:number 42 :remove ["triage"] :target "issue"})
      (is (= 1 (count @calls*)))
      (is (= ["gh" "issue" "edit" "42" "--remove-label" "triage"] (first @calls*))))))

(deftest edit-labels-remove-only-returns-ok-test
  (testing "remove only → :ok with correct :data"
    (let [result (invoke (ts/stub-shell-ok) {:number 42 :remove ["triage"] :target "issue"})]
      (is (= :ok (:status result)))
      (is (= {:number 42 :target "issue" :added-labels [] :removed-labels ["triage"]}
             (:data result)))
      (is (str/includes? (:summary result) "issue #42"))
      (is (str/includes? (:summary result) "-[triage]")))))

(deftest edit-labels-remove-multiple-labels-test
  (testing "multiple remove labels are joined as CSV"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke shell-fn {:number 42 :remove ["fix" "triage"] :target "issue"})
      (is (= ["gh" "issue" "edit" "42" "--remove-label" "fix,triage"] (first @calls*))))))

;;; ---------------------------------------------------------------------------
;;; Add and remove together

(deftest edit-labels-add-and-remove-issues-single-gh-call-test
  (testing "add and remove together issues a single gh call with both flags"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke shell-fn {:number 10 :add ["waiting"] :remove ["triage"] :target "issue"})
      (is (= 1 (count @calls*)))
      (is (= ["gh" "issue" "edit" "10" "--add-label" "waiting" "--remove-label" "triage"]
             (first @calls*))))))

(deftest edit-labels-add-and-remove-returns-ok-test
  (testing "add and remove → :ok with both sets in :data"
    (let [result (invoke (ts/stub-shell-ok)
                         {:number 10 :add ["waiting"] :remove ["triage"] :target "issue"})]
      (is (= :ok (:status result)))
      (is (= {:number 10 :target "issue" :added-labels ["waiting"] :removed-labels ["triage"]}
             (:data result)))
      (is (str/includes? (:summary result) "+[waiting]"))
      (is (str/includes? (:summary result) "-[triage]")))))

;;; ---------------------------------------------------------------------------
;;; PR target

(deftest edit-labels-pr-target-test
  (testing "pr target calls `gh pr edit <N> ...`"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke shell-fn {:number 5 :add ["review"] :remove ["implement"] :target "pr"})
      (is (= ["gh" "pr" "edit" "5" "--add-label" "review" "--remove-label" "implement"]
             (first @calls*))))))

(deftest edit-labels-pr-target-returns-ok-test
  (testing "pr target → :ok with target pr in :data and summary"
    (let [result (invoke (ts/stub-shell-ok) {:number 5 :add ["review"] :target "pr"})]
      (is (= :ok (:status result)))
      (is (= "pr" (:target (:data result))))
      (is (str/includes? (:summary result) "pr #5")))))

;;; ---------------------------------------------------------------------------
;;; Default target

(deftest edit-labels-default-target-is-issue-test
  (testing "omitting :target defaults to issue"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke shell-fn {:number 10 :add ["waiting"]})
      (is (= ["gh" "issue" "edit" "10" "--add-label" "waiting"] (first @calls*))))))

;;; ---------------------------------------------------------------------------
;;; No-op (neither :add nor :remove)

(deftest edit-labels-no-op-skips-shell-call-test
  (testing "no :add and no :remove → :ok without issuing a shell command"
    (let [[shell-fn calls*] (ts/capturing-shell-ok)]
      (invoke shell-fn {:number 42 :target "issue"})
      (is (= :ok (:status (invoke shell-fn {:number 42 :target "issue"}))))
      (is (empty? @calls*)))))

;;; ---------------------------------------------------------------------------
;;; Shell error

(deftest edit-labels-shell-error-test
  (testing "non-zero gh exit → :psi.github/shell-error"
    (let [result (invoke (ts/stub-shell-error "gh: not found")
                         {:number 42 :add ["waiting"] :target "issue"})]
      (is (= :error (:status result)))
      (is (= :psi.github/shell-error (:reason result)))
      (is (= "gh: not found" (:message result))))))
