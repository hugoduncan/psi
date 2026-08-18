(ns psi.turn-runtime.retry
  "Retry machinery for the live turn execution runtime.

   Owns the provider-retry decision and loop support: retry-attempt/deadline
   read-back, give-up termination decisions, active-retry marking/clearing,
   provider error classification, retry metadata shaping, and the interruptible
   retry sleep used between attempts. One prepared turn's retry orchestration
   (in psi.turn-runtime.core) drives this machinery."
  (:require
   [psi.agent-session.extensions :as ext]
   [psi.session-state.model :as session-model]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.accumulator :as accum]
   [psi.turn-runtime.state :as trs]
   [psi.turn-runtime.stream :as stream]))

(defn retry-attempt-for
  [ctx session-id]
  (or (:retry-attempt (ss/get-session-data-in ctx session-id)) 0))

(defn now-ms
  "Injected-clock epoch millis for the retry window, mirroring retry-metadata-for."
  [ctx]
  (let [now-fn (or (:now-fn ctx) #(java.time.Instant/now))]
    (.toEpochMilli ^java.time.Instant (now-fn))))

(defn retry-deadline-for
  "Loop-entry read-back of the canonical retry deadline, mirroring retry-attempt-for.
   A persisted deadline already in the past (stale, e.g. a window left open by a
   turn-end path outside the terminal clears, or a session rehydrated after the
   deadline) is treated as expired: the canonical field is cleared and nil is
   returned so the first retryable failure of the turn opens a fresh window.
   The expired window's :retry-attempt/:retry are reset alongside the deadline
   (the same cleanup the terminal clears do), so a session rehydrated mid-window
   after the deadline (process death during a retry sleep leaves :retry-attempt
   > 0 and a stale :retry map) starts its fresh window at attempt 0 with no
   stale retry metadata visible."
  [ctx session-id]
  (let [deadline (:retry-deadline-ms (ss/get-session-data-in ctx session-id))]
    (if (and (some? deadline) (< deadline (now-ms ctx)))
      (do
        (ss/apply-root-state-update-in!
         ctx
         (ss/session-update session-id
                            #(-> %
                                 (assoc :retry-attempt 0
                                        :retry nil)
                                 (dissoc :retry-deadline-ms))))
        nil)
      deadline)))

(defn give-up-decision
  "Single-sourced retry termination decision (count cap + total-time deadline +
   overshoot truncation). Returns nil (retry) or a structured outcome:
   {:failure-reason ...} for immediate finals, plus :exhausted-reason
   (:count-cap | :deadline) for retry-exhausted, and :final-sleep-ms
   (deadline - now) when the next full delay overshoots the deadline so the
   loop sleeps the truncated remainder before finalizing. Branch order mirrors
   the prior failure-reason-for: non-retryable → retry-disabled → count-cap →
   deadline (count-cap wins when both hold)."
  [{:keys [retryable? retry-enabled? retry-attempt count-cap deadline-ms next-delay-ms now]}]
  (cond
    (not retryable?) {:failure-reason :non-retryable}
    (not retry-enabled?) {:failure-reason :retry-disabled}
    (and (some? count-cap) (>= retry-attempt count-cap))
    {:failure-reason :retry-exhausted :exhausted-reason :count-cap}
    (and (some? deadline-ms) (>= now deadline-ms))
    {:failure-reason :retry-exhausted :exhausted-reason :deadline}
    (and (some? deadline-ms)
         (pos? (- deadline-ms now))
         (> (+ now next-delay-ms) deadline-ms))
    {:failure-reason :retry-exhausted :exhausted-reason :deadline
     :final-sleep-ms (- deadline-ms now)}
    :else nil))

(defn attempt-id-for
  [provider-request-id retry-attempt]
  (str provider-request-id "#attempt-" retry-attempt))

(defn dispatch-provider-event!
  [ctx event-name payload]
  (let [event (assoc payload :type event-name)]
    (when-let [session-id (:session-id payload)]
      (trs/append-provider-event-in! ctx session-id event))
    (when-let [reg (:extension-registry ctx)]
      (ext/dispatch-in reg event-name event))))

