(ns psi.tui.tmux-integration-harness-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.tui.test-harness.tmux :as tmux]
   [psi.tui.test-harness.tmux-delegate :as tmux-delegate]
   [psi.tui.test-harness.tmux-rehydration :as tmux-rehydration]
   [psi.tui.test-harness.tmux-startup-wrap :as tmux-startup-wrap]
   [psi.tui.test-harness.tmux-streaming :as tmux-streaming]))

(defn- assert-scenario-result
  "Shared assertion helper for tmux scenario results.
   Handles :skipped, :passed, and :failed cases uniformly."
  [result]
  (case (:status result)
    :skipped
    (do
      (println "WARNING:" (:warning result))
      (is (= :skipped (:status result)) (:warning result)))

    :passed
    (is (= :passed (:status result)))

    (do
      (is (= :passed (:status result))
          (str "expected tmux scenario to pass, got "
               (pr-str (dissoc result :pane-snapshot))))
      (when-not (= :passed (:status result))
        (is false
            (str "tmux scenario failure snapshot:\n"
                 (or (:pane-snapshot result) "<none>")))))))

(deftest ^:integration tui-tmux-harness-basic-scenario-test
  (testing "tmux harness launches TUI, runs /help, and exits with /quit using canonical or repo-local launcher resolution"
    (assert-scenario-result (tmux/run-basic-help-quit-scenario! {}))))

(deftest ^:integration tui-tmux-slash-autocomplete-scenario-test
  (testing "typing '/' opens visible slash-command autocomplete menu with a selected suggestion, Down moves selection, Escape dismisses"
    (assert-scenario-result (tmux/run-slash-autocomplete-scenario! {}))))

(deftest ^:integration tui-tmux-thinking-rehydration-scenario-test
  (testing "resuming a session with a thinking block shows '· ' prefix in the transcript"
    (assert-scenario-result (tmux-rehydration/run-thinking-rehydration-scenario! {}))))

(deftest ^:integration tui-tmux-streaming-display-scenario-test
  (testing "streaming display: thinking prefix visible, tool truncated by default, ctrl+o expands"
    (assert-scenario-result (tmux-streaming/run-streaming-display-scenario! {}))))

(deftest ^:integration tui-tmux-startup-wrap-scenario-test
  (testing "startup assistant output wraps on a narrow terminal"
    (assert-scenario-result (tmux-startup-wrap/run-startup-wrap-scenario! {}))))

(deftest ^:integration tui-tmux-resize-scenario-test
  (testing "TUI repaints correctly after terminal resize: banner remains visible after shrink and after restore"
    (assert-scenario-result (tmux/run-resize-scenario! {}))))

(deftest ^:integration tui-tmux-delegate-live-sequence-scenario-test
  (testing "real /delegate shows ack, user bridge marker, assistant result, and no visible bridge filler"
    (assert-scenario-result (tmux-delegate/run-delegate-live-sequence-scenario! {}))))
