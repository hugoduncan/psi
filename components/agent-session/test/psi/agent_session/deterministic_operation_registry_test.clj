(ns psi.agent-session.deterministic-operation-registry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.deterministic-operation-registry :as reg]
   [psi.agent-session.deterministic-operations :as ops]))

(deftest registry-registration-and-lookup-test
  (let [registry (reg/create-registry)
        operation {:id "github/search-issues-by-label"
                   :description "Search issues"
                   :handler (fn [_] {:status :ok :data {:issues []}})}]
    (reg/register-operation-in! registry operation)
    (is (= 1 (reg/operation-count-in registry)))
    (is (= ["github/search-issues-by-label"] (reg/operation-ids-in registry)))
    (is (= "github/search-issues-by-label"
           (:id (reg/get-operation-in registry "github/search-issues-by-label"))))))

(deftest duplicate-operation-id-rejected-test
  (let [registry (reg/create-registry)
        operation {:id "github/search-issues-by-label"
                   :handler (fn [_] {:status :ok :data {}})}]
    (reg/register-operation-in! registry operation)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"already registered"
         (reg/register-operation-in! registry operation)))))

(deftest invoke-operation-success-and-error-test
  (let [registry (reg/create-registry)]
    (reg/register-operation-in! registry
                                {:id "github/search-issues-by-label"
                                 :handler (fn [{:keys [args]}]
                                            {:status :ok
                                             :data {:repo (:repo args)}
                                             :summary "1 issue"})})
    (reg/register-operation-in! registry
                                {:id "github/fail"
                                 :handler (fn [_]
                                            {:status :error
                                             :reason :not-found
                                             :message "repo missing"
                                             :details {:repo "x"}})})
    (is (= {:status :ok :data {:repo "psi"} :summary "1 issue"}
           (reg/invoke-operation-in registry
                                    "github/search-issues-by-label"
                                    {:args {:repo "psi"}})))
    (is (= {:status :error
            :reason :not-found
            :message "repo missing"
            :details {:repo "x"}}
           (reg/invoke-operation-in registry "github/fail" {:args {}})))))

(deftest malformed-result-rejected-test
  (let [registry (reg/create-registry)]
    (reg/register-operation-in! registry
                                {:id "github/bad"
                                 :handler (fn [_] {:oops true})})
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"malformed result"
         (reg/invoke-operation-in registry "github/bad" {:args {}})))))

(deftest thrown-operation-becomes-canonical-error-result-test
  (let [registry (reg/create-registry)]
    (reg/register-operation-in! registry
                                {:id "github/throws"
                                 :handler (fn [_] (throw (ex-info "boom" {})))})
    (is (= {:status :error
            :reason :operation-threw
            :message "boom"
            :details {:operation-id "github/throws"}}
           (reg/invoke-operation-in registry "github/throws" {:args {}})))))

(deftest invoke-step-wrapping-test
  (testing "successful operation result wraps into canonical invoke outputs"
    (is (= {:kind :accepted-result
            :accepted-result {:outcome :ok
                              :outputs {:data {:issues [1]}
                                        :summary "1 issue"
                                        :result {:status :ok
                                                 :data {:issues [1]}
                                                 :summary "1 issue"}}}}
           (ops/operation-result->invoke-step-result
            {:status :ok :data {:issues [1]} :summary "1 issue"}))))

  (testing "error operation result wraps into canonical error yield input"
    (is (= {:kind :error-yield
            :yield {:type :error
                    :reason :not-found
                    :message "repo missing"
                    :details {:operation-result {:status :error
                                                 :reason :not-found
                                                 :message "repo missing"}}}}
           (ops/operation-result->invoke-step-result
            {:status :error :reason :not-found :message "repo missing"})))))
