(ns psi.tool-registry.registry
  "Tool-specific registry adapter over the shared root-registry substrate.

   Tool-registry owns tool-name validation, canonical normalization, and the
   public merged built-in plus extension tool surface. Shared storage,
   ownership, and lower-level lookup mechanics live in
   `psi.root-registry.registry`."
  (:require
   [psi.root-registry.registry :as root-registry]
   [psi.tool-registry.defs :as defs]))

(def ^:private registry-id
  :tools)

(def ^:private built-in-extension-id
  :built-in)

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

(defn- ensure-root-registry-declared
  [state]
  (if (root-registry/declared-registry? state registry-id)
    state
    (root-registry/declare-registry state registry-id)))

(defn- ensure-registered-extension-path!
  [reg ext-path]
  (when-not (contains? (:extensions (state-in reg)) ext-path)
    (throw (ex-info (str "Cannot register tool for unregistered extension path: " (pr-str ext-path))
                    {:ext-path ext-path
                     :reason :unregistered-extension-path}))))

(defn- registered-extension-entry
  [reg ext-path]
  (-> (root-registry/lookup (state-in reg) registry-id ext-path)
      :result
      :value))

(defn- extension-tool-map
  [reg ext-path]
  (or (get-in (registered-extension-entry reg ext-path) [:value :tools]) {}))

(defn- register-root-entry!
  [reg entry]
  (swap! (:state reg)
         (fn [state]
           (:root-state (root-registry/register (ensure-root-registry-declared state)
                                                registry-id
                                                entry))))
  reg)

(defn- extension-tool-entry
  [ext-path tools]
  {:id ext-path
   :extension-id ext-path
   :value {:tools tools}})

(defn- built-in-tool-entry
  [provenance-id tools]
  {:id provenance-id
   :extension-id built-in-extension-id
   :provenance-id provenance-id
   :value {:tools tools}})

(defn- ensure-valid-tool!
  [owner-id tool]
  (let [tool-name (:name tool)]
    (when-not (valid-tool-name? tool-name)
      (throw (ex-info (str "Invalid tool name: " (pr-str tool-name)
                           ". Expected kebab-case matching " tool-name-pattern)
                      {:owner-id  owner-id
                       :tool tool
                       :tool-name tool-name
                       :pattern   (str tool-name-pattern)
                       :reason    :invalid-tool-name}))))
  tool)

(defn- ensure-valid-built-in-tool!
  [provenance-id tool]
  (let [tool-name (:name tool)]
    (when-not (valid-tool-name? tool-name)
      (throw (ex-info (str "Invalid built-in tool name: " (pr-str tool-name)
                           ". Expected kebab-case matching " tool-name-pattern)
                      {:provenance-id provenance-id
                       :tool tool
                       :tool-name     tool-name
                       :pattern       (str tool-name-pattern)
                       :reason        :invalid-tool-name}))))
  tool)

(defn- normalize-extension-tool
  [ext-path tool]
  (let [tool* (defs/normalize-tool-def (assoc tool :source :extension :ext-path ext-path))]
    (when-not (fn? (:format-request tool*))
      (throw (ex-info (str "Tool definition missing required :format-request fn: " (pr-str (:name tool*)))
                      {:ext-path ext-path
                       :tool-name (:name tool*)
                       :reason :missing-format-request})))
    tool*))

(defn- normalize-built-in-tool
  [provenance-id tool]
  (let [tool* (defs/normalize-tool-def (assoc tool :source :built-in :ext-path provenance-id))]
    (when-not (fn? (:format-request tool*))
      (throw (ex-info (str "Built-in tool definition missing required :format-request fn: " (pr-str (:name tool*)))
                      {:provenance-id provenance-id
                       :tool-name     (:name tool*)
                       :reason        :missing-format-request})))
    tool*))

(defn register-tool-in!
  "Register `tool` (a map with :name key) for the extension at `ext-path`.
   Tool maps may include :lambda-description for lambda-mode prompt rendering.
   Stores canonical normalized tool defs in the registry.
   Throws when `ext-path` is not already registered, or when tool name is
   missing or not canonical kebab-case."
  [reg ext-path tool]
  (ensure-registered-extension-path! reg ext-path)
  (let [tool* (->> tool
                   (ensure-valid-tool! ext-path)
                   (normalize-extension-tool ext-path))
        tools (assoc (extension-tool-map reg ext-path)
                     (:name tool*)
                     tool*)]
    (register-root-entry! reg (extension-tool-entry ext-path tools))))

