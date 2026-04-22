(ns psi.introspection.graph-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [psi.engine.core :as engine]
   [psi.graph.analysis :as graph]
   [psi.introspection.core :as introspection]
   [psi.agent-session.core :as agent-session]
   [psi.query.registry :as registry]))

(defn- operation-metadata
  []
  (let [session-ctx       (agent-session/create-context {:persist? false
                                                         :cwd (str (java.nio.file.Files/createTempDirectory
                                                                    "psi-introspection-graph-test-"
                                                                    (make-array java.nio.file.attribute.FileAttribute 0)))})
        _                 (agent-session/new-session-in! session-ctx nil {})
        ctx               (introspection/create-context {:agent-session-ctx session-ctx})
        qctx              (:query-ctx ctx)]
    (engine/bootstrap-system-in! (:engine-ctx ctx))
    (introspection/register-resolvers-in! ctx)
    {:resolver-ops (mapv #(graph/operation->metadata :resolver %)
                         (registry/all-resolvers-in (:reg qctx)))
     :mutation-ops (mapv #(graph/operation->metadata :mutation %)
                         (registry/all-mutations-in (:reg qctx)))}))

(def ^:private operation-metadata-fixture
  (delay (operation-metadata)))

(defn- cached-operation-metadata
  []
  @operation-metadata-fixture)

