(ns psi.agent-session.extensions
  "Extension registry, loading, dispatch, tool wrapping, and introspection."
  (:require
   [psi.deterministic-operation-registry.registry :as deterministic-op-registry]
   [psi.deterministic-operation-registry.defs :as deterministic-op-defs]
   [psi.agent-session.extensions.api :as api]
   [psi.agent-session.extensions.loader :as loader]
   [psi.command-registry.registry :as command-registry]
   [psi.tool-registry.registry :as tool-registry]
   [psi.tool-runtime.core :as tool-runtime]
   [taoensso.timbre :as timbre]))

(defrecord ExtensionRegistry [state])

(def ^:private default-allowed-events
  #{:session/ui-request-dialog
    :session/ui-set-widget
    :session/ui-clear-widget
    :session/ui-set-widget-spec
    :session/ui-clear-widget-spec
    :session/ui-set-status
    :session/ui-clear-status
    :session/ui-notify
    :session/ui-register-tool-renderer
    :session/ui-register-message-renderer
    :session/ui-set-tools-expanded})

(defn create-registry
  "Create an isolated extension registry.
  State is {:extensions          {path ExtensionRecord}
            :registration-order  [path ...]
            :flag-values         {name value}
            :event-bus           {channel [handler-fn ...]}}

  Extension records may also carry deterministic operations under
  `:operations` keyed by stable author-facing operation ids."
  []
  (->ExtensionRegistry
   (atom {:extensions         {}
          :registration-order []
          :flag-values        {}
          :event-bus          {}})))

