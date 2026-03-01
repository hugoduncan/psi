(ns psi.introspection.agent-session-test
  "Integration tests: agent-session resolvers wired through the
   introspection context into the query graph.

   All tests are isolated — they use create-context with explicit
   contexts and never touch global state."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.introspection.core :as introspection]
   [psi.agent-session.core :as session]
   [psi.engine.core :as engine]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Helpers
;; ─────────────────────────────────────────────────────────────────────────────

(defn- session-introspection-ctx
  "Create a fully isolated introspection context with a fresh agent-session
   context attached.  Resolvers are registered and engine is bootstrapped."
  []
  (let [session-ctx (session/create-context)
        ctx         (introspection/create-context {:agent-session-ctx session-ctx})]
    (engine/bootstrap-system-in! (:engine-ctx ctx))
    (introspection/register-resolvers-in! ctx)
    ctx))

;; ─────────────────────────────────────────────────────────────────────────────
;; Context creation
;; ─────────────────────────────────────────────────────────────────────────────

(deftest create-context-with-agent-session-test
  (testing "create-context accepts :agent-session-ctx option"
    (let [session-ctx (session/create-context)
          ctx         (introspection/create-context {:agent-session-ctx session-ctx})]
      (is (some? (:agent-session-ctx ctx))
          ":agent-session-ctx should be stored in context")))

  (testing "create-context without :agent-session-ctx stores nil"
    (let [ctx (introspection/create-context)]
      (is (nil? (:agent-session-ctx ctx)))))

  (testing "create-context still accepts empty map"
    (let [ctx (introspection/create-context {})]
      (is (some? (:engine-ctx ctx)))
      (is (some? (:query-ctx ctx))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; register-resolvers-in! with agent-session
;; ─────────────────────────────────────────────────────────────────────────────

(deftest register-resolvers-in!-with-session-test
  (testing "register-resolvers-in! registers agent-session resolvers/mutations when ctx has session"
    (let [session-ctx (session/create-context)
          ctx         (introspection/create-context {:agent-session-ctx session-ctx})]
      (introspection/register-resolvers-in! ctx)
      (let [result  (introspection/query-graph-summary-in ctx)
            r-syms  (:psi.graph/resolver-syms result)
            m-syms  (:psi.graph/mutation-syms result)]
        (is (true? (:psi.graph/env-built result))
            "graph env should be built after registration")
        (is (contains? r-syms 'psi.ai.core/ai-model-list-resolver)
            "AI resolver should appear in graph summary")
        (is (contains? r-syms 'psi.history.resolvers/git-repo-status)
            "History resolver should appear in graph summary")
        (is (contains? r-syms 'psi.agent-session.resolvers/agent-session-identity)
            "identity resolver should appear in graph summary")
        (is (contains? r-syms 'psi.agent-session.resolvers/agent-session-phase)
            "phase resolver should appear in graph summary")
        (is (contains? m-syms 'psi.extension/add-prompt-template)
            "startup prompt mutation should appear in graph summary"))))

  (testing "register-resolvers-in! skips agent-session resolvers when no session ctx"
    (let [ctx (introspection/create-context)]
      (introspection/register-resolvers-in! ctx)
      (let [result (introspection/query-graph-summary-in ctx)
            syms   (:psi.graph/resolver-syms result)]
        (is (not (contains? syms 'psi.agent-session.resolvers/agent-session-identity))
            "identity resolver should NOT appear when no session ctx provided")))))

;; ─────────────────────────────────────────────────────────────────────────────
;; query-agent-session-in
;; ─────────────────────────────────────────────────────────────────────────────

(deftest query-agent-session-in-test
  (testing "query-agent-session-in resolves :psi.agent-session/session-id"
    (let [ctx    (session-introspection-ctx)
          result (introspection/query-agent-session-in
                  ctx [:psi.agent-session/session-id])]
      (is (string? (:psi.agent-session/session-id result)))))

  (testing "query-agent-session-in also resolves :psi.memory/status"
    (let [ctx    (session-introspection-ctx)
          result (introspection/query-agent-session-in
                  ctx [:psi.memory/status])]
      (is (= :initializing (:psi.memory/status result)))))

  (testing "query-agent-session-in resolves phase and idle flag"
    (let [ctx    (session-introspection-ctx)
          result (introspection/query-agent-session-in
                  ctx [:psi.agent-session/phase
                       :psi.agent-session/is-idle])]
      (is (= :idle (:psi.agent-session/phase result)))
      (is (true? (:psi.agent-session/is-idle result)))))

  (testing "query-agent-session-in reflects model after set-model-in!"
    (let [ctx         (session-introspection-ctx)
          session-ctx (:agent-session-ctx ctx)
          model       {:provider "x" :id "test-model" :reasoning false}]
      (session/set-model-in! session-ctx model)
      (let [result (introspection/query-agent-session-in
                    ctx [:psi.agent-session/model])]
        (is (= model (:psi.agent-session/model result))))))

  (testing "query-agent-session-in throws when no agent-session-ctx"
    (let [ctx (introspection/create-context)]
      (introspection/register-resolvers-in! ctx)
      (is (thrown? Exception
                   (introspection/query-agent-session-in
                    ctx [:psi.agent-session/session-id]))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Cross-graph query
;; ─────────────────────────────────────────────────────────────────────────────

(deftest cross-graph-query-test
  (testing "engine state and agent-session state queryable from same context"
    (let [ctx    (session-introspection-ctx)
          engine (:psi.system/has-substrate
                  (introspection/query-system-state-in ctx))
          phase  (:psi.agent-session/phase
                  (introspection/query-agent-session-in
                   ctx [:psi.agent-session/phase]))]
      (is (true? engine)
          "Engine substrate should be true after bootstrap")
      (is (= :idle phase)
          "Agent session phase should be :idle"))))

(deftest startup-bootstrap-introspection-test
  (testing "startup bootstrap summary is queryable via introspection graph"
    (let [session-ctx (session/create-context)
          _           (session/bootstrap-session-in!
                       session-ctx {:register-global-query? false
                                    :base-tools             []
                                    :system-prompt          "sys"
                                    :templates              [{:name "greet"
                                                              :description "desc"
                                                              :content "body"
                                                              :source :project
                                                              :file-path "/tmp/greet.md"}]
                                    :skills                 []
                                    :extension-paths        []})
          ctx         (introspection/create-context {:agent-session-ctx session-ctx})]
      (engine/bootstrap-system-in! (:engine-ctx ctx))
      (introspection/register-resolvers-in! ctx)
      (let [r (introspection/query-agent-session-in
               ctx [:psi.startup/prompt-count
                    :psi.startup/skill-count
                    :psi.startup/tool-count
                    :psi.startup/extension-loaded-count
                    :psi.startup/extension-error-count
                    :psi.startup/mutations])]
        (is (= 1 (:psi.startup/prompt-count r)))
        (is (= 0 (:psi.startup/skill-count r)))
        (is (= 0 (:psi.startup/tool-count r)))
        (is (vector? (:psi.startup/mutations r)))))))
