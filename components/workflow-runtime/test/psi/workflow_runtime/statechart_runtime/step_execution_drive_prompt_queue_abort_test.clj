(ns psi.workflow-runtime.statechart-runtime.step-execution-drive-prompt-queue-abort-test
  "task 226 Slice 6 — abort paths for the in-run multi-prompt drain
   (`drive-session-prompt-queue!`): intermediate/final-turn `:failed`,
   inter-prompt / in-flight `:cancelled`, and final-turn structured-output
   `:blocked`. Each abort skips routing (no `:actor/done`) and retains the
   per-prompt records of prompts completed before the abort, while the aborting
   prompt itself leaves no completed turn record (AC-5/AC-6/P10/P12/P13)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.progression-recording :as progression-recording]
   [psi.workflow-runtime.step-test-support :as step-test-support]))

(def ^:private running-attempt-state* step-test-support/running-attempt-state*)
(def ^:private drive! step-test-support/drive!)
(def ^:private ok-turn step-test-support/ok-turn)

(defn- recorded-indices
  [state* run-id step-id]
  (mapv :index
        (progression-recording/prompt-group-turn-records
         (get-in @state* (progression-recording/run-path run-id)) step-id)))

(deftest drive-session-prompt-queue-intermediate-turn-error-fails-naming-prompt-test
  (testing "an intermediate-turn error aborts to :failed naming the failing prompt; prior records retained, failing prompt leaves no record, routing skipped (AC-5/G1)"
    (let [run-id "run-1"
          step-id "design-review"
          state* (running-attempt-state* run-id step-id)
          execute-turn (fn [_ctx _sid prompt]
                         (if (= "PROMPT-ambiguity" prompt)
                           {:status :error
                            :failure {:reason :model-error :message "boom"}}
                           (ok-turn prompt)))
          working-memory* (atom {:current-step-id step-id})
          event-queue* (atom [])]
      (drive! {:ctx {:state* state* :workflow-execute-actor-turn-fn execute-turn}
               :step-def {:name step-id :type :session}
               :state* state* :run-id run-id :step-id step-id
               :working-memory* working-memory* :event-queue* event-queue*
               :prompt-queue [{:name "architecture" :contributions []}
                              {:name "ambiguity" :contributions []}
                              {:name "consistency" :contributions []}]})
      (let [pending (:pending-actor-result @working-memory*)]
        (is (= :failure (:kind pending)))
        (is (= {:index 1 :name "ambiguity"} (get-in pending [:payload :failed-prompt]))
            "failure payload names the failing prompt")
        (is (= :model-error (get-in pending [:payload :reason])) "carries the turn error"))
      ;; routing skipped: the terminal event is :actor/failed, never :actor/done
      (is (= :actor/failed (:event (first @event-queue*))))
      (is (not-any? #(= :actor/done (:event %)) @event-queue*) "no post-drain route")
      ;; index 0 completed before the failure is retained; index 1 (failing) and
      ;; index 2 (never reached) leave no record.
      (is (= [0] (recorded-indices state* run-id step-id))))))

(deftest drive-session-prompt-queue-final-turn-error-fails-naming-prompt-test
  (testing "a final/last-turn error follows the same :failed abort (P10): prior records retained, last prompt leaves no record, routing skipped"
    (let [run-id "run-1"
          step-id "design-review"
          state* (running-attempt-state* run-id step-id)
          execute-turn (fn [_ctx _sid prompt]
                         (if (= "PROMPT-consistency" prompt)
                           {:status :error
                            :failure {:reason :model-error :message "boom-last"}}
                           (ok-turn prompt)))
          working-memory* (atom {:current-step-id step-id})
          event-queue* (atom [])]
      (drive! {:ctx {:state* state* :workflow-execute-actor-turn-fn execute-turn}
               :step-def {:name step-id :type :session}
               :state* state* :run-id run-id :step-id step-id
               :working-memory* working-memory* :event-queue* event-queue*
               :prompt-queue [{:name "architecture" :contributions []}
                              {:name "ambiguity" :contributions []}
                              {:name "consistency" :contributions []}]})
      (let [pending (:pending-actor-result @working-memory*)]
        (is (= :failure (:kind pending)))
        (is (= {:index 2 :name "consistency"} (get-in pending [:payload :failed-prompt]))))
      (is (= :actor/failed (:event (first @event-queue*))))
      ;; the two prompts completed before the failing last one are retained.
      (is (= [0 1] (recorded-indices state* run-id step-id))))))

(deftest drive-session-prompt-queue-inter-prompt-cancellation-test
  (testing "cancellation around a prompt yields terminal :cancelled, routing skipped, completed records retained, interrupted prompt leaves no record (AC-6/P12)"
    (let [run-id "run-1"
          step-id "design-review"
          state* (running-attempt-state* run-id step-id)
          cancelled?* (atom false)
          execute-turn (fn [_ctx _sid prompt]
                         ;; cancellation arrives while the second prompt's turn runs
                         (when (= "PROMPT-ambiguity" prompt) (reset! cancelled?* true))
                         (ok-turn prompt))
          working-memory* (atom {:current-step-id step-id})
          event-queue* (atom [])]
      (drive! {:ctx {:state* state* :workflow-execute-actor-turn-fn execute-turn}
               :step-def {:name step-id :type :session}
               :state* state* :run-id run-id :step-id step-id
               :working-memory* working-memory* :event-queue* event-queue*
               :prompt-queue [{:name "architecture" :contributions []}
                              {:name "ambiguity" :contributions []}
                              {:name "consistency" :contributions []}]
               :stopped? (fn [] @cancelled?*)})
      ;; terminal :cancelled (distinct from :failed): :workflow/cancel enqueued,
      ;; no post-drain result recorded.
      (is (= :workflow/cancel (:event (first @event-queue*))))
      (is (not-any? #(= :actor/done (:event %)) @event-queue*) "routing skipped")
      (is (nil? (:pending-actor-result @working-memory*))
          "no post-drain :pending-actor-result on cancellation")
      ;; index 0 completed before the cancel is retained; the interrupted index 1
      ;; (and the never-reached index 2) leave no record.
      (is (= [0] (recorded-indices state* run-id step-id))))))

(deftest drive-session-prompt-queue-between-prompt-cancellation-checkpoint-test
  (testing "a cancellation observed between turns stops the queue at the top of the next iteration WITHOUT firing another turn (R-7): zero additional turn-fn invocations, :workflow/cancel enqueued, no post-drain result, prior records retained"
    (let [run-id "run-1"
          step-id "design-review"
          state* (running-attempt-state* run-id step-id)
          turn-calls* (atom 0)
          execute-turn (fn [_ctx _sid prompt]
                         (swap! turn-calls* inc)
                         (ok-turn prompt))
          working-memory* (atom {:current-step-id step-id})
          event-queue* (atom [])]
      (drive! {:ctx {:state* state* :workflow-execute-actor-turn-fn execute-turn}
               :step-def {:name step-id :type :session}
               :state* state* :run-id run-id :step-id step-id
               :working-memory* working-memory* :event-queue* event-queue*
               :prompt-queue [{:name "architecture" :contributions []}
                              {:name "ambiguity" :contributions []}
                              {:name "consistency" :contributions []}]
               ;; The cancellation becomes observable only once the first prompt's
               ;; turn record has been written — i.e. strictly BETWEEN prompt 0
               ;; and prompt 1. The pre-turn checkpoint must catch it before
               ;; prompt 1's turn fires (turn-calls stays at 1).
               :stopped? (fn [] (seq (recorded-indices state* run-id step-id)))})
      ;; exactly one turn fired (prompt 0); the between-prompt checkpoint stops
      ;; the queue before prompt 1's turn would have fired (without R-7 this
      ;; would be 2 — prompt 1's turn runs before the post-turn stopped? check).
      (is (= 1 @turn-calls*) "no additional turn fired after the between-prompt cancellation")
      (is (= :workflow/cancel (:event (first @event-queue*))) "terminal :cancelled")
      (is (not-any? #(= :actor/done (:event %)) @event-queue*) "routing skipped")
      (is (nil? (:pending-actor-result @working-memory*))
          "no post-drain :pending-actor-result on between-prompt cancellation")
      ;; the prompt completed before the cancel is retained.
      (is (= [0] (recorded-indices state* run-id step-id))))))

(deftest drive-session-prompt-queue-final-turn-structured-output-blocked-test
  (testing "a final-turn structured-output block after N-1 turns yields terminal :blocked; prior records retained, blocking final prompt leaves no record, routing skipped (P13/AC-3/AC-5)"
    (let [run-id "run-1"
          step-id "classify-chain"
          state* (running-attempt-state* run-id step-id)
          execute-turn (fn
                         ([_ctx _sid prompt] (ok-turn prompt))
                         ([_ctx _sid _prompt _opts]
                          ;; the final turn requests structured output; the model
                          ;; cannot satisfy it ⇒ :unsupported-structured-output.
                          {:status :error
                           :structured-output {:reason :unsupported-structured-output}
                           :failure {:reason :unsupported-structured-output
                                     :message "model cannot do structured output"}}))
          working-memory* (atom {:current-step-id step-id})
          event-queue* (atom [])
          step-def {:name step-id
                    :type :session
                    :outputs {:classification {:source :session/structured-output
                                               :mode :structured
                                               :schema-id :psi.workflow/test-classification
                                               :schema-version 1
                                               :schema [:map [:decision [:enum :pass :fail]]]
                                               :json-schema {:type "object"}}}}]
      (drive! {:ctx {:state* state* :workflow-execute-actor-turn-fn execute-turn}
               :step-def step-def
               :state* state* :run-id run-id :step-id step-id
               :working-memory* working-memory* :event-queue* event-queue*
               :prompt-queue [{:name "gather" :contributions []}
                              {:name "decide" :contributions []}]})
      (let [pending (:pending-actor-result @working-memory*)]
        (is (= :blocked (:kind pending)) "terminal :blocked (distinct from :failed/:cancelled)")
        (is (= :unsupported-structured-output (get-in pending [:payload :blocked :reason]))))
      (is (= :actor/blocked (:event (first @event-queue*))))
      (is (not-any? #(= :actor/done (:event %)) @event-queue*) "routing skipped")
      ;; the gather turn (index 0) completed before the final-turn block is
      ;; retained; the blocking final prompt (index 1) leaves no record.
      (is (= [0] (recorded-indices state* run-id step-id))))))

(deftest drive-session-prompt-queue-final-turn-invalid-structured-output-blocked-test
  (testing "a final-turn :invalid-structured-output block (P13 case iii: :status :ok reply that fails structured-output validation, the :branch :success blocked path) after N-1 turns yields terminal :blocked; prior records retained, blocking final prompt leaves no record, routing skipped (AC-3/AC-5)"
    (let [run-id "run-1"
          step-id "classify-chain"
          state* (running-attempt-state* run-id step-id)
          execute-turn (fn
                         ([_ctx _sid prompt] (ok-turn prompt))
                         ([_ctx _sid _prompt _opts]
                          ;; the final turn requests structured output and the
                          ;; model REPLIES OK, but the reply omits the
                          ;; authoritative :structured-output metadata seam ⇒
                          ;; missing-ai-structured-output-result ⇒
                          ;; :invalid-structured-output (the :branch :success
                          ;; blocked path, distinct from case (ii)'s :branch
                          ;; :error :unsupported-structured-output).
                          {:status :ok
                           :assistant-text "reply-decide"
                           :execution-result nil
                           :assistant-message (step-test-support/assistant-text-message "reply-decide")
                           :structured-output nil}))
          working-memory* (atom {:current-step-id step-id})
          event-queue* (atom [])
          step-def {:name step-id
                    :type :session
                    :outputs {:classification {:source :session/structured-output
                                               :mode :structured
                                               :schema-id :psi.workflow/test-classification
                                               :schema-version 1
                                               :schema [:map [:decision [:enum :pass :fail]]]
                                               :json-schema {:type "object"}}}}]
      (drive! {:ctx {:state* state* :workflow-execute-actor-turn-fn execute-turn}
               :step-def step-def
               :state* state* :run-id run-id :step-id step-id
               :working-memory* working-memory* :event-queue* event-queue*
               :prompt-queue [{:name "gather" :contributions []}
                              {:name "decide" :contributions []}]})
      (let [pending (:pending-actor-result @working-memory*)]
        (is (= :blocked (:kind pending)) "terminal :blocked (distinct from :failed/:cancelled)")
        (is (= :invalid-structured-output (get-in pending [:payload :blocked :reason]))
            "the :branch :success invalid-structured-output blocked reason"))
      (is (= :actor/blocked (:event (first @event-queue*))))
      (is (not-any? #(= :actor/done (:event %)) @event-queue*) "routing skipped")
      ;; the gather turn (index 0) completed before the final-turn block is
      ;; retained; the blocking final prompt (index 1) leaves no record.
      (is (= [0] (recorded-indices state* run-id step-id))))))
