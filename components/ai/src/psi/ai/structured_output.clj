(ns psi.ai.structured-output
  "Structured-output capability normalization and strategy selection helpers."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def unsupported-structured-output-capability
  {:supported? false
   :strategies []
   :native-mechanism nil
   :defaulted? true
   :notes "No structured-output capability was declared for this model/transport."})

(def openai-chat-completions-native-capability
  {:supported? true
   :strategies [:provider-native :prompted-json]
   :native-mechanism :openai/chat-completions-json-schema-response-format})

(def anthropic-forced-tool-native-capability
  {:supported? true
   :strategies [:provider-native :prompted-json]
   :native-mechanism :anthropic/forced-tool-use})

(def anthropic-json-schema-output-native-capability
  {:supported? true
   :strategies [:provider-native :prompted-json]
   :native-mechanism :anthropic/json-schema-output})

(def openai-codex-api
  "Transport `:api` for the ChatGPT/Codex responses backend."
  :openai-codex-responses)

(def openai-codex-base-url
  "Transport `:base-url` for the ChatGPT/Codex responses backend."
  "https://chatgpt.com/backend-api")

(def openai-codex-native-capability
  {:supported? true
   :strategies [:provider-native :prompted-json]
   :native-mechanism :openai/responses-text-format-json-schema
   :notes "Verified only for the ChatGPT/Codex responses transport with streaming text.format JSON Schema."})

(def openai-codex-fallback-capability
  {:supported? true
   :strategies [:prompted-json]
   :native-mechanism nil
   :notes "Do not assume public OpenAI Responses API fields are supported."})

(defn normalize-structured-output-capability
  "Return the effective structured-output capability for a model description.

   Omitted capability data remains load-compatible but defaults to effective
   unsupported so prompted JSON fallback is explicit opt-in."
  [capability]
  (if (map? capability)
    (merge {:supported? false
            :strategies []
            :native-mechanism nil}
           capability)
    unsupported-structured-output-capability))

(defn effective-capability
  "Return the normalized effective structured-output capability for model."
  [model]
  (normalize-structured-output-capability
   (get-in model [:capabilities :structured-output])))

(defn with-structured-output-capability
  "Associate a concrete structured-output capability on model."
  [model capability]
  (assoc-in model [:capabilities :structured-output]
            (normalize-structured-output-capability capability)))

(defn with-unsupported-structured-output-capability
  "Replace any model structured-output capability with effective unsupported."
  [model]
  (with-structured-output-capability model unsupported-structured-output-capability))

(defn with-openai-codex-native-capability
  "Replace any model structured-output capability with ChatGPT/Codex native streaming support."
  [model]
  (with-structured-output-capability model openai-codex-native-capability))

(defn with-openai-codex-transport
  "Shape model onto the ChatGPT/Codex responses transport.

   Single owner of the \"how a model becomes codex\" rule: sets the codex
   transport (`:api` + `:base-url`) and attaches the codex native
   structured-output capability. The catalog's declarative `:openai-codex-responses`
   entries and any runtime override compose this one rule rather than restating
   the transport/capability literals independently."
  [model]
  (-> model
      (assoc :api openai-codex-api
             :base-url openai-codex-base-url)
      with-openai-codex-native-capability))

(defn with-openai-codex-fallback-capability
  "Replace any model structured-output capability with Codex-safe fallback-only support."
  [model]
  (with-structured-output-capability model openai-codex-fallback-capability))

(defn normalize-model
  "Normalize model capability data for strategy-selection consumers.

   This does not rewrite persisted user definitions; it only materializes the
   effective runtime/catalog model map."
  [model]
  (assoc-in model [:capabilities :structured-output]
            (effective-capability model)))

(defn strategy-supported?
  [capability strategy]
  (contains? (set (:strategies (normalize-structured-output-capability capability))) strategy))

(defn structured-output-request
  "Return a normalized structured-output request map from options, when present."
  [options]
  (when-let [request (:structured-output options)]
    (when (map? request)
      (merge {:strict? true
              :fallback-allowed? true}
             request))))

(defn structured-output-name
  "Return a provider-safe structured-output schema name."
  [request]
  (let [raw       (or (:name request)
                      (some-> (:schema-id request) name)
                      "structured_output")
        sanitized (-> (str raw)
                      (str/replace #"[^A-Za-z0-9_-]" "_")
                      (str/replace #"_+" "_")
                      (str/replace #"^[_-]+|[_-]+$" ""))]
    (if (seq sanitized) sanitized "structured_output")))

(defn select-strategy
  "Select the effective strategy for a structured-output request.

   The caller must pass the resolved runtime model. Missing requests return nil;
   missing JSON Schema or undeclared capability returns :unsupported metadata."
  [model request]
  (when request
    (let [capability (effective-capability model)
          base       (cond-> {:schema-id (:schema-id request)
                              :schema-version (:schema-version request)
                              :fallback-used? false}
                       (:native-mechanism capability)
                       (assoc :native-mechanism (:native-mechanism capability)))]
      (cond
        (not (map? (:json-schema request)))
        (assoc base
               :strategy :unsupported
               :reason :missing-json-schema)

        (not (:supported? capability))
        (assoc base
               :strategy :unsupported
               :reason (if (:defaulted? capability)
                         :structured-output-capability-omitted
                         :structured-output-unsupported))

        (and (strategy-supported? capability :provider-native)
             (:native-mechanism capability))
        (assoc base :strategy :provider-native)

        (and (:fallback-allowed? request)
             (strategy-supported? capability :prompted-json))
        (-> base
            (assoc :strategy :prompted-json)
            (assoc :native-mechanism nil)
            (assoc :fallback-used? true))

        :else
        (assoc base
               :strategy :unsupported
               :native-mechanism nil
               :reason :fallback-not-allowed)))))

(defn json-only-instruction
  "Return deterministic prompted-JSON fallback instructions for request."
  [request]
  (let [schema-text (json/generate-string (:json-schema request))]
    (str "\n\nStructured output required. Return exactly one JSON value matching "
         "the supplied JSON Schema. Do not wrap the JSON in Markdown fences, "
         "do not add prose, and do not emit extra top-level text.\n"
         "Name: " (structured-output-name request) "\n"
         "Schema ID: " (pr-str (:schema-id request)) "\n"
         "Schema version: " (pr-str (:schema-version request)) "\n"
         "JSON Schema: " schema-text "\n"
         "Local runtime validation remains authoritative.")))

(defn append-fallback-instructions-to-text
  "Append adapter-owned prompted-JSON instructions to a text value."
  [text request]
  (str (or text "") (json-only-instruction request)))

(defn parse-json-value
  "Parse text as a JSON value.

   Returns {:parsed? true :payload value} when parsing succeeds, including
   scalar, array, object, and nil JSON values. Returns nil when parsing fails
   or text is blank."
  [text]
  (when (seq text)
    (try
      {:parsed? true
       :payload (json/parse-string text true)}
      (catch Exception _
        nil))))

(defn parse-json-object
  "Parse text as a JSON object, returning nil when parsing fails or the value is not a map."
  [text]
  (when-let [{:keys [payload]} (parse-json-value text)]
    (when (map? payload) payload)))
