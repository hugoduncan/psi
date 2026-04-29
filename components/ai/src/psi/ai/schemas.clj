(ns psi.ai.schemas
  "Malli schemas for AI entities following allium spec"
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt])
  (:import [java.time Instant]
           [java.util UUID]))

;; Value types

(def Provider
  "Provider identifier. Built-in providers are :anthropic and :openai;
   custom providers use arbitrary keywords (e.g. :local, :ollama)."
  keyword?)

(def Api
  [:enum :anthropic-messages :openai-completions :openai-codex-responses])

(def MessageRole
  [:enum :user :assistant :tool-result])

(def StopReason
  [:enum :stop :length :tool-use :error :aborted])

(def StreamEventType
  [:enum :start :text-start :text-delta :text-end
   :thinking-start :thinking-delta :thinking-signature-delta :thinking-end
   :toolcall-start :toolcall-delta :toolcall-end
   :done :error])

(def ContentBlockKind
  [:enum :text :thinking :image :tool-call])

(def MessageContentKind
  [:enum :text :structured])

(def ConversationStatus
  [:enum :active :completed :error])

(def StreamSessionStatus
  [:enum :streaming :completed :failed])

(def CacheRetention
  [:enum :none :short :long])

;; Entity schemas

(def CostBreakdown
  [:map {:closed true}
   [:input number?]
   [:output number?]
   [:cache-read number?]
   [:cache-write number?]
   [:total number?]])

(def CacheControl
  [:map {:closed true}
   [:type [:= :ephemeral]]])

(def Usage
  [:map {:closed true}
   [:input-tokens {:optional true} nat-int?]
   [:output-tokens {:optional true} nat-int?]
   [:cache-read-tokens {:optional true} nat-int?]
   [:cache-write-tokens {:optional true} nat-int?]
   [:total-tokens {:optional true} nat-int?]
   [:cost {:optional true} CostBreakdown]])

(def TextContent
  [:map {:closed true}
   [:kind [:= :text]]
   [:text string?]
   [:cache-control {:optional true} CacheControl]])

(def ThinkingContentBlock
  [:map {:closed true}
   [:kind [:= :thinking]]
   [:text string?]
   [:provider {:optional true} [:or keyword? string?]]
   [:signature {:optional true} string?]])

(def StructuredTextBlock
  [:map {:closed true}
   [:kind [:= :text]]
   [:text string?]
   [:cache-control {:optional true} CacheControl]])

(def ToolCallContentBlock
  [:map {:closed true}
   [:kind [:= :tool-call]]
   [:id {:optional true} string?]
   [:name string?]
   [:input {:optional true} map?]])

(def ImageContentBlock
  [:map {:closed true}
   [:kind [:= :image]]
   [:mime-type string?]
   [:data string?]])

(def ContentBlock
  [:multi {:dispatch :kind}
   [:text StructuredTextBlock]
   [:thinking ThinkingContentBlock]
   [:tool-call ToolCallContentBlock]
   [:image ImageContentBlock]])

(def StructuredContent
  [:map {:closed true}
   [:kind [:= :structured]]
   [:blocks [:vector ContentBlock]]])

(def MessageContent
  [:multi {:dispatch :kind}
   [:text TextContent]
   [:structured StructuredContent]])

(def Tool
  [:map {:closed true}
   [:name string?]
   [:description string?]
   [:parameters map?]
   [:cache-control {:optional true} CacheControl]])  ;; JSON schema

(def Locality
  [:enum :local :cloud])

(def LatencyTier
  [:enum :low :medium :high])

(def CostTier
  [:enum :zero :low :medium :high])

(def Model
  [:map {:closed true}
   [:id string?]
   [:name string?]
   [:provider Provider]
   [:api Api]
   [:base-url string?]
   [:supports-reasoning boolean?]
   [:adaptive-thinking {:optional true} boolean?]
   [:supports-images boolean?]
   [:supports-text boolean?]
   [:context-window pos-int?]
   [:max-tokens pos-int?]
   [:input-cost number?]
   [:output-cost number?]
   [:cache-read-cost number?]
   [:cache-write-cost number?]
   [:locality {:optional true} Locality]
   [:latency-tier {:optional true} LatencyTier]
   [:cost-tier {:optional true} CostTier]])

(def Message
  [:map {:closed true}
   [:id string?]
   [:role MessageRole]
   [:content MessageContent]
   [:timestamp inst?]
   [:provider {:optional true} Provider]
   [:model-id {:optional true} string?]
   [:api {:optional true} Api]
   [:usage {:optional true} Usage]
   [:stop-reason {:optional true} StopReason]
   [:error-message {:optional true} string?]
   [:tool-call-id {:optional true} string?]  ;; For tool-result messages
   [:tool-name {:optional true} string?]
   [:is-error {:optional true} boolean?]])

