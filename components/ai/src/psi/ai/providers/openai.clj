(ns psi.ai.providers.openai
  "OpenAI provider implementation.

   Supports two API variants behind the same :openai provider key:
   - :openai-completions      → OpenAI Chat Completions API
   - :openai-codex-responses  → ChatGPT Codex Responses endpoint"
  (:require [psi.ai.providers.openai.chat-completions :as chat]
            [psi.ai.providers.openai.codex-responses :as codex]))

(def transform-messages chat/transform-messages)
(def build-request chat/build-request)
(def codex-input-messages codex/codex-input-messages)
(def codex-reasoning codex/codex-reasoning)
(def build-codex-request codex/build-codex-request)
(def stream-openai chat/stream-openai)
(def execute-openai chat/execute-openai)
(def stream-openai-codex codex/stream-openai-codex)

(defn- codex-model?
  [model]
  (= :openai-codex-responses (:api model)))

(defn- provider-stream
  [model]
  (if (codex-model? model)
    stream-openai-codex
    stream-openai))

(defn stream-openai-dispatch
  [conversation model options consume-fn]
  ((provider-stream model) conversation model options consume-fn))

(defn execute-openai-dispatch
  [conversation model options]
  (if (codex-model? model)
    (throw (ex-info "Non-streaming execution is not implemented for OpenAI Codex responses"
                    {:provider :openai :api :openai-codex-responses}))
    (execute-openai conversation model options)))

(def provider
  {:name   :openai
   :stream stream-openai-dispatch
   :execute execute-openai-dispatch})
