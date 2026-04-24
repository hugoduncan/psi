(ns psi.tui.notification-render-test
  "Proves the notification rendering lifecycle: appear → visible → expired → dismissed."
  (:require
   [charm.message :as msg]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.app-runtime.projections :as projections]
   [psi.tui.app :as app]
   [psi.tui.ansi :as ansi]
   [psi.ui.state :as ui]))

(deftest notification-appears-then-disappears-after-expiry-test
  (testing "notification appears in rendered view, then disappears after simulated expiry"
    (let [ui-atom       (ui/create-ui-state)
          ui-read-fn    (fn [] (projections/extension-ui-snapshot-from-state @ui-atom))
          ui-dispatch-fn (fn [event-type _payload]
                           (case event-type
                             :session/ui-dismiss-expired  (ui/dismiss-expired! ui-atom 5000)
                             :session/ui-dismiss-overflow (ui/dismiss-overflow! ui-atom)
                             nil))
          init-fn       (app/make-init "test-model" nil ui-read-fn ui-dispatch-fn
                                       {:dispatch-fn (constantly nil)})
          [state _]     (init-fn)
          state         (assoc state :width 120)
          update-fn     (app/make-update (fn [_ _]))

          ;; Add a notification
          _             (ui/notify! ui-atom "test-ext" "Alert: disk full" :warning)

          ;; Tick to refresh ui-snapshot (fresh notification survives dismiss)
          [state' _]    (update-fn state (msg/window-size 120 40))
          plain-before  (ansi/strip-ansi (app/view state'))]

      (is (str/includes? plain-before "Alert: disk full")
          "notification should be visible in rendered view")

      ;; Backdate the notification's :created-at to simulate time passage
      (swap! ui-atom update :notifications
             (fn [notes]
               (mapv #(assoc % :created-at 0) notes)))

      ;; Tick — dismiss-expired now finds the notification > 5s old and
      ;; marks it dismissed in the atom.  The snapshot for *this* tick
      ;; was read before dismiss ran, so we need one more tick to pick
      ;; up the dismissed state.
      (let [[state'' _]   (update-fn state' (msg/window-size 120 40))
            ;; Third tick picks up the post-dismiss snapshot
            [state''' _]  (update-fn state'' (msg/window-size 120 40))
            plain-after   (ansi/strip-ansi (app/view state'''))]
        (is (not (str/includes? plain-after "Alert: disk full"))
            "notification should be gone after simulated expiry")))))
