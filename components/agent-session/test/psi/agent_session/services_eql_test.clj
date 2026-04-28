(ns psi.agent-session.services-eql-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.post-tool :as post-tool]
   [psi.agent-session.services :as services]
   [psi.agent-session.test-support :as test-support]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest services-eql-introspection-test
  (testing "query-in resolves managed service attrs"
    (let [[ctx session-id] (create-session-context)
          _ (services/ensure-service-in!
             ctx
             {:key [:svc "/repo"]
              :type :subprocess
              :spec {:command ["bash" "-lc" "sleep 5"]
                     :cwd "/tmp"
                     :transport :stdio}
              :ext-path "/ext/service.clj"})
          result (session/query-in ctx session-id [:psi.service/count
                                                   :psi.service/keys
                                                   {:psi.service/services
                                                    [:psi.service/key
                                                     :psi.service/status
                                                     :psi.service/command
                                                     :psi.service/cwd
                                                     :psi.service/transport
                                                     :psi.service/ext-path]}])]
      (try
        (is (= 1 (:psi.service/count result)))
        (is (= [[:svc "/repo"]] (:psi.service/keys result)))
        (is (= [{:psi.service/key [:svc "/repo"]
                 :psi.service/status :running
                 :psi.service/command ["bash" "-lc" "sleep 5"]
                 :psi.service/cwd "/tmp"
                 :psi.service/transport :stdio
                 :psi.service/ext-path "/ext/service.clj"}]
               (:psi.service/services result)))
        (finally
          (services/stop-service-in! ctx [:svc "/repo"]))))))

(deftest post-tool-eql-introspection-test
  (testing "query-in resolves post-tool processor and telemetry attrs"
    (let [[ctx session-id] (create-session-context)]
      (post-tool/register-processor-in!
       ctx
       {:name "lint"
        :ext-path "/ext/service.clj"
        :match {:tools #{"write"}}
        :timeout-ms 100
        :handler (fn [_] {:content/append "\nlint"})})
      (post-tool/run-post-tool-processing-in!
       ctx
       {:session-id session-id
        :tool-name "write"
        :tool-call-id "call-1"
        :tool-args {}
        :tool-result {:content "ok"
                      :is-error false
                      :details nil
                      :meta {:tool-name "write" :tool-call-id "call-1"}
                      :effects []
                      :enrichments []}
        :worktree-path "/repo"})
      (let [result (session/query-in ctx session-id [:psi.post-tool-processor/count
                                                     {:psi.post-tool-processors
                                                      [:psi.post-tool-processor/name
                                                       :psi.post-tool-processor/ext-path
                                                       :psi.post-tool-processor/tools
                                                       :psi.post-tool-processor/timeout-ms]}
                                                     :psi.post-tool/processed-count
                                                     :psi.post-tool/timeout-count
                                                     :psi.post-tool/error-count
                                                     {:psi.post-tool/recent-events
                                                      [:psi.post-tool/tool-call-id
                                                       :psi.post-tool/tool-name
                                                       :psi.post-tool/processor-name
                                                       :psi.post-tool/status
                                                       :psi.post-tool/duration-ms]}])]
        (is (= 1 (:psi.post-tool-processor/count result)))
        (is (= [{:psi.post-tool-processor/name "lint"
                 :psi.post-tool-processor/ext-path "/ext/service.clj"
                 :psi.post-tool-processor/tools ["write"]
                 :psi.post-tool-processor/timeout-ms 100}]
               (:psi.post-tool-processors result)))
        (is (= 1 (:psi.post-tool/processed-count result)))
        (is (zero? (:psi.post-tool/timeout-count result)))
        (is (zero? (:psi.post-tool/error-count result)))
        (is (= 1 (count (:psi.post-tool/recent-events result))))
        (let [event (first (:psi.post-tool/recent-events result))]
          (is (= "call-1" (:psi.post-tool/tool-call-id event)))
          (is (= "write" (:psi.post-tool/tool-name event)))
          (is (= "lint" (:psi.post-tool/processor-name event)))
          (is (= :success (:psi.post-tool/status event)))
          (is (integer? (:psi.post-tool/duration-ms event))))))))
