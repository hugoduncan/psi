(ns psi.session-state.model
  "Canonical session-state model authority: session schemas, defaults, pure
   derived predicates, and entry helpers. No atom, I/O, or runtime ownership."
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   [psi.agent-session.scheduler :as scheduler]))

(def thinking-level-schema
  [:enum :off :minimal :low :medium :high :xhigh])

(def speed-mode-schema
  [:enum :normal :fast])

(def effort-override-schema
  [:enum :low :medium :high :xhigh])

(def ui-type-schema
  [:enum :console :tui :emacs])

(def model-schema
  [:map
   [:provider :string]
   [:id :string]
   [:reasoning {:optional true} :boolean]])

(def session-profile-settings-schema
  [:map
   [:model {:optional true} [:maybe model-schema]]
   [:thinking-level {:optional true} thinking-level-schema]
   [:speed-mode {:optional true} speed-mode-schema]
   [:effort-override {:optional true} [:maybe effort-override-schema]]])

(def scoped-model-schema
  [:map
   [:model model-schema]
   [:thinking-level thinking-level-schema]])

(def skill-schema
  [:map
   [:name :string]
   [:description :string]
   [:lambda-description {:optional true} [:maybe :string]]
   [:file-path :string]
   [:base-dir :string]
   [:source [:enum :user :project :path]]
   [:disable-model-invocation :boolean]])

(def prompt-template-schema
  [:map
   [:name :string]
   [:description :string]
   [:content :string]
   [:source [:enum :user :project :path]]
   [:file-path :string]])

(def extension-schema
  [:map
   [:path :string]
   [:resolved-path {:optional true} :string]
   [:handlers {:optional true} [:set :string]]
   [:tools {:optional true} [:set :string]]
   [:commands {:optional true} [:set :string]]
   [:flags {:optional true} [:set :string]]
   [:shortcuts {:optional true} [:set :string]]])

(def session-entry-kind-schema
  [:enum :message :mid-system :thinking-level :model :compaction :branch-summary
   :custom :custom-message :label :session-info :logprobs])

(def session-entry-schema
  [:map
   [:id :string]
   [:parent-id {:optional true} [:maybe :string]]
   [:timestamp inst?]
   [:kind session-entry-kind-schema]
   [:data {:optional true} :map]])

(def cache-breakpoint-schema
  [:enum :system :tools])

(def prompt-mode-schema
  [:enum :lambda :prose])

(def response-mode-schema
  [:enum :streaming :non-streaming])

(def interruption-reason-schema
  [:enum :user-abort :deferred-interrupt :session-close :context-shutdown])

(def prompt-component-schema
  [:enum :preamble :context-files :skills :runtime-metadata])

(def prompt-component-selection-schema
  [:map
   [:agents-md? {:optional true} :boolean]
   [:extension-prompt-contributions {:optional true} [:maybe [:vector :string]]]
   [:tool-names {:optional true} [:maybe [:vector :string]]]
   [:skill-names {:optional true} [:maybe [:vector :string]]]
   [:components {:optional true} [:maybe [:set prompt-component-schema]]]])

(def schedule-kind-schema
  [:enum :message :session])

(def schedule-delivery-phase-schema
  [:enum :create-session :prompt-submit])

(def schedule-status-schema
  [:enum :pending :queued :delivered :cancelled :failed])

(def schedule-schema
  [:map
   [:schedule-id :string]
   [:kind {:optional true} schedule-kind-schema]
   [:label {:optional true} [:maybe :string]]
   [:message :string]
   [:source [:= :scheduled]]
   [:created-at inst?]
   [:fire-at inst?]
   [:status schedule-status-schema]
   [:origin-session-id {:optional true} :string]
   [:session-id {:optional true} :string]
   [:created-session-id {:optional true} [:maybe :string]]
   [:delivery-phase {:optional true} [:maybe schedule-delivery-phase-schema]]
   [:error-summary {:optional true} [:maybe :map]]
   [:session-config {:optional true} [:maybe :map]]
   [:session-config-summary {:optional true} [:maybe :map]]])

(def scheduler-state-schema
  [:map
   [:schedules [:map-of :string schedule-schema]]
   [:queue [:vector :string]]])

