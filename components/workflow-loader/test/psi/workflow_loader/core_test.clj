(ns psi.workflow-loader.core-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.core :as loader]))

(defn- with-temp-workflow-dir
  "Create a temp directory with workflow files, call f with the dir path, then cleanup."
  [files f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "wf-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (try
      (doseq [[filename content] files]
        (spit (io/file dir filename) content))
      (f (.getAbsolutePath dir))
      (finally
        (doseq [f (.listFiles dir)] (.delete f))
        (.delete dir)))))

(defn- with-project-loader-result
  [files f]
  (with-temp-workflow-dir
    files
    (fn [dir]
      (with-redefs [loader/global-workflow-dirs (constantly [])
                    loader/project-workflow-dir (constantly dir)]
        (f dir (loader/load-workflow-definitions dir))))))

(defn- with-project-definitions
  [files f]
  (with-project-loader-result
    files
    (fn [_dir {:keys [definitions errors warnings]}]
      (f {:definitions definitions
          :errors errors
          :warnings warnings}))))

(def planner-md
  (str "---\nname: planner\ndescription: Plans tasks\n---\n"
       "{:steps [{:name \"plan\"\n"
       "          :type :session\n"
       "          :tools [\"read\" \"bash\"]\n"
       "          :contributions [{:type :template\n"
       "                           :text \"You are a planner.\\n\\n{{input}}\"\n"
       "                           :vars {\"input\" {:from :workflow-input}}}]}]}"))

(def builder-md
  (str "---\nname: builder\ndescription: Builds code\n---\n"
       "{:steps [{:name \"build\"\n"
       "          :type :session\n"
       "          :tools [\"read\" \"bash\" \"edit\" \"write\"]\n"
       "          :contributions [{:type :template\n"
       "                           :text \"You are a builder agent.\\n\\n{{input}}\"\n"
       "                           :vars {\"input\" {:from :workflow-input}}}]}]}"))

(def reviewer-md
  (str "---\nname: reviewer\ndescription: Reviews code\n---\n"
       "{:steps [{:name \"review\"\n"
       "          :type :session\n"
       "          :tools [\"read\" \"bash\"]\n"
       "          :contributions [{:type :template\n"
       "                           :text \"You are a reviewer.\\n\\n{{input}}\"\n"
       "                           :vars {\"input\" {:from :workflow-input}}}]}]}"))

(def chain-md
  (str "---\nname: plan-build-review\ndescription: Plan, build, and review\n---\n"
       "{:steps [{:name \"plan\"\n"
       "          :type :delegate\n"
       "          :target \"planner\"\n"
       "          :prompt-string {:type :template :text \"{{input}}\" :vars {\"input\" {:from :workflow-input}}}}\n"
       "         {:name \"build\"\n"
       "          :type :delegate\n"
       "          :target \"builder\"\n"
       "          :prompt-string {:type :template :text \"Execute: {{input}}\\nOriginal: {{original}}\"\n"
       "                          :vars {\"input\" {:from {:step \"plan\" :yield :text}}\n"
       "                                 \"original\" {:from :workflow-original}}}}\n"
       "         {:name \"review\"\n"
       "          :type :delegate\n"
       "          :target \"reviewer\"\n"
       "          :prompt-string {:type :template :text \"Review: {{input}}\\nOriginal: {{original}}\"\n"
       "                          :vars {\"input\" {:from {:step \"build\" :yield :text}}\n"
       "                                 \"original\" {:from :workflow-original}}}}]}\n\n"
       "Coordinate a plan-build-review cycle."))

