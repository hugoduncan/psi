(ns psi.workflow-runtime.statechart-runtime.step-execution
  (:require
   [psi.deterministic-operation-registry.defs :as deterministic-op-defs]
   [psi.deterministic-operation-registry.registry :as deterministic-op-registry]
   [psi.deterministic-operation-runtime.core :as deterministic-op-runtime]
   [psi.workflow-step-materialization.source-resolution :as workflow-source-resolution]
   [psi.workflow-runtime.attempts :as attempts]
   [psi.workflow-runtime.ir :as workflow-ir]
   [psi.workflow-runtime.progression-recording :as progression-recording]
   [psi.workflow-runtime.structured-output :as structured-output]
   [psi.workflow-runtime.statechart-runtime.queue :as queue]
   [psi.workflow-runtime.statechart-runtime.state :as state]
   [psi.workflow-runtime.turn-execution-contract :as turn-execution]))

(def assistant-message-text
  turn-execution/assistant-message-text)

(defn operation-result->invoke-step-result
  [operation-result]
  (when-not (deterministic-op-defs/valid-operation-result? operation-result)
    (throw (ex-info "Cannot wrap malformed deterministic operation result"
                    {:type :malformed-operation-result
                     :result operation-result
                     :explanation (deterministic-op-defs/explain-operation-result operation-result)})))
  (case (:status operation-result)
    :ok
    {:kind :accepted-result
     :accepted-result {:outcome :ok
                       :outputs (cond-> {:data (:data operation-result)
                                         :result operation-result}
                                  (contains? operation-result :summary)
                                  (assoc :summary (:summary operation-result)))}}

    :error
    {:kind :execution-error
     :execution-error (cond-> {:reason (:reason operation-result)
                               :message (:message operation-result)
                               :operation-result operation-result}
                        (:details operation-result)
                        (assoc :operation-details (:details operation-result)))}

    (throw (ex-info "Unknown deterministic operation result status"
                    {:result operation-result}))))

(defn invoke-step-runtime-result
  [ctx parent-session-id run-id step-id step-def workflow-run attempt-id]
  (let [invoke-spec (or (:invoke step-def)
                        (get-in step-def [:judge :invoke]))
        args (workflow-source-resolution/resolve-invoke-args workflow-run step-id (:args invoke-spec))
        operation-result (deterministic-op-registry/invoke-operation-in
                          (:deterministic-operation-registry ctx)
                          (:operation invoke-spec)
                          {:ctx ctx
                           :parent-session-id parent-session-id
                           :workflow-run-id run-id
                           ;; task 228: use the attempt the caller just started,
                           ;; not the latest attempt derived from a `workflow-run`
                           ;; snapshot taken before this attempt was appended. On
                           ;; a re-executed (REPEAT) invoke step the snapshot is
                           ;; stale and its latest attempt id no longer matches
                           ;; the live latest attempt, which task-225's attempt
                           ;; equality guard rejects with :attempt-mismatch.
                           :workflow-attempt-id attempt-id
                           :step-id step-id
                           :args args}
                          deterministic-op-runtime/invoke-operation)]
    (when (state/workflow-stopped? ctx run-id)
      (throw (ex-info "Workflow execution stopped after invoke operation"
                      {:reason (state/workflow-stop-signal ctx run-id)
                       :run-id run-id
                       :step-id step-id})))
    {:effective-args args
     :operation-result operation-result}))

(defn apply-invoke-step-result
  [{:keys [effective-args operation-result]}]
  (let [{:keys [kind accepted-result execution-error]} (operation-result->invoke-step-result operation-result)]
    (case kind
      :accepted-result {:attempt-data {:effective-args effective-args}
                        :pending-kind :success
                        :payload accepted-result}
      :execution-error {:attempt-data {:effective-args effective-args}
                        :pending-kind :failure
                        :payload execution-error})))

(defn- fallback-candidates
  [execution-session]
  (get-in execution-session [:model-fallback :candidates]))

(defn- fallback-enabled?
  [execution-session]
  (and (= :ranked-model-candidates (get-in execution-session [:model-fallback :type]))
       (contains? execution-session :model-fallback)))

(defn- candidate-failure
  [model failure]
  {:model model
   :failure failure})

