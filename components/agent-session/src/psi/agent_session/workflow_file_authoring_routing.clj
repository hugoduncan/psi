(ns psi.agent-session.workflow-file-authoring-routing
  "Workflow-file authoring helpers for author-facing step references in routing.

   Owns resolution of author-facing `:goto` step names into canonical compiled
   step ids for deterministic workflow definitions.")

(defn routing-target->step-id-map
  "Build a map for goto resolution. Multi-step workflow routing is now fully
   spec-first: explicit step `:name` values are required and authoritative."
  [steps step-order]
  (into {}
        (keep-indexed
         (fn [idx step]
           (when-let [step-name (:name step)]
             [step-name (nth step-order idx)]))
         steps)))

(defn resolve-routing-table
  "Resolve :goto step names in a routing table to compiled step-ids.
   Keywords (:next, :previous, :done) pass through without resolution."
  [on-table target->step-id]
  (when on-table
    (into {}
          (map (fn [[signal directive]]
                 [signal
                  (if (and (string? (:goto directive))
                           (contains? target->step-id (:goto directive)))
                    (assoc directive :goto (get target->step-id (:goto directive)))
                    directive)]))
          on-table)))
