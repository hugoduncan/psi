(ns psi.tui.app-projection-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.tui.app :as app]
   [psi.tui.ansi :as ansi]))

(defn- init-state-with-ui-snapshot
  [ui-snapshot]
  (let [init-fn (app/make-init "test-model" nil (fn [] ui-snapshot) nil {:dispatch-fn (constantly nil)})
        [state _] (init-fn)]
    state))

(deftest view-renders-widgets-in-backend-owned-order-test
  (testing "TUI preserves canonical widget order from the backend projection"
    (let [state (-> (init-state-with-ui-snapshot
                     {:active-dialog nil
                      :widgets [{:placement :above-editor :extension-id "ext-a" :widget-id "w-1" :content ["A1"]}
                                {:placement :above-editor :extension-id "ext-b" :widget-id "w-2" :content ["B2"]}
                                {:placement :below-editor :extension-id "ext-c" :widget-id "w-3" :content ["C3"]}]
                      :visible-notifications []
                      :tools-expanded? false})
                    (assoc :width 120))
          plain (ansi/strip-ansi (app/view state))]
      (is (< (.indexOf plain "A1") (.indexOf plain "B2")))
      (is (< (.indexOf plain "B2") (.indexOf plain "C3"))))))

(deftest view-renders-visible-notifications-from-canonical-projection-test
  (testing "TUI renders the backend-owned visible notification window unchanged"
    (let [state (-> (init-state-with-ui-snapshot
                     {:active-dialog nil
                      :widgets []
                      :visible-notifications [{:id "n2" :message "second" :level :info}
                                              {:id "n3" :message "third" :level :warning}
                                              {:id "n4" :message "fourth" :level :error}]
                      :tools-expanded? false})
                    (assoc :width 120))
          plain (ansi/strip-ansi (app/view state))]
      (is (< (.indexOf plain "second") (.indexOf plain "third") (.indexOf plain "fourth")))
      (is (not (str/includes? plain "first"))))))
