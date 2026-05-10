(ns psi.github.extension-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.extensions :as ext]
   [psi.github.extension :as sut]))

(deftest init-registers-github-find-issue-operation-test
  (testing "init delegates to :register-operation with correct id and handler"
    (let [reg   (ext/create-registry)
          _     (ext/register-extension-in! reg "/ext/github")
          calls (atom [])
          api   (ext/create-extension-api
                 reg "/ext/github"
                 {:register-deterministic-operation-fn
                  (fn [ext-path op]
                    (swap! calls conj [ext-path op])
                    {:id (:id op)})})]
      (sut/init api)
      (is (= 1 (count @calls)))
      (let [[ext-path op] (first @calls)]
        (is (= "/ext/github" ext-path))
        (is (= "github/find-issue" (:id op)))
        (is (fn? (:handler op)))
        (is (string? (:description op)))))))
