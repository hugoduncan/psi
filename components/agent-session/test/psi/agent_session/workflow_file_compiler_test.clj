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
   :config {:steps [{:name "plan" :workflow "planner" :prompt "$INPUT"}
                    {:name "build" :workflow "builder" :prompt "Execute: $INPUT\nOriginal: $ORIGINAL"}
                    {:name "review" :workflow "reviewer" :prompt "Review: $INPUT\nOriginal: $ORIGINAL"}]}
   :body "Coordinate a plan-build-review cycle."})

(def single-step-config-only
  {:name "minimal"
   :description "Minimal config"
   :config {:tools ["read"]}
   :body nil})

(def multi-step-with-names-and-session-sources
  {:name "gh-bug-triage-modular"
   :description "Bug triage with explicit source selection"
   :config {:steps [{:name "discover"
                     :workflow "gh-bug-discover-and-read"
                     :session {:input {:from :workflow-input}}
                     :prompt "$INPUT"}
                    {:name "worktree"
                     :workflow "gh-issue-create-worktree"
                     :session {:input {:from {:step "discover" :kind :accepted-result}}
                               :reference {:from :workflow-original}}
                     :prompt "$INPUT"}
                    {:name "reproduce"
                     :workflow "gh-bug-reproduce"
                     :session {:input {:from {:step "worktree" :kind :accepted-result}}
                               :reference {:from :workflow-original}}
                     :prompt "$INPUT"}
                    {:name "request-more-info"
                     :workflow "gh-bug-request-more-info"
                     :session {:input {:from {:step "reproduce" :kind :accepted-result}}
                               :reference {:from :workflow-original}}
                     :prompt "$INPUT"}
                    {:name "fix"
                     :workflow "gh-bug-fix-and-pr"
                     :session {:input {:from {:step "reproduce" :kind :accepted-result}}
                               :reference {:from :workflow-original}}
                     :prompt "$INPUT"}]}
   :body "Coordinate bug triage."})

(def multi-step-with-projections
  {:name "projection-chain"
   :description "Chain with explicit projections"
   :config {:steps [{:name "discover"
                     :workflow "planner"
                     :session {:input {:from :workflow-input
                                       :projection {:path [:task]}}
                               :reference {:from :workflow-original
                                           :projection :full}}
                     :prompt "$INPUT"}
                    {:name "reproduce"
                     :workflow "builder"
                     :session {:input {:from {:step "discover" :kind :accepted-result}
                                       :projection {:path [:outputs :text]}}
                               :reference {:from :workflow-input
                                           :projection {:path [:ticket :title]}}}
                     :prompt "$INPUT"}
                    {:name "request-more-info"
                     :workflow "reviewer"
                     :session {:input {:from {:step "reproduce" :kind :accepted-result}
                                       :projection :full}
                               :reference {:from :workflow-original
                                           :projection :text}}
                     :prompt "$INPUT"}]}
   :body "Projection chain."})

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
      (is (= {:type :agent :profile "planner"}
             (get-in definition [:steps "step-1" :executor])))
      (is (= "$INPUT" (get-in definition [:steps "step-1" :prompt-template])))
      (is (= {:input {:source :workflow-input :path [:input]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps "step-1" :input-bindings])))
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
      (is (= {:input {:source :workflow-input :path [:input]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps plan-id :input-bindings])))
      (is (= {:input {:source :step-output :path [plan-id :outputs :text]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps build-id :input-bindings])))
      (is (= {:input {:source :step-output :path [build-id :outputs :text]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps review-id :input-bindings])))
      (is (= "$INPUT" (get-in definition [:steps plan-id :prompt-template])))
      (is (= "Execute: $INPUT\nOriginal: $ORIGINAL"
             (get-in definition [:steps build-id :prompt-template])))
      (is (= "Coordinate a plan-build-review cycle."
             (get-in definition [:workflow-file-meta :framing-prompt])))))

  (testing "multi-step step executors reference workflow profiles"
    (let [{:keys [definition]} (compiler/compile-workflow-file multi-step-parsed)
          [plan-id build-id review-id] (:step-order definition)]
      (is (= "planner" (get-in definition [:steps plan-id :executor :profile])))
      (is (= "builder" (get-in definition [:steps build-id :executor :profile])))
      (is (= "reviewer" (get-in definition [:steps review-id :executor :profile]))))))

