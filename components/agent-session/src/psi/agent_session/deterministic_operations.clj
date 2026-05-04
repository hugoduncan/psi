(ns psi.agent-session.deterministic-operations
  "Canonical deterministic-operation boundary for workflow IR `:type :invoke`.

   Owns:
   - stable author-facing operation ids
   - extension/runtime registration shape validation
   - tagged success/failure operation-result contract
   - runtime-owned invocation/result normalization helpers for invoke steps"
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
  [:map
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

(defn malformed-operation-result-ex
  [operation invocation result]
  (ex-info "Deterministic operation returned malformed result"
           {:type :malformed-operation-result
            :operation-id (:id operation)
            :invocation (dissoc invocation :ctx)
            :result result
            :explanation (explain-operation-result result)}))

(defn invoke-operation
  "Invoke a normalized deterministic operation.

   Implementations receive one invocation map. Current first-cut keys may include:
   - :operation-id
   - :args
   - :ctx
   - :session-id
   - :workflow-run-id
   - :step-id
   - :parent-session-id

   Implementations must return one tagged operation result:
   - success => {:status :ok :data ... :summary? string :details? map}
   - failure => {:status :error :reason keyword :message string :details? map}

   Thrown exceptions are canonicalized into tagged `:error` results.
   Malformed returned values are rejected with ex-info."
  [operation invocation]
  (let [result (try
                 ((:handler operation) (assoc invocation :operation-id (:id operation)))
                 (catch Throwable t
                   {:status :error
                    :reason :operation-threw
                    :message (or (ex-message t) (str t))
                    :details {:operation-id (:id operation)}}))]
    (when-not (valid-operation-result? result)
      (throw (malformed-operation-result-ex operation invocation result)))
    result))

(defn operation-result->invoke-step-result
  "Wrap a canonical operation result into runtime-owned invoke-step semantics.

   Success becomes an accepted-result envelope with canonical invoke outputs:
   - :data    <- operation :data
   - :summary <- operation :summary when present
   - :result  <- full tagged operation result

   Failure becomes a canonical error yield input for invoke-step execution."
  [operation-result]
  (when-not (valid-operation-result? operation-result)
    (throw (ex-info "Cannot wrap malformed deterministic operation result"
                    {:type :malformed-operation-result
                     :result operation-result
                     :explanation (explain-operation-result operation-result)})))
  (case (:status operation-result)
    :ok
    {:kind :accepted-result
     :accepted-result {:outcome :ok
                       :outputs (cond-> {:data (:data operation-result)
                                         :result operation-result}
                                  (contains? operation-result :summary)
                                  (assoc :summary (:summary operation-result)))}}

    :error
    {:kind :error-yield
     :yield {:type :error
             :reason (:reason operation-result)
             :message (:message operation-result)
             :details (cond-> {:operation-result operation-result}
                        (:details operation-result)
                        (assoc :operation-details (:details operation-result)))}}

    (throw (ex-info "Unknown deterministic operation result status"
                    {:result operation-result}))))
