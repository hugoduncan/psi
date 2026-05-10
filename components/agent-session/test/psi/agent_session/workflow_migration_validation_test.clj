(ns psi.agent-session.workflow-migration-validation-test
  "Validate that all migrated .psi/workflows/*.md files parse and compile correctly."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.compiler :as compiler]
   [psi.workflow-loader.core :as loader]))

(def ^:private required-workflow-subset
  #{"planner"
    "builder"
    "prompt-build"
    "lambda-build"
    "plan-build"
    "plan-build-review"
    "delegate-build-review"
    "gh-bug-triage-modular"})

(defn- workflow-migration-view
  []
  (let [parsed (loader/scan-directory ".psi/workflows")
        compile-result (compiler/compile-workflow-files parsed)
        definitions (:definitions compile-result)]
    {:parsed parsed
     :parse-errors (filter :error parsed)
     :definitions definitions
     :errors (:errors compile-result)
     :by-name (into {} (map (juxt :name identity)) definitions)}))

(deftest migrated-workflow-files-test
  (testing "all .psi/workflows/ files parse, compile, and validate"
    (let [{:keys [parsed parse-errors definitions errors by-name]} (workflow-migration-view)]
      (is (seq parsed)
          "Expected at least one workflow file")
      (is (every? #(contains? by-name %) required-workflow-subset)
          (str "Missing required workflows: "
               (pr-str (sort (remove #(contains? by-name %) required-workflow-subset)))))
      (is (empty? parse-errors)
          (str "Parse errors: " (pr-str (mapv #(select-keys % [:name :error :source-path]) parse-errors))))
      (is (empty? errors)
          (str "Compile errors: " (pr-str errors)))
      (doseq [defn-map definitions]
        (is (vector? (:steps defn-map))
            (str "Expected target-authored definition: " (:name defn-map))))
      (let [ref-result (compiler/validate-step-references definitions)]
        (is (true? (:valid? ref-result))
            (str "Unresolved step references: " (pr-str (:errors ref-result)))))
      (let [collision-result (compiler/validate-no-name-collisions definitions)]
        (is (true? (:valid? collision-result))
            (str "Name collisions: " (pr-str (:duplicates collision-result))))))))

(deftest migrated-single-step-workflows-test
  (testing "single-step workflows carry expected target-authored session configuration"
    (let [{:keys [by-name]} (workflow-migration-view)]
      ;; planner
      (let [p (get by-name "planner")]
        (is (some? p))
        (is (= [:session]
               (mapv :type (:steps p))))
        (is (= ["read" "bash"]
               (get-in p [:steps 0 :tools])))
        (is (= :workflow-input
               (get-in p [:steps 0 :contributions 0 :vars "input" :from]))))
      ;; builder has 4 tools
      (let [b (get by-name "builder")]
        (is (= ["read" "bash" "edit" "write"]
               (get-in b [:steps 0 :tools]))))
      ;; lambda-compiler has skill
      (let [lc (get by-name "lambda-compiler")]
        (is (= ["lambda-compiler"]
               (get-in lc [:steps 0 :skills])))))))

(deftest migrated-multi-step-workflows-test
  (testing "multi-step workflows have correct step counts"
    (let [{:keys [by-name]} (workflow-migration-view)]
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
    (let [{:keys [by-name]} (workflow-migration-view)
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
    (let [{:keys [by-name]} (workflow-migration-view)
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

  (testing "gh-bug-triage-modular discover is a deterministic :invoke step; downstream steps use yielded text and structured handoff surfaces"
    (let [{:keys [by-name]} (workflow-migration-view)
          gh-bug-triage-modular (get by-name "gh-bug-triage-modular")]
      (is (= [:invoke :delegate :delegate :delegate]
             (mapv :type (:steps gh-bug-triage-modular))))
      (is (= :text
             (get-in gh-bug-triage-modular [:steps 1 :prompt-string :vars "discover_report" :from :yield])))
      (is (= :text
             (get-in gh-bug-triage-modular [:steps 1 :context 1 :from :yield])))
      (is (= :handoff
             (get-in gh-bug-triage-modular [:steps 2 :context 2 :from :output])))
      (is (= :handoff
             (get-in gh-bug-triage-modular [:steps 3 :context 3 :from :output]))))))