(deftest classify-domain-test
  (testing "domain classification maps required Step 7 domains"
    (is (= :ai (graph/classify-domain 'psi.ai.core/ai-model-resolver)))
    (is (= :history (graph/classify-domain 'psi.history.resolvers/git-log)))
    (is (= :introspection (graph/classify-domain 'psi.introspection.resolvers/query-graph-summary)))
    (is (= :agent-session (graph/classify-domain 'psi.agent-session.core/add-prompt-template)))
    (is (= :agent-session (graph/classify-domain 'psi.extension/add-prompt-template)))))

(deftest derive-capability-graph-deterministic-shape-test
  (testing "capability graph derivation is deterministic and Step 7-constrained"
    (let [ops    (cached-operation-metadata)
          g1     (graph/derive-capability-graph ops)
          g2     (graph/derive-capability-graph ops)
          types  (set (map :type (:nodes g1)))]

      (is (= g1 g2) "graph derivation should be deterministic for same inputs")

      (testing "node types are limited to resolver/mutation/capability"
        (is (= #{:resolver :mutation :capability} types)))

      (testing "at least one edge includes non-nil :attribute metadata"
        (is (some :attribute (:edges g1))))

      (testing "mutation-derived operations have deferred sideEffects=nil"
        (let [mutation-ops (filter #(= :mutation (:type %)) (:domain-operations g1))]
          (is (seq mutation-ops))
          (is (every? #(contains? % :sideEffects) mutation-ops))
          (is (every? #(nil? (:sideEffects %)) mutation-ops)))))))

(deftest domain-coverage-includes-required-domains-test
  (testing "domain coverage always includes required Step 7 domains"
    (let [coverage (-> (cached-operation-metadata)
                       (graph/derive-capability-graph)
                       :domain-coverage)
          by-domain (into {} (map (juxt :domain identity) coverage))]
      (is (contains? by-domain :ai))
      (is (contains? by-domain :history))
      (is (contains? by-domain :agent-session))
      (is (contains? by-domain :introspection)))))

(deftest derive-capability-graph-node-ids-unique-test
  (testing "derived graph node ids are unique"
    (let [node-ids (->> (cached-operation-metadata)
                        graph/derive-capability-graph
                        :nodes
                        (map :id))]
      (is (= (count node-ids) (count (distinct node-ids)))))))

(deftest derive-capability-graph-edge-endpoints-exist-test
  (testing "derived graph edges always point at existing node ids"
    (let [{:keys [nodes edges]} (graph/derive-capability-graph (cached-operation-metadata))
          node-ids              (set (map :id nodes))]
      (is (every? #(contains? node-ids (:from %)) edges))
      (is (every? #(contains? node-ids (:to %)) edges)))))

(deftest derive-capability-graph-operation-symbols-have-nodes-test
  (testing "derived operation symbols are represented by operation nodes"
    (let [{:keys [resolver-ops mutation-ops]} (cached-operation-metadata)
          {:keys [nodes]}                     (graph/derive-capability-graph (cached-operation-metadata))
          resolver-syms                      (set (map :symbol resolver-ops))
          mutation-syms                      (set (map :symbol mutation-ops))
          resolver-node-syms                 (->> nodes
                                                  (filter #(= :resolver (:type %)))
                                                  (map :symbol)
                                                  set)
          mutation-node-syms                 (->> nodes
                                                  (filter #(= :mutation (:type %)))
                                                  (map :symbol)
                                                  set)]
      (is (= resolver-syms resolver-node-syms))
      (is (= mutation-syms mutation-node-syms)))))

(deftest derive-capability-graph-capabilities-align-with-domain-coverage-test
  (testing "derived capabilities align with domain coverage for present domains"
    (let [{:keys [capabilities domain-coverage]} (graph/derive-capability-graph (cached-operation-metadata))
          coverage-map                           (into {} (map (juxt :domain identity) domain-coverage))]
      (doseq [{:keys [domain operation-count resolver-count mutation-count]} capabilities
              :let [coverage (get coverage-map domain)]]
        (is coverage (str "expected domain-coverage entry for capability domain " domain))
        (is (= operation-count (:operation-count coverage)))
        (is (= resolver-count (:resolver-count coverage)))
        (is (= mutation-count (:mutation-count coverage)))))))

(deftest derive-resolver-index-test
  (testing "resolver-index contains only psi.* attrs"
    (let [index (graph/derive-resolver-index (:resolver-ops (cached-operation-metadata)))]
      (is (seq index))
      (is (every? symbol? (map :psi.resolver/sym index)))
      (is (every? vector? (map :psi.resolver/input index)))
      (is (every? vector? (map :psi.resolver/output index)))

      (testing "no internal seed keys in input or output"
        (let [all-inputs  (mapcat :psi.resolver/input index)
              all-outputs (mapcat :psi.resolver/output index)
              seed-keys   #{:psi/agent-session-ctx :psi/memory-ctx
                            :psi/recursion-ctx :psi/engine-ctx}]
          (is (not-any? #(contains? seed-keys %) all-inputs))
          (is (not-any? #(and (keyword? %) (contains? seed-keys %)) all-outputs))))

      (testing "all keyword attrs in psi.* namespace"
        (let [flat-attrs (fn [io-vec]
                           (mapcat (fn [x]
                                     (cond
                                       (keyword? x) [x]
                                       (map? x) (concat (keys x) (mapcat val x))
                                       :else []))
                                   io-vec))
              all-attrs  (->> index
                              (mapcat (fn [r]
                                        (concat (flat-attrs (:psi.resolver/input r))
                                                (flat-attrs (:psi.resolver/output r)))))
                              (filter keyword?))]
          (is (every? #(str/starts-with? (namespace %) "psi.") all-attrs))))

      (testing "sorted by sym"
        (let [syms (map (comp str :psi.resolver/sym) index)]
          (is (= syms (sort syms)))))

      (testing "join maps preserved in output"
        (let [join-outputs (->> index
                                (mapcat :psi.resolver/output)
                                (filter map?))]
          (is (seq join-outputs) "expected at least one join map in resolver outputs")))))

  (testing "context-sessions resolver output contains session-info join"
    (let [index  (graph/derive-resolver-index (:resolver-ops (cached-operation-metadata)))
          entry  (first (filter #(= 'psi.agent-session.resolvers.session/agent-session-identity
                                    (:psi.resolver/sym %))
                                index))
          joins  (filter map? (:psi.resolver/output entry))
          cs-key :psi.agent-session/context-sessions]
      (is entry "agent-session-identity resolver should be in index")
      (is (some #(contains? % cs-key) joins))
      (is (some #(contains? (set (get % cs-key [])) :psi.session-info/id) joins)))))

(deftest derive-attr-index-test
  (testing "attr-index inverts resolver-index correctly"
    (let [resolver-index (graph/derive-resolver-index (:resolver-ops (cached-operation-metadata)))
          attr-index     (graph/derive-attr-index resolver-index)]
      (is (map? attr-index))
      (is (seq attr-index))

      (testing "all keys are psi.* keywords"
        (is (every? #(clojure.string/starts-with? (namespace %) "psi.") (keys attr-index))))

      (testing "each entry has produced-by and reachable-via"
        (doseq [[_ v] attr-index]
          (is (vector? (:psi.attr/produced-by v)))
          (is (map? (:psi.attr/reachable-via v)))))

      (testing "psi.session-info/id is indexed and reachable via context-sessions"
        (let [entry (get attr-index :psi.session-info/id)]
          (is entry ":psi.session-info/id should appear in attr-index")
          (is (contains? (:psi.attr/reachable-via entry)
                         :psi.agent-session/context-sessions))))

      (testing "flat attrs have empty reachable-via"
        (let [flat-entry (get attr-index :psi.agent-session/session-name)]
          (is flat-entry)
          (is (empty? (:psi.attr/reachable-via flat-entry))))))))
