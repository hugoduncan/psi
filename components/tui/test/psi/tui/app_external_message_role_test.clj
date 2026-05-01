(ns psi.tui.app-external-message-role-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.tui.app :as app]))

(defn- init-state []
  (let [init-fn (app/make-init nil nil nil {:dispatch-fn (constantly nil)})
        [state _cmd] (init-fn)]
    state))

(deftest external-user-message-appended-as-user-test
  (testing "external-message preserves user role instead of coercing to assistant"
    (let [update-fn (app/make-update (fn [_text _queue] nil))
          state     (init-state)
          event     {:type :external-message
                     :message {:role "user"
                               :content [{:type :text :text "Workflow run r1 result:"}]}}
          [s1 cmd]  (update-fn state event)]
      (is (= 1 (count (:messages s1))))
      (is (= :user (:role (first (:messages s1)))))
      (is (= "Workflow run r1 result:" (:text (first (:messages s1)))))
      (is (some? cmd)))))
