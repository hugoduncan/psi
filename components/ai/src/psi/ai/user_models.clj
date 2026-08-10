(ns psi.ai.user-models
  "Load user-defined model providers from models.edn config files.

   Supports:
   - Custom providers with OpenAI-compatible (or Anthropic) endpoints
   - API key resolution: literal strings or \"env:VAR_NAME\"
   - Auth header control: omit Authorization for local servers
   - Sensible defaults for local models (cost=0, context=128k, etc.)

   See spec/custom-providers.allium for the formal specification."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [malli.core :as m]
   [psi.ai.schemas :as schemas]
   [psi.ai.structured-output :as structured-output]
   [taoensso.timbre :as log]))

;; ── Schemas ──────────────────────────────────────────────────────────────────

(def ^:private ApiProtocol
  [:enum :anthropic-messages :openai-completions :openai-codex-responses])

(def ^:private AuthConfig
  [:map {:closed true}
   [:api-key {:optional true} [:maybe string?]]
   [:auth-header? {:optional true} [:maybe boolean?]]
   [:headers {:optional true} [:maybe [:map-of string? string?]]]])

(def ^:private Locality
  [:enum :local :cloud])

(def ^:private LatencyTier
  [:enum :low :medium :high])

(def ^:private CostTier
  [:enum :zero :low :medium :high])

(def ^:private ModelDef
  [:map {:closed true}
   [:id string?]
   [:name {:optional true} [:maybe string?]]
   [:supports-reasoning {:optional true} [:maybe boolean?]]
   [:adaptive-thinking {:optional true} [:maybe boolean?]]
   [:supports-images {:optional true} [:maybe boolean?]]
   [:supports-text {:optional true} [:maybe boolean?]]
   [:supports-mid-conversation-system-messages {:optional true} [:maybe boolean?]]
   [:context-window {:optional true} [:maybe pos-int?]]
   [:max-tokens {:optional true} [:maybe pos-int?]]
   [:parallel-tool-calls {:optional true} [:maybe boolean?]]
   [:input-cost {:optional true} [:maybe number?]]
   [:output-cost {:optional true} [:maybe number?]]
   [:cache-read-cost {:optional true} [:maybe number?]]
   [:cache-write-cost {:optional true} [:maybe number?]]
   [:locality {:optional true} [:maybe Locality]]
   [:latency-tier {:optional true} [:maybe LatencyTier]]
   [:cost-tier {:optional true} [:maybe CostTier]]
   [:capabilities {:optional true} schemas/ModelCapabilities]])

(def ^:private ProviderDef
  [:map {:closed true}
   [:base-url string?]
   [:api ApiProtocol]
   [:auth {:optional true} [:maybe AuthConfig]]
   [:models [:vector {:min 1} ModelDef]]])

(def ModelsConfig
  "Malli schema for models.edn files."
  [:map {:closed true}
   [:version {:optional true} [:maybe pos-int?]]
   [:providers [:map-of string? ProviderDef]]])

;; ── Model expansion ─────────────────────────────────────────────────────────

(def ^:private model-defaults
  {:supports-reasoning false
   :supports-images    false
   :supports-text      true
   :context-window     128000
   :max-tokens         16384
   :input-cost         0.0
   :output-cost        0.0
   :cache-read-cost    0.0
   :cache-write-cost   0.0
   :locality           :local
   :latency-tier       :low
   :cost-tier          :zero})

(defn- expand-model
  "Expand a model definition into a fully-formed model map.

   Every custom models.edn model is tagged `:custom? true`. Provider
   transports use this origin tag together with the provider name when
   classifying models, so a custom provider literally named
   \"anthropic\"/\"openai\" cannot receive built-in-only treatment such as
   environment-key fallback or Claude Code OAuth headers."
  [provider-key base-url api model-def]
  (let [provider-kw (if (keyword? provider-key)
                      provider-key
                      (keyword provider-key))]
    (-> (merge model-defaults
               {:provider provider-kw
                :api      api
                :base-url base-url
                :custom?  true}
               (dissoc model-def :name)
               {:name (or (:name model-def) (:id model-def))})
        structured-output/normalize-model)))

;; ── Provider auth ────────────────────────────────────────────────────────────

(defn- extract-provider-auth
  "Extract auth config for a provider.

   The registry stores `:api-key` as a raw literal or `env:VAR` spec. Shared
   transport request support resolves `env:` specs from psi's process
   environment per request. Editing models.edn therefore requires no key-value
   snapshot refresh, but changing psi's launch environment requires relaunching
   psi. Blank or nil specs normalize to nil."
  [provider-key provider-def]
  (let [auth     (:auth provider-def)
        api-key  (not-empty (:api-key auth))
        auth-hdr (if (contains? auth :auth-header?)
                   (:auth-header? auth)
                   true)
        headers  (:headers auth)]
    {:provider     (if (keyword? provider-key)
                     provider-key
                     (keyword provider-key))
     :api-key      api-key
     :auth-header? auth-hdr
     :headers      (when (seq headers) headers)}))

;; ── File loading ─────────────────────────────────────────────────────────────

(defn- parse-providers
  "Parse a validated ModelsConfig into models and auth maps."
  [config]
  (let [providers (:providers config)]
    (reduce-kv
     (fn [acc provider-key provider-def]
       (let [base-url (:base-url provider-def)
             api      (:api provider-def)
             models   (mapv #(expand-model provider-key base-url api %)
                            (:models provider-def))
             auth     (extract-provider-auth provider-key provider-def)]
         (-> acc
             (update :models into models)
             (assoc-in [:auth (keyword provider-key)] auth))))
     {:models [] :auth {}}
     providers)))

(defn parse-models-config
  "Parse and validate a models config map.

   Returns {:models [...] :auth {provider-kw → auth-map}}
   or {:models [] :auth {} :error \"...\"} on validation failure."
  [config]
  (if (m/validate ModelsConfig config)
    (parse-providers config)
    (let [explanation (m/explain ModelsConfig config)]
      {:models []
       :auth   {}
       :error  (str "Invalid models.edn schema: "
                    (pr-str (:errors explanation)))})))

(defn load-models-file
  "Load and parse a models.edn file.

   Returns {:models [...] :auth {...}} on success,
   or {:models [] :auth {} :error \"...\"} on failure.
   Missing files return {:models [] :auth {}} with no error."
  [path]
  (let [f (io/file path)]
    (if (and (.exists f) (.isFile f))
      (try
        (let [content (slurp f)
              config  (edn/read-string content)]
          (if (map? config)
            (parse-models-config config)
            {:models [] :auth {}
             :error  (str "Expected a map in " path ", got: " (type config))}))
        (catch Exception e
          {:models [] :auth {}
           :error  (str "Failed to load " path ": " (ex-message e))}))
      {:models [] :auth {}})))

(defn load-models-file!
  "Load models.edn, logging warnings on errors. Returns result map."
  [path]
  (let [result (load-models-file path)]
    (when-let [err (:error result)]
      (log/warn "Custom models:" err))
    result))
