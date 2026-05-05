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
        ;; All produce valid canonical definitions or valid target-authored definitions
        (doseq [defn-map definitions]
          (is (or (workflow-model/valid-workflow-definition? defn-map)
                  (vector? (:steps defn-map)))
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
      ;; prompt-build: 3 target-authored delegate steps
      (is (= 3 (count (:steps (get by-name "prompt-build")))))
      (is (= [:delegate :delegate :delegate]
             (mapv :type (:steps (get by-name "prompt-build")))))
      ;; lambda-build: 3 target-authored delegate steps
      (is (= 3 (count (:steps (get by-name "lambda-build")))))
      (is (= [:delegate :delegate :delegate]
             (mapv :type (:steps (get by-name "lambda-build"))))))))

(deftest migrated-target-authoring-examples-test
  (testing "plan-build and plan-build-review compile as target-authored inline-session examples"
    (let [parsed (loader/scan-directory ".psi/workflows")
          {:keys [definitions]} (compiler/compile-workflow-files parsed)
          by-name (into {} (map (juxt :name identity)) definitions)
          plan-build (get by-name "plan-build")
          plan-build-review (get by-name "plan-build-review")]
      (is (= [:session :session]
             (mapv :type (:steps plan-build))))
      (is (= [:session :session :session]
             (mapv :type (:steps plan-build-review))))
      (is (= "plan"
             (get-in plan-build [:steps 1 :contributions 1 :vars "plan" :from :step])))
      (is (= :text
             (get-in plan-build [:steps 1 :contributions 1 :vars "plan" :from :yield])))))

  (testing "delegate-build-review compiles as the executable target-authored delegate-heavy example"
    (let [parsed (loader/scan-directory ".psi/workflows")
          {:keys [definitions]} (compiler/compile-workflow-files parsed)
          by-name (into {} (map (juxt :name identity)) definitions)
          delegate-build-review (get by-name "delegate-build-review")]
      (is (= [:delegate :delegate :session]
             (mapv :type (:steps delegate-build-review))))
      (is (= :text
             (get-in delegate-build-review [:steps 1 :prompt-string :vars "plan" :from :yield])))
      (is (= :text
             (get-in delegate-build-review [:steps 2 :contributions 1 :vars "implementation" :from :yield])))
      (is (= [{:type :source
               :from :workflow-original}
              {:type :source
               :from {:step "plan" :yield :text}}]
             (get-in delegate-build-review [:steps 1 :context])))))

  (testing "gh-bug-triage-modular now compiles as the richer target-authored delegate example with distinct yielded text and structured handoff surfaces"
    (let [parsed (loader/scan-directory ".psi/workflows")
          {:keys [definitions]} (compiler/compile-workflow-files parsed)
          by-name (into {} (map (juxt :name identity)) definitions)
          gh-bug-triage-modular (get by-name "gh-bug-triage-modular")]
      (is (= [:delegate :delegate :delegate :delegate]
             (mapv :type (:steps gh-bug-triage-modular))))
      (is (= :text
             (get-in gh-bug-triage-modular [:steps 1 :prompt-string :vars "discover_report" :from :yield])))
      (is (= :handoff
             (get-in gh-bug-triage-modular [:steps 1 :context 1 :from :output])))
      (is (= :handoff
             (get-in gh-bug-triage-modular [:steps 2 :context 2 :from :output])))
      (is (= :transcript
             (get-in gh-bug-triage-modular [:steps 3 :context 3 :from :output]))))))