(def agent-session-schema
  [:map
   [:session-id :string]
   [:session-file {:optional true} [:maybe :string]]
   [:session-name {:optional true} [:maybe :string]]
   [:worktree-path :string]
   [:scheduled-origin-session-id {:optional true} [:maybe :string]]
   [:scheduled-from-schedule-id {:optional true} [:maybe :string]]
   [:scheduled-from-label {:optional true} [:maybe :string]]
   [:parent-session-id {:optional true} [:maybe :string]]
   [:parent-session-path {:optional true} [:maybe :string]]
   [:spawn-mode {:optional true} [:enum :new-root :fork-head :fork-at-entry :agent]]
   [:workflow-run-id {:optional true} [:maybe :string]]
   [:workflow-step-id {:optional true} [:maybe :string]]
   [:workflow-attempt-id {:optional true} [:maybe :string]]
   [:workflow-owned? {:optional true} :boolean]
   [:response-mode {:optional true} [:maybe response-mode-schema]]
   [:model {:optional true} [:maybe model-schema]]
   [:thinking-level thinking-level-schema]
   [:speed-mode {:optional true} [:maybe speed-mode-schema]]
   [:effort-override {:optional true} [:maybe effort-override-schema]]
   [:selected-session-profile {:optional true}
    [:maybe
     [:map
      [:name :keyword]
      [:settings session-profile-settings-schema]
      [:readable-settings [:vector :string]]]]]
   [:is-streaming :boolean]
   [:is-compacting :boolean]
   [:interrupt-pending :boolean]
   [:interrupt-requested-at {:optional true} [:maybe inst?]]
   [:interrupt-reason {:optional true} [:maybe interruption-reason-schema]]
   [:base-system-prompt :string]
   [:system-prompt :string]
   [:prompt-mode {:optional true} prompt-mode-schema]
   [:nucleus-prelude-override {:optional true} [:maybe :string]]
   [:cache-breakpoints {:optional true} [:set cache-breakpoint-schema]]
   [:system-prompt-build-opts {:optional true} [:maybe :map]]
   [:prompt-component-selection {:optional true} [:maybe prompt-component-selection-schema]]
   [:developer-prompt {:optional true} [:maybe :string]]
   [:developer-prompt-source {:optional true} [:maybe [:enum :env :explicit]]]
   [:steering-messages [:vector :string]]
   [:follow-up-messages [:vector :string]]
   [:retry-attempt :int]
   [:retry-deadline-ms {:optional true} [:maybe :int]]
   [:retry {:optional true}
    [:maybe
     [:map
      [:active? :boolean]
      [:attempt :int]
      [:delay-ms :int]
      [:delay-source [:enum :retry-after :exponential-backoff]]
      [:resume-at :int]
      [:rate-limit {:optional true}
       [:maybe
        [:map
         [:limit {:optional true} [:maybe :int]]
         [:remaining {:optional true} [:maybe :int]]
         [:reset-at {:optional true} [:maybe :int]]
         [:reset-after-ms {:optional true} [:maybe :int]]]]]]]]
   [:auto-retry-enabled :boolean]
   [:auto-compaction-enabled :boolean]
   [:scoped-models [:vector scoped-model-schema]]
   [:skill-ids [:vector :string]]
   [:tool-ids [:vector :string]]
   [:prompt-templates [:vector prompt-template-schema]]
   [:prompt-contribution-ids {:optional true} [:vector :string]]
   [:extensions [:map-of :string extension-schema]]
   [:available-extension-capabilities {:optional true}
    [:map
     [:extensions {:optional true} [:map-of :string [:set :keyword]]]]]
   [:prompt-turns {:optional true} [:map-of :string :map]]
   [:turn-augmentations {:optional true} [:map-of :string :map]]
   [:session-entries [:vector session-entry-schema]]
   [:startup-bootstrap {:optional true}
    [:maybe
     [:map
      [:timestamp inst?]
      [:prompt-count :int]
      [:skill-count :int]
      [:tool-count :int]
      [:extension-loaded-count :int]
      [:extension-error-count :int]
      [:extension-errors [:vector [:map [:path :string] [:error :string]]]]]]]
   [:extension-last-prompt-source {:optional true} [:maybe :string]]
   [:extension-last-prompt-delivery {:optional true} [:maybe [:enum :prompt :deferred :follow-up]]]
   [:extension-last-prompt-at {:optional true} [:maybe inst?]]
   [:context-tokens {:optional true} [:maybe :int]]
   [:context-window {:optional true} [:maybe :int]]
   [:ui-type {:optional true} ui-type-schema]
   [:tool-output-overrides {:optional true} [:map-of :string [:map
                                                              [:max-lines {:optional true} [:maybe :int]]
                                                              [:max-bytes {:optional true} [:maybe :int]]]]]
   [:scheduler {:optional true} scheduler-state-schema]
   [:temperature {:optional true} [:maybe [:double {:min 0.0 :max 2.0}]]]
   [:logprobs-enabled {:optional true} :boolean]
   [:top-logprobs {:optional true} [:int {:min 1 :max 20}]]
   [:last-turn-logprobs {:optional true} [:maybe [:vector :map]]]])

