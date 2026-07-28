(ns psi.ai.models.anthropic-catalog
  "Anthropic model catalog data.")

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
    ;; Opus 4.7+ uses adaptive thinking — a different API protocol from
    ;; extended thinking (budget_tokens). See providers/anthropic.clj.
    :adaptive-thinking true
    :supports-images true
    :supports-text true
    :context-window 1000000
    :max-tokens 128000
    :input-cost 5.0
    :output-cost 25.0
    :cache-read-cost 0.5
    :cache-write-cost 6.25}

   :opus-4.8
   {:id "claude-opus-4-8"
    :name "Claude Opus 4.8"
    :provider :anthropic
    :api :anthropic-messages
    :base-url "https://api.anthropic.com"
    :supports-reasoning true
    :adaptive-thinking true
    :supports-mid-conversation-system-messages true
    :supports-images true
    :supports-text true
    :context-window 1000000
    :max-tokens 128000
    :input-cost 5.0
    :output-cost 25.0
    :cache-read-cost 0.5
    :cache-write-cost 6.25}

   :fable-5
   {:id "claude-fable-5"
    :name "Claude Fable 5"
    :provider :anthropic
    :api :anthropic-messages
    :base-url "https://api.anthropic.com"
    :supports-reasoning true
    :adaptive-thinking true
    :supports-mid-conversation-system-messages true
    :supports-images true
    :supports-text true
    :context-window 1000000
    :max-tokens 128000
    :input-cost 10.0
    :output-cost 50.0
    :cache-read-cost 1.0
    :cache-write-cost 12.5}

   :sonnet-5
   {:id "claude-sonnet-5"
    :name "Claude Sonnet 5"
    :provider :anthropic
    :api :anthropic-messages
    :base-url "https://api.anthropic.com"
    :supports-reasoning true
    :adaptive-thinking true
    :supports-mid-conversation-system-messages true
    :supports-images true
    :supports-text true
    :context-window 1000000
    :max-tokens 128000
    :input-cost 3.0
    :output-cost 15.0
    :cache-read-cost 0.3
    :cache-write-cost 3.75}})
