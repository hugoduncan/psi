(ns psi.recursion.core
  "Feed-forward recursion controller scaffold.

   Establishes an isolated RecursionContext (Nullable pattern), global wrappers,
   and the initial controller state shape. Subsequent tasks add trigger handling,
   observe/plan/execute/verify/learn phases, and EQL resolvers."
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
