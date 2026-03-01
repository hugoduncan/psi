(ns psi.introspection.core-test
  "Tests for the introspection component.

   All tests use create-context (Nullable pattern) — fully isolated, no
   shared atoms, no cleanup fixtures required."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.introspection.core :as introspection]
   [psi.engine.core :as engine]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Helpers
;; ─────────────────────────────────────────────────────────────────────────────

(defn- bootstrapped-ctx
  "Create a fully isolated introspection context with engine bootstrapped
   and introspection resolvers registered."
  []
  (let [ctx (introspection/create-context)]
    (engine/bootstrap-system-in! (:engine-ctx ctx))
    (introspection/register-resolvers-in! ctx)
    ctx))

;; ─────────────────────────────────────────────────────────────────────────────
;; Context isolation
;; ─────────────────────────────────────────────────────────────────────────────

(deftest context-isolation-test
  (testing "Two introspection contexts are fully independent"
    (let [ctx-a (bootstrapped-ctx)
          ctx-b (introspection/create-context)]
      (introspection/register-resolvers-in! ctx-b)

      ;; ctx-a has bootstrapped system state; ctx-b does not
      (let [state-a (:psi.system/state (introspection/query-system-state-in ctx-a))
            state-b (:psi.system/state (introspection/query-system-state-in ctx-b))]
        (is (some? state-a) "ctx-a should have system state after bootstrap")
        (is (nil? state-b)  "ctx-b should have no system state (never bootstrapped)")))))

;; ─────────────────────────────────────────────────────────────────────────────
;; System state introspection
;; ─────────────────────────────────────────────────────────────────────────────

(deftest query-system-state-test
  (testing "System state is queryable via EQL after bootstrap"
    (let [ctx    (bootstrapped-ctx)
          result (introspection/query-system-state-in ctx)]

      (testing ":psi.system/state is present"
        (is (map? (:psi.system/state result))))

      (testing ":psi.system/mode is a keyword"
        (is (keyword? (:psi.system/mode result))))

      (testing ":psi.system/evolution-stage is :integrating after graph emergence"
        (is (= :integrating (:psi.system/evolution-stage result))))

      (testing ":psi.system/readiness contains expected keys"
        (let [readiness (:psi.system/readiness result)]
          (is (map? readiness))
          (is (contains? readiness :engine-ready))
          (is (contains? readiness :query-ready))))

      (testing "has-substrate is true after bootstrap"
        (is (true? (:psi.system/has-substrate result))))

      (testing "readiness includes derived :graph-ready true"
        (is (true? (get-in result [:psi.system/readiness :graph-ready]))))

      (testing "is-ai-complete is false at integrating stage"
        (is (false? (:psi.system/is-ai-complete result)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Engine enumeration
;; ─────────────────────────────────────────────────────────────────────────────

(deftest query-all-engines-test
  (testing "All engines are visible via EQL"
    (let [ctx    (bootstrapped-ctx)
          result (introspection/query-all-engines-in ctx)]

      (testing ":psi.engine/engine-count is 1 after bootstrap"
        (is (= 1 (:psi.engine/engine-count result))))

      (testing ":psi.engine/all-engines contains main-engine"
        (is (contains? (:psi.engine/all-engines result) "main-engine"))))))

(deftest query-engine-detail-test
  (testing "Single-engine detail is queryable"
    (let [ctx    (bootstrapped-ctx)
          result (introspection/query-engine-detail-in ctx "main-engine")]

      (testing ":psi.engine/status is a keyword"
        (is (keyword? (:psi.engine/status result))))

      (testing ":psi.engine/active-states is a set"
        (is (set? (:psi.engine/active-states result))))

      (testing ":psi.engine/diagnostics is a map"
        (is (map? (:psi.engine/diagnostics result)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Transition history
;; ─────────────────────────────────────────────────────────────────────────────

(deftest query-transitions-test
  (testing "State transitions are captured and queryable"
    (let [ctx        (bootstrapped-ctx)
          engine-ctx (:engine-ctx ctx)]

      ;; Fire some transitions through the engine
      (engine/trigger-engine-event-in! engine-ctx "main-engine" :configuration-start)
      (engine/trigger-engine-event-in! engine-ctx "main-engine" :configuration-complete)

      (let [result (introspection/query-transitions-in ctx)]

        (testing ":psi.engine/transition-count reflects fired transitions"
          (is (pos? (:psi.engine/transition-count result))))

        (testing ":psi.engine/transitions is a sequence"
          (is (sequential? (:psi.engine/transitions result))))

        (testing "each transition has expected keys"
          (let [t (first (:psi.engine/transitions result))]
            (is (contains? t :engine-id))
            (is (contains? t :from-state))
            (is (contains? t :to-state))
            (is (contains? t :trigger))
            (is (contains? t :timestamp))))))))

(deftest query-recent-transitions-test
  (testing ":psi.engine/recent-transitions returns newest-first"
    (let [ctx        (bootstrapped-ctx)
          engine-ctx (:engine-ctx ctx)]

      (engine/trigger-engine-event-in! engine-ctx "main-engine" :configuration-start)
      (engine/trigger-engine-event-in! engine-ctx "main-engine" :configuration-complete)

      (let [result  (introspection/query-recent-transitions-in ctx)
            recents (:psi.engine/recent-transitions result)]

        (is (sequential? recents))
        (is (pos? (count recents)))

        (testing "most recent is last-fired"
          ;; engine stores (str event), so keyword becomes ":configuration-complete"
          (is (= ":configuration-complete" (:trigger (first recents)))))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Query graph introspection
;; ─────────────────────────────────────────────────────────────────────────────

(deftest query-graph-summary-test
  (testing "Query graph statistics are queryable"
    (let [ctx     (bootstrapped-ctx)
          result  (introspection/query-graph-summary-in ctx)
          result2 (introspection/query-graph-summary-in ctx)]

      (testing ":psi.graph/resolver-count includes introspection resolvers"
        (is (pos? (:psi.graph/resolver-count result))))

      (testing ":psi.graph/resolver-syms is a set of qualified symbols"
        (let [syms (:psi.graph/resolver-syms result)]
          (is (set? syms))
          (is (every? qualified-symbol? syms))))

      (testing ":psi.graph/env-built is true"
        (is (true? (:psi.graph/env-built result))))

      (testing "Step 7 startup domains are registered in same graph"
        (let [syms (:psi.graph/resolver-syms result)]
          (is (contains? syms 'psi.ai.core/ai-model-list-resolver)
              "AI resolver should be present")
          (is (contains? syms 'psi.history.resolvers/git-repo-status)
              "History resolver should be present")
          (is (contains? syms 'psi.introspection.resolvers/query-graph-summary)
              "Introspection resolver should be present")))

      (testing ":psi.graph/mutation-count is a non-negative integer"
        (is (nat-int? (:psi.graph/mutation-count result))))

      (testing "Step 7 capability graph attrs are exposed"
        (is (vector? (:psi.graph/nodes result)))
        (is (vector? (:psi.graph/edges result)))
        (is (vector? (:psi.graph/capabilities result)))
        (is (vector? (:psi.graph/domain-coverage result))))

      (testing "domain coverage includes required domains"
        (let [domains (set (map :domain (:psi.graph/domain-coverage result)))]
          (is (contains? domains :ai))
          (is (contains? domains :history))
          (is (contains? domains :agent-session))
          (is (contains? domains :introspection))))

      (testing "repeated queries are deterministic for graph attrs"
        (is (= (:psi.graph/nodes result) (:psi.graph/nodes result2)))
        (is (= (:psi.graph/edges result) (:psi.graph/edges result2)))
        (is (= (:psi.graph/capabilities result) (:psi.graph/capabilities result2)))
        (is (= (:psi.graph/domain-coverage result) (:psi.graph/domain-coverage result2)))))))

(deftest graph-self-describes-test
  (testing "Introspection resolvers appear in the graph summary"
    (let [ctx    (bootstrapped-ctx)
          result (introspection/query-graph-summary-in ctx)
          syms   (:psi.graph/resolver-syms result)]

      (is (contains? syms 'psi.introspection.resolvers/engine-system-state)
          "engine-system-state resolver should be listed")
      (is (contains? syms 'psi.introspection.resolvers/engine-transitions)
          "engine-transitions resolver should be listed")
      (is (contains? syms 'psi.introspection.resolvers/query-graph-summary)
          "query-graph-summary resolver should be listed"))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Derived system properties
;; ─────────────────────────────────────────────────────────────────────────────

(deftest derived-properties-test
  (testing "Derived system properties reflect Step 7 readiness reconciliation"
    (let [ctx        (bootstrapped-ctx)
          engine-ctx (:engine-ctx ctx)]

      ;; Bootstrapped introspection registration derives query+graph readiness.
      (let [r1 (introspection/query-system-state-in ctx)]
        (is (true? (:psi.system/has-interface r1))
            "Has interface when engine + query both ready")
        (is (true? (get-in r1 [:psi.system/readiness :graph-ready]))
            "graph-ready should be true when graph has nodes+edges")
        (is (= :integrating (:psi.system/evolution-stage r1))
            "evolution stage should be integrating when graph emerges"))

      ;; Negative path: clear readiness flags and set emerging semantics.
      (engine/update-system-component-in! engine-ctx :query-ready false)
      (engine/update-system-component-in! engine-ctx :graph-ready false)
      (engine/set-evolution-stage-in! engine-ctx :developing)

      (let [r2 (introspection/query-system-state-in ctx)]
        (is (false? (:psi.system/has-interface r2))
            "No interface after query-ready is cleared")
        (is (false? (get-in r2 [:psi.system/readiness :graph-ready]))
            "graph-ready should be false when gate does not hold")
        (is (= :developing (:psi.system/evolution-stage r2))
            "evolution stage should reflect emerging semantics")))))