(def Conversation
  [:map {:closed true}
   [:id string?]
   [:system-prompt {:optional true} [:maybe string?]]
   [:system-prompt-blocks {:optional true} [:vector TextContent]]
   [:status ConversationStatus]
   [:created-at inst?]
   [:updated-at inst?]
   [:messages [:vector Message]]
   [:tools [:set Tool]]
   [:error-message {:optional true} string?]])

(def StreamSession
  [:map {:closed true}
   [:id string?]
   [:conversation-id string?]
   [:model Model]
   [:status StreamSessionStatus]
   [:started-at inst?]
   [:completed-at {:optional true} [:maybe inst?]]
   [:temperature {:optional true} [:maybe number?]]
   [:max-tokens {:optional true} [:maybe pos-int?]]
   [:cache-retention CacheRetention]
   [:error-message {:optional true} string?]])

;; Stream options

(def StreamOptions
  [:map {:closed false}  ;; Allow additional provider-specific options
   [:temperature {:optional true} [:maybe [:double {:min 0.0 :max 2.0}]]]
   [:max-tokens {:optional true} [:maybe pos-int?]]
   [:api-key {:optional true} [:maybe string?]]
   [:cache-retention {:optional true} CacheRetention]
   [:session-id {:optional true} [:maybe string?]]
   [:headers {:optional true} [:map-of string? string?]]
   [:metadata {:optional true} [:map-of string? any?]]])

;; Event schemas

(def StreamEventData
  [:map {:closed false}  ;; Allow additional fields per event type
   [:type StreamEventType]
   [:timestamp inst?]
   [:partial {:optional true} Message]
   [:content-index {:optional true} nat-int?]
   [:delta {:optional true} string?]
   [:signature {:optional true} string?]
   [:thinking {:optional true} string?]
   [:reason {:optional true} StopReason]
   [:message {:optional true} Message]
   [:error-message {:optional true} string?]])

;; Validation helpers

(defn valid?
  "Check if data matches schema"
  [schema data]
  (m/validate schema data))

(defn explain
  "Get validation errors"
  [schema data]
  (m/explain schema data))

(def ^:private max-validation-summary-chars 600)
(def ^:private max-validation-value-chars 240)

(defn- truncate-with-ellipsis
  [s max-chars]
  (if (and (string? s)
           (> (count s) max-chars))
    (str (subs s 0 max-chars) "…")
    s))

(defn- validation-summary
  [errors]
  (try
    (-> errors
        me/humanize
        pr-str
        (truncate-with-ellipsis max-validation-summary-chars))
    (catch Throwable _
      (-> errors
          pr-str
          (truncate-with-ellipsis max-validation-summary-chars)))))

(defn- legacy-canonical-blocks?
  [v]
  (and (sequential? v)
       (seq v)
       (every? map? v)
       (some #(contains? % :type) v)
       (not-any? #(contains? % :kind) v)))

(defn- validation-error-type-label
  [t]
  (case t
    :malli.core/invalid-dispatch-value "invalid dispatch value"
    :malli.core/missing-key "missing required key"
    :malli.core/extra-key "unexpected key"
    (if (keyword? t)
      (name t)
      (str t))))

(defn- first-error-detail
  [errors]
  (when-let [err (first (:errors errors))]
    (let [path* (if (seq (:path err))
                  (pr-str (:path err))
                  "<root>")
          type* (validation-error-type-label (:type err))
          value* (-> (:value err)
                     pr-str
                     (truncate-with-ellipsis max-validation-value-chars))
          hint (when (and (= :malli.core/invalid-dispatch-value (:type err))
                          (legacy-canonical-blocks? (:value err)))
                 " hint: value looks like canonical blocks {:type ...}; expected a :kind-based content map (e.g. {:kind :text ...} or {:kind :structured :blocks [...]})")]
      (str "at " path* " (" type* "), value=" value* hint))))

(defn validate!
  "Validate data, throw on failure"
  [schema data]
  (if (m/validate schema data)
    data
    (let [errors  (m/explain schema data)
          summary (validation-summary errors)
          detail  (first-error-detail errors)]
      (throw (ex-info (if detail
                        (str "Validation failed " detail ". Summary: " summary)
                        (str "Validation failed: " summary))
                      {:schema schema
                       :data data
                       :errors errors
                       :validation-summary summary
                       :validation-detail detail})))))

;; Transformers

(def string->uuid-transformer
  {:name :string->uuid
   :decoders {:string (fn [_ _]
                        (fn [x]
                          (if (string? x)
                            (UUID/fromString x)
                            x)))}})

(def instant-transformer
  {:name :instant
   :decoders {inst? (fn [_ _]
                      (fn [x]
                        (cond
                          (inst? x) x
                          (string? x) (Instant/parse x)
                          (number? x) (Instant/ofEpochMilli x)
                          :else x)))}})

(def decode-transformer
  (mt/transformer
   mt/string-transformer
   string->uuid-transformer
   instant-transformer))

(defn decode
  "Decode data using transformers"
  [schema data]
  (m/decode schema data decode-transformer))