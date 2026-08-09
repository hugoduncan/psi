(ns psi.provider-auth.core
  "Shared provider-auth resolution helpers.

   Keeps provider-scoped auth precedence consistent across canonical request
   preparation and runtime-facing helper paths."
  (:require
   [clojure.string :as str]
   [psi.ai.model-registry :as model-registry]
   [psi.provider-auth.oauth.core :as oauth]
   [psi.provider-auth.oauth.store :as oauth.store]))

(defn normalize-provider-id
  "Normalize provider identity to the keyword form used by shared provider
   registries. Blank strings normalize to nil."
  [provider]
  (cond
    (keyword? provider) provider
    (string? provider)  (when-not (str/blank? provider)
                          (keyword provider))
    :else               nil))

(defn provider-auth-config
  "Return model-registry auth for `provider` or nil."
  [provider]
  (when-let [provider-id (normalize-provider-id provider)]
    (model-registry/get-auth provider-id)))

(defn provider-api-key
  "Resolve a provider-scoped API key using shared precedence:
   1. OAuth/runtime credential for the selected provider — BUILT-IN models
      only (`custom?` false): OAuth login exists only for built-in
      anthropic/openai, and a custom models.edn provider literally named
      \"anthropic\"/\"openai\" must never receive the built-in same-named
      OAuth credential (review 42 — origin gate at the session
      request-options layer).
   2. model-registry auth for the selected provider when auth headers are
      enabled — CUSTOM models only (`custom?` true): registry `:auth` is
      populated from models.edn custom providers keyed by provider NAME, so
      a built-in same-named model must never inherit a custom provider's
      auth config (review 42).

   `custom?` is the resolved model's `:custom?` origin tag (review 14).
   Absent/nil is treated as built-in (OAuth only, no registry auth) — the
   safe default: registry auth is never consulted unless the model is proven
   custom, and built-in models resolve only env/OAuth.

   For custom models.edn providers the registry stores the RAW `:api-key`
   spec (literal or \"env:VAR\", review 26) — the transports'
   `request-support/resolve-api-key` re-resolves `env:` keys per request.
   Callers that need a concrete key must route through that shared helper."
  ([ctx provider]
   (provider-api-key ctx provider nil))
  ([ctx provider custom?]
   (let [provider-id (normalize-provider-id provider)]
     (or (when (and provider-id (not custom?) (:oauth-ctx ctx))
           (oauth/get-api-key (:oauth-ctx ctx) provider-id))
         (when (and custom? provider-id)
           (when-let [auth (provider-auth-config provider-id)]
             (when (:auth-header? auth)
               (:api-key auth))))))))

(defn oauth-credential-type
  "Return the stored OAuth credential type for `provider` from `ctx`, or nil.
   This is intentionally narrower than auth resolution: it inspects the live
   stored credential so higher layers can distinguish ChatGPT OAuth-backed
   OpenAI sessions from platform-key-backed ones."
  [ctx provider]
  (when-let [provider-id (normalize-provider-id provider)]
    (when-let [store (some-> ctx :oauth-ctx :store)]
      (:type (oauth.store/get-credential store provider-id)))))

(defn oauth-backed?
  "True when `provider` currently resolves from a stored OAuth credential."
  [ctx provider]
  (= :oauth (oauth-credential-type ctx provider)))

(defn provider-request-options
  "Return provider-scoped request options derived from model-registry auth.
   Includes transport hints such as `:no-auth-header` and custom headers.

   Registry `:auth` is populated from models.edn custom providers keyed by
   provider NAME — a built-in same-named model must never inherit a custom
   provider's `:no-auth-header`/headers config (review 42), so the lookup is
   gated on `custom?` (the resolved model's `:custom?` origin tag, review
   14). Absent/nil is treated as built-in and returns nil — the safe
   default: registry-derived options are never applied unless the model is
   proven custom."
  ([provider]
   (provider-request-options provider nil))
  ([provider custom?]
   (when custom?
     (when-let [auth (provider-auth-config provider)]
       (cond-> {}
         (false? (:auth-header? auth))
         (assoc :no-auth-header true)

         (seq (:headers auth))
         (assoc :headers (:headers auth)))))))