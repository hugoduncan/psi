(ns psi.memory.resolvers
  "Pathom resolvers exposing scaffolded memory state via EQL."
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]))

(defn- by-tag-index
  [records]
  (reduce (fn [acc {:keys [tags] :as record}]
            (reduce (fn [acc' tag]
                      (update acc' tag (fnil conj []) record))
                    acc
                    tags))
          {}
          records))

(pco/defresolver memory-context-state
  "Resolve :psi.memory/state from :psi/memory-ctx."
  [input]
  {::pco/input  [:psi/memory-ctx]
   ::pco/output [:psi.memory/state]}
  (let [memory-ctx (:psi/memory-ctx input)]
    {:psi.memory/state @(:state-atom memory-ctx)}))

(pco/defresolver memory-state
  "Resolve scaffolded Step 10 memory attrs from :psi.memory/state."
  [input]
  {::pco/input  [:psi.memory/state]
   ::pco/output [:psi.memory/status
                 :psi.memory/entry-count
                 :psi.memory/entries
                 :psi.memory/search-results
                 :psi.memory/recovery-count
                 :psi.memory/recoveries
                 :psi.memory/graph-snapshots
                 :psi.memory/graph-deltas
                 :psi.memory/by-tag
                 :psi.memory/capability-history
                 :psi.memory/index-stats]}
  (let [{:keys [status
                records
                recoveries
                graph-snapshots
                graph-deltas
                index-stats
                search-results
                capability-history]} (:psi.memory/state input)]
    {:psi.memory/status             status
     :psi.memory/entry-count        (count records)
     :psi.memory/entries            records
     :psi.memory/search-results     (or search-results [])
     :psi.memory/recovery-count     (count recoveries)
     :psi.memory/recoveries         recoveries
     :psi.memory/graph-snapshots    graph-snapshots
     :psi.memory/graph-deltas       graph-deltas
     :psi.memory/by-tag             (by-tag-index records)
     :psi.memory/capability-history (or capability-history [])
     :psi.memory/index-stats        index-stats}))

(def all-resolvers
  [memory-context-state
   memory-state])
