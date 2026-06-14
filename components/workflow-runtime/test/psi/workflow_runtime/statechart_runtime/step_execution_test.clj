(ns psi.workflow-runtime.statechart-runtime.step-execution-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.deterministic-operation-registry.registry :as deterministic-op-registry]
   [psi.workflow-runtime.execution-adapter :as execution-adapter]
   [psi.workflow-runtime.statechart-runtime.step-execution :as step-execution]
   [psi.workflow-runtime.turn-execution-contract :as turn-execution]))

(deftest operation-result->invoke-step-result-test
  (testing "ok deterministic operation results become accepted workflow step results"
    (is (= {:kind :accepted-result
            :accepted-result {:outcome :ok
                              :outputs {:data {:value 42}
                                        :result {:status :ok
                                                 :data {:value 42}
                                                 :summary "done"}
                                        :summary "done"}}}
           (step-execution/operation-result->invoke-step-result
            {:status :ok
             :data {:value 42}
             :summary "done"}))))

  (testing "error deterministic operation results become execution failures"
    (is (= {:kind :execution-error
            :execution-error {:reason :bad-input
                              :message "No repo"
                              :operation-result {:status :error
                                                 :reason :bad-input
                                                 :message "No repo"
                                                 :details {:path "/tmp"}}
                              :operation-details {:path "/tmp"}}}
           (step-execution/operation-result->invoke-step-result
            {:status :error
             :reason :bad-input
             :message "No repo"
             :details {:path "/tmp"}})))))

;; NOTE: `:session-id` is included in session-step raw outputs (execute-session-step!)
;; but requires full runtime context (ctx, parent session, execution session) to test.
;; This surface is integration-tested via workflow execution (e.g. local-logprobs workflow
;; exercises {:from {:step "run" :output :session-id}} in its invoke step).
;; A unit-level assertion is impractical here without substantial test infrastructure.

(deftest invoke-step-re-execution-uses-just-started-attempt-not-stale-snapshot-test
  ;; Regression for task 228's SECOND defect (:attempt-mismatch on REPEAT). When
  ;; an invoke step is re-executed (e.g. a REPEAT routing loop), the `:step/enter`
  ;; caller appends a fresh attempt to the live `state*` and threads its
  ;; attempt-id into `invoke-step-runtime-result`. The `workflow-run` snapshot the
  ;; caller captured BEFORE appending that attempt is stale: its latest attempt is
  ;; the PREVIOUS attempt. `invoke-step-runtime-result` must drive the
  ;; deterministic operation against the threaded just-started attempt-id, not the
  ;; stale snapshot's latest attempt — otherwise task-225's attempt-equality guard
  ;; aborts the step `:operation` with :attempt-mismatch. This is the localized
  ;; characterization companion to the first defect's
  ;; `invoke-step-operation-then-judge-operation-share-one-attempt-test`.
  (testing "re-executed invoke step operation drives the threaded attempt, not the stale snapshot's latest"
    (let [handler-calls* (atom 0)
          reg (deterministic-op-registry/create-registry)
          _ (deterministic-op-registry/register-operation-in!
             reg {:id "workflow/constant-routing"
                  :handler (fn [_]
                             (swap! handler-calls* inc)
                             {:status :ok :data {:routed? true}})})
          ;; Live state*: attempt-2 is the just-started latest live attempt.
          state* (atom {:workflows {:runs {"run-1" {:run-id "run-1"
                                                    :status :running
                                                    :step-runs {"clarity-status"
                                                                {:attempts [{:attempt-id "attempt-1"}
                                                                            {:attempt-id "attempt-2"}]}}}}}})
          ctx {:state* state*
               :deterministic-operation-registry reg}
          ;; Stale snapshot captured before attempt-2 was appended: latest = attempt-1.
          stale-workflow-run {:run-id "run-1"
                              :step-runs {"clarity-status" {:attempts [{:attempt-id "attempt-1"}]}}}
          step-def {:invoke {:operation "workflow/constant-routing"}}
          {:keys [operation-result]}
          (step-execution/invoke-step-runtime-result
           ctx nil "run-1" "clarity-status" step-def stale-workflow-run "attempt-2")
          attempts (get-in @state* [:workflows :runs "run-1" :step-runs
                                    "clarity-status" :attempts])]
      (is (= 1 @handler-calls*) "the step operation handler runs once")
      (is (= :ok (:status operation-result))
          "the operation succeeds against the just-started attempt, not abort with :attempt-mismatch")
      (is (= :entered (:operation-handler-entry-state (nth attempts 1)))
          "the live just-started attempt (attempt-2) is driven to :entered")
      (is (nil? (:operation-handler-entry-state (nth attempts 0)))
          "the stale snapshot's latest attempt (attempt-1) is NOT driven"))))

