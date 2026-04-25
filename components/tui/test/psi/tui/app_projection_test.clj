(ns psi.tui.app-projection-test
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

(defn- init-state []
  (let [init-fn (app/make-init "test-model" nil nil nil {:dispatch-fn (constantly nil)})
        [state _] (init-fn)]
    state))

(deftest view-renders-open-slash-autocomplete-menu-test
  (testing "open slash autocomplete is visible in the prompt view"
    (let [state (-> (init-state)
                    (assoc :width 120)
                    (assoc-in [:prompt-input-state :autocomplete]
                              {:prefix "/"
                               :candidates [{:value "/help" :label "/help" :description nil :kind :slash_command :is-directory false}
                                            {:value "/jobs" :label "/jobs" :description nil :kind :slash_command :is-directory false}
                                            {:value "/new" :label "/new" :description nil :kind :slash_command :is-directory false}]
                               :selected-index 1
                               :context :slash_command
                               :trigger-mode :auto}))
          plain (ansi/strip-ansi (app/view state))]
      (is (str/includes? plain "Suggestions"))
      (is (str/includes? plain "  /help"))
      (is (str/includes? plain "▸ /jobs"))
      (is (str/includes? plain "  /new")))))

(deftest view-limits-visible-autocomplete-suggestions-to-five-test
  (testing "rendered autocomplete suggestions are capped at five visible rows"
    (let [labels (mapv #(str "/cmd-" %) (range 1 8))
          candidates (mapv (fn [label]
                             {:value label :label label :description nil :kind :slash_command :is-directory false})
                           labels)
          state (-> (init-state)
                    (assoc :width 120)
                    (assoc-in [:prompt-input-state :autocomplete]
                              {:prefix "/"
                               :candidates candidates
                               :selected-index 0
                               :context :slash_command
                               :trigger-mode :auto}))
          plain (ansi/strip-ansi (app/view state))]
      (doseq [label (take 5 labels)]
        (is (str/includes? plain label)))
      (doseq [label (drop 5 labels)]
        (is (not (str/includes? plain label)))))))

(deftest view-does-not-render-autocomplete-when-closed-test
  (testing "closed autocomplete state renders no suggestion menu"
    (let [plain (ansi/strip-ansi (app/view (assoc (init-state) :width 120)))]
      (is (not (str/includes? plain "Suggestions")))
      (is (not (str/includes? plain "▸ /"))))))

(deftest autocomplete-selection-movement-updates-rendered-highlight-test
  (testing "moving selection changes the visibly highlighted autocomplete row"
    (let [update-fn (app/make-update (fn [_ _] nil))
          base-state (-> (init-state)
                         (assoc :width 120))
          [s1 _] (update-fn base-state (msg/key-press "/"))
          plain1 (ansi/strip-ansi (app/view s1))
          [s2 _] (update-fn s1 (msg/key-press :down))
          plain2 (ansi/strip-ansi (app/view s2))]
      (is (str/includes? plain1 "▸ /cancel-job"))
      (is (str/includes? plain2 "▸ /exit"))
      (is (not= plain1 plain2)))))
