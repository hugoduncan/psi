(ns psi.workflow-step-materialization.source-resolution-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.workflow-step-materialization.source-resolution :as workflow-source-resolution]
   [psi.workflow-runtime.core :as workflow-runtime]))

(def mixed-form-definition
  {:steps [{:name "discover"
            :type :invoke
            :operation "github/search"
            :args {:repo {:from :workflow-input :path [:repo]}
                   :labels {:from :workflow-input :path [:labels]}
                   :state "open"}}
           {:name "report"
            :type :session
            :contributions [{:type :source
                             :from :workflow-original}
                            {:type :template
                             :text "Review {{issues}} / {{summary}}"
                             :vars {"issues" {:from {:step "discover" :output :data}
                                              :path [:issues]}
                                    "summary" {:from {:step "discover" :yield :data}
                                               :path [:summary]}}}]}
           {:name "report-call"
            :type :delegate
            :target "builder"
            :prompt-string {:type :template
                            :text "Ship {{issues}}"
                            :vars {"issues" {:from {:step "discover" :output :data}
                                             :path [:issues]}}}
            :context [{:type :source
                       :from :workflow-original}
                      {:type :source
                       :from {:step "discover" :output :data}
                       :path [:issues]}]}]})

(defn- workflow-run-with-results
  []
  (let [[state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                       {:definition mixed-form-definition
                                                        :run-id "run-mixed"
                                                        :workflow-input {:repo "org/repo"
                                                                         :labels ["bug"]
                                                                         :original {:ticket 123 :request "Please triage"}}})
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "discover" :accepted-result]
                         {:outcome :ok
                          :outputs {:data {:issues ["i-1" "i-2"]
                                           :summary "2 issues found"}
                                    :summary "2 issues found"}})]
    (workflow-runtime/workflow-run-in state3 run-id)))

(deftest resolve-invoke-args-shares-canonical-source-spec-semantics-test
  (let [run (workflow-run-with-results)
        invoke-step (first (get-in run [:effective-definition :canonical-ir :steps]))]
    (is (= {:repo "org/repo"
            :labels ["bug"]
            :state "open"}
           (workflow-source-resolution/resolve-invoke-args run (get-in invoke-step [:invoke :args]))))))

(deftest render-template-contribution-shares-canonical-source-spec-semantics-test
  (let [run (workflow-run-with-results)
        contribution (-> run :effective-definition :canonical-ir :steps second :session :contributions second)]
    (is (= "Review [\"i-1\" \"i-2\"] / 2 issues found"
           (workflow-source-resolution/render-template-contribution run contribution)))))

(deftest resolve-delegate-prompt-and-context-share-canonical-source-spec-semantics-test
  (let [run (workflow-run-with-results)
        delegate-step (nth (get-in run [:effective-definition :canonical-ir :steps]) 2)
        rendered-prompt (workflow-source-resolution/render-delegate-prompt-string run (get-in delegate-step [:delegate :prompt-string]))
        resolved-context (workflow-source-resolution/resolve-delegate-context run (get-in delegate-step [:delegate :context]))]
    (is (= "Ship [\"i-1\" \"i-2\"]"
           rendered-prompt))
    (is (= [{:ticket 123 :request "Please triage"}
            ["i-1" "i-2"]]
           resolved-context))
    (let [[state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                         {:definition {:steps [{:name "callee"
                                                                                :type :session
                                                                                :contributions [{:type :source
                                                                                                 :from :workflow-input}
                                                                                                {:type :source
                                                                                                 :from :workflow-original}]}]}
                                                          :run-id "run-delegate-callee"
                                                          :workflow-input {:original resolved-context
                                                                           :prompt-string rendered-prompt}})
          callee-run (-> state2
                         (assoc-in [:workflows :runs run-id :workflow-input] rendered-prompt)
                         (assoc-in [:workflows :runs run-id :workflow-original] resolved-context)
                         (workflow-runtime/workflow-run-in run-id))]
      (is (= rendered-prompt
             (workflow-source-resolution/resolve-source-ref callee-run :workflow-input)))
      (is (= resolved-context
             (or (:workflow-original callee-run)
                 (workflow-source-resolution/resolve-source-ref callee-run :workflow-original)))))))

