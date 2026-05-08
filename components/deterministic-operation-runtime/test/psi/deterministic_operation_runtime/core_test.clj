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
