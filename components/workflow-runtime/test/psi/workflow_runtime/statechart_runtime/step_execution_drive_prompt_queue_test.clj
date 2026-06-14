(ns psi.workflow-runtime.statechart-runtime.step-execution-drive-prompt-queue-test
  "task 226 Slice 3 — in-run N-turn drain (drive-session-prompt-queue!).

   Extracted from step-execution-test to keep each test namespace focused and
   within the file-length budget."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.progression-recording :as progression-recording]
   [psi.workflow-runtime.statechart-runtime.step-execution :as step-execution]
   [psi.workflow-runtime.step-test-support :as step-test-support]
   [psi.workflow-step-materialization.core :as materialization]))

(defn- user-text-message
  [text]
  {:role "user" :content [{:type :text :text text}]})

;;;; Shared multi-prompt drain fixtures + SUT-invocation helper live in
;;;; step-test-support (TS-1) and are used from both sibling drain namespaces.
(def ^:private assistant-text-message step-test-support/assistant-text-message)
(def ^:private running-attempt-state* step-test-support/running-attempt-state*)
(def ^:private recorded-turns-state* step-test-support/recorded-turns-state*)
(def ^:private drive! step-test-support/drive!)

;;;; task 226 R-2 — later-group single-submission limitation.
;;;;
;;;; A later prompt-group submits only its split :prompt (the final user
;;;; message); any preloaded (non-final) messages a multi-message group
;;;; materializes to are intentionally NOT re-injected mid-session. This pins
;;;; the documented limitation (doc/workflow-grammar.md) using the real
;;;; split-step-session-conversation.

(deftest later-group-turn-prompt-single-message-test
  (testing "a single-message later group submits that message as its turn prompt"
    (let [materialize-fn (fn [_workflow-run group]
                           [(user-text-message (str "only-" (:name group)))])]
      (is (= "only-ambiguity"
             (step-execution/later-group-turn-prompt
              materialize-fn
              materialization/split-step-session-conversation
              {} {:name "ambiguity" :contributions []}))))))

(deftest later-group-turn-prompt-drops-multi-message-preload-test
  (testing "a later group whose contributions materialize to >1 message submits ONLY the final message; preloaded messages are dropped"
    (let [materialize-fn (fn [_workflow-run _group]
                           [(user-text-message "preamble-1")
                            (user-text-message "preamble-2")
                            (user-text-message "actual-ask")])]
      (is (= "actual-ask"
             (step-execution/later-group-turn-prompt
              materialize-fn
              materialization/split-step-session-conversation
              {} {:name "ambiguity" :contributions []}))
          "only the final user message is submitted; earlier (preloaded) messages are silently dropped for later groups"))))

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
                         (step-test-support/ok-turn prompt))
          ctx {:state* state*
               :workflow-execute-actor-turn-fn execute-turn}
          working-memory* (atom {:current-step-id step-id})
          event-queue* (atom [])
          prompt-queue [{:name "architecture" :contributions []}
                        {:name "ambiguity" :contributions []}
                        {:name "consistency" :contributions []}]]
      (drive! {:ctx ctx :step-def {:name step-id :type :session}
               :state* state* :run-id run-id :step-id step-id
               :working-memory* working-memory* :event-queue* event-queue*
               :prompt-queue prompt-queue})
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
          state* (recorded-turns-state*
                  run-id step-id
                  [{:index 0 :name "architecture"
                    :outputs {:final-llm-reply "prior"}}])
          turn-calls* (atom 0)
          submitted* (atom [])
          execute-turn (fn [_ctx _session-id prompt]
                         (swap! turn-calls* inc)
                         (swap! submitted* conj prompt)
                         (step-test-support/ok-turn prompt))
          ctx {:state* state*
               :workflow-execute-actor-turn-fn execute-turn}
          working-memory* (atom {:current-step-id step-id})
          event-queue* (atom [])
          prompt-queue [{:name "architecture" :contributions []}
                        {:name "ambiguity" :contributions []}]]
      (drive! {:ctx ctx :step-def {:name step-id :type :session}
               :state* state* :run-id run-id :step-id step-id
               :working-memory* working-memory* :event-queue* event-queue*
               :prompt-queue prompt-queue})
      ;; index 0 already recorded → never re-submitted; only index 1 runs
      (is (= 1 @turn-calls*) "only the un-run prompt fires a turn")
      (is (= ["PROMPT-ambiguity"] @submitted*))
      (is (= :actor/done (:event (first @event-queue*)))))))

;;;; task 226 Slice 5 — resume-from-progression across process-restart / replay.
;;;;
;;;; Slice 3 proved the in-run drain re-reads progression each iteration; Slice 5
;;;; proves that re-driving against a FRESHLY reconstructed state* + ctx (no
;;;; in-memory loop state carried across the restart) reconstructs queue position
;;;; purely from persisted per-prompt progression and never re-fires a recorded
;;;; turn's `ai/generate` effect (P8 observable: the per-prompt turn-call count
;;;; at the execute-turn seam is zero for any prompt with an existing record).

