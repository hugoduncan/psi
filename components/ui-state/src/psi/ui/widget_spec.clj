(ns psi.ui.widget-spec
  "Declarative widget spec — vui-inspired node tree expressed as pure data.

   A WidgetSpec is a map with:
     :id          — string, unique within extension
     :placement   — :above-editor | :below-editor
     :query       — vector of EQL keywords; result map available to all nodes
     :spec        — root WidgetNode map
     :subscriptions — vector of EventSubscription maps

   A WidgetNode is a typed map:
     :type  — keyword (see node-types)
     :key   — optional string, stable identity for local state preservation

   Node types and their additional fields mirror vui's primitive vocabulary
   but are pure data — no functions, no lambdas.
   Interactions reference server-side ops by qualified symbol name.

   See spec/emacs-widget-spec.allium for the full behavioural specification."
  (:require [clojure.string :as str]))

;; ─────────────────────────────────────────────
;; Node type registry
;; ─────────────────────────────────────────────

(def node-types
  "All valid :type values for WidgetNode maps."
  #{:text :newline :hstack :vstack
    :heading :strong :muted :code
    :success :warning :error
    :button :collapsible :list})

(def container-types
  "Node types that carry :children."
  #{:hstack :vstack :collapsible})

;; ─────────────────────────────────────────────
;; Validation
;; ─────────────────────────────────────────────

(defn- valid-node-type? [node]
  (contains? node-types (:type node)))

(defn- valid-placement? [p]
  (contains? #{:above-editor :below-editor} p))

(defn valid-node?
  "Return true when node has a recognised :type and required fields."
  [node]
  (and (map? node)
       (valid-node-type? node)
       (case (:type node)
         :button      (and (string? (:label node))
                           (map? (:mutation node))
                           (qualified-symbol? (get-in node [:mutation :name])))
         :collapsible (string? (:title node))
         :list        (some? (:items-path node))
         true)))

(defn validate-node
  "Return nil when valid, or an error string describing the problem."
  [node]
  (cond
    (not (map? node))
    "node must be a map"

    (not (valid-node-type? node))
    (str "unknown node type: " (pr-str (:type node))
         "; valid types: " (str/join ", " (map name (sort node-types))))

    (= :button (:type node))
    (cond
      (not (string? (:label node)))
      "button node requires :label string"
      (not (map? (:mutation node)))
      "button node requires :mutation map"
      (not (qualified-symbol? (get-in node [:mutation :name])))
      "button :mutation requires :name qualified-symbol"
      :else nil)

    (= :collapsible (:type node))
    (when-not (string? (:title node))
      "collapsible node requires :title string")

    (= :list (:type node))
    (when-not (some? (:items-path node))
      "list node requires :items-path")

    :else nil))

(defn validate-spec
  "Return nil when valid, or a map of {:errors [...]} describing problems."
  [{:keys [id placement spec subscriptions]}]
  (let [errors
        (cond-> []
          (not (string? id))
          (conj "widget-spec :id must be a string")

          (or (nil? id) (str/blank? id))
          (conj "widget-spec :id must not be blank")

          (not (valid-placement? placement))
          (conj (str "widget-spec :placement must be :above-editor or :below-editor, got: "
                     (pr-str placement)))

          (nil? spec)
          (conj "widget-spec :spec must not be nil")

          (and (some? spec) (some? (validate-node spec)))
          (conj (str "widget-spec root node invalid: " (validate-node spec)))

          (and (some? subscriptions)
               (not (every? (fn [s] (string? (:event-name s))) subscriptions)))
          (conj "each subscription must have a string :event-name"))]
    (when (seq errors)
      {:errors errors})))

;; ─────────────────────────────────────────────
;; Node constructors
;; ─────────────────────────────────────────────

(defn text
  "Plain text node. :content-path overrides :content when set."
  [content & {:keys [key face content-path]}]
  (cond-> {:type :text :content content}
    key          (assoc :key key)
    face         (assoc :face face)
    content-path (assoc :content-path content-path)))

(defn newline-node
  "Newline node."
  ([]      {:type :newline})
  ([& {:keys [key]}] (cond-> {:type :newline} key (assoc :key key))))

(defn hstack
  "Horizontal stack. Children rendered left-to-right."
  [children & {:keys [key spacing] :or {spacing 1}}]
  (cond-> {:type :hstack :spacing spacing :children (vec children)}
    key (assoc :key key)))

(defn vstack
  "Vertical stack. Children rendered top-to-bottom."
  [children & {:keys [key indent spacing] :or {indent 0 spacing 0}}]
  (cond-> {:type :vstack :indent indent :spacing spacing :children (vec children)}
    key (assoc :key key)))

(defn heading
  "Heading node. Level 1–8."
  [content & {:keys [key level content-path] :or {level 1}}]
  (cond-> {:type :heading :content content :level level}
    key          (assoc :key key)
    content-path (assoc :content-path content-path)))

(defn- semantic-text-node [type content {:keys [key content-path]}]
  (cond-> {:type type :content content}
    key          (assoc :key key)
    content-path (assoc :content-path content-path)))

(defn strong   [content & {:as opts}] (semantic-text-node :strong  content opts))
(defn muted    [content & {:as opts}] (semantic-text-node :muted   content opts))
(defn code     [content & {:as opts}] (semantic-text-node :code    content opts))
(defn success  [content & {:as opts}] (semantic-text-node :success content opts))
(defn warning  [content & {:as opts}] (semantic-text-node :warning content opts))
(defn error    [content & {:as opts}] (semantic-text-node :error   content opts))

(defn button
  "Button node. :mutation is a MutationRef map.
   MutationRef: {:name qualified-symbol :params map :timeout-ms int?}"
  [label mutation & {:keys [key disabled timeout-ms]}]
  (cond-> {:type :button :label label :mutation mutation :disabled (boolean disabled)}
    key        (assoc :key key)
    timeout-ms (assoc :timeout-ms timeout-ms)))

(defn mutation-ref
  "Build a MutationRef map."
  [name & {:keys [params timeout-ms] :or {params {}}}]
  (cond-> {:name name :params params}
    timeout-ms (assoc :timeout-ms timeout-ms)))

(defn collapsible
  "Collapsible section. :key required for local state tracking."
  [title children & {:keys [key initially-expanded title-path]
                     :or   {initially-expanded false}}]
  (cond-> {:type             :collapsible
           :title            title
           :children         (vec children)
           :initially-expanded (boolean initially-expanded)}
    key        (assoc :key key)
    title-path (assoc :title-path title-path)))

(defn list-node
  "List node. Renders item-spec once per element at items-path in query result."
  [items-path item-spec & {:keys [key vertical indent]
                           :or   {vertical true indent 0}}]
  (cond-> {:type       :list
           :items-path items-path
           :item-spec  item-spec
           :vertical   vertical
           :indent     indent}
    key (assoc :key key)))

;; ─────────────────────────────────────────────
;; EventSubscription constructor
;; ─────────────────────────────────────────────

(defn event-subscription
  "Build an EventSubscription map.
   :local-state-map is a map of local-state-key → path-into-event-payload.
   nil :local-state-map means the event is a pure re-query signal."
  [event-name & {:keys [local-state-map]}]
  (cond-> {:event-name event-name}
    local-state-map (assoc :local-state-map local-state-map)))

;; ─────────────────────────────────────────────
;; WidgetSpec constructor
;; ─────────────────────────────────────────────

(defn widget-spec
  "Build a WidgetSpec map.
   :query         — vector of EQL forms (keywords, joins)
   :entity        — optional map merged into Pathom query entity (e.g. for seeding
                    :psi.extension/path + :psi.extension.workflow/id)
   :spec          — root WidgetNode
   :subscriptions — vector of EventSubscription maps"
  [id placement spec-node & {:keys [query entity subscriptions]
                             :or   {query [] subscriptions []}}]
  (cond-> {:id            id
           :placement     placement
           :query         (vec query)
           :spec          spec-node
           :subscriptions (vec subscriptions)}
    entity (assoc :entity entity)))

;; ─────────────────────────────────────────────
;; Node traversal
;; ─────────────────────────────────────────────

(defn all-nodes
  "Return a lazy seq of all nodes in the tree (depth-first)."
  [node]
  (lazy-seq
   (cons node
         (mapcat all-nodes
                 (concat (:children node)
                         (when (= :list (:type node))
                           [(:item-spec node)]))))))

(defn initial-collapsed-keys
  "Return the set of node keys that should start collapsed."
  [root-node]
  (->> (all-nodes root-node)
       (filter #(= :collapsible (:type %)))
       (filter #(and (some? (:key %))
                     (not (:initially-expanded %))))
       (map :key)
       (into #{})))


