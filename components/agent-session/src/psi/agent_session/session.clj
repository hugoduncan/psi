(ns psi.agent-session.session
  "AgentSession data model, malli schemas, and pure derived predicates.

   This namespace is pure data — no atoms, no I/O, no statechart.
   Everything here operates on plain maps.

   Attribute conventions (kebab-case mirrors allium camelCase):
     :session-id            — stable UUID string for this branch
     :session-file          — path to the journal file, nil if unsaved
     :session-name          — user-assigned label, nil if unnamed
     :worktree-path         — effective working directory for this session branch
     :model                 — {:provider string :id string :reasoning bool}
     :thinking-level        — :off | :minimal | :low | :medium | :high | :xhigh
     :is-streaming          — true while the agent loop is running
     :is-compacting         — true while a compaction is executing
     :interrupt-pending     — true when interrupt is scheduled for current turn boundary
     :interrupt-requested-at — timestamp when deferred interrupt was requested
     :base-system-prompt    — assembled base system prompt string (without extension contributions)
     :system-prompt         — assembled runtime system prompt string (base + developer + extension contributions)
     :developer-prompt      — optional developer instruction layer string
     :developer-prompt-source — nil | :env | :explicit
     :steering-messages     — queued steer texts (injected mid-run)
     :follow-up-messages    — queued follow-up texts (delivered after run)
     :retry-attempt         — current retry counter (0 = not retrying)
     :auto-retry-enabled    — bool
     :auto-compaction-enabled — bool
     :scoped-models         — [{:model m :thinking-level l}] from --models flag
     :skills                — [Skill map]
     :prompt-templates      — [PromptTemplate map]
     :extensions            — {extension-path Extension-map}
     :session-entries       — [SessionEntry map], append-only journal
     :context-tokens        — nil | integer, nil after compaction until next turn
     :context-window        — integer, model context window size
     :ui-type               — :console | :tui | :emacs (runtime surface hint for extensions)"
  (:require
   [malli.core :as m]))

;; ============================================================
;; Schemas
;; ============================================================

(def thinking-level-schema
  [:enum :off :minimal :low :medium :high :xhigh])

(def ui-type-schema
  [:enum :console :tui :emacs])

(def model-schema
  [:map
   [:provider :string]
   [:id :string]
   [:reasoning {:optional true} :boolean]])

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
  [:enum :message :thinking-level :model :compaction :branch-summary
   :custom :custom-message :label :session-info])

(def session-entry-schema
  [:map
   [:id :string]
   [:parent-id {:optional true} [:maybe :string]]
   [:timestamp inst?]
   [:kind session-entry-kind-schema]
   [:data {:optional true} :map]])

(def prompt-contribution-schema
  [:map
   [:id :string]
   [:ext-path :string]
   [:section {:optional true} [:maybe :string]]
   [:content :string]
   [:priority {:optional true} :int]
   [:enabled {:optional true} :boolean]
   [:created-at inst?]
   [:updated-at inst?]])

(def cache-breakpoint-schema
  [:enum :system :tools])

(def prompt-mode-schema
  [:enum :lambda :prose])

(def prompt-component-schema
  [:enum :preamble :context-files :skills :runtime-metadata])

(def prompt-component-selection-schema
  [:map
   [:agents-md? {:optional true} :boolean]
   [:extension-prompt-contributions {:optional true} [:maybe [:vector :string]]]
   [:tool-names {:optional true} [:maybe [:vector :string]]]
   [:skill-names {:optional true} [:maybe [:vector :string]]]
   [:components {:optional true} [:maybe [:set prompt-component-schema]]]])

(def agent-session-schema
  [:map
   [:session-id :string]
   [:session-file {:optional true} [:maybe :string]]
   [:session-name {:optional true} [:maybe :string]]
   [:worktree-path :string]
   [:parent-session-id {:optional true} [:maybe :string]]
   [:parent-session-path {:optional true} [:maybe :string]]
   [:spawn-mode {:optional true} [:enum :new-root :fork-head :fork-at-entry :agent]]
   [:model {:optional true} [:maybe model-schema]]
   [:thinking-level thinking-level-schema]
   [:is-streaming :boolean]
   [:is-compacting :boolean]
   [:interrupt-pending :boolean]
   [:interrupt-requested-at {:optional true} [:maybe inst?]]
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
   [:auto-retry-enabled :boolean]
   [:auto-compaction-enabled :boolean]
   [:scoped-models [:vector scoped-model-schema]]
   [:skills [:vector skill-schema]]
   [:prompt-templates [:vector prompt-template-schema]]
   [:prompt-contributions {:optional true} [:vector prompt-contribution-schema]]
   [:tool-defs {:optional true} [:vector :map]]
   [:extensions [:map-of :string extension-schema]]
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
      [:extension-errors [:vector [:map [:path :string] [:error :string]]]]
      [:mutations [:vector qualified-symbol?]]]]]
   [:extension-last-prompt-source {:optional true} [:maybe :string]]
   [:extension-last-prompt-delivery {:optional true} [:maybe [:enum :prompt :deferred :follow-up]]]
   [:extension-last-prompt-at {:optional true} [:maybe inst?]]
   [:context-tokens {:optional true} [:maybe :int]]
   [:context-window {:optional true} [:maybe :int]]
   [:ui-type {:optional true} ui-type-schema]
   [:tool-output-overrides {:optional true} [:map-of :string [:map
                                                              [:max-lines {:optional true} [:maybe :int]]
                                                              [:max-bytes {:optional true} [:maybe :int]]]]]])

