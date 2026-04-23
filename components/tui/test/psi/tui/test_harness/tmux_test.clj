(ns psi.tui.test-harness.tmux-test
  (:require
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
