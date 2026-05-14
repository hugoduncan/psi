(ns psi.tool-registry.registry
  "Tool-specific extension-registry ownership: validation, registration, and queries."
  (:require
   [psi.tool-registry.defs :as defs]
   [psi.ui.state :as ui-state]))

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

(defn- state-in
  [reg]
  @(:state reg))

(defn- ensure-registered-extension-path!
  [reg ext-path]
  (when-not (contains? (:extensions (state-in reg)) ext-path)
    (throw (ex-info (str "Cannot register tool for unregistered extension path: " (pr-str ext-path))
                    {:ext-path ext-path
                     :reason :unregistered-extension-path}))))

(defn register-tool-in!
  "Register `tool` (a map with :name key) for the extension at `ext-path`.
   Tool maps may include :lambda-description for lambda-mode prompt rendering.
   Stores canonical normalized tool defs in the registry.
   Throws when `ext-path` is not already registered, or when tool name is
   missing or not canonical kebab-case."
  [reg ext-path tool]
  (let [tool-name (:name tool)]
    (ensure-registered-extension-path! reg ext-path)
    (when-not (valid-tool-name? tool-name)
      (throw (ex-info (str "Invalid tool name: " (pr-str tool-name)
                           ". Expected kebab-case matching " tool-name-pattern)
                      {:ext-path  ext-path
                       :tool-name tool-name
                       :pattern   (str tool-name-pattern)})))
    (let [tool* (defs/normalize-tool-def (assoc tool :source :extension :ext-path ext-path))]
      (swap! (:state reg)
             assoc-in [:extensions ext-path :tools tool-name] tool*)
      (ui-state/register-tool-def-renderers!
       (get-in @(:state reg) [:ui :extension-ui])
       (assoc tool* :extension-path ext-path))
      reg)))

(defn- extension-item-maps
  [state item-key]
  (map #(or (get-in state [:extensions % item-key]) {})
       (:registration-order state)))

(defn tool-names-in
  "Return set of all registered tool names across all extensions in `reg`."
  [reg]
  (let [state (state-in reg)]
    (into #{}
          (mapcat keys)
          (extension-item-maps state :tools))))

(defn all-tools-in
  "Return vector of all registered tool definition maps across all extensions.
   First registration per name wins."
  [reg]
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
        (or (get-in state [:extensions path :tools]) {})))
     []
     (:registration-order state))))

(defn get-tool-in
  "Return the tool map for `tool-name`, or nil."
  [reg tool-name]
  (let [state (state-in reg)]
    (some #(get-in state [:extensions % :tools tool-name])
          (:registration-order state))))
