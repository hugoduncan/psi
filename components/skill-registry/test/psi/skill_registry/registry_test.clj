(ns psi.skill-registry.registry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.skill-registry.registry :as skill-registry]))

(deftest valid-skill-name?-test
  (is (true? (skill-registry/valid-skill-name? "coding")))
  (is (false? (skill-registry/valid-skill-name? nil)))
  (is (false? (skill-registry/valid-skill-name? "")))
  (is (false? (skill-registry/valid-skill-name? "   "))))

(deftest register-skill-test
  (testing "adds new skills by name"
    (let [skill {:name "coding" :description "Use coding guidance"}
          result (skill-registry/register-skill [] skill)]
      (is (= [skill] (:skills result)))
      (is (= skill (:skill result)))
      (is (true? (:added? result)))
      (is (true? (:changed? result)))
      (is (= 1 (:count result)))))

  (testing "ignores duplicate registrations and returns canonical skill-name order"
    (let [existing  {:name "testing" :description "Original"}
          duplicate {:name "testing" :description "Replacement attempt"}
          earlier   {:name "coding" :description "Coding guidance"}
          later     {:name "analysis" :description "Analysis guidance"}
          first-result (skill-registry/register-skill [existing earlier] duplicate)
          second-result (skill-registry/register-skill (:skills first-result) later)]
      (is (= [earlier existing] (:skills first-result)))
      (is (= existing (:skill first-result)))
      (is (false? (:added? first-result)))
      (is (false? (:changed? first-result)))
      (is (= 2 (:count first-result)))
      (is (= [later earlier existing] (:skills second-result)))
      (is (= ["analysis" "coding" "testing"] (skill-registry/skill-names (:skills second-result))))
      (is (= 3 (skill-registry/skill-count (:skills second-result))))))

  (testing "rejects missing or blank skill names"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid skill name"
         (skill-registry/register-skill [] {:description "No name"})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid skill name"
         (skill-registry/register-skill [] {:name "   " :description "Blank name"})))))

(deftest query-helpers-test
  (let [skills [{:name "testing" :description "t"}
                {:name "Coding" :description "capital"}
                {:name "coding" :description "d"}]]
    (is (= [{:name "Coding" :description "capital"}
            {:name "coding" :description "d"}
            {:name "testing" :description "t"}]
           (skill-registry/all-skills skills)))
    (is (= {:name "coding" :description "d"}
           (skill-registry/find-skill skills "coding")))
    (is (nil? (skill-registry/find-skill skills "missing")))
    (is (= ["Coding" "coding" "testing"] (skill-registry/skill-names skills)))
    (is (= 3 (skill-registry/skill-count skills)))))