(deftest execute-session-step-invalid-structured-output-blocks-with-envelope-test
  (testing "invalid structured output records raw output and validation errors instead of escaping surface resolution"
    (let [working-memory* (atom {:current-step-id "classify"})
          event-queue* (atom [])
          raw-output "not json"
          turn-opts* (atom nil)]
      (with-redefs [turn-execution/execute-actor-turn!
                    (fn [_ctx session-id prompt opts]
                      (is (= "child-session" session-id))
                      (is (= "Classify" prompt))
                      (reset! turn-opts* opts)
                      {:status :ok
                       :assistant-text raw-output
                       :execution-result nil
                       :assistant-message nil})]
        (step-execution/execute-session-step!
         {}
         {:session-id "child-session"}
         {:name "classify"
          :type :session
          :outputs {:classification {:source :session/structured-output
                                     :mode :structured
                                     :schema-id :psi.workflow/test-classification
                                     :schema-version 1
                                     :schema [:map
                                              [:decision [:enum :pass :fail]]]
                                     :json-schema {:type "object"
                                                   :required ["decision"]
                                                   :properties {"decision" {:type "string"}}}}}}
         "classify"
         "attempt-1"
         working-memory*
         event-queue*
         "Classify"))
      (let [pending (:pending-actor-result @working-memory*)
            payload (:payload pending)
            classification (get-in payload [:outputs :classification])]
        (is (= :blocked (:kind pending)))
        (is (= :blocked (:outcome payload)))
        (is (= :invalid-structured-output (get-in payload [:blocked :reason])))
        (is (= raw-output (:raw-output classification)))
        (is (= :invalid (get-in classification [:structured-output :status])))
        (is (seq (get-in classification [:structured-output :errors])))
        (is (= raw-output (get-in payload [:outputs :final-llm-reply])))
        (is (= {:structured-output {:schema-id :psi.workflow/test-classification
                                    :schema-version 1
                                    :json-schema {:type "object"
                                                  :required ["decision"]
                                                  :properties {"decision" {:type "string"}}}
                                    :strategy-preference :provider-native
                                    :fallback-allowed? true
                                    :strict? true}}
               @turn-opts*))
        (is (= :actor/blocked (:event (first @event-queue*))))))))

(deftest execute-session-step-unsupported-structured-output-blocks-test
  ;; Tests fallback-forbidden AI strategy failures use the structured blocked
  ;; workflow surface, preserving machine-readable AI metadata for inspection.
  (testing "unsupported structured output records a blocked actor result"
    (let [working-memory* (atom {:current-step-id "classify"})
          event-queue* (atom [])]
      (with-redefs [turn-execution/execute-actor-turn!
                    (fn [_ctx _session-id _prompt _opts]
                      {:status :error
                       :assistant-text ""
                       :execution-result {:execution-result/structured-output
                                          {:strategy :unsupported
                                           :reason :unsupported-structured-output
                                           :resolved-model {:provider "local" :id "fallback-only"}}}
                       :structured-output {:strategy :unsupported
                                           :reason :unsupported-structured-output
                                           :resolved-model {:provider "local" :id "fallback-only"}}
                       :failure {:reason :unsupported-structured-output
                                 :message "Resolved model cannot provide native structured output"}})]
        (step-execution/execute-session-step!
         {}
         {:session-id "child-session"}
         {:name "classify"
          :type :session
          :outputs {:classification {:source :session/structured-output
                                     :mode :structured
                                     :schema-id :psi.workflow/test-classification
                                     :schema-version 1
                                     :schema [:map [:decision [:enum :pass :fail]]]
                                     :json-schema {:type "object"}
                                     :require-provider-native? true}}}
         "classify"
         "attempt-1"
         working-memory*
         event-queue*
         "Classify"))
      (let [pending (:pending-actor-result @working-memory*)
            payload (:payload pending)]
        (is (= :blocked (:kind pending)))
        (is (= :blocked (:outcome payload)))
        (is (= :unsupported-structured-output (get-in payload [:blocked :reason])))
        (is (= :classification (get-in payload [:blocked :details :output-key])))
        (is (= {:strategy :unsupported
                :reason :unsupported-structured-output
                :resolved-model {:provider "local" :id "fallback-only"}}
               (get-in payload [:blocked :details :structured-output])))
        (is (= :actor/blocked (:event (first @event-queue*))))))))

