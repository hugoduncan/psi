(ns psi.agent-session.extensions-post-tool-api-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.extensions :as ext]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(deftest create-extension-api-registers-post-tool-processor-via-mutation-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/ext/test.clj"})
        reg (ext/create-registry)
        runtime-fns {:query-fn (:query api)
                     :mutate-fn (:mutate api)
                     :get-api-key-fn (:get-api-key api)
                     :ui-type-fn (fn [] :console)
                     :ui-context-fn (fn [_] (:ui api))
                     :log-fn (fn [_] nil)}
        ext-api (ext/create-extension-api reg "/ext/test.clj" runtime-fns)]
    ((:register-post-tool-processor ext-api)
     {:name "lint"
      :match {:tools #{"write"}}
      :timeout-ms 100
      :handler (fn [_] {:content/append "x"})})
    (is (= [{:name "lint"
             :ext-path "/ext/test.clj"
             :tools #{"write"}
             :timeout-ms 100}]
           (:post-tool-processors @state)))))

(deftest create-extension-api-ensures-and-stops-services-via-mutation-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/ext/test.clj"})
        reg (ext/create-registry)
        runtime-fns {:query-fn (:query api)
                     :mutate-fn (:mutate api)
                     :get-api-key-fn (:get-api-key api)
                     :ui-type-fn (fn [] :console)
                     :ui-context-fn (fn [_] (:ui api))
                     :log-fn (fn [_] nil)}
        ext-api (ext/create-extension-api reg "/ext/test.clj" runtime-fns)]
    ((:ensure-service ext-api)
     {:key [:svc "/repo"]
      :type :subprocess
      :spec {:command ["generic-service"] :cwd "/repo" :transport :stdio}})
    (is (= [{:key [:svc "/repo"]
             :type :subprocess
             :spec {:command ["generic-service"] :cwd "/repo" :transport :stdio}}]
           (:services @state)))
    ((:stop-service ext-api) [:svc "/repo"])
    (is (= [] (:services @state)))))
