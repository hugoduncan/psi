(ns psi.agent-session.workflow-file-compiler-target-authoring-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-file-compiler :as compiler]
   [psi.agent-session.workflow-target-ir-compiler :as target-compiler]))

(deftest compile-target-authored-workflow-file-test
  (testing "workflow file compiler preserves target-authored examples as target definitions"
    (let [parsed {:name "plan-build"
                  :description "Plan and build without review"
                  :config {:steps [{:name "plan"
                                    :type :session
                                    :contributions [{:type :template
                                                     :text "{{input}}"
                                                     :vars {"input" {:from :workflow-input
                                                                     :path [:input]}}}]}
                                   {:name "build"
                                    :type :session
                                    :contributions [{:type :source
                                                     :from :workflow-original}
                                                    {:type :template
                                                     :text "Build {{plan}} / {{original}}"
                                                     :vars {"plan" {:from {:step "plan" :yield :text}}
                                                            "original" {:from :workflow-original
                                                                        :path [:original]}}}]}]}
                  :body "Frame it."}
          {:keys [definition error]} (compiler/compile-workflow-file parsed)]
      (is (nil? error))
      (is (= "plan-build" (:definition-id definition)))
      (is (= "plan-build" (:name definition)))
      (is (= "Plan and build without review" (:summary definition)))
      (is (= "Frame it." (get-in definition [:workflow-file-meta :framing-prompt])))
      (is (target-compiler/target-authored-workflow-definition? definition))
      (is (= [:session :session]
             (mapv :type (:steps definition))))))

  (testing "legacy current-authored multi-step files still compile through the existing path"
    (let [parsed {:name "plan-build-review"
                  :description "Plan, build, and review"
                  :config {:steps [{:name "plan" :workflow "planner" :prompt "$INPUT"}
                                   {:name "build" :workflow "builder" :prompt "Build: $INPUT"}]}
                  :body "Coordinate the cycle."}
          {:keys [definition error]} (compiler/compile-workflow-file parsed)]
      (is (nil? error))
      (is (= ["step-1-planner" "step-2-builder"]
             (:step-order definition)))
      (is (= "builder"
             (get-in definition [:steps "step-2-builder" :executor :profile]))))))
