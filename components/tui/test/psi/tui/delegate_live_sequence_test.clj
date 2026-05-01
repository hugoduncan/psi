(ns psi.tui.delegate-live-sequence-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.tui.app :as app]
   [psi.tui.ansi :as ansi]))

(defn- init-state []
  (let [init-fn (app/make-init nil nil nil {:dispatch-fn (constantly nil)})
        [state _] (init-fn)]
    state))

(deftest delegate-live-sequence-renders-ack-user-marker-and-result-test
  (testing "TUI preserves the immediate delegated ack and later user+assistant bridge"
    (let [update-fn (app/make-update (fn [_text _queue] nil))
          state0    (init-state)
          [state1 _] (update-fn state0 {:type :external-message
                                        :message {:role "assistant"
                                                  :content [{:type :text
                                                             :text "Delegated to lambda-build — run run-1"}]}})
          [state2 _] (update-fn state1 {:type :external-message
                                        :message {:role "user"
                                                  :content [{:type :text
                                                             :text "Workflow run run-1 result:"}]}})
          [state3 _] (update-fn state2 {:type :external-message
                                        :message {:role "assistant"
                                                  :content [{:type :text
                                                             :text "result text"}]}})
          out (ansi/strip-ansi (app/view state3))]
      (is (str/includes? out "ψ: Delegated to lambda-build — run run-1"))
      (is (str/includes? out "刀: Workflow run run-1 result:"))
      (is (str/includes? out "ψ: result text")))))
