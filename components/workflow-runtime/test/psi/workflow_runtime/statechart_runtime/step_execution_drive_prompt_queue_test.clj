(ns psi.workflow-runtime.statechart-runtime.step-execution-drive-prompt-queue-test
  "task 226 Slice 3 — in-run N-turn drain (drive-session-prompt-queue!).

   Extracted from step-execution-test to keep each test namespace focused and
   within the file-length budget."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.progression-recording :as progression-recording]
   [psi.workflow-runtime.statechart-runtime.step-execution :as step-execution]))

(defn- assistant-text-message
  [text]
  {:role "assistant" :content [{:type :text :text text}]})

(defn- running-attempt-state*
  "A canonical state* atom with one started (running, no per-prompt records)
   latest attempt for `run-id`/`step-id`."
  [run-id step-id]
  (atom {:workflows
         {:runs
          {run-id {:run-id run-id
                   :status :running
                   :step-runs {step-id {:attempts [{:attempt-id "attempt-1"
                                                    :status :running}]}}}}}}))

(defn- recording-record-turn-fn
  "Mirror the production record-turn-fn: persist one per-prompt turn record
   through the canonical progression substrate; returns truthy (live)."
  [state* run-id step-id]
  (fn [index group-name outputs]
    (swap! state* progression-recording/record-prompt-group-turn run-id step-id
           {:index index :name group-name :outputs outputs})
    true))

(deftest drive-session-prompt-queue-runs-named-turns-in-order-test
  (testing "N named prompts run as sequential turns in author order against the same session, with one post-drain result"
    (let [run-id "run-1"
          step-id "design-review"
          state* (running-attempt-state* run-id step-id)
          submitted* (atom [])
          turn-calls* (atom 0)
          execute-turn (fn [_ctx session-id prompt]
                         (swap! turn-calls* inc)
                         (swap! submitted* conj {:session-id session-id :prompt prompt})
                         {:status :ok
                          :assistant-text (str "reply-" prompt)
                          :execution-result nil
                          :assistant-message (assistant-text-message (str "reply-" prompt))})
          ctx {:state* state*
               :workflow-execute-actor-turn-fn execute-turn}
          working-memory* (atom {:current-step-id step-id})
          event-queue* (atom [])
          prompt-queue [{:name "architecture" :contributions []}
                        {:name "ambiguity" :contributions []}
                        {:name "consistency" :contributions []}]]
      (step-execution/drive-session-prompt-queue!
       ctx {:session-id "child-session"}
       {:name step-id :type :session}
       step-id "attempt-1" working-memory* event-queue*
       run-id prompt-queue "PROMPT-architecture"
       (fn [group] (str "PROMPT-" (:name group)))
       (recording-record-turn-fn state* run-id step-id)
       (constantly false))
      ;; exactly one turn per prompt (no re-fire) in author order, same session
      (is (= 3 @turn-calls*) "one turn per un-run prompt, no re-fire")
      (is (= ["child-session" "child-session" "child-session"]
             (map :session-id @submitted*))
          "all turns run against the same shared child session")
      (is (= ["PROMPT-architecture" "PROMPT-ambiguity" "PROMPT-consistency"]
             (map :prompt @submitted*))
          "prompts submitted in author order; group 0 uses the pre-split prompt")
      ;; progression-driven per-prompt records, introspectable, in order (S4)
      (let [records (progression-recording/prompt-group-turn-records
                     (get-in @state* (progression-recording/run-path run-id)) step-id)]
        (is (= [0 1 2] (mapv :index records)))
        (is (= ["architecture" "ambiguity" "consistency"] (mapv :name records)))
        (is (= "reply-PROMPT-architecture"
               (get-in records [0 :outputs :final-llm-reply]))))
      ;; one post-drain :pending-actor-result: drained only after every turn
      (let [pending (:pending-actor-result @working-memory*)
            outputs (get-in pending [:payload :outputs])]
        (is (= :success (:kind pending)))
        (is (= :actor/done (:event (first @event-queue*))))
        (is (= 1 (count @event-queue*)) "exactly one post-drain event")
        ;; step-level rollup: last prompt's reply + accumulated transcript
        (is (= "reply-PROMPT-consistency" (:final-llm-reply outputs)))
        (is (= ["reply-PROMPT-architecture" "reply-PROMPT-ambiguity" "reply-PROMPT-consistency"]
               (map (comp :text first :content) (:transcript outputs)))
            "transcript accumulated across all turns")
        ;; ordered per-prompt records nested in the envelope
        (is (= ["architecture" "ambiguity" "consistency"]
               (map :name (:prompt-group-outputs outputs))))))))

