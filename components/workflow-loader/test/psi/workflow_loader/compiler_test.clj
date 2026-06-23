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
                    :thinking-level :off
                    :session-profile :planning}
   :body "You are a planner."
   :vars nil
   :source-path "/tmp/planner.md"})

(def edn-parsed
  {:workflow-kind :multi-step-edn
   :config {:name "plan-build-review"
            :description "Plan, build, and review code changes"
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
              :session-profile :planning
              :contributions [{:type :template :text "You are a planner." :vars {}}]}
             (first (:steps definition))))
      (is (workflow-definition/target-authored-workflow-definition? definition))))

  (testing "advertise defaults to true when absent from parsed markdown"
    (let [{:keys [definition]} (compiler/compile-workflow-file markdown-parsed)]
      (is (true? (:advertise definition)))))

  (testing "advertise false propagates into the markdown definition"
    (let [{:keys [definition]} (compiler/compile-workflow-file
                                (assoc markdown-parsed :advertise false))]
      (is (false? (:advertise definition)))))

  (testing "batch compilation keeps markdown and edn successes together"
    (let [{:keys [definitions errors]} (compiler/compile-workflow-files [markdown-parsed edn-parsed])]
      (is (= 2 (count definitions)))
      (is (empty? errors)))))

(deftest compile-edn-prompt-workflow-test
  (testing "advertise false in edn config propagates into the compiled definition"
    (let [{:keys [definition error]}
          (compiler/compile-workflow-file
           (assoc-in edn-parsed [:config :advertise] false))]
      (is (nil? error))
      (is (false? (:advertise definition)))))

  (testing "advertise absent from edn config leaves advertise absent in the definition"
    (let [{:keys [definition error]} (compiler/compile-workflow-file edn-parsed)]
      (is (nil? error))
      (is (not (contains? definition :advertise)))))

  (testing "edn workflows require top-level name and description"
    (let [{missing-name-error :error}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:description "desc"
                     :steps [{:name "plan"
                              :type :session
                              :contributions [{:type :template :text "hi" :vars {}}]}]}
            :source-path "/tmp/orchestrator.edn"})
          {blank-name-error :error}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:name "  "
                     :description "desc"
                     :steps [{:name "plan"
                              :type :session
                              :contributions [{:type :template :text "hi" :vars {}}]}]}
            :source-path "/tmp/orchestrator.edn"})
          {missing-description-error :error}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:name "orchestrator"
                     :steps [{:name "plan"
                              :type :session
                              :contributions [{:type :template :text "hi" :vars {}}]}]}
            :source-path "/tmp/orchestrator.edn"})
          {blank-description-error :error}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:name "orchestrator"
                     :description "  "
                     :steps [{:name "plan"
                              :type :session
                              :contributions [{:type :template :text "hi" :vars {}}]}]}
            :source-path "/tmp/orchestrator.edn"})]
      (is (= "Workflow EDN files must define top-level `:name` as a string" missing-name-error))
      (is (= "Workflow EDN files must define top-level `:name` as a non-blank string" blank-name-error))
      (is (= "Workflow EDN files must define top-level `:description` as a string" missing-description-error))
      (is (= "Workflow EDN files must define top-level `:description` as a non-blank string" blank-description-error))))

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
                         :description "Orchestrates a prompt workflow"
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
            :config {:name "orchestrator"
                     :description "Orchestrates a prompt workflow"
                     :steps [{:name "plan"
                              :type :delegate
                              :prompt-workflow "planner.md"}]}
            :source-path "/tmp/orchestrator.edn"})]
      (is (= "`:prompt-workflow` is allowed only on `:session` steps" error))))

  (testing "prompt-workflow rejects dual prompt sources"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:name "orchestrator"
                     :description "Orchestrates a prompt workflow"
                     :steps [{:name "plan"
                              :type :session
                              :prompt-workflow "planner.md"
                              :contributions [{:type :template :text "nope" :vars {}}]}]}
            :source-path "/tmp/orchestrator.edn"})]
      (is (= "`:prompt-workflow` cannot be combined with another authored prompt source" error))))

  (testing "prompt-workflow rejects missing referenced file"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:name "orchestrator"
                     :description "Orchestrates a prompt workflow"
                     :steps [{:name "plan"
                              :type :session
                              :prompt-workflow "missing.md"}]}
            :source-path "/tmp/orchestrator.edn"})]
      (is (re-find #"Referenced prompt workflow file not found" error))))

  (testing "prompt-workflow rejects wrong-kind reference"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:name "orchestrator"
                     :description "Orchestrates a prompt workflow"
                     :steps [{:name "plan"
                              :type :session
                              :prompt-workflow "planner.edn"}]}
            :source-path "/tmp/orchestrator.edn"})]
      (is (re-find #"must reference a \.md file" error))))

  (testing "prompt-workflow rejects absolute and escaping paths"
    (let [{absolute-error :error}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:name "orchestrator"
                     :description "Orchestrates a prompt workflow"
                     :steps [{:name "plan"
                              :type :session
                              :prompt-workflow "/tmp/planner.md"}]}
            :source-path "/tmp/orchestrator.edn"})
          {escape-error :error}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:name "orchestrator"
                     :description "Orchestrates a prompt workflow"
                     :steps [{:name "plan"
                              :type :session
                              :prompt-workflow "../planner.md"}]}
            :source-path "/tmp/orchestrator.edn"})]
      (is (= "`:prompt-workflow` must be a relative .md path within the consuming workflow directory"
             absolute-error))
      (is (= "`:prompt-workflow` must be a relative .md path within the consuming workflow directory"
             escape-error)))))

