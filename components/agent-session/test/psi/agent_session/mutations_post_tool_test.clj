(ns psi.agent-session.mutations-post-tool-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.post-tool :as post-tool]
   [psi.agent-session.services :as services]
   [psi.agent-session.test-support :as test-support]
   [psi.query.core :as query]))

(defn- create-session-context []
  (let [ctx (session/create-context (test-support/safe-context-opts {}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(deftest register-post-tool-processor-mutation-test
  (let [[ctx session-id] (create-session-context)
        qctx   (query/create-query-context)
        mutate (fn [op params]
                 (get (query/query-in qctx
                                      {:psi/agent-session-ctx ctx}
                                      [(list op (assoc params :psi/agent-session-ctx ctx :session-id session-id))])
                      op))]
    (session/register-resolvers-in! qctx false)
    (session/register-mutations-in! qctx mutations/all-mutations true)
    (let [r (mutate 'psi.extension/register-post-tool-processor
                    {:ext-path "/ext/lsp.clj"
                     :name "lint"
                     :match {:tools #{"write"}}
                     :timeout-ms 100
                     :handler (fn [_] {:content/append "x"})})]
      (is (= "/ext/lsp.clj" (:psi.extension/path r)))
      (is (= 1 (post-tool/processor-count-in ctx))))))

(deftest ensure-and-stop-service-mutations-test
  (let [[ctx session-id] (create-session-context)
        qctx   (query/create-query-context)
        mutate (fn [op params]
                 (get (query/query-in qctx
                                      {:psi/agent-session-ctx ctx}
                                      [(list op (assoc params :psi/agent-session-ctx ctx :session-id session-id))])
                      op))]
    (session/register-resolvers-in! qctx false)
    (session/register-mutations-in! qctx mutations/all-mutations true)
    (let [r1 (mutate 'psi.extension/ensure-service
                     {:ext-path "/ext/lsp.clj"
                      :key [:lsp "/repo"]
                      :type :subprocess
                      :spec {:command ["bash" "-lc" "sleep 5"]
                             :cwd "/tmp"
                             :transport :stdio}})
          _  (mutate 'psi.extension/stop-service
                     {:ext-path "/ext/lsp.clj"
                      :key [:lsp "/repo"]})]
      (is (= "/ext/lsp.clj" (:psi.extension/path r1)))
      (is (= :stopped (:status (services/service-in ctx [:lsp "/repo"])))))))
