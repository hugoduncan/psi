(ns psi.recursion.core
  "Feed-forward recursion controller.

   Establishes an isolated RecursionContext (Nullable pattern), global wrappers,
   the initial controller state shape, trigger intake, and readiness gating."
  (:require
   [psi.recursion.policy :as policy]))

(defrecord RecursionContext [state-atom config])

(defn initial-state
  "Return the initial controller state map."
  []
  {:status :idle
   :current-future-state nil
   :policy (policy/default-policy)
   :config (policy/default-config)
   :hooks []
   :cycles []
   :paused-reason nil
   :last-error nil})

(defn create-context
  "Create an isolated RecursionContext.

   Options:
   - :state-overrides   map merged over initial controller state
   - :config-overrides  map merged over default config"
  ([]
   (create-context {}))
  ([{:keys [state-overrides config-overrides]
     :or   {state-overrides {}
            config-overrides {}}}]
   (let [base-state  (initial-state)
         merged-config (merge (policy/default-config) config-overrides)
         state (merge base-state
                      {:config merged-config}
                      state-overrides)]
     (->RecursionContext
      (atom state)
      merged-config))))

(defonce ^:private global-ctx (atom nil))

(defn- ensure-global-ctx!
  []
  (or @global-ctx
      (let [ctx (create-context)]
        (reset! global-ctx ctx)
        ctx)))

(defn global-context
  "Return the global recursion context singleton, creating it when absent."
  []
  (ensure-global-ctx!))

(defn reset-global-context!
  "Reset the global context to nil. Useful for testing."
  []
  (reset! global-ctx nil))

(defn get-state-in
  "Return the full controller state map from `ctx`."
  [ctx]
  @(:state-atom ctx))

(defn swap-state-in!
  "Apply `f` to controller state atom in `ctx`."
  [ctx f & args]
  (apply swap! (:state-atom ctx) f args))

(defn get-state
  "Global wrapper for `get-state-in`."
  []
  (get-state-in (global-context)))

(defn swap-state!
  "Global wrapper for `swap-state-in!`."
  [f & args]
  (apply swap-state-in! (global-context) f args))

;;; --- Hooks ---

(defn register-hooks-in!
  "Initialize the hooks list from config's accepted trigger types.
   Each hook derives its `:enabled` flag from the config's `enabled-trigger-hooks`."
  [ctx]
  (let [state  (get-state-in ctx)
        config (:config state)
        accepted (:accepted-trigger-types config)
        enabled  (:enabled-trigger-hooks config)
        hooks  (mapv (fn [t]
                       {:id           (str "hook-" (name t))
                        :trigger-type t
                        :enabled      (contains? enabled t)
                        :timeout-ms   nil})
                     (sort-by name accepted))]
    (swap-state-in! ctx assoc :hooks hooks)
    hooks))

(defn register-hooks!
  "Global wrapper for `register-hooks-in!`."
  []
  (register-hooks-in! (global-context)))

;;; --- Trigger intake and readiness gating ---

(defn- new-cycle
  "Create a new cycle record for `trigger-signal` with the given initial `status`."
  [trigger-signal status]
  {:cycle-id           (str "cycle-" (random-uuid))
   :trigger            trigger-signal
   :started-at         (java.time.Instant/now)
   :ended-at           nil
   :status             status
   :observation        nil
   :proposal           nil
   :execution-attempts []
   :verification       nil
   :outcome            nil
   :learning-memory-ids #{}})

(defn- active-cycle?
  "True if cycle is in a non-terminal status."
  [cycle]
  (not (contains? #{:completed :failed :aborted :blocked} (:status cycle))))

(defn- readiness-ok?
  "Check all four readiness flags in `system-state`. Returns true when all ready."
  [system-state]
  (and (:query-ready system-state)
       (:graph-ready system-state)
       (:introspection-ready system-state)
       (:memory-ready system-state)))

(defn handle-trigger-in!
  "Main trigger entry point. Takes `ctx`, a `trigger-signal` map, and a
   `system-state` map with readiness flags. Returns a result map:
   - `{:result :accepted, :cycle-id ...}` on success
   - `{:result :ignored}` when trigger type is disabled
   - `{:result :blocked, :cycle-id ...}` when readiness fails
   - `{:result :rejected, :reason ...}` when trigger type unknown or controller busy"
  [ctx trigger-signal system-state]
  (let [state    (get-state-in ctx)
        config   (:config state)
        ttype    (:type trigger-signal)
        accepted (:accepted-trigger-types config)
        enabled  (:enabled-trigger-hooks config)]
    (cond
      ;; 1. Unknown trigger type
      (not (contains? accepted ttype))
      {:result :rejected, :reason :unknown-trigger-type}

      ;; 2. Disabled trigger — no state change, no cycle
      (not (contains? enabled ttype))
      {:result :ignored}

      ;; 3. Controller busy (not idle or has active cycles)
      (or (not= :idle (:status state))
          (some active-cycle? (:cycles state)))
      {:result :rejected, :reason :controller-busy}

      ;; 4. Readiness prerequisites fail
      (not (readiness-ok? system-state))
      (let [cycle (new-cycle trigger-signal :blocked)]
        (swap-state-in! ctx (fn [s]
                              (-> s
                                  (assoc :status :paused)
                                  (assoc :paused-reason "recursion_prerequisites_not_ready")
                                  (update :cycles conj cycle))))
        {:result :blocked, :cycle-id (:cycle-id cycle)})

      ;; 5. All checks pass — create observing cycle
      :else
      (let [cycle (new-cycle trigger-signal :observing)]
        (swap-state-in! ctx (fn [s]
                              (-> s
                                  (assoc :status :observing)
                                  (update :cycles conj cycle))))
        {:result :accepted, :cycle-id (:cycle-id cycle)}))))

(defn handle-trigger!
  "Global wrapper for `handle-trigger-in!`."
  [trigger-signal system-state]
  (handle-trigger-in! (global-context) trigger-signal system-state))