(deftest render-map-prompt-string-resolves-fields-to-map-test
  (let [run (workflow-run-with-results)
        map-prompt-string {:type :map
                           :fields {:issue_number {:from {:step "discover" :output :data}
                                                   :path [:issues 0]}
                                    :summary {:from {:step "discover" :output :data}
                                              :path [:summary]}}}
        rendered (workflow-source-resolution/render-delegate-prompt-string run map-prompt-string)]
    (is (= {:issue_number "i-1"
            :summary "2 issues found"}
           rendered))))

(deftest apply-source-spec-returns-value-literal-test
  (let [run (workflow-run-with-results)]
    (is (= "task-implementation-review"
           (workflow-source-resolution/apply-source-spec run {:value "task-implementation-review"})))
    (is (nil?
         (workflow-source-resolution/apply-source-spec run {:value nil})))
    (is (= {:nested :map}
           (workflow-source-resolution/apply-source-spec run {:value {:nested :map}})))))

(deftest render-map-prompt-string-supports-value-literal-fields-test
  (let [run (workflow-run-with-results)
        map-prompt-string {:type :map
                           :fields {:input {:from :workflow-input :path [:repo]}
                                    :skill {:value "task-implementation-review"}}}
        rendered (workflow-source-resolution/render-delegate-prompt-string run map-prompt-string)]
    (is (= {:input "org/repo"
            :skill "task-implementation-review"}
           rendered))))

(deftest source-spec-predicate-recognizes-value-literal-test
  (is (true? (workflow-source-resolution/source-spec? {:value "literal"})))
  (is (true? (workflow-source-resolution/source-spec? {:value nil})))
  (is (true? (workflow-source-resolution/source-spec? {:from :workflow-input})))
  (is (false? (workflow-source-resolution/source-spec? "not-a-map")))
  (is (false? (workflow-source-resolution/source-spec? {:other :key}))))

(defn- run-with-report-accepted-result
  [run-id accepted-result]
  (let [[state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                       {:definition {:steps [{:name "report"
                                                                              :type :session
                                                                              :contributions [{:type :template
                                                                                               :text "x"
                                                                                               :vars {}}]}]}
                                                        :run-id run-id
                                                        :workflow-input {}})
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "report" :accepted-result]
                         accepted-result)]
    (workflow-runtime/workflow-run-in state3 run-id)))

(defn- run-with-transcript
  [run-id transcript]
  (run-with-report-accepted-result
   run-id
   {:outcome :ok
    :outputs {:transcript transcript
              :final-llm-reply "Done"}}))

(defn- run-with-delegate-step-accepted-result
  [run-id accepted-result]
  (let [[state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                       {:definition {:steps [{:name "delegate-step"
                                                                              :type :delegate
                                                                              :target "builder"
                                                                              :prompt-string "Do it"
                                                                              :outputs {:handoff {:source :delegate/handoff}}
                                                                              :yields {:type :delegated}}
                                                                             {:name "report"
                                                                              :type :session
                                                                              :contributions [{:type :template
                                                                                               :text "Report {{delegated}} / {{issue}}"
                                                                                               :vars {"delegated" {:from {:step "delegate-step" :yield :text}}
                                                                                                      "issue" {:from {:step "delegate-step" :output :handoff}
                                                                                                               :path [:issue_number]}}}]}]}
                                                        :run-id run-id
                                                        :workflow-input {}})
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "delegate-step" :accepted-result]
                         accepted-result)]
    (workflow-runtime/workflow-run-in state3 run-id)))