;; ============================================================
;; Validation
;; ============================================================

(defn valid-session? [s] (m/validate agent-session-schema s))
(defn explain-session [s] (m/explain agent-session-schema s))
(defn valid-skill? [s] (m/validate skill-schema s))
(defn valid-extension? [e] (m/validate extension-schema e))
(defn valid-session-entry? [e] (m/validate session-entry-schema e))

;; ============================================================
;; Defaults
;; ============================================================

(def default-config
  {:auto-compaction-threshold      0.8
   :auto-compaction-reserve-tokens 16384
   :auto-compaction-keep-recent-tokens 20000
   :branch-summary-reserve-tokens  16384
   :auto-retry-enabled             true
   :auto-retry-max-retries         3
   :auto-retry-base-delay-ms       2000
   :auto-retry-max-delay-ms        60000
   :llm-stream-idle-timeout-ms     600000
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
     :model                   nil
     :thinking-level          :off
     :is-streaming            false
     :is-compacting           false
     :interrupt-pending       false
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
     :auto-retry-enabled      (:auto-retry-enabled default-config)
     :auto-compaction-enabled false
     :scoped-models           []
     :skills                  []
     :prompt-templates        []
     :prompt-contributions    []
     :tool-defs               []
     :extensions              {}
     :session-entries         []
     :extension-last-prompt-source nil
     :extension-last-prompt-delivery nil
     :extension-last-prompt-at nil
     :context-tokens          nil
     :context-window          nil
     :ui-type                 :console
     :tool-output-overrides   {}}
    overrides)))

;; ============================================================
;; Pure derived predicates (operate on a session data map)
;; ============================================================

(defn idle?
  "True when the session is neither streaming nor compacting."
  [session]
  (and (not (:is-streaming session))
       (not (:is-compacting session))))

(defn pending-message-count
  "Total number of queued steering + follow-up messages."
  [session]
  (+ (count (:steering-messages session))
     (count (:follow-up-messages session))))

(defn has-pending-messages?
  "True when any steering or follow-up messages are queued."
  [session]
  (pos? (pending-message-count session)))

(defn context-fraction-used
  "Fraction of the context window currently used, or nil if unknown."
  [session]
  (when (and (:context-tokens session) (:context-window session)
             (pos? (:context-window session)))
    (/ (double (:context-tokens session))
       (double (:context-window session)))))

(defn above-compaction-threshold?
  "True when context usage exceeds the compaction threshold."
  ([session] (above-compaction-threshold? session (:auto-compaction-threshold default-config)))
  ([session threshold]
   (let [frac (context-fraction-used session)]
     (and frac (>= frac threshold)))))

;; ── Thinking level clamping ─────────────────────────────

(def ^:private thinking-level-order
  [:off :minimal :low :medium :high :xhigh])

(defn clamp-thinking-level
  "Return the highest thinking level that is <= `level` supported by `model`.
  If the model does not support reasoning, always returns :off."
  [level model]
  (if (:reasoning model)
    level
    :off))

(defn next-thinking-level
  "Cycle to the next thinking level after `current` for `model`.
  Wraps around from :xhigh back to :off."
  [current model]
  (if (:reasoning model)
    (let [available thinking-level-order
          idx       (.indexOf available current)
          next-idx  (mod (inc idx) (count available))]
      (nth available next-idx))
    :off))

;; ── Model cycling ───────────────────────────────────────

