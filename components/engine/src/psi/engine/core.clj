(ns psi.engine.core
  "Core engine implementation using statecharts for system evolution"
  (:require
   [com.fulcrologic.statecharts.simple :as sc]
   [malli.core :as m]))

;; ========================================
;; Malli schemas for Engine entities
;; ========================================

(def engine-status-schema
  [:enum :initializing :ready :processing :error])

(def evolution-stage-schema
  [:enum :bootstrap :developing :integrating :complete])

(def system-mode-schema
  [:enum :build :explore :debug :reflect :play :atom])

(def engine-id-schema
  :string)

(def statechart-config-schema
  :map)

(def active-states-schema
  [:set :string])

(def state-transition-schema
  [:map
   [:engine-id :string]
   [:from-state :string]
   [:to-state :string]
   [:trigger :string]
   [:timestamp inst?]
   [:context :map]])

(def engine-schema
  [:map
   [:engine-id :string]
   [:engine-status engine-status-schema]
   [:statechart-config statechart-config-schema]
   [:active-states active-states-schema]
   [:state-transitions {:optional true} [:vector state-transition-schema]]])

(def system-state-schema
  [:map
   [:current-mode system-mode-schema]
   [:evolution-stage evolution-stage-schema]
   [:last-updated inst?]
   [:git-commit {:optional true} [:maybe :string]]
   [:engine-ready {:optional true} :boolean]
   [:query-ready {:optional true} :boolean]
   [:graph-ready {:optional true} :boolean]
   [:introspection-ready {:optional true} :boolean]
   [:history-ready {:optional true} :boolean]
   [:knowledge-ready {:optional true} :boolean]
   [:memory-ready {:optional true} :boolean]])

;; ========================================
;; Validation functions
;; ========================================

(defn valid-engine?
  "Validate engine data against schema"
  [engine-data]
  (m/validate engine-schema engine-data))

(defn valid-system-state?
  "Validate system state data against schema"
  [state-data]
  (m/validate system-state-schema state-data))

(defn valid-state-transition?
  "Validate state transition data against schema"
  [transition-data]
  (m/validate state-transition-schema transition-data))

(defn explain-validation-error
  "Get human-readable validation error explanation"
  [schema data]
  (m/explain schema data))

;; ========================================
;; State management atoms
;; ========================================

(defonce ^:private engines (atom {}))
(defonce ^:private system-state (atom nil))
(defonce ^:private state-transitions (atom []))
(defonce ^:private sc-env (atom nil))

;; ========================================
;; Isolated context (Nullable pattern)
;; ========================================

(defn create-context
  "Create an isolated engine context with its own atoms.
   Use this in tests (via create-null) to avoid touching global state.

   Returns a context map that can be passed to the *-in context-aware
   variants of all engine functions."
  []
  {:engines          (atom {})
   :system-state     (atom nil)
   :state-transitions (atom [])
   :sc-env           (atom nil)})

;; ========================================
;; Engine statechart definition
;; ========================================

(def ^:private engine-statechart
  "Core engine statechart modeling system evolution"
  {::sc/id :engine
   ::sc/initial :initializing
   ::sc/states
   {:initializing {::sc/on {:initialization-failed :error
                            :configuration-start :configuring}}

    :configuring  {::sc/on {:configuration-complete :ready
                            :configuration-failed :error}}

    :ready        {::sc/entry (fn [env]
                                (-> env
                                    (assoc-in [:data :can-model-functionality] true)
                                    (assoc-in [:data :provides-state-access] true)))
                   ::sc/on {:start-processing :processing
                            :error-occurred :error}}

    :processing   {::sc/on {:processing-complete :ready
                            :processing-failed :error}}

    :error        {::sc/on {:recover :ready}}}})

;; ========================================
;; System evolution statechart
;; ========================================

(def ^:private system-evolution-statechart
  "System-wide evolution statechart"
  {::sc/id :system-evolution
   ::sc/initial :bootstrap
   ::sc/states
   {:bootstrap   {::sc/entry (fn [env]
                               (assoc-in env [:data :evolution-stage] :bootstrap))
                  ::sc/on {:engine-ready :developing}}

    :developing  {::sc/on {:graph-emerges :integrating}}

    :integrating {::sc/on {:ai-complete :complete}}

    :complete    {::sc/entry (fn [env]
                               (assoc-in env [:data :is-ai-complete] true))}}})

;; ========================================
;; Engine management functions (context-aware core)
;; ========================================

(defn- global-context
  "Return the global (singleton) context map."
  []
  {:engines           engines
   :system-state      system-state
   :state-transitions state-transitions
   :sc-env            sc-env})

(defn create-engine-in
  "Create a new engine instance within an isolated `ctx` context."
  [ctx engine-id config]
  (let [{:keys [engines sc-env]} ctx
        env (or @sc-env (reset! sc-env (sc/simple-env)))
        _   (sc/register! env engine-id engine-statechart)
        _   (sc/start! env engine-id)
        engine {:engine-id         engine-id
                :engine-status     :initializing
                :statechart-config config
                :active-states     #{"initializing"}
                :state-transitions []}]
    (when-not (valid-engine? engine)
      (throw (ex-info "Invalid engine data"
                      {:engine-id         engine-id
                       :validation-errors (explain-validation-error engine-schema engine)})))
    (swap! engines assoc engine-id engine)
    engine))

