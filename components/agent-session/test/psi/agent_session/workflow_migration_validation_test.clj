(ns psi.agent-session.workflow-migration-validation-test
  "Validate that all migrated .psi/workflows/*.md files parse and compile correctly."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-file-compiler :as compiler]
   [psi.agent-session.workflow-file-loader :as loader]
   [psi.agent-session.workflow-model :as workflow-model]))

(deftest migrated-workflow-files-test
  (testing "all .psi/workflows/ files parse, compile, and validate"
    (let [dir ".psi/workflows"
          parsed (loader/scan-directory dir)]
      ;; Should find all 12 migrated files
      (is (<= 12 (count parsed))
          (str "Expected at least 12 workflow files, found " (count parsed)))
      ;; No parse errors
      (let [parse-errors (filter :error parsed)]
        (is (empty? parse-errors)
            (str "Parse errors: " (pr-str (mapv #(select-keys % [:name :error :source-path]) parse-errors)))))
      ;; All compile successfully
      (let [{:keys [definitions errors]} (compiler/compile-workflow-files parsed)]
        (is (empty? errors)
            (str "Compile errors: " (pr-str errors)))
        ;; All produce valid canonical definitions
        (doseq [defn-map definitions]
          (is (workflow-model/valid-workflow-definition? defn-map)
              (str "Invalid definition: " (:name defn-map)
                   " — " (pr-str (workflow-model/explain-workflow-definition defn-map)))))
        ;; Step references all resolve
        (let [ref-result (compiler/validate-step-references definitions)]
          (is (true? (:valid? ref-result))
              (str "Unresolved step references: " (pr-str (:errors ref-result)))))
        ;; No name collisions
        (let [collision-result (compiler/validate-no-name-collisions definitions)]
          (is (true? (:valid? collision-result))
              (str "Name collisions: " (pr-str (:duplicates collision-result)))))))))

(deftest migrated-single-step-workflows-test
  (testing "single-step workflows carry expected metadata"
    (let [parsed (loader/scan-directory ".psi/workflows")
          {:keys [definitions]} (compiler/compile-workflow-files parsed)
          by-name (into {} (map (juxt :name identity)) definitions)]
      ;; planner
      (let [p (get by-name "planner")]
        (is (some? p))
        (is (= 1 (count (:step-order p))))
        (is (= #{"read" "bash"} (get-in p [:steps "step-1" :capability-policy :tools])))
        (is (some? (get-in p [:workflow-file-meta :system-prompt]))))
      ;; builder has 4 tools
      (let [b (get by-name "builder")]
        (is (= #{"read" "bash" "edit" "write"}
               (get-in b [:steps "step-1" :capability-policy :tools]))))
      ;; lambda-compiler has skill
      (let [lc (get by-name "lambda-compiler")]
        (is (= ["lambda-compiler"] (get-in lc [:workflow-file-meta :skills])))))))

(deftest migrated-multi-step-workflows-test
  (testing "multi-step workflows have correct step counts"
    (let [parsed (loader/scan-directory ".psi/workflows")
          {:keys [definitions]} (compiler/compile-workflow-files parsed)
          by-name (into {} (map (juxt :name identity)) definitions)]
      ;; plan-build-review: 3 steps
      (is (= 3 (count (:step-order (get by-name "plan-build-review")))))
      ;; plan-build: 2 steps
      (is (= 2 (count (:step-order (get by-name "plan-build")))))
      ;; prompt-build: 3 steps
      (is (= 3 (count (:step-order (get by-name "prompt-build")))))
      ;; lambda-build: 3 steps
      (is (= 3 (count (:step-order (get by-name "lambda-build"))))))))

(deftest migrated-session-first-authoring-examples-test
  (testing "converged workflow examples use explicit session-first authoring surfaces"
    (let [parsed (loader/scan-directory ".psi/workflows")
          {:keys [definitions]} (compiler/compile-workflow-files parsed)
          by-name (into {} (map (juxt :name identity)) definitions)
          plan-build-review (get by-name "plan-build-review")
          prompt-build (get by-name "prompt-build")
          gh-bug-triage-modular (get by-name "gh-bug-triage-modular")]
      (is (= ["step-1-planner" "step-2-builder" "step-3-reviewer"]
             (:step-order plan-build-review)))
      (is (= "step-1-planner"
             (get-in plan-build-review [:steps "step-2-builder" :input-bindings :input :path 0])))
      (is (= [:original]
             (get-in plan-build-review [:steps "step-2-builder" :input-bindings :original :path])))
      (is (= "step-2-prompt-decompiler"
             (get-in prompt-build [:steps "step-3-prompt-compiler" :input-bindings :input :path 0])))
      (is (= [{:kind :value
               :role "user"
               :binding {:source :workflow-input
                         :path [:original]}}
              {:kind :value
               :role "assistant"
               :binding {:source :step-output
                         :path ["step-1-gh-bug-discover-and-read" :outputs :text]}}
              {:kind :value
               :role "assistant"
               :binding {:source :step-output
                         :path ["step-2-gh-issue-create-worktree" :outputs :text]}}
              {:kind :session-transcript
               :step-id "step-3-gh-bug-reproduce"
               :projection {:type :tail :turns 4 :tool-output false}}]
             (get-in gh-bug-triage-modular [:steps "step-4-gh-bug-post-repro" :session-preload]))))))