(defn- all-root-entries
  [reg]
  (let [result (:result (root-registry/list-entries (state-in reg)
                                                    registry-id))]
    (:entries result)))

(defn- built-in-tool-entries
  [reg]
  (->> (all-root-entries reg)
       (filter #(= built-in-extension-id (:extension-id %)))))

(defn- extension-registration-order
  [reg]
  (:registration-order (state-in reg)))

(defn- extension-tool-entries
  [reg]
  (keep (fn [extension-path]
          (when-let [entry (registered-extension-entry reg extension-path)]
            {:extension-path extension-path
             :tools (get-in entry [:value :tools])}))
        (extension-registration-order reg)))

(defn tool-names-in
  "Return set of all registered tool names across built-ins and extensions."
  [reg]
  (let [built-in-names (into #{}
                             (mapcat (comp keys :tools :value))
                             (built-in-tool-entries reg))
        ext-names (into #{}
                        (mapcat (comp keys :tools))
                        (extension-tool-entries reg))]
    (into built-in-names ext-names)))

(defn- built-in-tool-map
  [entry]
  (assoc (:value entry)
         :source :built-in
         :ext-path (:provenance-id entry)))

(defn all-tools-in
  "Return vector of all registered tool definition maps across built-ins and extensions.
   Built-in tools are listed first; first registration per name wins."
  [reg]
  (let [seen (volatile! #{})
        built-in-items (reduce
                        (fn [items entry]
                          (reduce-kv
                           (fn [items name tool]
                             (if (contains? @seen name)
                               items
                               (do
                                 (vswap! seen conj name)
                                 (conj items (built-in-tool-map (assoc entry :value tool))))))
                           items
                           (:tools (:value entry))))
                        []
                        (built-in-tool-entries reg))]
    (reduce
     (fn [items {:keys [extension-path tools]}]
       (reduce-kv
        (fn [items name tool]
          (if (contains? @seen name)
            items
            (do
              (vswap! seen conj name)
              (conj items (assoc tool :extension-path extension-path)))))
        items
        tools))
     built-in-items
     (extension-tool-entries reg))))

(defn get-tool-in
  "Return the tool map for `tool-name`, or nil.
   Checks built-in tools first, then extension tools in registration order."
  [reg tool-name]
  (or (some (fn [entry]
              (some-> (get-in entry [:value :tools tool-name])
                      (assoc :source :built-in
                             :ext-path (:provenance-id entry))))
            (built-in-tool-entries reg))
      (some (fn [{:keys [extension-path tools]}]
              (some-> (get tools tool-name)
                      (assoc :extension-path extension-path)))
            (extension-tool-entries reg))))

(defn register-built-in-tool-in!
  "Register `tool` as a built-in tool owned by `provenance-id`.
   Built-in tools do not require a prior extension registration.
   `provenance-id` is a stable identifier (e.g. `\"built-in:workflow\"`) used
   to group and identify the owning built-in surface."
  [reg provenance-id tool]
  (let [tool* (->> tool
                   (ensure-valid-built-in-tool! provenance-id)
                   (normalize-built-in-tool provenance-id))
        existing-tools (or (get-in (registered-extension-entry reg provenance-id) [:value :tools]) {})
        tools (assoc existing-tools
                     (:name tool*)
                     tool*)]
    (register-root-entry! reg (built-in-tool-entry provenance-id tools))))

(defn all-built-in-tools-in
  "Return vector of all registered built-in tool maps, across all provenance ids."
  [reg]
  (vec (mapcat (fn [entry]
                 (map (fn [[_ tool]]
                        (assoc tool :source :built-in :ext-path (:provenance-id entry)))
                      (:tools (:value entry))))
               (built-in-tool-entries reg))))

(defn built-in-tool-names-in
  "Return set of all registered built-in tool names."
  [reg]
  (into #{}
        (mapcat (comp keys :tools :value))
        (built-in-tool-entries reg)))
