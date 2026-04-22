(ns psi.agent-session.graph-surface-test
  "Focused contract tests for the session-root graph discovery surface.

   Scope:
   - :psi.graph/* queryability and shape
   - root seed and root-queryable advertisement
   - graph introspection visibility for bridged attrs
   - graph summary coherence invariants

   Non-scope:
   - concrete resolver business behavior; see resolvers_test.clj

   This namespace exists to keep the graph surface easy to scan and to make
   discovery-contract regressions obvious."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]))

(def ^:private shared-ctx
  (delay (first (test-support/create-test-session))))

(defn- q
  "Run EQL query against a shared session context."
  [eql]
  (let [session-id (some-> (ss/list-context-sessions-in @shared-ctx)
                           first
                           :session-id)]
    (session/query-in @shared-ctx session-id eql)))

(defn- qe
  "Run EQL query with extra entity seed against a shared session context."
  [eql extra-entity]
  (let [session-id (some-> (ss/list-context-sessions-in @shared-ctx)
                           first
                           :session-id)]
    (session/query-in @shared-ctx session-id eql extra-entity)))

(defn- join-attr?
  [x]
  (and (map? x) (= 1 (count x)) (keyword? (ffirst x))))

(defn- graph-attr-present?
  [attrs k]
  (or (some #(= k %) attrs)
      (some #(and (join-attr? %) (contains? % k)) attrs)))

;; Graph contract helpers mirror the public discovery surface:
;; - capabilities are rich summaries for domains present in the graph
;; - domain-coverage is normalized and includes required zero-count domains
;; - edges are operation->capability membership edges annotated by :attribute
;; - edge :attribute may be a keyword, a join map, or nil
;; - root-queryable attrs come from resolver-only fixed-point reachability

(defn- assert-graph-summary-map
  [m]
  (is (and (map? m)
           (contains? m :domain)
           (contains? m :operation-count)
           (contains? m :resolver-count)
           (contains? m :mutation-count)
           (keyword? (:domain m))
           (nat-int? (:operation-count m))
           (nat-int? (:resolver-count m))
           (nat-int? (:mutation-count m)))
      (str "expected graph summary map, got: " (pr-str m))))

(defn- assert-graph-node
  [node]
  (is (and (map? node)
           (contains? node :id)
           (contains? node :type)
           (contains? node :domain)
           (#{:resolver :mutation :capability} (:type node)))
      (str "expected graph node map, got: " (pr-str node))))

(defn- assert-graph-edge
  [edge]
  (is (and (map? edge)
           (contains? edge :from)
           (contains? edge :to)
           (contains? edge :attribute)
           (or (nil? (:attribute edge))
               (keyword? (:attribute edge))
               (join-attr? (:attribute edge))))
      (str "expected graph edge map, got: " (pr-str edge))))

(def canonical-graph-root-attrs
  #{:psi.graph/root-seeds
    :psi.graph/root-queryable-attrs
    :psi.graph/resolver-count
    :psi.graph/mutation-count
    :psi.graph/resolver-syms
    :psi.graph/mutation-syms
    :psi.graph/env-built
    :psi.graph/nodes
    :psi.graph/edges
    :psi.graph/capabilities
    :psi.graph/domain-coverage
    :psi.graph/resolver-index
    :psi.graph/attr-index})

(defn- assert-canonical-graph-root-attrs
  [root-attrs]
  (let [root-attrs (set root-attrs)
        missing    (seq (remove root-attrs canonical-graph-root-attrs))]
    (is (nil? missing)
        (str "expected canonical graph root attrs to be advertised, missing: "
             (pr-str (vec missing))))))

(deftest context-session-graph-introspection-test
  (testing "context session attrs are discoverable in graph root attrs and edges"
    (let [result     (q [:psi.graph/root-queryable-attrs :psi.graph/edges])
          root-attrs (:psi.graph/root-queryable-attrs result)
          edge-attrs (keep :attribute (:psi.graph/edges result))]
      (is (graph-attr-present? root-attrs :psi.agent-session/context-session-count))
      (is (graph-attr-present? edge-attrs :psi.agent-session/context-session-count))
      (is (graph-attr-present? edge-attrs :psi.agent-session/context-sessions)))))

(deftest background-jobs-graph-introspection-test
  (testing "background job attrs are discoverable in graph introspection"
    (let [result (q [:psi.graph/root-queryable-attrs
                     :psi.graph/edges])
          root-attrs (set (:psi.graph/root-queryable-attrs result))
          edge-attrs (set (keep :attribute (:psi.graph/edges result)))]
      (is (contains? root-attrs :psi.agent-session/background-job-count))
      (is (contains? root-attrs :psi.agent-session/background-job-statuses))
      (is (contains? root-attrs :psi.agent-session/background-jobs))
      (is (contains? edge-attrs :psi.agent-session/background-job-count))
      (is (contains? edge-attrs :psi.agent-session/background-job-statuses))
      (is (contains? edge-attrs :psi.agent-session/background-jobs)))))

(deftest graph-bridge-resolver-count-test
  (testing "resolver-count is a non-negative integer"
    (let [result (q [:psi.graph/resolver-count])]
      (is (integer? (:psi.graph/resolver-count result)))
      (is (nat-int? (:psi.graph/resolver-count result))))))

(deftest graph-bridge-mutation-count-test
  (testing "mutation-count is a non-negative integer"
    (let [result (q [:psi.graph/mutation-count])]
      (is (integer? (:psi.graph/mutation-count result)))
      (is (nat-int? (:psi.graph/mutation-count result))))))

(deftest graph-bridge-resolver-syms-test
  (testing "resolver-syms is a set of symbols"
    (let [result (q [:psi.graph/resolver-syms])
          syms   (:psi.graph/resolver-syms result)]
      (is (set? syms))
      (is (every? symbol? syms))
      (is (contains? syms 'psi.agent-session.resolvers.session/agent-session-identity)))))

(deftest graph-bridge-mutation-syms-test
  (testing "mutation-syms is a set of symbols"
    (let [result (q [:psi.graph/mutation-syms])
          syms   (:psi.graph/mutation-syms result)]
      (is (set? syms))
      (is (every? symbol? syms)))))

(deftest graph-bridge-env-built-test
  (testing "env-built is a boolean"
    (let [result (q [:psi.graph/env-built])]
      (is (boolean? (:psi.graph/env-built result))))))

(deftest graph-bridge-nodes-test
  (testing "nodes is a vector of graph node maps"
    (let [result (q [:psi.graph/nodes])
          nodes  (:psi.graph/nodes result)]
      (is (vector? nodes))
      (doseq [node nodes]
        (assert-graph-node node)))))

(deftest graph-bridge-edges-test
  (testing "edges are operation->capability membership edges with annotated attributes"
    (let [result (q [:psi.graph/edges])
          edges  (:psi.graph/edges result)]
      (is (vector? edges))
      (doseq [edge edges]
        (assert-graph-edge edge)))))

(deftest graph-bridge-capabilities-test
  (testing "capabilities is a non-empty vector of rich summaries for domains present in the graph"
    (let [result       (q [:psi.graph/capabilities])
          capabilities (:psi.graph/capabilities result)]
      (is (vector? capabilities))
      (is (seq capabilities))
      (doseq [capability capabilities]
        (assert-graph-summary-map capability)))))

(deftest graph-bridge-domain-coverage-test
  (testing "domain-coverage is normalized and includes required Step 7 domains even when empty"
    (let [result   (q [:psi.graph/domain-coverage])
          coverage (:psi.graph/domain-coverage result)
          domains  (set (map :domain coverage))]
      (is (vector? coverage))
      (is (seq coverage))
      (doseq [domain-summary coverage]
        (assert-graph-summary-map domain-summary))
      (is (contains? domains :ai))
      (is (contains? domains :history))
      (is (contains? domains :agent-session))
      (is (contains? domains :introspection)))))

(deftest graph-bridge-all-nine-attrs-test
  (testing "all 9 required Step 7 graph attrs succeed in one query"
    (let [result (q [:psi.graph/resolver-count
                     :psi.graph/mutation-count
                     :psi.graph/resolver-syms
                     :psi.graph/mutation-syms
                     :psi.graph/env-built
                     :psi.graph/nodes
                     :psi.graph/edges
                     :psi.graph/capabilities
                     :psi.graph/domain-coverage])]
      (is (integer? (:psi.graph/resolver-count result)))
      (is (integer? (:psi.graph/mutation-count result)))
      (is (set? (:psi.graph/resolver-syms result)))
      (is (set? (:psi.graph/mutation-syms result)))
      (is (boolean? (:psi.graph/env-built result)))
      (is (vector? (:psi.graph/nodes result)))
      (is (vector? (:psi.graph/edges result)))
      (is (vector? (:psi.graph/capabilities result)))
      (is (vector? (:psi.graph/domain-coverage result)))
      (is (= (:psi.graph/resolver-count result)
             (count (:psi.graph/resolver-syms result))))
      (is (= (:psi.graph/mutation-count result)
             (count (:psi.graph/mutation-syms result))))
      (is (seq (:psi.graph/capabilities result)))
      (is (seq (:psi.graph/domain-coverage result))))))

(deftest graph-bridge-node-ids-are-unique-test
  (testing "graph node ids are unique"
    (let [nodes (:psi.graph/nodes (q [:psi.graph/nodes]))
          ids   (map :id nodes)]
      (is (= (count ids) (count (distinct ids)))))))

(deftest graph-bridge-edge-endpoints-exist-test
  (testing "every graph edge endpoint refers to an existing node id"
    (let [result    (q [:psi.graph/nodes :psi.graph/edges])
          node-ids  (set (map :id (:psi.graph/nodes result)))
          edges     (:psi.graph/edges result)]
      (is (every? #(contains? node-ids (:from %)) edges))
      (is (every? #(contains? node-ids (:to %)) edges)))))

(deftest graph-bridge-operation-symbols-have-nodes-test
  (testing "every resolver/mutation symbol has a corresponding operation node"
    (let [result         (q [:psi.graph/nodes
                             :psi.graph/resolver-syms
                             :psi.graph/mutation-syms])
          nodes          (:psi.graph/nodes result)
          resolver-nodes (->> nodes
                              (filter #(= :resolver (:type %)))
                              (map :symbol)
                              set)
          mutation-nodes (->> nodes
                              (filter #(= :mutation (:type %)))
                              (map :symbol)
                              set)]
      (is (= (:psi.graph/resolver-syms result) resolver-nodes))
      (is (= (:psi.graph/mutation-syms result) mutation-nodes)))))

(deftest graph-bridge-capabilities-align-with-domain-coverage-test
  (testing "capabilities align with domain-coverage for domains present in the graph"
    (let [result       (q [:psi.graph/capabilities :psi.graph/domain-coverage])
          capabilities (:psi.graph/capabilities result)
          coverage-map (into {} (map (juxt :domain identity)
                                     (:psi.graph/domain-coverage result)))]
      (doseq [{:keys [domain operation-count resolver-count mutation-count]} capabilities
              :let [coverage (get coverage-map domain)]]
        (is coverage (str "expected domain-coverage entry for capability domain " domain))
        (is (= operation-count (:operation-count coverage)))
        (is (= resolver-count (:resolver-count coverage)))
        (is (= mutation-count (:mutation-count coverage)))))))

(deftest root-queryable-attrs-contract-test
  (testing "every advertised root-queryable attr resolves from session root via resolver-only reachability"
    (let [meta       (q [:psi.graph/root-seeds :psi.graph/root-queryable-attrs])
          root-seeds (:psi.graph/root-seeds meta)
          root-attrs (:psi.graph/root-queryable-attrs meta)]
      (is (vector? root-seeds))
      (is (every? keyword? root-seeds))
      (is (= [:psi/agent-session-ctx :psi.agent-session/session-id :psi/memory-ctx :psi/recursion-ctx :psi/engine-ctx]
             root-seeds))
      (is (vector? root-attrs))
      (is (seq root-attrs))
      (is (every? keyword? root-attrs))
      (assert-canonical-graph-root-attrs root-attrs)
      (let [result (q (vec root-attrs))]
        (doseq [attr root-attrs]
          (is (contains? result attr)
              (str "expected root-queryable attr to resolve: " attr)))))))

(deftest root-queryable-attrs-clean-contract-test
  (testing "root-queryable attrs do not expose legacy/compat names"
    (let [root-attrs (set (:psi.graph/root-queryable-attrs
                           (q [:psi.graph/root-queryable-attrs])))]
      (is (not-any? #(re-find #"-compat$" (name %)) root-attrs))
      (is (not (contains? root-attrs :psi.agent-session/current-request-shape)))
      (is (not (contains? root-attrs :psi.agent-session/api-error-list)))
      (is (not (contains? root-attrs :psi.memory/memory-state)))
      (is (not (contains? root-attrs :psi.memory/memory-store-state)))
      (is (not (contains? root-attrs :psi.memory/memory-context-state)))
      (is (not (contains? root-attrs :psi.history/git-repo-status)))
      (is (not (contains? root-attrs :psi.history/git-repo-commits)))
      (is (not (contains? root-attrs :psi.history/git-learning-commits)))
      (is (not (contains? root-attrs :psi.introspection/query-graph-summary)))
      (is (not (contains? root-attrs :psi.introspection/engine-system-state))))))

(deftest graph-bridge-resolver-index-test
  (testing "resolver-index is queryable and has expected shape"
    (let [result (q [:psi.graph/resolver-index])
          index  (:psi.graph/resolver-index result)]
      (is (vector? index))
      (is (seq index))
      (doseq [entry index]
        (is (symbol? (:psi.resolver/sym entry)))
        (is (vector? (:psi.resolver/input entry)))
        (is (vector? (:psi.resolver/output entry))))

      (testing "sorted by sym"
        (let [syms (map (comp str :psi.resolver/sym) index)]
          (is (= syms (sort syms)))))

      (testing "agent-session-identity entry has context-sessions join in output"
        (let [entry (first (filter #(= 'psi.agent-session.resolvers.session/agent-session-identity
                                       (:psi.resolver/sym %))
                                   index))
              joins (filter map? (:psi.resolver/output entry))]
          (is entry)
          (is (some #(contains? % :psi.agent-session/context-sessions) joins))
          (is (some #(some #{:psi.session-info/id}
                           (get % :psi.agent-session/context-sessions []))
                    joins)))))))

(deftest graph-bridge-attr-index-test
  (testing "attr-index is queryable and has expected shape"
    (let [result (q [:psi.graph/attr-index])
          index  (:psi.graph/attr-index result)]
      (is (map? index))
      (is (seq index))

      (testing "all keys are psi.* keywords"
        (is (every? #(str/starts-with? (namespace %) "psi.") (keys index))))

      (testing "each entry has produced-by and reachable-via"
        (doseq [[_ v] index]
          (is (vector? (:psi.attr/produced-by v)))
          (is (map? (:psi.attr/reachable-via v)))))

      (testing "psi.session-info/id is reachable via context-sessions join"
        (let [entry (get index :psi.session-info/id)]
          (is entry)
          (is (contains? (:psi.attr/reachable-via entry)
                         :psi.agent-session/context-sessions))))

      (testing "flat attrs have empty reachable-via"
        (let [entry (get index :psi.agent-session/session-name)]
          (is entry)
          (is (empty? (:psi.attr/reachable-via entry))))))))

(deftest resolver-detail-test
  (testing "resolver-detail resolves I/O for a known sym"
    (let [result (qe [:psi.resolver/input :psi.resolver/output]
                     {:psi.resolver/sym 'psi.agent-session.resolvers.session/agent-session-identity})]
      (is (vector? (:psi.resolver/input result)))
      (is (vector? (:psi.resolver/output result)))
      (is (some #{:psi.agent-session/session-name} (:psi.resolver/output result)))
      (is (some #(and (map? %) (contains? % :psi.agent-session/context-sessions))
                (:psi.resolver/output result)))))

  (testing "resolver-detail returns nil attrs for unknown sym"
    (let [result (qe [:psi.resolver/input :psi.resolver/output]
                     {:psi.resolver/sym 'psi.no.such/resolver})]
      (is (nil? (:psi.resolver/input result)))
      (is (nil? (:psi.resolver/output result))))))

(deftest worktree-attr-discovery-and-query-contract-test
  (testing "worktree attrs are queryable from session root"
    (let [result (q [:git.worktree/list
                     :git.worktree/current
                     :git.worktree/count
                     :git.worktree/inside-repo?])]
      (is (contains? result :git.worktree/list))
      (is (contains? result :git.worktree/current))
      (is (contains? result :git.worktree/count))
      (is (contains? result :git.worktree/inside-repo?))
      (is (integer? (:git.worktree/count result))))))