(deftest execute-session-step-fallback-none-unsupported-structured-output-blocks-test
  ;; Tests :fallback :none reaches the same workflow failure surface as
  ;; required-native when the AI layer reports unsupported structured output.
  (testing "fallback none unsupported structured output records a blocked actor result"
    (let [working-memory* (atom {:current-step-id "classify"})
          event-queue* (atom [])
          turn-opts* (atom nil)]
      (with-redefs [turn-execution/execute-actor-turn!
                    (fn [_ctx _session-id _prompt opts]
                      (reset! turn-opts* opts)
                      {:status :error
                       :assistant-text ""
                       :structured-output {:strategy :unsupported
                                           :reason :unsupported-structured-output
                                           :resolved-model {:provider "local" :id "unsupported"}}
                       :failure {:reason :unsupported-structured-output
                                 :message "Resolved model cannot provide structured output without fallback"}})]
        (step-execution/execute-session-step!
         {}
         {:session-id "child-session"}
         {:name "classify"
          :type :session
          :outputs {:classification {:source :session/structured-output
                                     :mode :structured
                                     :schema-id :psi.workflow/test-classification
                                     :schema-version 1
                                     :schema [:map [:decision [:enum :pass :fail]]]
                                     :json-schema {:type "object"}
                                     :fallback :none}}}
         "classify"
         "attempt-1"
         working-memory*
         event-queue*
         "Classify"))
      (let [pending (:pending-actor-result @working-memory*)
            payload (:payload pending)]
        (is (= :blocked (:kind pending)))
        (is (= :blocked (:outcome payload)))
        (is (= :unsupported-structured-output (get-in payload [:blocked :reason])))
        (is (= :classification (get-in payload [:blocked :details :output-key])))
        (is (= {:strategy :unsupported
                :reason :unsupported-structured-output
                :resolved-model {:provider "local" :id "unsupported"}}
               (get-in payload [:blocked :details :structured-output])))
        (is (false? (get-in @turn-opts* [:structured-output :fallback-allowed?])))
        (is (not (get-in @turn-opts* [:structured-output :require-provider-native?])))
        (is (= :actor/blocked (:event (first @event-queue*))))))))

(deftest execute-session-step-missing-turn-result-structured-output-blocks-test
  ;; Tests structured workflow requests reject a missing bounded turn-result
  ;; :structured-output seam instead of guessing strategy/payload from prose.
  (testing "session step blocks when structured metadata seam is absent"
    (let [working-memory* (atom {:current-step-id "classify"})
          event-queue* (atom [])]
      (with-redefs [turn-execution/execute-actor-turn!
                    (fn [_ctx _session-id _prompt _opts]
                      {:status :ok
                       :assistant-text "{\"decision\":\"pass\"}"
                       :execution-result nil
                       :assistant-message nil})]
        (step-execution/execute-session-step!
         {}
         {:session-id "child-session"}
         {:name "classify"
          :type :session
          :outputs {:classification {:source :session/structured-output
                                     :mode :structured
                                     :schema-id :psi.workflow/test-classification
                                     :schema-version 1
                                     :schema [:map [:decision [:enum :pass :fail]]]
                                     :json-schema {:type "object"}}}}
         "classify"
         "attempt-1"
         working-memory*
         event-queue*
         "Classify"))
      (let [pending (:pending-actor-result @working-memory*)
            classification (get-in pending [:payload :outputs :classification])]
        (is (= :blocked (:kind pending)))
        (is (= :blocked (get-in pending [:payload :outcome])))
        (is (= :invalid-structured-output (get-in pending [:payload :blocked :reason])))
        (is (= :invalid (get-in classification [:structured-output :status])))
        (is (= :prompted-json (get-in classification [:structured-output :strategy])))
        (is (= [{:type :missing-structured-output
                 :message "Structured workflow generation did not return structured-output metadata"}]
               (get-in classification [:structured-output :errors])))
        (is (not (contains? (:structured-output classification) :value)))
        (is (= :actor/blocked (:event (first @event-queue*))))))))