(defn register-extension-in!
  "Register a new extension by path into `reg`.
   Seeds the extension record with explicit default `:allowed-events`.
   Extensions may narrow or extend these via `set-allowed-events-in!`.
   If an extension map was pre-seeded before formal registration, ensures the
   path is still present in `:registration-order`."
  [reg path]
  (swap! (:state reg)
         (fn [s]
           (let [existing   (get-in s [:extensions path])
                 registered? (some #(= path %) (:registration-order s))]
             (cond->
              (if existing
                s
                (assoc-in s [:extensions path]
                          {:path           path
                           :handlers       {}
                           :tools          {}
                           :commands       {}
                           :flags          {}
                           :shortcuts      {}
                           :operations     {}
                           :allowed-events default-allowed-events}))
               (not registered?)
               (update :registration-order conj path)))))
  reg)

(defn set-allowed-events-in!
  "Replace the explicit allowed-event set for `ext-path`.
   Accepts any sequential collection of event keywords."
  [reg ext-path allowed-events]
  (swap! (:state reg)
         assoc-in [:extensions ext-path :allowed-events]
         (set (or allowed-events #{})))
  reg)

(defn register-handler-in!
  "Register `handler-fn` for `event-name` on the extension at `ext-path`."
  [reg ext-path event-name handler-fn]
  (swap! (:state reg)
         update-in [:extensions ext-path :handlers event-name]
         (fnil conj [])
         {:extension-path ext-path :handler handler-fn})
  reg)

(defn register-tool-in!
  "Thin upper seam delegating tool registration to the extracted tool-registry owner."
  [reg ext-path tool]
  (tool-registry/register-tool-in! reg ext-path tool))

(defn tool-names-in
  "Thin upper seam delegating tool-name queries to the extracted tool-registry owner."
  [reg]
  (tool-registry/tool-names-in reg))

(defn all-tools-in
  "Thin upper seam delegating registered-tool listing to the extracted tool-registry owner."
  [reg]
  (tool-registry/all-tools-in reg))

(defn get-tool-in
  "Thin upper seam delegating tool lookup to the extracted tool-registry owner."
  [reg tool-name]
  (tool-registry/get-tool-in reg tool-name))

(defn register-command-in!
  "Thin upper seam delegating command registration to the extracted command-registry owner."
  [reg ext-path cmd]
  (command-registry/register-command-in! reg ext-path cmd))

(defn register-operation-in!
  "Register a deterministic operation for the extension at `ext-path`.
   Operation ids are stable author-facing ids such as
   `github/search-issues-by-label`.

   This records extension ownership in the extension registry. The runtime-owned
   deterministic operation registry remains authoritative for invoke-time
   resolution and duplicate detection."
  [reg ext-path operation]
  (let [operation* (deterministic-op-defs/normalize-operation-def
                    (assoc operation :ext-path ext-path :source :extension))]
    (swap! (:state reg)
           assoc-in [:extensions ext-path :operations (:id operation*)] operation*)
    reg))

(defn register-flag-in!
  "Register `flag` (a map with :name key) for the extension at `ext-path`.
   If the flag has a :default value and no value is set yet, sets the default."
  [reg ext-path flag]
  (swap! (:state reg)
         (fn [s]
           (let [s (assoc-in s [:extensions ext-path :flags (:name flag)] flag)]
             (if (and (contains? flag :default)
                      (not (contains? (:flag-values s) (:name flag))))
               (assoc-in s [:flag-values (:name flag)] (:default flag))
               s))))
  reg)

(defn register-shortcut-in!
  "Register `shortcut` (a map with :key and :handler) for the extension at `ext-path`."
  [reg ext-path shortcut]
  (swap! (:state reg)
         assoc-in [:extensions ext-path :shortcuts (:key shortcut)] shortcut)
  reg)

(defn unregister-extension-in!
  "Remove one registered extension from `reg`.
   Preserves other extension state, flag-values, and event-bus.

   When `deterministic-operation-registry` is provided, also removes any
   runtime-owned deterministic operations registered by the extension so invoke
   resolution cannot outlive extension ownership."
  ([reg path]
   (unregister-extension-in! reg path nil))
  ([reg path deterministic-operation-registry]
   (when deterministic-operation-registry
     (deterministic-op-registry/unregister-operations-by-extension-in!
      deterministic-operation-registry
      path))
   (swap! (:state reg)
          (fn [s]
            (-> s
                (update :extensions dissoc path)
                (update :registration-order (fn [order]
                                              (vec (remove #(= path %) order)))))))
   reg))

(defn unregister-all-in!
  "Remove all registered extensions from `reg`. Used during reload.
   Preserves flag-values and event-bus.

   When `deterministic-operation-registry` is provided, clears all extension-
   owned deterministic operations from the runtime registry before removing the
   extension records."
  ([reg]
   (unregister-all-in! reg nil))
  ([reg deterministic-operation-registry]
   (when deterministic-operation-registry
     (doseq [path (:registration-order @(:state reg))]
       (deterministic-op-registry/unregister-operations-by-extension-in!
        deterministic-operation-registry
        path)))
   (swap! (:state reg) assoc :extensions {} :registration-order [])
   reg))

(defn get-flag-in
  "Get the current value of flag `name` from `reg`."
  [reg name]
  (get-in @(:state reg) [:flag-values name]))

(defn set-flag-in!
  "Set the value of flag `name` in `reg`."
  [reg name value]
  (swap! (:state reg) assoc-in [:flag-values name] value)
  reg)

(defn all-flag-values-in
  "Return map of all flag name → current value."
  [reg]
  (:flag-values @(:state reg)))

(defn- state-in
  [reg]
  @(:state reg))

(defn- extension-item-maps
  [state item-key]
  (map #(or (get-in state [:extensions % item-key]) {})
       (:registration-order state)))

(defn- extension-item-names-in
  [reg item-key]
  (let [state (state-in reg)]
    (into #{}
          (mapcat keys)
          (extension-item-maps state item-key))))

(defn bus-emit-in!
  "Emit `data` on `channel` to all event bus subscribers in `reg`."
  [reg channel data]
  (let [handlers (get-in @(:state reg) [:event-bus channel])]
    (doseq [h handlers]
      (try (h data)
           (catch Exception e
             (timbre/warn "Event bus handler error on channel" channel
                          (ex-message e)))))))

(defn bus-on-in!
  "Subscribe `handler-fn` to `channel` on the event bus in `reg`.
   Returns a no-arg unsubscribe function."
  [reg channel handler-fn]
  (swap! (:state reg) update-in [:event-bus channel] (fnil conj []) handler-fn)
  (fn []
    (swap! (:state reg) update-in [:event-bus channel]
           (fn [handlers] (vec (remove #(= % handler-fn) handlers))))))

(defn dispatch-in
  "Dispatch `event` to all handlers registered for `event-name` in `reg`.
   Fires handlers in registration order (extension registration order, then
   handler-registration order within each extension).
   Returns a map:
     :cancelled?        — true if any handler returned {:cancel true}
     :results           — vector of all handler return values (nil for void handlers)
     :override-present? — true when any handler returned explicit override data
                          via canonical :result or legacy :compaction
     :override          — selected override payload, or nil.

   Override precedence:
   - canonical :result wins over legacy :compaction
   - within each form, last writer wins
   - explicit {:result nil} counts as an override and suppresses legacy fallback"
  [reg event-name event]
  (let [state             @(:state reg)
        ordered-paths     (:registration-order state)
        all-handler-lists (keep #(get-in state [:extensions % :handlers event-name])
                                ordered-paths)
        all-handlers      (mapcat identity all-handler-lists)
        results           (mapv (fn [{:keys [handler]}]
                                  (try (handler event)
                                       (catch Exception e
                                         {:error (.getMessage e)})))
                                all-handlers)
        result-overrides  (reduce (fn [xs r]
                                    (if (and (map? r) (contains? r :result))
                                      (conj xs (:result r))
                                      xs))
                                  []
                                  results)
        comp-overrides    (reduce (fn [xs r]
                                    (if (and (map? r) (contains? r :compaction))
                                      (conj xs (:compaction r))
                                      xs))
                                  []
                                  results)
        override-present? (or (seq result-overrides)
                              (seq comp-overrides))
        override          (if (seq result-overrides)
                            (last result-overrides)
                            (last comp-overrides))]
    {:cancelled?        (boolean (some :cancel results))
     :override-present? (boolean override-present?)
     :override          override
     :results           results}))

(defn tool-call-event
  "Build the canonical `tool_call` bus-event payload.
   Single source of the cross-path payload shape — used by both
   `dispatch-tool-call-in` (plan path) and the session
   `emit-tool-lifecycle!` bridge (interactive/batch path)."
  [tool-name tool-call-id args]
  {:type         "tool_call"
   :tool-name    tool-name
   :tool-call-id tool-call-id
   :input        args})

(defn tool-result-event
  "Build the canonical `tool_result` bus-event payload.
   Single source of the cross-path payload shape and value semantics —
   used by both `dispatch-tool-result-in` (plan path) and the session
   `emit-tool-lifecycle!` bridge (interactive/batch path). Coerces
   `:content` to normalized content-blocks and `:is-error` to a strict
   boolean so both paths deliver an identical contract."
  [tool-name tool-call-id args content details is-error?]
  {:type         "tool_result"
   :tool-name    tool-name
   :tool-call-id tool-call-id
   :input        args
   :content      (tool-runtime/normalize-tool-content content)
   :details      details
   :is-error     (boolean is-error?)})

(defn dispatch-tool-call-in
  "Dispatch a tool_call event. Returns {:block true :reason s} or nil."
  [reg tool-name tool-call-id args]
  (let [{:keys [results]} (dispatch-in reg "tool_call"
                                       (tool-call-event tool-name tool-call-id args))]
    (first (filter :block results))))

(def modifiable-tool-result-keys
  "The set of `tool_result` keys an extension handler may modify.

   Single source of the modifiable-key contract for the tool-result path:
   `dispatch-tool-result-in` *selects* a handler return as an override iff it is
   a map containing at least one of these keys (the selection guard), and
   `merge-tool-result-override` *applies* exactly these keys from the override
   onto the result (the application). Both the producer (selection) and the
   consumer (application) derive from this one set, so adding a modifiable key
   is a single-site edit."
  #{:content :details :is-error})

(defn modifiable-tool-result-override?
  "True when `x` is a map carrying at least one modifiable tool-result key,
   i.e. a handler return eligible to override the result. The selection guard."
  [x]
  (and (map? x)
       (boolean (some #(contains? x %) modifiable-tool-result-keys))))

(defn merge-tool-result-override
  "Apply `override`'s modifiable keys onto `result`, copying only the keys in
   `modifiable-tool-result-keys` that `override` actually carries. When
   `override` is nil/absent, `result` is returned unchanged. The application
   half of the modifiable-key contract."
  [result override]
  (reduce (fn [acc k]
            (cond-> acc
              (contains? override k) (assoc k (get override k))))
          result
          modifiable-tool-result-keys))

(defn dispatch-tool-result-in
  "Dispatch a tool_result event. Returns modified result map or nil."
  [reg tool-name tool-call-id args result is-error?]
  (let [{:keys [results]} (dispatch-in reg "tool_result"
                                       (tool-result-event tool-name tool-call-id args
                                                          (:content result)
                                                          (:details result)
                                                          is-error?))]
    (first (filter modifiable-tool-result-override? results))))

(defn extensions-in
  "Return sequence of all registered extension paths in `reg`."
  [reg]
  (:registration-order (state-in reg)))

(defn extension-count-in
  "Return number of registered extensions in `reg`."
  [reg]
  (count (extensions-in reg)))

(defn handler-count-in
  "Return total number of handler registrations across all events in `reg`."
  [reg]
  (let [state (state-in reg)]
    (reduce
     (fn [acc handlers-by-event]
       (+ acc (reduce + 0 (map count (vals handlers-by-event)))))
     0
     (extension-item-maps state :handlers))))

(defn handler-event-names-in
  "Return sorted set of all event names that have at least one handler in `reg`."
  [reg]
  (into (sorted-set) (extension-item-names-in reg :handlers)))

(defn operation-ids-in
  "Return set of all deterministic operation ids registered across all extensions in `reg`."
  [reg]
  (extension-item-names-in reg :operations))

(defn command-names-in
  "Thin upper seam delegating command-name queries to the extracted command-registry owner."
  [reg]
  (command-registry/command-names-in reg))

(defn flag-names-in
  "Return set of all registered flag names across all extensions in `reg`."
  [reg]
  (extension-item-names-in reg :flags))

(defn all-commands-in
  "Thin upper seam delegating registered-command listing to the extracted command-registry owner."
  [reg]
  (command-registry/all-commands-in reg))

(defn all-flags-in
  "Return vector of all registered flag maps across all extensions."
  [reg]
  (let [state (state-in reg)]
    (reduce
     (fn [flags path]
       (reduce-kv
        (fn [flags _ flag]
          (conj flags
                (assoc flag
                       :extension-path path
                       :current-value (get-in state [:flag-values (:name flag)]))))
        flags
        (or (get-in state [:extensions path :flags]) {})))
     []
     (:registration-order state))))
(defn get-command-in
  "Thin upper seam delegating command lookup to the extracted command-registry owner."
  [reg cmd-name]
  (command-registry/get-command-in reg cmd-name))

(defn extension-detail-in
  "Return detail map for a single extension at `ext-path`, or nil."
  [reg ext-path]
  (let [state @(:state reg)
        ext   (get-in state [:extensions ext-path])]
    (when ext
      (let [commands (filter #(= ext-path (:extension-path %))
                             (command-registry/all-commands-in reg))
            tools    (filter #(= ext-path (:extension-path %))
                             (tool-registry/all-tools-in reg))]
        {:path            ext-path
         :handler-names   (into (sorted-set) (keys (:handlers ext)))
         :handler-count   (reduce + 0 (map count (vals (:handlers ext))))
         :tool-names      (into (sorted-set) (map :name) tools)
         :tool-count      (count tools)
         :operation-ids   (into (sorted-set) (keys (:operations ext)))
         :operation-count (count (:operations ext))
         :command-names   (into (sorted-set) (map :name) commands)
         :command-count   (count commands)
         :flag-names      (into (sorted-set) (keys (:flags ext)))
         :flag-count      (count (:flags ext))
         :shortcut-count  (count (:shortcuts ext))
         :allowed-events  (:allowed-events ext)}))))

(defn extension-details-in
  "Return vector of detail maps for all registered extensions."
  [reg]
  (mapv #(extension-detail-in reg %) (extensions-in reg)))

(defn summary-in
  "Return a summary map describing the extension registry state."
  [reg]
  {:extension-count (extension-count-in reg)
   :extensions      (extensions-in reg)
   :handler-count   (handler-count-in reg)
   :handler-events  (handler-event-names-in reg)
   :tool-names      (tool-registry/tool-names-in reg)
   :operation-ids   (operation-ids-in reg)
   :command-names   (command-names-in reg)
   :flag-names      (flag-names-in reg)})

(defn create-extension-api
  "Build the ExtensionAPI map for an extension at `ext-path`.
   The API provides registration methods plus EQL runtime access.
   `runtime-fns` is a map of runtime implementations:
     :query-fn       — (fn [eql-query])
     :mutate-fn      — (fn [op-sym params])
     :get-api-key-fn — (fn [provider]) ; narrow auth capability
     :ui-type-fn     — (fn [] => :console|:tui|:emacs|nil)
     :ui-context-fn  — (fn [ext-path] => extension ui context)
     :log-fn         — (fn [text]) ; diagnostic output → stderr
   Any missing runtime key throws."
  [reg ext-path runtime-fns]
  (api/create-extension-api
   {:register-handler-in!     register-handler-in!
    :register-tool-in!        register-tool-in!
    :register-command-in!     register-command-in!
    :register-operation-in!   register-operation-in!
    :register-shortcut-in!    register-shortcut-in!
    :register-flag-in!        register-flag-in!
    :set-allowed-events-in!   set-allowed-events-in!
    :get-flag-in              get-flag-in
    :bus-emit-in!             bus-emit-in!
    :bus-on-in!               bus-on-in!}
   reg
   ext-path
   runtime-fns))

(defn discover-extension-paths
  "Discover extension paths from standard locations and explicit paths.
   Search order:
     1. .psi/extensions/        (project-local)
     2. ~/.psi/agent/extensions/  (user-global)
   Plus any explicit paths (files or dirs)."
  ([] (loader/discover-extension-paths [] nil))
  ([configured-paths] (loader/discover-extension-paths configured-paths nil))
  ([configured-paths cwd]
   (loader/discover-extension-paths configured-paths cwd)))

(defn load-extension-in!
  "Load a single extension from `ext-path` into `reg`.
   The file must define an `init` function in its namespace.
   `runtime-fns` is passed through to `create-extension-api`.
   Returns {:extension ext-path :error nil} or {:extension nil :error msg}."
  [reg ext-path runtime-fns]
  (let [unregister-extension* (fn [reg* ext-path*]
                                (unregister-extension-in!
                                 reg*
                                 ext-path*
                                 (:deterministic-operation-registry runtime-fns)))]
    (loader/load-extension-in! reg ext-path runtime-fns register-extension-in! unregister-extension* create-extension-api)))

(defn load-init-var-extension-in!
  "Load a manifest-installed extension by stable id + init var.
   Returns {:extension ext-id :error nil} or {:extension nil :error msg}."
  [reg ext-id init-var runtime-fns]
  (let [unregister-extension* (fn [reg* ext-id*]
                                (unregister-extension-in!
                                 reg*
                                 ext-id*
                                 (:deterministic-operation-registry runtime-fns)))]
    (loader/load-init-var-extension-in! reg ext-id init-var runtime-fns register-extension-in! unregister-extension* create-extension-api)))

(defn load-extension-init-in!
  "Compatibility alias for init-var-backed manifest activation."
  [reg ext-id init-var runtime-fns]
  (load-init-var-extension-in! reg ext-id init-var runtime-fns))

(defn activate-extensions-in!
  "Activate a mixed set of extension activation entries under one shared layer.
   See `psi.agent-session.extensions.loader/activate-extensions-in!` for the
   supported entry shapes."
  [reg runtime-fns activation-entries]
  (let [unregister-extension* (fn [reg* ext-id*]
                                (unregister-extension-in!
                                 reg*
                                 ext-id*
                                 (:deterministic-operation-registry runtime-fns)))]
    (loader/activate-extensions-in! reg runtime-fns activation-entries register-extension-in! unregister-extension* create-extension-api)))

(defn load-extensions-in!
  "Discover and load all extensions into `reg`.
   `configured-paths` are explicit CLI paths.
   `runtime-fns` is passed to each extension's API.
   Returns {:loaded [paths] :errors [{:path :error}]}."
  ([reg] (load-extensions-in! reg {} []))
  ([reg runtime-fns] (load-extensions-in! reg runtime-fns []))
  ([reg runtime-fns configured-paths]
   (load-extensions-in! reg runtime-fns configured-paths nil))
  ([reg runtime-fns configured-paths cwd]
   (loader/load-extensions-in! reg runtime-fns configured-paths cwd load-extension-in!)))

(defn reload-extensions-in!
  "Clear registered extensions and reload them from discovery/configured paths."
  ([reg runtime-fns configured-paths]
   (reload-extensions-in! reg runtime-fns configured-paths nil))
  ([reg runtime-fns configured-paths cwd]
   (let [unregister-all* (fn [reg*]
                           (unregister-all-in!
                            reg*
                            (:deterministic-operation-registry runtime-fns)))]
     (loader/reload-extensions-in! reg runtime-fns configured-paths cwd unregister-all* load-extensions-in!))))