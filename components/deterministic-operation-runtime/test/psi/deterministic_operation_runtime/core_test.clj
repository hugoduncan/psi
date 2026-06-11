(ns psi.deterministic-operation-runtime.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.deterministic-operation-runtime.core :as runtime]))

(deftest invoke-operation-test
  (testing "handler receives injected operation-id"
    (let [received* (atom nil)]
      (is (= {:status :ok :data {:ok true}}
             (runtime/invoke-operation {:id "github/search"
                                        :handler (fn [invocation]
                                                   (reset! received* invocation)
                                                   {:status :ok :data {:ok true}})}
                                       {:args {:repo 1}})))
      (is (= {:operation-id "github/search"
              :args {:repo 1}}
             (select-keys @received* [:operation-id :args])))))

  (testing "successful operation results pass through unchanged"
    (let [result {:status :ok
                  :data {:issues [1]}
                  :summary "1 issue"}]
      (is (= result
             (runtime/invoke-operation {:id "github/search"
                                        :handler (fn [_]
                                                   result)}
                                       {:args {:repo 1}})))))

  (testing "thrown exceptions are canonicalized into tagged error results"
    (is (= {:status :error
            :reason :operation-threw
            :message "boom"
            :details {:operation-id "github/search"}}
           (runtime/invoke-operation {:id "github/search"
                                      :handler (fn [_]
                                                 (throw (ex-info "boom" {})))}
                                     {:args {}}))))

  (testing "malformed returned values are rejected with structured ex-info"
    (let [ex (try
               (runtime/invoke-operation {:id "github/search"
                                          :handler (fn [_] {:status :succeeded :data {}})}
                                         {:args {}
                                          :ctx :opaque
                                          :step-id "discover"})
               (catch clojure.lang.ExceptionInfo e
                 e))]
      (is (= "Deterministic operation returned malformed result"
             (ex-message ex)))
      (is (= {:type :malformed-operation-result
              :operation-id "github/search"
              :invocation {:args {}
                           :step-id "discover"}
              :result {:status :succeeded :data {}}}
             (select-keys (ex-data ex)
                          [:type :operation-id :invocation :result])))
      (is (= [{:path [:status]
               :in [:status]
               :type :malli.core/invalid-dispatch-value}]
             (mapv #(select-keys % [:path :in :type])
                   (:errors (:explanation (ex-data ex)))))))))

(deftest invoke-operation-honors-workflow-cancellation-test
  ;; Regression for task 225 implementation review pass 6: the deterministic
  ;; operation runtime boundary consumes the durable workflow stop marker, so an
  ;; operation cannot start after the canonical cancel CAS wins.
  (testing "cancelled workflow run prevents handler invocation"
    (let [handler-calls* (atom 0)
          result (runtime/invoke-operation
                  {:id "workflow/op"
                   :handler (fn [_]
                              (swap! handler-calls* inc)
                              {:status :ok :data {:started? true}})}
                  {:ctx {:state* (atom {:workflows {:runs {"run-cancelled" {:run-id "run-cancelled"
                                                                            :status :cancelled}}}})}
                   :workflow-run-id "run-cancelled"
                   :step-id "invoke"})]
      (is (= 0 @handler-calls*)
          "the operation handler must not be invoked after cancellation")
      (is (= {:status :error
              :reason :workflow-stopped
              :message "Workflow execution stopped before deterministic operation start"
              :details {:operation-id "workflow/op"
                        :workflow-run-id "run-cancelled"
                        :step-id "invoke"
                        :stop-reason :cancelled}}
             result)))))
