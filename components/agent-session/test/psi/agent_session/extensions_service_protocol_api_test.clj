(ns psi.agent-session.extensions-service-protocol-api-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.extensions :as ext]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(deftest create-extension-api-registers-service-protocol-via-mutation-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/ext/test.clj"})
        reg (ext/create-registry)
        runtime-fns {:query-fn (:query api)
                     :mutate-fn (:mutate api)
                     :get-api-key-fn (:get-api-key api)
                     :ui-type-fn (fn [] :console)
                     :ui-context-fn (fn [_] (:ui api))
                     :log-fn (fn [_] nil)}
        ext-api (ext/create-extension-api reg "/ext/test.clj" runtime-fns)]
    ((:service-request ext-api)
     {:key [:svc "/repo"]
      :request-id "r1"
      :payload {:op :ping}
      :timeout-ms 123})
    ((:service-notify ext-api)
     {:key [:svc "/repo"]
      :payload {:op :initialized}})
    (is (= [{:ext-path "/ext/test.clj"
             :key [:svc "/repo"]
             :request-id "r1"
             :payload {:op :ping}
             :timeout-ms 123}]
           (mapv #(select-keys % [:ext-path :key :request-id :payload :timeout-ms])
                 (:service-requests @state))))
    (is (= [{:ext-path "/ext/test.clj"
             :key [:svc "/repo"]
             :payload {:op :initialized}}]
           (:service-notifications @state)))))
