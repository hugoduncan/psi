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

(defn- long-integer?
  [value]
  (and (integer? value)
       (<= Long/MIN_VALUE value Long/MAX_VALUE)))

(defn- invalid-retry-policy!
  [config-key value requirement]
  (throw (ex-info (str "Invalid retry configuration: " (name config-key) " " requirement)
                  {:config-key config-key
                   :value value
                   :requirement requirement})))

(defn- configured-policy-value
  [ctx config-key]
  (get (:config ctx) config-key (get session-model/default-config config-key)))

(defn resolve-retry-limiters!
  "Resolves the retry policy fields required to decide immediate termination.
   Call only for an enabled, retryable failure. Delay settings remain inert
   until that decision establishes that a retry sleep will be scheduled."
  [ctx]
  (let [timeout   (configured-policy-value ctx :auto-retry-total-timeout-ms)
        count-cap (configured-policy-value ctx :auto-retry-max-retries)]
    (when-not (or (nil? timeout) (long-integer? timeout))
      (invalid-retry-policy! :auto-retry-total-timeout-ms timeout
                             "must be an integer or nil"))
    (when-not (or (nil? count-cap)
                  (and (long-integer? count-cap) (not (neg? count-cap))))
      (invalid-retry-policy! :auto-retry-max-retries count-cap
                             "must be a non-negative integer or nil"))
    (let [timeout-ms     (some-> timeout long)
          budget-active? (boolean (and timeout-ms (pos? timeout-ms)))]
      {:budget-timeout-ms (or timeout-ms 0)
       :budget-active? budget-active?
       :explicit-count-cap (some-> count-cap long)
       :count-cap (cond
                    (some? count-cap) (long count-cap)
                    (not budget-active?) 3
                    :else nil)})))

(defn resolve-retry-delays!
  "Completes a resolved limiter policy with validated, long-valued delay fields.
   Call only after the termination decision establishes that a full or
   truncated retry sleep will be scheduled."
  [ctx limiter-policy]
  (let [base-delay (configured-policy-value ctx :auto-retry-base-delay-ms)
        max-delay  (configured-policy-value ctx :auto-retry-max-delay-ms)]
    (doseq [[config-key value] [[:auto-retry-base-delay-ms base-delay]
                                [:auto-retry-max-delay-ms max-delay]]]
      (when-not (and (long-integer? value) (pos? value))
        (invalid-retry-policy! config-key value "must be a positive integer")))
    (assoc limiter-policy
           :base-delay-ms (long base-delay)
           :max-delay-ms (long max-delay))))

(defn retry-policy-preview
  "Non-throwing policy preview used only before settings become active at an
   enabled, retryable failure. Invalid raw values remain inert at this stage."
  [ctx]
  (let [timeout        (configured-policy-value ctx :auto-retry-total-timeout-ms)
        explicit-cap   (configured-policy-value ctx :auto-retry-max-retries)
        budget-active? (boolean (and (long-integer? timeout) (pos? timeout)))]
    {:budget-active? budget-active?
     :count-cap (cond
                  (and (long-integer? explicit-cap) (not (neg? explicit-cap))) (long explicit-cap)
                  (not budget-active?) 3
                  :else nil)}))

(defn deadline-ms
  "Adds a positive timeout to epoch millis, saturating at Long/MAX_VALUE."
  [now timeout-ms]
  (if (> now (- Long/MAX_VALUE timeout-ms))
    Long/MAX_VALUE
    (+ now timeout-ms)))