(deftest markdown-body-var-expansion-test
  (testing "body with {{input}} produces :vars with input wired to :workflow-input"
    (let [{:keys [definition error]}
          (compiler/compile-workflow-file
           {:workflow-kind :single-step-markdown
            :name "step"
            :description "A step"
            :session-config {}
            :body "Process {{input}} now."
            :vars nil})]
      (is (nil? error))
      (is (= {"input" {:from :workflow-input :path [:input]}}
             (get-in definition [:steps 0 :contributions 0 :vars])))))

  (testing "body with {{original}} produces :vars with original wired to :workflow-original"
    (let [{:keys [definition error]}
          (compiler/compile-workflow-file
           {:workflow-kind :single-step-markdown
            :name "step"
            :description "A step"
            :session-config {}
            :body "Original was {{original}}."
            :vars nil})]
      (is (nil? error))
      (is (= {"original" {:from :workflow-original}}
             (get-in definition [:steps 0 :contributions 0 :vars])))))

  (testing "body with unknown {{foo}} not declared returns error"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:workflow-kind :single-step-markdown
            :name "step"
            :description "A step"
            :session-config {}
            :body "Unknown {{foo}} token."
            :vars nil})]
      (is (re-find #"Unknown \{\{varname\}\} tokens" error))
      (is (re-find #"\"foo\"" error)
          "error message should name the specific unknown var")))

  (testing "non-matching tokens like {{1bad}} and {{}} pass through without error"
    (let [{:keys [definition error]}
          (compiler/compile-workflow-file
           {:workflow-kind :single-step-markdown
            :name "step"
            :description "A step"
            :session-config {}
            :body "Token {{1bad}} and {{}} are not vars."
            :vars nil})]
      (is (nil? error))
      (is (= {} (get-in definition [:steps 0 :contributions 0 :vars]))
          "non-matching tokens should not be treated as unknown vars")))

  (testing "body with {{my-var}} declared in frontmatter vars produces correct :vars entry"
    (let [{:keys [definition error]}
          (compiler/compile-workflow-file
           {:workflow-kind :single-step-markdown
            :name "step"
            :description "A step"
            :session-config {}
            :body "Use {{my-var}} here."
            :vars {"my-var" {:from :workflow-input :path [:some-field]}}})]
      (is (nil? error))
      (is (= {"my-var" {:from :workflow-input :path [:some-field]}}
             (get-in definition [:steps 0 :contributions 0 :vars])))))

  (testing "no :framing-prompt in workflow-file-meta for single-step .md workflow"
    (let [{:keys [definition error]}
          (compiler/compile-workflow-file
           {:workflow-kind :single-step-markdown
            :name "step"
            :description "A step"
            :session-config {}
            :body "Body text."
            :vars nil})]
      (is (nil? error))
      (is (nil? (get-in definition [:workflow-file-meta :framing-prompt])))))

  (testing ":prompt-workflow step referencing .md with {{input}} compiles with correct :vars"
    (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "wf-var-test-" (System/nanoTime)))
          md-file (io/file dir "my-step.md")]
      (.mkdirs dir)
      (spit md-file "---\nname: my-step\ndescription: My step\ntools:\n  - read\n---\nProcess {{input}}.")
      (try
        (let [{:keys [definition error]}
              (compiler/compile-workflow-file
               {:workflow-kind :multi-step-edn
                :config {:name "orchestrator"
                         :description "Orchestrates"
                         :definition-id "orchestrator"
                         :steps [{:name "step"
                                  :type :session
                                  :prompt-workflow "my-step.md"}]}
                :source-path (.getAbsolutePath (io/file dir "orchestrator.edn"))})]
          (is (nil? error))
          (is (= {"input" {:from :workflow-input :path [:input]}}
                 (get-in definition [:steps 0 :contributions 0 :vars]))))
        (finally
          (.delete md-file)
          (.delete dir)))))

  (testing ".edn step :tools takes precedence over .md frontmatter tools"
    (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "wf-tools-test-" (System/nanoTime)))
          md-file (io/file dir "my-step.md")]
      (.mkdirs dir)
      (spit md-file "---\nname: my-step\ndescription: My step\ntools:\n  - read\n---\nProcess {{input}}.")
      (try
        (let [{:keys [definition error]}
              (compiler/compile-workflow-file
               {:workflow-kind :multi-step-edn
                :config {:name "orchestrator"
                         :description "Orchestrates"
                         :definition-id "orchestrator"
                         :steps [{:name "step"
                                  :type :session
                                  :tools ["bash" "write"]
                                  :prompt-workflow "my-step.md"}]}
                :source-path (.getAbsolutePath (io/file dir "orchestrator.edn"))})]
          (is (nil? error))
          ;; .edn step tools take precedence
          (is (= ["bash" "write"] (get-in definition [:steps 0 :tools]))))
        (finally
          (.delete md-file)
          (.delete dir)))))

  (testing ".md frontmatter tools fill in when .edn step omits :tools"
    (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "wf-tools-fill-test-" (System/nanoTime)))
          md-file (io/file dir "my-step.md")]
      (.mkdirs dir)
      (spit md-file "---\nname: my-step\ndescription: My step\ntools:\n  - read\n---\nProcess {{input}}.")
      (try
        (let [{:keys [definition error]}
              (compiler/compile-workflow-file
               {:workflow-kind :multi-step-edn
                :config {:name "orchestrator"
                         :description "Orchestrates"
                         :definition-id "orchestrator"
                         :steps [{:name "step"
                                  :type :session
                                  :prompt-workflow "my-step.md"}]}
                :source-path (.getAbsolutePath (io/file dir "orchestrator.edn"))})]
          (is (nil? error))
          ;; .md frontmatter fills in tools when step omits them
          (is (= ["read"] (get-in definition [:steps 0 :tools]))))
        (finally
          (.delete md-file)
          (.delete dir)))))

  (testing "standard vars always win over declared vars override attempts"
    ;; vars: frontmatter declaring {"input" {:from :workflow-original}} must NOT override
    ;; the canonical standard-var spec — {{input}} must still resolve to :workflow-input.
    (let [{:keys [definition error]}
          (compiler/compile-workflow-file
           {:workflow-kind :single-step-markdown
            :name "step"
            :description "A step"
            :session-config {}
            :body "Process {{input}}."
            :vars {"input" {:from :workflow-original}}})]
      (is (nil? error))
      (is (= {"input" {:from :workflow-input :path [:input]}}
             (get-in definition [:steps 0 :contributions 0 :vars])))))

  (testing "vars: declared in .md frontmatter threads through :prompt-workflow"
    ;; compile-prompt-workflow-step passes (:vars referenced) to markdown-body->contribution.
    ;; A custom var declared in .md frontmatter vars: must appear in the compiled contribution.
    (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "wf-vars-threading-test-" (System/nanoTime)))
          md-file (io/file dir "my-step.md")]
      (.mkdirs dir)
      (spit md-file "---\nname: my-step\ndescription: My step\ntools:\n  - read\nvars: '{\"my-var\" {:from :workflow-input :path [:some-field]}}'\n---\nUse {{my-var}} here.")
      (try
        (let [{:keys [definition error]}
              (compiler/compile-workflow-file
               {:workflow-kind :multi-step-edn
                :config {:name "orchestrator"
                         :description "Orchestrates"
                         :definition-id "orchestrator"
                         :steps [{:name "step"
                                  :type :session
                                  :prompt-workflow "my-step.md"}]}
                :source-path (.getAbsolutePath (io/file dir "orchestrator.edn"))})]
          (is (nil? error))
          (is (= {"my-var" {:from :workflow-input :path [:some-field]}}
                 (get-in definition [:steps 0 :contributions 0 :vars]))
              "custom var declared in .md frontmatter vars: must be wired in compiled contribution"))
        (finally
          (.delete md-file)
          (.delete dir))))))

