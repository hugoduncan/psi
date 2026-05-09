(ns psi.workflow-runtime.statechart-runtime.lifecycle-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.fulcrologic.statecharts :as sc]
   [psi.workflow-runtime.statechart-runtime.lifecycle :as lifecycle]
   [psi.workflow-runtime.statechart-runtime.state :as state]))

(deftest drain-events-processes-queued-events-in-order-test
  (let [calls* (atom [])
        event-queue* (atom [{:event :e1 :data {:n 1}}
                            {:event :e2 :data {:n 2}}])
        wf-ctx {:event-queue* event-queue*
                :run-id "run-1"}
        wm {::sc/configuration #{:pending}}]
    (with-redefs [psi.workflow-runtime.statechart-runtime.lifecycle/process-event!
                  (fn [_wf-ctx wm event data]
                    (swap! calls* conj [event data])
                    wm)]
      (is (= wm
             (lifecycle/drain-events! wf-ctx wm)))
      (is (= [[:e1 {:n 1}]
              [:e2 {:n 2}]]
             @calls*))
      (is (= [] @event-queue*)))))

(deftest drain-events-discards-queued-tail-on-terminal-configuration-test
  (let [calls* (atom [])
        event-queue* (atom [{:event :e1 :data {:n 1}}])
        wf-ctx {:event-queue* event-queue*
                :run-id "run-2"}
        wm {::sc/configuration #{:completed}}]
    (with-redefs [psi.workflow-runtime.statechart-runtime.lifecycle/process-event!
                  (fn [& _]
                    (swap! calls* conj :processed)
                    wm)]
      (is (= wm
             (lifecycle/drain-events! wf-ctx wm)))
      (is (= [] @calls*))
      (is (= [] @event-queue*)))))

(deftest drain-events-throws-on-overflow-test
  (let [event-queue* (atom [{:event :e1 :data {:n 1}}])
        wf-ctx {:event-queue* event-queue*
                :run-id "run-3"}
        wm {::sc/configuration #{:pending}}]
    (with-redefs [psi.workflow-runtime.statechart-runtime.state/max-drain-events 0]
      (let [ex (is (thrown-with-msg?
                    clojure.lang.ExceptionInfo
                    #"Workflow event drain exceeded safety bound"
                    (lifecycle/drain-events! wf-ctx wm)))]
        (testing "overflow reports run and queue context"
          (let [data (ex-data ex)]
            (is (= "run-3" (:run-id data)))
            (is (= 0 (:processed-events data)))
            (is (= [{:event :e1 :data {:n 1}}]
                   (:queued-events data)))))))))
