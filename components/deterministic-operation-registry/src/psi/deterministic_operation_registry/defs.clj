(ns psi.deterministic-operation-registry.defs
  "Canonical deterministic-operation definition helpers.

   Owns canonical operation ids, registration definition validation, result
   validation, and stored-definition normalization used by the runtime-owned
   deterministic-operation registry."
  (:require
   [clojure.string :as str]
   [malli.core :as m]))

(def operation-id-pattern
  "Canonical deterministic operation ids are namespaced kebab-case strings.
   Example: github/search-issues-by-label"
  #"^[a-z0-9][a-z0-9-]*/[a-z0-9][a-z0-9-]*$")

(defn valid-operation-id?
  [operation-id]
  (and (string? operation-id)
       (boolean (re-matches operation-id-pattern operation-id))))

(def operation-definition-schema
  [:map {:closed true}
   [:id [:fn {:error/message (str "operation id must match " operation-id-pattern)}
         valid-operation-id?]]
   [:handler fn?]
   [:description {:optional true} [:maybe :string]]
   [:summary {:optional true} [:maybe :string]]
   [:ext-path {:optional true} [:maybe :string]]
   [:source {:optional true} [:maybe [:enum :extension :runtime]]]])

(def operation-success-result-schema
  [:map
   [:status [:= :ok]]
   [:data :any]
   [:summary {:optional true} [:maybe :string]]
   [:details {:optional true} [:maybe :map]]])

(def operation-error-result-schema
  [:map
   [:status [:= :error]]
   [:reason :keyword]
   [:message :string]
   [:details {:optional true} [:maybe :map]]])

(def operation-result-schema
  [:multi {:dispatch :status}
   [:ok operation-success-result-schema]
   [:error operation-error-result-schema]])

(defn valid-operation-definition?
  [x]
  (m/validate operation-definition-schema x))

(defn valid-operation-result?
  [x]
  (m/validate operation-result-schema x))

(defn explain-operation-result
  [x]
  (m/explain operation-result-schema x))

(defn normalize-operation-def
  [operation]
  (when-not (valid-operation-definition? operation)
    (throw (ex-info "Invalid deterministic operation definition"
                    {:operation operation
                     :explanation (m/explain operation-definition-schema operation)})))
  (cond-> operation
    (contains? operation :description) (update :description #(some-> % str/trim))
    (contains? operation :summary) (update :summary #(some-> % str/trim))))
