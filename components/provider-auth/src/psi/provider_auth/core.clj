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
   1. OAuth/runtime credential for the selected provider
   2. model-registry auth for the selected provider when auth headers are enabled

   For custom models.edn providers the registry stores the RAW `:api-key`
   spec (literal or \"env:VAR\", review 26) — the transports'
   `request-support/resolve-api-key` re-resolves `env:` keys per request.
   Callers that need a concrete key must route through that shared helper."
  [ctx provider]
  (let [provider-id (normalize-provider-id provider)]
    (or (when (and provider-id (:oauth-ctx ctx))
          (oauth/get-api-key (:oauth-ctx ctx) provider-id))
        (when-let [auth (provider-auth-config provider-id)]
          (when (:auth-header? auth)
            (:api-key auth))))))

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
   Includes transport hints such as `:no-auth-header` and custom headers."
  [provider]
  (when-let [auth (provider-auth-config provider)]
    (cond-> {}
      (false? (:auth-header? auth))
      (assoc :no-auth-header true)

      (seq (:headers auth))
      (assoc :headers (:headers auth)))))