(ns psi.workflow-runtime.child-session-contract-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.child-session-contract :as contract]))

(deftest request-contract-validates-supported-surface-test
  (testing "supported workflow child-session create request is valid"
    (is (true? (contract/valid-request?
                {:child-session-id "child-1"
                 :session-name "workflow child"
                 :system-prompt "system"
                 :prompt-mode :lambda
                 :response-mode :non-streaming
                 :tool-defs []
                 :thinking-level :off
                 :model {:provider "openai" :id "gpt-5"}
                 :skills []
                 :developer-prompt "developer"
                 :developer-prompt-source :explicit
                 :preloaded-messages [{:role "user" :content "hello"}]
                 :cache-breakpoints #{:system}
                 :prompt-component-selection {:components #{}}
                 :logprobs true
                 :top-logprobs 3
                 :workflow-run-id "run-1"
                 :workflow-step-id "step-1"
                 :workflow-attempt-id "attempt-1"
                 :workflow-owned? true})))))

(deftest request-contract-rejects-unknown-fields-test
  (testing "unknown request fields fail clearly"
    (let [ex (try
               (contract/assert-valid-request!
                {:child-session-id "child-1"
                 :session-name "workflow child"
                 :model-fallback {:type :ranked-model-candidates}}
                :test/caller)
               nil
               (catch clojure.lang.ExceptionInfo ex
                 ex))]
      (is (some? ex))
      (is (= :workflow-child-session-create (:contract (ex-data ex))))
      (is (= :request (:stage (ex-data ex))))
      (is (= :test/caller (:caller (ex-data ex)))))))

(deftest result-contract-validates-minimal-shape-test
  (testing "minimal result shape is valid"
    (is (= {:psi.agent-session/session-id "child-1"}
           (contract/assert-valid-result!
            {:psi.agent-session/session-id "child-1"}
            :test/caller)))))

(deftest result-contract-rejects-malformed-results-test
  (testing "malformed result fails clearly"
    (let [ex (try
               (contract/assert-valid-result!
                {:session-id "child-1"}
                :test/caller)
               nil
               (catch clojure.lang.ExceptionInfo ex
                 ex))]
      (is (some? ex))
      (is (= :workflow-child-session-create (:contract (ex-data ex))))
      (is (= :result (:stage (ex-data ex))))
      (is (= :test/caller (:caller (ex-data ex)))))))
