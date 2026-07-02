(ns psi.state-kernel.dispatch-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
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

(deftest normalize-event-populates-defaults-and-explicit-opts
  (let [event (dispatch/normalize-event :demo/run {:session-id "s-1" :x 1} {})
        event* (dispatch/normalize-event :demo/run {:x 2}
                                         {:origin :extension
                                          :ext-id "/ext/demo.clj"
                                          :replaying? true
                                          :dispatch-id "d-123"})]
    (is (= :demo/run (:event/type event)))
    (is (= {:session-id "s-1" :x 1} (:event/data event)))
    (is (= "s-1" (:event/session-id event)))
    (is (= :core (:event/origin event)))
    (is (nil? (:event/ext-id event)))
    (is (false? (:event/replaying? event)))
    (is (string? (:event/dispatch-id event)))

    (is (= {:event/type :demo/run
            :event/data {:x 2}
            :event/session-id nil
            :event/origin :extension
            :event/ext-id "/ext/demo.clj"
            :event/replaying? true
            :event/dispatch-id "d-123"}
           event*))))

(deftest replay-dispatch-marks-handler-data
  ;; Replayed handlers receive explicit replay context so pure lifecycle code can
  ;; rebuild recorded state without rerunning live effects.
  (let [seen (atom nil)]
    (dispatch/register-handler! :demo/replay-aware
                                (fn [_ data]
                                  (reset! seen data)
                                  :ok))
    (is (= :ok (dispatch/dispatch! {} :demo/replay-aware {:x 1} {:replaying? true})))
    (is (= {:x 1
            :replaying? true
            :dispatch-id (:dispatch-id @seen)}
           @seen))))

(deftest interceptor-chain-runs-before-in-order-after-in-reverse-and-stops-after-block
  (testing "before fns run in order and after fns run in reverse"
    (let [order (atom [])
          interceptors [(dispatch/->interceptor
                         {:id :a
                          :before (fn [ictx] (swap! order conj :a-before) ictx)
                          :after (fn [ictx] (swap! order conj :a-after) ictx)})
                        (dispatch/->interceptor
                         {:id :b
                          :before (fn [ictx] (swap! order conj :b-before) ictx)
                          :after (fn [ictx] (swap! order conj :b-after) ictx)})]]
      (dispatch/run-interceptor-chain {:env {}} interceptors)
      (is (= [:a-before :b-before :b-after :a-after] @order))))

  (testing "blocked ctx stops later before fns but still unwinds after fns in reverse"
    (let [order (atom [])
          interceptors [(dispatch/->interceptor
                         {:id :a
                          :before (fn [ictx] (swap! order conj :a-before) ictx)
                          :after (fn [ictx] (swap! order conj :a-after) ictx)})
                        (dispatch/->interceptor
                         {:id :blocker
                          :before (fn [ictx]
                                    (swap! order conj :blocker-before)
                                    (assoc ictx :blocked? true :block-reason :blocked))
                          :after (fn [ictx] (swap! order conj :blocker-after) ictx)})
                        (dispatch/->interceptor
                         {:id :c
                          :before (fn [ictx] (swap! order conj :c-before) ictx)
                          :after (fn [ictx] (swap! order conj :c-after) ictx)})]
          result (dispatch/run-interceptor-chain {:env {} :blocked? false} interceptors)]
      (is (= true (:blocked? result)))
      (is (= :blocked (:block-reason result)))
      (is (= [:a-before :blocker-before :c-after :blocker-after :a-after] @order)))))

(deftest replay-suppresses-effects-but-preserves-state-update
  (let [state* (atom {:count 0})
        effects* (atom [])
        env {:state* state*
             :execute-effect-fn (fn [_ effect]
                                  (swap! effects* conj effect)
                                  :done)}]
    (dispatch/register-handler! :tick
                                (fn [_ _]
                                  {:root-state-update #(update % :count inc)
                                   :effects [{:effect/type :demo/ping}]
                                   :return :ok}))
    (is (= :ok (dispatch/dispatch! env :tick nil {:replaying? true})))
    (is (= {:count 1} @state*))
    (is (= [] @effects*))
    (let [entry (last (dispatch/event-log-entries))]
      (is (true? (:replaying? entry)))
      (is (= [{:effect/type :demo/ping}] (:declared-effects entry)))
      (is (= [] (:applied-effects entry))))))

(deftest validation-failure-blocks-effects-and-records-log-state
  (let [state* (atom {:count 0})
        effects* (atom [])
        env {:state* state*
             :execute-effect-fn (fn [_ effect]
                                  (swap! effects* conj effect)
                                  :done)
             :validate-result-fn (fn [_ ictx]
                                   (is (= [{:effect/type :demo/ping}] (:applied-effects ictx)))
                                   {:valid? false
                                    :reason :invalid-demo})}]
    (dispatch/register-handler! :emit
                                (fn [_ _]
                                  {:effects [{:effect/type :demo/ping}]
                                   :return :ok}))
    (is (= :ok (dispatch/dispatch! env :emit {})))
    (is (= [] @effects*))
    (let [entry (last (dispatch/event-log-entries))
          trace (last (dispatch/dispatch-trace-entries))]
      (is (true? (:blocked? entry)))
      (is (= :invalid-demo (:block-reason entry)))
      (is (= :invalid-demo (:validation-error entry)))
      (is (= [{:effect/type :demo/ping}] (:declared-effects entry)))
      (is (= [] (:applied-effects entry)))
      (is (= :dispatch/failed (:trace/kind trace)))
      (is (= :invalid-demo (:validation-error trace)))
      (is (= :invalid-demo (:block-reason trace))))))

(deftest validator-exception-blocks-with-structured-reason
  (let [env {:validate-result-fn (fn [_ _]
                                   (throw (ex-info "boom" {})))}]
    (dispatch/register-handler! :emit
                                (fn [_ _]
                                  {:effects [{:effect/type :demo/ping}]
                                   :return :ok}))
    (is (= :ok (dispatch/dispatch! env :emit {})))
    (let [entry (last (dispatch/event-log-entries))]
      (is (true? (:blocked? entry)))
      (is (= {:type :validator-exception
              :message "boom"}
             (:validation-error entry))))))

(deftest handler-exception-records-failed-trace-and_returns_nil
  (dispatch/register-handler! :boom
                              (fn [_ _]
                                (throw (ex-info "handler blew up" {}))))
  (is (nil? (dispatch/dispatch! {} :boom {})))
  (let [entries (dispatch/dispatch-trace-entries)]
    (is (= :dispatch/received (:trace/kind (first entries))))
    (is (= :dispatch/completed (:trace/kind (last entries))))))

(deftest effect-exception-records-error-trace-and-rethrows
  (let [env {:execute-effect-fn (fn [_ _]
                                  (throw (ex-info "effect blew up" {})))}]
    (dispatch/register-handler! :emit
                                (fn [_ _]
                                  {:effects [{:effect/type :demo/ping}]}))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"effect blew up"
                          (dispatch/dispatch! env :emit {})))
    (let [entries (dispatch/dispatch-trace-entries)
          finish (last entries)]
      (is (some #(= :dispatch/effect-start (:trace/kind %)) entries))
      (is (= :dispatch/failed (:trace/kind finish)))
      (is (= "effect blew up" (:error-message (nth entries (- (count entries) 2)))))
      (is (= :dispatch/effect-finish (:trace/kind (nth entries (- (count entries) 2))))))))

(deftest publish-and-trace-callback-failures-are-non-fatal
  (let [published* (atom [])
        traced* (atom [])
        env {:dispatch-trace-fn (fn [entry]
                                  (swap! traced* conj entry)
                                  (when (= :dispatch/completed (:trace/kind entry))
                                    (throw (ex-info "trace sink failed" {}))))
             :publish-change-fn (fn [entry]
                                  (swap! published* conj entry)
                                  (throw (ex-info "publish failed" {})))}]
    (dispatch/register-handler! :ok (fn [_ _] :done))
    (is (= :done (dispatch/dispatch! env :ok {})))
    (is (seq @traced*))
    (is (= [{:dispatch-id (:dispatch-id (first @published*))
             :event-type :ok
             :session-id nil
             :blocked? false}]
           @published*))))

(deftest return-key-reads-post-update-root-state
  (let [state* (atom {:session {:name "before"}})
        env {:state* state*}]
    (dispatch/register-handler! :rename
                                (fn [_ _]
                                  {:root-state-update #(assoc-in % [:session :name] "after")
                                   :return-key [:session :name]}))
    (is (= "after" (dispatch/dispatch! env :rename {})))
    (is (= {:session {:name "after"}} @state*))))

(deftest replay-event-log-replays-in-order-through-kernel
  (let [state* (atom {:count 0})
        env {:state* state*}]
    (dispatch/register-handler! :inc
                                (fn [_ _]
                                  {:root-state-update #(update % :count inc)
                                   :return :ok}))
    (dispatch/dispatch! env :inc {})
    (dispatch/dispatch! env :inc {})
    (let [entries (dispatch/event-log-entries)]
      (reset! state* {:count 0})
      (is (= [:ok :ok]
             (dispatch/replay-event-log! dispatch/dispatch! env entries)))
      (is (= {:count 2} @state*)))))