(deftest resolve-delegate-yielded-text-from-canonical-terminal-envelope-test
  (let [run (run-with-delegate-step-accepted-result
             "run-delegate-yield"
             {:outcome :ok
              :outputs {:final-llm-reply "delegated terminal text"
                        :handoff {:issue_number "42"}
                        :result {:outcome :ok}}
              :diagnostics {:delegate {:target "builder"}}})]
    (is (= "delegated terminal text"
           (workflow-source-resolution/resolve-source-ref run {:step "delegate-step" :yield :text})))
    (is (= {:issue_number "42"}
           (workflow-source-resolution/resolve-source-ref run {:step "delegate-step" :output :handoff})))
    (is (= "Report delegated terminal text / 42"
           (workflow-source-resolution/render-template-contribution
            run
            (-> run :effective-definition :canonical-ir :steps second :session :contributions first))))))

;; ── Task 226 Slice 4 — per-prompt `:prompt` source-ref resolution ───────────

(def ^:private multi-prompt-accepted-result
  "Post-drain envelope of a named multi-prompt session step: step-level rollup
   (last prompt's reply + accumulated transcript) plus ordered per-prompt
   turn-local records under `:prompt-group-outputs`."
  {:outcome :ok
   :outputs {:final-llm-reply "ambiguity reply"
             :transcript ["msg-a" "msg-b"]
             :prompt-group-outputs
             [{:index 0 :name "architecture"
               :outputs {:final-llm-reply "architecture reply"
                         :transcript ["msg-a"]}}
              {:index 1 :name "ambiguity"
               :outputs {:final-llm-reply "ambiguity reply"
                         :transcript ["msg-a" "msg-b"]}}]}})

(deftest resolve-prompt-discriminated-per-prompt-surface-test
  (let [run (run-with-report-accepted-result
             "run-multi-prompt" multi-prompt-accepted-result)]
    ;; No `:prompt` → step-level surface (last prompt / accumulated transcript).
    (is (= "ambiguity reply"
           (workflow-source-resolution/resolve-source-ref
            run {:step "report" :output :final-llm-reply})))
    (is (= ["msg-a" "msg-b"]
           (workflow-source-resolution/resolve-source-ref
            run {:step "report" :output :transcript})))
    ;; `:prompt` → that group's turn-local surface.
    (is (= "architecture reply"
           (workflow-source-resolution/resolve-source-ref
            run {:step "report" :prompt "architecture" :output :final-llm-reply})))
    (is (= ["msg-a"]
           (workflow-source-resolution/resolve-source-ref
            run {:step "report" :prompt "architecture" :output :transcript})))
    (is (= "ambiguity reply"
           (workflow-source-resolution/resolve-source-ref
            run {:step "report" :prompt "ambiguity" :output :final-llm-reply})))))

(defn- run-with-chosen-workflow
  [run-id selected-workflow]
  (let [[state2 run-id _] (workflow-runtime/create-run
                           {:workflows {:definitions {} :runs {} :run-order []}}
                           {:definition {:steps [{:name "choose-workflow"
                                                  :type :invoke
                                                  :operation "demo/select-workflow"
                                                  :args {}}
                                                 {:name "run-selected-workflow"
                                                  :type :delegate
                                                  :target {:from {:step "choose-workflow" :output :data}
                                                           :path [:selected-workflow]}
                                                  :prompt-string "Handle the issue using the selected workflow."}]}
                            :run-id run-id
                            :workflow-input {}})
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "choose-workflow" :accepted-result]
                         {:outcome :ok
                          :outputs {:data {:selected-workflow selected-workflow}}})]
    (workflow-runtime/workflow-run-in state3 run-id)))

(deftest resolve-workflow-ref-source-spec-success-test
  (let [run (run-with-chosen-workflow "run-dynamic-target"
                                      {:type :workflow-ref
                                       :name "builder"})]
    (is (= {:type :workflow-ref :name "builder"}
           (workflow-source-resolution/resolve-workflow-ref-source-spec
            run
            {:from {:step "choose-workflow" :output :data}
             :path [:selected-workflow]})))))

(deftest resolve-workflow-ref-source-spec-rejects-plain-string-test
  (let [run (run-with-chosen-workflow "run-dynamic-target-invalid" "builder")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Dynamic delegate target must resolve to a workflow reference"
         (workflow-source-resolution/resolve-workflow-ref-source-spec
          run
          {:from {:step "choose-workflow" :output :data}
           :path [:selected-workflow]})))))

