(ns psi.agent-session.deterministic-operation-action
  "Shared invocation/listing mechanism for deterministic operations.

   Single underlying mechanism behind both the `/operations` / `/operation`
   slash commands and the psi-tool `operation` action. Builds the
   direct-invocation map from session ctx and routes through the existing
   registry + runtime boundary. Owns no rendering: surfaces format the data
   returned here."
  (:require
   [psi.deterministic-operation-registry.registry :as registry]
   [psi.deterministic-operation-runtime.core :as runtime]
   [psi.session-state.state :as session-state]))

(def ^:private truncation-limit
  "Maximum characters of a per-key `pr-str` value before truncation."
  2000)

(defn list-operations
  "Return the registry's operations projected to `{:id :description}`, sorted
   ascending by id (string compare). Empty registry yields `[]`."
  [ctx]
  (->> (registry/all-operations-in (:deterministic-operation-registry ctx))
       (map (fn [op] {:id (:id op) :description (:description op)}))
       (sort-by :id)
       vec))

(defn build-invocation
  "Build the caller invocation map for a direct operation invocation.

   `operation-id` is NOT a key here; it is passed positionally to
   `invoke-operation-in`, and `runtime/invoke-operation` injects it. The
   invocation carries `:args`, `:ctx`, `:session-id`, and conditionally
   `:parent-session-id` (only when the invoking session has a known parent).
   `:workflow-run-id` / `:step-id` are always absent (nil) for direct calls."
  [ctx session-id args]
  (let [parent-session-id (:parent-session-id
                           (session-state/get-session-data-in ctx session-id))]
    (cond-> {:args (or args {})
             :ctx ctx
             :session-id session-id}
      parent-session-id (assoc :parent-session-id parent-session-id))))

(defn invoke-operation
  "Invoke a deterministic operation by id through the existing runtime boundary.

   Returns the tagged result (`:ok` / `:error`). Lets
   `:missing-deterministic-operation` and `:malformed-operation-result` ex-info
   propagate to the calling surface for distinct rendering."
  [ctx session-id operation-id args]
  (registry/invoke-operation-in
   (:deterministic-operation-registry ctx)
   operation-id
   (build-invocation ctx session-id args)
   runtime/invoke-operation))

(defn truncate-value
  "Truncate a `pr-str`-derived string to `truncation-limit` characters,
   appending a marker noting the untruncated character count when exceeded."
  [s]
  (let [n (count s)]
    (if (> n truncation-limit)
      (str (subs s 0 truncation-limit)
           " … (truncated, " n " chars total)")
      s)))

(defn project-result
  "Project a tagged result map to `{k truncated-string}`, preserving all
   top-level keys. Each value is `pr-str`'d then per-key truncated. Surface-
   independent so the command and psi-tool action render identically."
  [result]
  (into {}
        (map (fn [[k v]] [k (truncate-value (pr-str v))]))
        result))
