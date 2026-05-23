(ns psi.agent-session.workflow-child-session-context-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-core.core :as agent-core]
   [psi.agent-session.context]
   [psi.agent-session.core :as session-core]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session-core/create-context (test-support/safe-context-opts opts))
         sd (session-core/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- call-private-create-workflow-child-session!
  [ctx parent-session-id request]
  ((var-get #'psi.agent-session.context/create-workflow-child-session!)
   ctx parent-session-id request))

(deftest create-workflow-child-session-shared-realization-edge-attempt-shape-test
  (testing "create-workflow-child-session! applies the authoritative contract to the wider attempt caller shape"
    (let [[ctx parent-session-id] (create-session-context {:persist? false})
          child-session-id "attempt-child-1"
          request {:child-session-id child-session-id
                   :session-name "workflow plan attempt"
                   :system-prompt "system"
                   :prompt-mode :lambda
                   :response-mode :non-streaming
                   :logprobs true
                   :top-logprobs 4
                   :tool-defs []
                   :thinking-level :off
                   :model {:provider "openai" :id "gpt-5"}
                   :skills []
                   :developer-prompt "dev"
                   :developer-prompt-source :explicit
                   :preloaded-messages [{:role "user" :content [{:type :text :text "hello child"}]}]
                   :cache-breakpoints #{:system :tools}
                   :prompt-component-selection {:components #{} :tool-names [] :skill-names [] :extension-prompt-contributions []}
                   :workflow-run-id "run-1"
                   :workflow-step-id "plan"
                   :workflow-attempt-id "attempt-1"
                   :workflow-owned? true}
          result (call-private-create-workflow-child-session! ctx parent-session-id request)
          child-sd (ss/get-session-data-in ctx child-session-id)
          agent-msgs (:messages (agent-core/get-data-in (ss/agent-ctx-in ctx child-session-id)))]
      (is (= {:psi.agent-session/session-id child-session-id} result))
      (is (= parent-session-id (:parent-session-id child-sd)))
      (is (= [] (:skill-ids child-sd)))
      (is (nil? (:skills child-sd)))
      (is (= "run-1" (:workflow-run-id child-sd)))
      (is (= "plan" (:workflow-step-id child-sd)))
      (is (= "attempt-1" (:workflow-attempt-id child-sd)))
      (is (true? (:workflow-owned? child-sd)))
      (is (= :non-streaming (:response-mode child-sd)))
      (is (true? (:logprobs-enabled child-sd)))
      (is (= 4 (:top-logprobs child-sd)))
      (is (= {:provider "openai" :id "gpt-5"} (:model child-sd)))
      (is (= :explicit (:developer-prompt-source child-sd)))
      (is (= "dev" (:developer-prompt child-sd)))
      (is (= [{:role "user" :content [{:type :text :text "hello child"}]}] agent-msgs))
      (is (some? (ss/agent-ctx-in ctx child-session-id)))
      (is (some? (ss/sc-session-id-in ctx child-session-id)))
      (session-core/shutdown-context! ctx))))

(deftest create-workflow-child-session-shared-realization-edge-judge-shape-test
  (testing "create-workflow-child-session! applies the same authoritative contract to the narrower judge caller shape"
    (let [[ctx parent-session-id] (create-session-context {:persist? false})
          child-session-id "judge-child-1"
          request {:child-session-id child-session-id
                   :session-name "workflow judge"
                   :system-prompt "judge system"
                   :tool-defs []
                   :thinking-level :off
                   :preloaded-messages [{:role "user" :content "judge this"}]
                   :workflow-owned? true}
          result (call-private-create-workflow-child-session! ctx parent-session-id request)
          child-sd (ss/get-session-data-in ctx child-session-id)
          agent-msgs (:messages (agent-core/get-data-in (ss/agent-ctx-in ctx child-session-id)))]
      (is (= {:psi.agent-session/session-id child-session-id} result))
      (is (= parent-session-id (:parent-session-id child-sd)))
      (is (= [] (:skill-ids child-sd)))
      (is (nil? (:skills child-sd)))
      (is (= "workflow judge" (:session-name child-sd)))
      (is (= "judge system" (:system-prompt child-sd)))
      (is (true? (:workflow-owned? child-sd)))
      (is (= [{:role "user" :content "judge this"}] agent-msgs))
      (is (some? (ss/agent-ctx-in ctx child-session-id)))
      (is (some? (ss/sc-session-id-in ctx child-session-id)))
      (session-core/shutdown-context! ctx))))

(deftest create-workflow-child-session-invalid-request-fails-locally-test
  (testing "realization edge rejects malformed workflow child-session create requests clearly"
    (let [[ctx parent-session-id] (create-session-context {:persist? false})
          ex (try
               (call-private-create-workflow-child-session!
                ctx
                parent-session-id
                {:child-session-id "bad-child"
                 :session-name "workflow child"
                 :tool-defs :not-a-vector
                 :thinking-level :off})
               nil
               (catch clojure.lang.ExceptionInfo ex
                 ex))]
      (is (some? ex))
      (is (= :workflow-child-session-create (:contract (ex-data ex))))
      (is (= :request (:stage (ex-data ex))))
      (is (= :psi.agent-session.context/create-workflow-child-session!
             (:caller (ex-data ex))))
      (session-core/shutdown-context! ctx))))
