(ns psi.agent-session.tool-output-integration-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cheshire.core :as json]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tool-output :as tool-output]
   [psi.agent-session.tool-runtime-adapter :as tool-runtime-adapter]
   [psi.test-support.fs :as test-fs]))

(defn- large-bash-command []
  ;; Guaranteed to exceed default 1000-line/51200-byte policy.
  "yes line | head -n 50000")

(use-fixtures
  :each
  (fn [f]
    (try
      (f)
      (finally
        (tool-output/cleanup-temp-store!)))))

(deftest bash-limit-hit-eql-telemetry-test
  (testing "bash truncation persists full output and is queryable via EQL"
    (let [[ctx session-id] (test-support/create-test-session)
          tc  {:id "bash-limit-1"
               :name "bash"
               :arguments (json/generate-string
                           {"command" (large-bash-command)})}
          result (#'psi.agent-session.tool-runtime-adapter/run-tool-call! ctx session-id tc nil)
          truncation (get-in result [:details :truncation])
          full-path  (get-in result [:details :full-output-path])
          eql-result (session/query-in ctx session-id
                                       [{:psi.tool-output/calls
                                         [:psi.tool-output.call/tool-name
                                          :psi.tool-output.call/limit-hit?
                                          :psi.tool-output.call/output-bytes
                                          :psi.tool-output.call/context-bytes-added]}
                                        :psi.tool-output/stats])
          calls      (:psi.tool-output/calls eql-result)
          call       (first calls)]
      (is (true? (:truncated truncation)))
      (is (contains? #{:lines :bytes} (:truncated-by truncation)))
      (is (string? full-path))
      (is (.exists (io/file full-path)))
      (is (re-find #"line" (slurp full-path)))

      (is (= 1 (count calls)))
      (is (= "bash" (:psi.tool-output.call/tool-name call)))
      (is (true? (:psi.tool-output.call/limit-hit? call)))
      (is (= (:psi.tool-output.call/output-bytes call)
             (:psi.tool-output.call/context-bytes-added call)))
      (is (= (:psi.tool-output.call/context-bytes-added call)
             (get-in eql-result [:psi.tool-output/stats :total-context-bytes]))))))

(deftest read-within-limits-telemetry-test
  (testing "read call within limits records no limit-hit and matching byte counts"
    (let [f   (doto (java.io.File/createTempFile "psi-read-ok" ".txt")
                (.deleteOnExit))
          _   (spit f "alpha\nbeta\ngamma\n")
          [ctx session-id] (test-support/create-test-session)
          tc  {:id "read-ok-1"
               :name "read"
               :arguments (json/generate-string
                           {"filePath" (.getAbsolutePath f)})}
          _   (#'psi.agent-session.tool-runtime-adapter/run-tool-call! ctx session-id tc nil)
          eql-result (session/query-in ctx session-id
                                       [{:psi.tool-output/calls
                                         [:psi.tool-output.call/tool-name
                                          :psi.tool-output.call/limit-hit?
                                          :psi.tool-output.call/output-bytes
                                          :psi.tool-output.call/context-bytes-added]}])
          call (first (:psi.tool-output/calls eql-result))]
      (is (= "read" (:psi.tool-output.call/tool-name call)))
      (is (false? (:psi.tool-output.call/limit-hit? call)))
      (is (= (:psi.tool-output.call/output-bytes call)
             (:psi.tool-output.call/context-bytes-added call))))))

(deftest aggregate-stats-across-multiple-tool-calls-test
  (testing "aggregates reflect multiple calls with limit-hit counts by tool"
    (let [f   (doto (java.io.File/createTempFile "psi-read-agg" ".txt")
                (.deleteOnExit))
          _   (spit f "small\nfile\n")
          [ctx session-id] (test-support/create-test-session)
          bash-args (json/generate-string {"command" (large-bash-command)})
          read-args (json/generate-string {"filePath" (.getAbsolutePath f)})]
      (#'psi.agent-session.tool-runtime-adapter/run-tool-call! ctx session-id {:id "b1" :name "bash" :arguments bash-args} nil)
      (#'psi.agent-session.tool-runtime-adapter/run-tool-call! ctx session-id {:id "r1" :name "read" :arguments read-args} nil)
      (#'psi.agent-session.tool-runtime-adapter/run-tool-call! ctx session-id {:id "b2" :name "bash" :arguments bash-args} nil)
      (let [eql-result (session/query-in ctx session-id
                                         [{:psi.tool-output/calls
                                           [:psi.tool-output.call/tool-name
                                            :psi.tool-output.call/context-bytes-added
                                            :psi.tool-output.call/limit-hit?]}
                                          :psi.tool-output/stats])
            calls (:psi.tool-output/calls eql-result)
            total-from-calls (reduce + (map :psi.tool-output.call/context-bytes-added calls))
            stats (:psi.tool-output/stats eql-result)]
        (is (= 3 (count calls)))
        (is (= total-from-calls (:total-context-bytes stats)))
        (is (contains? (:by-tool stats) "bash"))
        (is (contains? (:by-tool stats) "read"))
        (is (= 2 (get-in stats [:limit-hits-by-tool "bash"])))
        (is (zero? (get-in stats [:limit-hits-by-tool "read"] 0)))))))

(deftest temp-store-cleanup-lifecycle-test
  (testing "cleanup removes persisted artifacts"
    (let [root (tool-output/init-temp-store!)
          p    (tool-output/persist-truncated-output! "bash" "cleanup-1" "full output")]
      (is (.exists (io/file root)))
      (is (.exists (io/file p)))
      (is (true? (tool-output/cleanup-temp-store!)))
      (is (not (.exists (io/file root))))))

  (testing "cleanup failure path is warning-only and does not throw"
    (let [root (tool-output/init-temp-store!)]
      (test-fs/delete-recursively! root)
      (is (boolean? (tool-output/cleanup-temp-store!)))
      (is (nil? (tool-output/temp-store-root))))))
