(ns psi.workflow-registry.registry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.root-registry.registry :as root-registry]
   [psi.workflow-registry.registry :as workflow-registry]))

(def registered-definition
  {:definition-id "plan-build-review"
   :name "Plan Build Review"
   :summary "Plan, build, and review"
   :steps [{:name "plan"
            :type :session
            :contributions [{:type :template
                             :text "Plan {{task}}"
                             :vars {"task" {:from :workflow-input :path [:task]}}}]}
           {:name "build"
            :type :session
            :contributions [{:type :template
                             :text "Build {{plan}}"
                             :vars {"plan" {:from {:step "plan" :yield :text}}}}]}]})

(deftest normalize-id-test
  (testing "normalize-id preserves strings and normalizes keywords"
    (is (= "plan-build-review" (workflow-registry/normalize-id "plan-build-review")))
    (is (= "plan-build-review" (workflow-registry/normalize-id :plan-build-review)))
    (is (= "42" (workflow-registry/normalize-id 42))))

  (testing "normalize-id generates a UUID string for blank or missing ids"
    (is (string? (workflow-registry/normalize-id nil)))
    (is (string? (workflow-registry/normalize-id "")))
    (is (string? (workflow-registry/normalize-id "   ")))))

(deftest register-definition-test
  (testing "register-definition stores validated definitions under canonical workflow root state"
    (let [[state definition-id stored]
          (workflow-registry/register-definition {:workflows {:definitions {}}}
                                                 registered-definition)]
      (is (= "plan-build-review" definition-id))
      (is (= stored (workflow-registry/workflow-definition state definition-id)))
      (is (= [stored] (workflow-registry/list-definitions state)))
      (is (= [definition-id] (workflow-registry/definition-ids state)))
      (is (= stored (get-in state [:workflows :definitions definition-id])))))

  (testing "register-definition normalizes missing or blank ids onto stored definitions"
    (let [[state definition-id stored]
          (workflow-registry/register-definition {:workflows {:definitions {}}}
                                                 (assoc registered-definition :definition-id "   "))]
      (is (string? definition-id))
      (is (= definition-id (:definition-id stored)))
      (is (= stored (workflow-registry/workflow-definition state definition-id)))))

  (testing "register-definition rejects invalid definitions"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (workflow-registry/register-definition {:workflows {:definitions {}}}
                                                                 {:bad "data"})))]
      (is ex))))

(deftest replacement-ordering-and-miss-contracts-test
  (testing "re-registering an existing normalized id fully replaces the stored definition"
    (let [[state1 _ _]
          (workflow-registry/register-definition {:workflows {:definitions {}}}
                                                 registered-definition)
          replacement (assoc registered-definition :summary "Replacement summary")
          [state2 definition-id stored]
          (workflow-registry/register-definition state1 replacement)]
      (is (= "Replacement summary"
             (:summary (workflow-registry/workflow-definition state2 definition-id))))
      (is (= stored (workflow-registry/workflow-definition state2 definition-id)))))

  (testing "public lookup normalizes caller-provided ids and returns nil on miss"
    (let [[state definition-id _]
          (workflow-registry/register-definition {:workflows {:definitions {}}}
                                                 registered-definition)]
      (is (= definition-id (:definition-id (workflow-registry/workflow-definition state :plan-build-review))))
      (is (nil? (workflow-registry/workflow-definition state "missing")))))

  (testing "list-definitions and definition-ids are sorted by definition-id"
    (let [[state1 _ _]
          (workflow-registry/register-definition {:workflows {:definitions {}}}
                                                 (assoc registered-definition :definition-id "z-last" :name "Last"))
          [state2 _ _]
          (workflow-registry/register-definition state1
                                                 (assoc registered-definition :definition-id "a-first" :name "First"))]
      (is (= ["a-first" "z-last"]
             (workflow-registry/definition-ids state2)))
      (is (= ["a-first" "z-last"]
             (mapv :definition-id (workflow-registry/list-definitions state2))))))

  (testing "remove-definition returns removed definition and public removal normalizes caller ids"
    (let [[state1 definition-id stored]
          (workflow-registry/register-definition {:workflows {:definitions {}}}
                                                 registered-definition)
          [state2 removed]
          (workflow-registry/remove-definition state1 :plan-build-review)]
      (is (= stored removed))
      (is (= definition-id (:definition-id removed)))
      (is (nil? (workflow-registry/workflow-definition state2 definition-id)))
      (is (= [] (workflow-registry/list-definitions state2)))
      (is (= [] (workflow-registry/definition-ids state2)))
      (is (= {} (get-in state2 [:workflows :definitions])))))

  (testing "remove-definition consumes lower unregister result directly for removed value"
    (let [[state1 definition-id stored]
          (workflow-registry/register-definition {:workflows {:definitions {}}}
                                                 registered-definition)
          root-result (root-registry/lookup state1 :workflow-definitions definition-id)]
      (is (= stored (:value (:entry root-result))))
      (is (= stored
             (second (workflow-registry/remove-definition state1 definition-id))))))

  (testing "remove-definition throws on missing definitions"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (workflow-registry/remove-definition {:workflows {:definitions {}}}
                                                               "missing")))]
      (is ex))))

(deftest root-registry-adoption-and-compatibility-test
  (testing "workflow-registry declares and uses root-registry without leaking raw entry shapes"
    (let [[state definition-id stored]
          (workflow-registry/register-definition {:workflows {:definitions {}}}
                                                 registered-definition)
          lookup-result (root-registry/lookup state :workflow-definitions definition-id)
          root-entry (get-in lookup-result [:result :value])]
      (is (root-registry/declared-registry? state :workflow-definitions))
      (is (= {:id definition-id
              :extension-id :workflow-definition
              :value stored}
             root-entry))
      (is (= stored (workflow-registry/workflow-definition state definition-id)))
      (is (= stored (get-in state [:workflows :definitions definition-id])))))

  (testing "canonical workflow definitions path stays coherent after replacement and removal"
    (let [[state1 definition-id _]
          (workflow-registry/register-definition {:workflows {:definitions {}}}
                                                 registered-definition)
          [state2 _ replaced]
          (workflow-registry/register-definition state1
                                                 (assoc registered-definition :summary "Replaced from root-registry"))
          [state3 removed]
          (workflow-registry/remove-definition state2 definition-id)]
      (is (= replaced (get-in state2 [:workflows :definitions definition-id])))
      (is (= removed {:definition-id definition-id
                      :name "Plan Build Review"
                      :summary "Replaced from root-registry"
                      :steps [{:name "plan"
                               :type :session
                               :contributions [{:type :template
                                                :text "Plan {{task}}"
                                                :vars {"task" {:from :workflow-input :path [:task]}}}]}
                              {:name "build"
                               :type :session
                               :contributions [{:type :template
                                                :text "Build {{plan}}"
                                                :vars {"plan" {:from {:step "plan" :yield :text}}}}]}]}))
      (is (nil? (get-in state3 [:workflows :definitions definition-id]))))))
