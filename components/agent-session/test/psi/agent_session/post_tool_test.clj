(ns psi.agent-session.post-tool-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.post-tool :as post-tool]
   [psi.state-kernel.dispatch :as kernel]))

(defn- clean-state [f]
  (kernel/clear-handlers!)
  (kernel/clear-dispatch-trace!)
  (try (f)
       (finally
         (kernel/clear-handlers!)
         (kernel/clear-dispatch-trace!))))

(use-fixtures :each clean-state)

(defn- make-ctx []
  {:post-tool-registry (post-tool/create-registry)
   :state* (atom {})})

(def base-result
  {:content "ok"
   :is-error false
   :details nil
   :meta {:tool-name "write" :tool-call-id "call-1"}
   :effects []
   :enrichments []})

(deftest register-and-match-processors-test
  (let [ctx (make-ctx)]
    (post-tool/register-processor-in! ctx {:name "p1" :match {:tools #{"write"}} :timeout-ms 100 :handler (fn [_] nil)})
    (post-tool/register-processor-in! ctx {:name "p2" :match {:tools #{"edit"}} :timeout-ms 100 :handler (fn [_] nil)})
    (is (= 2 (post-tool/processor-count-in ctx)))
    (is (= #{{:name "p1" :ext-path nil :tools #{"write"} :timeout-ms 100}
             {:name "p2" :ext-path nil :tools #{"edit"} :timeout-ms 100}}
           (set (post-tool/projected-processors-in ctx))))))

(deftest additive-processing-test
  (let [ctx (make-ctx)]
    (post-tool/register-processor-in!
     ctx
     {:name "append"
      :match {:tools #{"write"}}
      :timeout-ms 100
      :handler (fn [_]
                 {:content/append "\nextra"
                  :details/merge {:x 1}
                  :enrichments [{:type :diagnostic :message "warn"}]})})
    (post-tool/register-processor-in!
     ctx
     {:name "append-2"
      :match {:tools #{"write"}}
      :timeout-ms 100
      :handler (fn [_]
                 {:content/append "\nmore"
                  :details/merge {:y 2}
                  :enrichments [{:type :note :message "info"}]})})
    (let [result (post-tool/run-post-tool-processing-in!
                  ctx
                  {:session-id "s1"
                   :tool-name "write"
                   :tool-call-id "call-1"
                   :tool-args {}
                   :tool-result base-result
                   :worktree-path "/repo"})]
      (is (= "ok\nextra\nmore" (:content result)))
      (is (= {:x 1 :y 2} (:details result)))
      (is (= [{:type :diagnostic :message "warn"}
              {:type :note :message "info"}]
             (:enrichments result)))
      (is (= {:processed-count 2 :timeout-count 0 :error-count 0}
             (post-tool/telemetry-counts-in ctx))))))

(deftest timeout-and-error-do-not-fail-base-result-test
  (let [ctx (make-ctx)]
    (post-tool/register-processor-in!
     ctx
     {:name "timeout"
      :match {:tools #{"write"}}
      :timeout-ms 10
      :handler (fn [_] (Thread/sleep 50) {:content/append "bad"})})
    (post-tool/register-processor-in!
     ctx
     {:name "error"
      :match {:tools #{"write"}}
      :timeout-ms 100
      :handler (fn [_] (throw (ex-info "boom" {})))})
    (let [result (post-tool/run-post-tool-processing-in!
                  ctx
                  {:session-id "s1"
                   :tool-name "write"
                   :tool-call-id "call-1"
                   :tool-args {}
                   :tool-result base-result
                   :worktree-path "/repo"})]
      (is (= base-result result))
      (is (= {:processed-count 0 :timeout-count 1 :error-count 1}
             (post-tool/telemetry-counts-in ctx))))))

(deftest canonical-post-tool-trace-failure-and-timeout-test
  (dispatch/register-handler! :session/post-tool-run
                              (fn [ctx input]
                                {:return (post-tool/run-post-tool-processing-direct-in! ctx input)}))
  (let [ctx (make-ctx)]
    (post-tool/register-processor-in!
     ctx
     {:name "timeout"
      :match {:tools #{"write"}}
      :timeout-ms 10
      :handler (fn [_] (Thread/sleep 50) {:content/append "bad"})})
    (post-tool/register-processor-in!
     ctx
     {:name "error"
      :match {:tools #{"write"}}
      :timeout-ms 100
      :handler (fn [_] (throw (ex-info "boom" {})))})
    (kernel/clear-dispatch-trace!)
    (let [result (post-tool/run-post-tool-processing-in!
                  ctx
                  {:session-id "s1"
                   :tool-name "write"
                   :tool-call-id "call-trace-1"
                   :tool-args {}
                   :tool-result base-result
                   :worktree-path "/repo"})
          entries (kernel/dispatch-trace-entries)
          received (first entries)
          completed (last entries)]
      (is (= base-result result))
      (is (= :dispatch/received (:trace/kind received)))
      (is (= :session/post-tool-run (:event-type received)))
      (is (some #(and (= :dispatch/interceptor-enter (:trace/kind %))
                      (= :permission (:interceptor-id %)))
                entries))
      (is (some #(and (= :dispatch/handler-result (:trace/kind %))
                      (= :session/post-tool-run (:event-type %)))
                entries))
      (is (some #(and (= :dispatch/interceptor-exit (:trace/kind %))
                      (= :apply (:interceptor-id %)))
                entries))
      (is (= :dispatch/completed (:trace/kind completed)))
      (is (= :session/post-tool-run (:event-type completed)))
      (is (= {:processed-count 0 :timeout-count 1 :error-count 1}
             (post-tool/telemetry-counts-in ctx))))))
