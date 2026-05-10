(ns psi.deterministic-operation-registry.registry-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.deterministic-operation-registry.registry :as reg]))

(deftest registry-registration-and-lookup-test
  (let [registry (reg/create-registry)
        operation {:id "github/search-issues-by-label"
                   :description "Search issues"
                   :handler (fn [_] {:status :ok :data {:issues []}})}]
    (is (= registry (reg/register-operation-in! registry operation)))
    (is (= 1 (reg/operation-count-in registry)))
    (is (= ["github/search-issues-by-label"] (reg/operation-ids-in registry)))
    (is (= ["github/search-issues-by-label"] (mapv :id (reg/all-operations-in registry))))
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
                                    {:args {:repo "psi"}}
                                    (fn [operation invocation]
                                      ((:handler operation) invocation)))))
    (is (= {:status :error
            :reason :not-found
            :message "repo missing"
            :details {:repo "x"}}
           (reg/invoke-operation-in registry
                                    "github/fail"
                                    {:args {}}
                                    (fn [operation invocation]
                                      ((:handler operation) invocation)))))))

(deftest invoke-operation-missing-id-test
  (let [registry (reg/create-registry)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"not found"
         (reg/invoke-operation-in registry
                                  "github/missing"
                                  {:args {}}
                                  (fn [_ _]
                                    (throw (ex-info "should not be called" {}))))))))

(deftest unregister-operations-by-extension-test
  (let [registry (reg/create-registry)]
    (reg/register-operation-in! registry
                                {:id "github/search"
                                 :ext-path "/ext/a"
                                 :handler (fn [_] {:status :ok :data {}})})
    (reg/register-operation-in! registry
                                {:id "github/create"
                                 :ext-path "/ext/a"
                                 :handler (fn [_] {:status :ok :data {}})})
    (reg/register-operation-in! registry
                                {:id "jira/search"
                                 :ext-path "/ext/b"
                                 :handler (fn [_] {:status :ok :data {}})})
    (is (= registry (reg/unregister-operations-by-extension-in! registry "/ext/a")))
    (is (= ["jira/search"] (reg/operation-ids-in registry)))
    (is (nil? (reg/get-operation-in registry "github/search")))
    (is (nil? (reg/get-operation-in registry "github/create")))
    (is (= "jira/search" (:id (reg/get-operation-in registry "jira/search"))))))

(deftest unregister-nil-tolerant-and-order-preserving-test
  (let [registry (reg/create-registry)]
    (reg/register-operation-in! registry {:id "a/one" :ext-path "/ext/a" :handler (fn [_] {:status :ok :data 1})})
    (reg/register-operation-in! registry {:id "b/two" :ext-path "/ext/b" :handler (fn [_] {:status :ok :data 2})})
    (reg/register-operation-in! registry {:id "a/three" :ext-path "/ext/a" :handler (fn [_] {:status :ok :data 3})})
    (reg/unregister-operations-by-extension-in! registry "/ext/missing")
    (is (= ["a/one" "b/two" "a/three"] (reg/operation-ids-in registry)))
    (reg/unregister-operations-by-extension-in! registry "/ext/a")
    (is (= ["b/two"] (reg/operation-ids-in registry)))))
