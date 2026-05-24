(ns psi.agent-session.workflow-judge-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-judge :as workflow-judge]
   [psi.session-persistence.core]
   [psi.workflow-runtime.execution-adapter :as workflow-execution-adapter]
   [psi.workflow-runtime.turn-execution-contract]))

(def step-order ["step-1-plan" "step-2-build" "step-3-review"])

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
          (is (= [] (:tool-defs (first @created-sessions*))))
          (is (= "You are a judge." (:system-prompt (first @created-sessions*))))
          (is (= {:child-session-id (:child-session-id (first @created-sessions*))
                  :session-name "workflow judge"
                  :system-prompt "You are a judge."
                  :tool-defs []
                  :thinking-level :off
                  :preloaded-messages [{:role "user" :content "Build it"}
                                       {:role "assistant" :content [{:type :text :text "Done building."}]}]
                  :workflow-owned? true}
                 (first @created-sessions*)))
          (is (= 1 (count @prompts*)))
          (is (= "APPROVED or REVISE?" (:text (first @prompts*)))))))))

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

(deftest execute-judge-structured-output-test
  ;; Tests LLM judges can return a schema-validated local structured output and
  ;; route from its explicit decision field without prose matching.
  (testing "structured judge output validates and routes by decision"
    (let [ctx {workflow-execution-adapter/adapter-key
               (workflow-execution-adapter/create
                {:create-child-session! (fn [_ctx _parent opts]
                                          {:psi.agent-session/session-id (:child-session-id opts)})})}
          judge-spec {:type :llm
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
                                                  [:confidence [:double {:min 0.0 :max 1.0}]]]}}
                      :projection :none}
          routing-table {:clear {:goto :done}
                         :needs-work {:goto "step-2-build" :max-iterations 3}}
          step-runs {"step-2-build" {:step-id "step-2-build" :attempts [] :iteration-count 1}
                     "step-3-review" {:step-id "step-3-review" :attempts [] :iteration-count 1}}]
      (with-redefs [psi.session-persistence.core/messages-from-entries-in
                    (fn [_ctx _sid] [])
                    psi.workflow-runtime.turn-execution-contract/execute-judge-turn!
                    (fn [_ctx sid _text]
                      {:status :ok
                       :session-id sid
                       :assistant-text "{\"decision\":\"needs-work\",\"issues\":[{\"severity\":\"blocking\",\"kind\":\"ambiguity\",\"description\":\"unclear\",\"evidence\":\"design\",\"suggested-change\":\"clarify\"}],\"confidence\":0.8}"})]
        (let [result (workflow-judge/execute-judge!
                      ctx "parent-1" "actor-1" judge-spec routing-table
                      {:current-step-id "step-3-review"
                       :step-order step-order
                       :step-runs step-runs})]
          (is (= :needs-work (:judge-event result)))
          (is (= {:action :goto :target "step-2-build"} (:routing-result result)))
          (is (= :valid (get-in result [:judge-output :review :structured-output :status])))
          (is (= :needs-work (get-in result [:judge-output :review :structured-output :value :decision]))))))))
