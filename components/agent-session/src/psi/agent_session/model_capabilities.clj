(ns psi.agent-session.model-capabilities
  "Shared model capability predicates for session-facing features."
  (:require
   [psi.ai.model-registry :as model-registry]
   [psi.ai.providers.request-support :as request-support]
   [psi.provider-auth.core :as provider-auth]
   [psi.session-state.state :as ss]))

(defn supports-mid-system-messages?
  "Return true when a resolved model supports mid-conversation system messages.

   Explicit model metadata wins for providers that declare the feature. OpenAI
   chat-completions support is also inferred from the runtime API shape so
   custom/runtime-loaded OpenAI chat models do not need to carry psi-specific
   metadata — but only for built-in catalog models: the inference is gated on
   the review-14 `:custom?` origin tag via the shared
   `request-support/builtin-openai-chat-completions?` predicate (review 26),
   so a custom models.edn provider literally named \"openai\" (tagged
   `:custom? true` by `expand-model`) cannot receive the built-in-only
   inference by name. Custom providers must declare
   `:supports-mid-conversation-system-messages` explicitly."
  [model]
  (let [explicit-support (:supports-mid-conversation-system-messages model)]
    (boolean
     (cond
       (true? explicit-support)
       true

       (false? explicit-support)
       false

       :else
       (request-support/builtin-openai-chat-completions? model)))))

(defn runtime-active-model
  "Resolve the active runtime model for `session-id`, falling back to the stored
   session model when runtime model resolution cannot produce a model."
  [ctx session-id]
  (let [session-model (:model (ss/get-session-data-in ctx session-id))
        provider      (provider-auth/normalize-provider-id (:provider session-model))
        model-id      (:id session-model)]
    (or (when (and provider model-id)
          (model-registry/resolve-runtime-model ctx provider model-id))
        session-model)))

(defn session-supports-mid-system-messages?
  [ctx session-id]
  (supports-mid-system-messages? (runtime-active-model ctx session-id)))
