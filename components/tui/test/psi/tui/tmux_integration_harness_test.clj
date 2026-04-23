(ns psi.tui.tmux-integration-harness-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.tui.test-harness.tmux :as tmux]))

(deftest ^:integration tui-tmux-harness-basic-scenario-test
  (testing "tmux harness launches TUI, runs /help, and exits with /quit"
    (let [result (tmux/run-basic-help-quit-scenario! {})]
      (case (:status result)
        :skipped
        (do
          (println "WARNING:" (:warning result))
          (is (= :skipped (:status result)) (:warning result)))

        :passed
        (is (= :passed (:status result)))

        (do
          (is (= :passed (:status result))
              (str "expected harness scenario to pass, got "
                   (pr-str (dissoc result :pane-snapshot))))
          (when-not (= :passed (:status result))
            (is false
                (str "tmux harness failure snapshot:\n"
                     (or (:pane-snapshot result) "<none>")))))))))
