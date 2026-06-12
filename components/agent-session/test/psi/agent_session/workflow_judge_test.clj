(ns psi.agent-session.workflow-judge-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-judge :as workflow-judge]
   [psi.session-persistence.core]
   [psi.workflow-runtime.execution-adapter :as workflow-execution-adapter]
   [psi.workflow-runtime.turn-execution-contract]))

(def step-order ["step-1-plan" "step-2-build" "step-3-review"])

(declare structured-judge-test-ctx
         structured-review-judge-spec
         structured-review-routing-table
         structured-review-step-runs)

(deftest execute-judge-successful-match-test
  (testing "judge matches on first attempt"
    (let [created-sessions* (atom [])
          prompts* (atom [])
          judge-spec {:prompt "APPROVED or REVISE?"
                      :system-prompt "You are a judge."
                      :projection :full}
          routing-table {"APPROVED" {:goto :next}
                         "REVISE"   {:goto "step-2-build" :max-iterations 3}}
          step-runs {"step-1-plan"   {:step-id "step-1-plan" :attempts [] :iteration-count 1}
                     "step-2-build"  {:step-id "step-2-build" :attempts [] :iteration-count 1}
                     "step-3-review" {:step-id "step-3-review" :attempts [] :iteration-count 1}}
          ctx {workflow-execution-adapter/adapter-key
               (workflow-execution-adapter/create
                {:create-child-session! (fn [_ctx _parent opts]
                                          (swap! created-sessions* conj opts)
                                          {:psi.agent-session/session-id (:child-session-id opts)})})}]
      (with-redefs [psi.session-persistence.core/messages-from-entries-in
                    (fn [_ctx _sid]
                      [{:role "user" :content "Build it"}
                       {:role "assistant" :content [{:type :text :text "Done building."}]}])
                    psi.workflow-runtime.turn-execution-contract/execute-judge-turn!
                    (fn [_ctx sid text]
                      (swap! prompts* conj {:session-id sid :text text})
                      {:status :ok
                       :session-id sid
                       :turn-outcome :turn.outcome/stop
                       :assistant-message {:role "assistant" :content [{:type :text :text "APPROVED"}]}
                       :assistant-text "APPROVED"
                       :execution-result {:execution-result/session-id sid}})]
        (let [result (workflow-judge/execute-judge!
                      ctx "parent-1" "actor-1" judge-spec routing-table
                      {:current-step-id "step-3-review"
                       :step-order step-order
                       :step-runs step-runs})]
          (is (string? (:judge-session-id result)))
          (is (= "APPROVED" (:judge-output result)))
          (is (= "APPROVED" (:judge-event result)))
          (is (= {:action :complete} (:routing-result result)))
          (is (= [] (:tool-ids (first @created-sessions*))))
          (is (= "You are a judge." (:system-prompt (first @created-sessions*))))
          (is (= {:child-session-id (:child-session-id (first @created-sessions*))
                  :session-name "workflow judge"
                  :system-prompt "You are a judge."
                  :tool-ids []
                  :thinking-level :off
                  :preloaded-messages [{:role "user" :content "Build it"}
                                       {:role "assistant" :content [{:type :text :text "Done building."}]}]
                  :workflow-owned? true}
                 (first @created-sessions*)))
          (is (= 1 (count @prompts*)))
          (is (= "APPROVED or REVISE?" (:text (first @prompts*)))))))))

