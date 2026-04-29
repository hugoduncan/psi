(ns psi.ai.models
  "Model definitions and capabilities")

;; Model definitions following allium spec

(def anthropic-models
  {:claude-3-5-sonnet
   {:id "claude-3-5-sonnet-20241022"
    :name "Claude 3.5 Sonnet"
    :provider :anthropic
    :api :anthropic-messages
    :base-url "https://api.anthropic.com"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 200000
    :max-tokens 8192
    :input-cost 3.0
    :output-cost 15.0
    :cache-read-cost 0.3
    :cache-write-cost 3.75}

   :claude-3-5-haiku
   {:id "claude-3-5-haiku-20241022"
    :name "Claude 3.5 Haiku"
    :provider :anthropic
    :api :anthropic-messages
    :base-url "https://api.anthropic.com"
    :supports-reasoning false
    :supports-images true
    :supports-text true
    :context-window 200000
    :max-tokens 8192
    :input-cost 1.0
    :output-cost 5.0
    :cache-read-cost 0.1
    :cache-write-cost 1.25}

   :sonnet-4
   {:id "claude-sonnet-4-20250514"
    :name "Claude Sonnet 4"
    :provider :anthropic
    :api :anthropic-messages
    :base-url "https://api.anthropic.com"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 200000
    :max-tokens 16384
    :input-cost 3.0
    :output-cost 15.0
    :cache-read-cost 0.3
    :cache-write-cost 3.75}

   :opus-4
   {:id "claude-opus-4-20250514"
    :name "Claude Opus 4"
    :provider :anthropic
    :api :anthropic-messages
    :base-url "https://api.anthropic.com"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 200000
    :max-tokens 32768
    :input-cost 15.0
    :output-cost 75.0
    :cache-read-cost 1.5
    :cache-write-cost 18.75}

   :sonnet-4.5
   {:id "claude-sonnet-4-5"
    :name "Claude Sonnet 4.5"
    :provider :anthropic
    :api :anthropic-messages
    :base-url "https://api.anthropic.com"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 200000
    :max-tokens 16384
    :input-cost 3.0
    :output-cost 15.0
    :cache-read-cost 0.3
    :cache-write-cost 3.75}

   :opus-4.5
   {:id "claude-opus-4-5"
    :name "Claude Opus 4.5"
    :provider :anthropic
    :api :anthropic-messages
    :base-url "https://api.anthropic.com"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 200000
    :max-tokens 32768
    :input-cost 15.0
    :output-cost 75.0
    :cache-read-cost 1.5
    :cache-write-cost 18.75}

   :sonnet-4.6
   {:id "claude-sonnet-4-6"
    :name "Claude Sonnet 4.6"
    :provider :anthropic
    :api :anthropic-messages
    :base-url "https://api.anthropic.com"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 200000
    :max-tokens 16384
    :input-cost 3.0
    :output-cost 15.0
    :cache-read-cost 0.3
    :cache-write-cost 3.75}

   :opus-4.6
   {:id "claude-opus-4-6"
    :name "Claude Opus 4.6"
    :provider :anthropic
    :api :anthropic-messages
    :base-url "https://api.anthropic.com"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 200000
    :max-tokens 32768
    :input-cost 15.0
    :output-cost 75.0
    :cache-read-cost 1.5
    :cache-write-cost 18.75}

   :haiku-4.5
   {:id "claude-haiku-4-5"
    :name "Claude Haiku 4.5"
    :provider :anthropic
    :api :anthropic-messages
    :base-url "https://api.anthropic.com"
    :supports-reasoning false
    :supports-images true
    :supports-text true
    :context-window 200000
    :max-tokens 8192
    :input-cost 1.0
    :output-cost 5.0
    :cache-read-cost 0.1
    :cache-write-cost 1.25}

   :opus-4.7
   {:id "claude-opus-4-7"
    :name "Claude Opus 4.7"
    :provider :anthropic
    :api :anthropic-messages
    :base-url "https://api.anthropic.com"
    :supports-reasoning true
    ;; Opus 4.7 uses adaptive thinking — a different API protocol from
    ;; extended thinking (budget_tokens). See providers/anthropic.clj.
    :adaptive-thinking true
    :supports-images true
    :supports-text true
    :context-window 1000000
    :max-tokens 128000
    :input-cost 5.0
    :output-cost 25.0
    :cache-read-cost 0.5
    :cache-write-cost 6.25}})

