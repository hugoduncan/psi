(ns psi.agent-session.session
  "AgentSession data model, malli schemas, and pure derived predicates.

   This namespace is pure data — no atoms, no I/O, no statechart.
   Everything here operates on plain maps.

   Attribute conventions (kebab-case mirrors allium camelCase):
     :session-id            — stable UUID string for this branch
     :session-file          — path to the journal file, nil if unsaved
     :session-name          — user-assigned label, nil if unnamed
     :model                 — {:provider string :id string :reasoning bool}
     :thinking-level        — :off | :minimal | :low | :medium | :high | :xhigh
     :is-streaming          — true while the agent loop is running
     :is-compacting         — true while a compaction is executing
     :system-prompt         — assembled system prompt string
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
     :context-window        — integer, model context window size"
  (:require
   [malli.core :as m]))

;; ============================================================
;; Schemas
;; ============================================================

(def thinking-level-schema
  [:enum :off :minimal :low :medium :high :xhigh])

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

(def agent-session-schema
  [:map
   [:session-id :string]
   [:session-file {:optional true} [:maybe :string]]
   [:session-name {:optional true} [:maybe :string]]
   [:model {:optional true} [:maybe model-schema]]
   [:thinking-level thinking-level-schema]
   [:is-streaming :boolean]
   [:is-compacting :boolean]
   [:system-prompt :string]
   [:steering-messages [:vector :string]]
   [:follow-up-messages [:vector :string]]
   [:retry-attempt :int]
   [:auto-retry-enabled :boolean]
   [:auto-compaction-enabled :boolean]
   [:scoped-models [:vector scoped-model-schema]]
   [:skills [:vector skill-schema]]
   [:prompt-templates [:vector prompt-template-schema]]
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
   [:context-tokens {:optional true} [:maybe :int]]
   [:context-window {:optional true} [:maybe :int]]
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
   :default-active-tools           #{"read" "bash" "edit" "write"}})

(defn initial-session
  "Return a fresh AgentSession map merged with optional `overrides`."
  ([] (initial-session {}))
  ([overrides]
   (merge
    {:session-id              (str (java.util.UUID/randomUUID))
     :session-file            nil
     :session-name            nil
     :model                   nil
     :thinking-level          :off
     :is-streaming            false
     :is-compacting           false
     :system-prompt           ""
     :steering-messages       []
     :follow-up-messages      []
     :retry-attempt           0
     :auto-retry-enabled      (:auto-retry-enabled default-config)
     :auto-compaction-enabled false
     :scoped-models           []
     :skills                  []
     :prompt-templates        []
     :extensions              {}
     :session-entries         []
     :context-tokens          nil
     :context-window          nil
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
