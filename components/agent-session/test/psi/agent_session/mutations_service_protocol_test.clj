(ns psi.agent-session.mutations-service-protocol-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations :as sut]
   [psi.agent-session.service-protocol :as protocol]
   [psi.agent-session.test-support :as test-support]
   [psi.query.core :as query]))

(defn- create-session-context []
  (let [ctx (session/create-context (test-support/safe-context-opts {}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(deftest service-request-mutation-test
  (testing "service-request delegates to service-protocol helper"
    (let [[ctx session-id] (create-session-context)
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (assoc params :psi/agent-session-ctx ctx :session-id session-id))])
                        op))
          calls (atom [])]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx sut/all-mutations true)
      (with-redefs [protocol/send-service-request!
                    (fn
                      ([ctx' key req]
                       (swap! calls conj [ctx' key req])
                       {:service-key key
                        :request-id (:request-id req)
                        :payload (:payload req)
                        :timeout-ms (:timeout-ms req)})
                      ([ctx' key req trace-opts]
                       (swap! calls conj [ctx' key req trace-opts])
                       {:service-key key
                        :request-id (:request-id req)
                        :payload (:payload req)
                        :timeout-ms (:timeout-ms req)}))]
        (let [r (mutate 'psi.extension/service-request
                        {:ext-path "/ext/service.clj"
                         :key [:svc "/repo"]
                         :request-id "1"
                         :payload {:op :ping}
                         :timeout-ms 250})]
          (is (= [[ctx [:svc "/repo"]
                   {:request-id "1"
                    :payload {:op :ping}
                    :timeout-ms 250}
                   {:dispatch-id nil}]]
                 @calls))
          (is (= "/ext/service.clj" (:psi.extension/path r)))
          (is (= [:svc "/repo"] (:psi.extension.service/service-key r)))
          (is (= "1" (:psi.extension.service/request-id r)))
          (is (= 250 (:psi.extension.service/timeout-ms r))))))))

(deftest service-notify-mutation-test
  (testing "service-notify delegates to service-protocol helper"
    (let [[ctx session-id] (create-session-context)
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (assoc params :psi/agent-session-ctx ctx :session-id session-id))])
                        op))
          calls (atom [])]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx sut/all-mutations true)
      (with-redefs [protocol/send-service-notification!
                    (fn
                      ([ctx' key payload]
                       (swap! calls conj [ctx' key payload])
                       {:service-key key
                        :payload payload})
                      ([ctx' key payload trace-opts]
                       (swap! calls conj [ctx' key payload trace-opts])
                       {:service-key key
                        :payload payload}))]
        (let [r (mutate 'psi.extension/service-notify
                        {:ext-path "/ext/service.clj"
                         :key [:svc "/repo"]
                         :payload {:op :initialized}})]
          (is (= [[ctx [:svc "/repo"] {:op :initialized} {:dispatch-id nil}]]
                 @calls))
          (is (= "/ext/service.clj" (:psi.extension/path r)))
          (is (= [:svc "/repo"] (:psi.extension.service/service-key r)))
          (is (= {:op :initialized}
                 (:psi.extension.service/payload r))))))))
