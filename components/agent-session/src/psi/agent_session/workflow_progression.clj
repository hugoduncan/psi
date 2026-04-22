(ns psi.agent-session.workflow-progression
  "Pure workflow result submission, validation, retry, block, resume, and failure progression.

   This slice owns deterministic control-plane progression over workflow runs once
   step attempts exist and produce results or failures."
  (:require
   [malli.core :as m]
   [psi.agent-session.workflow-model :as workflow-model]
   [psi.agent-session.workflow-statechart :as workflow-statechart]))

(defn- now []
  (java.time.Instant/now))

(defn- run-path [run-id]
  [:workflows :runs run-id])

(defn- latest-attempt-index
  [workflow-run step-id]
  (let [attempts (get-in workflow-run [:step-runs step-id :attempts])]
    (when (seq attempts)
      (dec (count attempts)))))

(defn latest-attempt
  [workflow-run step-id]
  (some->> (latest-attempt-index workflow-run step-id)
           (get-in workflow-run [:step-runs step-id :attempts])))

(defn- step-definition
  [workflow-run step-id]
  (get-in workflow-run [:effective-definition :steps step-id]))

(defn- retry-policy
  [workflow-run step-id]
  (:retry-policy (step-definition workflow-run step-id)))

(defn- attempt-count
  [workflow-run step-id]
  (count (get-in workflow-run [:step-runs step-id :attempts])))

(defn retry-available?
  [workflow-run step-id failure-kind]
  (let [{:keys [max-attempts retry-on]} (retry-policy workflow-run step-id)]
    (and (contains? (or retry-on #{}) failure-kind)
         (< (attempt-count workflow-run step-id) (or max-attempts 1)))))

(defn- append-history
  [workflow-run event data]
  (-> workflow-run
      (update :history (fnil conj []) {:event event :timestamp (now) :data data})
      (assoc :updated-at (now))))

(defn- update-attempt
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

(defn- generic-envelope-validation
  [envelope]
  (let [ok? (m/validate workflow-model/workflow-result-envelope-schema envelope)]
    {:accepted? ok?
     :errors (when-not ok?
               [(m/explain workflow-model/workflow-result-envelope-schema envelope)])}))

(defn- ok-envelope-step-validation
  [step-definition envelope]
  (let [schema (:result-schema step-definition)
        ok?    (m/validate schema envelope)]
    {:accepted? ok?
     :errors (when-not ok?
               [(m/explain schema envelope)])}))

(defn- next-step-id
  [workflow-run step-id]
  ((:next-step-id-fn (workflow-statechart/compile-definition (:effective-definition workflow-run))) step-id))

(defn submit-result-envelope
  "Submit a structured result envelope for the latest attempt of `step-id`.

   Progression rules:
   - invalid generic envelope => attempt validation-failed; run either remains on step for retry or fails
   - blocked envelope => attempt blocked; run blocked
   - valid ok envelope => step accepted; run advances or completes
   - step-schema validation failure => attempt validation-failed; run either remains on step for retry or fails"
  [state run-id step-id envelope]
  (update-in state (run-path run-id)
             (fn [workflow-run]
               (let [generic-validation (generic-envelope-validation envelope)
                     workflow-run       (-> workflow-run
                                            (assoc :status :validating :updated-at (now))
                                            (append-history :workflow/result-received
                                                            {:run-id run-id
                                                             :step-id step-id
                                                             :envelope envelope}))
                     latest             (latest-attempt workflow-run step-id)]
                 (cond
                   (not (:accepted? generic-validation))
                   (let [retry? (retry-available? workflow-run step-id :validation-failed)]
                     (-> workflow-run
                         (update-attempt step-id #(assoc %
                                                         :status :validation-failed
                                                         :result-envelope envelope
                                                         :validation-outcome generic-validation
                                                         :updated-at (now)
                                                         :finished-at (now)))
                         (assoc :status (if retry? :running :failed)
                                :updated-at (now))
                         (cond-> retry?
                           (assoc :current-step-id step-id))
                         (cond-> (not retry?)
                           (assoc :finished-at (now)
                                  :terminal-outcome {:outcome :failed
                                                     :reason :validation-failed
                                                     :step-id step-id
                                                     :attempt-id (:attempt-id latest)}))
                         (append-history (if retry? :workflow/retry :workflow/fail)
                                         {:run-id run-id
                                          :step-id step-id
                                          :reason :validation-failed})))

                   (= :blocked (:outcome envelope))
                   (-> workflow-run
                       (update-attempt step-id #(assoc %
                                                       :status :blocked
                                                       :result-envelope envelope
                                                       :validation-outcome {:accepted? true}
                                                       :blocked (:blocked envelope)
                                                       :updated-at (now)
                                                       :finished-at (now)))
                       (assoc :status :blocked
                              :blocked (:blocked envelope)
                              :updated-at (now))
                       (append-history :workflow/block
                                       {:run-id run-id
                                        :step-id step-id
                                        :attempt-id (:attempt-id latest)
                                        :blocked (:blocked envelope)}))

                   :else
                   (let [step-validation (ok-envelope-step-validation (step-definition workflow-run step-id) envelope)]
                     (if-not (:accepted? step-validation)
                       (let [retry? (retry-available? workflow-run step-id :validation-failed)]
                         (-> workflow-run
                             (update-attempt step-id #(assoc %
                                                             :status :validation-failed
                                                             :result-envelope envelope
                                                             :validation-outcome step-validation
                                                             :updated-at (now)
                                                             :finished-at (now)))
                             (assoc :status (if retry? :running :failed)
                                    :updated-at (now))
                             (cond-> retry?
                               (assoc :current-step-id step-id))
                             (cond-> (not retry?)
                               (assoc :finished-at (now)
                                      :terminal-outcome {:outcome :failed
                                                         :reason :validation-failed
                                                         :step-id step-id
                                                         :attempt-id (:attempt-id latest)}))
                             (append-history (if retry? :workflow/retry :workflow/fail)
                                             {:run-id run-id
                                              :step-id step-id
                                              :reason :validation-failed})))
                       (if-let [next-step (next-step-id workflow-run step-id)]
                         (-> workflow-run
                             (update-attempt step-id #(assoc %
                                                             :status :succeeded
                                                             :result-envelope envelope
                                                             :validation-outcome step-validation
                                                             :updated-at (now)
                                                             :finished-at (now)))
                             (assoc-in [:step-runs step-id :accepted-result] envelope)
                             (assoc :status :running
                                    :current-step-id next-step
                                    :blocked nil
                                    :updated-at (now))
                             (append-history :workflow/step-succeeded
                                             {:run-id run-id
                                              :step-id step-id
                                              :attempt-id (:attempt-id latest)
                                              :next-step-id next-step}))
                         (-> workflow-run
                             (update-attempt step-id #(assoc %
                                                             :status :succeeded
                                                             :result-envelope envelope
                                                             :validation-outcome step-validation
                                                             :updated-at (now)
                                                             :finished-at (now)))
                             (assoc-in [:step-runs step-id :accepted-result] envelope)
                             (assoc :status :completed
                                    :current-step-id nil
                                    :blocked nil
                                    :updated-at (now)
                                    :finished-at (now)
                                    :terminal-outcome {:outcome :completed
                                                       :step-id step-id
                                                       :attempt-id (:attempt-id latest)
                                                       :result-envelope envelope})
                             (append-history :workflow/complete
                                             {:run-id run-id
                                              :step-id step-id
                                              :attempt-id (:attempt-id latest)}))))))))))

