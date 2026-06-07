(ns psi.workflow-loader.session-profile-grammar-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.compiler :as compiler]
   [psi.workflow-loader.parser :as parser]
   [psi.workflow-runtime.ir :as workflow-ir]
   [psi.workflow-runtime.target-ir-compiler :as target-compiler]))

(defn- compile-ir
  [definition]
  (target-compiler/compile-workflow-definition definition))

(deftest session-profile-grammar-compilation-test
  ;; Tests compact :session-profile grammar on supported workflow step forms.
  (testing "session steps compile compact :session-profile into canonical session config"
    (let [ir (compile-ir {:steps [{:name "plan"
                                   :type :session
                                   :session-profile :planning
                                   :model "gpt-5.5"
                                   :thinking-level :high
                                   :contributions [{:type :source
                                                    :from :workflow-original}]}]})]
      (is (= {:session-profile :planning
              :model "gpt-5.5"
              :thinking-level :high}
             (select-keys (get-in ir [:steps 0 :session])
                          [:session-profile :model :thinking-level])))))

  (testing "delegate steps compile compact :session-profile into delegate session config"
    (let [ir (compile-ir {:steps [{:name "build"
                                   :type :delegate
                                   :target "builder"
                                   :session-profile :coding
                                   :model "gpt-5.5"
                                   :thinking-level :medium
                                   :prompt-string "Build it."}]})]
      (is (= {:session-profile :coding
              :model "gpt-5.5"
              :thinking-level :medium}
             (get-in ir [:steps 0 :delegate :session])))))

  (testing "markdown frontmatter compiles :session-profile to the generated session step"
    (let [parsed (parser/parse-workflow-file
                  :md
                  "---\nname: planner\ndescription: Plans\nsession-profile: planning\n---\nPlan {{input}}.")
          {definition :definition parse-error :error} (compiler/compile-workflow-file parsed)
          ir (compile-ir definition)]
      (is (nil? parse-error))
      (is (= :planning (get-in definition [:steps 0 :session-profile])))
      (is (= :planning (get-in ir [:steps 0 :session :session-profile])))))

  (testing "existing workflows without :session-profile compile unchanged at profile surfaces"
    (let [ir (compile-ir {:steps [{:name "plan"
                                   :type :session
                                   :model "gpt-5.5"
                                   :contributions [{:type :source
                                                    :from :workflow-original}]}
                                  {:name "build"
                                   :type :delegate
                                   :target "builder"
                                   :prompt-string "Build it."}]})]
      (is (not (contains? (get-in ir [:steps 0 :session]) :session-profile)))
      (is (not (contains? (get-in ir [:steps 1 :delegate]) :session))))))

(deftest session-profile-name-grammar-rejection-test
  ;; Tests that unsupported session-profile names fail at workflow loading and canonical validation boundaries.
  (testing "canonical IR rejects namespaced, command-unparseable, and reserved session-step profile names"
    (doseq [profile-name [:team/coding :fast+coding :clear]]
      (let [{:keys [valid? structural-errors]}
            (workflow-ir/validate-workflow-ir
             {:version :workflow-ir/v1
              :steps [{:name "plan"
                       :type :session
                       :session {:session-profile profile-name
                                 :contributions [{:type :source
                                                  :from :workflow-original}]}
                       :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                       :yields {:type :text :text :final-llm-reply}}]})]
        (is (false? valid?) (str "invalid profile should fail: " (pr-str profile-name)))
        (is (some? structural-errors)))))

  (testing "canonical IR rejects namespaced, command-unparseable, and reserved delegate profile names"
    (doseq [profile-name [:team/coding :fast+coding :clear]]
      (let [{:keys [valid? structural-errors]}
            (workflow-ir/validate-workflow-ir
             {:version :workflow-ir/v1
              :steps [{:name "build"
                       :type :delegate
                       :delegate {:target "builder"
                                  :prompt-string "Build it."
                                  :session {:session-profile profile-name}}
                       :outputs {:handoff {:source :delegate/handoff}}
                       :yields {:type :delegated}}]})]
        (is (false? valid?) (str "invalid profile should fail: " (pr-str profile-name)))
        (is (some? structural-errors)))))

  (testing "target compiler rejects invalid compact session and delegate profile names before run creation"
    (doseq [[step-type profile-name]
            [[:session :team/coding]
             [:session :fast+coding]
             [:session :clear]
             [:delegate :team/coding]
             [:delegate :fast+coding]
             [:delegate :clear]]]
      (let [step (case step-type
                   :session {:name "plan"
                             :type :session
                             :session-profile profile-name
                             :contributions [{:type :source
                                              :from :workflow-original}]}
                   :delegate {:name "build"
                              :type :delegate
                              :target "builder"
                              :session-profile profile-name
                              :prompt-string "Build it."})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Session profile names must be selectable unqualified non-reserved keywords"
             (compile-ir {:steps [step]}))
            (str "compiler should reject " step-type " " (pr-str profile-name))))))

  (testing "markdown frontmatter invalid profile names fail when compiled to canonical IR"
    (doseq [frontmatter-profile [":team/coding" "fast+coding" "clear" ":clear"]]
      (let [parsed (parser/parse-workflow-file
                    :md
                    (str "---\nname: planner\ndescription: Plans\nsession-profile: "
                         frontmatter-profile
                         "\n---\nPlan {{input}}."))
            {definition :definition parse-error :error} (compiler/compile-workflow-file parsed)]
        (is (nil? parse-error))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Session profile names must be selectable unqualified non-reserved keywords"
             (compile-ir definition))))))

  (testing "canonical IR rejects string session-profile names"
    (let [{:keys [valid? structural-errors]}
          (workflow-ir/validate-workflow-ir
           {:version :workflow-ir/v1
            :steps [{:name "plan"
                     :type :session
                     :session {:session-profile "planning"
                               :contributions [{:type :source
                                                :from :workflow-original}]}
                     :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                     :yields {:type :text :text :final-llm-reply}}]})]
      (is (false? valid?))
      (is (some? structural-errors))))

  (testing "valid selectable profile names still pass session and delegate IR validation"
    (let [session-result (workflow-ir/validate-workflow-ir
                          {:version :workflow-ir/v1
                           :steps [{:name "plan"
                                    :type :session
                                    :session {:session-profile :fast-coding_1.2
                                              :contributions [{:type :source
                                                               :from :workflow-original}]}
                                    :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                                    :yields {:type :text :text :final-llm-reply}}]})
          delegate-result (workflow-ir/validate-workflow-ir
                           {:version :workflow-ir/v1
                            :steps [{:name "build"
                                     :type :delegate
                                     :delegate {:target "builder"
                                                :prompt-string "Build it."
                                                :session {:session-profile :fast-coding_1.2}}
                                     :outputs {:handoff {:source :delegate/handoff}}
                                     :yields {:type :delegated}}]})]
      (is (:valid? session-result))
      (is (:valid? delegate-result))))

  (testing "parser normalizes markdown frontmatter via shared selectable token spelling"
    (let [bare (parser/parse-workflow-file
                :md
                "---\nname: planner\ndescription: Plans\nsession-profile: fast-coding_1.2\n---\nPlan {{input}}.")
          edn-style (parser/parse-workflow-file
                     :md
                     "---\nname: planner\ndescription: Plans\nsession-profile: :fast-coding_1.2\n---\nPlan {{input}}.")]
      (is (= :fast-coding_1.2 (get-in bare [:session-config :session-profile])))
      (is (= :fast-coding_1.2 (get-in edn-style [:session-config :session-profile]))))))

