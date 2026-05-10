(ns psi.workflow-registry.definition-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.workflow-registry.definition :as definition]))

(deftest target-authored-workflow-definition?-test
  (is (true? (definition/target-authored-workflow-definition?
              {:steps [{:name "plan" :type :session}]})))
  (is (false? (definition/target-authored-workflow-definition? nil)))
  (is (false? (definition/target-authored-workflow-definition? [])))
  (is (false? (definition/target-authored-workflow-definition? {:steps {}})))
  (is (false? (definition/target-authored-workflow-definition? {:steps [1 2 3]}))))
