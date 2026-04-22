(ns psi.app-runtime.projections-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.app-runtime.projections :as projections]))

(deftest extension-ui-snapshot-from-state-sorts-widgets-statuses-and-carries-tools-expanded-test
  (let [snapshot (projections/extension-ui-snapshot-from-state
                  {:dialog-queue {:pending [] :active nil}
                   :widgets {[:ext-b :w-2] {:placement :below-editor
                                            :extension-id "ext-b"
                                            :widget-id "w-2"
                                            :content ["B2"]}
                             [:ext-a :w-3] {:placement :above-editor
                                            :extension-id "ext-a"
                                            :widget-id "w-3"
                                            :content ["A3"]}
                             [:ext-b :w-1] {:placement :above-editor
                                            :extension-id "ext-b"
                                            :widget-id "w-1"
                                            :content ["B1"]}}
                   :widget-specs {}
                   :statuses {"ext-z" {:extension-id "ext-z" :text "Zeta"}
                              "ext-a" {:extension-id "ext-a" :text "Alpha"}}
                   :notifications []
                   :tool-renderers {"read" {:tool-name "read"
                                            :extension-path "ext-a"
                                            :render-call-fn (fn [_] "call")
                                            :render-result-fn (fn [_ _] "result")}}
                   :message-renderers {"custom" {:custom-type "custom"
                                                 :extension-path "ext-a"
                                                 :render-fn (fn [_ _] "msg")}}
                   :tools-expanded? true})]
    (is (= [["ext-a" "w-3"]
            ["ext-b" "w-1"]
            ["ext-b" "w-2"]]
           (mapv (juxt :extension-id :widget-id) (:widgets snapshot))))
    (is (= ["ext-a" "ext-z"]
           (mapv :extension-id (:statuses snapshot))))
    (is (= #{:render-call-fn :render-result-fn}
           (set (keys (get-in snapshot [:tool-renderers "read"])))))
    (is (= #{:render-fn}
           (set (keys (get-in snapshot [:message-renderers "custom"])))))
    (is (true? (:tools-expanded? snapshot)))))

(deftest extension-ui-snapshot-from-state-preserves-canonical-visible-notification-order-test
  (testing "snapshot preserves oldest->newest order for the visible notification window"
    (let [snapshot (projections/extension-ui-snapshot-from-state
                    {:dialog-queue {:pending [] :active nil}
                     :widgets {}
                     :widget-specs {}
                     :statuses {}
                     :notifications [{:id "n1" :message "first"  :created-at 1000 :dismissed? false}
                                     {:id "n2" :message "second" :created-at 2000 :dismissed? false}
                                     {:id "n3" :message "third"  :created-at 3000 :dismissed? false}
                                     {:id "n4" :message "fourth" :created-at 4000 :dismissed? false}]
                     :tool-renderers {}
                     :message-renderers {}
                     :tools-expanded? false})]
      (is (= ["n2" "n3" "n4"]
             (mapv :id (:visible-notifications snapshot)))))))
