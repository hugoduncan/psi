(ns psi.agent-session.workflow-file-authoring-routing
  "Workflow-file authoring helpers for author-facing step references in routing.

   Owns resolution of author-facing `:goto` step names into canonical compiled
   step ids for deterministic workflow definitions.")

(defn routing-target->step-id-map
  "Build a map for goto resolution. Explicit `:name` values are authoritative.
   For backward compatibility, unique delegated workflow names are also accepted
   when unambiguous."
  [steps step-order]
  (let [workflow-name-freqs (frequencies (keep :workflow steps))]
    (into {}
          (keep-indexed
           (fn [idx step]
             (cond
               (:name step)
               [(:name step) (nth step-order idx)]

               (and (:workflow step)
                    (= 1 (get workflow-name-freqs (:workflow step))))
               [(:workflow step) (nth step-order idx)]

               :else
               nil))
           steps))))

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
