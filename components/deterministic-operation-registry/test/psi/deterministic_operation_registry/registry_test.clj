(ns psi.deterministic-operation-registry.registry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.deterministic-operation-registry.registry :as reg]
   [psi.root-registry.registry :as root-registry]))

(deftest registry-registration-and-lookup-test
  ;; Proves registration, lookup, and unordered listing/count coherence.
  (let [registry (reg/create-registry)
        operation {:id "github/search-issues-by-label"
                   :description "Search issues"
                   :handler (fn [_] {:status :ok :data {:issues []}})}]
    (is (= registry (reg/register-operation-in! registry operation)))
    (is (= 1 (reg/operation-count-in registry)))
    (is (= #{"github/search-issues-by-label"}
           (set (reg/operation-ids-in registry))))
    (is (= #{"github/search-issues-by-label"}
           (set (map :id (reg/all-operations-in registry)))))
    (is (= "github/search-issues-by-label"
           (:id (reg/get-operation-in registry "github/search-issues-by-label"))))))

(deftest same-owner-operation-id-replaces-test
  ;; Proves same-owner re-registration refreshes the runtime handler while
  ;; preserving membership/count. This keeps reload-code idempotent for built-in
  ;; operations whose handlers are re-created after namespace reload.
  (let [registry (reg/create-registry)
        first-handler (fn [_] {:status :ok :data :first})
        second-handler (fn [_] {:status :ok :data :second})]
    (reg/register-operation-in! registry {:id "github/search-issues-by-label"
                                          :ext-path "github"
                                          :handler first-handler})
    (reg/register-operation-in! registry {:id "github/search-issues-by-label"
                                          :ext-path "github"
                                          :handler second-handler})
    (is (= 1 (reg/operation-count-in registry)))
    (is (= #{"github/search-issues-by-label"}
           (set (reg/operation-ids-in registry))))
    (is (= second-handler
           (:handler (reg/get-operation-in registry "github/search-issues-by-label"))))))

(deftest cross-owner-duplicate-operation-id-rejected-test
  ;; Proves cross-owner duplicate registration throws and preserves membership/count.
  (let [registry (reg/create-registry)]
    (reg/register-operation-in! registry {:id "github/search-issues-by-label"
                                          :ext-path "github"
                                          :handler (fn [_] {:status :ok :data {}})})
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"already registered"
         (reg/register-operation-in! registry {:id "github/search-issues-by-label"
                                               :ext-path "other"
                                               :handler (fn [_] {:status :ok :data {}})})))
    (is (= 1 (reg/operation-count-in registry)))
    (is (= #{"github/search-issues-by-label"}
           (set (reg/operation-ids-in registry))))))

(deftest invoke-operation-success-and-error-test
  ;; Proves invoke behaviour is unchanged for success and error returns.
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
  ;; Proves missing invoke lookup still throws.
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
  ;; Proves unregister removes exactly matching operations and preserves survivors.
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
    (is (= 1 (reg/operation-count-in registry)))
    (is (= #{"jira/search"} (set (reg/operation-ids-in registry))))
    (is (= #{"jira/search"} (set (map :id (reg/all-operations-in registry)))))
    (is (nil? (reg/get-operation-in registry "github/search")))
    (is (nil? (reg/get-operation-in registry "github/create")))
    (is (= "jira/search" (:id (reg/get-operation-in registry "jira/search"))))))

(deftest unregister-missing-extension-is-no-op-test
  ;; Proves missing-extension unregister is nil-tolerant and leaves membership/count unchanged.
  (let [registry (reg/create-registry)]
    (reg/register-operation-in! registry {:id "a/one" :ext-path "/ext/a" :handler (fn [_] {:status :ok :data 1})})
    (reg/register-operation-in! registry {:id "b/two" :ext-path "/ext/b" :handler (fn [_] {:status :ok :data 2})})
    (reg/register-operation-in! registry {:id "a/three" :ext-path "/ext/a" :handler (fn [_] {:status :ok :data 3})})
    (reg/unregister-operations-by-extension-in! registry "/ext/missing")
    (is (= 3 (reg/operation-count-in registry)))
    (is (= #{"a/one" "b/two" "a/three"}
           (set (reg/operation-ids-in registry))))
    (is (= #{"a/one" "b/two" "a/three"}
           (set (map :id (reg/all-operations-in registry)))))))

(deftest listing-contract-is-unordered-membership-test
  ;; Proves listing helpers expose exact membership without an ordering guarantee.
  (let [registry (reg/create-registry)
        operation-a {:id "ops/a" :ext-path "/ext/a" :handler (fn [_] {:status :ok :data :a})}
        operation-b {:id "ops/b" :ext-path "/ext/b" :handler (fn [_] {:status :ok :data :b})}
        operation-c {:id "ops/c" :ext-path "/ext/c" :handler (fn [_] {:status :ok :data :c})}]
    (reg/register-operation-in! registry operation-a)
    (reg/register-operation-in! registry operation-b)
    (reg/register-operation-in! registry operation-c)
    (is (= 3 (reg/operation-count-in registry)))
    (is (= #{"ops/a" "ops/b" "ops/c"}
           (set (reg/operation-ids-in registry))))
    (is (= #{"ops/a" "ops/b" "ops/c"}
           (set (map :id (reg/all-operations-in registry)))))
    (reg/register-operation-in! registry
                                (assoc operation-b :handler (fn [_] {:status :ok :data :b2})))
    (is (= 3 (reg/operation-count-in registry)))
    (is (= #{"ops/a" "ops/b" "ops/c"}
           (set (reg/operation-ids-in registry))))
    (is (= #{"ops/a" "ops/b" "ops/c"}
           (set (map :id (reg/all-operations-in registry)))))))

(deftest shared-root-storage-is-canonical-owner-test
  ;; Proves the registry object hosts canonical operation entries only in shared root-registry state.
  (let [registry   (reg/create-registry)
        operation  {:id "github/search-issues-by-label"
                    :ext-path "/ext/github"
                    :handler (fn [_] {:status :ok :data {:issues []}})}]
    (reg/register-operation-in! registry operation)
    (let [state      @(:state registry)
          root-state (:root-state state)
          lookup     (root-registry/lookup root-state :deterministic-operations "github/search-issues-by-label")]
      (testing "adapter state no longer carries a local canonical :operations map"
        (is (= #{:root-state} (set (keys state))))
        (is (not (contains? state :operations))))
      (testing "shared root-registry storage holds the canonical registered entry"
        (is (root-registry/declared-registry? root-state :deterministic-operations))
        (is (= operation
               (-> lookup :result :value :value)))
        (is (= #{"github/search-issues-by-label"}
               (->> (root-registry/list-entries root-state :deterministic-operations)
                    :result
                    :entries
                    (map :id)
                    set)))))))
