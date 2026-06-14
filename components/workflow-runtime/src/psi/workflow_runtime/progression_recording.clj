(ns psi.workflow-runtime.progression-recording
  "Phase A record/update substrate for workflow runs.

   These helpers record attempt, step, and judge outcomes without owning workflow
   control flow. They are the canonical mutation substrate for the statechart-
   driven runtime.")

(defn- now []
  (java.time.Instant/now))

(defn run-path [run-id]
  [:workflows :runs run-id])

(defn latest-attempt-index
  [workflow-run step-id]
  (let [attempts (get-in workflow-run [:step-runs step-id :attempts])]
    (when (seq attempts)
      (dec (count attempts)))))

(defn latest-attempt
  [workflow-run step-id]
  (some->> (latest-attempt-index workflow-run step-id)
           (get-in workflow-run [:step-runs step-id :attempts])))

(defn step-definition
  [workflow-run step-id]
  (or (some->> (get-in workflow-run [:effective-definition :canonical-ir :steps])
               (filter #(= step-id (:name %)))
               first)
      (get-in workflow-run [:effective-definition :steps step-id])))

(defn retry-policy
  [workflow-run step-id]
  (:retry-policy (step-definition workflow-run step-id)))

(defn attempt-count
  [workflow-run step-id]
  (count (get-in workflow-run [:step-runs step-id :attempts])))

(defn retry-available?
  [workflow-run step-id failure-kind]
  (let [{:keys [max-attempts retry-on]} (retry-policy workflow-run step-id)]
    (and (contains? (or retry-on #{}) failure-kind)
         (< (attempt-count workflow-run step-id) (or max-attempts 1)))))

(defn append-history
  [workflow-run event data]
  (-> workflow-run
      (update :history (fnil conj []) {:event event :timestamp (now) :data data})
      (assoc :updated-at (now))))

(defn update-attempt
  [workflow-run step-id f]
  (if-let [idx (latest-attempt-index workflow-run step-id)]
    (update-in workflow-run [:step-runs step-id :attempts idx] f)
    workflow-run))

(defn start-latest-attempt
  "Mark the latest attempt for `step-id` as :running and the run as :running.

   Optional `attempt-data` is merged onto the latest attempt before status/start
   metadata so attempt-local execution records such as effective invoke args can
   be captured canonically on the attempt that actually ran."
  ([state run-id step-id]
   (start-latest-attempt state run-id step-id nil))
  ([state run-id step-id attempt-data]
   (update-in state (run-path run-id)
              (fn [workflow-run]
                (-> workflow-run
                    (update-attempt step-id #(cond-> (merge % (or attempt-data {}))
                                               true (assoc :status :running
                                                           :updated-at (now))))
                    (assoc :status :running
                           :current-step-id step-id
                           :blocked nil
                           :updated-at (now))
                    (append-history :workflow/attempt-started
                                    {:run-id run-id
                                     :step-id step-id
                                     :attempt-id (:attempt-id (latest-attempt workflow-run step-id))}))))))

(defn increment-iteration-count
  "Increment the iteration count on a step-run. Starts at 0, incremented on every entry."
  [state run-id step-id]
  (update-in state (conj (run-path run-id) :step-runs step-id :iteration-count)
             (fnil inc 0)))

(defn merge-latest-attempt-data
  "Merge additional canonical execution data onto the latest attempt without
   changing workflow control-flow status/history.

   Used when step execution can only determine attempt-local runtime details
   such as effective invoke args after the attempt has already been started."
  [state run-id step-id attempt-data]
  (if (seq attempt-data)
    (update-in state (run-path run-id)
               (fn [workflow-run]
                 (-> workflow-run
                     (update-attempt step-id #(-> %
                                                  (merge attempt-data)
                                                  (assoc :updated-at (now))))
                     (assoc :updated-at (now)))))
    state))

;;; Per-prompt turn records (task 226 Slice 3).
;;;
;;; A multi-prompt `:session` step runs N turns inside ONE statechart step,
;;; against ONE shared child session. Each completed turn for a NAMED prompt-group
;;; is recorded as an ordered per-prompt turn record on the step's latest attempt
;;; under `:prompt-group-turns`. The unnamed N=1 degenerate group records NO
;;; per-prompt record (it contributes only the step-level rollup, design C3).
;;;
;;; Records are the canonical progression substrate the queue driver consults to
;;; pick the next UN-RUN prompt: each record carries its static queue `:index`, so
;;; selection is progression-driven (read recorded indices), never an in-memory
;;; counter, making the drain idempotent under resume (design F1) — a group whose
;;; turn record already exists is never re-submitted.

(defn- latest-attempt-map
  "The latest attempt map for `step-id` (or nil). Distinct from `latest-attempt`,
   which returns the whole attempts vector; this resolves the single latest
   attempt map by index."
  [workflow-run step-id]
  (when-let [idx (latest-attempt-index workflow-run step-id)]
    (get-in workflow-run [:step-runs step-id :attempts idx])))

(defn prompt-group-turn-records
  "Read the ordered per-prompt turn records recorded on the latest attempt for
   `step-id`. Returns `[]` when none recorded (single-turn / unnamed degenerate)."
  [workflow-run step-id]
  (or (:prompt-group-turns (latest-attempt-map workflow-run step-id)) []))

(defn recorded-prompt-group-indices
  "Set of static queue indices that already have a recorded per-prompt turn record
   on the latest attempt for `step-id`."
  [workflow-run step-id]
  (into #{} (map :index) (prompt-group-turn-records workflow-run step-id)))

(defn next-un-run-prompt-group
  "Select the next un-run prompt-group for `step-id` from recorded progression.

   `prompt-queue` is the ordered normalized prompt-queue (one group per turn). The
   next un-run group is the LOWEST static queue position whose per-prompt turn
   record does not yet exist on the latest attempt (progression-driven, not a
   counter). Returns `{:index i :group group :final? bool}` for that group, or
   `nil` when every group has a recorded turn (the queue is drained)."
  [workflow-run step-id prompt-queue]
  (let [recorded (recorded-prompt-group-indices workflow-run step-id)
        n (count prompt-queue)]
    (some (fn [i]
            (when-not (contains? recorded i)
              {:index i
               :group (nth prompt-queue i)
               :final? (= i (dec n))}))
          (range n))))

(defn record-prompt-group-turn
  "Append one completed per-prompt turn record to the latest attempt for `step-id`
   without owning control flow.

   `turn-record` is `{:index i :name group-name :outputs {...}}` for a NAMED group
   (the unnamed N=1 degenerate records no per-prompt record). Recording is
   idempotent on `:index`: a turn whose index already has a record is not
   re-appended, upholding the resume non-re-fire invariant (design F1)."
  [state run-id step-id turn-record]
  (let [idx (:index turn-record)]
    (update-in state (run-path run-id)
               (fn [workflow-run]
                 (if (contains? (recorded-prompt-group-indices workflow-run step-id) idx)
                   workflow-run
                   (-> workflow-run
                       (update-attempt step-id
                                       #(update % :prompt-group-turns
                                                (fnil conj [])
                                                (assoc turn-record :recorded-at (now))))
                       (assoc :updated-at (now))
                       (append-history :workflow/prompt-group-turn-recorded
                                       {:run-id run-id
                                        :step-id step-id
                                        :prompt-index idx
                                        :prompt-name (:name turn-record)})))))))

(defn record-step-result
  "Record a successful step result on the latest attempt without owning control flow.

   Used by Phase A statechart-driven execution for non-judged acting success.
   Does not mutate run status or current-step-id."
  [state run-id step-id envelope]
  (update-in state (run-path run-id)
             (fn [workflow-run]
               (-> workflow-run
                   (update-attempt step-id #(assoc %
                                                   :status :succeeded
                                                   :result-envelope envelope
                                                   :validation-outcome {:accepted? true}
                                                   :updated-at (now)
                                                   :finished-at (now)))
                   (assoc-in [:step-runs step-id :accepted-result] envelope)
                   (assoc :updated-at (now))
                   (append-history :workflow/result-received
                                   {:run-id run-id
                                    :step-id step-id
                                    :envelope envelope})))))

(defn record-attempt-execution-failure
  "Record execution failure on the latest attempt without owning control flow.

   Used by Phase A statechart-driven execution for acting failure exits.
   Does not mutate run status or current-step-id."
  [state run-id step-id execution-error]
  (update-in state (run-path run-id)
             (fn [workflow-run]
               (-> workflow-run
                   (update-attempt step-id #(assoc %
                                                   :status :execution-failed
                                                   :execution-error execution-error
                                                   :updated-at (now)
                                                   :finished-at (now)))
                   (assoc :updated-at (now))
                   (append-history :workflow/execution-failure-recorded
                                   {:run-id run-id
                                    :step-id step-id
                                    :attempt-id (:attempt-id (latest-attempt workflow-run step-id))
                                    :execution-error execution-error})))))

(defn record-actor-result
  "Record the actor's ok envelope and accepted-result on the step-run without advancing.

   Used for judged steps where the judge routing determines the next step,
   not the normal submit-result-envelope advancement path.

   This is currently an alias of `record-step-result`; it remains named separately
   so Phase A callers can stay semantically explicit about judged-vs-linear usage."
  [state run-id step-id envelope]
  (record-step-result state run-id step-id envelope))

(defn record-judge-result
  "Record judge metadata on the latest attempt without owning control flow.

   Used by Phase A judged exits before the chart-owned routing transition is
   projected externally. Does not mutate run status or current-step-id."
  [state run-id step-id judge-result]
  (let [{:keys [judge-session-id judge-output judge-event]} judge-result]
    (update-in state (run-path run-id)
               (fn [workflow-run]
                 (-> workflow-run
                     (update-attempt step-id
                                     #(assoc %
                                             :judge-session-id judge-session-id
                                             :judge-output judge-output
                                             :judge-event judge-event
                                             :updated-at (now)))
                     (assoc :updated-at (now))
                     (append-history :workflow/judge-recorded
                                     {:run-id run-id
                                      :step-id step-id
                                      :attempt-id (:attempt-id (latest-attempt workflow-run step-id))
                                      :judge-event judge-event
                                      :judge-output judge-output}))))))
