(ns psi.memory.graph-history
  "Graph snapshot/delta retention constants and helpers."
  (:require
   [clojure.set :as set]))

(def snapshot-retention-limit
  "Step 10 fixed-window snapshot cap."
  200)

(def delta-retention-limit
  "Step 10 fixed-window delta cap."
  1000)

(defn trim-window
  "Keep only the latest `limit` entries from `entries`."
  [entries limit]
  (let [c (count entries)]
    (if (<= c limit)
      (vec entries)
      (vec (drop (- c limit) entries)))))

(defn- resolve-fingerprint
  [capability-graph]
  (or (:fingerprint capability-graph)
      (:graph-fingerprint capability-graph)
      (:graphFingerprint capability-graph)))

(defn- capability-id
  [capability]
  (cond
    (keyword? capability) capability
    (string? capability) capability
    (map? capability) (or (:id capability)
                          (:capability/id capability)
                          (:capabilityId capability)
                          (:name capability))
    :else nil))

(defn- normalize-capability-ids
  [capability-graph]
  (let [ids (or (:capability-ids capability-graph)
                (:capabilityIds capability-graph)
                (some->> (:capabilities capability-graph)
                         (keep capability-id)
                         vec))]
    (->> (or ids [])
         (remove nil?)
         set)))

(defn- operation-id
  [operation]
  (cond
    (keyword? operation) operation
    (string? operation) operation
    (map? operation) (or (:id operation)
                         (:operation/id operation)
                         (:operationId operation)
                         (:name operation))
    :else nil))

(defn- normalize-operation-ids
  [capability-graph]
  (->> (or (:operations capability-graph)
           (:operation-ids capability-graph)
           (:operationIds capability-graph)
           [])
       (keep operation-id)
       set))

(defn make-snapshot
  "Build a canonical graph snapshot map from capability graph data.

   Returns nil when fingerprint is missing."
  ([capability-graph]
   (make-snapshot capability-graph (java.time.Instant/now)))
  ([capability-graph timestamp]
   (when-let [fingerprint (resolve-fingerprint capability-graph)]
     (let [capability-ids (normalize-capability-ids capability-graph)]
       {:snapshot-id (str (random-uuid))
        :timestamp timestamp
        :fingerprint fingerprint
        :node-count (or (:node-count capability-graph)
                        (:nodeCount capability-graph)
                        (count (:nodes capability-graph))
                        0)
        :edge-count (or (:edge-count capability-graph)
                        (:edgeCount capability-graph)
                        (count (:edges capability-graph))
                        0)
        :capability-count (or (:capability-count capability-graph)
                              (:capabilityCount capability-graph)
                              (count capability-ids)
                              0)
        :domain-coverage (or (:domain-coverage capability-graph)
                             (:domainCoverage capability-graph)
                             {})
        :capability-ids (vec capability-ids)
        :operation-ids (vec (normalize-operation-ids capability-graph))}))))

(defn make-delta
  "Build a graph delta between `previous-snapshot` and `current-snapshot`."
  ([previous-snapshot current-snapshot]
   (make-delta previous-snapshot current-snapshot (java.time.Instant/now)))
  ([previous-snapshot current-snapshot timestamp]
   (let [previous-capabilities (set (:capability-ids previous-snapshot))
         current-capabilities (set (:capability-ids current-snapshot))
         previous-operations (set (:operation-ids previous-snapshot))
         current-operations (set (:operation-ids current-snapshot))]
     {:delta-id (str (random-uuid))
      :timestamp timestamp
      :from-fingerprint (:fingerprint previous-snapshot)
      :to-fingerprint (:fingerprint current-snapshot)
      :added-capability-ids (vec (set/difference current-capabilities previous-capabilities))
      :removed-capability-ids (vec (set/difference previous-capabilities current-capabilities))
      :added-operations (vec (set/difference current-operations previous-operations))
      :removed-operations (vec (set/difference previous-operations current-operations))})))