(deftest drive-session-prompt-queue-resume-skips-recorded-prompts-test
  (testing "a mid-queue re-entry runs only the un-run prompts (progression-driven, no re-fire of recorded turns)"
    (let [run-id "run-1"
          step-id "design-review"
          ;; attempt already has a recorded turn for index 0.
          state* (atom {:workflows
                        {:runs
                         {run-id {:run-id run-id
                                  :status :running
                                  :step-runs {step-id {:attempts [{:attempt-id "attempt-1"
                                                                   :status :running
                                                                   :prompt-group-turns
                                                                   [{:index 0 :name "architecture"
                                                                     :outputs {:final-llm-reply "prior"}}]}]}}}}}})
          turn-calls* (atom 0)
          submitted* (atom [])
          execute-turn (fn [_ctx _session-id prompt]
                         (swap! turn-calls* inc)
                         (swap! submitted* conj prompt)
                         {:status :ok
                          :assistant-text (str "reply-" prompt)
                          :execution-result nil
                          :assistant-message (assistant-text-message (str "reply-" prompt))})
          ctx {:state* state*
               :workflow-execute-actor-turn-fn execute-turn}
          working-memory* (atom {:current-step-id step-id})
          event-queue* (atom [])
          prompt-queue [{:name "architecture" :contributions []}
                        {:name "ambiguity" :contributions []}]]
      (step-execution/drive-session-prompt-queue!
       ctx {:session-id "child-session"}
       {:name step-id :type :session}
       step-id "attempt-1" working-memory* event-queue*
       run-id prompt-queue "PROMPT-architecture"
       (fn [group] (str "PROMPT-" (:name group)))
       (recording-record-turn-fn state* run-id step-id)
       (constantly false))
      ;; index 0 already recorded → never re-submitted; only index 1 runs
      (is (= 1 @turn-calls*) "only the un-run prompt fires a turn")
      (is (= ["PROMPT-ambiguity"] @submitted*))
      (is (= :actor/done (:event (first @event-queue*)))))))

(deftest drive-session-prompt-queue-requests-structured-output-on-final-turn-only-test
  (testing "structured :outputs are requested on the final turn only (P5)"
    (let [run-id "run-1"
          step-id "classify-chain"
          state* (running-attempt-state* run-id step-id)
          opts-by-prompt* (atom [])
          ai-structured-output {:strategy :provider-native
                                :payload {"decision" "pass"}
                                :raw-payload "{\"decision\":\"pass\"}"}
          execute-turn (fn
                         ([_ctx _session-id prompt]
                          (swap! opts-by-prompt* conj [prompt nil])
                          {:status :ok
                           :assistant-text (str "reply-" prompt)
                           :execution-result nil
                           :assistant-message (assistant-text-message (str "reply-" prompt))})
                         ([_ctx _session-id prompt opts]
                          (swap! opts-by-prompt* conj [prompt opts])
                          {:status :ok
                           :assistant-text "{\"decision\":\"pass\"}"
                           :structured-output ai-structured-output
                           :execution-result {:execution-result/structured-output ai-structured-output}
                           :assistant-message (assistant-text-message "{\"decision\":\"pass\"}")}))
          ctx {:state* state*
               :workflow-execute-actor-turn-fn execute-turn}
          working-memory* (atom {:current-step-id step-id})
          event-queue* (atom [])
          step-def {:name step-id
                    :type :session
                    :outputs {:classification {:source :session/structured-output
                                               :mode :structured
                                               :schema-id :psi.workflow/test-classification
                                               :schema-version 1
                                               :schema [:map [:decision [:enum :pass :fail]]]
                                               :json-schema {:type "object"}}}}
          prompt-queue [{:name "gather" :contributions []}
                        {:name "decide" :contributions []}]]
      (step-execution/drive-session-prompt-queue!
       ctx {:session-id "child-session"} step-def
       step-id "attempt-1" working-memory* event-queue*
       run-id prompt-queue "PROMPT-gather"
       (fn [group] (str "PROMPT-" (:name group)))
       (recording-record-turn-fn state* run-id step-id)
       (constantly false))
      ;; first (non-final) turn gets no structured-output opts; final turn does
      (is (= [["PROMPT-gather" nil]] (filter #(= "PROMPT-gather" (first %)) @opts-by-prompt*)))
      (let [[final-prompt final-opts] (first (filter #(= "PROMPT-decide" (first %)) @opts-by-prompt*))]
        (is (= "PROMPT-decide" final-prompt))
        (is (some? (:structured-output final-opts))
            "structured output requested on the final turn"))
      (let [outputs (get-in (:pending-actor-result @working-memory*) [:payload :outputs])]
        (is (contains? outputs :classification)
            "final-turn structured output is bound on the step-level rollup")))))

(deftest drive-session-prompt-queue-blocks-upfront-on-invalid-structured-request-test
  (testing "an invalid structured-output request blocks upfront before any turn runs (P13a)"
    (let [run-id "run-1"
          step-id "classify-chain"
          state* (running-attempt-state* run-id step-id)
          turn-calls* (atom 0)
          ctx {:state* state*
               :workflow-execute-actor-turn-fn (fn [& _] (swap! turn-calls* inc) {:status :ok})}
          working-memory* (atom {:current-step-id step-id})
          event-queue* (atom [])
          ;; mode :structured with no schema-id/json-schema ⇒ invalid request
          step-def {:name step-id
                    :type :session
                    :outputs {:classification {:source :session/structured-output
                                               :mode :structured}}}
          prompt-queue [{:name "gather" :contributions []}
                        {:name "decide" :contributions []}]]
      (step-execution/drive-session-prompt-queue!
       ctx {:session-id "child-session"} step-def
       step-id "attempt-1" working-memory* event-queue*
       run-id prompt-queue "PROMPT-gather"
       (fn [group] (str "PROMPT-" (:name group)))
       (recording-record-turn-fn state* run-id step-id)
       (constantly false))
      (is (= 0 @turn-calls*) "zero turns run on an upfront structured-request block")
      (let [pending (:pending-actor-result @working-memory*)
            records (progression-recording/prompt-group-turn-records
                     (get-in @state* (progression-recording/run-path run-id)) step-id)]
        (is (= :blocked (:kind pending)))
        (is (= :blocked (get-in pending [:payload :outcome])))
        (is (empty? records) "zero per-prompt records on an upfront block")
        (is (= :actor/blocked (:event (first @event-queue*))))))))
