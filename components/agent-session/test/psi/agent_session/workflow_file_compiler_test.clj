(ns psi.agent-session.workflow-file-compiler-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-file-compiler :as compiler]
   [psi.workflow-registry.definition :as workflow-definition]))

(def target-session-parsed
  {:name "planner"
   :description "Plans tasks"
   :config {:steps [{:name "plan"
                     :type :session
                     :tools ["read" "bash"]
                     :contributions [{:type :template
                                      :text "Plan {{task}}"
                                      :vars {"task" {:from :workflow-input :path [:task]}}}]}]}
   :body "You are a planner."})

(def target-delegate-chain-parsed
  {:name "plan-build-review"
   :description "Plan, build, and review"
   :config {:steps [{:name "plan"
                     :type :delegate
                     :target "planner"
                     :prompt-string {:type :template
                                     :text "{{input}}"
                                     :vars {"input" {:from :workflow-input :path [:input]}}}}
                    {:name "build"
                     :type :delegate
                     :target "builder"
                     :prompt-string {:type :template
                                     :text "Build {{plan}} / {{original}}"
                                     :vars {"plan" {:from {:step "plan" :yield :text}}
                                            "original" {:from :workflow-original :path [:original]}}}
                     :context [{:type :source :from :workflow-original}]}
                    {:name "review"
                     :type :session
                     :contributions [{:type :source
                                      :from {:step "build" :output :transcript}
                                      :projection {:type :tail :turns 2 :tool-output false}}
                                     {:type :template
                                      :text "Review {{build}}"
                                      :vars {"build" {:from {:step "build" :yield :text}}}}]}]}
   :body "Coordinate a plan-build-review cycle."})

(def legacy-current-authored-parsed
  {:name "legacy-plan-build-review"
   :description "Plan, build, and review"
   :config {:steps [{:name "plan" :workflow "planner" :prompt "$INPUT"}
                    {:name "build" :workflow "builder" :prompt "Build: $INPUT"}]}
   :body "Coordinate the cycle."})

(deftest compile-target-authored-workflow-file-test
  (testing "target-authored single-step workflow files compile and preserve metadata"
    (let [{:keys [definition error]} (compiler/compile-workflow-file target-session-parsed)]
      (is (nil? error))
      (is (= "planner" (:definition-id definition)))
      (is (= "planner" (:name definition)))
      (is (= "Plans tasks" (:summary definition)))
      (is (= "You are a planner." (get-in definition [:workflow-file-meta :framing-prompt])))
      (is (workflow-definition/target-authored-workflow-definition? definition))
      (is (= [:session] (mapv :type (:steps definition))))))

  (testing "target-authored multi-step workflow files compile unchanged as authored definitions"
    (let [{:keys [definition error]} (compiler/compile-workflow-file target-delegate-chain-parsed)]
      (is (nil? error))
      (is (= "plan-build-review" (:definition-id definition)))
      (is (= [:delegate :delegate :session] (mapv :type (:steps definition))))
      (is (= "planner" (get-in definition [:steps 0 :target])))
      (is (= :text (get-in definition [:steps 1 :prompt-string :vars "plan" :from :yield])))
      (is (= :tail (get-in definition [:steps 2 :contributions 0 :projection :type])))
      (is (= "Coordinate a plan-build-review cycle."
             (get-in definition [:workflow-file-meta :framing-prompt]))))))

(deftest compile-target-authored-errors-test
  (testing "parser error is propagated"
    (let [{:keys [error]} (compiler/compile-workflow-file {:error "bad file"})]
      (is (= "bad file" error))))

  (testing "missing workflow name fails clearly"
    (let [{:keys [error]} (compiler/compile-workflow-file {:name nil :config {:steps []}})]
      (is (= "Cannot compile: missing workflow name" error))))

  (testing "legacy current-authored workflow files are rejected"
    (let [{:keys [definition error]} (compiler/compile-workflow-file legacy-current-authored-parsed)]
      (is (nil? definition))
      (is (= "Workflow files must define target-authored `{:steps [...]}` config" error))))

  (testing "target-authored compilation requires steps with explicit type"
    (let [{:keys [definition error]}
          (compiler/compile-workflow-file
           {:name "bad-target"
            :config {:steps [{:name "plan"
                              :contributions []}]}})]
      (is (nil? definition))
      (is (= "Workflow files must define target-authored `{:steps [...]}` config" error)))))

(deftest compile-workflow-files-test
  (testing "batch compilation separates target-authored successes from errors"
    (let [result (compiler/compile-workflow-files
                  [target-session-parsed
                   {:error "bad parse"}
                   legacy-current-authored-parsed
                   target-delegate-chain-parsed])]
      (is (= 2 (count (:definitions result))))
      (is (= 2 (count (:errors result))))
      (is (= ["planner" "plan-build-review"]
             (mapv :name (:definitions result)))))))

(deftest validate-step-references-test
  (testing "target-authored file loading no longer performs separate loader-time step-reference validation"
    (is (= {:valid? true}
           (compiler/validate-step-references [])))))

(deftest validate-no-name-collisions-test
  (testing "no collisions"
    (let [defs [(-> (compiler/compile-workflow-file target-session-parsed) :definition)
                (-> (compiler/compile-workflow-file target-delegate-chain-parsed) :definition)]]
      (is (true? (:valid? (compiler/validate-no-name-collisions defs))))))

  (testing "duplicate names detected from compiled target-authored definitions"
    (let [defs [(-> (compiler/compile-workflow-file target-session-parsed) :definition)
                (-> (compiler/compile-workflow-file target-session-parsed) :definition)]
          result (compiler/validate-no-name-collisions defs)]
      (is (false? (:valid? result)))
      (is (= ["planner"] (:duplicates result))))))

(deftest validate-judge-routing-test
  (testing "target-authored file loading delegates routing validation to the target compiler + IR path"
    (is (= {:valid? true}
           (compiler/validate-judge-routing [])))))
