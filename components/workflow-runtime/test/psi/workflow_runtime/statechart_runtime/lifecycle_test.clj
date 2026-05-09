(ns psi.workflow-runtime.statechart-runtime.lifecycle-test
  (:require
   [clojure.test :refer [deftest is]]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [psi.workflow-runtime.statechart-runtime.lifecycle :as lifecycle]))

(deftest send-and-drain-delegates-through-lifecycle-test
  (let [calls* (atom [])
        wf-ctx {:event-queue* (atom []) :run-id "run-1"}
        wm {::wmdm/configuration #{:pending}}]
    (with-redefs [psi.workflow-runtime.statechart-runtime.lifecycle/process-event!
                  (fn [_wf-ctx _wm event data]
                    (swap! calls* conj [:process event data])
                    (assoc wm ::wmdm/configuration #{}))
                  psi.workflow-runtime.statechart-runtime.lifecycle/drain-events!
                  (fn [_wf-ctx wm']
                    (swap! calls* conj [:drain (::wmdm/configuration wm')])
                    :done)]
      (is (= :done
             (lifecycle/send-and-drain! wf-ctx wm :workflow/start {:x 1})))
      (is (= [[:process :workflow/start {:x 1}]
              [:drain #{}]]
             @calls*)))))