(deftest execute-session-step-success-uses-turn-result-structured-output-metadata-test
  ;; Tests successful session-step envelopes are built from the authoritative
  ;; bounded turn-result :structured-output seam rather than assistant prose or
  ;; helper-level defaults.
  (testing "session step records actual provider-native strategy metadata and payload from turn result"
    (let [working-memory* (atom {:current-step-id "classify"})
          event-queue* (atom [])
          ai-structured-output {:strategy :provider-native
                                :native-mechanism :openai/chat-completions-json-schema-response-format
                                :source :openai/message-content
                                :payload {"decision" "pass"}
                                :raw-payload "{\"decision\":\"pass\"}"}]
      (with-redefs [turn-execution/execute-actor-turn!
                    (fn [_ctx session-id prompt _opts]
                      (is (= "child-session" session-id))
                      (is (= "Classify" prompt))
                      {:status :ok
                       :assistant-text "ordinary prose should not be parsed"
                       :structured-output ai-structured-output
                       :execution-result {:execution-result/structured-output ai-structured-output}
                       :assistant-message {:role "assistant"
                                           :content [{:type :text :text "ordinary prose should not be parsed"}]}})]
        (step-execution/execute-session-step!
         {}
         {:session-id "child-session"}
         {:name "classify"
          :type :session
          :outputs {:classification {:source :session/structured-output
                                     :mode :structured
                                     :schema-id :psi.workflow/test-classification
                                     :schema-version 1
                                     :schema [:map [:decision [:enum :pass :fail]]]
                                     :json-schema {:type "object"}}}}
         "classify"
         "attempt-1"
         working-memory*
         event-queue*
         "Classify"))
      (let [pending (:pending-actor-result @working-memory*)
            classification (get-in pending [:payload :outputs :classification])]
        (is (= :success (:kind pending)))
        (is (= :valid (get-in classification [:structured-output :status])))
        (is (= :provider-native (get-in classification [:structured-output :strategy])))
        (is (= :openai/chat-completions-json-schema-response-format
               (get-in classification [:structured-output :native-mechanism])))
        (is (= :openai/message-content (get-in classification [:structured-output :source])))
        (is (= {"decision" "pass"} (get-in classification [:structured-output :payload])))
        (is (= "{\"decision\":\"pass\"}" (get-in classification [:structured-output :raw-payload])))
        (is (= {:decision :pass} (get-in classification [:structured-output :value])))
        (is (= :actor/done (:event (first @event-queue*))))))))

(deftest execute-session-step-ranked-fallback-preserves-structured-output-opts-test
  ;; Tests ranked model fallback reuses the exact provider-neutral structured
  ;; output opts for each candidate instead of rebuilding or dropping policy.
  (testing "ranked fallback preserves structured output opts across candidates"
    (let [working-memory* (atom {:current-step-id "classify"})
          event-queue* (atom [])
          calls* (atom [])
          set-models* (atom [])
          adapter (execution-adapter/create
                   {:set-session-model! (fn [_ctx session-id model _scope]
                                          (swap! set-models* conj {:session-id session-id
                                                                   :model model}))})
          ctx {execution-adapter/adapter-key adapter}]
      (with-redefs [turn-execution/execute-actor-turn!
                    (fn [_ctx session-id _prompt opts]
                      (swap! calls* conj {:session-id session-id :opts opts})
                      (if (= 1 (count @calls*))
                        {:status :error
                         :assistant-text ""
                         :execution-result nil
                         :failure {:reason :provider-unavailable
                                   :message "connection refused"
                                   :fallback-worthy? true}}
                        (let [ai-structured-output {:strategy :prompted-json
                                                    :source :prompted-json/text
                                                    :payload {"decision" "pass"}
                                                    :raw-payload "{\"decision\":\"pass\"}"}]
                          {:status :ok
                           :assistant-text "{\"decision\":\"pass\"}"
                           :structured-output ai-structured-output
                           :execution-result {:execution-result/structured-output ai-structured-output}
                           :assistant-message nil})))]
        (step-execution/execute-session-step!
         ctx
         {:session-id "child-session"
          :model-fallback {:type :ranked-model-candidates
                           :candidates [{:provider "local" :id "first"}
                                        {:provider "local" :id "second"}]}}
         {:name "classify"
          :type :session
          :outputs {:classification {:source :session/structured-output
                                     :mode :structured
                                     :schema-id :psi.workflow/test-classification
                                     :schema-version 1
                                     :schema [:map [:decision [:enum :pass :fail]]]
                                     :json-schema {:type "object"}}}}
         "classify"
         "attempt-1"
         working-memory*
         event-queue*
         "Classify"))
      (let [expected-opts {:structured-output {:schema-id :psi.workflow/test-classification
                                               :schema-version 1
                                               :json-schema {:type "object"}
                                               :strategy-preference :provider-native
                                               :fallback-allowed? true
                                               :strict? true}}]
        (is (= [expected-opts expected-opts] (mapv :opts @calls*)))
        (is (= [{:session-id "child-session"
                 :model {:provider "local" :id "second"}}]
               @set-models*))
        (is (= :success (get-in @working-memory* [:pending-actor-result :kind])))))))