(defn provider-error-fields
  [assistant-message]
  (let [stop-reason   (:stop-reason assistant-message)
        error-message (:error-message assistant-message)
        http-status   (:http-status assistant-message)
        error-kind    (session-model/provider-error-kind stop-reason error-message http-status)]
    {:stop-reason stop-reason
     :error-message error-message
     :http-status http-status
     :error-kind error-kind
     :retryable? (contains? #{:rate-limit :timeout :overloaded :provider-unavailable :transport} error-kind)}))

(defn retry-metadata-for
  [ctx assistant-message retry-attempt]
  (let [base-ms              (get-in ctx [:config :auto-retry-base-delay-ms] 2000)
        max-ms               (get-in ctx [:config :auto-retry-max-delay-ms] 60000)
        exponential-delay-ms (session-model/exponential-backoff-ms retry-attempt base-ms max-ms)
        now                  (now-ms ctx)]
    (session-model/retry-metadata (:provider-error/headers assistant-message)
                                  retry-attempt
                                  exponential-delay-ms
                                  now)))

(defn emit-retry-updated-progress!
  [progress-queue session-id]
  (accum/emit-progress! progress-queue {:event-kind :retry-updated
                                        :session-id session-id}))

(defn mark-active-retry!
  [ctx session-id retry-metadata next-retry-attempt retry-deadline-ms progress-queue]
  ;; Assoc the deadline only when non-nil: count-only mode (budget disabled)
  ;; passes nil and must not write a spurious top-level `:retry-deadline-ms nil`
  ;; into canonical session state for the window.
  (ss/apply-root-state-update-in!
   ctx
   (ss/session-update session-id
                      #(cond-> (assoc %
                                      :retry-attempt next-retry-attempt
                                      :retry retry-metadata)
                         (some? retry-deadline-ms) (assoc :retry-deadline-ms retry-deadline-ms))))
  (emit-retry-updated-progress! progress-queue session-id))

(defn retry-clear-needed?
  [session-data clear-deadline?]
  (boolean
   (or (:retry session-data)
       (pos? (or (:retry-attempt session-data) 0))
       (:provider-retry-abort-requested? session-data)
       (and clear-deadline? (some? (:retry-deadline-ms session-data))))))

(defn clear-active-retry!
  [ctx session-id progress-queue & [clear-deadline?]]
  (when (retry-clear-needed? (ss/get-session-data-in ctx session-id) clear-deadline?)
    (ss/apply-root-state-update-in!
     ctx
     (ss/session-update session-id
                        #(cond-> (-> %
                                     (assoc :retry-attempt 0
                                            :retry nil)
                                     (dissoc :provider-retry-abort-requested?))
                           clear-deadline? (dissoc :retry-deadline-ms))))
    (emit-retry-updated-progress! progress-queue session-id)))

(defn active-turn-cancelled?
  [ctx session-id]
  (boolean
   (when-let [turn-ctx (trs/turn-context-in ctx session-id)]
     (some-> turn-ctx :turn-data deref :stream-handle stream/cancelled-stream-handle?))))

(defn provider-retry-cancelled?
  [ctx session-id]
  (boolean
   (or (active-turn-cancelled? ctx session-id)
       (:provider-retry-abort-requested? (ss/get-session-data-in ctx session-id))
       (when-let [cancelled? (:provider-retry-cancelled? ctx)]
         (cancelled? session-id)))))

(defn retry-sleep-poll-ms
  [ctx delay-ms]
  (long (min (max 1 (long (or delay-ms 0)))
             (max 1 (long (get-in ctx [:config :provider-retry-sleep-poll-ms] 250))))))

(defn interruptible-sleep-for-retry!
  [ctx session-id delay-ms]
  (let [deadline-ms (+ (System/currentTimeMillis) (long delay-ms))
        poll-ms     (retry-sleep-poll-ms ctx delay-ms)]
    (loop []
      (let [remaining-ms (- deadline-ms (System/currentTimeMillis))]
        (when (and (pos? remaining-ms)
                   (not (provider-retry-cancelled? ctx session-id)))
          (Thread/sleep (long (min poll-ms remaining-ms)))
          (recur))))))

(defn sleep-for-retry!
  [ctx session-id delay-ms]
  (when (and (not= false (:provider-retry-sleep? ctx))
             (pos? (long (or delay-ms 0)))
             (not (provider-retry-cancelled? ctx session-id)))
    (if-let [sleep-fn (:provider-retry-sleep-fn ctx)]
      (sleep-fn (long delay-ms))
      (interruptible-sleep-for-retry! ctx session-id (long delay-ms))))
  (provider-retry-cancelled? ctx session-id))

(defn cancelled-retry-outcome
  [turn-id failed-attempt next-attempt max-retries retry-enabled? error-fields]
  (merge error-fields
         {:failure-reason :retry-cancelled
          :provider-request-id turn-id
          :turn-id turn-id
          :retry-attempt next-attempt
          :failed-attempt failed-attempt
          :attempt-count (inc failed-attempt)
          :max-retries max-retries
          :retry-enabled? (boolean retry-enabled?)
          :cancelled? true
          :last-error-message (:error-message error-fields)}))
