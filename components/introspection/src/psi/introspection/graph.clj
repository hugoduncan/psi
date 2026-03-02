(ns psi.introspection.graph
  "Pure Step 7 capability-graph derivation helpers.

   The functions in this namespace operate on operation metadata and return
   deterministic graph shapes for introspection resolvers.

   Node types in Step 7 are restricted to:
   - :resolver
   - :mutation
   - :capability

   Attribute links are represented as edge metadata (:attribute), not nodes.
   Mutation side effects are deferred in Step 7 and are represented as nil.
  "
  (:require
   [clojure.string :as str]
   [com.wsscode.pathom3.connect.operation :as pco]))

(def required-domains
  "Required Step 7 domains for graph emergence."
  [:ai :history :agent-session :introspection])

(defn classify-domain
  "Classify an operation symbol into a Step 7 domain keyword."
  [op-sym]
  (let [n (namespace op-sym)]
    (cond
      (str/starts-with? n "psi.ai") :ai
      (str/starts-with? n "psi.history") :history
      (str/starts-with? n "psi.introspection") :introspection
      (str/starts-with? n "psi.memory") :memory
      (str/starts-with? n "psi.recursion") :recursion
      (or (str/starts-with? n "psi.agent-session")
          (str/starts-with? n "psi.extension")) :agent-session
      :else :unknown)))

(defn operation->metadata
  "Extract normalized metadata from a Pathom operation.

   Returns map keys:
   - :op-type   (:resolver | :mutation)
   - :symbol    qualified symbol
   - :input     vector
   - :output    vector
   - :params    vector"
  [op-type operation]
  (let [cfg (pco/operation-config operation)]
    {:op-type op-type
     :symbol  (::pco/op-name cfg)
     :input   (vec (or (::pco/input cfg) []))
     :output  (vec (or (::pco/output cfg) []))
     :params  (vec (or (::pco/params cfg) []))}))

(defn operation-node-id
  [{:keys [op-type symbol]}]
  (str (name op-type) ":" symbol))

(defn capability-node-id
  [domain]
  (str "capability:" (name domain)))

(defn domain-operation
  "Build a normalized domain operation record from operation metadata."
  [{:keys [op-type symbol input output params] :as op}]
  (let [domain      (classify-domain symbol)
        io-attrs    (vec (distinct (concat input output params)))
        operation-id (operation-node-id op)]
    {:id          operation-id
     :symbol      symbol
     :domain      domain
     :type        op-type
     :io          {:input input :output output :params params}
     :attributes  io-attrs
     :sideEffects (when (= op-type :mutation) nil)}))

(defn derive-domain-operations
  "Derive sorted domain operation records from resolver/mutation metadata."
  [{:keys [resolver-ops mutation-ops]}]
  (->> (concat resolver-ops mutation-ops)
       (map domain-operation)
       (sort-by (comp str :symbol))
       vec))

(defn derive-capabilities
  "Derive capability summaries grouped by domain."
  [domain-ops]
  (->> domain-ops
       (group-by :domain)
       (map (fn [[domain ops]]
              {:id               (capability-node-id domain)
               :domain           domain
               :operation-count  (count ops)
               :resolver-count   (count (filter #(= :resolver (:type %)) ops))
               :mutation-count   (count (filter #(= :mutation (:type %)) ops))
               :operation-symbols (vec (map :symbol ops))
               :attributes       (vec (distinct (mapcat :attributes ops)))}))
       (sort-by (comp str :domain))
       vec))

(defn derive-nodes
  "Derive graph nodes (resolver/mutation/capability only)."
  [domain-ops capabilities]
  (vec
   (concat
    (map (fn [{:keys [id symbol domain type sideEffects]}]
           (cond-> {:id id :type type :symbol symbol :domain domain}
             (= type :mutation) (assoc :sideEffects sideEffects)))
         domain-ops)
    (map (fn [{:keys [id domain operation-count]}]
           {:id id :type :capability :domain domain :operation-count operation-count})
         capabilities))))

(defn derive-edges
  "Derive operation→capability edges with :attribute edge metadata.

   For operations with no IO attrs, emits one edge with nil :attribute so
   graph linkage is still explicit."
  [domain-ops]
  (->> domain-ops
       (mapcat (fn [{:keys [id domain attributes]}]
                 (let [cap-id (capability-node-id domain)
                       attrs  (seq attributes)]
                   (if attrs
                     (map (fn [attr]
                            {:from id :to cap-id :attribute attr})
                          attrs)
                     [{:from id :to cap-id :attribute nil}]))))
       vec))

(defn derive-domain-coverage
  "Derive deterministic domain coverage summary.

   Includes required Step 7 domains even when they have zero operations."
  [domain-ops]
  (let [grouped        (group-by :domain domain-ops)
        present-domains (set (keys grouped))
        ordered-domains (vec (concat required-domains
                                     (sort (remove (set required-domains)
                                                   present-domains))))]
    (mapv (fn [domain]
            (let [ops (get grouped domain [])]
              {:domain          domain
               :resolver-count  (count (filter #(= :resolver (:type %)) ops))
               :mutation-count  (count (filter #(= :mutation (:type %)) ops))
               :operation-count (count ops)}))
          ordered-domains)))

(defn derive-capability-graph
  "Derive complete Step 7 capability graph structure from operation metadata.

   Input map:
   {:resolver-ops [{:op-type :resolver ...}]
    :mutation-ops [{:op-type :mutation ...}]}

   Returns:
   {:domain-operations [...]
    :nodes [...]
    :edges [...]
    :capabilities [...]
    :domain-coverage [...]}"
  [{:keys [resolver-ops mutation-ops] :as operation-metadata}]
  (let [domain-ops    (derive-domain-operations operation-metadata)
        capabilities  (derive-capabilities domain-ops)]
    {:domain-operations domain-ops
     :nodes             (derive-nodes domain-ops capabilities)
     :edges             (derive-edges domain-ops)
     :capabilities      capabilities
     :domain-coverage   (derive-domain-coverage domain-ops)
     :operation-count   (+ (count resolver-ops) (count mutation-ops))}))
