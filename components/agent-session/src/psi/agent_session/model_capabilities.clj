(ns psi.agent-session.model-capabilities
  "Shared model capability predicates for session-facing features."
  (:require
   [psi.ai.model-registry :as model-registry]
   [psi.ai.providers.request-support :as request-support]
   [psi.provider-auth.core :as provider-auth]
   [psi.session-state.state :as ss]))

(defn supports-mid-system-messages?
  "Return true when a resolved model supports mid-conversation system messages.

   Explicit model metadata wins. Built-in OpenAI chat-completions support is
   also inferred from the runtime API shape via
   `request-support/builtin-openai-chat-completions?`, so catalog models need
   no psi-specific metadata. The predicate gates inference on the `:custom?`
   origin tag: a custom models.edn provider named \"openai\" must declare
   `:supports-mid-conversation-system-messages` explicitly. Codex-routed
   built-ins (`:api :openai-codex-responses`) never match this inference."
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
