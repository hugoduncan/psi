(ns psi.system-bootstrap.core-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.history.git :as history-git]
   [psi.query.core :as query]
   [psi.query.registry :as registry]
   [psi.system-bootstrap.core :as bootstrap]))

(use-fixtures
  :each
  (fn [f]
    (registry/reset-registry!)
    (query/rebuild-env!)
    (try
      (f)
      (finally
        (registry/reset-registry!)
        (query/rebuild-env!)))))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest register-all-domains!-assembles-global-graph-test
  (testing "register-all-domains! assembles the global resolver and mutation graph"
    (bootstrap/register-all-domains!)
    (let [resolver-syms (query/resolver-syms)
          mutation-syms (query/mutation-syms)]
      (is (contains? resolver-syms 'psi.ai.core/ai-model-list-resolver))
      (is (contains? resolver-syms 'psi.history.resolvers/git-repo-status))
      (is (contains? resolver-syms 'psi.agent-session.resolvers.session/agent-session-identity))
      (is (contains? mutation-syms 'psi.extension/add-prompt-template))
      (is (contains? mutation-syms 'git.worktree/add!)))))

(deftest register-all-domains!-is-idempotent-test
  (testing "register-all-domains! can be called repeatedly without growing the registry"
    (bootstrap/register-all-domains!)
    (let [resolver-syms-1 (query/resolver-syms)
          mutation-syms-1 (query/mutation-syms)
          resolver-count-1 (count resolver-syms-1)
          mutation-count-1 (count mutation-syms-1)]
      (bootstrap/register-all-domains!)
      (is (= resolver-syms-1 (query/resolver-syms)))
      (is (= mutation-syms-1 (query/mutation-syms)))
      (is (= resolver-count-1 (count (query/resolver-syms))))
      (is (= mutation-count-1 (count (query/mutation-syms)))))))

(deftest register-all-domains!-builds-queryable-global-env-test
  (testing "register-all-domains! rebuilds a usable global env, not just symbol presence"
    (bootstrap/register-all-domains!)
    (let [result (query/query {}
                              [:ai/all-models
                               :ai/registered-providers])]
      (is (vector? (:ai/all-models result)))
      (is (contains? (:ai/registered-providers result) :openai)))))

(deftest register-domains-in!-with-session-assembles-session-surface-test
  (testing "register-domains-in! assembles an isolated query context when a session context is present"
    (let [[session-ctx session-id] (create-session-context)
          qctx                     (query/create-query-context)]
      (bootstrap/register-domains-in! qctx session-ctx)
      (let [resolver-syms (query/resolver-syms-in qctx)
            mutation-syms (query/mutation-syms-in qctx)
            result        (query/query-in qctx
                                          {:psi/agent-session-ctx session-ctx
                                           :psi.agent-session/session-id session-id}
                                          [:psi.agent-session/session-id
                                           :psi.agent-session/phase])]
        (is (contains? resolver-syms 'psi.ai.core/ai-model-list-resolver))
        (is (contains? resolver-syms 'psi.agent-session.resolvers.session/agent-session-identity))
        (is (contains? mutation-syms 'psi.extension/add-prompt-template))
        (is (contains? mutation-syms 'git.worktree/add!))
        (is (= session-id (:psi.agent-session/session-id result)))
        (is (= :idle (:psi.agent-session/phase result)))))))

(deftest register-domains-in!-without-session-assembles-base-surface-only-test
  (testing "register-domains-in! without session-ctx registers base domains and omits session domains"
    (let [qctx    (query/create-query-context)
          git-ctx (history-git/create-null-context)]
      (bootstrap/register-domains-in! qctx)
      (let [resolver-syms (query/resolver-syms-in qctx)
            mutation-syms (query/mutation-syms-in qctx)
            result        (query/query-in qctx
                                          {:git/context (history-git/create-context (:repo-dir git-ctx))}
                                          [:ai/all-models
                                           :git.repo/status])]
        (is (contains? resolver-syms 'psi.ai.core/ai-model-list-resolver))
        (is (contains? resolver-syms 'psi.history.resolvers/git-repo-status))
        (is (contains? mutation-syms 'git.worktree/add!))
        (is (not (contains? resolver-syms 'psi.agent-session.resolvers.session/agent-session-identity)))
        (is (not (contains? mutation-syms 'psi.extension/add-prompt-template)))
        (is (vector? (:ai/all-models result)))
        (is (keyword? (:git.repo/status result)))))))

(deftest register-domains-in!-is-idempotent-test
  (testing "register-domains-in! can be called repeatedly without growing the isolated registry"
    (let [[session-ctx _session-id] (create-session-context)
          qctx                      (query/create-query-context)]
      (bootstrap/register-domains-in! qctx session-ctx)
      (let [resolver-syms-1  (query/resolver-syms-in qctx)
            mutation-syms-1  (query/mutation-syms-in qctx)
            resolver-count-1 (count resolver-syms-1)
            mutation-count-1 (count mutation-syms-1)]
        (bootstrap/register-domains-in! qctx session-ctx)
        (is (= resolver-syms-1 (query/resolver-syms-in qctx)))
        (is (= mutation-syms-1 (query/mutation-syms-in qctx)))
        (is (= resolver-count-1 (count (query/resolver-syms-in qctx))))
        (is (= mutation-count-1 (count (query/mutation-syms-in qctx))))))))
