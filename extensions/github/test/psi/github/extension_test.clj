(ns psi.github.extension-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.extensions :as ext]
   [psi.github.extension :as sut]))

(deftest init-registers-all-three-operations-test
  (testing "init registers exactly three operations with correct ids, handlers, and descriptions"
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
      (is (= 3 (count @calls*)))
      (let [registered-ids (set (map (fn [[_ op]] (:id op)) @calls*))]
        (is (contains? registered-ids "github/find-issue"))
        (is (contains? registered-ids "github/find-pr"))
        (is (contains? registered-ids "github/edit-labels")))
      (doseq [[ext-path op] @calls*]
        (is (= "/ext/github" ext-path))
        (is (fn? (:handler op)))
        (is (not-empty (:description op)))))))