(def openai-models
  {:gpt-4o
   {:id "gpt-4o"
    :name "GPT-4o"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning false
    :supports-images true
    :supports-text true
    :context-window 128000
    :max-tokens 16384
    :input-cost 2.5
    :output-cost 10.0
    :cache-read-cost 1.25
    :cache-write-cost 0.0}

   :o1-preview
   {:id "o1-preview"
    :name "GPT-o1 Preview"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning true
    :supports-images false
    :supports-text true
    :context-window 128000
    :max-tokens 32768
    :input-cost 15.0
    :output-cost 60.0
    :cache-read-cost 7.5
    :cache-write-cost 0.0}

   :codex-mini-latest
   {:id "codex-mini-latest"
    :name "Codex Mini"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning true
    :supports-images false
    :supports-text true
    :context-window 200000
    :max-tokens 100000
    :input-cost 1.5
    :output-cost 6.0
    :cache-read-cost 0.375
    :cache-write-cost 0.0}

   :gpt-5
   {:id "gpt-5"
    :name "GPT-5"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 400000
    :max-tokens 128000
    :input-cost 1.25
    :output-cost 10.0
    :cache-read-cost 0.125
    :cache-write-cost 0.0}

   :gpt-5-chat-latest
   {:id "gpt-5-chat-latest"
    :name "GPT-5 Chat Latest"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning false
    :supports-images true
    :supports-text true
    :context-window 128000
    :max-tokens 16384
    :input-cost 1.25
    :output-cost 10.0
    :cache-read-cost 0.125
    :cache-write-cost 0.0}

   :gpt-5-codex
   {:id "gpt-5-codex"
    :name "GPT-5 Codex"
    :provider :openai
    :api :openai-codex-responses
    :base-url "https://chatgpt.com/backend-api"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 272000
    :max-tokens 128000
    :input-cost 1.25
    :output-cost 10.0
    :cache-read-cost 0.125
    :cache-write-cost 0.0}

   :gpt-5-mini
   {:id "gpt-5-mini"
    :name "GPT-5 Mini"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 400000
    :max-tokens 128000
    :input-cost 0.25
    :output-cost 2.0
    :cache-read-cost 0.025
    :cache-write-cost 0.0}

   :gpt-5-nano
   {:id "gpt-5-nano"
    :name "GPT-5 Nano"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 400000
    :max-tokens 128000
    :input-cost 0.05
    :output-cost 0.4
    :cache-read-cost 0.005
    :cache-write-cost 0.0}

   :gpt-5-pro
   {:id "gpt-5-pro"
    :name "GPT-5 Pro"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 400000
    :max-tokens 272000
    :input-cost 15.0
    :output-cost 120.0
    :cache-read-cost 0.0
    :cache-write-cost 0.0}

   :gpt-5.1
   {:id "gpt-5.1"
    :name "GPT-5.1"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 400000
    :max-tokens 128000
    :input-cost 1.25
    :output-cost 10.0
    :cache-read-cost 0.13
    :cache-write-cost 0.0}

   :gpt-5.1-chat-latest
   {:id "gpt-5.1-chat-latest"
    :name "GPT-5.1 Chat"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 128000
    :max-tokens 16384
    :input-cost 1.25
    :output-cost 10.0
    :cache-read-cost 0.125
    :cache-write-cost 0.0}

   :gpt-5.1-codex
   {:id "gpt-5.1-codex"
    :name "GPT-5.1 Codex"
    :provider :openai
    :api :openai-codex-responses
    :base-url "https://chatgpt.com/backend-api"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 272000
    :max-tokens 128000
    :input-cost 1.25
    :output-cost 10.0
    :cache-read-cost 0.125
    :cache-write-cost 0.0}

   :gpt-5.1-codex-max
   {:id "gpt-5.1-codex-max"
    :name "GPT-5.1 Codex Max"
    :provider :openai
    :api :openai-codex-responses
    :base-url "https://chatgpt.com/backend-api"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 272000
    :max-tokens 128000
    :input-cost 1.25
    :output-cost 10.0
    :cache-read-cost 0.125
    :cache-write-cost 0.0}

   :gpt-5.1-codex-mini
   {:id "gpt-5.1-codex-mini"
    :name "GPT-5.1 Codex Mini"
    :provider :openai
    :api :openai-codex-responses
    :base-url "https://chatgpt.com/backend-api"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 272000
    :max-tokens 128000
    :input-cost 0.25
    :output-cost 2.0
    :cache-read-cost 0.025
    :cache-write-cost 0.0}

   :gpt-5.2
   {:id "gpt-5.2"
    :name "GPT-5.2"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 400000
    :max-tokens 128000
    :input-cost 1.75
    :output-cost 14.0
    :cache-read-cost 0.175
    :cache-write-cost 0.0}

   :gpt-5.2-chat-latest
   {:id "gpt-5.2-chat-latest"
    :name "GPT-5.2 Chat"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 128000
    :max-tokens 16384
    :input-cost 1.75
    :output-cost 14.0
    :cache-read-cost 0.175
    :cache-write-cost 0.0}

   :gpt-5.2-codex
   {:id "gpt-5.2-codex"
    :name "GPT-5.2 Codex"
    :provider :openai
    :api :openai-codex-responses
    :base-url "https://chatgpt.com/backend-api"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 272000
    :max-tokens 128000
    :input-cost 1.75
    :output-cost 14.0
    :cache-read-cost 0.175
    :cache-write-cost 0.0}

   :gpt-5.2-pro
   {:id "gpt-5.2-pro"
    :name "GPT-5.2 Pro"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 400000
    :max-tokens 128000
    :input-cost 21.0
    :output-cost 168.0
    :cache-read-cost 0.0
    :cache-write-cost 0.0}

   :gpt-5.3-codex
   {:id "gpt-5.3-codex"
    :name "GPT-5.3 Codex"
    :provider :openai
    :api :openai-codex-responses
    :base-url "https://chatgpt.com/backend-api"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 272000
    :max-tokens 128000
    :input-cost 1.75
    :output-cost 14.0
    :cache-read-cost 0.175
    :cache-write-cost 0.0}

   :gpt-5.3-codex-spark
   {:id "gpt-5.3-codex-spark"
    :name "GPT-5.3 Codex Spark"
    :provider :openai
    :api :openai-codex-responses
    :base-url "https://chatgpt.com/backend-api"
    :supports-reasoning true
    :supports-images false
    :supports-text true
    :context-window 128000
    :max-tokens 128000
    :input-cost 0.0
    :output-cost 0.0
    :cache-read-cost 0.0
    :cache-write-cost 0.0}

   :gpt-5.4
   {:id "gpt-5.4"
    :name "GPT-5.4"
    :provider :openai
    :api :openai-codex-responses
    :base-url "https://chatgpt.com/backend-api"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 272000
    :max-tokens 128000
    :input-cost 2.5
    :output-cost 15.0
    :cache-read-cost 0.25
    :cache-write-cost 0.0}

   :gpt-5.4-mini
   {:id "gpt-5.4-mini"
    :name "GPT-5.4 Mini"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 400000
    :max-tokens 128000
    :input-cost 0.75
    :output-cost 4.5
    :cache-read-cost 0.0
    :cache-write-cost 0.0}

   :gpt-5.5
   {:id "gpt-5.5"
    :name "GPT-5.5"
    :provider :openai
    :api :openai-completions
    :base-url "https://api.openai.com/v1"
    :supports-reasoning true
    :supports-images true
    :supports-text true
    :context-window 1000000
    :max-tokens 128000
    :input-cost 5.0
    :output-cost 30.0
    :cache-read-cost 0.5
    :cache-write-cost 0.0}})

