(ns psi.agent-session.provider-auth
  "Shared provider-auth resolution helpers.

   Keeps provider-scoped auth precedence consistent across canonical request
   preparation and runtime-facing helper paths."
  (:require
   [psi.ai.model-registry :as model-registry]
   [psi.agent-session.oauth.core :as oauth]))

(defn provider-auth-config
  "Return model-registry auth for `provider` or nil."
  [provider]
  (when provider
    (model-registry/get-auth provider)))

(defn provider-api-key
  "Resolve a provider-scoped API key using shared precedence:
   1. OAuth/runtime credential for the selected provider
   2. model-registry auth for the selected provider when auth headers are enabled"
  [ctx provider]
  (or (when-let [oauth-ctx (:oauth-ctx ctx)]
        (oauth/get-api-key oauth-ctx provider))
      (when-let [auth (provider-auth-config provider)]
        (when (:auth-header? auth)
          (:api-key auth)))))

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