(ns psi.ai.model-registry
  "Merged model catalog: built-in + user-global + project-local.

   Single source of truth for model lookup and custom provider auth.
   Replaces direct references to psi.ai.models/all-models."
  (:require
   [clojure.string :as str]
   [psi.ai.models :as built-in]
   [psi.ai.user-models :as user-models]
   [taoensso.timbre :as log]))

;; ── State ────────────────────────────────────────────────────────────────────

(defonce ^:private registry-state
  (atom {:catalog    nil  ;; {[provider-kw model-id] → model-map}, nil = not yet initialized
         :auth       {}   ;; {provider-kw → auth-map}
         :load-error nil}))

;; ── Built-in catalog ─────────────────────────────────────────────────────────

(defn- built-in-catalog
  "Index built-in models by [provider model-id]."
  []
  (reduce-kv
   (fn [acc _key model]
     (assoc acc [(:provider model) (:id model)] model))
   {}
   built-in/all-models))

;; ── Merge logic ──────────────────────────────────────────────────────────────

(defn- merge-custom-models
  "Merge custom models into a catalog, respecting shadow rules.

   Custom models that collide with existing entries by [provider id] are
   skipped with a warning. This prevents custom models from accidentally
   replacing built-in models, while still allowing project models to
   override user models (since they share a separate merge pass)."
  [catalog custom-models warn-on-shadow?]
  (reduce
   (fn [cat model]
     (let [k [(:provider model) (:id model)]]
       (if (and warn-on-shadow? (contains? cat k))
         (do (log/warn "Custom model" (name (:provider model)) "/" (:id model)
                       "shadows existing entry; skipping")
             cat)
         (assoc cat k model))))
   catalog
   custom-models))

(defn- merge-auth
  "Merge auth maps. Later values win per provider key."
  [base overlay]
  (merge base overlay))

;; ── Loading ──────────────────────────────────────────────────────────────────

(defn- load-and-merge
  "Build the catalog from built-in + user + project models."
  [{:keys [user-models-path project-models-path]}]
  (let [built-ins (built-in-catalog)

        ;; Load user-global models
        user-result (if user-models-path
                      (user-models/load-models-file! user-models-path)
                      {:models [] :auth {}})

        ;; Load project-local models
        project-result (if project-models-path
                         (user-models/load-models-file! project-models-path)
                         {:models [] :auth {}})

        ;; Merge user models into built-in catalog (warn on shadow)
        with-user (merge-custom-models built-ins (:models user-result) true)

        ;; Merge project models: project overrides user for same key,
        ;; but still warns if shadowing a built-in
        ;; First, remove any user-model entries that project models will replace
        project-keys (set (map (fn [m] [(:provider m) (:id m)])
                               (:models project-result)))
        with-user-pruned (reduce (fn [cat pk]
                                   ;; Only prune if the existing is from a custom
                                   ;; provider (not built-in). Check: if the key
                                   ;; exists in the original built-ins, don't prune.
                                   (if (contains? built-ins pk)
                                     cat
                                     (dissoc cat pk)))
                                 with-user
                                 project-keys)

        ;; Now merge project models (warn if shadowing built-in)
        final-catalog (merge-custom-models with-user-pruned
                                           (:models project-result)
                                           true)

        ;; Auth: user < project (project wins)
        final-auth (merge-auth (:auth user-result) (:auth project-result))

        ;; Collect errors
        errors (keep identity [(:error user-result) (:error project-result)])
        load-error (when (seq errors) (str/join "; " errors))]

    {:catalog    final-catalog
     :auth       final-auth
     :load-error load-error}))

(defn init!
  "Initialize the registry from built-in models + config file paths.

   Options:
     :user-models-path    — path to ~/.psi/agent/models.edn (or nil)
     :project-models-path — path to .psi/models.edn (or nil)"
  [opts]
  (reset! registry-state (load-and-merge opts))
  :ok)

(defn refresh!
  "Reload the registry. Requires the same opts as init!."
  [opts]
  (init! opts))

(defn load-project-models!
  "Reload with a new project-models path (e.g., on session bootstrap in a new cwd).
   Preserves the user-models-path from the current state."
  [project-models-path user-models-path]
  (init! {:user-models-path    user-models-path
          :project-models-path project-models-path}))

;; ── Queries ──────────────────────────────────────────────────────────────────

(defn- ensure-catalog
  "Return the catalog, lazily initializing from built-ins if not yet loaded."
  []
  (let [cat (:catalog @registry-state)]
    (if (some? cat)
      cat
      (built-in-catalog))))

(defn all-models
  "Return all models as a map of {[provider-kw model-id] → model-map}."
  []
  (ensure-catalog))

(defn all-models-seq
  "Return all models as a sequence of model maps."
  []
  (vals (ensure-catalog)))

(defn all-models-by-key
  "Return all models as {model-key → model-map}, keyed the same way
   as psi.ai.models/all-models for backward compatibility.

   Uses the built-in key where available; custom models get a synthesized
   keyword key of :provider/model-id."
  []
  (let [;; Built-in models have named keys — build a reverse lookup
        built-in-by-pid (reduce-kv
                         (fn [acc k model]
                           (assoc acc [(:provider model) (:id model)] k))
                         {}
                         built-in/all-models)]
    (reduce-kv
     (fn [acc [provider model-id :as pid] model]
       (let [k (or (get built-in-by-pid pid)
                   (keyword (name provider) model-id))]
         (assoc acc k model)))
     {}
     (ensure-catalog))))

(defn find-model
  "Find a model by provider keyword and model-id string.
   Returns the model map or nil."
  [provider-kw model-id]
  (get (ensure-catalog) [provider-kw model-id]))

(defn get-auth
  "Get auth config for a provider keyword.
   Returns {:provider kw :api-key str? :auth-header? bool :headers map?} or nil."
  [provider-kw]
  (get (:auth @registry-state) provider-kw))

(defn get-load-error
  "Return the load error string, or nil if no errors."
  []
  (:load-error @registry-state))

(defn providers
  "Return the set of all provider keywords in the catalog."
  []
  (into #{} (map first) (keys (ensure-catalog))))

(defn models-for-provider
  "Return all models for a given provider keyword."
  [provider-kw]
  (into {}
        (filter (fn [[[p _] _]] (= p provider-kw)))
        (ensure-catalog)))

;; ── Init from defaults ───────────────────────────────────────────────────────

(defn default-user-models-path
  "Return the default user-global models.edn path."
  []
  (str (System/getProperty "user.home") "/.psi/agent/models.edn"))
