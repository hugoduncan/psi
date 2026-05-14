(ns psi.app-runtime.ui-actions-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.app-runtime.ui-actions :as ui-actions]))

(deftest context-session-action-preserves-selector-order-and-action-values-test
  (let [action (ui-actions/context-session-action
                {:selector/prompt "Select a live session"
                 :selector/items [{:item/id [:session "s1"]
                                   :item/kind :session
                                   :item/default-label "Main"
                                   :item/action {:action/kind :switch-session
                                                 :action/session-id "s1"}}
                                  {:item/id [:fork-point "e1"]
                                   :item/kind :fork-point
                                   :item/default-label "⎇ Branch from here"
                                   :item/action {:action/kind :fork-session
                                                 :action/session-id "s1"
                                                 :action/entry-id "e1"}}]})]
    (is (= :select (:ui/action-kind action)))
    (is (= :select-session (:ui/action-id action)))
    (is (= :select-session (:ui/action-name action)))
    (is (= :preserve (:ui/order action)))
    (is (= ["Main" "⎇ Branch from here"]
           (mapv :ui.item/label (:ui/items action))))
    (is (= {:action/kind :fork-session
            :action/session-id "s1"
            :action/entry-id "e1"}
           (:ui.item/value (second (:ui/items action)))))
    (is (nil? (:ui/legacy action)))))

(deftest resume-session-action-wraps-query-result-test
  (let [query-result {:psi.persisted-session/list [{:psi.session-info/path "/tmp/a.ndedn"
                                                    :psi.session-info/name "Alpha"}]}
        action (ui-actions/resume-session-action query-result)]
    (is (= :select-resume-session (:ui/action-id action)))
    (is (= :select-resume-session (:ui/action-name action)))
    (is (= "/tmp/a.ndedn" (get-in action [:ui/items 0 :ui.item/value])))
    (is (nil? (:ui/legacy action)))))

(deftest model-and-thinking-actions-produce_canonical_submit_contracts_test
  (let [model-action (ui-actions/model-picker-action [{:provider "openai"
                                                       :id "gpt-5.3-codex"
                                                       :reasoning true}])
        thinking-action (ui-actions/thinking-picker-action)]
    (is (= :set-model (get-in model-action [:ui/on-submit :submit/kind])))
    (is (= :select-model (:ui/action-id model-action)))
    (is (= :select-model (:ui/action-name model-action)))
    (is (= :set-thinking-level (get-in thinking-action [:ui/on-submit :submit/kind])))
    (is (= :select-thinking-level (:ui/action-id thinking-action)))
    (is (= :select-thinking-level (:ui/action-name thinking-action)))
    (is (= ["off" "minimal" "low" "medium" "high" "xhigh"]
           (mapv :ui.item/value (:ui/items thinking-action))))))

(deftest action-result-normalizes-canonical-action-ids-and-statuses-test
  (testing "canonical action names normalize to canonical action keys"
    (is (= :select-session
           (:ui.result/action-key
            (ui-actions/action-result {:action-name "select-session"
                                       :status "submitted"}))))
    (is (= :select-resume-session
           (:ui.result/action-key
            (ui-actions/action-result {:action-name "select-resume-session"
                                       :status "submitted"}))))
    (is (= :select-model
           (:ui.result/action-key
            (ui-actions/action-result {:action-name "select-model"
                                       :status "submitted"}))))
    (is (= :select-thinking-level
           (:ui.result/action-key
            (ui-actions/action-result {:action-name "select-thinking-level"
                                       :status "submitted"})))))
  (testing "status values normalize to canonical keywords"
    (is (= :submitted (:ui.result/status (ui-actions/action-result {:status "submitted"}))))
    (is (= :cancelled (:ui.result/status (ui-actions/action-result {:status "cancelled"}))))
    (is (= :failed (:ui.result/status (ui-actions/action-result {:status "failed"})))))
  (testing "cancelled and failed results derive canonical messages"
    (is (= "Cancelled select-model."
           (:ui.result/message (ui-actions/action-result {:action-name "select-model"
                                                          :status "cancelled"}))))
    (is (= "Frontend action failed: select-model"
           (:ui.result/message (ui-actions/action-result {:action-name "select-model"
                                                          :status "failed"}))))
    (is (= "boom"
           (:ui.result/message (ui-actions/action-result {:action-name "select-model"
                                                          :status "failed"
                                                          :error-message "boom"})))))
  (testing "session selector values normalize to canonical action maps"
    (is (= {:action/kind :switch-session
            :action/session-id "s2"}
           (:ui.result/value
            (ui-actions/action-result {:action-name "select-session"
                                       :status "submitted"
                                       :value "s2"}))))
    (is (= {:action/kind :fork-session
            :action/session-id "s1"
            :action/entry-id "e1"}
           (:ui.result/value
            (ui-actions/action-result {:action-name "select-session"
                                       :status "submitted"
                                       :value {:action/kind :fork-session
                                               :action/session-id "s1"
                                               :action/entry-id "e1"}})))))
  (testing "model and thinking picker values normalize to canonical submitted values"
    (is (= {:provider "openai" :id "gpt-5.3-codex"}
           (:ui.result/value
            (ui-actions/action-result {:action-name "select-model"
                                       :status "submitted"
                                       :value {:provider "openai" :id "gpt-5.3-codex"}}))))
    (is (= "high"
           (:ui.result/value
            (ui-actions/action-result {:action-name "select-thinking-level"
                                       :status "submitted"
                                       :value :high}))))))