(def explicit-source-chain-md
  (str "---\nname: bug-triage\ndescription: Modular bug triage\n---\n"
       "{:steps [{:name \"discover\"\n"
       "          :type :delegate\n"
       "          :target \"planner\"\n"
       "          :prompt-string {:type :template :text \"{{input}}\" :vars {\"input\" {:from :workflow-input}}}}\n"
       "         {:name \"reproduce\"\n"
       "          :type :delegate\n"
       "          :target \"builder\"\n"
       "          :prompt-string {:type :template :text \"{{input}}\"\n"
       "                          :vars {\"input\" {:from {:step \"discover\" :yield :text}}}}}\n"
       "         {:name \"request-more-info\"\n"
       "          :type :delegate\n"
       "          :target \"reviewer\"\n"
       "          :prompt-string {:type :template :text \"{{input}}\"\n"
       "                          :vars {\"input\" {:from {:step \"reproduce\" :yield :text}}}}}\n"
       "         {:name \"fix\"\n"
       "          :type :delegate\n"
       "          :target \"reviewer\"\n"
       "          :prompt-string {:type :template :text \"{{input}}\"\n"
       "                          :vars {\"input\" {:from {:step \"reproduce\" :yield :text}}}}}]}\n\n"
       "Coordinate modular bug triage."))

(def projected-chain-md
  (str "---\nname: projection-chain\ndescription: Projection chain\n---\n"
       "{:steps [{:name \"discover\"\n"
       "          :type :delegate\n"
       "          :target \"planner\"\n"
       "          :prompt-string {:type :template :text \"{{input}}\" :vars {\"input\" {:from :workflow-input :path [:task]}}}\n"
       "          :context [{:type :source :from :workflow-original :projection :full}]}\n"
       "         {:name \"reproduce\"\n"
       "          :type :delegate\n"
       "          :target \"builder\"\n"
       "          :prompt-string {:type :template :text \"{{input}}\"\n"
       "                          :vars {\"input\" {:from {:step \"discover\" :yield :text}}}}\n"
       "          :context [{:type :source :from :workflow-input :path [:ticket :title]}]}\n"
       "         {:name \"request-more-info\"\n"
       "          :type :delegate\n"
       "          :target \"reviewer\"\n"
       "          :prompt-string {:type :template :text \"{{input}}\"\n"
       "                          :vars {\"input\" {:from {:step \"reproduce\" :yield :text}}}}\n"
       "          :context [{:type :source :from :workflow-original :projection :text}]}]}\n\n"
       "Projection chain."))

(def preload-chain-md
  (str "---\nname: preload-chain\ndescription: Preload chain\n---\n"
       "{:steps [{:name \"discover\"\n"
       "          :type :delegate\n"
       "          :target \"planner\"\n"
       "          :prompt-string {:type :template :text \"{{input}}\" :vars {\"input\" {:from :workflow-input}}}}\n"
       "         {:name \"reproduce\"\n"
       "          :type :delegate\n"
       "          :target \"builder\"\n"
       "          :prompt-string {:type :template :text \"{{input}}\"\n"
       "                          :vars {\"input\" {:from {:step \"discover\" :yield :text}}}}}\n"
       "         {:name \"post-repro\"\n"
       "          :type :session\n"
       "          :tools [\"read\" \"bash\"]\n"
       "          :contributions [{:type :source :from :workflow-original}\n"
       "                          {:type :source :from {:step \"discover\" :yield :text} :projection :text}\n"
       "                          {:type :source :from {:step \"reproduce\" :output :transcript}\n"
       "                           :projection {:type :tail :turns 4 :tool-output false}}\n"
       "                          {:type :template :text \"{{input}}\"\n"
       "                           :vars {\"input\" {:from {:step \"reproduce\" :yield :text}}}}]}]}\n\n"
       "Preload chain."))

(def bad-md
  "---\nname: broken\n---\nNo description.")

(deftest scan-directory-test
  (testing "scans directory for .md files and parses them"
    (with-temp-workflow-dir
      {"planner.md" planner-md
       "builder.md" builder-md
       "not-a-workflow.txt" "ignored"}
      (fn [dir]
        (let [results (loader/scan-directory dir)]
          (is (= 2 (count results)))
          (is (= #{"planner" "builder"}
                 (set (map :name results))))
          (is (every? :source-path results))))))

  (testing "returns empty for non-existent directory"
    (is (empty? (loader/scan-directory "/tmp/nonexistent-workflow-dir-xyz")))))