(def ^:private provider-defaults
  {:anthropic {:locality :cloud
               :latency-tier :low}
   :openai    {:locality :cloud
               :latency-tier :low}})

(defn- cost-tier
  [{:keys [input-cost output-cost]}]
  (let [input*  (double (or input-cost 0.0))
        output* (double (or output-cost 0.0))]
    (cond
      (and (zero? input*) (zero? output*)) :zero
      (and (<= input* 1.0) (<= output* 5.0)) :low
      (and (<= input* 5.0) (<= output* 20.0)) :medium
      :else :high)))

(defn- annotate-model
  [model]
  (let [provider-meta (get provider-defaults (:provider model) {})]
    (merge provider-meta
           model
           {:cost-tier (cost-tier model)})))

(def all-models
  (->> (merge anthropic-models openai-models)
       (map (fn [[k model]] [k (annotate-model model)]))
       (into {})))

;; Model access functions

(defn get-model
  "Get model by key"
  [model-key]
  (get all-models model-key))

(defn list-for-provider
  "List models for specific provider"
  [provider]
  (->> all-models
       (filter (fn [[_ model]] (= provider (:provider model))))
       (into {})))

(defn list-reasoning-models
  "List models that support reasoning"
  []
  (->> all-models
       (filter (fn [[_ model]] (:supports-reasoning model)))
       (into {})))

(defn list-multimodal-models
  "List models that support images"
  []
  (->> all-models
       (filter (fn [[_ model]]
                 (and (:supports-images model)
                      (:supports-text model))))
       (into {})))

;; Cost calculation

(defn calculate-cost
  "Calculate cost for given usage and model"
  [model usage]
  (let [{:keys [input-cost output-cost cache-read-cost cache-write-cost]} model
        {:keys [input-tokens output-tokens cache-read-tokens cache-write-tokens]} usage
        input-cost-total (* (/ input-tokens 1000000.0) input-cost)
        output-cost-total (* (/ output-tokens 1000000.0) output-cost)
        cache-read-cost-total (* (/ cache-read-tokens 1000000.0) cache-read-cost)
        cache-write-cost-total (* (/ cache-write-tokens 1000000.0) cache-write-cost)]
    {:input input-cost-total
     :output output-cost-total
     :cache-read cache-read-cost-total
     :cache-write cache-write-cost-total
     :total (+ input-cost-total output-cost-total
               cache-read-cost-total cache-write-cost-total)}))

;; Model validation

(defn supports-multimodal?
  "Check if model supports both text and images"
  [model]
  (and (:supports-images model)
       (:supports-text model)))

(defn supports-reasoning?
  "Check if model supports reasoning/thinking"
  [model]
  (:supports-reasoning model))

(defn get-context-window
  "Get context window size for model"
  [model]
  (:context-window model))

(defn get-max-tokens
  "Get max output tokens for model"
  [model]
  (:max-tokens model))