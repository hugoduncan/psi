(ns extensions.context-manager-friction-parsing-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [extensions.context-manager :as context-manager]))

(deftest build-friction-prompt-test
  (testing "embeds history excerpt, open tasks, and recently-closed tasks"
    (let [{:keys [system-prompt user-prompt]}
          (context-manager/build-friction-prompt
           {:history-excerpt "User: do X\nAssistant: did X via bash workaround"
            :open-tasks [{:id "010-foo" :title "Foo issue"}]
            :recent-closed-tasks [{:id "005-bar" :title "Bar issue"}]})]
      (is (str/includes? system-prompt "tooling or dependencies"))
      (is (str/includes? system-prompt "ISSUE:"))
      (is (str/includes? system-prompt "DUPLICATE:"))
      (is (str/includes? system-prompt "NONE"))
      (is (str/includes? user-prompt "did X via bash workaround"))
      (is (str/includes? user-prompt "010-foo: Foo issue"))
      (is (str/includes? user-prompt "005-bar: Bar issue"))))

  (testing "renders (none) when task lists are empty"
    (let [{:keys [user-prompt]}
          (context-manager/build-friction-prompt
           {:history-excerpt nil :open-tasks [] :recent-closed-tasks []})]
      (is (str/includes? user-prompt "(none)")))))

(deftest parse-friction-output-nominal-test
  (testing "parses a single well-formed ISSUE block"
    (is (= {:issues [{:slug "flaky-bash-tool"
                      :title "bash tool retries silently"
                      :friction "bash calls retried 3x with no diagnostic"
                      :evidence "turns 2-3"
                      :suggestion "surface retry count in tool output"}]
            :duplicates []}
           (context-manager/parse-friction-output
            (str "ISSUE: flaky-bash-tool | bash tool retries silently\n"
                 "FRICTION: bash calls retried 3x with no diagnostic\n"
                 "EVIDENCE: turns 2-3\n"
                 "SUGGESTION: surface retry count in tool output\n"))))))

(deftest parse-friction-output-none-test
  (testing "NONE yields empty issues and duplicates"
    (is (= {:issues [] :duplicates []}
           (context-manager/parse-friction-output "NONE"))))
  (testing "blank/nil input yields empty issues and duplicates"
    (is (= {:issues [] :duplicates []} (context-manager/parse-friction-output "")))
    (is (= {:issues [] :duplicates []} (context-manager/parse-friction-output nil)))))

(deftest parse-friction-output-malformed-test
  (testing "an ISSUE block missing a required field is dropped"
    (is (= {:issues [] :duplicates []}
           (context-manager/parse-friction-output
            (str "ISSUE: incomplete | Incomplete issue\n"
                 "FRICTION: something happened\n")))))

  (testing "preamble/commentary text is ignored"
    (is (= {:issues [] :duplicates []}
           (context-manager/parse-friction-output
            "Here is my analysis of the conversation:\nNothing notable.")))))

(deftest parse-friction-output-slug-sanitization-test
  (testing "a slug containing path-traversal segments is dropped"
    (is (= {:issues [] :duplicates []}
           (context-manager/parse-friction-output
            (str "ISSUE: ../../../../tmp/pwned | Evil\n"
                 "FRICTION: something\n"
                 "EVIDENCE: turn 1\n"
                 "SUGGESTION: do nothing\n")))))

  (testing "a slug containing a path separator is dropped"
    (is (= {:issues [] :duplicates []}
           (context-manager/parse-friction-output
            (str "ISSUE: foo/bar | Slash slug\n"
                 "FRICTION: something\n"
                 "EVIDENCE: turn 1\n"
                 "SUGGESTION: do nothing\n")))))

  (testing "a slug with uppercase or underscores is dropped (not kebab-case)"
    (is (= {:issues [] :duplicates []}
           (context-manager/parse-friction-output
            (str "ISSUE: Not_Kebab | Bad casing\n"
                 "FRICTION: something\n"
                 "EVIDENCE: turn 1\n"
                 "SUGGESTION: do nothing\n")))))

  (testing "a plain kebab-case slug still parses"
    (is (= {:issues [{:slug "valid-slug-123"
                      :title "Fine"
                      :friction "something"
                      :evidence "turn 1"
                      :suggestion "do nothing"}]
            :duplicates []}
           (context-manager/parse-friction-output
            (str "ISSUE: valid-slug-123 | Fine\n"
                 "FRICTION: something\n"
                 "EVIDENCE: turn 1\n"
                 "SUGGESTION: do nothing\n"))))))

(deftest parse-friction-output-mixed-test
  (testing "an issue and a duplicate line both parse from the same output"
    (is (= {:issues [{:slug "missing-dep"
                      :title "Missing linter dependency"
                      :friction "linter not installed, had to skip"
                      :evidence "turn 4"
                      :suggestion "add linter to deps.edn"}]
            :duplicates [{:slug "slow-test-loop" :existing-id "012-slow-tests"}]}
           (context-manager/parse-friction-output
            (str "ISSUE: missing-dep | Missing linter dependency\n"
                 "FRICTION: linter not installed, had to skip\n"
                 "EVIDENCE: turn 4\n"
                 "SUGGESTION: add linter to deps.edn\n"
                 "DUPLICATE: slow-test-loop ~ 012-slow-tests\n"))))))