(defn now-ms
  "Injected-clock epoch millis for the retry window, mirroring retry-metadata-for."
  [ctx]
  (let [now-fn (or (:now-fn ctx) #(java.time.Instant/now))]
    (.toEpochMilli ^java.time.Instant (now-fn))))

(defn retry-deadline-for
  "Loop-entry read-back of the canonical retry deadline, mirroring retry-attempt-for.
   Budget-disabled (count-only) mode has no deadline at all: a leftover FUTURE
   canonical :retry-deadline-ms from a prior budget-active window (e.g. a session
   persisted mid-window and rehydrated with :auto-retry-total-timeout-ms
   nil/absent/<= 0) must not bind the loop, so it is cleared and nil is yielded —
   the give-up predicate then evaluates only the count cap, per the design's
   Approach 1 disable semantics. The stale :retry-attempt/:retry residue of the
   prior window is reset alongside the deadline (mirroring the stale-past
   branch): without the reset, a stale attempt >= the count-only fallback 3
   gives up at the FIRST failure with 0 retries, or the backoff resumes
   mid-sequence with a stale :retry map visible.
   A persisted deadline already in the past (stale, e.g. a window left open by a
   turn-end path outside the terminal clears, or a session rehydrated after the
   deadline) is treated as expired: the canonical field is cleared and nil is
   returned so the first retryable failure of the turn opens a fresh window.
   The expired window's :retry-attempt/:retry are reset alongside the deadline
   (the same cleanup the terminal clears do), so a session rehydrated mid-window
   after the deadline (process death during a retry sleep leaves :retry-attempt
   > 0 and a stale :retry map) starts its fresh window at attempt 0 with no
   stale retry metadata visible.
   Both cases — budget-disabled leftover and stale-past — share a single branch:
   each requires a present canonical deadline, clears it, resets the same
   :retry-attempt/:retry residue, and yields nil; only the predicate differs
   (budget disabled, vs deadline already in the past)."
  [ctx session-id budget-active?]
  (let [deadline (:retry-deadline-ms (ss/get-session-data-in ctx session-id))]
    (cond
      (and (some? deadline)
           (or (not budget-active?) (< deadline (now-ms ctx))))
      (do
        (ss/apply-root-state-update-in!
         ctx
         (ss/session-update session-id
                            #(-> %
                                 (assoc :retry-attempt 0
                                        :retry nil)
                                 (dissoc :retry-deadline-ms))))
        nil)

      :else deadline)))

(defn- retry-min-clock-advance-ms
  "Minimum injected-clock advance (ms) between consecutive scheduled retries
   below which a sleep-disabled, budget-active, cap-free retry seam is treated
   as a non-advancing (hot-loop) clock. Derived from the resolved backoff
   delays — (min :base-delay-ms :max-delay-ms) — so downstream retry machinery
   does not reinterpret raw operator config. Overridable per-test via
   :retry-min-clock-advance-ms on the ctx (e.g. for a cap-free budget-active
   test whose smallest delay is a provider Retry-After below the configured
   base)."
  [ctx {:keys [base-delay-ms max-delay-ms]}]
  (or (:retry-min-clock-advance-ms ctx)
      (min base-delay-ms max-delay-ms)))

(defn assert-test-seam-no-hot-loop!
  "Fail fast when the test-seam retry loop cannot reach its deadline: with real
   retry sleeps disabled — either :provider-retry-sleep? false or an injected
   :provider-retry-sleep-fn (no-op or otherwise) — an active total-time budget,
   no explicit count cap (sentinel-nil default :auto-retry-max-retries), and a
   clock that did not advance between consecutive scheduled retries, a
   persistent retryable failure would spin until the REAL wall-clock deadline
   (10 minutes with the default :auto-retry-total-timeout-ms 600000). Pre-change
   the same test-seam misconfiguration terminated after the default 3 attempts;
   the budget-active default now has no count limiter, so the seam requires an
   ADVANCING :now-fn (e.g. an atom-backed clock advanced by
   :provider-retry-sleep-fn) whenever the budget is active.

   All session contexts supply a default wall-clock :now-fn
   (java.time.Instant/now) and create fresh fn instances, so 'no injected
   :now-fn' cannot be detected statically; the behavioral check below treats an
   injected clock that advanced less than the resolved policy's minimum
   backoff delay between retries as non-advancing. The threshold remains
   overridable via :retry-min-clock-advance-ms on the ctx, so sub-second base
   delays do not false-positive. last-retry-now is the previous scheduled
   retry's now-ms (nil on the first retry), now the current failed attempt's."
  [ctx retry-policy last-retry-now now]
  (let [{:keys [budget-active? count-cap]} retry-policy
        min-advance (retry-min-clock-advance-ms ctx retry-policy)]
    (when (and (or (= false (:provider-retry-sleep? ctx))
                   (some? (:provider-retry-sleep-fn ctx)))
               budget-active?
               (nil? count-cap)
               (some? last-retry-now)
               (< (- now last-retry-now) min-advance))
      (throw (ex-info "Test-seam misconfiguration: real retry sleeps disabled (:provider-retry-sleep? false or :provider-retry-sleep-fn) with an active :auto-retry-total-timeout-ms budget, no explicit :auto-retry-max-retries, and a non-advancing clock would hot-loop a persistent retryable failure until the real wall-clock deadline. Inject an ADVANCING :now-fn (e.g. an atom-backed clock advanced by :provider-retry-sleep-fn) or set an explicit :auto-retry-max-retries."
                      {:provider-retry-sleep? (:provider-retry-sleep? ctx)
                       :provider-retry-sleep-fn (some? (:provider-retry-sleep-fn ctx))
                       :auto-retry-total-timeout-ms (get-in ctx [:config :auto-retry-total-timeout-ms])
                       :auto-retry-max-retries (get-in ctx [:config :auto-retry-max-retries])
                       :clock-advance-ms (- now last-retry-now)
                       :min-retry-clock-advance-ms min-advance})))))

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
         ;; Subtraction-based: `(- deadline-ms now)` is bounded by the window
         ;; (<= the total-timeout), while `(+ now next-delay-ms)` would
         ;; overflow for a near-Long/MAX provider `Retry-After` delay.
         (> next-delay-ms (- deadline-ms now)))
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
  [ctx assistant-message retry-attempt {:keys [base-delay-ms max-delay-ms]}]
  (let [exponential-delay-ms (session-model/exponential-backoff-ms retry-attempt
                                                                   base-delay-ms
                                                                   max-delay-ms)
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

(def ^:private retry-clear-modes
  #{:between-attempts :window-close})

(defn- assert-retry-clear-mode!
  [mode]
  (when-not (contains? retry-clear-modes mode)
    (throw (ex-info "Invalid retry clear mode"
                    {:mode mode
                     :supported-modes retry-clear-modes}))))

(defn retry-clear-needed?
  [session-data mode]
  (assert-retry-clear-mode! mode)
  (boolean
   (or (:retry session-data)
       (pos? (or (:retry-attempt session-data) 0))
       (:provider-retry-abort-requested? session-data)
       (and (= :window-close mode)
            (some? (:retry-deadline-ms session-data))))))

(defn clear-active-retry!
  [ctx session-id progress-queue mode]
  (assert-retry-clear-mode! mode)
  (when (retry-clear-needed? (ss/get-session-data-in ctx session-id) mode)
    (ss/apply-root-state-update-in!
     ctx
     (ss/session-update session-id
                        #(cond-> (-> %
                                     (assoc :retry-attempt 0
                                            :retry nil)
                                     (dissoc :provider-retry-abort-requested?))
                           (= :window-close mode) (dissoc :retry-deadline-ms))))
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
  (let [sleep-deadline-ms (deadline-ms (System/currentTimeMillis) (long delay-ms))
        poll-ms           (retry-sleep-poll-ms ctx delay-ms)]
    (loop []
      (let [remaining-ms (- sleep-deadline-ms (System/currentTimeMillis))]
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