(defn- exhaustion-failure
  [candidate-failures]
  {:reason :ranked-candidate-exhausted
   :message "Workflow model-query candidates exhausted"
   :candidate-failures candidate-failures})

(defn- stopped-execution-result
  [execution-session]
  {:status :error
   :session-id (:session-id execution-session)
   :assistant-message nil
   :assistant-text ""
   :execution-result nil
   :failure {:reason :workflow-stopped
             :message "Workflow execution stopped before model fallback turn"}})

(defn- execute-with-ranked-fallback!
  [ctx execution-session prompt opts stopped?]
  (let [initial-candidates (vec (fallback-candidates execution-session))
        stopped? (or stopped? (constantly false))]
    (if-not (seq initial-candidates)
      {:status :error
       :session-id (:session-id execution-session)
       :assistant-message nil
       :assistant-text ""
       :execution-result nil
       :failure (exhaustion-failure [])}
      (loop [remaining initial-candidates
             candidate-failures []
             current-session execution-session
             first-candidate? true]
        (if (and (not first-candidate?) (stopped?))
          (stopped-execution-result current-session)
          (let [model (first remaining)
                current-session (if first-candidate?
                                  (assoc current-session :model model)
                                  (attempts/set-execution-session-model! ctx current-session model))
                result (if opts
                         (turn-execution/execute-actor-turn! ctx (:session-id current-session) prompt opts)
                         (turn-execution/execute-actor-turn! ctx (:session-id current-session) prompt))]
            (cond
              (= :ok (:status result))
              result

              (and (next remaining)
                   (get-in result [:failure :fallback-worthy?]))
              (recur (next remaining)
                     (conj candidate-failures (candidate-failure model (:failure result)))
                     current-session
                     false)

              :else
              (let [all-failures (conj candidate-failures (candidate-failure model (:failure result)))]
                {:status :error
                 :session-id (:session-id current-session)
                 :assistant-message (:assistant-message result)
                 :assistant-text (:assistant-text result)
                 :execution-result (:execution-result result)
                 :structured-output (:structured-output result)
                 :failure (if (get-in result [:failure :fallback-worthy?])
                            (exhaustion-failure all-failures)
                            (:failure result))}))))))))

(defn- structured-output-blocked-payload
  [reason message details outputs]
  {:outcome :blocked
   :blocked {:reason reason
             :message message
             :details details}
   :outputs outputs})

(defn- record-actor-pending!
  [working-memory* event-queue* step-id attempt-id kind payload event]
  (swap! working-memory* assoc :pending-actor-result {:kind kind
                                                      :payload payload
                                                      :step-id step-id
                                                      :attempt-id attempt-id
                                                      :updated-at (state/now)})
  (queue/enqueue-event! event-queue* working-memory* event {}))

