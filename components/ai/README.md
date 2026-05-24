# AI Component

A Polylith-style component implementing the Allium AI provider specification. Provides unified multi-provider LLM interface supporting Anthropic and OpenAI.

## Features

- **Unified Interface**: Single API across Anthropic and OpenAI providers
- **Streaming Responses**: Real-time token streaming with event-based updates  
- **Conversation Management**: Full conversation lifecycle with message history
- **Tool Support**: Tool calling and response handling
- **Usage Tracking**: Token usage and cost calculation
- **Type Safety**: Malli schemas for all entities and validation

## Architecture

Based on the [Allium specification](../../spec/ai-abstract-model.allium), the component follows behavioral patterns:

- `Conversation` - conversation lifecycle with messages and tools
- `Model` - provider models with capabilities and costs
- `StreamSession` - streaming response session metadata and lifecycle state
- `Message` - user/assistant/tool messages with usage tracking

## Usage

### Basic Conversation

```clojure
(require '[psi.ai.core :as ai]
         '[psi.ai.models :as models])

;; Create conversation
(def conversation (ai/create-conversation "You are a helpful assistant"))

;; Add user message
(def updated (ai/send-message conversation "What is 2+2?"))

;; Stream response via callback
(def model (models/get-model :claude-3-5-sonnet))
(def options {:temperature 0.7 :max-tokens 1000})
(def {:keys [future session]}
  (ai/stream-response
   updated model options
   (fn [event]
     (case (:type event)
       :start (println "Response starting...")
       :text-delta (print (:delta event))
       :thinking-delta (println "Thinking:" (:delta event))
       :done (println "\nResponse complete:" (:reason event))
       :error (println "Error:" (:error-message event))))))

@future
@session
```

### Processing Stream Events as a Sequence

```clojure
(let [{:keys [events session]}
      (ai/stream-response-seq updated model options)]
  (doseq [event events]
    (case (:type event)
      :start (println "Response starting...")
      :text-delta (print (:delta event))
      :thinking-delta (println "Thinking:" (:delta event))
      :done (println "\nResponse complete:" (:reason event))
      :error (println "Error:" (:error-message event))))
  @session)
```

### Available Models

```clojure
;; List all models
(models/all-models)

;; List by provider
(models/list-for-provider :anthropic)
(models/list-for-provider :openai)

;; Get specific model
(models/get-model :claude-3-5-sonnet)
(models/get-model :gpt-4o)

;; Filter by capability
(models/list-reasoning-models)
(models/list-multimodal-models)
```

### Usage and Cost Tracking

```clojure
;; Get conversation usage
(ai/get-conversation-usage conversation)
;; => {:input-tokens 15 :output-tokens 42 :total-tokens 57}

;; Get conversation cost  
(ai/get-conversation-cost conversation)
;; => {:input 0.045 :output 0.63 :total 0.675}
```

## Configuration

Set environment variables for API access:

```bash
export ANTHROPIC_API_KEY="your-key"
export OPENAI_API_KEY="your-key" 
```

Or pass in options:

```clojure
(ai/stream-response conversation model {:api-key "your-key"})
```

## Schemas

All entities are validated using Malli schemas:

- `schemas/Conversation` - conversation state and metadata
- `schemas/Message` - individual messages with role and content
- `schemas/Model` - model definitions and capabilities
- `schemas/StreamOptions` - streaming configuration options
- `schemas/Usage` - token usage and cost tracking

## Structured output capabilities

Model descriptions may declare `[:capabilities :structured-output]` to make the effective structured-output strategy explicit. Omitted capability data remains load-valid but normalizes to unsupported; prompted JSON fallback is opt-in with `:strategies [:prompted-json]`.

Request options may include `:structured-output` with `:schema-id`, `:schema-version`, `:name`, `:strict?`, `:fallback-allowed?`, and a caller-supplied `:json-schema`. AI adapters do not convert Malli/domain `:schema` to JSON Schema.

Supported native mechanisms in this slice:

- OpenAI `:openai-completions` models that declare `:openai/chat-completions-json-schema-response-format` send Chat Completions `response_format {:type "json_schema" ...}`.
- Anthropic `:anthropic-messages` models that declare `:anthropic/json-schema-output` use Anthropic Messages `output_format {:type "json_schema" ...}` plus the structured-output beta header. This is the preferred Anthropic native mechanism for supported Claude 4.5+ catalog entries.
- Anthropic `:anthropic-messages` models that declare `:anthropic/forced-tool-use` append a synthetic forced tool with `input_schema`. This is a separate native tool-use mechanism for older or compatibility model entries, not the only Anthropic structured-output path.
- OpenAI `:openai-codex-responses` models are fallback-only when declared; they never receive public OpenAI Chat Completions/Responses schema fields.
- Prompted JSON remains fallback only; local runtime validation still gates downstream structured values.

Strategy metadata is explicit. Streaming calls emit `:structured-output-strategy` before provider content and `:structured-output-result` when extracted payload data is known. Non-streaming provider results include top-level `:structured-output` metadata and any extracted payload. Provider-native output still requires caller/workflow validation before use as trusted structured data.

## Provider Support

Currently supports:

### Anthropic
- Claude 3.5 Sonnet (reasoning, multimodal)
- Claude 3.5 Haiku (multimodal)
- Claude 4/4.5+ catalog entries
- Messages API with streaming and non-streaming execution
- JSON Schema structured output for supported Claude 4.5+ catalog entries via `:anthropic/json-schema-output`
- Forced tool-use structured output for older/compatibility entries via `:anthropic/forced-tool-use`
- Prompted JSON fallback only when explicitly declared; local runtime validation remains authoritative
- Prompt caching support
- OAuth/API tokens must not be written into docs, task files, fixtures, logs, or commits

### OpenAI  
- GPT-4o (multimodal)
- o1-preview (reasoning)
- GPT-5 family (gpt-5, gpt-5.1, gpt-5.2, gpt-5-pro)
- GPT-5 Codex family (gpt-5.1-codex, gpt-5.2-codex, gpt-5.3-codex, gpt-5.3-codex-spark)
- Chat Completions API with streaming (`:openai-completions`)
- Codex Responses streaming via ChatGPT backend (`:openai-codex-responses`)
- Tool calling support
- Note: Codex responses require a ChatGPT OAuth access token (contains `chatgpt_account_id`)

## Testing

```bash
clj -M:test
```

Run with API keys to test live providers:

```bash
ANTHROPIC_API_KEY=... OPENAI_API_KEY=... clj -M:test
```