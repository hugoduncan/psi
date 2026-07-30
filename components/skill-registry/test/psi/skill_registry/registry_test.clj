(ns psi.skill-registry.registry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.session-state.model :as session-model]
   [psi.session-state.state :as session-state]
   [psi.skill-registry.registry :as skill-registry]
   [psi.skill-registry.root-storage :as root-storage]))

(deftest valid-skill-name?-test
  (is (true? (skill-registry/valid-skill-name? "coding")))
  (is (false? (skill-registry/valid-skill-name? nil)))
  (is (false? (skill-registry/valid-skill-name? "")))
  (is (false? (skill-registry/valid-skill-name? "   "))))

(deftest prompt-hidden?-test
  (testing "disable-model-invocation hides"
    (is (true? (skill-registry/prompt-hidden? {:disable-model-invocation true}))))
  (testing "advertise false hides"
    (is (true? (skill-registry/prompt-hidden? {:advertise false}))))
  (testing "advertise absent or true is visible"
    (is (not (skill-registry/prompt-hidden? {})))
    (is (not (skill-registry/prompt-hidden? {:advertise true})))))

(deftest visible-hidden-skills-test
  (let [all-skills [{:name "z" :advertise true}
                    {:name "h" :disable-model-invocation true}
                    {:name "u" :advertise false}
                    {:name "a"}]]
    (testing "visible-skills excludes hidden in canonical order"
      (is (= ["a" "z"] (mapv :name (skill-registry/visible-skills all-skills)))))
    (testing "hidden-skills returns only hidden in canonical order"
      (is (= ["h" "u"] (mapv :name (skill-registry/hidden-skills all-skills)))))))

(deftest register-skill-test
  (testing "adds new skills by name"
    (let [skill {:name "coding" :description "Use coding guidance"}
          result (skill-registry/register-skill [] skill)]
      (is (= [skill] (:skills result)))
      (is (= skill (:skill result)))
      (is (true? (:added? result)))
      (is (true? (:changed? result)))
      (is (= 1 (:count result)))))

  (testing "adds new skills into unsorted collections in canonical skill-name order"
    (let [z-skill {:name "z-skill" :description "Z"}
          m-skill {:name "m-skill" :description "M"}
          a-skill {:name "a-skill" :description "A"}
          result  (skill-registry/register-skill [z-skill m-skill] a-skill)]
      (is (= [a-skill m-skill z-skill] (:skills result)))
      (is (= a-skill (:skill result)))
      (is (true? (:added? result)))
      (is (true? (:changed? result)))
      (is (= 3 (:count result)))))

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

(deftest root-storage-register-and-set-test
  (testing "root storage persists canonical definitions and session-owned skill ids"
    (let [session-id "session-1"
          base-state {:agent-session {:sessions {session-id {:data (assoc (session-model/initial-session)
                                                                          :session-id session-id)}}}}
          z-skill {:name "z-skill" :description "Z"}
          a-skill {:name "a-skill" :description "A"}
          registered (root-storage/register-skill-in-root-state base-state session-id z-skill)
          state-1 (:root-state registered)
          set-result (root-storage/set-skills-in-root-state state-1 session-id [z-skill a-skill])
          state-2 (:root-state set-result)
          session-data (get-in state-2 (session-state/session-data-path session-id))]
      (is (= ["z-skill"] (:skill-ids (get-in state-1 (session-state/session-data-path session-id)))))
      (is (= ["a-skill" "z-skill"] (mapv :name (:skills set-result))))
      (is (= ["z-skill" "a-skill"] (:skill-ids session-data)))
      (is (nil? (:skills session-data)))
      (is (= 2 (count (get-in state-2 [:root-registries :skills :entries-by-id]))))))

  (testing "duplicate registration is a public no-op for membership and preserves first-written definition"
    (let [session-id "session-2"
          base-state {:agent-session {:sessions {session-id {:data (assoc (session-model/initial-session)
                                                                          :session-id session-id)}}}}
          original {:name "coding" :description "Original"}
          replacement {:name "coding" :description "Replacement"}
          first-pass (root-storage/register-skill-in-root-state base-state session-id original)
          second-pass (root-storage/register-skill-in-root-state (:root-state first-pass) session-id replacement)]
      (is (true? (:added? first-pass)))
      (is (false? (:added? second-pass)))
      (is (= original (:skill second-pass)))
      (is (= ["coding"] (:skill-ids second-pass))))))
