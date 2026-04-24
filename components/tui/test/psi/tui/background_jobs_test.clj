(ns psi.tui.background-jobs-test
  (:require
   [charm.message :as msg]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.tui.app :as app]
   [psi.tui.ansi :as ansi]))

(defn- init-state-with-ui-snapshot
  [ui-snapshot]
  (let [init-fn (app/make-init "test-model" nil (fn [] ui-snapshot) nil {:dispatch-fn (constantly nil)})
        [state _] (init-fn)]
    (assoc state :width 120)))

(deftest background-jobs-widget-renders-from-canonical-ui-projection-test
  (testing "TUI renders background jobs from the projected widget instead of transcript text"
    (let [state (init-state-with-ui-snapshot
                 {:active-dialog nil
                  :widgets [{:placement :below-editor
                             :extension-id "psi-background-jobs"
                             :widget-id "background-jobs"
                             :content [{:text "job-1  [running]  agent-chain"
                                        :action {:type :command :command "/job job-1"}}
                                       {:text "job-2  [pending-cancel]  agent-run"
                                        :action {:type :command :command "/job job-2"}}]}]
                  :visible-notifications []
                  :tools-expanded? false})
          plain (ansi/strip-ansi (app/view state))]
      (is (str/includes? plain "job-1  [running]  agent-chain"))
      (is (str/includes? plain "job-2  [pending-cancel]  agent-run"))
      (is (not (str/includes? plain "Background job ─"))))))

(deftest background-job-widget-refresh-cycle-test
  (testing "widget content changes are visible in the rendered view after a tick cycle"
    (let [snap-a   {:active-dialog nil
                    :widgets [{:placement :below-editor
                               :extension-id "psi-background-jobs"
                               :widget-id "background-jobs"
                               :content [{:text "job-alpha  [running]  agent-chain"}]}]
                    :visible-notifications []
                    :tools-expanded? false}
          snap-b   {:active-dialog nil
                    :widgets [{:placement :below-editor
                               :extension-id "psi-background-jobs"
                               :widget-id "background-jobs"
                               :content [{:text "job-beta  [pending-cancel]  agent-run"}]}]
                    :visible-notifications []
                    :tools-expanded? false}
          snap-atom (atom snap-a)
          init-fn   (app/make-init "test-model" nil (fn [] @snap-atom) nil
                                   {:dispatch-fn (constantly nil)})
          [state _] (init-fn)
          state     (assoc state :width 120)
          ;; Verify initial content
          plain-a   (ansi/strip-ansi (app/view state))
          _         (assert (str/includes? plain-a "job-alpha  [running]  agent-chain"))
          ;; Swap to snapshot B and trigger a tick via window-size message
          _         (reset! snap-atom snap-b)
          update-fn (app/make-update (fn [_ _]))
          [state' _] (update-fn state (msg/window-size 120 40))
          plain-b   (ansi/strip-ansi (app/view state'))]
      (is (str/includes? plain-b "job-beta  [pending-cancel]  agent-run"))
      (is (not (str/includes? plain-b "job-alpha"))))))
