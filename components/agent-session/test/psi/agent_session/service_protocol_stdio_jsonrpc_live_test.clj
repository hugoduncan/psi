(ns psi.agent-session.service-protocol-stdio-jsonrpc-live-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.services :as services]
   [psi.agent-session.test-support :as test-support]
   [psi.query.core :as query]))

(def ^:private jsonrpc-echo-command
  [(or (System/getenv "BB") "bb")
   (str (.getCanonicalPath (java.io.File. "components/agent-session/test/psi/agent_session/jsonrpc_echo_bb.clj")))])

(defn- create-session-context []
  (let [ctx (session/create-context (test-support/safe-context-opts {}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(deftest ensure-service-jsonrpc-runtime-installs-live-fns-test
  (testing "ensure-service mutation attaches stdio json-rpc runtime when protocol opts in"
    (let [[ctx session-id] (create-session-context)
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (assoc params :psi/agent-session-ctx ctx :session-id session-id))])
                        op))
          cwd (test-support/temp-cwd)]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (try
        (mutate 'psi.extension/ensure-service
                {:ext-path "/ext/lsp.clj"
                 :key [:jsonrpc-live cwd]
                 :type :subprocess
                 :spec {:command jsonrpc-echo-command
                        :cwd cwd
                        :transport :stdio
                        :protocol :json-rpc}})
        (let [svc (services/service-in ctx [:jsonrpc-live cwd])]
          (is (fn? (:send-fn svc)))
          (is (fn? (:await-response-fn svc))))
        (finally
          (when-let [close-fn (:close-fn (services/service-in ctx [:jsonrpc-live cwd]))]
            (future (close-fn)))
          (future (services/stop-service-in! ctx [:jsonrpc-live cwd])))))))
