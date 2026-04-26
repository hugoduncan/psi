(ns psi.agent-session.workflow-progression-recording
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
  (get-in workflow-run [:effective-definition :steps step-id]))

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
  "Mark the latest attempt for `step-id` as :running and the run as :running."
  [state run-id step-id]
  (update-in state (run-path run-id)
             (fn [workflow-run]
               (-> workflow-run
                   (update-attempt step-id #(-> %
                                                (assoc :status :running
                                                       :updated-at (now))))
                   (assoc :status :running
                          :current-step-id step-id
                          :blocked nil
                          :updated-at (now))
                   (append-history :workflow/attempt-started
                                   {:run-id run-id
                                    :step-id step-id
                                    :attempt-id (:attempt-id (latest-attempt workflow-run step-id))})))))

(defn increment-iteration-count
  "Increment the iteration count on a step-run. Starts at 0, incremented on every entry."
  [state run-id step-id]
  (update-in state (conj (run-path run-id) :step-runs step-id :iteration-count)
             (fnil inc 0)))

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