(deftest load-workflow-definitions-test
  (testing "loads and compiles workflow files from a project directory"
    (with-project-loader-result
      {"planner.md" planner-md
       "builder.md" builder-md}
      (fn [_dir {:keys [definitions errors]}]
        (is (= #{"planner" "builder"} (set (keys definitions))))
        (is (empty? errors))
        (is (= [:session]
               (mapv :type (:steps (get definitions "planner")))))
        (is (= [:session]
               (mapv :type (:steps (get definitions "builder"))))))))

  (testing "multi-step definitions compile with step references resolved"
    (with-project-loader-result
      {"planner.md" planner-md
       "builder.md" builder-md
       "reviewer.md" reviewer-md
       "plan-build-review.md" chain-md}
      (fn [_dir {:keys [definitions errors]}]
        (is (= 4 (count definitions)))
        (is (contains? definitions "plan-build-review"))
        (is (= 3 (count (get-in definitions ["plan-build-review" :steps]))))
        (is (= [:delegate :delegate :delegate]
               (mapv :type (get-in definitions ["plan-build-review" :steps]))))
        (is (empty? errors)))))

  (testing "explicit named prior-step source selection loads and compiles"
    (with-project-definitions
      {"planner.md" planner-md
       "builder.md" builder-md
       "reviewer.md" reviewer-md
       "bug-triage.md" explicit-source-chain-md}
      (fn [{:keys [definitions errors]}]
        (let [definition (get definitions "bug-triage")]
          (is (empty? errors))
          (is (= [:delegate :delegate :delegate :delegate]
                 (mapv :type (:steps definition))))
          (is (= :workflow-input
                 (get-in definition [:steps 0 :prompt-string :vars "input" :from])))
          (is (= :text
                 (get-in definition [:steps 1 :prompt-string :vars "input" :from :yield])))
          (is (= :text
                 (get-in definition [:steps 2 :prompt-string :vars "input" :from :yield])))
          (is (= :text
                 (get-in definition [:steps 3 :prompt-string :vars "input" :from :yield])))))))

  (testing "projected source selection loads and compiles"
    (with-project-definitions
      {"planner.md" planner-md
       "builder.md" builder-md
       "reviewer.md" reviewer-md
       "projection-chain.md" projected-chain-md}
      (fn [{:keys [definitions errors]}]
        (let [definition (get definitions "projection-chain")]
          (is (empty? errors))
          (is (= [:delegate :delegate :delegate]
                 (mapv :type (:steps definition))))
          (is (= [:task]
                 (get-in definition [:steps 0 :prompt-string :vars "input" :path])))
          (is (= :full
                 (get-in definition [:steps 0 :context 0 :projection])))
          (is (= [:ticket :title]
                 (get-in definition [:steps 1 :context 0 :path])))
          (is (= :text
                 (get-in definition [:steps 2 :context 0 :projection])))))))

  (testing "session preload loads and compiles"
    (with-project-definitions
      {"planner.md" planner-md
       "builder.md" builder-md
       "reviewer.md" reviewer-md
       "preload-chain.md" preload-chain-md}
      (fn [{:keys [definitions errors]}]
        (let [definition (get definitions "preload-chain")]
          (is (empty? errors))
          (is (= [:delegate :delegate :session]
                 (mapv :type (:steps definition))))
          (is (= :workflow-original
                 (get-in definition [:steps 2 :contributions 0 :from])))
          (is (= :text
                 (get-in definition [:steps 2 :contributions 1 :from :yield])))
          (is (= :transcript
                 (get-in definition [:steps 2 :contributions 2 :from :output])))
          (is (= {:type :tail :turns 4 :tool-output false}
                 (get-in definition [:steps 2 :contributions 2 :projection])))))))

  (testing "delegate targets remain loader-time data and do not require local target definitions to compile"
    (with-project-definitions
      {"plan-build-review.md" chain-md}
      (fn [{:keys [definitions errors]}]
        (is (= 1 (count definitions)))
        (is (empty? errors))
        (is (= [:delegate :delegate :delegate]
               (mapv :type (:steps (get definitions "plan-build-review"))))))))

  (testing "parse errors collected separately from successful compilations"
    (with-project-loader-result
      {"planner.md" planner-md
       "broken.md" bad-md}
      (fn [_dir {:keys [definitions errors]}]
        (is (= 1 (count definitions)))
        (is (contains? definitions "planner"))
        (is (= 1 (count errors)))))))

(deftest load-workflow-definitions-target-only-compilation-test
  (testing "current-authored workflow files are rejected after retirement"
    (let [current-authored-md (str "---\nname: current-authored\ndescription: Old grammar\n---\n"
                                   "{:steps [{:name \"plan\" :workflow \"planner\" :prompt \"$INPUT\"}]}\n")]
      (with-project-definitions
        {"current-authored.md" current-authored-md}
        (fn [{:keys [definitions errors]}]
          (is (empty? definitions))
          (is (seq errors))
          (is (some #(re-find #"Workflow files must define target-authored `\{:steps \[\.\.\.\]\}` config" (:error %)) errors))))))

  (testing "malformed EDN still surfaces as a parse/compile error"
    (let [bad-edn-md (str "---\nname: bad-edn\ndescription: Bad edn\n---\n"
                          "{:steps [")]
      (with-project-definitions
        {"bad-edn.md" bad-edn-md}
        (fn [{:keys [errors]}]
          (is (seq errors))
          (is (some #(re-find #"EOF while reading" (:error %)) errors)))))))

(deftest loader-api-contract-test
  (testing "load-workflow-definitions remains the canonical lower entrypoint while helper APIs retain intentional result shapes"
    (with-project-loader-result
      {"planner.md" planner-md}
      (fn [dir {:keys [definitions errors warnings] :as result}]
        (is (= #{:definitions :errors :warnings}
               (set (keys result))))
        (is (= #{"planner"}
               (set (keys definitions))))
        (is (vector? errors))
        (is (vector? warnings))
        (is (vector? (loader/scan-directory dir))))))

  (testing "scan-directory remains the lower file-scan helper shape used by higher proofs"
    (with-temp-workflow-dir
      {"planner.md" planner-md}
      (fn [dir]
        (let [parsed (loader/scan-directory dir)]
          (is (= 1 (count parsed)))
          (is (= #{:name :description :config :body :source-path}
                 (set (keys (first parsed))))))))))

(deftest directory-precedence-test
  (testing "project definitions override global definitions with same name"
    (let [global-planner planner-md
          project-planner (str "---\nname: planner\ndescription: Project planner\n---\n"
                               "{:steps [{:name \"plan\"\n"
                               "          :type :session\n"
                               "          :tools [\"read\"]\n"
                               "          :contributions [{:type :template\n"
                               "                           :text \"Project planner: {{input}}\"\n"
                               "                           :vars {\"input\" {:from :workflow-input}}}]}]}")]
      (with-temp-workflow-dir
        {"planner.md" global-planner}
        (fn [global-dir]
          (with-temp-workflow-dir
            {"planner.md" project-planner}
            (fn [project-dir]
              (with-redefs [loader/global-workflow-dirs (constantly [global-dir])
                            loader/project-workflow-dir (constantly project-dir)]
                (let [{:keys [definitions]} (loader/load-workflow-definitions project-dir)]
                  (is (= "Project planner" (:summary (get definitions "planner"))))
                  (is (= "Project planner: {{input}}"
                         (get-in definitions ["planner" :steps 0 :contributions 0 :text]))))))))))))
