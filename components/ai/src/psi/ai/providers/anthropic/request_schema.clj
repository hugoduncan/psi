(ns psi.ai.providers.anthropic.request-schema
  (:require [malli.core :as m]
            [malli.error :as me]))

(def ^:private anthropic-cache-control-schema
  [:map {:closed true}
   [:type [:= "ephemeral"]]])

(def ^:private anthropic-text-block-schema
  [:map {:closed true}
   [:type [:= "text"]]
   [:text :string]
   [:cache_control {:optional true} anthropic-cache-control-schema]])

(def ^:private anthropic-thinking-block-schema
  [:map {:closed true}
   [:type [:= "thinking"]]
   [:thinking :string]
   [:signature {:optional true} :string]])

(def ^:private anthropic-tool-use-block-schema
  [:map {:closed true}
   [:type [:= "tool_use"]]
   [:id [:re "^[a-zA-Z0-9_-]{1,128}$"]]
   [:name [:re "^[a-zA-Z0-9_-]{1,128}$"]]
   [:input map?]
   [:cache_control {:optional true} anthropic-cache-control-schema]])

(def ^:private anthropic-tool-result-block-schema
  [:map {:closed true}
   [:type [:= "tool_result"]]
   [:tool_use_id [:re "^[a-zA-Z0-9_-]{1,128}$"]]
   [:content :string]
   [:is_error {:optional true} boolean?]])

(def ^:private anthropic-user-content-block-schema
  [:or
   anthropic-text-block-schema
   anthropic-tool-result-block-schema])

(def ^:private anthropic-assistant-content-block-schema
  [:or
   anthropic-text-block-schema
   anthropic-thinking-block-schema
   anthropic-tool-use-block-schema])

(def ^:private anthropic-user-message-schema
  [:map {:closed true}
   [:role [:= "user"]]
   [:content [:sequential anthropic-user-content-block-schema]]])

(def ^:private anthropic-assistant-message-schema
  [:map {:closed true}
   [:role [:= "assistant"]]
   [:content [:sequential anthropic-assistant-content-block-schema]]])

(def ^:private anthropic-message-schema
  [:or
   anthropic-user-message-schema
   anthropic-assistant-message-schema])

(def ^:private anthropic-tool-schema
  [:map {:closed true}
   [:name [:re "^[a-zA-Z0-9_-]{1,128}$"]]
   [:description :string]
   [:input_schema map?]
   [:cache_control {:optional true} anthropic-cache-control-schema]])

(def ^:private anthropic-system-block-schema
  [:map {:closed true}
   [:type [:= "text"]]
   [:text :string]
   [:cache_control {:optional true} anthropic-cache-control-schema]])

(def ^:private anthropic-extended-thinking-schema
  "Extended thinking — used by Opus 4.6 and earlier reasoning models."
  [:map {:closed true}
   [:type [:= "enabled"]]
   [:budget_tokens pos-int?]])

(def ^:private anthropic-adaptive-thinking-schema
  "Adaptive thinking — used by Opus 4.7+. No budget_tokens; effort is in output_config."
  [:map {:closed true}
   [:type [:= "adaptive"]]
   [:display {:optional true} [:enum "summarized" "omitted"]]])

(def ^:private anthropic-thinking-schema
  [:or
   anthropic-extended-thinking-schema
   anthropic-adaptive-thinking-schema])

(def ^:private anthropic-output-config-schema
  "output_config — used with adaptive thinking to specify effort level."
  [:map {:closed true}
   [:effort [:enum "low" "medium" "high"]]
   [:task_budget {:optional true} [:map
                                   [:type [:= "tokens"]]
                                   [:total pos-int?]]]])

(def ^:private anthropic-request-body-schema
  [:map {:closed true}
   [:model :string]
   [:max_tokens pos-int?]
   [:messages [:sequential anthropic-message-schema]]
   [:stream [:= true]]
   [:system {:optional true} [:or :string [:sequential anthropic-system-block-schema]]]
   [:temperature {:optional true} number?]
   [:thinking {:optional true} anthropic-thinking-schema]
   [:output_config {:optional true} anthropic-output-config-schema]
   [:tools {:optional true} [:sequential anthropic-tool-schema]]])

(defn- request-shape-error-message
  [body explain]
  (let [err       (first (:errors explain))
        err-path  (vec (:path err))
        bad-value (when (seq err-path)
                    (get-in body err-path))
        snippet   (binding [*print-length* 8
                            *print-level* 4]
                    (pr-str bad-value))
        summary   (binding [*print-length* 20
                            *print-level* 6]
                    (pr-str (me/humanize explain)))]
    (str "Anthropic request shape invalid"
         (when (seq err-path)
           (str " at " err-path))
         (when-let [et (:type err)]
           (str " (" et ")"))
         (when (seq err-path)
           (str ", value=" snippet))
         ". Summary: " summary)))

(defn validate-request-body!
  [body]
  (when-not (m/validate anthropic-request-body-schema body)
    (let [explain (m/explain anthropic-request-body-schema body)]
      (throw (ex-info (request-shape-error-message body explain)
                      {:error-code "provider/anthropic-invalid-request-shape"
                       :provider :anthropic
                       :schema-humanized (me/humanize explain)
                       :schema-explain explain}))))
  body)