(deftest execute-session-step-ranked-fallback-stops-between-candidates-test
  ;; Regression for task 225 implementation review pass 5: a cancellation that
  ;; lands after one fallback-worthy actor failure must not start the next ranked
  ;; model candidate turn.
  (testing "ranked fallback stop predicate is checked before each fallback candidate"
    (let [working-memory* (atom {:current-step-id "classify"})
          event-queue* (atom [])
          calls* (atom [])
          stopped* (atom false)
          set-models* (atom [])
          adapter (execution-adapter/create
                   {:set-session-model! (fn [_ctx session-id model _scope]
                                          (swap! set-models* conj {:session-id session-id
                                                                   :model model}))})
          ctx {execution-adapter/adapter-key adapter
               :workflow-execute-actor-turn-fn
               (fn
                 ([_ctx session-id _prompt]
                  (swap! calls* conj {:session-id session-id :opts nil})
                  (reset! stopped* true)
                  {:status :error
                   :assistant-text ""
                   :execution-result nil
                   :failure {:reason :provider-unavailable
                             :message "connection refused"
                             :fallback-worthy? true}})
                 ([_ctx session-id _prompt opts]
                  (swap! calls* conj {:session-id session-id :opts opts})
                  (reset! stopped* true)
                  {:status :error
                   :assistant-text ""
                   :execution-result nil
                   :failure {:reason :provider-unavailable
                             :message "connection refused"
                             :fallback-worthy? true}}))}]
      (step-execution/execute-session-step!
       ctx
       {:session-id "child-session"
        :model-fallback {:type :ranked-model-candidates
                         :candidates [{:provider "local" :id "first"}
                                      {:provider "local" :id "second"}]}}
       {:name "classify"
        :type :session}
       "classify"
       "attempt-1"
       working-memory*
       event-queue*
       "Classify"
       #(deref stopped*))
      (is (= 1 (count @calls*))
          "the second ranked candidate turn must not start after cancellation")
      (is (= [] @set-models*)
          "the stopped fallback candidate is not installed on the child session")
      (is (nil? (:pending-actor-result @working-memory*))
          "no ordinary actor result is recorded after the stop predicate trips")
      (is (= :workflow/cancel (:event (first @event-queue*)))))))

(deftest execute-session-step-text-output-remains-compatible-test
  (testing "session steps without structured outputs still accept text outputs unchanged"
    (let [working-memory* (atom {:current-step-id "summarize"})
          event-queue* (atom [])
          raw-output "plain human summary"]
      (with-redefs [turn-execution/execute-actor-turn!
                    (fn [_ctx session-id prompt]
                      (is (= "child-session" session-id))
                      (is (= "Summarize" prompt))
                      {:status :ok
                       :assistant-text raw-output
                       :execution-result nil
                       :assistant-message {:role "assistant"
                                           :content [{:type :text :text raw-output}]}})]
        (step-execution/execute-session-step!
         {}
         {:session-id "child-session"}
         {:name "summarize"
          :type :session
          :outputs {:final-llm-reply {:source :session/final-llm-reply}
                    :transcript {:source :session/transcript}}
          :yields {:type :text :text :final-llm-reply}}
         "summarize"
         "attempt-1"
         working-memory*
         event-queue*
         "Summarize"))
      (let [pending (:pending-actor-result @working-memory*)
            payload (:payload pending)]
        (is (= :success (:kind pending)))
        (is (= :ok (:outcome payload)))
        (is (= raw-output (get-in payload [:outputs :final-llm-reply])))
        (is (= raw-output (get-in payload [:outputs :text])))
        (is (= [{:role "assistant" :content [{:type :text :text raw-output}]}]
               (get-in payload [:outputs :transcript])))
        (is (string? (get-in payload [:outputs :final-llm-reply])))
        (is (= :actor/done (:event (first @event-queue*))))))))

(deftest single-prompt-session-step-envelope-characterization-test
  ;; Task 226 Slice 1 (P3/R4) — equivalence-baseline characterization. Pins the
  ;; ASSERTED SHAPE of the single-prompt `execute-session-step!`
  ;; `:pending-actor-result` envelope (not a full-content golden snapshot): the
  ;; presence/shape of `:final-llm-reply`/`:text`/`:transcript` for a
  ;; representative text step, and the structured `:outputs` keys for a
  ;; representative structured step. The unified N=1-degenerate prompt-queue path
  ;; (Slice 1) MUST keep this green unchanged; any change to the asserted envelope
  ;; shape is a defect (R4). This is the Slice-1 done-gate comparand.
  (testing "single-prompt text session step yields the canonical step-level rollup envelope"
    (let [working-memory* (atom {:current-step-id "summarize"})
          event-queue* (atom [])
          assistant-message {:role "assistant"
                             :content [{:type :text :text "the summary"}]}]
      (step-execution/execute-session-step!
       {:workflow-execute-actor-turn-fn
        (fn [_ctx _session-id _prompt]
          {:status :ok
           :assistant-text "the summary"
           :execution-result nil
           :assistant-message assistant-message})}
       {:session-id "child-session"}
       {:name "summarize"
        :type :session
        :outputs {:final-llm-reply {:source :session/final-llm-reply}
                  :transcript {:source :session/transcript}}
        :yields {:type :text :text :final-llm-reply}}
       "summarize"
       "attempt-1"
       working-memory*
       event-queue*
       "Summarize")
      (let [pending (:pending-actor-result @working-memory*)
            payload (:payload pending)
            outputs (:outputs payload)]
        ;; pending-actor-result envelope shape
        (is (= :success (:kind pending)))
        (is (= "summarize" (:step-id pending)))
        (is (= "attempt-1" (:attempt-id pending)))
        (is (= :ok (:outcome payload)))
        ;; step-level text surfaces
        (is (= "the summary" (:final-llm-reply outputs)))
        (is (= "the summary" (:text outputs)))
        (is (= [assistant-message] (:transcript outputs)))
        (is (= "child-session" (:session-id outputs)))
        (is (contains? outputs :logprobs))
        (is (= :actor/done (:event (first @event-queue*)))))))

  (testing "single-prompt structured session step binds the declared structured output key"
    (let [working-memory* (atom {:current-step-id "classify"})
          event-queue* (atom [])
          ai-structured-output {:strategy :provider-native
                                :native-mechanism :openai/chat-completions-json-schema-response-format
                                :source :openai/message-content
                                :payload {"decision" "pass"}
                                :raw-payload "{\"decision\":\"pass\"}"}]
      (step-execution/execute-session-step!
       {:workflow-execute-actor-turn-fn
        (fn [_ctx _session-id _prompt _opts]
          {:status :ok
           :assistant-text "{\"decision\":\"pass\"}"
           :structured-output ai-structured-output
           :execution-result {:execution-result/structured-output ai-structured-output}
           :assistant-message nil})}
       {:session-id "child-session"}
       {:name "classify"
        :type :session
        :outputs {:classification {:source :session/structured-output
                                   :mode :structured
                                   :schema-id :psi.workflow/test-classification
                                   :schema-version 1
                                   :schema [:map [:decision [:enum :pass :fail]]]
                                   :json-schema {:type "object"}}}}
       "classify"
       "attempt-1"
       working-memory*
       event-queue*
       "Classify")
      (let [pending (:pending-actor-result @working-memory*)
            payload (:payload pending)
            classification (get-in payload [:outputs :classification])]
        (is (= :success (:kind pending)))
        (is (= :ok (:outcome payload)))
        ;; structured output key present and valid in the envelope outputs
        (is (contains? (:outputs payload) :classification))
        (is (= :valid (get-in classification [:structured-output :status])))
        (is (= {:decision :pass} (get-in classification [:structured-output :value])))
        (is (= :actor/done (:event (first @event-queue*))))))))

(deftest assistant-message-text-test
  (testing "assistant-message-text delegates to turn-execution-contract"
    (is (= "hello world"
           (step-execution/assistant-message-text
            {:role "assistant"
             :content [{:type :text :text "hello world"}]})))))
