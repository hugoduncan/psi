(ns psi.deterministic-operation-registry.defs-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.deterministic-operation-registry.defs :as defs]))

(deftest valid-operation-id-test
  (testing "accepts canonical namespaced kebab-case ids"
    (is (true? (defs/valid-operation-id? "github/search-issues-by-label"))))

  (testing "rejects non-canonical ids"
    (is (false? (defs/valid-operation-id? nil)))
    (is (false? (defs/valid-operation-id? :github/search)))
    (is (false? (defs/valid-operation-id? "github")))
    (is (false? (defs/valid-operation-id? "github/search_issues")))
    (is (false? (defs/valid-operation-id? "GitHub/search")))))

(deftest normalize-operation-def-test
  (testing "preserves canonical closed shape and trims optional strings"
    (let [handler (fn [_] {:status :ok :data {}})
          operation (defs/normalize-operation-def
                     {:id "github/search-issues-by-label"
                      :handler handler
                      :description "  Search issues  "
                      :summary "  Search summary  "
                      :ext-path "/ext/github"
                      :source :extension})]
      (is (= {:id "github/search-issues-by-label"
              :handler handler
              :description "Search issues"
              :summary "Search summary"
              :ext-path "/ext/github"
              :source :extension}
             operation))))

  (testing "rejects invalid definitions"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid deterministic operation definition"
         (defs/normalize-operation-def {:id "github/search"})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid deterministic operation definition"
         (defs/normalize-operation-def {:id "github/search"
                                        :handler (fn [_] {:status :ok :data {}})
                                        :extra true}))))

  (testing "accepts nil optional description and summary"
    (let [handler (fn [_] {:status :ok :data {}})]
      (is (= {:id "github/search"
              :handler handler
              :description nil
              :summary nil}
             (defs/normalize-operation-def {:id "github/search"
                                            :handler handler
                                            :description nil
                                            :summary nil}))))))

(deftest operation-result-validation-test
  (testing "accepts canonical success results"
    (is (true? (defs/valid-operation-result?
                {:status :ok
                 :data {:issues []}
                 :summary "ok"
                 :details {:count 0}}))))

  (testing "accepts canonical error results"
    (is (true? (defs/valid-operation-result?
                {:status :error
                 :reason :not-found
                 :message "repo missing"
                 :details {:repo "psi"}}))))

  (testing "rejects malformed results"
    (is (false? (defs/valid-operation-result?
                 {:status :ok
                  :summary "missing data"})))
    (is (some? (defs/explain-operation-result
                {:status :ok
                 :summary "missing data"})))))