(defn- session-turn-ok-envelope
  "Compute the success-arm disposition for a completed session turn (pure).

   Derives the per-turn `raw-outputs`, applies final-turn structured-output
   validation, and builds the `:pending-actor-result` envelope. `structured-entry`
   (`[output-key output-spec]`) is non-nil only on the final turn (structured
   `:outputs` are requested on the final turn only, P5); on non-final turns the
   declared structured key is excluded from surface resolution so an absent
   structured value cannot throw.

   Returns the `:branch :success` disposition map consumed by
   `execute-session-turn-outcome` — `:disposition :ok` or `:disposition :blocked`
   (`:invalid-structured-output`). This is the pure OK-envelope computation,
   separated from `execute-session-turn-outcome`'s disposition flow control.

   `turn-result` is the cohesive turn-result map; its `:assistant-text`,
   `:assistant-message`, `:execution-result`, and `:structured-output` co-members
   travel as one named value (no positional transposition risk)."
  [step-def execution-session structured-entry turn-result]
  (let [{:keys [assistant-text assistant-message execution-result structured-output]} turn-result
        logprobs (:execution-result/logprobs execution-result)
        ;; Structured `:outputs` bind the final turn only (P5). On non-final
        ;; turns the declared structured key is NOT produced, so it must be
        ;; excluded from surface resolution to avoid an invalid-resolution
        ;; throw for an absent structured value.
        step-structured-key (first (structured-output/single-structured-output-entry (:outputs step-def)))
        surface-step-def (if (and step-structured-key (nil? structured-entry))
                           (update step-def :outputs dissoc step-structured-key)
                           step-def)
        raw-outputs {:final-llm-reply assistant-text
                     :text assistant-text
                     :transcript (when assistant-message [assistant-message])
                     :logprobs logprobs
                     :session-id (:session-id execution-session)}
        raw-outputs (if-let [[output-key output-spec] structured-entry]
                      (assoc raw-outputs output-key
                             (if (some? structured-output)
                               (structured-output/output-result output-spec assistant-text structured-output)
                               (structured-output/missing-ai-structured-output-result output-spec assistant-text)))
                      raw-outputs)
        structured-result (some-> structured-entry first raw-outputs)
        invalid-structured-output? (and structured-entry
                                        (not (structured-output/valid-output-result? structured-result)))
        normalized-outputs (when-not invalid-structured-output?
                             (workflow-ir/step-output-surfaces
                              surface-step-def
                              {:outcome :ok
                               :outputs raw-outputs}))
        envelope (if invalid-structured-output?
                   {:outcome :blocked
                    :blocked {:reason :invalid-structured-output
                              :message "Workflow structured output failed validation"
                              :details {:output-key (first structured-entry)
                                        :structured-output (:structured-output structured-result)}}
                    :outputs raw-outputs}
                   {:outcome :ok
                    :outputs (merge normalized-outputs raw-outputs)})]
    {:disposition (if (= :blocked (:outcome envelope)) :blocked :ok)
     :branch :success
     :payload envelope
     :raw-outputs raw-outputs
     :assistant-text assistant-text
     :assistant-message assistant-message}))

(defn- execute-session-turn-outcome
  "Run one already-shaped session turn and classify its outcome WITHOUT recording
   or enqueuing.

   `turn-opts` carries the structured-output request and is nil except on the
   final turn (structured `:outputs` are requested on the final turn only, P5).
   `structured-entry` (`[output-key output-spec]`) is non-nil only when structured
   output is requested on this (final) turn.

   Returns a disposition map:
   - `{:disposition :cancelled}` — the run was stopped after the turn (CHECK A);
   - `{:disposition :failed :payload failure :branch :error}`;
   - `{:disposition :blocked :payload blocked-payload :branch :error|:success}`;
   - `{:disposition :ok :payload envelope :branch :success
       :raw-outputs ... :assistant-text ... :assistant-message ...}`.

   `:branch :success` outcomes still require the caller's pre-record stopped?
   recheck (CHECK B); `:branch :error` outcomes do not (preserving the byte-exact
   single-turn N=1 control flow)."
  [ctx execution-session step-def turn-prompt stopped? turn-opts structured-entry]
  (let [turn-result
        (if (fallback-enabled? execution-session)
          (execute-with-ranked-fallback! ctx execution-session turn-prompt turn-opts stopped?)
          (if turn-opts
            (turn-execution/execute-actor-turn! ctx (:session-id execution-session) turn-prompt turn-opts)
            (turn-execution/execute-actor-turn! ctx (:session-id execution-session) turn-prompt)))
        {:keys [status failure structured-output]} turn-result]
    (cond
      (stopped?)
      {:disposition :cancelled}

      (= :error status)
      (if (= :unsupported-structured-output (or (:reason structured-output)
                                                (:reason failure)))
        {:disposition :blocked
         :branch :error
         :payload (structured-output-blocked-payload
                   :unsupported-structured-output
                   (or (:message failure)
                       "Workflow structured output is not supported by the resolved model")
                   {:output-key (first structured-entry)
                    :structured-output structured-output
                    :failure failure}
                   {})}
        {:disposition :failed
         :branch :error
         :payload failure})

      :else
      (session-turn-ok-envelope
       step-def execution-session structured-entry turn-result))))