(deftest compile-edn-prompts-step-test
  (testing "a :prompts step with inline :contributions groups compiles, preserving order and shared config"
    (let [{:keys [definition error]}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:name "design-review"
                     :description "Multi-prompt design review"
                     :steps [{:name "review"
                              :type :session
                              :tools ["read"]
                              :prompts [{:name "architecture"
                                         :contributions [{:type :template :text "arch" :vars {}}]}
                                        {:name "ambiguity"
                                         :contributions [{:type :template :text "ambig" :vars {}}]}]}]}
            :source-path "/tmp/design-review.edn"})]
      (is (nil? error))
      (is (= ["read"] (get-in definition [:steps 0 :tools])))
      (is (= ["architecture" "ambiguity"]
             (mapv :name (get-in definition [:steps 0 :prompts]))))
      (is (= [{:type :template :text "arch" :vars {}}]
             (get-in definition [:steps 0 :prompts 0 :contributions])))))

  (testing "a group :prompt-workflow body resolves into group :contributions"
    (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "wf-prompts-" (System/nanoTime)))
          md-file (io/file dir "architecture.md")]
      (.mkdirs dir)
      (spit md-file "---\nname: architecture\ndescription: Arch review\n---\nReview the architecture.")
      (try
        (let [{:keys [definition error]}
              (compiler/compile-workflow-file
               {:workflow-kind :multi-step-edn
                :config {:name "design-review"
                         :description "Multi-prompt design review"
                         :steps [{:name "review"
                                  :type :session
                                  :prompts [{:name "architecture"
                                             :prompt-workflow "architecture.md"}
                                            {:name "ambiguity"
                                             :contributions [{:type :template :text "ambig" :vars {}}]}]}]}
                :source-path (.getAbsolutePath (io/file dir "design-review.edn"))})]
          (is (nil? error))
          (is (not (contains? (get-in definition [:steps 0 :prompts 0]) :prompt-workflow)))
          (is (= [{:type :template :text "Review the architecture." :vars {}}]
                 (get-in definition [:steps 0 :prompts 0 :contributions]))))
        (finally
          (.delete md-file)
          (.delete dir)))))

  (testing "a prompt-group with both :prompt-workflow and :contributions is rejected"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:name "design-review"
                     :description "Multi-prompt design review"
                     :steps [{:name "review"
                              :type :session
                              :prompts [{:name "architecture"
                                         :prompt-workflow "architecture.md"
                                         :contributions [{:type :template :text "x" :vars {}}]}]}]}
            :source-path "/tmp/design-review.edn"})]
      (is (= "A prompt-group must define `:prompt-workflow` XOR `:contributions`, not both" error))))

  (testing "a prompt-group with neither :prompt-workflow nor :contributions is rejected"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:name "design-review"
                     :description "Multi-prompt design review"
                     :steps [{:name "review"
                              :type :session
                              :prompts [{:name "architecture"}]}]}
            :source-path "/tmp/design-review.edn"})]
      (is (= "A prompt-group must define `:prompt-workflow` or `:contributions`" error))))

  (testing "a :prompts step combined with step-level :contributions is rejected"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:name "design-review"
                     :description "Multi-prompt design review"
                     :steps [{:name "review"
                              :type :session
                              :contributions [{:type :template :text "x" :vars {}}]
                              :prompts [{:name "architecture"
                                         :contributions [{:type :template :text "arch" :vars {}}]}]}]}
            :source-path "/tmp/design-review.edn"})]
      (is (= "`:prompts` cannot be combined with a step-level `:contributions`/`:system-prompt` prompt source"
             error))))

  (testing "a :prompts step combined with step-level :prompt-workflow is rejected"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:name "design-review"
                     :description "Multi-prompt design review"
                     :steps [{:name "review"
                              :type :session
                              :prompt-workflow "x.md"
                              :prompts [{:name "architecture"
                                         :contributions [{:type :template :text "arch" :vars {}}]}]}]}
            :source-path "/tmp/design-review.edn"})]
      (is (= "`:prompts` cannot be combined with a step-level `:prompt-workflow`" error))))

  (testing "a :prompts step on a non-session step is rejected"
    (let [{:keys [error]}
          (compiler/compile-workflow-file
           {:workflow-kind :multi-step-edn
            :config {:name "design-review"
                     :description "Multi-prompt design review"
                     :steps [{:name "review"
                              :type :delegate
                              :prompts [{:name "architecture"
                                         :contributions [{:type :template :text "arch" :vars {}}]}]}]}
            :source-path "/tmp/design-review.edn"})]
      (is (= "`:prompts` is allowed only on `:session` steps" error)))))