(deftest apply-source-spec-rejects-both-path-and-projection-test
  (let [run (workflow-run-with-results)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"cannot contain both `:path` and `:projection`"
         (workflow-source-resolution/apply-source-spec
          run
          {:from {:step "discover" :output :data}
           :path [:issues]
           :projection :full})))))

(deftest apply-source-spec-projects-transcript-surfaces-test
  (let [transcript [{:role "user" :content "Request"}
                    {:role "assistant" :content [{:type :text :text "Thinking"}
                                                 {:type :tool_use :id "t1" :name "read" :input {:path "x"}}]}
                    {:role "tool" :content [{:type :tool_result :tool-use-id "t1" :content "ok"}]}
                    {:role "assistant" :content [{:type :text :text "Done"}]}]
        run (run-with-transcript "run-transcript" transcript)]
    (is (= [{:role "user" :content "Request"}
            {:role "assistant" :content [{:type :text :text "Thinking"}]}
            {:role "assistant" :content [{:type :text :text "Done"}]}]
           (workflow-source-resolution/apply-source-spec
            run
            {:from {:step "report" :output :transcript}
             :projection {:type :tail :turns 1 :tool-output false}})))
    (is (= transcript
           (workflow-source-resolution/apply-source-spec
            run
            {:from {:step "report" :output :transcript}
             :projection :full})))))

(deftest apply-source-spec-projects-through-lower-owner-dropping-emptied-messages-test
  (let [transcript [{:role "user" :content "Request"}
                    {:role "assistant" :content [{:type :tool_use :id "t1" :name "read" :input {:path "x"}}]}
                    {:role "tool" :content [{:type :tool_result :tool-use-id "t1" :content "ok"}]}
                    {:role "assistant" :content [{:type :text :text "Done"}]}]
        run (run-with-transcript "run-transcript-tool-only" transcript)]
    (is (= [{:role "user" :content "Request"}
            {:role "assistant" :content [{:type :text :text "Done"}]}]
           (workflow-source-resolution/apply-source-spec
            run
            {:from {:step "report" :output :transcript}
             :projection {:type :tail :turns 1 :tool-output false}})))))

(deftest apply-source-spec-missing-nested-path-returns-nil-test
  (let [run (workflow-run-with-results)]
    (is (nil? (workflow-source-resolution/apply-source-spec
               run
               {:from {:step "discover" :output :data}
                :path [:issues 99 :missing]})))))

(deftest resolve-binding-ref-distinguishes-canonical-session-output-from-legacy-storage-key-test
  (let [run (run-with-report-accepted-result
             "run-binding-ref"
             {:outcome :ok
              :outputs {:transcript nil
                        :final-llm-reply "Done"
                        :text "legacy text"}})]
    (is (= "Done"
           (workflow-source-resolution/resolve-binding-ref
            run
            {:source :step-output
             :path ["report" :outputs :final-llm-reply]})))
    (is (= "legacy text"
           (workflow-source-resolution/resolve-binding-ref
            run
            {:source :step-output
             :path ["report" :outputs :text]})))))

(deftest resolve-binding-ref-workflow-runtime-branch-test
  (let [[state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                       {:definition {:steps [{:name "noop"
                                                                              :type :session
                                                                              :contributions [{:type :template
                                                                                               :text "x"
                                                                                               :vars {}}]}]}
                                                        :run-id "run-workflow-runtime-ref"
                                                        :workflow-input {}})
        state3 (assoc-in state2 [:workflows :runs run-id :status] :blocked)
        run (workflow-runtime/workflow-run-in state3 run-id)]
    (is (= {:run-id "run-workflow-runtime-ref"
            :status :blocked}
           {:run-id (workflow-source-resolution/resolve-binding-ref run {:source :workflow-runtime
                                                                         :path [:run-id]})
            :status (workflow-source-resolution/resolve-binding-ref run {:source :workflow-runtime
                                                                         :path [:status]})}))))
