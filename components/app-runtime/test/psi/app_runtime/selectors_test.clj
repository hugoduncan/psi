(ns psi.app-runtime.selectors-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.app-runtime.selectors :as selectors]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest context-session-selector-orders-root-and-child-sessions-test
  (let [items [{:item/id [:session "child"]
                :item/kind :session
                :item/session-id "child"
                :item/parent-id [:session "root"]}
               {:item/id [:session "root"]
                :item/kind :session
                :item/session-id "root"
                :item/parent-id nil}]
        ordered (#'selectors/tree-sort-session-items items)]
    (is (= ["root" "child"]
           (mapv :item/session-id ordered)))))

(deftest context-session-selector-interleaves-current-session-fork-points-after-descendants-test
  (let [[ctx root-id] (create-session-context {:persist? false})
        child-sd      (session/new-session-in! ctx root-id {})
        child-id      (:session-id child-sd)
        _             (ss/update-state-value-in! ctx
                                                 (ss/state-path :session-data child-id)
                                                 assoc
                                                 :parent-session-id root-id)
        entry         (persist/message-entry {:role "user"
                                              :content [{:type :text :text "Branch from here"}]})
        _             (ss/journal-append-in! ctx root-id entry)
        items         (selectors/context-session-selector-items ctx root-id)]
    (is (= [[:session root-id]
            [:session child-id]
            [:fork-point (:id entry)]]
           (mapv :item/id items)))))

(deftest context-session-selector-ensures-current-session-present-when-missing-from-index-test
  (let [[ctx root-id] (create-session-context {:persist? false})
        sessions      []
        ensured       (#'selectors/ensure-current-session-present ctx root-id sessions)]
    (is (= [root-id]
           (mapv :session-id ensured)))))

(deftest context-session-selector-marks-active-and-streaming-items-test
  (let [[ctx root-id] (create-session-context {:persist? false})
        child-sd      (session/new-session-in! ctx root-id {})
        child-id      (:session-id child-sd)
        _             (ss/update-state-value-in! ctx
                                                 (ss/state-path :session-data child-id)
                                                 assoc
                                                 :is-streaming true)
        selector      (selectors/context-session-selector ctx root-id)
        items         (:selector/items selector)
        root-item     (some #(when (= root-id (:item/session-id %)) %) items)
        child-item    (some #(when (= child-id (:item/session-id %)) %) items)]
    (is (= (:item/id root-item) (:selector/active-item-id selector)))
    (is (true? (:item/is-active root-item)))
    (is (false? (:item/is-active child-item)))
    (is (true? (:item/is-streaming child-item)))))

(deftest context-session-selector-uses-stable-item-ids-test
  (let [session-item {:item/kind :session :item/session-id "s1" :item/display-name "Main"}
        fork-item    {:item/kind :fork-point :item/display-name "From prompt"}]
    (is (= "Main" (selectors/selector-item->default-label session-item)))
    (is (= "⎇ From prompt" (selectors/selector-item->default-label fork-item)))))

(deftest context-session-selector-adds-default-labels-test
  (let [[ctx root-id] (create-session-context {:persist? false})
        entry         (persist/message-entry {:role "user"
                                              :content [{:type :text :text "Branch from here"}]})
        _             (ss/journal-append-in! ctx root-id entry)
        items         (selectors/context-session-selector-items ctx root-id)
        session-item  (first items)
        fork-item     (some #(when (= :fork-point (:item/kind %)) %) items)]
    (is (= "⎇ Branch from here" (:item/default-label fork-item)))
    (is (= (selectors/selector-item->default-label session-item)
           (:item/default-label session-item)))))
