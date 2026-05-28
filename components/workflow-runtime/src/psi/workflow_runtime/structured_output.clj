(ns psi.workflow-runtime.structured-output
  "Canonical workflow structured-output parsing, coercion, and validation."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
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

(defn- parse-json-value
  "Parse raw-output as JSON. Accepts any valid JSON value (object, string, number,
   array, boolean). Schema-level constraints are enforced by malli validation
   downstream, not here.

   Fallback: if raw-output is not valid JSON (e.g. the AI returned plain text
   `DONE` rather than the JSON string `\"DONE\"`), treat the trimmed raw output
   as a plain string. This handles the common case where a judge model outputs
   an unquoted enum word. Schema validation downstream rejects non-conforming values."
  [raw-output]
  (try
    {:ok? true :parsed-value (json/parse-string raw-output)}
    (catch Exception _
      {:ok? true :parsed-value (str/trim raw-output)})))

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

(defn structured-output-request
  "Build provider-neutral AI structured-output request options from a workflow
   structured output spec.

   Returns {:ok? true :opts {:structured-output ...}} or
   {:ok? false :reason :missing-json-schema :details ...}. Capability-based
   unsupported-native detection remains below this boundary after model/transport
   resolution."
  [output-key output-spec]
  (let [fallback (or (:fallback output-spec) :prompted-json)
        require-native? (true? (:require-provider-native? output-spec))]
    (if-not (:json-schema output-spec)
      {:ok? false
       :reason :missing-json-schema
       :message "Workflow structured output requires an explicit JSON Schema"
       :details (cond-> {:output-key output-key}
                  (:schema-id output-spec) (assoc :schema-id (:schema-id output-spec))
                  (:schema-version output-spec) (assoc :schema-version (:schema-version output-spec)))}
      {:ok? true
       :opts {:structured-output
              {:schema-id (:schema-id output-spec)
               :schema-version (:schema-version output-spec)
               :json-schema (:json-schema output-spec)
               :strategy-preference (or (:strategy-preference output-spec) :provider-native)
               :fallback-allowed? (and (not require-native?) (= :prompted-json fallback))
               :strict? true}}})))

(defn- envelope-base
  [output-spec ai-structured-output]
  (let [strategy (or (:strategy ai-structured-output)
                     (:strategy output-spec)
                     :prompted-json)]
    (cond-> {:mode :structured
             :schema-id (:schema-id output-spec)
             :schema-version (:schema-version output-spec)
             :strategy strategy}
      (:native-mechanism ai-structured-output)
      (assoc :native-mechanism (:native-mechanism ai-structured-output))

      (:source ai-structured-output)
      (assoc :source (:source ai-structured-output))

      (contains? ai-structured-output :fallback-used?)
      (assoc :fallback-used? (:fallback-used? ai-structured-output))

      (contains? ai-structured-output :payload)
      (assoc :payload (:payload ai-structured-output))

      (contains? ai-structured-output :raw-payload)
      (assoc :raw-payload (:raw-payload ai-structured-output))

      (:provider-metadata ai-structured-output)
      (assoc :provider-metadata (:provider-metadata ai-structured-output)))))

(defn- validation-input
  [raw-output ai-structured-output]
  (if (contains? ai-structured-output :payload)
    {:ok? true :parsed-value (:payload ai-structured-output)}
    (parse-json-value raw-output)))

(defn structured-output-envelope
  ([output-spec raw-output]
   (structured-output-envelope output-spec raw-output nil))
  ([output-spec raw-output ai-structured-output]
   (let [ai-structured-output (or ai-structured-output {})
         schema (canonical-schema output-spec)
         base (envelope-base output-spec ai-structured-output)]
     (if-not schema
       (assoc base
              :status :invalid
              :errors [{:type :missing-schema
                        :message "Structured output declaration must include a schema or known schema-id/schema-version"}])
       (let [{:keys [ok? parsed-value errors]} (validation-input raw-output ai-structured-output)]
         ;; ok? is always true for the raw-output path: parse-json-value uses a
         ;; plain-text fallback and never returns {:ok? false}.  The ok? false
         ;; branch below is only reachable via the :payload-absent path in
         ;; ai-structured-output (i.e. when validation-input delegates to
         ;; parse-json-value and a future caller explicitly passes {:ok? false}).
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
                      :errors (validation-errors schema coerced))))))))))

(defn output-result
  ([output-spec raw-output]
   (output-result output-spec raw-output nil))
  ([output-spec raw-output ai-structured-output]
   {:raw-output raw-output
    :structured-output (structured-output-envelope output-spec raw-output ai-structured-output)}))

(defn missing-ai-structured-output-result
  "Build the stable invalid envelope used when workflow execution made a
  structured-output request but the bounded turn result omitted the authoritative
  top-level :structured-output metadata seam.  The caller must not fall back to
  parsing assistant prose in this case because the AI strategy/payload metadata
  would be synthetic."
  [output-spec raw-output]
  {:raw-output raw-output
   :structured-output (assoc (envelope-base output-spec {})
                             :status :invalid
                             :errors [{:type :missing-structured-output
                                       :message "Structured workflow generation did not return structured-output metadata"}])})

(def valid-output-result? structured-output-contract/valid-output-result?)