(deftest compile-multi-step-session-source-selection-test
  (testing "explicit named prior-step source selection compiles to canonical input bindings"
    (let [{:keys [definition error]} (compiler/compile-workflow-file multi-step-with-names-and-session-sources)
          [discover-id worktree-id reproduce-id request-more-info-id fix-id] (:step-order definition)]
      (is (nil? error))
      (is (workflow-model/valid-workflow-definition? definition))
      (is (= {:input {:source :workflow-input :path [:input]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps discover-id :input-bindings])))
      (is (= {:input {:source :step-output :path [discover-id :outputs :text]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps worktree-id :input-bindings])))
      (is (= {:input {:source :step-output :path [worktree-id :outputs :text]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps reproduce-id :input-bindings])))
      (is (= {:input {:source :step-output :path [reproduce-id :outputs :text]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps request-more-info-id :input-bindings])))
      (is (= {:input {:source :step-output :path [reproduce-id :outputs :text]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps fix-id :input-bindings])))))

  (testing "partial session override preserves current defaults"
    (let [{:keys [definition error]}
          (compiler/compile-workflow-file
           {:name "partial-override"
            :description "Partial override"
            :config {:steps [{:name "plan"
                              :workflow "planner"
                              :prompt "$INPUT"}
                             {:name "build"
                              :workflow "builder"
                              :session {:reference {:from :workflow-original}}
                              :prompt "$INPUT"}]}
            :body "Frame."})
          [plan-id build-id] (:step-order definition)]
      (is (nil? error))
      (is (= {:input {:source :step-output :path [plan-id :outputs :text]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps build-id :input-bindings])))))

  (testing "empty session map is equivalent to absent session block"
    (let [{:keys [definition error]}
          (compiler/compile-workflow-file
           {:name "empty-session"
            :description "Empty session"
            :config {:steps [{:name "plan"
                              :workflow "planner"
                              :prompt "$INPUT"}
                             {:name "build"
                              :workflow "builder"
                              :session {}
                              :prompt "$INPUT"}]}
            :body "Frame."})
          [plan-id build-id] (:step-order definition)]
      (is (nil? error))
      (is (= {:input {:source :step-output :path [plan-id :outputs :text]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps build-id :input-bindings]))))))

(deftest compile-multi-step-projection-test
  (testing "minimal projection forms compile on top of task-060 source selection"
    (let [{:keys [definition error]} (compiler/compile-workflow-file multi-step-with-projections)
          [discover-id reproduce-id request-more-info-id] (:step-order definition)]
      (is (nil? error))
      (is (workflow-model/valid-workflow-definition? definition))
      (is (= {:input {:source :workflow-input :path [:task]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps discover-id :input-bindings])))
      (is (= {:input {:source :step-output :path [discover-id :outputs :text]}
              :original {:source :workflow-input :path [:ticket :title]}}
             (get-in definition [:steps reproduce-id :input-bindings])))
      (is (= {:input {:source :step-output :path [reproduce-id]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps request-more-info-id :input-bindings])))))

  (testing "named prior-step non-adjacent source use supports structured field extraction"
    (let [{:keys [definition error]}
          (compiler/compile-workflow-file
           {:name "non-adjacent-projection"
            :description "Non-adjacent projection"
            :config {:steps [{:name "discover"
                              :workflow "planner"
                              :prompt "$INPUT"}
                             {:name "review"
                              :workflow "reviewer"
                              :session {:input {:from {:step "discover" :kind :accepted-result}
                                                :projection {:path [:diagnostics :summary]}}}
                              :prompt "$INPUT"}
                             {:name "request-more-info"
                              :workflow "builder"
                              :session {:input {:from {:step "discover" :kind :accepted-result}
                                                :projection {:path [:outputs :text]}}}
                              :prompt "$INPUT"}]}
            :body "Frame."})
          [discover-id review-id request-more-info-id] (:step-order definition)]
      (is (nil? error))
      (is (= {:source :step-output :path [discover-id :diagnostics :summary]}
             (get-in definition [:steps review-id :input-bindings :input])))
      (is (= {:source :step-output :path [discover-id :outputs :text]}
             (get-in definition [:steps request-more-info-id :input-bindings :input]))))))