(defn execute-session-step!
  "Drive ONE session turn (the unnamed N=1 degenerate of the unified prompt-queue)
   and record its post-turn `:pending-actor-result`. Named multi-prompt queues are
   driven by `drive-session-prompt-queue!`, which loops the same per-turn
   primitive (`execute-session-turn-outcome`)."
  ([ctx execution-session step-def step-id attempt-id working-memory* event-queue* prompt]
   (execute-session-step! ctx execution-session step-def step-id attempt-id working-memory* event-queue* prompt nil))
  ([ctx execution-session step-def step-id attempt-id working-memory* event-queue* prompt stopped?]
   (let [stopped? (or stopped? (constantly false))
         structured-entry (structured-output/single-structured-output-entry (:outputs step-def))
         request-result (when-let [[output-key output-spec] structured-entry]
                          (structured-output/structured-output-request output-key output-spec))]
     (cond
       (stopped?)
       (queue/enqueue-event! event-queue* working-memory* :workflow/cancel {})

       (false? (:ok? request-result))
       (record-actor-pending!
        working-memory* event-queue* step-id attempt-id :blocked
        (structured-output-blocked-payload (:reason request-result)
                                           (:message request-result)
                                           (:details request-result)
                                           {})
        :actor/blocked)

       :else
       (let [outcome (execute-session-turn-outcome
                      ctx execution-session step-def prompt stopped?
                      (:opts request-result) structured-entry)]
         (case (:disposition outcome)
           :cancelled
           (queue/enqueue-event! event-queue* working-memory* :workflow/cancel {})

           :failed
           (record-actor-pending!
            working-memory* event-queue* step-id attempt-id :failure (:payload outcome) :actor/failed)

           :blocked
           (if (and (= :success (:branch outcome)) (stopped?))
             (queue/enqueue-event! event-queue* working-memory* :workflow/cancel {})
             (record-actor-pending!
              working-memory* event-queue* step-id attempt-id :blocked (:payload outcome) :actor/blocked))

           :ok
           (if (stopped?)
             (queue/enqueue-event! event-queue* working-memory* :workflow/cancel {})
             (record-actor-pending!
              working-memory* event-queue* step-id attempt-id :success (:payload outcome) :actor/done))))))))

(defn- turn-local-outputs
  "The turn-local output surfaces recorded for one named prompt-group turn."
  [outcome]
  (select-keys (:raw-outputs outcome)
               [:final-llm-reply :text :transcript :logprobs :session-id]))

(defn- post-drain-envelope
  "Build the single post-drain `:pending-actor-result` envelope: the final turn's
   step-level rollup, with the accumulated multi-turn `:transcript` and the ordered
   per-prompt records nested under `:prompt-group-outputs`."
  [final-envelope transcript prompt-group-outputs]
  (-> final-envelope
      (assoc-in [:outputs :transcript] (vec transcript))
      (assoc-in [:outputs :prompt-group-outputs] (vec prompt-group-outputs))))

