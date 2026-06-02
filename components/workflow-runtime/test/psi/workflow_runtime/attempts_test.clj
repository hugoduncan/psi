(ns psi.workflow-runtime.attempts-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session-core]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.model]
   [psi.session-state.state :as session-state]
   [psi.workflow-runtime.attempts :as workflow-attempts]
   [psi.workflow-runtime.execution-adapter :as execution-adapter]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session-core/create-context (test-support/safe-context-opts opts))
         sd  (session-core/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest create-step-attempt-session-test
  (testing "each workflow step attempt gets one canonical child session with workflow linkage"
    (let [[ctx parent-session-id] (create-session-context {:persist? false})
          {:keys [attempt execution-session]}
          (workflow-attempts/create-step-attempt-session!
           ctx
           parent-session-id
           {:workflow-run-id "run-1"
            :workflow-step-id "plan"
            :attempt-id "attempt-1"
            :session-name "workflow plan attempt"
            :response-mode :non-streaming
            :logprobs true
            :top-logprobs 4
            :tool-defs []
            :thinking-level :off})]
      (is (= "attempt-1" (:attempt-id attempt)))
      (is (= :pending (:status attempt)))
      (is (= (:session-id execution-session) (:execution-session-id attempt)))
      (is (= :agent (:spawn-mode execution-session)))
      (is (true? (:workflow-owned? execution-session)))
      (is (= "run-1" (:workflow-run-id execution-session)))
      (is (= "plan" (:workflow-step-id execution-session)))
      (is (= "attempt-1" (:workflow-attempt-id execution-session)))
      (is (= :non-streaming (:response-mode execution-session)))
      (is (true? (:logprobs-enabled execution-session)))
      (is (= 4 (:top-logprobs execution-session)))
      (is (= parent-session-id (:parent-session-id execution-session)))
      (is (instance? java.time.Instant (:created-at execution-session)))
      (is (instance? java.time.Instant (:updated-at execution-session)))
      (is (= (:created-at execution-session) (:updated-at execution-session)))
      (is (some? (session-state/agent-ctx-in ctx (:session-id execution-session))))
      (is (some? (session-state/sc-session-id-in ctx (:session-id execution-session))))
      (session-core/shutdown-context! ctx))))

(deftest create-step-attempt-session-preserves-combined-response-mode-and-logprobs-controls-test
  (testing "workflow attempt child-session creation preserves non-streaming and logprob controls on the same path"
    (let [[ctx parent-session-id] (create-session-context {:persist? false})
          {:keys [execution-session]}
          (workflow-attempts/create-step-attempt-session!
           ctx
           parent-session-id
           {:workflow-run-id "run-1"
            :workflow-step-id "plan"
            :attempt-id "attempt-2"
            :session-name "workflow plan attempt"
            :response-mode :non-streaming
            :logprobs true
            :top-logprobs 6
            :tool-defs []
            :thinking-level :off})]
      (is (= {:response-mode :non-streaming
              :logprobs-enabled true
              :top-logprobs 6}
             (select-keys execution-session [:response-mode :logprobs-enabled :top-logprobs])))
      (session-core/shutdown-context! ctx))))

