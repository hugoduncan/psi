(ns psi.tui.test-harness.tmux-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.tui.test-harness.tmux :as tmux]))

(deftest tmux-preflight-result-test
  (testing "returns ok when tmux is available"
    (with-redefs [tmux/tmux-available? (constantly true)
                  tmux/ci-env?         (constantly false)]
      (is (= {:status :ok}
             (tmux/tmux-preflight-result)))))

  (testing "returns skipped with warning for local missing tmux"
    (with-redefs [tmux/tmux-available? (constantly false)
                  tmux/ci-env?         (constantly false)]
      (let [result (tmux/tmux-preflight-result)]
        (is (= :skipped (:status result)))
        (is (= :tmux-not-available (:reason result)))
        (is (string? (:warning result))))))

  (testing "returns failed in CI when tmux is missing"
    (with-redefs [tmux/tmux-available? (constantly false)
                  tmux/ci-env?         (constantly true)]
      (let [result (tmux/tmux-preflight-result)]
        (is (= :failed (:status result)))
        (is (= :tmux-required-in-ci (:reason result)))
        (is (string? (:error-message result)))))))

(deftest primary-pane-id-and-targeting-test
  (testing "primary-pane-id trims tmux output"
    (with-redefs [tmux/run-sh (fn [_] {:exit 0 :out "%42\n"})]
      (is (= "%42"
             (tmux/primary-pane-id "session-name")))))

  (testing "pane-target prefers explicit pane-id over session fallback"
    (with-redefs [tmux/primary-pane-id (constantly "%99")]
      (is (= "%42"
             (#'tmux/pane-target {:session-name "s" :pane-id "%42"})))
      (is (= "%99"
             (#'tmux/pane-target {:session-name "s"})))
      (is (= "missing:0.0"
             (with-redefs [tmux/primary-pane-id (constantly nil)]
               (#'tmux/pane-target {:session-name "missing"})))))))

(defn- make-pane-snapshot
  "Build a minimal sanitized pane snapshot string for check-layout-invariants tests.
   Lines are joined with newlines; no trailing newline is added."
  [& lines]
  (str/join "\n" lines))

(deftest check-layout-invariants-ok-test
  (testing "passes for a well-formed TUI snapshot at the expected width"
    (let [snap (make-pane-snapshot
                "ψ Psi Agent Session"
                "  Model: test-model"
                "  ESC=interrupt  Ctrl+C=clear/quit  Ctrl+D=exit-empty"
                ""
                "────────────────────────────────────────────────────────────────────────────────"
                "刀: Type a message…"
                "────────────────────────────────────────────────────────────────────────────────")]
      (is (= {:ok? true} (tmux/check-layout-invariants snap)))
      (is (= {:ok? true} (tmux/check-layout-invariants snap 80))))))

(deftest check-layout-invariants-banner-at-column-0-test
  (testing "fails when banner line is indented"
    (let [snap   (make-pane-snapshot
                  "   ψ Psi Agent Session"
                  "  Model: test-model"
                  "────────────────────────────────────────────────────────────────────────────────"
                  "刀: Type a message…"
                  "────────────────────────────────────────────────────────────────────────────────")
          result (tmux/check-layout-invariants snap 80)]
      (is (false? (:ok? result)))
      (is (some #(= :banner-at-column-0 (:check %)) (:violations result)))))

  (testing "fails when banner line is absent"
    (let [snap   (make-pane-snapshot
                  "  Model: test-model"
                  "────────────────────────────────────────────────────────────────────────────────"
                  "刀: Type a message…"
                  "────────────────────────────────────────────────────────────────────────────────")
          result (tmux/check-layout-invariants snap)]
      (is (false? (:ok? result)))
      (is (some #(= :banner-at-column-0 (:check %)) (:violations result))))))

(deftest check-layout-invariants-banner-appears-once-test
  (testing "fails when banner appears more than once (double-render artefact)"
    (let [snap   (make-pane-snapshot
                  "ψ Psi Agent Session"
                  "  Model: test-model"
                  "ψ Psi Agent Session"
                  "  Model: test-model"
                  "────────────────────────────────────────────────────────────────────────────────"
                  "刀: Type a message…"
                  "────────────────────────────────────────────────────────────────────────────────")
          result (tmux/check-layout-invariants snap)]
      (is (false? (:ok? result)))
      (is (some #(= :banner-appears-once (:check %)) (:violations result))))))

(deftest check-layout-invariants-separator-at-column-0-test
  (testing "fails when separator line is indented"
    (let [snap   (make-pane-snapshot
                  "ψ Psi Agent Session"
                  "  Model: test-model"
                  "   ────────────────────────────────────────────────────────────────────────────"
                  "刀: Type a message…"
                  "   ────────────────────────────────────────────────────────────────────────────")
          result (tmux/check-layout-invariants snap)]
      (is (false? (:ok? result)))
      (is (some #(= :separator-at-column-0 (:check %)) (:violations result)))))

  (testing "fails when no separator line is present"
    (let [snap   (make-pane-snapshot
                  "ψ Psi Agent Session"
                  "  Model: test-model"
                  "刀: Type a message…")
          result (tmux/check-layout-invariants snap)]
      (is (false? (:ok? result)))
      (is (some #(= :separator-at-column-0 (:check %)) (:violations result))))))

(deftest check-layout-invariants-separator-spans-width-test
  (testing "fails when separator is shorter than expected width by more than 2"
    ;; separator is 62 dashes, expected width 80 → delta 18 > 2
    (let [snap   (make-pane-snapshot
                  "ψ Psi Agent Session"
                  "  Model: test-model"
                  "──────────────────────────────────────────────────────────────"
                  "刀: Type a message…"
                  "──────────────────────────────────────────────────────────────")
          result (tmux/check-layout-invariants snap 80)]
      (is (false? (:ok? result)))
      (is (some #(= :separator-spans-width (:check %)) (:violations result)))))

  (testing "passes when separator length is within 2 of expected width"
    (let [sep  (apply str (repeat 79 "─"))
          snap (make-pane-snapshot
                "ψ Psi Agent Session"
                "  Model: test-model"
                "  ESC=interrupt"
                sep
                "刀: Type a message…"
                sep)]
      (is (= {:ok? true} (tmux/check-layout-invariants snap 80))))))

(deftest check-layout-invariants-min-content-lines-test
  (testing "fails when fewer than 4 non-blank lines are present"
    (let [snap   (make-pane-snapshot
                  "ψ Psi Agent Session"
                  ""
                  "────────────────────────────────────────────────────────────────────────────────")
          result (tmux/check-layout-invariants snap)]
      (is (false? (:ok? result)))
      (is (some #(= :min-content-lines (:check %)) (:violations result)))))

  (testing "passes with exactly 4 non-blank lines"
    (let [snap (make-pane-snapshot
                "ψ Psi Agent Session"
                "  ESC=interrupt"
                "────────────────────────────────────────────────────────────────────────────────"
                "刀: Type a message…"
                "────────────────────────────────────────────────────────────────────────────────")]
      (is (= {:ok? true} (tmux/check-layout-invariants snap))))))

(deftest check-layout-invariants-multiple-violations-test
  (testing "reports all violations when multiple checks fail simultaneously"
    ;; blank screen: no banner, no separator, only 1 non-blank line
    (let [snap   (make-pane-snapshot "" "" "some text" "" "")
          result (tmux/check-layout-invariants snap 80)]
      (is (false? (:ok? result)))
      (is (> (count (:violations result)) 1))
      (is (some #(= :banner-at-column-0 (:check %)) (:violations result)))
      (is (some #(= :separator-at-column-0 (:check %)) (:violations result)))
      (is (some #(= :min-content-lines (:check %)) (:violations result))))))

(deftest run-basic-help-quit-scenario-preflight-test
  (testing "short-circuits to local skip result when tmux is unavailable locally"
    (with-redefs [tmux/tmux-preflight-result (constantly {:status :skipped
                                                          :reason :tmux-not-available
                                                          :warning "warn"})]
      (is (= {:status :skipped
              :reason :tmux-not-available
              :warning "warn"}
             (tmux/run-basic-help-quit-scenario! {})))))

  (testing "short-circuits to CI failure result when tmux is unavailable in CI"
    (with-redefs [tmux/tmux-preflight-result (constantly {:status :failed
                                                          :reason :tmux-required-in-ci
                                                          :error-message "tmux missing"})]
      (is (= {:status :failed
              :reason :tmux-required-in-ci
              :error-message "tmux missing"}
             (tmux/run-basic-help-quit-scenario! {}))))))