(deftest parse-friction-output-multi-block-test
  (testing "two well-formed ISSUE blocks parse into an ordered two-element :issues"
    ;; Exercises `parse-friction-output`'s own block boundaries — the
    ;; header + `(take-while (not ISSUE|DUPLICATE))` split then
    ;; `(drop (count block) …)` to resume at the next header — so multiple
    ;; issues must accumulate in order. Only multi-issue coverage that
    ;; existed was at the orchestration layer (`cap-applied-test`).
    (is (= {:issues [{:slug "first-issue"
                      :title "First friction"
                      :friction "first thing broke"
                      :evidence "turn 1"
                      :suggestion "fix first thing"}
                     {:slug "second-issue"
                      :title "Second friction"
                      :friction "second thing broke"
                      :evidence "turn 2"
                      :suggestion "fix second thing"}]
            :duplicates []}
           (context-manager/parse-friction-output
            (str "ISSUE: first-issue | First friction\n"
                 "FRICTION: first thing broke\n"
                 "EVIDENCE: turn 1\n"
                 "SUGGESTION: fix first thing\n"
                 "\n"
                 "ISSUE: second-issue | Second friction\n"
                 "FRICTION: second thing broke\n"
                 "EVIDENCE: turn 2\n"
                 "SUGGESTION: fix second thing\n"))))))

(deftest parse-friction-output-malformed-then-valid-recovery-test
  (testing "a malformed ISSUE block followed by a valid one yields only the valid issue"
    ;; A bad first block (missing its SUGGESTION line) must be dropped
    ;; without swallowing the following complete block — the parser drops
    ;; the malformed header + its take-while span, then resumes at the next
    ;; ISSUE header and recovers the valid block. A regression that let a
    ;; malformed leading block consume the following valid one (or that
    ;; stopped after the first parsed block) would silently drop real
    ;; detected issues yet pass every single-block parse test.
    (is (= {:issues [{:slug "good-one"
                      :title "Good friction"
                      :friction "real friction here"
                      :evidence "turn 3"
                      :suggestion "fix the real thing"}]
            :duplicates []}
           (context-manager/parse-friction-output
            (str "ISSUE: bad-one | Malformed friction\n"
                 "FRICTION: incomplete block missing suggestion\n"
                 "EVIDENCE: turn 2\n"
                 "\n"
                 "ISSUE: good-one | Good friction\n"
                 "FRICTION: real friction here\n"
                 "EVIDENCE: turn 3\n"
                 "SUGGESTION: fix the real thing\n"))))))

(deftest render-friction-design-md-test
  (testing "includes auto-generated marker, friction, evidence, and suggestion"
    (let [content (context-manager/render-friction-design-md
                   {:slug "flaky-bash-tool"
                    :title "bash tool retries silently"
                    :friction "bash calls retried 3x with no diagnostic"
                    :evidence "turns 2-3"
                    :suggestion "surface retry count in tool output"})]
      (is (str/includes? content "Auto-generated"))
      ;; AC2: the marker must actually identify *this* analyzer, not merely
      ;; contain the word "Auto-generated" (task 239 task-test-review round-2
      ;; follow-up) — a regression dropping "context-manager"/"task 239"/
      ;; "analyzer" would otherwise pass.
      (is (str/includes? content "context-manager")
          "marker names the context-manager analyzer")
      (is (str/includes? content "task 239")
          "marker names the owning task")
      (is (str/includes? content "analyzer")
          "marker names it as the tooling-friction analyzer")
      ;; :title is rendered as the H1 heading.
      (is (str/includes? content "# bash tool retries silently")
          "title becomes the H1 heading")
      (is (str/includes? content "bash calls retried 3x with no diagnostic"))
      (is (str/includes? content "turns 2-3"))
      (is (str/includes? content "surface retry count in tool output")))))

(deftest cap-issues-test
  (testing "0 issues"
    (is (= {:selected [] :dropped []} (context-manager/cap-issues [] 2))))
  (testing "1 issue, cap 2 — none dropped"
    (is (= {:selected [{:slug "a"}] :dropped []}
           (context-manager/cap-issues [{:slug "a"}] 2))))
  (testing "2 issues, cap 2 — none dropped"
    (is (= {:selected [{:slug "a"} {:slug "b"}] :dropped []}
           (context-manager/cap-issues [{:slug "a"} {:slug "b"}] 2))))
  (testing "3 issues, cap 2 — third dropped, order preserved"
    (is (= {:selected [{:slug "a"} {:slug "b"}] :dropped [{:slug "c"}]}
           (context-manager/cap-issues [{:slug "a"} {:slug "b"} {:slug "c"}] 2)))))
