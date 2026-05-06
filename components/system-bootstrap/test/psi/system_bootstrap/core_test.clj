(ns psi.system-bootstrap.core-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [psi.query.core :as query]
   [psi.query.registry :as registry]
   [psi.system-bootstrap.core :as bootstrap]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]))

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

(deftest register-all-domains!-test
  (testing "register-all-domains! assembles the global resolver and mutation graph"
    (bootstrap/register-all-domains!)
    (let [resolver-syms (query/resolver-syms)
          mutation-syms (query/mutation-syms)]
      (is (contains? resolver-syms 'psi.ai.core/ai-model-list-resolver))
      (is (contains? resolver-syms 'psi.history.resolvers/git-repo-status))
      (is (contains? resolver-syms 'psi.agent-session.resolvers.session/agent-session-identity))
      (is (contains? mutation-syms 'psi.extension/add-prompt-template))
      (is (contains? mutation-syms 'git.worktree/add!)))))

(deftest register-domains-in!-with-session-test
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
