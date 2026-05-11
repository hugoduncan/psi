(ns psi.github.extension-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.extensions :as ext]
   [psi.github.extension :as sut]))

(deftest init-registers-all-four-operations-test
  (testing "init registers exactly four operations with correct ids, handlers, and descriptions"
    (let [reg    (ext/create-registry)
          _      (ext/register-extension-in! reg "/ext/github")
          calls* (atom [])
          api    (ext/create-extension-api
                  reg "/ext/github"
                  {:register-deterministic-operation-fn
                   (fn [ext-path op]
                     (swap! calls* conj [ext-path op])
                     {:id (:id op)})})]
      (sut/init api)
      (is (= 4 (count @calls*)))
      (let [registered-ids (set (map (fn [[_ op]] (:id op)) @calls*))]
        (is (contains? registered-ids "github/find-issue"))
        (is (contains? registered-ids "github/find-pr"))
        (is (contains? registered-ids "github/add-label"))
        (is (contains? registered-ids "github/remove-label")))
      (doseq [[ext-path op] @calls*]
        (is (= "/ext/github" ext-path))
        (is (fn? (:handler op)))
        (is (not-empty (:description op)))))))