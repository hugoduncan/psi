(ns psi.ai.structured-output
  "Structured-output capability normalization and strategy selection helpers.")

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