(defn get-engine-in
  "Retrieve engine by ID from `ctx`."
  [ctx engine-id]
  (get @(:engines ctx) engine-id))

(defn get-all-engines-in
  "Return all engines in `ctx`."
  [ctx]
  @(:engines ctx))

(defn engine-status-in
  "Get current status of engine from `ctx`."
  [ctx engine-id]
  (when-let [engine (get-engine-in ctx engine-id)]
    (let [current-state (:engine-status engine)]
      {:engine-status          current-state
       :active-states          #{current-state}
       :can-model-functionality (= :ready current-state)
       :provides-state-access  true})))

(defn trigger-engine-event-in!
  "Send event to engine statechart in `ctx`."
  [ctx engine-id event & [data]]
  (when-let [engine (get-engine-in ctx engine-id)]
    (let [{:keys [engines state-transitions]} ctx
          old-state (:engine-status engine)
          new-state (case [old-state event]
                      [:initializing :configuration-start]  :configuring
                      [:configuring  :configuration-complete] :ready
                      [:ready        :start-processing]       :processing
                      [:processing   :processing-complete]    :ready
                      old-state)
          transition {:engine-id  engine-id
                      :from-state (str old-state)
                      :to-state   (str new-state)
                      :trigger    (str event)
                      :timestamp  (java.time.Instant/now)
                      :context    (or data {})}]
      (when-not (valid-state-transition? transition)
        (throw (ex-info "Invalid state transition data"
                        {:transition        transition
                         :validation-errors (explain-validation-error
                                             state-transition-schema transition)})))
      (swap! state-transitions conj transition)
      (swap! engines assoc-in [engine-id :engine-status] new-state)
      (swap! engines assoc-in [engine-id :active-states] #{new-state})
      transition)))

(defn initialize-system-state-in!
  "Initialize system state within `ctx`."
  [ctx]
  (let [{:keys [system-state sc-env]} ctx
        initial-state {:current-mode      :explore
                       :evolution-stage   :bootstrap
                       :last-updated      (java.time.Instant/now)
                       :git-commit        nil
                       :engine-ready      false
                       :query-ready       false
                       :graph-ready       false
                       :introspection-ready false
                       :history-ready     false
                       :knowledge-ready   false
                       :memory-ready      false}
        env (or @sc-env (reset! sc-env (sc/simple-env)))
        _   (sc/register! env :system system-evolution-statechart)
        _   (sc/start! env :system)]
    (when-not (valid-system-state? initial-state)
      (throw (ex-info "Invalid system state data"
                      {:validation-errors (explain-validation-error
                                           system-state-schema initial-state)})))
    (reset! system-state initial-state)
    @system-state))

(defn get-system-state-in
  "Get current system state from `ctx`."
  [ctx]
  @(:system-state ctx))

(defn update-system-component-in!
  "Mark a system component as ready in `ctx`."
  [ctx component-key ready?]
  (let [ss (:system-state ctx)]
    (when @ss
      (swap! ss assoc component-key ready?)
      @ss)))

(defn set-evolution-stage-in!
  "Set system evolution stage in `ctx`.
   Stage must satisfy `evolution-stage-schema`."
  [ctx stage]
  (when-not (m/validate evolution-stage-schema stage)
    (throw (ex-info "Invalid evolution stage"
                    {:stage stage
                     :validation-errors (explain-validation-error
                                         evolution-stage-schema stage)})))
  (let [ss (:system-state ctx)]
    (when @ss
      (swap! ss assoc
             :evolution-stage stage
             :last-updated (java.time.Instant/now))
      @ss)))

(defn system-has-interface-in?
  "Check if system in `ctx` has complete interface (engine + query ready)."
  [ctx]
  (when-let [state (get-system-state-in ctx)]
    (and (:engine-ready state) (:query-ready state))))

(defn system-has-substrate-in?
  "Check if system in `ctx` has substrate (engine ready)."
  [ctx]
  (when-let [state (get-system-state-in ctx)]
    (:engine-ready state)))

(defn system-has-memory-layer-in?
  "Check if system in `ctx` has complete memory layer."
  [ctx]
  (when-let [state (get-system-state-in ctx)]
    (and (:query-ready state) (:history-ready state) (:knowledge-ready state))))

(defn system-is-ai-complete-in?
  "Check if system in `ctx` has achieved AI COMPLETE status."
  [ctx]
  (when-let [state (get-system-state-in ctx)]
    (and (:engine-ready state) (:query-ready state) (:graph-ready state)
         (:introspection-ready state) (:history-ready state)
         (:knowledge-ready state) (:memory-ready state))))

(defn get-state-transitions-in
  "Get all state transitions from `ctx`, optionally filtered by engine."
  ([ctx]
   @(:state-transitions ctx))
  ([ctx engine-id]
   (filter #(= (:engine-id %) engine-id) @(:state-transitions ctx))))

(defn engine-diagnostics-in
  "Get diagnostic information about an engine in `ctx`."
  [ctx engine-id]
  (when-let [engine (get-engine-in ctx engine-id)]
    {:engine             engine
     :status             (engine-status-in ctx engine-id)
     :recent-transitions (take 10 (reverse (get-state-transitions-in ctx engine-id)))
     :transition-count   (count (get-state-transitions-in ctx engine-id))}))

(defn system-diagnostics-in
  "Get diagnostic information about the entire system in `ctx`."
  [ctx]
  (let [all-engines (get-all-engines-in ctx)
        state       (get-system-state-in ctx)]
    {:system-state       state
     :engine-count       (count all-engines)
     :engines            (into {} (map (fn [[id _]] [id (engine-diagnostics-in ctx id)]) all-engines))
     :derived-properties {:has-interface    (system-has-interface-in? ctx)
                          :has-substrate    (system-has-substrate-in? ctx)
                          :has-memory-layer (system-has-memory-layer-in? ctx)
                          :is-ai-complete   (system-is-ai-complete-in? ctx)}
     :total-transitions  (count @(:state-transitions ctx))}))

(defn bootstrap-system-in!
  "Bootstrap the complete engine system in `ctx`."
  [ctx]
  (initialize-system-state-in! ctx)
  (let [main-engine (create-engine-in ctx "main-engine" {:type :main :bootstrap true})]
    (update-system-component-in! ctx :engine-ready true)
    {:system-state  (get-system-state-in ctx)
     :main-engine   main-engine
     :engine-status (engine-status-in ctx "main-engine")
     :diagnostics   (system-diagnostics-in ctx)}))

;; ========================================
;; Global (singleton) API — thin wrappers
;; These delegate to the context-aware -*in functions using global atoms.
;; ========================================

(defn create-engine
  "Create a new engine instance with statechart configuration"
  ([]
   (create-engine (str (random-uuid))))
  ([engine-id]
   (create-engine engine-id {}))
  ([engine-id config]
   (create-engine-in (global-context) engine-id config)))

(defn get-engine
  "Retrieve engine by ID"
  [engine-id]
  (get-engine-in (global-context) engine-id))

(defn get-all-engines
  "Get all engines"
  []
  (get-all-engines-in (global-context)))

(defn engine-status
  "Get current status of engine"
  [engine-id]
  (engine-status-in (global-context) engine-id))

(defn trigger-engine-event!
  "Send event to engine statechart"
  [engine-id event & [data]]
  (trigger-engine-event-in! (global-context) engine-id event data))

;; ========================================
;; System state management (global wrappers)
;; ========================================

(defn initialize-system-state!
  "Initialize system state with bootstrap configuration."
  []
  (initialize-system-state-in! (global-context)))

(defn get-system-state
  "Get current system state."
  []
  (get-system-state-in (global-context)))

(defn update-system-component!
  "Mark a system component as ready."
  [component-key ready?]
  (update-system-component-in! (global-context) component-key ready?))

;; ========================================
;; Query functions for derived properties (global wrappers)
;; ========================================

(defn system-has-interface?
  "Check if system has complete interface (engine + query ready)."
  []
  (system-has-interface-in? (global-context)))

(defn system-has-substrate?
  "Check if system has substrate (engine ready)."
  []
  (system-has-substrate-in? (global-context)))

(defn system-has-memory-layer?
  "Check if system has complete memory layer."
  []
  (system-has-memory-layer-in? (global-context)))

(defn system-is-ai-complete?
  "Check if system has achieved AI COMPLETE status."
  []
  (system-is-ai-complete-in? (global-context)))

;; ========================================
;; State transition queries (global wrappers)
;; ========================================

(defn get-state-transitions
  "Get all state transitions, optionally filtered by engine."
  ([]
   (get-state-transitions-in (global-context)))
  ([engine-id]
   (get-state-transitions-in (global-context) engine-id)))

(defn get-recent-transitions
  "Get recent state transitions within time window."
  [since-instant]
  (filter #(.isAfter (:timestamp %) since-instant) @state-transitions))

;; ========================================
;; Introspection and diagnostics (global wrappers)
;; ========================================

(defn engine-diagnostics
  "Get diagnostic information about an engine."
  [engine-id]
  (engine-diagnostics-in (global-context) engine-id))

(defn system-diagnostics
  "Get diagnostic information about the entire system."
  []
  (system-diagnostics-in (global-context)))

;; ========================================
;; Bootstrap functions
;; ========================================

(defn bootstrap-system!
  "Bootstrap the complete engine system using global state."
  []
  (println "🚀 Bootstrapping Psi engine system...")
  (let [result (bootstrap-system-in! (global-context))]
    (println "✓ System state initialized")
    (println "✓ Main engine created:" (get-in result [:main-engine :engine-id]))
    (println "✓ Engine marked as ready")
    result))