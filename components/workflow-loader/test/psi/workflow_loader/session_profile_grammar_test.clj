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