(defn later-group-turn-prompt
  "Derive a later prompt-group's turn submission against the live child session.

   `materialize-fn` materializes the group's `:contributions` into a conversation
   (`materialize-prompt-group-conversation`); `split-fn`
   (`split-step-session-conversation`) splits it into `:preloaded-messages` +
   `:prompt`. Only the split `:prompt` (the final user message) is returned.

   LIMITATION (task 226 — single submission per later group). Unlike group 0
   (whose `:preloaded-messages` ARE injected at child-session spawn in
   `:step/enter`), a later group's `:preloaded-messages` are intentionally **not**
   re-injected mid-session: later groups rely on the live session's conversation
   memory for shared context (design E1), and only their final message is
   submitted. A later group whose `:contributions` materialize to MORE THAN ONE
   message therefore silently drops every non-final message. Author multi-message
   bodies as group 0, or keep later groups to a single submission (the common
   `:prompt-workflow` single-user-message form). See
   `doc/workflow-grammar.md` (\"Later-group single-submission limitation\")."
  [materialize-fn split-fn workflow-run group]
  (:prompt (split-fn (materialize-fn workflow-run group))))

(defn drive-session-prompt-queue!
  "Drive a named multi-prompt session step as an in-run N-turn drain (design F1).

   Each turn is the same synchronous per-turn primitive
   (`execute-session-turn-outcome`) the N=1 degenerate uses, looped against the
   SAME child session id. The next un-run prompt is selected from RECORDED
   per-prompt progression (`next-un-run-prompt-group`), never an in-memory counter,
   so a prompt whose turn record already exists is never re-submitted (resume
   non-re-fire invariant). Structured `:outputs` are requested on the final turn
   only (P5); the static request-validity gate runs upfront before turn 1 (P13a).
   On drain, emits ONE post-drain `:pending-actor-result` carrying the step-level
   rollup plus the ordered per-prompt records.

   `first-prompt` is group 0's already-split prompt (materialized at `:step/enter`).
   `next-group-prompt-fn` materializes+splits a later group's prompt against the
   live session. `record-turn-fn` persists one per-prompt turn record through the
   live-state guard and returns falsey only when the run was cancelled mid-record."
  [ctx execution-session step-def step-id attempt-id working-memory* event-queue*
   run-id prompt-queue first-prompt next-group-prompt-fn record-turn-fn stopped?]
  (let [stopped? (or stopped? (constantly false))
        structured-entry (structured-output/single-structured-output-entry (:outputs step-def))
        request-result (when-let [[output-key output-spec] structured-entry]
                         (structured-output/structured-output-request output-key output-spec))]
    (cond
      (stopped?)
      (queue/enqueue-event! event-queue* working-memory* :workflow/cancel {})

      ;; Upfront structured request-validity gate (P13a): turn-independent, runs
      ;; before any turn, leaving zero per-prompt records on a request fault.
      (false? (:ok? request-result))
      (record-actor-pending!
       working-memory* event-queue* step-id attempt-id :blocked
       (structured-output-blocked-payload (:reason request-result)
                                          (:message request-result)
                                          (:details request-result)
                                          {})
       :actor/blocked)

      :else
      (loop [transcript []
             prompt-group-outputs []
             final-envelope nil]
        (if (stopped?)
          ;; Between-prompt cancellation checkpoint (R-7): a cancellation
          ;; arriving between turns stops the queue cleanly at the top of the
          ;; iteration, before the next prompt is selected or its turn fires
          ;; (cooperative-cancellation, 225-lineage; realizes P12's
          ;; between-prompts "queue stops"). Symmetric with the N=1
          ;; `execute-session-step!` pre-turn `stopped?` check, so a cancelled
          ;; queue never fires an extra turn's `ai/generate` + tool loop.
          (queue/enqueue-event! event-queue* working-memory* :workflow/cancel {})
          (let [workflow-run (get-in @(:state* ctx) (progression-recording/run-path run-id))
                {:keys [index group final?]}
                (progression-recording/next-un-run-prompt-group workflow-run step-id prompt-queue)]
            (if (nil? group)
            ;; Drained: emit the one post-drain result over the whole queue.
              (record-actor-pending!
               working-memory* event-queue* step-id attempt-id :success
               (post-drain-envelope final-envelope transcript prompt-group-outputs)
               :actor/done)
              (let [turn-prompt (if (zero? index) first-prompt (next-group-prompt-fn group))
                    turn-opts (when final? (:opts request-result))
                    turn-structured-entry (when final? structured-entry)
                    outcome (execute-session-turn-outcome
                             ctx execution-session step-def turn-prompt stopped?
                             turn-opts turn-structured-entry)]
                (case (:disposition outcome)
                  :cancelled
                  (queue/enqueue-event! event-queue* working-memory* :workflow/cancel {})

                  :failed
                  (record-actor-pending!
                   working-memory* event-queue* step-id attempt-id :failure
                   (assoc (:payload outcome)
                          :failed-prompt {:index index :name (:name group)})
                   :actor/failed)

                  :blocked
                  (if (and (= :success (:branch outcome)) (stopped?))
                    (queue/enqueue-event! event-queue* working-memory* :workflow/cancel {})
                    (record-actor-pending!
                     working-memory* event-queue* step-id attempt-id :blocked (:payload outcome) :actor/blocked))

                  :ok
                  (if (stopped?)
                    (queue/enqueue-event! event-queue* working-memory* :workflow/cancel {})
                    (let [outputs (turn-local-outputs outcome)]
                      (if (record-turn-fn index (:name group) outputs)
                        (recur (cond-> transcript
                                 (:assistant-message outcome) (conj (:assistant-message outcome)))
                               (conj prompt-group-outputs
                                     {:index index :name (:name group) :outputs outputs})
                               (:payload outcome))
                      ;; Recording was skipped because the run was cancelled.
                        (queue/enqueue-event! event-queue* working-memory* :workflow/cancel {})))))))))))))
