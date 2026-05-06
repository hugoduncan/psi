(ns psi.session-state.model
  "Canonical session-state model authority: session schemas, defaults, pure
   derived predicates, and entry helpers. No atom, I/O, or runtime ownership."
  (:require
   [malli.core :as m]
   [psi.agent-session.scheduler :as scheduler]))

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
                                                              [:max-bytes {:optional true} [:maybe :int]]]]]
   [:scheduler {:optional true} scheduler-state-schema]])

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
     :workflow-run-id         nil
     :workflow-step-id        nil
     :workflow-attempt-id     nil
     :workflow-owned?         false
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
   #"(?i)overloaded"
   #"(?i)status[ .:_]429"
   #"(?i)status[ .:_]5\d\d"
   #"(?i)premature end of chunk coded message body"
   #"(?i)closing chunk expected"])

(defn retry-error?
  ([stop-reason error-message]
   (retry-error? stop-reason error-message nil))
  ([stop-reason error-message http-status]
   (and (= stop-reason :error)
        (or (contains? retriable-http-statuses http-status)
            (some #(re-find % (or error-message ""))
                  retriable-error-patterns)))))

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
