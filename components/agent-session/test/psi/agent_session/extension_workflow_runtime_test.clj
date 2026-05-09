(ns psi.agent-session.extension-workflow-runtime-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]
   [psi.agent-session.extension-workflow-runtime :as extension-workflow-runtime]))

(def simple-chart
  (chart/statechart {:id :simple-workflow}
                    (ele/state {:id :idle}
                               (ele/transition {:event :workflow/start :target :running}))

                    (ele/state {:id :running}
                               (ele/transition {:event :workflow/finish :target :done}
                                               (ele/script
                                                {:expr (fn [_ data]
                                                         [{:op :assign
                                                           :data {:result (get-in data [:_event :data :result])}}])})))

                    (ele/final {:id :done})))

(def invoke-chart
  (chart/statechart {:id :invoke-workflow}
                    (ele/state {:id :idle}
                               (ele/transition {:event :workflow/start :target :running}))

                    (ele/state {:id :running}
                               (ele/invoke
                                {:id     :job
                                 :type   :future
                                 :params (fn [_ data] {:value (:value data)})
                                 :src    (fn [{:keys [value]}]
                                           (Thread/sleep 40)
                                           {:value value})})
                               (ele/transition {:event :done.invoke.job :target :done}
                                               (ele/script
                                                {:expr (fn [_ data]
                                                         [{:op :assign
                                                           :data {:result (get-in data [:_event :data :value])}}])})))

                    (ele/final {:id :done})))

(def resumable-chart
  (chart/statechart {:id :resumable-workflow}
                    (ele/state {:id :idle}
                               (ele/transition {:event :workflow/start :target :running}))

                    (ele/state {:id :running}
                               (ele/transition {:event :workflow/finish :target :done}
                                               (ele/script
                                                {:expr (fn [_ data]
                                                         [{:op :assign
                                                           :data {:result (get-in data [:_event :data :result])}}])})))

    ;; Non-final terminal state: can transition back to running.
                    (ele/state {:id :done}
                               (ele/transition {:event :workflow/continue :target :running}))))

(defn- wait-until-done
  [reg ext-path id]
  (loop [i 0]
    (let [w (extension-workflow-runtime/workflow-in reg ext-path id)]
      (cond
        (>= i 200) w
        (= :done (:phase w)) w
        :else (do
                (Thread/sleep 10)
                (recur (inc i)))))))

(deftest workflow-lifecycle-test
  (let [reg (extension-workflow-runtime/create-registry)]
    (try
      (testing "register/create/send/remove workflow"
        (let [r1 (extension-workflow-runtime/register-type-in! reg "/ext/a" {:type :simple :chart simple-chart})
              r2 (extension-workflow-runtime/create-workflow-in! reg "/ext/a" {:type :simple :id "w1" :auto-start? false})]
          (is (true? (:registered? r1)))
          (is (true? (:created? r2)))

          (is (true? (:event-accepted?
                      (extension-workflow-runtime/send-event-in! reg "/ext/a" "w1" :workflow/start nil))))
          (is (true? (:event-accepted?
                      (extension-workflow-runtime/send-event-in! reg "/ext/a" "w1" :workflow/finish {:result 42}))))

          (let [w (extension-workflow-runtime/workflow-in reg "/ext/a" "w1")]
            (is (= :done (:phase w)))
            (is (= 42 (:result w)))
            (is (false? (:running? w)))
            (is (true? (:done? w)))
            (is (= 2 (:event-count w))))

          (is (true? (:removed? (extension-workflow-runtime/remove-workflow-in! reg "/ext/a" "w1"))))
          (is (= 0 (extension-workflow-runtime/workflow-count-in reg "/ext/a")))))
      (finally
        (extension-workflow-runtime/shutdown-in! reg)))))

(deftest workflow-invoke-future-test
  (let [reg (extension-workflow-runtime/create-registry)]
    (try
      (testing "future invocation completion is processed by workflow pump"
        (is (true?
             (:registered?
              (extension-workflow-runtime/register-type-in!
               reg
               "/ext/async"
               {:type            :async
                :chart           invoke-chart
                :initial-data-fn (fn [input] {:value (:value input)})}))))

        (let [created (extension-workflow-runtime/create-workflow-in!
                       reg
                       "/ext/async"
                       {:type :async :id "a1" :input {:value 7}})]
          (is (true? (:created? created)))
          (let [w (wait-until-done reg "/ext/async" "a1")]
            (is (= :done (:phase w)))
            (is (= 7 (:result w)))
            (is (false? (:running? w))))))
      (finally
        (extension-workflow-runtime/shutdown-in! reg)))))

(deftest workflow-resumable-done-phase-test
  (let [reg (extension-workflow-runtime/create-registry)]
    (try
      (is (true?
           (:registered?
            (extension-workflow-runtime/register-type-in! reg "/ext/r" {:type :resumable :chart resumable-chart}))))

      (is (true?
           (:created?
            (extension-workflow-runtime/create-workflow-in! reg "/ext/r" {:type :resumable :id "r1" :auto-start? false}))))

      (is (true? (:event-accepted?
                  (extension-workflow-runtime/send-event-in! reg "/ext/r" "r1" :workflow/start nil))))
      (is (true? (:event-accepted?
                  (extension-workflow-runtime/send-event-in! reg "/ext/r" "r1" :workflow/finish {:result 1}))))

      (let [w (extension-workflow-runtime/workflow-in reg "/ext/r" "r1")]
        (is (= :done (:phase w)))
        (is (true? (:done? w)))
        (is (false? (:running? w)))
        (is (= 1 (:result w))))

      (is (true? (:event-accepted?
                  (extension-workflow-runtime/send-event-in! reg "/ext/r" "r1" :workflow/continue nil))))
      (let [w (extension-workflow-runtime/workflow-in reg "/ext/r" "r1")]
        (is (= :running (:phase w)))
        (is (true? (:running? w)))
        (is (false? (:done? w))))

      (is (true? (:event-accepted?
                  (extension-workflow-runtime/send-event-in! reg "/ext/r" "r1" :workflow/finish {:result 2}))))
      (let [w (extension-workflow-runtime/workflow-in reg "/ext/r" "r1")]
        (is (= :done (:phase w)))
        (is (true? (:done? w)))
        (is (false? (:running? w)))
        (is (= 2 (:result w))))
      (finally
        (extension-workflow-runtime/shutdown-in! reg)))))
