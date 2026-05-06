(ns psi.state-kernel.dispatch-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [psi.state-kernel.dispatch :as dispatch]))

(defn- clean-state [f]
  (dispatch/clear-handlers!)
  (dispatch/clear-event-log!)
  (dispatch/clear-dispatch-trace!)
  (dispatch/set-interceptors! nil)
  (try (f)
       (finally
         (dispatch/clear-handlers!)
         (dispatch/clear-event-log!)
         (dispatch/clear-dispatch-trace!)
         (dispatch/set-interceptors! nil))))

(use-fixtures :each clean-state)

(deftest applies-pure-root-state-update-through-kernel
  (let [state* (atom {:count 0})
        env {:state* state*}]
    (dispatch/register-handler! :inc
                                (fn [_ _]
                                  {:root-state-update #(update % :count inc)
                                   :return :ok}))
    (is (= :ok (dispatch/dispatch! env :inc {})))
    (is (= {:count 1} @state*))))

(deftest bounded-event-log-and-trace-retained-in-kernel
  (dispatch/register-handler! :ping (fn [_ _] :pong))
  (dotimes [_ 1005]
    (dispatch/dispatch! {} :ping {}))
  (is (<= (count (dispatch/event-log-entries)) 1000))
  (is (<= (count (dispatch/dispatch-trace-entries)) 1000))
  (is (= :ping (:event-type (last (dispatch/event-log-entries))))))

(deftest effect-execution-uses-kernel-environment-contract
  (let [effects* (atom [])
        env {:execute-effect-fn (fn [_ effect]
                                  (swap! effects* conj effect)
                                  :done)}]
    (dispatch/register-handler! :emit
                                (fn [_ _]
                                  {:effects [{:effect/type :demo/ping :x 1}]
                                   :return-effect-result? true}))
    (is (= :done (dispatch/dispatch! env :emit {})))
    (is (= [{:effect/type :demo/ping :x 1}] @effects*))))

(deftest kernel-event-log-summary-is-domain-independent
  (let [state* (atom {:agent-session {:sessions {:s1 {}}}
                      :background-jobs {:store {:j1 {}}}
                      :turn {:ctx {:active true}}
                      :other {:x 1}})
        env {:state* state*}]
    (dispatch/register-handler! :noop (fn [_ _] :ok))
    (is (= :ok (dispatch/dispatch! env :noop {})))
    (let [entry (last (dispatch/event-log-entries))]
      (is (= {:root-keys [:agent-session :background-jobs :other :turn]
              :root-key-count 4}
             (:db-summary-before entry)))
      (is (= {:root-keys [:agent-session :background-jobs :other :turn]
              :root-key-count 4}
             (:db-summary-after entry)))
      (is (not (contains? entry :statechart-claimed?))))))