(deftest compile-multi-step-session-overrides-test
  (testing "per-step session-shaping overrides compile under :session"
    (let [{:keys [definition error]}
          (compiler/compile-workflow-file
           {:name "override-chain"
            :description "Per-step overrides"
            :config {:steps [{:name "plan"
                              :workflow "planner"
                              :prompt "$INPUT"}
                             {:name "review"
                              :workflow "reviewer"
                              :session {:input {:from {:step "plan" :kind :accepted-result}}
                                        :reference {:from :workflow-original}
                                        :system-prompt "Focus only on correctness, edge cases, and missing tests."
                                        :tools []
                                        :skills ["testing-best-practices"]
                                        :model "gpt-5"
                                        :thinking-level :high}
                              :prompt "$INPUT"}]}
            :body "Frame."})
          [_plan-id review-id] (:step-order definition)]
      (is (nil? error))
      (is (workflow-model/valid-workflow-definition? definition))
      (is (= {:system-prompt "Focus only on correctness, edge cases, and missing tests."
              :tools []
              :skills ["testing-best-practices"]
              :model "gpt-5"
              :thinking-level :high}
             (get-in definition [:steps review-id :session-overrides])))))

  (testing "empty session map remains equivalent to absent session block"
    (let [{:keys [definition error]}
          (compiler/compile-workflow-file
           {:name "empty-session-overrides"
            :description "Empty session overrides"
            :config {:steps [{:name "plan"
                              :workflow "planner"
                              :prompt "$INPUT"}
                             {:name "review"
                              :workflow "reviewer"
                              :session {}
                              :prompt "$INPUT"}]}
            :body "Frame."})
          [_plan-id review-id] (:step-order definition)]
      (is (nil? error))
      (is (nil? (get-in definition [:steps review-id :session-overrides]))))))

;;; Error handling

(deftest compile-error-test
  (testing "parser error propagated"
    (let [{:keys [error]} (compiler/compile-workflow-file {:error "bad file"})]
      (is (= "bad file" error))))

  (testing "missing name returns error"
    (let [{:keys [error]} (compiler/compile-workflow-file {:name nil :description "x" :config nil :body "x"})]
      (is (string? error)))))