(deftest create-step-attempt-session-forwards-supported-request-surface-test
  (testing "attempt path validates and forwards the supported workflow child-session create surface"
    (let [create-calls* (atom [])
          child-session-id* (atom nil)
          ctx {execution-adapter/adapter-key
               (execution-adapter/create
                {:create-child-session! (fn [_ctx _parent opts]
                                          (reset! child-session-id* (:child-session-id opts))
                                          (swap! create-calls* conj opts)
                                          {:psi.agent-session/session-id (:child-session-id opts)})
                 :get-session-data (fn [_ctx sid]
                                     {:session-id sid})})}]
      (with-redefs [psi.session-state.model/valid-session? (constantly true)]
        (let [{:keys [execution-session]}
              (workflow-attempts/create-step-attempt-session!
               ctx
               "parent-1"
               {:workflow-run-id "run-1"
                :workflow-step-id "plan"
                :attempt-id "attempt-1"
                :session-name "workflow plan attempt"
                :system-prompt "system"
                :prompt-mode :lambda
                :response-mode :non-streaming
                :logprobs true
                :top-logprobs 2
                :tool-defs [{:name "read"}]
                :thinking-level :off
                :speed-mode :fast
                :effort-override :xhigh
                :model {:provider "openai" :id "gpt-5"}
                :skills [{:name "skill-a"}]
                :developer-prompt "dev"
                :developer-prompt-source :explicit
                :preloaded-messages [{:role "user" :content "hello"}]
                :cache-breakpoints #{:system :tools}
                :prompt-component-selection {:components #{:tools}}
                :model-fallback {:type :ranked-model-candidates
                                 :candidates [{:provider "x" :id "y"}]}})]
          (is (= 1 (count @create-calls*)))
          (is (= {:child-session-id @child-session-id*
                  :session-name "workflow plan attempt"
                  :system-prompt "system"
                  :prompt-mode :lambda
                  :response-mode :non-streaming
                  :logprobs true
                  :top-logprobs 2
                  :tool-ids ["read"]
                  :thinking-level :off
                  :speed-mode :fast
                  :effort-override :xhigh
                  :model {:provider "openai" :id "gpt-5"}
                  :skills [{:name "skill-a"}]
                  :developer-prompt "dev"
                  :developer-prompt-source :explicit
                  :preloaded-messages [{:role "user" :content "hello"}]
                  :cache-breakpoints #{:system :tools}
                  :prompt-component-selection {:components #{:tools}}
                  :workflow-run-id "run-1"
                  :workflow-step-id "plan"
                  :workflow-attempt-id "attempt-1"
                  :workflow-owned? true}
                 (first @create-calls*)))
          (is (= {:type :ranked-model-candidates
                  :candidates [{:provider "x" :id "y"}]}
                 (:model-fallback execution-session))))))))

(deftest create-step-attempt-session-invalid-request-fails-locally-test
  (testing "malformed attempt child-session requests fail at the contract boundary"
    (let [ctx {execution-adapter/adapter-key
               (execution-adapter/create
                {:create-child-session! (fn [_ctx _parent _opts]
                                          (throw (ex-info "should not be called" {})))
                 :get-session-data (fn [_ctx _sid] nil)})}
          ex (try
               (workflow-attempts/create-step-attempt-session!
                ctx
                "parent-1"
                {:workflow-run-id "run-1"
                 :workflow-step-id "plan"
                 :attempt-id "attempt-1"
                 :session-name "workflow plan attempt"
                 :tool-defs []
                 :thinking-level :off
                 :model :not-a-map})
               nil
               (catch clojure.lang.ExceptionInfo ex
                 ex))]
      (is (some? ex))
      (is (= :workflow-child-session-create (:contract (ex-data ex))))
      (is (= :request (:stage (ex-data ex))))
      (is (= :psi.workflow-runtime.attempts/create-step-attempt-session!
             (:caller (ex-data ex)))))))

(deftest create-step-attempt-session-invalid-result-fails-locally-test
  (testing "malformed adapter results fail at the contract boundary"
    (let [ctx {execution-adapter/adapter-key
               (execution-adapter/create
                {:create-child-session! (fn [_ctx _parent _opts]
                                          {:session-id "child-1"})
                 :get-session-data (fn [_ctx _sid]
                                     (throw (ex-info "should not reach get-session-data" {})))})}
          ex (try
               (workflow-attempts/create-step-attempt-session!
                ctx
                "parent-1"
                {:workflow-run-id "run-1"
                 :workflow-step-id "plan"
                 :attempt-id "attempt-1"
                 :session-name "workflow plan attempt"
                 :tool-defs []
                 :thinking-level :off})
               nil
               (catch clojure.lang.ExceptionInfo ex
                 ex))]
      (is (some? ex))
      (is (= :workflow-child-session-create (:contract (ex-data ex))))
      (is (= :result (:stage (ex-data ex))))
      (is (= :psi.workflow-runtime.attempts/create-step-attempt-session!
             (:caller (ex-data ex)))))))

(deftest set-execution-session-model-is-session-scoped-test
  (testing "workflow-owned execution-session model updates are explicitly session-scoped"
    (let [calls* (atom [])
          execution-session {:session-id "child-1"}]
      (with-redefs [psi.workflow-runtime.execution-adapter/set-session-model!
                    (fn [_ctx sid model scope]
                      (swap! calls* conj {:session-id sid :model model :scope scope})
                      {:ok true})]
        (is (= {:session-id "child-1"
                :model {:provider "openai" :id "gpt-5"}}
               (workflow-attempts/set-execution-session-model! {} execution-session {:provider "openai" :id "gpt-5"})))
        (is (= [{:session-id "child-1"
                 :model {:provider "openai" :id "gpt-5"}
                 :scope :session}]
               @calls*))))))

(deftest append-attempt-to-run-test
  (testing "append-attempt-to-run records attempt under the selected step"
    (let [run {:run-id "run-1"
               :status :pending
               :effective-definition {:definition-id "def"
                                      :step-order ["plan"]
                                      :steps {"plan" {:executor {:type :agent}
                                                      :result-schema :any
                                                      :retry-policy {:max-attempts 1
                                                                     :retry-on #{:execution-failed}}}}}
               :workflow-input {}
               :current-step-id "plan"
               :step-runs {"plan" {:step-id "plan" :attempts []}}
               :history []
               :created-at (java.time.Instant/now)
               :updated-at (java.time.Instant/now)}
          attempt {:attempt-id "a1"
                   :status :pending
                   :execution-session-id "child-1"
                   :created-at (java.time.Instant/now)
                   :updated-at (java.time.Instant/now)}
          run' (workflow-attempts/append-attempt-to-run run "plan" attempt)]
      (is (= [attempt] (get-in run' [:step-runs "plan" :attempts]))))))
