(ns psi.agent-session.extensions
  "Extension registry, loading, dispatch, tool wrapping, and introspection."
  (:require
   [psi.agent-session.deterministic-operations :as deterministic-ops]
   [psi.agent-session.extensions.api :as api]
   [psi.agent-session.extensions.loader :as loader]
   [psi.agent-session.tool-defs :as tool-defs]
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

(def ^:private tool-name-pattern
  "Canonical tool names are kebab-case ASCII.
   This keeps names portable across model providers and transports."
  #"^[a-z0-9][a-z0-9-]*$")

(defn valid-tool-name?
  "Return true when `tool-name` is canonical kebab-case ASCII.
   Examples: read, psi-tool, delegate."
  [tool-name]
  (and (string? tool-name)
       (boolean (re-matches tool-name-pattern tool-name))))

(defn register-tool-in!
  "Register `tool` (a map with :name key) for the extension at `ext-path`.
   Tool maps may include :lambda-description for lambda-mode prompt rendering.
   Stores canonical normalized tool defs in the registry.
   Throws when tool name is missing or not canonical kebab-case."
  [reg ext-path tool]
  (let [tool-name (:name tool)]
    (when-not (valid-tool-name? tool-name)
      (throw (ex-info (str "Invalid tool name: " (pr-str tool-name)
                           ". Expected kebab-case matching " tool-name-pattern)
                      {:ext-path  ext-path
                       :tool-name tool-name
                       :pattern   (str tool-name-pattern)})))
    (let [tool* (tool-defs/normalize-tool-def (assoc tool :source :extension :ext-path ext-path))]
      (swap! (:state reg)
             assoc-in [:extensions ext-path :tools tool-name] tool*)
      reg)))

(defn register-command-in!
  "Register `cmd` (a map with :name key) for the extension at `ext-path`."
  [reg ext-path cmd]
  (swap! (:state reg)
         assoc-in [:extensions ext-path :commands (:name cmd)] cmd)
  reg)

(defn register-operation-in!
  "Register a deterministic operation for the extension at `ext-path`.
   Operation ids are stable author-facing ids such as
   `github/search-issues-by-label`.

   This records extension ownership in the extension registry. The runtime-owned
   deterministic operation registry remains authoritative for invoke-time
   resolution and duplicate detection."
  [reg ext-path operation]
  (let [operation* (deterministic-ops/normalize-operation-def
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
   Preserves other extension state, flag-values, and event-bus."
  [reg path]
  (swap! (:state reg)
         (fn [s]
           (-> s
               (update :extensions dissoc path)
               (update :registration-order (fn [order]
                                             (vec (remove #(= path %) order)))))))
  reg)

(defn unregister-all-in!
  "Remove all registered extensions from `reg`. Used during reload.
   Preserves flag-values and event-bus."
  [reg]
  (swap! (:state reg) assoc :extensions {} :registration-order [])
  reg)

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

(defn- all-first-registered-items-in
  [reg item-key]
  (let [state (state-in reg)
        seen  (volatile! #{})]
    (reduce
     (fn [items path]
       (reduce-kv
        (fn [items name item]
          (if (contains? @seen name)
            items
            (do
              (vswap! seen conj name)
              (conj items (assoc item :extension-path path)))))
        items
        (or (get-in state [:extensions path item-key]) {})))
     []
     (:registration-order state))))

(defn- get-extension-item-in
  [reg item-key item-name]
  (let [state (state-in reg)]
    (some #(get-in state [:extensions % item-key item-name])
          (:registration-order state))))

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

(defn dispatch-tool-call-in
  "Dispatch a tool_call event. Returns {:block true :reason s} or nil."
  [reg tool-name tool-call-id args]
  (let [{:keys [results]} (dispatch-in reg "tool_call"
                                       {:type         "tool_call"
                                        :tool-name    tool-name
                                        :tool-call-id tool-call-id
                                        :input        args})]
    (first (filter :block results))))

(defn dispatch-tool-result-in
  "Dispatch a tool_result event. Returns modified result map or nil."
  [reg tool-name tool-call-id args result is-error?]
  (let [{:keys [results]} (dispatch-in reg "tool_result"
                                       {:type         "tool_result"
                                        :tool-name    tool-name
                                        :tool-call-id tool-call-id
                                        :input        args
                                        :content      (:content result)
                                        :details      (:details result)
                                        :is-error     is-error?})]
    (first (filter #(and (map? %)
                         (or (contains? % :content) (contains? % :details)
                             (contains? % :is-error)))
                   results))))

(defn wrap-tool-executor
  "Wrap a tool executor fn with extension pre/post hooks.
   `execute-fn` is (fn [tool-name args] → {:content s :is-error bool}).
   Returns a wrapped fn with the same signature."
  [reg execute-fn]
  (fn [tool-name args]
    (let [block-result (dispatch-tool-call-in reg tool-name nil args)]
      (if (:block block-result)
        {:content  (or (:reason block-result)
                       "Tool execution was blocked by an extension")
         :is-error true}
        (let [result   (execute-fn tool-name args)
              is-error (:is-error result false)
              modified (dispatch-tool-result-in
                        reg tool-name nil args result is-error)]
          (if modified
            (cond-> result
              (contains? modified :content)  (assoc :content (:content modified))
              (contains? modified :details)  (assoc :details (:details modified))
              (contains? modified :is-error) (assoc :is-error (:is-error modified)))
            result))))))

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

(defn tool-names-in
  "Return set of all registered tool names across all extensions in `reg`."
  [reg]
  (extension-item-names-in reg :tools))

(defn operation-ids-in
  "Return set of all deterministic operation ids registered across all extensions in `reg`."
  [reg]
  (extension-item-names-in reg :operations))

(defn command-names-in
  "Return set of all registered command names across all extensions in `reg`."
  [reg]
  (extension-item-names-in reg :commands))

(defn flag-names-in
  "Return set of all registered flag names across all extensions in `reg`."
  [reg]
  (extension-item-names-in reg :flags))

(defn all-tools-in
  "Return vector of all registered tool definition maps across all extensions.
   First registration per name wins."
  [reg]
  (all-first-registered-items-in reg :tools))

(defn all-commands-in
  "Return vector of all registered command maps across all extensions.
   First registration per name wins."
  [reg]
  (all-first-registered-items-in reg :commands))

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
  "Return the command map for `cmd-name`, or nil."
  [reg cmd-name]
  (get-extension-item-in reg :commands cmd-name))

(defn get-tool-in
  "Return the tool map for `tool-name`, or nil."
  [reg tool-name]
  (get-extension-item-in reg :tools tool-name))

(defn extension-detail-in
  "Return detail map for a single extension at `ext-path`, or nil."
  [reg ext-path]
  (let [state @(:state reg)
        ext   (get-in state [:extensions ext-path])]
    (when ext
      {:path            ext-path
       :handler-names   (into (sorted-set) (keys (:handlers ext)))
       :handler-count   (reduce + 0 (map count (vals (:handlers ext))))
       :tool-names      (into (sorted-set) (keys (:tools ext)))
       :tool-count      (count (:tools ext))
       :operation-ids   (into (sorted-set) (keys (:operations ext)))
       :operation-count (count (:operations ext))
       :command-names   (into (sorted-set) (keys (:commands ext)))
       :command-count   (count (:commands ext))
       :flag-names      (into (sorted-set) (keys (:flags ext)))
       :flag-count      (count (:flags ext))
       :shortcut-count  (count (:shortcuts ext))
       :allowed-events  (:allowed-events ext)})))

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
   :tool-names      (tool-names-in reg)
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
  (loader/load-extension-in! reg ext-path runtime-fns register-extension-in! unregister-extension-in! create-extension-api))

(defn load-init-var-extension-in!
  "Load a manifest-installed extension by stable id + init var.
   Returns {:extension ext-id :error nil} or {:extension nil :error msg}."
  [reg ext-id init-var runtime-fns]
  (loader/load-init-var-extension-in! reg ext-id init-var runtime-fns register-extension-in! unregister-extension-in! create-extension-api))

(defn load-extension-init-in!
  "Compatibility alias for init-var-backed manifest activation."
  [reg ext-id init-var runtime-fns]
  (load-init-var-extension-in! reg ext-id init-var runtime-fns))

(defn activate-extensions-in!
  "Activate a mixed set of extension activation entries under one shared layer.
   See `psi.agent-session.extensions.loader/activate-extensions-in!` for the
   supported entry shapes."
  [reg runtime-fns activation-entries]
  (loader/activate-extensions-in! reg runtime-fns activation-entries register-extension-in! unregister-extension-in! create-extension-api))

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
   (loader/reload-extensions-in! reg runtime-fns configured-paths cwd unregister-all-in! load-extensions-in!)))