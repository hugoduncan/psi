(ns psi.tool-registry.registry
  "Tool-specific extension-registry ownership: validation, registration, and queries.

   Built-in tools are stored under the `:built-in-tools` key in registry state,
   keyed by provenance id then tool name.  Extension tools continue to live
   under `:extensions`.  All public read paths merge both stores so callers
   see a unified surface."
  (:require
   [psi.tool-registry.defs :as defs]))

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
      (when-not (fn? (:format-request tool*))
        (throw (ex-info (str "Tool definition missing required :format-request fn: " (pr-str tool-name))
                        {:ext-path ext-path
                         :tool-name tool-name
                         :reason :missing-format-request})))
      (swap! (:state reg)
             assoc-in [:extensions ext-path :tools tool-name] tool*)
      reg)))

(defn- extension-item-maps
  [state item-key]
  (map #(or (get-in state [:extensions % item-key]) {})
       (:registration-order state)))

(defn tool-names-in
  "Return set of all registered tool names across built-ins and extensions."
  [reg]
  (let [state (state-in reg)
        built-in-names (into #{}
                             (for [[_ tools] (:built-in-tools state)
                                   [name _]  tools]
                               name))
        ext-names (into #{}
                        (mapcat keys)
                        (extension-item-maps state :tools))]
    (into built-in-names ext-names)))

(defn all-tools-in
  "Return vector of all registered tool definition maps across built-ins and extensions.
   Built-in tools are listed first; first registration per name wins."
  [reg]
  (let [state (state-in reg)
        seen  (volatile! #{})
        built-in-items (reduce
                        (fn [items [_ tools]]
                          (reduce-kv
                           (fn [items name tool]
                             (if (contains? @seen name)
                               items
                               (do (vswap! seen conj name)
                                   (conj items tool))))
                           items
                           tools))
                        []
                        (:built-in-tools state))]
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
     built-in-items
     (:registration-order state))))

(defn get-tool-in
  "Return the tool map for `tool-name`, or nil.
   Checks built-in tools first, then extension tools."
  [reg tool-name]
  (let [state (state-in reg)]
    (or (some #(get-in state [:built-in-tools % tool-name])
              (keys (:built-in-tools state)))
        (some #(get-in state [:extensions % :tools tool-name])
              (:registration-order state)))))

;;; Built-in registration

(defn register-built-in-tool-in!
  "Register `tool` as a built-in tool owned by `provenance-id`.
   Built-in tools do not require a prior extension registration.
   `provenance-id` is a stable identifier (e.g. `\"built-in:workflow\"`) used
   to group and identify the owning built-in surface."
  [reg provenance-id tool]
  (let [tool-name (:name tool)]
    (when-not (valid-tool-name? tool-name)
      (throw (ex-info (str "Invalid built-in tool name: " (pr-str tool-name)
                           ". Expected kebab-case matching " tool-name-pattern)
                      {:provenance-id provenance-id
                       :tool-name     tool-name
                       :pattern       (str tool-name-pattern)})))
    (let [tool* (defs/normalize-tool-def (assoc tool :source :built-in :ext-path provenance-id))]
      (when-not (fn? (:format-request tool*))
        (throw (ex-info (str "Built-in tool definition missing required :format-request fn: " (pr-str tool-name))
                        {:provenance-id provenance-id
                         :tool-name     tool-name
                         :reason        :missing-format-request})))
      (swap! (:state reg)
             assoc-in [:built-in-tools provenance-id tool-name] tool*)
      reg)))

(defn all-built-in-tools-in
  "Return vector of all registered built-in tool maps, across all provenance ids."
  [reg]
  (let [state (state-in reg)]
    (vec (for [[_ tools] (:built-in-tools state)
               [_ tool]  tools]
           tool))))

(defn built-in-tool-names-in
  "Return set of all registered built-in tool names."
  [reg]
  (let [state (state-in reg)]
    (into #{}
          (for [[_ tools] (:built-in-tools state)
                [name _]  tools]
            name))))