(defn record-execution-failure
  "Record execution failure for the latest attempt of `step-id`.

   Retryable execution failures keep the run on the same step and record a retry event.
   Non-retryable or exhausted failures fail the workflow run terminally."
  [state run-id step-id execution-error]
  (update-in state (run-path run-id)
             (fn [workflow-run]
               (let [retry? (retry-available? workflow-run step-id :execution-failed)
                     latest (latest-attempt workflow-run step-id)]
                 (-> workflow-run
                     (update-attempt step-id #(assoc %
                                                     :status :execution-failed
                                                     :execution-error execution-error
                                                     :updated-at (now)
                                                     :finished-at (now)))
                     (assoc :status (if retry? :running :failed)
                            :updated-at (now))
                     (cond-> retry?
                       (assoc :current-step-id step-id))
                     (cond-> (not retry?)
                       (assoc :finished-at (now)
                              :terminal-outcome {:outcome :failed
                                                 :reason :execution-failed
                                                 :step-id step-id
                                                 :attempt-id (:attempt-id latest)
                                                 :execution-error execution-error}))
                     (append-history (if retry? :workflow/retry :workflow/fail)
                                     {:run-id run-id
                                      :step-id step-id
                                      :attempt-id (:attempt-id latest)
                                      :reason :execution-failed
                                      :execution-error execution-error}))))))

(defn resume-blocked-run
  "Resume a blocked run, clearing blocked payload and returning to :running on the same step.

   Resume does not mutate the blocked attempt; callers should create a new attempt afterwards."
  [state run-id]
  (update-in state (run-path run-id)
             (fn [workflow-run]
               (-> workflow-run
                   (assoc :status :running
                          :blocked nil
                          :updated-at (now))
                   (append-history :workflow/resume
                                   {:run-id run-id
                                    :step-id (:current-step-id workflow-run)})))))

(defn cancel-run
  "Cancel a non-terminal workflow run.

   Cancellation is runtime-owned and records a terminal outcome plus history entry."
  [state run-id reason]
  (update-in state (run-path run-id)
             (fn [workflow-run]
               (-> workflow-run
                   (assoc :status :cancelled
                          :blocked nil
                          :current-step-id (:current-step-id workflow-run)
                          :updated-at (now)
                          :finished-at (now)
                          :terminal-outcome {:outcome :cancelled
                                             :reason reason
                                             :step-id (:current-step-id workflow-run)})
                   (append-history :workflow/cancel
                                   {:run-id run-id
                                    :step-id (:current-step-id workflow-run)
                                    :reason reason})))))
