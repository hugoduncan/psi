(ns psi.agent-session.tool-defs
  "Canonical tool definition helpers and boundary projections.

   Canonical shape is owned by agent-session and keeps `:parameters` as data.
   Boundary projections adapt that shape for agent-core and provider use."
  (:require
   [cheshire.core :as json]
   [clojure.edn :as edn]))

(defn- empty-object-schema []
  {:type "object"
   :properties {}})

(defn- parse-parameters [parameters]
  (cond
    (map? parameters)
    (merge (empty-object-schema) parameters)

    (string? parameters)
    (or (try
          (let [v (json/parse-string parameters true)]
            (when (map? v)
              (merge (empty-object-schema) v)))
          (catch Exception _
            nil))
        (try
          (let [v (edn/read-string parameters)]
            (when (map? v)
              (merge (empty-object-schema) v)))
          (catch Exception _
            nil))
        (empty-object-schema))

    (nil? parameters) (empty-object-schema)
    :else (empty-object-schema)))

(defn normalize-tool-def
  "Normalize a builtin/extension tool map into the canonical tool-def shape.
   Preserves executable runtime fns for in-process tool execution while
   boundary projections decide what crosses into agent-core/provider payloads."
  [tool]
  (when-let [name (some-> (:name tool) str not-empty)]
    (cond-> {:name               name
             :label              (or (some-> (:label tool) str not-empty)
                                     name)
             :description        (or (some-> (:description tool) str)
                                     "")
             :parameters         (parse-parameters (:parameters tool))
             :lambda-description (some-> (:lambda-description tool) str)
             :source             (:source tool)
             :ext-path           (:ext-path tool)
             :enabled?           (if (contains? tool :enabled?)
                                   (boolean (:enabled? tool))
                                   true)}
      (contains? tool :execute)
      (assoc :execute (:execute tool)))))

(defn normalize-tool-defs
  [tools]
  (mapv normalize-tool-def (filter map? (or tools []))))

(defn agent-core-tool
  "Project a canonical or richer tool map into the agent-core runtime shape.
   During migration this preserves structured `:parameters` data."
  [tool]
  (when-let [name (some-> (:name tool) str not-empty)]
    {:name        name
     :label       (or (some-> (:label tool) str not-empty)
                      name)
     :description (or (some-> (:description tool) str)
                      "")
     :parameters  (parse-parameters (:parameters tool))}))

(defn agent-core-tools
  [tools]
  (mapv agent-core-tool (filter map? (or tools []))))

(defn provider-tool
  "Project a canonical or richer tool map into provider-facing conversation shape."
  [tool]
  (when-let [name (some-> (:name tool) str not-empty)]
    (cond-> {:name        name
             :description (or (some-> (:description tool) str) "")
             :parameters  (parse-parameters (:parameters tool))}
      (:cache-control tool)
      (assoc :cache-control (:cache-control tool)))))

(defn provider-tools
  [tools]
  (mapv provider-tool (filter map? (or tools []))))
