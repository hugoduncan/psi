(ns psi.workflow-loader.compiler-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.compiler :as compiler]
   [psi.workflow-registry.definition :as workflow-definition]))

(def markdown-parsed
  {:workflow-kind :single-step-markdown
   :name "planner"
   :description "Plans tasks"
   :session-config {:tools ["read" "bash"]
                    :thinking-level :off}
   :body "You are a planner."
   :source-path "/tmp/planner.md"})

(def edn-parsed
  {:workflow-kind :multi-step-edn
   :config {:name "plan-build-review"
            :definition-id "plan-build-review"
            :steps [{:name "plan"
                     :type :session
                     :contributions [{:type :template
                                      :text "{{input}}"
                                      :vars {"input" {:from :workflow-input
                                                      :path [:input]}}}]}
                    {:name "review"
                     :type :delegate
                     :target "reviewer"
                     :prompt-string {:type :template
                                     :text "Review {{plan}}"
                                     :vars {"plan" {:from {:step "plan"
                                                           :yield :text}}}}}]}
   :source-path "/tmp/plan-build-review.edn"})

(deftest compile-markdown-workflow-file-test
  (testing "standalone markdown workflow compiles to exactly one canonical session step"
    (let [{:keys [definition error]} (compiler/compile-workflow-file markdown-parsed)]
      (is (nil? error))
      (is (= "planner" (:definition-id definition)))
      (is (= "planner" (:name definition)))
      (is (= "Plans tasks" (:summary definition)))
      (is (= 1 (count (:steps definition))))
      (is (= {:name "step"
              :type :session
              :tools ["read" "bash"]
              :thinking-level :off
              :contributions [{:type :template :text "You are a planner." :vars {}}]}
             (first (:steps definition))))
      (is (workflow-definition/target-authored-workflow-definition? definition))))

  (testing "batch compilation keeps markdown and edn successes together"
    (let [{:keys [definitions errors]} (compiler/compile-workflow-files [markdown-parsed edn-parsed])]
      (is (= 2 (count definitions)))
      (is (empty? errors)))))

(deftest compile-edn-prompt-workflow-test
  (testing "session step prompt-workflow imports markdown body and default config with step-local override precedence"
    (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "wf-compiler-" (System/nanoTime)))
          md-file (io/file dir "planner.md")]
      (.mkdirs dir)
      (spit md-file "---\nname: planner\ndescription: Plans tasks\ntools:\n  - read\nthinking-level: :low\n---\nYou are a planner.")
      (try
        (let [{:keys [definition error]}
              (compiler/compile-workflow-file
               {:workflow-kind :multi-step-edn
                :config {:name "orchestrator"
                         :definition-id "orchestrator"
                         :steps [{:name "plan"
                                  :type :session
                                  :prompt-workflow "planner.md"
                                  :tools ["bash"]}]}
                :source-path (.getAbsolutePath (io/file dir "orchestrator.edn"))})]
          (is (nil? error))
          (is (= ["bash"] (get-in definition [:steps 0 :tools])))
          (is (= ":low" (get-in definition [:steps 0 :thinking-level])))
          (is (= [{:type :template :text "You are a planner." :vars {}}]
                 (get-in definition [:steps 0 :contributions]))))
        (finally
          (.delete md-file)
          (.delete dir)))))

  (testing "prompt-workflow rejects non-session step usage"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:steps [{:name "plan"
                              :type :delegate
                              :prompt-workflow "planner.md"}]}
            :source-path "/tmp/orchestrator.edn"})]
      (is (= "`:prompt-workflow` is allowed only on `:session` steps" error))))

  (testing "prompt-workflow rejects dual prompt sources"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:steps [{:name "plan"
                              :type :session
                              :prompt-workflow "planner.md"
                              :contributions [{:type :template :text "nope" :vars {}}]}]}
            :source-path "/tmp/orchestrator.edn"})]
      (is (= "`:prompt-workflow` cannot be combined with another authored prompt source" error))))

  (testing "prompt-workflow rejects missing referenced file"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:steps [{:name "plan"
                              :type :session
                              :prompt-workflow "missing.md"}]}
            :source-path "/tmp/orchestrator.edn"})]
      (is (re-find #"Referenced prompt workflow file not found" error))))

  (testing "prompt-workflow rejects wrong-kind reference"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:steps [{:name "plan"
                              :type :session
                              :prompt-workflow "planner.edn"}]}
            :source-path "/tmp/orchestrator.edn"})]
      (is (re-find #"must reference a \.md file" error)))))