(deftest execute-judge-rechecks-cancellation-before-no-match-retry-test
  ;; Regression for task 225 implementation review pass 5: cancellation after a
  ;; no-match judge response must not start another ordinary judge turn for the
  ;; retry prompt.
  (let [prompt-count* (atom 0)
        stopped-calls* (atom 0)
        ctx {workflow-execution-adapter/adapter-key
             (workflow-execution-adapter/create
              {:create-child-session! (fn [_ctx _parent opts]
                                        {:psi.agent-session/session-id (:child-session-id opts)})})
             :workflow-judge-messages-fn (fn [_ctx _sid] [])
             :workflow-execute-judge-turn-fn
             (fn [_ctx _sid _text]
               (swap! prompt-count* inc)
               {:status :ok
                :session-id "judge"
                :assistant-message {:role "assistant" :content [{:type :text :text "MAYBE"}]}
                :assistant-text "MAYBE"
                :execution-result {}})}
        judge-spec {:prompt "APPROVED or REVISE?"
                    :projection :none}
        routing-table {"APPROVED" {:goto :next}}
        step-runs {"step-3-review" {:step-id "step-3-review" :attempts [] :iteration-count 1}}
        ex (try
             (workflow-judge/execute-judge!
              ctx "parent-1" "actor-1" judge-spec routing-table
              {:current-step-id "step-3-review"
               :step-order step-order
               :step-runs step-runs
               :stopped? #(>= (swap! stopped-calls* inc) 5)})
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is (= :workflow-stopped (:reason (ex-data ex))))
    (is (= 1 @prompt-count*)
        "cancellation between no-match judge attempts must prevent the retry turn")))

(deftest execute-judge-rechecks-cancellation-before-structured-output-retry-test
  ;; Regression for task 225 implementation review pass 5: cancellation after an
  ;; invalid structured judge response must not start another ordinary judge turn
  ;; for the structured-output retry prompt.
  (let [prompt-count* (atom 0)
        stopped-calls* (atom 0)
        ctx (assoc (structured-judge-test-ctx)
                   :workflow-judge-messages-fn (fn [_ctx _sid] [])
                   :workflow-execute-judge-turn-fn
                   (fn [_ctx _sid _text opts]
                     (is (some? opts))
                     (swap! prompt-count* inc)
                     {:status :ok
                      :session-id "judge"
                      :assistant-text "APPROVED"}))
        ex (try
             (workflow-judge/execute-judge!
              ctx "parent-1" "actor-1"
              structured-review-judge-spec structured-review-routing-table
              {:current-step-id "step-3-review"
               :step-order step-order
               :step-runs structured-review-step-runs
               :stopped? #(>= (swap! stopped-calls* inc) 5)})
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is (= :workflow-stopped (:reason (ex-data ex))))
    (is (= 1 @prompt-count*)
        "cancellation between structured judge attempts must prevent the retry turn")))

(deftest execute-judge-retry-then-match-test
  (testing "judge retries on no-match then matches"
    (let [prompt-count* (atom 0)
          ctx {workflow-execution-adapter/adapter-key
               (workflow-execution-adapter/create
                {:create-child-session! (fn [_ctx _parent opts]
                                          {:psi.agent-session/session-id (:child-session-id opts)})})}
          judge-spec {:prompt "APPROVED or REVISE?"
                      :projection :none}
          routing-table {"APPROVED" {:goto :next}
                         "REVISE"   {:goto "step-2-build" :max-iterations 3}}
          step-runs {"step-2-build"  {:step-id "step-2-build" :attempts [] :iteration-count 1}
                     "step-3-review" {:step-id "step-3-review" :attempts [] :iteration-count 1}}]
      (with-redefs [psi.session-persistence.core/messages-from-entries-in
                    (fn [_ctx _sid] [])
                    psi.workflow-runtime.turn-execution-contract/execute-judge-turn!
                    (fn [_ctx sid _text]
                      (swap! prompt-count* inc)
                      (if (<= @prompt-count* 1)
                        {:status :ok
                         :session-id sid
                         :turn-outcome :turn.outcome/stop
                         :assistant-message {:role "assistant" :content [{:type :text :text "I think it looks good"}]}
                         :assistant-text "I think it looks good"
                         :execution-result {:execution-result/session-id sid}}
                        {:status :ok
                         :session-id sid
                         :turn-outcome :turn.outcome/stop
                         :assistant-message {:role "assistant" :content [{:type :text :text "APPROVED"}]}
                         :assistant-text "APPROVED"
                         :execution-result {:execution-result/session-id sid}}))]
        (let [result (workflow-judge/execute-judge!
                      ctx "parent-1" "actor-1" judge-spec routing-table
                      {:current-step-id "step-3-review"
                       :step-order ["step-2-build" "step-3-review"]
                       :step-runs step-runs})]
          (is (= "APPROVED" (:judge-output result)))
          (is (= "APPROVED" (:judge-event result)))
          (is (= {:action :complete} (:routing-result result)))
          (is (= 2 @prompt-count*)))))))

(deftest execute-judge-llm-spec-contributions-test
  (testing "judge derives prompt from :session :contributions when :prompt key is absent"
    (let [prompts* (atom [])
          ctx {workflow-execution-adapter/adapter-key
               (workflow-execution-adapter/create
                {:create-child-session! (fn [_ctx _parent opts]
                                          {:psi.agent-session/session-id (:child-session-id opts)})})}
          ;; Compiled :llm judge spec — no :prompt key, contributions hold the text
          judge-spec {:type :llm
                      :session {:contributions [{:type :template
                                                 :text "Respond with exactly one word: REPEAT or DONE."
                                                 :vars {}}]}}
          routing-table {"REPEAT" {:goto "step-1" :max-iterations 3}
                         "DONE"   {:goto :next}}
          step-runs {"step-1" {:step-id "step-1" :attempts [] :iteration-count 1}
                     "step-2" {:step-id "step-2" :attempts [] :iteration-count 1}}]
      (with-redefs [psi.session-persistence.core/messages-from-entries-in
                    (fn [_ctx _sid] [])
                    psi.workflow-runtime.turn-execution-contract/execute-judge-turn!
                    (fn [_ctx sid text]
                      (swap! prompts* conj {:session-id sid :text text})
                      {:status :ok
                       :session-id sid
                       :turn-outcome :turn.outcome/stop
                       :assistant-message {:role "assistant" :content [{:type :text :text "DONE"}]}
                       :assistant-text "DONE"
                       :execution-result {:execution-result/session-id sid}})]
        (let [result (workflow-judge/execute-judge!
                      ctx "parent-1" "actor-1" judge-spec routing-table
                      {:current-step-id "step-2"
                       :step-order ["step-1" "step-2"]
                       :step-runs step-runs})]
          (is (= "DONE" (:judge-output result)))
          (is (= "DONE" (:judge-event result)))
          (is (= {:action :complete} (:routing-result result)))
          (is (= 1 (count @prompts*)))
          ;; Key assertion: the contribution text was used as the prompt, not nil
          (is (= "Respond with exactly one word: REPEAT or DONE."
                 (:text (first @prompts*)))))))))

(deftest execute-judge-retry-exhaustion-test
  (testing "judge retries exhausted — returns no-match routing"
    (let [prompt-count* (atom 0)
          ctx {workflow-execution-adapter/adapter-key
               (workflow-execution-adapter/create
                {:create-child-session! (fn [_ctx _parent opts]
                                          {:psi.agent-session/session-id (:child-session-id opts)})})}
          judge-spec {:prompt "APPROVED or REVISE?"
                      :projection :none}
          routing-table {"APPROVED" {:goto :next}
                         "REVISE"   {:goto "step-2-build" :max-iterations 3}}
          step-runs {"step-2-build"  {:step-id "step-2-build" :attempts [] :iteration-count 1}
                     "step-3-review" {:step-id "step-3-review" :attempts [] :iteration-count 1}}]
      (with-redefs [psi.session-persistence.core/messages-from-entries-in
                    (fn [_ctx _sid] [])
                    psi.workflow-runtime.turn-execution-contract/execute-judge-turn!
                    (fn [_ctx sid _text]
                      (swap! prompt-count* inc)
                      {:status :ok
                       :session-id sid
                       :turn-outcome :turn.outcome/stop
                       :assistant-message {:role "assistant" :content [{:type :text :text "hmm not sure"}]}
                       :assistant-text "hmm not sure"
                       :execution-result {:execution-result/session-id sid}})]
        (let [result (workflow-judge/execute-judge!
                      ctx "parent-1" "actor-1" judge-spec routing-table
                      {:current-step-id "step-3-review"
                       :step-order ["step-2-build" "step-3-review"]
                       :step-runs step-runs})]
          (is (= "hmm not sure" (:judge-output result)))
          (is (nil? (:judge-event result)))
          (is (= {:action :no-match} (:routing-result result)))
          (is (= 3 @prompt-count*)))))))

(deftest execute-judge-invalid-request-fails-locally-test
  (testing "malformed judge child-session requests fail at the shared contract boundary"
    (let [ctx {workflow-execution-adapter/adapter-key
               (workflow-execution-adapter/create
                {:create-child-session! (fn [_ctx _parent _opts]
                                          (throw (ex-info "should not be called" {})))})}
          ex (try
               (with-redefs [psi.session-persistence.core/messages-from-entries-in
                             (fn [_ctx _sid] [])]
                 (workflow-judge/execute-judge!
                  ctx
                  "parent-1"
                  "actor-1"
                  {:prompt "APPROVED or REVISE?"
                   :system-prompt [:not-a-string]
                   :projection :none}
                  {"APPROVED" {:goto :next}}
                  {:current-step-id "step-1"
                   :step-order ["step-1"]
                   :step-runs {"step-1" {:step-id "step-1" :attempts [] :iteration-count 1}}}))
               nil
               (catch clojure.lang.ExceptionInfo ex
                 ex))]
      (is (some? ex))
      (is (= :workflow-child-session-create (:contract (ex-data ex))))
      (is (= :request (:stage (ex-data ex))))
      (is (= :psi.agent-session.workflow-judge/execute-judge!
             (:caller (ex-data ex)))))))

(deftest execute-judge-invalid-result-fails-locally-test
  (testing "malformed judge child-session create results fail at the shared contract boundary"
    (let [ctx {workflow-execution-adapter/adapter-key
               (workflow-execution-adapter/create
                {:create-child-session! (fn [_ctx _parent _opts]
                                          {:session-id "judge-1"})})}
          ex (try
               (with-redefs [psi.session-persistence.core/messages-from-entries-in
                             (fn [_ctx _sid] [])]
                 (workflow-judge/execute-judge!
                  ctx
                  "parent-1"
                  "actor-1"
                  {:prompt "APPROVED or REVISE?"
                   :projection :none}
                  {"APPROVED" {:goto :next}}
                  {:current-step-id "step-1"
                   :step-order ["step-1"]
                   :step-runs {"step-1" {:step-id "step-1" :attempts [] :iteration-count 1}}}))
               nil
               (catch clojure.lang.ExceptionInfo ex
                 ex))]
      (is (some? ex))
      (is (= :workflow-child-session-create (:contract (ex-data ex))))
      (is (= :result (:stage (ex-data ex))))
      (is (= :psi.agent-session.workflow-judge/execute-judge!
             (:caller (ex-data ex)))))))

(def structured-review-judge-spec
  {:type :llm
   :session {:contributions [{:type :template
                              :text "Return review JSON"
                              :vars {}}]}
   :outputs {:review {:source :judge/structured-output
                      :mode :structured
                      :schema-id :psi.workflow/judge-review-result
                      :schema-version 1
                      :schema [:map
                               [:decision [:enum :clear :needs-work :unclear]]
                               [:issues [:vector [:map
                                                  [:severity [:enum :blocking :minor]]
                                                  [:kind [:enum :ambiguity :inconsistency :missing-acceptance :scope-drift]]
                                                  [:description :string]
                                                  [:evidence :string]
                                                  [:suggested-change :string]]]]
                               [:confidence [:double {:min 0.0 :max 1.0}]]]
                      :json-schema {:type "object"
                                    :required ["decision" "issues" "confidence"]
                                    :properties {"decision" {:type "string"}
                                                 "issues" {:type "array"}
                                                 "confidence" {:type "number"}}}}}
   :projection :none})

(defn- structured-judge-test-ctx []
  {workflow-execution-adapter/adapter-key
   (workflow-execution-adapter/create
    {:create-child-session! (fn [_ctx _parent opts]
                              {:psi.agent-session/session-id (:child-session-id opts)})})})

(def structured-review-routing-table
  {:clear {:goto :done}
   :needs-work {:goto "step-2-build" :max-iterations 3}})

(def structured-review-step-runs
  {"step-2-build" {:step-id "step-2-build" :attempts [] :iteration-count 1}
   "step-3-review" {:step-id "step-3-review" :attempts [] :iteration-count 1}})

(deftest execute-judge-structured-output-test
  ;; Tests LLM judges can return a schema-validated local structured output and
  ;; route from its explicit decision field without prose matching.
  (testing "structured judge output validates and routes by decision"
    (let [turn-opts* (atom nil)]
      (with-redefs [psi.session-persistence.core/messages-from-entries-in
                    (fn [_ctx _sid] [])
                    psi.workflow-runtime.turn-execution-contract/execute-judge-turn!
                    (fn [_ctx sid _text opts]
                      (reset! turn-opts* opts)
                      (let [ai-structured-output {:strategy :prompted-json
                                                  :source :prompted-json/text
                                                  :payload {"decision" "clear"
                                                            "issues" []
                                                            "confidence" 0.8}
                                                  :raw-payload "{\"decision\":\"clear\",\"issues\":[],\"confidence\":0.8}"}]
                        {:status :ok
                         :session-id sid
                         :assistant-text "{\"decision\":\"clear\",\"issues\":[],\"confidence\":0.8}"
                         :structured-output ai-structured-output}))]
        (let [result (workflow-judge/execute-judge!
                      (structured-judge-test-ctx) "parent-1" "actor-1"
                      structured-review-judge-spec structured-review-routing-table
                      {:current-step-id "step-3-review"
                       :step-order step-order
                       :step-runs structured-review-step-runs})]
          (is (= :clear (:judge-event result)))
          (is (= {:action :complete} (:routing-result result)))
          (is (= :valid (get-in result [:judge-output :review :structured-output :status])))
          (is (= :clear (get-in result [:judge-output :review :structured-output :value :decision])))
          (is (= {:structured-output {:schema-id :psi.workflow/judge-review-result
                                      :schema-version 1
                                      :json-schema {:type "object"
                                                    :required ["decision" "issues" "confidence"]
                                                    :properties {"decision" {:type "string"}
                                                                 "issues" {:type "array"}
                                                                 "confidence" {:type "number"}}}
                                      :strategy-preference :provider-native
                                      :fallback-allowed? true
                                      :strict? true}}
                 @turn-opts*)))))))

(deftest execute-judge-missing-turn-result-structured-output-fails-test
  ;; Tests that when a turn result has no :structured-output metadata but the
  ;; assistant text is valid JSON matching the schema, the judge routes
  ;; successfully by falling back to parse-json-value (plain-text fallback,
  ;; always {:ok? true}).  The old contract (missing metadata → :invalid with
  ;; :missing-structured-output error) was removed when workflow_judge.clj
  ;; switched from missing-ai-structured-output-result to output-result, which
  ;; enables plain-text judge responses (e.g. "DONE"/"REPEAT") to route without
  ;; requiring provider-native structured-output metadata.
  (testing "structured judge routes successfully when metadata absent but assistant text is valid JSON"
    (with-redefs [psi.session-persistence.core/messages-from-entries-in
                  (fn [_ctx _sid] [])
                  psi.workflow-runtime.turn-execution-contract/execute-judge-turn!
                  (fn [_ctx sid _text _opts]
                    {:status :ok
                     :session-id sid
                     :assistant-text "{\"decision\":\"clear\",\"issues\":[],\"confidence\":0.9}"})]
      (let [result (workflow-judge/execute-judge!
                    (structured-judge-test-ctx) "parent-1" "actor-1"
                    structured-review-judge-spec structured-review-routing-table
                    {:current-step-id "step-3-review"
                     :step-order step-order
                     :step-runs structured-review-step-runs})
            envelope (get-in result [:judge-output :review :structured-output])]
        (is (= :clear (:judge-event result)))
        (is (= {:action :complete} (:routing-result result)))
        (is (= :valid (:status envelope)))
        (is (= :prompted-json (:strategy envelope)))
        (is (= :clear (get-in envelope [:value :decision])))
        (is (= [] (get-in envelope [:value :issues])))))))

(deftest execute-judge-structured-output-success-uses-turn-result-metadata-test
  ;; Tests successful structured judge envelopes are built from the top-level
  ;; bounded turn-result :structured-output seam, preserving the actual strategy,
  ;; source, and payload instead of parsing prose-only output.
  (testing "structured judge records actual prompted-json metadata and payload from turn result"
    (let [ai-structured-output {:strategy :prompted-json
                                :source :prompted-json/text
                                :fallback-used? true
                                :payload {"decision" "needs-work"
                                          "issues" [{"severity" "blocking"
                                                     "kind" "ambiguity"
                                                     "description" "unclear"
                                                     "evidence" "design"
                                                     "suggested-change" "clarify"}]
                                          "confidence" 0.7}
                                :raw-payload "{\"decision\":\"needs-work\"}"}]
      (with-redefs [psi.session-persistence.core/messages-from-entries-in
                    (fn [_ctx _sid] [])
                    psi.workflow-runtime.turn-execution-contract/execute-judge-turn!
                    (fn [_ctx sid _text _opts]
                      {:status :ok
                       :session-id sid
                       :assistant-text "this prose is not JSON and must not be the source"
                       :structured-output ai-structured-output
                       :execution-result {:execution-result/structured-output ai-structured-output}})]
        (let [result (workflow-judge/execute-judge!
                      (structured-judge-test-ctx) "parent-1" "actor-1"
                      structured-review-judge-spec structured-review-routing-table
                      {:current-step-id "step-3-review"
                       :step-order step-order
                       :step-runs structured-review-step-runs})
              envelope (get-in result [:judge-output :review :structured-output])]
          (is (= :needs-work (:judge-event result)))
          (is (= {:action :goto :target "step-2-build"} (:routing-result result)))
          (is (= :valid (:status envelope)))
          (is (= :prompted-json (:strategy envelope)))
          (is (= :prompted-json/text (:source envelope)))
          (is (true? (:fallback-used? envelope)))
          (is (= (:payload ai-structured-output) (:payload envelope)))
          (is (= (:raw-payload ai-structured-output) (:raw-payload envelope)))
          (is (= :needs-work (get-in envelope [:value :decision])))
          (is (= :blocking (get-in envelope [:value :issues 0 :severity]))))))))

(deftest execute-judge-unsupported-structured-output-fails-test
  ;; Tests fallback-forbidden AI strategy failures for structured judges return
  ;; the terminal judge failure surface without prose no-match retries.
  (testing "unsupported structured judge output fails with machine-readable reason"
    (let [turn-prompts* (atom [])]
      (with-redefs [psi.session-persistence.core/messages-from-entries-in
                    (fn [_ctx _sid] [])
                    psi.workflow-runtime.turn-execution-contract/execute-judge-turn!
                    (fn [_ctx sid text _opts]
                      (swap! turn-prompts* conj text)
                      {:status :error
                       :session-id sid
                       :assistant-text ""
                       :structured-output {:strategy :unsupported
                                           :reason :unsupported-structured-output
                                           :resolved-model {:provider "local" :id "fallback-only"}}
                       :failure {:reason :unsupported-structured-output
                                 :message "Resolved model cannot provide native structured output"}})]
        (let [result (workflow-judge/execute-judge!
                      (structured-judge-test-ctx) "parent-1" "actor-1"
                      (assoc-in structured-review-judge-spec
                                [:outputs :review :require-provider-native?]
                                true)
                      structured-review-routing-table
                      {:current-step-id "step-3-review"
                       :step-order step-order
                       :step-runs structured-review-step-runs})]
          (is (nil? (:judge-event result)))
          (is (= {:action :fail
                  :reason :unsupported-structured-output
                  :output-key :review}
                 (select-keys (:routing-result result) [:action :reason :output-key])))
          (is (= {:strategy :unsupported
                  :reason :unsupported-structured-output
                  :resolved-model {:provider "local" :id "fallback-only"}}
                 (get-in result [:routing-result :details :structured-output])))
          (is (= ["Return review JSON"] @turn-prompts*)))))))

(deftest execute-judge-fallback-none-unsupported-structured-output-fails-test
  ;; Tests :fallback :none uses the same terminal judge failure surface as
  ;; required-native when the AI layer reports unsupported structured output.
  (testing "fallback none unsupported structured judge output fails with machine-readable reason"
    (let [turn-prompts* (atom [])
          turn-opts* (atom nil)]
      (with-redefs [psi.session-persistence.core/messages-from-entries-in
                    (fn [_ctx _sid] [])
                    psi.workflow-runtime.turn-execution-contract/execute-judge-turn!
                    (fn [_ctx sid text opts]
                      (swap! turn-prompts* conj text)
                      (reset! turn-opts* opts)
                      {:status :error
                       :session-id sid
                       :assistant-text ""
                       :structured-output {:strategy :unsupported
                                           :reason :unsupported-structured-output
                                           :resolved-model {:provider "local" :id "unsupported"}}
                       :failure {:reason :unsupported-structured-output
                                 :message "Resolved model cannot provide structured output without fallback"}})]
        (let [result (workflow-judge/execute-judge!
                      (structured-judge-test-ctx) "parent-1" "actor-1"
                      (assoc-in structured-review-judge-spec
                                [:outputs :review :fallback]
                                :none)
                      structured-review-routing-table
                      {:current-step-id "step-3-review"
                       :step-order step-order
                       :step-runs structured-review-step-runs})]
          (is (nil? (:judge-event result)))
          (is (= {:action :fail
                  :reason :unsupported-structured-output
                  :output-key :review}
                 (select-keys (:routing-result result) [:action :reason :output-key])))
          (is (= {:strategy :unsupported
                  :reason :unsupported-structured-output
                  :resolved-model {:provider "local" :id "unsupported"}}
                 (get-in result [:routing-result :details :structured-output])))
          (is (false? (get-in @turn-opts* [:structured-output :fallback-allowed?])))
          (is (not (get-in @turn-opts* [:structured-output :require-provider-native?])))
          (is (= ["Return review JSON"] @turn-prompts*)))))))

(deftest execute-judge-invalid-structured-output-retry-then-succeeds-test
  ;; Tests structured-output validation failures retry with the original opts and
  ;; can recover to a valid routed judge result.
  (testing "structured judge output retries with structured opts and routes when retry is valid"
    (let [turns* (atom [])]
      (with-redefs [psi.session-persistence.core/messages-from-entries-in
                    (fn [_ctx _sid] [])
                    psi.workflow-runtime.turn-execution-contract/execute-judge-turn!
                    (fn [_ctx sid text opts]
                      (swap! turns* conj {:text text :opts opts})
                      (if (= 1 (count @turns*))
                        {:status :ok
                         :session-id sid
                         :assistant-text "APPROVED"}
                        (let [ai-structured-output {:strategy :prompted-json
                                                    :source :prompted-json/text
                                                    :payload {"decision" "clear"
                                                              "issues" []
                                                              "confidence" 0.8}
                                                    :raw-payload "{\"decision\":\"clear\",\"issues\":[],\"confidence\":0.8}"}]
                          {:status :ok
                           :session-id sid
                           :assistant-text "{\"decision\":\"clear\",\"issues\":[],\"confidence\":0.8}"
                           :structured-output ai-structured-output})))]
        (let [result (workflow-judge/execute-judge!
                      (structured-judge-test-ctx) "parent-1" "actor-1"
                      structured-review-judge-spec structured-review-routing-table
                      {:current-step-id "step-3-review"
                       :step-order step-order
                       :step-runs structured-review-step-runs})]
          (is (= :clear (:judge-event result)))
          (is (= {:action :complete} (:routing-result result)))
          (is (= 2 (count @turns*)))
          (is (= (get-in (first @turns*) [:opts :structured-output])
                 (get-in (second @turns*) [:opts :structured-output])))
          (is (re-find #"did not match any expected signal"
                       (:text (second @turns*)))))))))

(deftest execute-judge-invalid-structured-output-fails-locally-test
  (testing "invalid structured judge output retries and then fails locally without prose routing"
    (let [turns* (atom [])]
      (with-redefs [psi.session-persistence.core/messages-from-entries-in
                    (fn [_ctx _sid] [])
                    psi.workflow-runtime.turn-execution-contract/execute-judge-turn!
                    (fn
                      ([_ctx sid text]
                       (swap! turns* conj {:text text :opts nil})
                       {:status :ok
                        :session-id sid
                        :assistant-text "APPROVED"})
                      ([_ctx sid text opts]
                       (swap! turns* conj {:text text :opts opts})
                       {:status :ok
                        :session-id sid
                        :assistant-text "APPROVED"}))]
        (let [result (workflow-judge/execute-judge!
                      (structured-judge-test-ctx) "parent-1" "actor-1"
                      structured-review-judge-spec structured-review-routing-table
                      {:current-step-id "step-3-review"
                       :step-order step-order
                       :step-runs structured-review-step-runs})
              turn-prompts (mapv :text @turns*)
              structured-output-opts (mapv #(get-in % [:opts :structured-output]) @turns*)]
          (is (nil? (:judge-event result)))
          (is (= {:action :fail
                  :reason :invalid-structured-output
                  :output-key :review}
                 (select-keys (:routing-result result) [:action :reason :output-key])))
          (is (= :invalid
                 (get-in result [:routing-result :details :structured-output :status])))
          (is (= :invalid (get-in result [:judge-output :review :structured-output :status])))
          (is (= 3 (count @turns*)))
          (is (= "Return review JSON" (first turn-prompts)))
          (is (every? #(re-find #"did not match any expected signal" %)
                      (rest turn-prompts)))
          (is (every? some? structured-output-opts))
          (is (apply = structured-output-opts))
          (is (= (get-in (first @turns*) [:opts :structured-output :json-schema])
                 {:type "object"
                  :required ["decision" "issues" "confidence"]
                  :properties {"decision" {:type "string"}
                               "issues" {:type "array"}
                               "confidence" {:type "number"}}})))))))

(deftest execute-judge-schema-valid-negative-decision-routes-test
  (testing "schema-valid negative decision drives the configured non-clear branch"
    (with-redefs [psi.session-persistence.core/messages-from-entries-in
                  (fn [_ctx _sid] [])
                  psi.workflow-runtime.turn-execution-contract/execute-judge-turn!
                  (fn
                    ([_ctx sid _text]
                     {:status :ok
                      :session-id sid
                      :assistant-text "{\"decision\":\"needs-work\",\"issues\":[{\"severity\":\"blocking\",\"kind\":\"ambiguity\",\"description\":\"unclear\",\"evidence\":\"design\",\"suggested-change\":\"clarify\"}],\"confidence\":0.8}"})
                    ([_ctx sid _text _opts]
                     (let [ai-structured-output {:strategy :prompted-json
                                                 :source :prompted-json/text
                                                 :payload {"decision" "needs-work"
                                                           "issues" [{"severity" "blocking"
                                                                      "kind" "ambiguity"
                                                                      "description" "unclear"
                                                                      "evidence" "design"
                                                                      "suggested-change" "clarify"}]
                                                           "confidence" 0.8}
                                                 :raw-payload "{\"decision\":\"needs-work\"}"}]
                       {:status :ok
                        :session-id sid
                        :assistant-text "{\"decision\":\"needs-work\",\"issues\":[{\"severity\":\"blocking\",\"kind\":\"ambiguity\",\"description\":\"unclear\",\"evidence\":\"design\",\"suggested-change\":\"clarify\"}],\"confidence\":0.8}"
                        :structured-output ai-structured-output})))]
      (let [result (workflow-judge/execute-judge!
                    (structured-judge-test-ctx) "parent-1" "actor-1"
                    structured-review-judge-spec structured-review-routing-table
                    {:current-step-id "step-3-review"
                     :step-order step-order
                     :step-runs structured-review-step-runs})]
        (is (= :needs-work (:judge-event result)))
        (is (= {:action :goto :target "step-2-build"} (:routing-result result)))
        (is (= :needs-work (get-in result [:judge-output :review :structured-output :value :decision])))))))

(def string-enum-judge-spec
  ;; [:enum "REPEAT" "DONE"] — structured-output value is a plain string, not a map.
  ;; Bug 3 regression: old code did (:decision raw-value) which returns nil for strings.
  {:type :llm
   :session {:contributions [{:type :template
                              :text "Respond with exactly one word: REPEAT or DONE."
                              :vars {}}]}
   :outputs {:routing-result
             {:source :judge/structured-output
              :mode :structured
              :schema-id :psi.workflow/judge-routing-result
              :schema-version 1
              :schema [:enum "REPEAT" "DONE"]
              :json-schema {:type "string" :enum ["REPEAT" "DONE"]}}}
   :projection :none})

(deftest execute-judge-string-enum-structured-output-test
  ;; Regression test for bug 3: execute-judge! extracted :decision from the structured
  ;; output value, which silently returns nil for [:enum "REPEAT" "DONE"] schemas where
  ;; the validated value is a plain string.  The fix uses the string directly when the
  ;; value is not a map.
  (testing "string-enum structured output routes using the string value directly"
    (with-redefs [psi.session-persistence.core/messages-from-entries-in
                  (fn [_ctx _sid] [])
                  psi.workflow-runtime.turn-execution-contract/execute-judge-turn!
                  (fn [_ctx sid _text _opts]
                    {:status :ok
                     :session-id sid
                     :assistant-text "\"DONE\""
                     :structured-output {:strategy :prompted-json
                                         :source :prompted-json/text
                                         :payload "DONE"
                                         :raw-payload "\"DONE\""}})]
      (let [result (workflow-judge/execute-judge!
                    (structured-judge-test-ctx) "parent-1" "actor-1"
                    string-enum-judge-spec
                    {"REPEAT" {:goto "step-1" :max-iterations 3}
                     "DONE"   {:goto :next}}
                    {:current-step-id "step-2"
                     :step-order ["step-1" "step-2"]
                     :step-runs {"step-1" {:step-id "step-1" :attempts [] :iteration-count 1}
                                 "step-2" {:step-id "step-2" :attempts [] :iteration-count 1}}})]
        ;; Pre-fix: judge-event was nil because (:decision "DONE") => nil
        (is (= "DONE" (:judge-event result)))
        (is (= {:action :complete} (:routing-result result)))
        (is (= :valid (get-in result [:judge-output :routing-result :structured-output :status])))
        (is (= "DONE" (get-in result [:judge-output :routing-result :structured-output :value])))))))