(defn next-model
  "Return the next model after `current` in `candidates`, cycling.
  Direction :forward or :backward.
  When `current` is nil, returns the first candidate model (for :forward)
  or last (for :backward)."
  [candidates current direction]
  (when (seq candidates)
    (let [models (mapv :model candidates)
          n      (count models)]
      (if-not current
        ;; No current model — return first (forward) or last (backward)
        (case direction
          :backward (nth models (dec n))
          (nth models 0))
        ;; Current model known — advance by one in requested direction
        (let [idx    (or (first (keep-indexed #(when (= (:id %2) (:id current)) %1) models)) 0)
              next-i (case direction
                       :forward  (mod (inc idx) n)
                       :backward (mod (dec idx) n)
                       (mod (inc idx) n))]
          (nth models next-i))))))

;; ── Session entry helpers ───────────────────────────────

(defn make-entry
  "Construct a SessionEntry map."
  [kind data]
  {:id        (str (java.util.UUID/randomUUID))
   :parent-id nil
   :timestamp (java.time.Instant/now)
   :kind      kind
   :data      data})

(defn append-entry
  "Return session with `entry` appended to :session-entries."
  [session entry]
  (update session :session-entries conj entry))

;; ── Canonical-root runtime access helpers ───────────────

(defn- session-data-path [sid] [:agent-session :sessions sid :data])

(defn- session-telemetry-path [sid k] [:agent-session :sessions sid :telemetry k])
(defn- session-journal-path [sid] [:agent-session :sessions sid :persistence :journal])
(defn- session-flush-state-path [sid] [:agent-session :sessions sid :persistence :flush-state])
(defn- session-turn-ctx-path [sid] [:agent-session :sessions sid :turn :ctx])

(def ^:private static-state-paths
  {:workflow-state  [:workflows]
   :workflow-definitions [:workflows :definitions]
   :workflow-runs   [:workflows :runs]
   :workflow-run-order [:workflows :run-order]
   :nrepl-runtime   [:runtime :nrepl]
   :background-jobs [:background-jobs :store]
   :ui-state        [:ui :extension-ui]
   :recursion       [:recursion]
   :oauth           [:oauth]
   :rpc-trace       [:runtime :rpc-trace]})

(def ^:private session-state-path-builders
  {:session-data          session-data-path
   :tool-output-stats     #(session-telemetry-path % :tool-output-stats)
   :tool-call-attempts    #(session-telemetry-path % :tool-call-attempts)
   :tool-lifecycle-events #(session-telemetry-path % :tool-lifecycle-events)
   :provider-requests     #(session-telemetry-path % :provider-requests)
   :provider-replies      #(session-telemetry-path % :provider-replies)
   :journal               session-journal-path
   :flush-state           session-flush-state-path
   :turn-ctx              session-turn-ctx-path})

(defn state-path
  "Resolve known canonical-root runtime paths by key.
   Session-scoped keys (:session-data, :journal, :flush-state, :turn-ctx,
   :tool-output-stats, :tool-call-attempts, :tool-lifecycle-events,
   :provider-requests, :provider-replies) require a session-id `sid`.
   Non-session keys work with one arg."
  ([k] (state-path k nil))
  ([k sid]
   (if-let [build-path (get session-state-path-builders k)]
     (when sid (build-path sid))
     (get static-state-paths k))))

(defn get-state-value-in
  "Read `path` from canonical root state in `ctx`."
  [ctx path]
  (get-in @(:state* ctx) path))

(defn assoc-state-value-in!
  "Assoc `value` at `path` in canonical root state in `ctx`."
  [ctx path value]
  (swap! (:state* ctx) assoc-in path value))

(defn get-session-data-in
  "Read session data for `sid` from canonical root state in `ctx`."
  [ctx sid]
  (get-state-value-in ctx (session-data-path sid)))

;; ── Retry backoff ───────────────────────────────────────

(def ^:private retriable-http-statuses
  "HTTP status codes that indicate a retriable error."
  #{429 500 502 503 529})

(defn retry-error?
  "True if the error is retriable.
  Checks numeric :http-status first (preferred), falls back to string matching."
  ([stop-reason error-message]
   (retry-error? stop-reason error-message nil))
  ([stop-reason error-message http-status]
   (and (= stop-reason :error)
        (or (contains? retriable-http-statuses http-status)
            (some #(re-find % (or error-message ""))
                  [#"(?i)rate.limit"
                   #"(?i)too.many.requests"
                   #"(?i)overloaded"
                   #"(?i)status[ .:_]429"
                   #"(?i)status[ .:_]5\d\d"])))))

(defn context-overflow-error?
  "True if `error-message` indicates a context-window overflow."
  [error-message]
  (boolean
   (some #(re-find % (or error-message ""))
         [#"(?i)context.length"
          #"(?i)context.window"
          #"(?i)max.tokens"
          #"(?i)too.many.tokens"])))

(defn exponential-backoff-ms
  "Compute backoff delay in ms for `attempt` (0-indexed) with base and max."
  [attempt base-ms max-ms]
  (min max-ms (long (* base-ms (Math/pow 2 attempt)))))