(defn valid-session? [s] (m/validate agent-session-schema s))
(defn explain-session [s] (m/explain agent-session-schema s))
(defn valid-skill? [s] (m/validate skill-schema s))
(defn valid-extension? [e] (m/validate extension-schema e))
(defn valid-session-entry? [e] (m/validate session-entry-schema e))
(defn valid-schedule? [s] (m/validate schedule-schema s))
(defn valid-scheduler-state? [s] (m/validate scheduler-state-schema s))

(def default-config
  {:auto-compaction-threshold      0.8
   :auto-compaction-reserve-tokens 16384
   :auto-compaction-keep-recent-tokens 20000
   :branch-summary-reserve-tokens  16384
   :auto-retry-enabled             true
   :auto-retry-max-retries         nil
   :auto-retry-base-delay-ms       2000
   :auto-retry-max-delay-ms        60000
   :auto-retry-total-timeout-ms    600000
   :llm-stream-idle-timeout-ms     1200000
   :tool-batch-max-parallelism     4
   :default-active-tools           #{"read" "bash" "edit" "write"}})

(defn initial-session
  "Return a fresh AgentSession map merged with optional `overrides`."
  ([] (initial-session {}))
  ([overrides]
   (merge
    {:session-id              (str (java.util.UUID/randomUUID))
     :session-file            nil
     :session-name            nil
     :worktree-path           (or (:worktree-path overrides)
                                  (System/getProperty "user.dir"))
     :parent-session-id       nil
     :parent-session-path     nil
     :spawn-mode              :new-root
     :workflow-run-id         nil
     :workflow-step-id        nil
     :workflow-attempt-id     nil
     :workflow-owned?         false
     :model                   nil
     :thinking-level          :off
     :speed-mode              nil
     :effort-override         nil
     :selected-session-profile nil
     :is-streaming            false
     :is-compacting           false
     :interrupt-pending       false
     :interrupt-reason        nil
     :interrupt-requested-at  nil
     :base-system-prompt      ""
     :system-prompt           ""
     :prompt-mode              :lambda
     :cache-breakpoints       #{:system}
     :prompt-component-selection nil
     :developer-prompt        nil
     :developer-prompt-source nil
     :steering-messages       []
     :follow-up-messages      []
     :retry-attempt           0
     :retry                   nil
     :auto-retry-enabled      (:auto-retry-enabled default-config)
     :auto-compaction-enabled false
     :scoped-models           []
     :skill-ids               []
     :tool-ids                []
     :prompt-templates        []
     :prompt-contribution-ids []
     :extensions              {}
     :available-extension-capabilities {:extensions {}}
     :prompt-turns            {}
     :turn-augmentations      {}
     :session-entries         []
     :extension-last-prompt-source nil
     :extension-last-prompt-delivery nil
     :extension-last-prompt-at nil
     :context-tokens          nil
     :context-window          nil
     :ui-type                 :console
     :tool-output-overrides   {}
     :scheduler               (scheduler/empty-state)}
    overrides)))

(defn idle?
  [session]
  (and (not (:is-streaming session))
       (not (:is-compacting session))))

(defn pending-message-count
  [session]
  (+ (count (:steering-messages session))
     (count (:follow-up-messages session))))

(defn has-pending-messages?
  [session]
  (pos? (pending-message-count session)))

(defn context-fraction-used
  [session]
  (when (and (:context-tokens session) (:context-window session)
             (pos? (:context-window session)))
    (/ (double (:context-tokens session))
       (double (:context-window session)))))

(defn above-compaction-threshold?
  ([session] (above-compaction-threshold? session (:auto-compaction-threshold default-config)))
  ([session threshold]
   (let [frac (context-fraction-used session)]
     (and frac (>= frac threshold)))))

(def ^:private thinking-level-order
  [:off :minimal :low :medium :high :xhigh])

(defn clamp-thinking-level
  [level model]
  (if (:reasoning model)
    level
    :off))

(defn next-thinking-level
  [current model]
  (if (:reasoning model)
    (let [available thinking-level-order
          idx       (.indexOf available current)
          next-idx  (mod (inc idx) (count available))]
      (nth available next-idx))
    :off))

(defn next-model
  [candidates current direction]
  (when (seq candidates)
    (let [models (mapv :model candidates)
          n      (count models)]
      (if-not current
        (case direction
          :backward (nth models (dec n))
          (nth models 0))
        (let [idx    (or (first (keep-indexed #(when (= (:id %2) (:id current)) %1) models)) 0)
              next-i (case direction
                       :forward  (mod (inc idx) n)
                       :backward (mod (dec idx) n)
                       (mod (inc idx) n))]
          (nth models next-i))))))

(defn make-entry
  [kind data]
  {:id        (str (java.util.UUID/randomUUID))
   :parent-id nil
   :timestamp (java.time.Instant/now)
   :kind      kind
   :data      data})

(defn append-entry
  [session entry]
  (update session :session-entries conj entry))

(def ^:private retriable-http-statuses
  #{429 500 502 503 529})

(def ^:private retriable-error-patterns
  [#"(?i)rate.limit"
   #"(?i)too.many.requests"
   #"(?i)usage.limit"
   #"(?i)limit.has.been.reached"
   #"(?i)overloaded"
   #"(?i)status[ .:_]429"
   #"(?i)status[ .:_]5\d\d"
   #"(?i)an error occurred while processing your request"
   #"(?i)\bserver_error\b"
   #"(?i)internal server error"
   #"(?i)premature end of chunk coded message body"
   #"(?i)closing chunk expected"])

(def ^:private auth-error-patterns
  [#"(?i)unauthorized"
   #"(?i)forbidden"
   #"(?i)invalid api key"
   #"(?i)authentication"])

(def ^:private rate-limit-error-patterns
  [#"(?i)rate.limit"
   #"(?i)too.many.requests"
   #"(?i)usage.limit"
   #"(?i)limit.has.been.reached"
   #"(?i)status[ .:_]429"])

(def ^:private overloaded-error-patterns
  [#"(?i)overloaded"])

(def ^:private server-error-patterns
  ;; Canonical OpenAI transient server error delivered as a mid-stream
  ;; error/response.failed event without an HTTP status. The provider
  ;; explicitly invites a retry ("You can retry your request").
  [#"(?i)an error occurred while processing your request"
   #"(?i)\bserver_error\b"
   #"(?i)internal server error"])

(def ^:private invalid-request-error-patterns
  [#"(?i)invalid request"
   #"(?i)bad request"
   #"(?i)unprocessable"])

(def ^:private transport-error-patterns
  [#"(?i)premature end of chunk coded message body"
   #"(?i)closing chunk expected"
   #"(?i)connection reset"
   #"(?i)connection refused"
   #"(?i)broken pipe"
   #"(?i)eof"
   #"(?i)timed? ?out"
   #"(?i)socket"
   #"(?i)network"])

(defn retry-error?
  ([stop-reason error-message]
   (retry-error? stop-reason error-message nil))
  ([stop-reason error-message http-status]
   (and (= stop-reason :error)
        (or (contains? retriable-http-statuses http-status)
            (some #(re-find % (or error-message ""))
                  retriable-error-patterns)))))

(defn provider-error-kind
  [stop-reason error-message http-status]
  (let [message (or error-message "")]
    (cond
      (not= stop-reason :error)
      nil

      (or (contains? #{401 403} http-status)
          (some #(re-find % message) auth-error-patterns))
      :auth

      (or (= 429 http-status)
          (some #(re-find % message) rate-limit-error-patterns))
      :rate-limit

      (= "Timeout waiting for LLM response" message)
      :timeout

      (some #(re-find % message) overloaded-error-patterns)
      :overloaded

      (or (contains? #{400 404 422} http-status)
          (some #(re-find % message) invalid-request-error-patterns))
      :invalid-request

      (or (contains? #{500 502 503 529} http-status)
          (some #(re-find % message) server-error-patterns))
      :provider-unavailable

      (some #(re-find % message) transport-error-patterns)
      :transport

      :else
      :unknown)))

(defn context-overflow-error?
  [error-message]
  (boolean
   (some #(re-find % (or error-message ""))
         [#"(?i)context.length"
          #"(?i)context.window"
          #"(?i)max.tokens"
          #"(?i)too.many.tokens"])))

(defn exponential-backoff-ms
  [attempt base-ms max-ms]
  (min max-ms (long (* base-ms (Math/pow 2 attempt)))))

(defn- parse-long-safe
  [s]
  (when (string? s)
    (try
      (Long/parseLong s)
      (catch Exception _
        nil))))

(defn- integer-string?
  [s]
  (boolean (re-matches #"^[0-9]+$" (or s ""))))

(defn- normalize-header-name
  [header-name]
  (when header-name
    (-> (if (keyword? header-name)
          (name header-name)
          (str header-name))
        str/lower-case
        str/trim)))

(defn normalized-headers
  [headers]
  (reduce-kv (fn [acc k v]
               (assoc acc (normalize-header-name k) v))
             {}
             (or headers {})))

(defn header-value
  [headers & names]
  (let [headers* (normalized-headers headers)]
    (some #(get headers* (normalize-header-name %)) names)))

(defn retry-after-delay-ms
  [header-value now-ms]
  (let [raw (some-> header-value str str/trim)]
    (cond
      (not (seq raw))
      nil

      (integer-string? raw)
      (let [seconds (some-> raw parse-long-safe)
            ;; Cap the accepted seconds: strictly below Long/MAX_VALUE / 1000
            ;; (the `* 1000` overflow boundary) and below the value whose
            ;; delay-ms would overflow retry-metadata's `:resume-at`
            ;; `(+ now-ms delay-ms)`. A PARSEABLE near-Long/MAX integer
            ;; (16 digits, seconds >= 9223372036854775) previously slipped
            ;; through both and crashed the turn with an uncaught
            ;; ArithmeticException; it now yields nil, flooring to the
            ;; exponential backoff like the RFC-date branch does for <= 0 /
            ;; unparsable values, so retry-metadata's
            ;; `(or retry-after-ms exponential-delay-ms)` falls back.
            delay-ms (when (and seconds
                                (< seconds (quot Long/MAX_VALUE 1000))
                                (<= (* 1000 seconds) (- Long/MAX_VALUE (long now-ms))))
                       (* 1000 seconds))]
        (when (and delay-ms (pos? delay-ms))
          delay-ms))

      :else
      (try
        (let [resume-ms (.toEpochMilli (.toInstant (java.time.ZonedDateTime/parse raw java.time.format.DateTimeFormatter/RFC_1123_DATE_TIME)))
              delay-ms  (- resume-ms (long now-ms))]
          (when (pos? delay-ms)
            delay-ms))
        (catch Exception _
          nil)))))

(defn rate-limit-reset->timing
  [header-value now-ms]
  (when-let [n (some-> header-value str str/trim parse-long-safe)]
    (cond
      (>= n 1000000000000)
      {:reset-at n}

      (>= n 1000000000)
      {:reset-at (* 1000 n)}

      :else
      (let [reset-after-ms (* 1000 n)]
        {:reset-after-ms reset-after-ms
         :reset-at (+ (long now-ms) reset-after-ms)}))))

(defn retry-metadata
  [headers attempt exponential-delay-ms now-ms]
  (let [retry-after-ms (retry-after-delay-ms (header-value headers "retry-after" "x-retry-after") now-ms)
        ;; Floor the per-attempt delay to a positive minimum (1 ms): a configured
        ;; :auto-retry-base-delay-ms / :auto-retry-max-delay-ms of 0 yields a
        ;; zero exponential, and sleep-for-retry!'s `(pos? ...)` guard skips
        ;; non-positive delays — under the budget-active default (cap-free) a
        ;; persistent retryable failure would then hot-loop back-to-back with
        ;; zero delay until the REAL wall-clock deadline (10 min default;
        ;; pre-change the default count cap 3 bounded the same misconfiguration
        ;; to 4 instant attempts). The floor guarantees the loop always sleeps
        ;; between attempts (design Approach 5's never-back-to-back guarantee)
        ;; and keeps the scheduled :delay-ms / :resume-at positive. A
        ;; non-positive `Retry-After` already floors to nil (exponential) above,
        ;; so this only ever raises a zero exponential.
        delay-ms       (max 1 (long (or retry-after-ms exponential-delay-ms)))
        rate-limit     (merge
                        (when-some [limit (some-> (header-value headers "ratelimit-limit" "x-ratelimit-limit") str str/trim parse-long-safe)]
                          {:limit limit})
                        (when-some [remaining (some-> (header-value headers "ratelimit-remaining" "x-ratelimit-remaining") str str/trim parse-long-safe)]
                          {:remaining remaining})
                        (rate-limit-reset->timing (header-value headers "ratelimit-reset" "x-ratelimit-reset") now-ms))]
    {:active? true
     :attempt (int attempt)
     :delay-ms delay-ms
     :delay-source (if retry-after-ms :retry-after :exponential-backoff)
     :resume-at (+ (long now-ms) delay-ms)
     :rate-limit (when (seq rate-limit) rate-limit)}))