(deftest session-profile-grammar-rejection-test
  ;; Tests that unsupported placements remain invalid at the canonical IR boundary.
  (testing "nested session-profile spelling is rejected by session schema"
    (let [{:keys [valid? structural-errors]}
          (workflow-ir/validate-workflow-ir
           {:version :workflow-ir/v1
            :steps [{:name "plan"
                     :type :session
                     :session {:session {:session-profile :planning}
                               :contributions [{:type :source
                                                :from :workflow-original}]}
                     :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                     :yields {:type :text :text :final-llm-reply}}]})]
      (is (false? valid?))
      (is (some? structural-errors))))

  (testing "invoke steps reject session-profile"
    (let [{:keys [valid? structural-errors]}
          (workflow-ir/validate-workflow-ir
           {:version :workflow-ir/v1
            :steps [{:name "load"
                     :type :invoke
                     :session-profile :planning
                     :invoke {:operation "demo/load"}
                     :outputs {:data {:source :invoke/data}}
                     :yields {:type :data :data :data}}]})]
      (is (false? valid?))
      (is (some? structural-errors))))

  (testing "llm judge specs reject top-level session-profile"
    (let [{:keys [valid? structural-errors]}
          (workflow-ir/validate-workflow-ir
           {:version :workflow-ir/v1
            :steps [{:name "review"
                     :type :session
                     :session {:contributions [{:type :source
                                                :from :workflow-original}]}
                     :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                     :yields {:type :text :text :final-llm-reply}
                     :judge {:type :llm
                             :session-profile :review
                             :session {:contributions [{:type :template
                                                        :text "OK?"
                                                        :vars {}}]}}
                     :on {"OK" {:goto :done}}}]})]
      (is (false? valid?))
      (is (some? structural-errors))))

  (testing "llm judge session config rejects canonical session-profile"
    (let [{:keys [valid? structural-errors]}
          (workflow-ir/validate-workflow-ir
           {:version :workflow-ir/v1
            :steps [{:name "review"
                     :type :session
                     :session {:contributions [{:type :source
                                                :from :workflow-original}]}
                     :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                     :yields {:type :text :text :final-llm-reply}
                     :judge {:type :llm
                             :session {:session-profile :review
                                       :contributions [{:type :template
                                                        :text "OK?"
                                                        :vars {}}]}}
                     :on {"OK" {:goto :done}}}]})]
      (is (false? valid?))
      (is (some? structural-errors))))

  (testing "direct speed and effort authored keys stay out of session and delegate grammar"
    (let [{session-valid? :valid? session-structural-errors :structural-errors}
          (workflow-ir/validate-workflow-ir
           {:version :workflow-ir/v1
            :steps [{:name "plan"
                     :type :session
                     :session {:speed-mode :fast
                               :effort-override :high
                               :contributions [{:type :source
                                                :from :workflow-original}]}
                     :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                     :yields {:type :text :text :final-llm-reply}}]})
          {delegate-valid? :valid? delegate-structural-errors :structural-errors}
          (workflow-ir/validate-workflow-ir
           {:version :workflow-ir/v1
            :steps [{:name "build"
                     :type :delegate
                     :delegate {:target "builder"
                                :prompt-string "Build it."
                                :session {:session-profile :coding
                                          :speed-mode :fast
                                          :effort-override :high}}
                     :outputs {:handoff {:source :delegate/handoff}}
                     :yields {:type :delegated}}]})]
      (is (false? session-valid?))
      (is (some? session-structural-errors))
      (is (false? delegate-valid?))
      (is (some? delegate-structural-errors)))))
