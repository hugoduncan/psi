(ns psi.agent-session.workflow-sequential-compat-test-support
  "Test-only sequential workflow helpers retained while migrating compatibility-era proofs.

   These helpers model the old linear progression semantics for tests only.
   Production/runtime code must use `workflow-runtime`, `workflow-statechart-runtime`,
   and `workflow-progression-recording` directly."
  (:require
   [malli.core :as m]
   [psi.agent-session.workflow-model :as workflow-model]
   [psi.agent-session.workflow-progression-recording :as rec]
   [psi.agent-session.workflow-statechart :as workflow-statechart]))

(defn- now []
  (java.time.Instant/now))

(defn- run-path [run-id]
  (rec/run-path run-id))

(defn- step-definition
  [workflow-run step-id]
  (rec/step-definition workflow-run step-id))

(defn- append-history
  [workflow-run event data]
  (rec/append-history workflow-run event data))

(defn- update-attempt
  [workflow-run step-id f]
  (rec/update-attempt workflow-run step-id f))

(defn- latest-attempt
  [workflow-run step-id]
  (rec/latest-attempt workflow-run step-id))

(defn- retry-available?
  [workflow-run step-id failure-kind]
  (rec/retry-available? workflow-run step-id failure-kind))

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
  (workflow-statechart/next-step-id (:effective-definition workflow-run) step-id))

(defn submit-result-envelope
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

(defn submit-judged-result
  [state run-id step-id judge-result]
  (let [{:keys [judge-output judge-event routing-result]} judge-result
        {:keys [action target]} routing-result]
    (update-in (rec/record-judge-result state run-id step-id judge-result) (run-path run-id)
               (fn [workflow-run]
                 (let [latest (latest-attempt workflow-run step-id)]
                   (case action
                     :goto
                     (-> workflow-run
                         (assoc :current-step-id target
                                :status :running)
                         (append-history :verdict/goto
                                         {:run-id run-id
                                          :step-id step-id
                                          :attempt-id (:attempt-id latest)
                                          :target target
                                          :judge-event judge-event}))

                     :complete
                     (-> workflow-run
                         (assoc :status :completed
                                :current-step-id nil
                                :finished-at (now)
                                :terminal-outcome {:outcome :completed
                                                   :step-id step-id
                                                   :attempt-id (:attempt-id latest)
                                                   :result-envelope (get-in workflow-run [:step-runs step-id :accepted-result])})
                         (append-history :verdict/advance
                                         {:run-id run-id
                                          :step-id step-id
                                          :attempt-id (:attempt-id latest)
                                          :judge-event judge-event}))

                     (-> workflow-run
                         (assoc :status :failed
                                :finished-at (now)
                                :terminal-outcome {:outcome :failed
                                                   :reason (or (:reason routing-result) :judge-no-match)
                                                   :step-id step-id
                                                   :attempt-id (:attempt-id latest)
                                                   :judge-output judge-output})
                         (append-history :verdict/exhausted
                                         {:run-id run-id
                                          :step-id step-id
                                          :attempt-id (:attempt-id latest)
                                          :reason (or (:reason routing-result) :judge-no-match)
                                          :judge-output judge-output}))))))))