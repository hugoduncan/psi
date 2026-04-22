(ns psi.agent-session.workflow-file-compiler-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-file-compiler :as compiler]
   [psi.agent-session.workflow-model :as workflow-model]))

;;; Fixtures — parsed workflow file data (output of parse-workflow-file)

(def single-step-no-config
  {:name "planner"
   :description "Plans tasks"
   :config nil
   :body "You are a planner."})

(def single-step-with-config
  {:name "builder"
   :description "Builds code"
   :config {:tools ["read" "bash" "edit" "write"]
            :skills ["clojure-coding-standards"]
            :thinking-level :off}
   :body "You are a builder agent."})

(def multi-step-parsed
  {:name "plan-build-review"
   :description "Plan, build, and review"
   :config {:steps [{:workflow "planner" :prompt "$INPUT"}
                    {:workflow "builder" :prompt "Execute: $INPUT\nOriginal: $ORIGINAL"}
                    {:workflow "reviewer" :prompt "Review: $INPUT\nOriginal: $ORIGINAL"}]}
   :body "Coordinate a plan-build-review cycle."})

(def single-step-config-only
  {:name "minimal"
   :description "Minimal config"
   :config {:tools ["read"]}
   :body nil})

;;; Single-step compilation

(deftest compile-single-step-test
  (testing "single-step with no config produces valid 1-step definition"
    (let [{:keys [definition error]} (compiler/compile-workflow-file single-step-no-config)]
      (is (nil? error))
      (is (= "planner" (:definition-id definition)))
      (is (= "planner" (:name definition)))
      (is (= "Plans tasks" (:summary definition)))
      (is (= ["step-1"] (:step-order definition)))
      (is (= 1 (count (:steps definition))))
      (is (workflow-model/valid-workflow-definition? definition))
      ;; Executor carries the profile name
      (is (= {:type :agent :profile "planner"}
             (get-in definition [:steps "step-1" :executor])))
      ;; Prompt template is passthrough
      (is (= "$INPUT" (get-in definition [:steps "step-1" :prompt-template])))
      ;; Input bindings wire workflow-input
      (is (= {:input {:source :workflow-input :path [:input]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps "step-1" :input-bindings])))
      ;; Source metadata carries system prompt
      (is (= "You are a planner." (get-in definition [:workflow-file-meta :system-prompt])))))

  (testing "single-step with config carries tools and skills in metadata"
    (let [{:keys [definition]} (compiler/compile-workflow-file single-step-with-config)]
      (is (workflow-model/valid-workflow-definition? definition))
      (is (= #{"read" "bash" "edit" "write"}
             (get-in definition [:steps "step-1" :capability-policy :tools])))
      (is (= ["read" "bash" "edit" "write"]
             (get-in definition [:workflow-file-meta :tools])))
      (is (= ["clojure-coding-standards"]
             (get-in definition [:workflow-file-meta :skills])))
      (is (= :off (get-in definition [:workflow-file-meta :thinking-level])))))

  (testing "single-step with nil body produces no system prompt in metadata"
    (let [{:keys [definition]} (compiler/compile-workflow-file single-step-config-only)]
      (is (workflow-model/valid-workflow-definition? definition))
      (is (nil? (get-in definition [:workflow-file-meta :system-prompt]))))))

;;; Multi-step compilation

(deftest compile-multi-step-test
  (testing "multi-step produces N-step definition with correct binding wiring"
    (let [{:keys [definition error]} (compiler/compile-workflow-file multi-step-parsed)
          [plan-id build-id review-id] (:step-order definition)]
      (is (nil? error))
      (is (= "plan-build-review" (:definition-id definition)))
      (is (= "plan-build-review" (:name definition)))
      (is (= 3 (count (:step-order definition))))
      (is (workflow-model/valid-workflow-definition? definition))
      ;; Step 1: input from workflow-input
      (is (= {:input {:source :workflow-input :path [:input]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps plan-id :input-bindings])))
      ;; Step 2: input from step 1 output
      (is (= {:input {:source :step-output :path [plan-id :outputs :text]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps build-id :input-bindings])))
      ;; Step 3: input from step 2 output
      (is (= {:input {:source :step-output :path [build-id :outputs :text]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps review-id :input-bindings])))
      ;; Prompt templates preserved
      (is (= "$INPUT" (get-in definition [:steps plan-id :prompt-template])))
      (is (= "Execute: $INPUT\nOriginal: $ORIGINAL"
             (get-in definition [:steps build-id :prompt-template])))
      ;; Framing prompt in metadata
      (is (= "Coordinate a plan-build-review cycle."
             (get-in definition [:workflow-file-meta :framing-prompt])))))

  (testing "multi-step step executors reference workflow profiles"
    (let [{:keys [definition]} (compiler/compile-workflow-file multi-step-parsed)
          [plan-id build-id review-id] (:step-order definition)]
      (is (= "planner" (get-in definition [:steps plan-id :executor :profile])))
      (is (= "builder" (get-in definition [:steps build-id :executor :profile])))
      (is (= "reviewer" (get-in definition [:steps review-id :executor :profile]))))))

;;; Error handling

(deftest compile-error-test
  (testing "parser error propagated"
    (let [{:keys [error]} (compiler/compile-workflow-file {:error "bad file"})]
      (is (= "bad file" error))))

  (testing "missing name returns error"
    (let [{:keys [error]} (compiler/compile-workflow-file {:name nil :description "x" :config nil :body "x"})]
      (is (string? error)))))

;;; Batch compilation

(deftest compile-workflow-files-test
  (testing "compiles multiple files, separating successes and errors"
    (let [result (compiler/compile-workflow-files
                  [single-step-no-config
                   {:error "bad parse"}
                   multi-step-parsed])]
      (is (= 2 (count (:definitions result))))
      (is (= 1 (count (:errors result))))
      (is (= "planner" (:name (first (:definitions result)))))
      (is (= "plan-build-review" (:name (second (:definitions result))))))))

;;; Validation

(deftest validate-step-references-test
  (testing "all references resolved"
    (let [defs [(-> (compiler/compile-workflow-file single-step-no-config) :definition)
                (-> (compiler/compile-workflow-file
                     {:name "reviewer" :description "Reviews" :config nil :body "Review."})
                    :definition)
                (-> (compiler/compile-workflow-file
                     {:name "builder" :description "Builds" :config nil :body "Build."})
                    :definition)
                (-> (compiler/compile-workflow-file multi-step-parsed) :definition)]]
      (is (true? (:valid? (compiler/validate-step-references defs))))))

  (testing "missing reference detected"
    (let [defs [(-> (compiler/compile-workflow-file single-step-no-config) :definition)
                ;; Multi-step references "builder" and "reviewer" which are not defined
                (-> (compiler/compile-workflow-file multi-step-parsed) :definition)]
          result (compiler/validate-step-references defs)]
      (is (false? (:valid? result)))
      (is (= #{"builder" "reviewer"}
             (set (map :missing (:errors result))))))))

(deftest validate-no-name-collisions-test
  (testing "no collisions"
    (let [defs [(-> (compiler/compile-workflow-file single-step-no-config) :definition)
                (-> (compiler/compile-workflow-file multi-step-parsed) :definition)]]
      (is (true? (:valid? (compiler/validate-no-name-collisions defs))))))

  (testing "duplicate names detected"
    (let [defs [(-> (compiler/compile-workflow-file single-step-no-config) :definition)
                (-> (compiler/compile-workflow-file single-step-no-config) :definition)]
          result (compiler/validate-no-name-collisions defs)]
      (is (false? (:valid? result)))
      (is (= ["planner"] (:duplicates result))))))
