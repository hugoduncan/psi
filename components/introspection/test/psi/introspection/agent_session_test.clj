(ns psi.introspection.agent-session-test
  "Integration tests: agent-session resolvers wired through the
   introspection context into the query graph.

   All tests are isolated — they use create-context with explicit
   contexts and never touch global state."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.introspection.core :as introspection]
   [psi.agent-session.bootstrap :as bootstrap]
   [psi.agent-session.core :as session]
   [psi.engine.core :as engine]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Helpers
;; ─────────────────────────────────────────────────────────────────────────────

(defn- temp-cwd []
  (let [p (str (java.nio.file.Files/createTempDirectory
                "psi-introspection-agent-session-test-"
                (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.mkdirs (java.io.File. p))
    p))

(defn- session-introspection-ctx
  "Create a fully isolated introspection context with a fresh agent-session
   context attached.  Resolvers are registered and engine is bootstrapped.
   Returns the context map with :session-id for tests that dispatch events."
  []
  (let [session-ctx      (session/create-context {:cwd (temp-cwd) :persist? false})
        session-id       (:session-id (session/new-session-in! session-ctx nil {}))
        ctx              (introspection/create-context {:agent-session-ctx session-ctx})]
    (engine/bootstrap-system-in! (:engine-ctx ctx))
    (introspection/register-resolvers-in! ctx)
    (assoc ctx :session-id session-id)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Context creation
;; ─────────────────────────────────────────────────────────────────────────────

(deftest create-context-with-agent-session-test
  (testing "create-context accepts :agent-session-ctx option"
    (let [session-ctx      (session/create-context {:cwd (temp-cwd) :persist? false})
          _               (session/new-session-in! session-ctx nil {})
          ctx              (introspection/create-context {:agent-session-ctx session-ctx})]
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
    (let [session-ctx      (session/create-context {:cwd (temp-cwd) :persist? false})
          _               (session/new-session-in! session-ctx nil {})
          ctx              (introspection/create-context {:agent-session-ctx session-ctx})]
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
        (is (contains? r-syms 'psi.agent-session.resolvers.session/agent-session-identity)
            "identity resolver should appear in graph summary")
        (is (contains? r-syms 'psi.agent-session.resolvers.session/agent-session-phase)
            "phase resolver should appear in graph summary")
        (is (contains? r-syms 'psi.history.resolvers/git-worktree-list)
            "history worktree resolver should appear in graph summary")
        (is (contains? r-syms 'psi.history.resolvers/git-default-branch)
            "history default-branch resolver should appear in graph summary")
        (is (contains? m-syms 'psi.extension/add-prompt-template)
            "startup prompt mutation should appear in graph summary")
        (is (contains? m-syms 'psi.extension.tool/read)
            "tool read mutation should appear in graph summary")
        (is (contains? m-syms 'psi.extension.tool/bash)
            "tool bash mutation should appear in graph summary")
        (is (contains? m-syms 'psi.extension.tool/write)
            "tool write mutation should appear in graph summary")
        (is (contains? m-syms 'psi.extension.tool/update)
            "tool update mutation should appear in graph summary")
        (is (contains? m-syms 'psi.extension.tool/chain)
            "tool chain mutation should appear in graph summary"))))

  (testing "register-resolvers-in! skips agent-session resolvers when no session ctx"
    (let [ctx (introspection/create-context)]
      (introspection/register-resolvers-in! ctx)
      (let [result (introspection/query-graph-summary-in ctx)
            syms   (:psi.graph/resolver-syms result)]
        (is (not (contains? syms 'psi.agent-session.resolvers.session/agent-session-identity))
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
          session-id  (:session-id ctx)
          model       {:provider "x" :id "test-model" :reasoning false}]
      (session/dispatch-in! session-ctx :session/set-model {:session-id session-id :model model} {:origin :core})
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
    (let [session-ctx      (session/create-context {:cwd (temp-cwd) :persist? false})
          session-id       (:session-id (session/new-session-in! session-ctx nil {}))
          _               (bootstrap/load-startup-resources-in!
                           session-ctx session-id
                           {:templates [{:name "greet"
                                         :description "desc"
                                         :content "body"
                                         :source :project
                                         :file-path "/tmp/greet.md"}]})
          _               (session/dispatch-in! session-ctx
                                                :session/set-startup-bootstrap-summary
                                                {:session-id session-id
                                                 :summary {:timestamp              (java.time.Instant/now)
                                                           :prompt-count           1
                                                           :skill-count            0
                                                           :tool-count             0
                                                           :extension-loaded-count 0
                                                           :extension-error-count  0
                                                           :extension-errors       []}}
                                                {:origin :core})
          ctx         (introspection/create-context {:agent-session-ctx session-ctx})]
      (engine/bootstrap-system-in! (:engine-ctx ctx))
      (introspection/register-resolvers-in! ctx)
      (let [r (introspection/query-agent-session-in
               ctx session-id
               [:psi.startup/prompt-count
                :psi.startup/skill-count
                :psi.startup/tool-count
                :psi.startup/extension-loaded-count
                :psi.startup/extension-error-count])]
        (is (= 1 (:psi.startup/prompt-count r)))
        (is (= 0 (:psi.startup/skill-count r)))
        (is (= 0 (:psi.startup/tool-count r)))
        (is (= 0 (:psi.startup/extension-loaded-count r)))
        (is (= 0 (:psi.startup/extension-error-count r)))))))