(deftest drive-session-prompt-queue-reconstructs-position-from-persisted-progression-test
  (testing "a restart re-drive against reconstructed persisted progression runs only un-run prompts (zero re-fire of recorded turns, P8/AC-7)"
    (let [run-id "run-1"
          step-id "design-review"
          ;; Reconstructed state*: indices 0 and 1 already have recorded turns;
          ;; nothing about the prior (now-dead) drain loop survives — only the
          ;; persisted progression does.
          state* (recorded-turns-state*
                  run-id step-id
                  [{:index 0 :name "architecture" :outputs {:final-llm-reply "prior-arch"}}
                   {:index 1 :name "ambiguity" :outputs {:final-llm-reply "prior-ambig"}}])
          turn-calls* (atom 0)
          submitted* (atom [])
          execute-turn (fn [_ctx _session-id prompt]
                         (swap! turn-calls* inc)
                         (swap! submitted* conj prompt)
                         (step-test-support/ok-turn prompt))
          ;; A fresh ctx for the post-restart process — only state* + the seam.
          ctx {:state* state*
               :workflow-execute-actor-turn-fn execute-turn}
          working-memory* (atom {:current-step-id step-id})
          event-queue* (atom [])
          prompt-queue [{:name "architecture" :contributions []}
                        {:name "ambiguity" :contributions []}
                        {:name "consistency" :contributions []}]]
      (drive! {:ctx ctx :step-def {:name step-id :type :session}
               :state* state* :run-id run-id :step-id step-id
               :working-memory* working-memory* :event-queue* event-queue*
               :prompt-queue prompt-queue})
      ;; P8: exactly one ai/generate effect — for the single un-run prompt; zero
      ;; for each already-recorded prompt.
      (is (= 1 @turn-calls*) "only the un-run prompt fires a turn after a restart")
      (is (= ["PROMPT-consistency"] @submitted*)
          "position reconstructed purely from persisted progression (next un-run = index 2)")
      ;; Corroborating observable: no second turn record / no progression mutation
      ;; for an already-recorded prompt; the prior records are retained verbatim.
      (let [records (progression-recording/prompt-group-turn-records
                     (get-in @state* (progression-recording/run-path run-id)) step-id)]
        (is (= [0 1 2] (mapv :index records)) "one record per index, no duplicate append")
        (is (= "prior-arch" (get-in records [0 :outputs :final-llm-reply]))
            "pre-restart record for index 0 retained verbatim")
        (is (= "prior-ambig" (get-in records [1 :outputs :final-llm-reply]))
            "pre-restart record for index 1 retained verbatim"))
      ;; Post-drain route reached only after every prompt has a recorded turn.
      (is (= :actor/done (:event (first @event-queue*)))))))

(deftest drive-session-prompt-queue-replay-fully-recorded-fires-zero-turns-test
  (testing "re-driving a fully-recorded queue (event-log replay) fires zero turns and drains immediately (P8 zero re-fire/AC-7)"
    (let [run-id "run-1"
          step-id "design-review"
          records [{:index 0 :name "architecture" :outputs {:final-llm-reply "r0"}}
                   {:index 1 :name "ambiguity" :outputs {:final-llm-reply "r1"}}]
          state* (recorded-turns-state* run-id step-id records)
          turn-calls* (atom 0)
          ctx {:state* state*
               :workflow-execute-actor-turn-fn (fn [& _] (swap! turn-calls* inc) {:status :ok})}
          working-memory* (atom {:current-step-id step-id})
          event-queue* (atom [])
          prompt-queue [{:name "architecture" :contributions []}
                        {:name "ambiguity" :contributions []}]]
      (drive! {:ctx ctx :step-def {:name step-id :type :session}
               :state* state* :run-id run-id :step-id step-id
               :working-memory* working-memory* :event-queue* event-queue*
               :prompt-queue prompt-queue})
      ;; Zero ai/generate effects across the fully-recorded reconstructed state.
      (is (= 0 @turn-calls*) "no turn re-fires when every prompt already has a record")
      ;; No progression mutation: the records are untouched.
      (is (= records
             (mapv #(dissoc % :recorded-at)
                   (progression-recording/prompt-group-turn-records
                    (get-in @state* (progression-recording/run-path run-id)) step-id)))
          "no second turn record written on replay")
      ;; Post-drain route still reached (queue already drained).
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
      (drive! {:ctx ctx :step-def step-def
               :state* state* :run-id run-id :step-id step-id
               :working-memory* working-memory* :event-queue* event-queue*
               :prompt-queue prompt-queue})
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
      (drive! {:ctx ctx :step-def step-def
               :state* state* :run-id run-id :step-id step-id
               :working-memory* working-memory* :event-queue* event-queue*
               :prompt-queue prompt-queue})
      (is (= 0 @turn-calls*) "zero turns run on an upfront structured-request block")
      (let [pending (:pending-actor-result @working-memory*)
            records (progression-recording/prompt-group-turn-records
                     (get-in @state* (progression-recording/run-path run-id)) step-id)]
        (is (= :blocked (:kind pending)))
        (is (= :blocked (get-in pending [:payload :outcome])))
        (is (empty? records) "zero per-prompt records on an upfront block")
        (is (= :actor/blocked (:event (first @event-queue*))))))))
