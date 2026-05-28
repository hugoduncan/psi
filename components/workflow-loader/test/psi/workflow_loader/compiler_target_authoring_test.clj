(ns psi.workflow-loader.compiler-target-authoring-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.compiler :as compiler]
   [psi.workflow-registry.definition :as workflow-definition]))

(deftest compile-target-authored-workflow-file-test
  (testing "workflow file compiler preserves target-authored examples as target definitions"
    (let [parsed {:workflow-kind :single-step-markdown
                  :name "plan-build"
                  :description "Plan and build without review"
                  :session-config {}
                  :body "Frame it."
                  :vars nil}
          {:keys [definition error]} (compiler/compile-workflow-file parsed)]
      (is (nil? error))
      (is (= "plan-build" (:definition-id definition)))
      (is (= "plan-build" (:name definition)))
      (is (= "Plan and build without review" (:summary definition)))
      (is (nil? (get-in definition [:workflow-file-meta :framing-prompt])))
      (is (workflow-definition/target-authored-workflow-definition? definition))
      (is (= [:session]
             (mapv :type (:steps definition))))))

  (testing "non-target workflow files now fail compilation explicitly"
    (let [parsed {:workflow-kind :multi-step-edn
                  :config {:name "legacy-plan-build-review"
                           :description "Plan, build, and review"
                           :definition-id "legacy-plan-build-review"
                           :steps [{:name "plan" :workflow "planner" :prompt "$INPUT"}
                                   {:name "build" :workflow "builder" :prompt "Build: $INPUT"}]}
                  :source-path "/tmp/legacy-plan-build-review.edn"}
          {:keys [definition error]} (compiler/compile-workflow-file parsed)]
      (is (nil? definition))
      (is (= "Workflow EDN files must define target-authored `{:steps [...]}` config"
             error)))))
