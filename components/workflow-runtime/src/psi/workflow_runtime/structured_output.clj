(ns psi.workflow-runtime.structured-output
  "Canonical workflow structured-output parsing, coercion, and validation."
  (:require
   [cheshire.core :as json]
   [malli.core :as m]
   [psi.workflow-runtime.structured-output-schemas :as structured-output-schemas]
   [psi.workflow-step-materialization.structured-output :as structured-output-contract]))

(def structured-output-sources structured-output-contract/structured-output-sources)
(def structured-output-spec? structured-output-contract/structured-output-spec?)
(def structured-output-entries structured-output-contract/structured-output-entries)
(def single-structured-output-entry structured-output-contract/single-structured-output-entry)

(defn canonical-schema
  [{:keys [schema-id schema-version schema]}]
  (or (structured-output-schemas/schema-for schema-id schema-version)
      schema))

(defn- parse-json-object
  [raw-output]
  (try
    (let [parsed (json/parse-string raw-output)]
      (if (map? parsed)
        {:ok? true :parsed-value parsed}
        {:ok? false
         :parsed-value parsed
         :errors [{:type :parse-error
                   :message "Structured output must be a single JSON object"}]}))
    (catch Exception e
      {:ok? false
       :errors [{:type :parse-error
                 :message (ex-message e)}]})))

(defn- map-entry-schema
  [schema key-name]
  (some (fn [entry]
          (when (and (vector? entry)
                     (= key-name (first entry)))
            (if (map? (second entry))
              (nth entry 2 nil)
              (second entry))))
        (rest schema)))

(declare coerce-value)

(defn- coerce-map
  [schema value]
  (if-not (map? value)
    value
    (into {}
          (map (fn [[k v]]
                 (let [keyword-key (if (string? k) (keyword k) k)
                       value-schema (map-entry-schema schema keyword-key)]
                   [keyword-key (coerce-value value-schema v)])))
          value)))

(defn- coerce-enum
  [schema value]
  (if-not (string? value)
    value
    (let [keyword-value (keyword value)
          enum-values (set (rest schema))]
      (if (contains? enum-values keyword-value)
        keyword-value
        value))))

(defn- coerce-vector
  [schema value]
  (if-not (vector? value)
    value
    (let [item-schema (second schema)]
      (mapv #(coerce-value item-schema %) value))))

(defn coerce-value
  [schema value]
  (if-not (vector? schema)
    value
    (case (first schema)
      :map (coerce-map schema value)
      :enum (coerce-enum schema value)
      :vector (coerce-vector schema value)
      value)))

(defn- validation-errors
  [schema value]
  (mapv (fn [{:keys [in path value type]}]
          {:type (or type :validation-error)
           :path (or in path [])
           :value value})
        (:errors (m/explain schema value))))

(defn structured-output-envelope
  [output-spec raw-output]
  (let [strategy (or (:strategy output-spec) :prompted-json)
        schema (canonical-schema output-spec)
        base {:mode :structured
              :schema-id (:schema-id output-spec)
              :schema-version (:schema-version output-spec)
              :strategy strategy}]
    (if-not schema
      (assoc base
             :status :invalid
             :errors [{:type :missing-schema
                       :message "Structured output declaration must include a schema or known schema-id/schema-version"}])
      (let [{:keys [ok? parsed-value errors]} (parse-json-object raw-output)]
        (if-not ok?
          (cond-> (assoc base
                         :status :invalid
                         :errors errors)
            (some? parsed-value) (assoc :parsed-value parsed-value))
          (let [coerced (coerce-value schema parsed-value)]
            (if (m/validate schema coerced)
              (assoc base
                     :status :valid
                     :value coerced)
              (assoc base
                     :status :invalid
                     :parsed-value parsed-value
                     :errors (validation-errors schema coerced)))))))))

(defn output-result
  [output-spec raw-output]
  {:raw-output raw-output
   :structured-output (structured-output-envelope output-spec raw-output)})

(def valid-output-result? structured-output-contract/valid-output-result?)