(deftest compile-session-source-validation-test
  (testing "unknown step name fails clearly"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:name "bad-source"
            :description "Bad source"
            :config {:steps [{:name "plan" :workflow "planner" :prompt "$INPUT"}
                             {:name "build"
                              :workflow "builder"
                              :session {:input {:from {:step "missing" :kind :accepted-result}}}
                              :prompt "$INPUT"}]}
            :body "Frame."})]
      (is (re-find #"Unknown step name" error))))

  (testing "forward step reference fails clearly"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:name "forward-source"
            :description "Forward source"
            :config {:steps [{:name "plan"
                              :workflow "planner"
                              :session {:input {:from {:step "review" :kind :accepted-result}}}
                              :prompt "$INPUT"}
                             {:name "review" :workflow "reviewer" :prompt "$INPUT"}]}
            :body "Frame."})]
      (is (re-find #"Forward step reference" error))))

  (testing "present but empty input map is malformed"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:name "empty-input"
            :description "Empty input"
            :config {:steps [{:name "plan" :workflow "planner" :session {:input {}} :prompt "$INPUT"}]}
            :body "Frame."})]
      (is (re-find #"expected non-empty map with `:from`" error))))

  (testing "present but empty reference map is malformed"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:name "empty-reference"
            :description "Empty reference"
            :config {:steps [{:name "plan" :workflow "planner" :session {:reference {}} :prompt "$INPUT"}]}
            :body "Frame."})]
      (is (re-find #"expected non-empty map with `:from`" error))))

  (testing "projection validation errors surface clearly through compiler"
    (doseq [{:keys [label config-step expected-re]}
            [{:label "unsupported projection operator"
              :config-step {:name "plan"
                            :workflow "planner"
                            :session {:input {:from :workflow-input
                                              :projection :tail}}
                            :prompt "$INPUT"}
              :expected-re #"unsupported `:projection`"}
             {:label "malformed path projection"
              :config-step {:name "plan"
                            :workflow "planner"
                            :session {:input {:from :workflow-input
                                              :projection {:path :not-a-vector}}}
                            :prompt "$INPUT"}
              :expected-re #"expected vector path"}
             {:label "projection with unexpected keys"
              :config-step {:name "plan"
                            :workflow "planner"
                            :session {:input {:from :workflow-input
                                              :projection {:path [:task]
                                                           :extra true}}}
                            :prompt "$INPUT"}
              :expected-re #"unexpected keys"}]]
      (let [{:keys [error]}
            (compiler/compile-workflow-file
             {:name (str "projection-validation-" (name (gensym "case-")))
              :description label
              :config {:steps [config-step]}
              :body "Frame."})]
        (is (re-find expected-re error) label))))

  (testing "session preload compiles for task 063 surfaces"
    (let [{:keys [definition error]}
          (compiler/compile-workflow-file
           {:name "session-preload"
            :description "Session preload"
            :config {:steps [{:name "plan"
                              :workflow "planner"
                              :prompt "$INPUT"}
                             {:name "review"
                              :workflow "reviewer"
                              :session {:input {:from {:step "plan" :kind :accepted-result}}
                                        :preload [{:from :workflow-original}
                                                  {:from {:step "plan" :kind :accepted-result}}
                                                  {:from {:step "plan" :kind :session-transcript}
                                                   :projection {:type :tail :turns 2 :tool-output false}}]}
                              :prompt "$INPUT"}]}
            :body "Frame."})
          [_plan-id review-id] (:step-order definition)]
      (is (nil? error))
      (is (= [{:kind :value
               :role "user"
               :binding {:source :workflow-input :path [:original]}}
              {:kind :value
               :role "assistant"
               :binding {:source :step-output :path [_plan-id :outputs :text]}}
              {:kind :session-transcript
               :step-id _plan-id
               :projection {:type :tail :turns 2 :tool-output false}}]
             (get-in definition [:steps review-id :session-preload])))))

  (testing "malformed preload validation errors surface clearly through compiler"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:name "bad-preload"
            :description "Bad preload"
            :config {:steps [{:name "plan"
                              :workflow "planner"
                              :prompt "$INPUT"}
                             {:name "review"
                              :workflow "reviewer"
                              :session {:preload [{:from {:step "plan" :kind :session-transcript}
                                                   :projection {:type :head :turns 1}}]}
                              :prompt "$INPUT"}]}
            :body "Frame."})]
      (is (re-find #"unsupported transcript/message projection" error))))

  (testing "value preload rejects :full projection clearly through compiler"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:name "bad-value-preload"
            :description "Bad value preload"
            :config {:steps [{:name "plan"
                              :workflow "planner"
                              :prompt "$INPUT"}
                             {:name "review"
                              :workflow "reviewer"
                              :session {:preload [{:from {:step "plan" :kind :accepted-result}
                                                   :projection :full}]}
                              :prompt "$INPUT"}]}
            :body "Frame."})]
      (is (re-find #"value preload supports only `:projection :text`" error))))

  (testing "one representative malformed override still surfaces clearly through compiler"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:name "bad-thinking-override"
            :description "Bad thinking override"
            :config {:steps [{:name "plan"
                              :workflow "planner"
                              :session {:thinking-level :ultra}
                              :prompt "$INPUT"}]}
            :body "Frame."})]
      (is (re-find #"expected one of :off, :minimal, :low, :medium, :high, :xhigh in `:session thinking-level`" error))))

  (testing "duplicate author-facing step names fail clearly"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:name "duplicate-step-names"
            :description "Duplicate step names"
            :config {:steps [{:name "build" :workflow "planner" :prompt "$INPUT"}
                             {:name "build" :workflow "reviewer" :prompt "$INPUT"}]}
            :body "Frame."})]
      (is (re-find #"Duplicate workflow step names" error))))

  (testing "missing multi-step step names fail clearly"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:name "repeat-workflow"
            :description "Repeated delegated workflow"
            :config {:steps [{:workflow "lambda-compiler" :prompt "$INPUT"}
                             {:workflow "lambda-compiler" :prompt "$INPUT"}]}
            :body "Frame."})]
      (is (re-find #"Multi-step workflow steps must have unique string `:name`" error)))))

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

;;; Judge and routing compilation

(def multi-step-with-judge
  {:name "plan-build-review"
   :description "Plan, build, and review with judge"
   :config {:steps [{:name "plan" :workflow "planner" :prompt "$INPUT"}
                    {:name "build" :workflow "builder" :prompt "Execute: $INPUT\nOriginal: $ORIGINAL"}
                    {:name "review" :workflow "reviewer" :prompt "Review: $INPUT\nOriginal: $ORIGINAL"
                     :judge {:prompt "APPROVED or REVISE?"
                             :system-prompt "You are a routing judge."
                             :projection {:type :tail :turns 1}}
                     :on {"APPROVED" {:goto :next}
                          "REVISE"   {:goto "build" :max-iterations 3}}}]}
   :body "Coordinate a plan-build-review cycle."})

(deftest compile-multi-step-with-judge-test
  (testing "multi-step with judge threads judge and resolves goto targets"
    (let [{:keys [definition error]} (compiler/compile-workflow-file multi-step-with-judge)
          [_plan-id build-id review-id] (:step-order definition)]
      (is (nil? error))
      (is (workflow-model/valid-workflow-definition? definition))
      (is (= {:prompt "APPROVED or REVISE?"
              :system-prompt "You are a routing judge."
              :projection {:type :tail :turns 1}}
             (get-in definition [:steps review-id :judge])))
      (let [on-table (get-in definition [:steps review-id :on])]
        (is (= {:goto :next} (get on-table "APPROVED")))
        (is (= build-id (get-in on-table ["REVISE" :goto])))
        (is (= 3 (get-in on-table ["REVISE" :max-iterations]))))
      (is (nil? (get-in definition [:steps _plan-id :judge])))
      (is (nil? (get-in definition [:steps _plan-id :on]))))))

(deftest compile-multi-step-without-judge-unchanged-test
  (testing "multi-step without judge compiles identically to before"
    (let [{:keys [definition]} (compiler/compile-workflow-file multi-step-parsed)]
      (is (workflow-model/valid-workflow-definition? definition))
      (doseq [[_step-id step-def] (:steps definition)]
        (is (nil? (:judge step-def)))
        (is (nil? (:on step-def)))))))

;;; Judge routing validation

(deftest validate-judge-routing-test
  (testing "valid judge+on passes"
    (let [defs [(-> (compiler/compile-workflow-file multi-step-with-judge) :definition)]]
      (is (true? (:valid? (compiler/validate-judge-routing defs))))))

  (testing ":on without :judge is an error"
    (let [bad-def {:definition-id "bad"
                   :name "bad"
                   :step-order ["s1"]
                   :steps {"s1" {:executor {:type :agent :profile "x"}
                                 :result-schema :any
                                 :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}
                                 :on {"OK" {:goto :next}}}}}
          result (compiler/validate-judge-routing [bad-def])]
      (is (false? (:valid? result)))
      (is (= :on-without-judge (:error (first (:errors result)))))))

  (testing "unknown goto target is an error"
    (let [bad-def {:definition-id "bad"
                   :name "bad"
                   :step-order ["s1" "s2"]
                   :steps {"s1" {:executor {:type :agent :profile "x"}
                                 :result-schema :any
                                 :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}
                                 :judge {:prompt "yes or no?"}
                                 :on {"YES" {:goto "nonexistent"}}}
                           "s2" {:executor {:type :agent :profile "y"}
                                 :result-schema :any
                                 :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}}
          result (compiler/validate-judge-routing [bad-def])]
      (is (false? (:valid? result)))
      (is (= :unknown-goto-target (:error (first (:errors result)))))))

  (testing "keyword goto targets (:next, :previous, :done) are always valid"
    (let [ok-def {:definition-id "ok"
                  :name "ok"
                  :step-order ["s1" "s2"]
                  :steps {"s1" {:executor {:type :agent :profile "x"}
                                :result-schema :any
                                :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}
                                :judge {:prompt "choose"}
                                :on {"A" {:goto :next}
                                     "B" {:goto :previous}
                                     "C" {:goto :done}}}
                          "s2" {:executor {:type :agent :profile "y"}
                                :result-schema :any
                                :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}}]
      (is (true? (:valid? (compiler/validate-judge-routing [ok-def])))))))
