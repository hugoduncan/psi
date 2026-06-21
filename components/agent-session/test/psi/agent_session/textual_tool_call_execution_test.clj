(ns psi.agent-session.textual-tool-call-execution-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.ai.models :as models]
   [psi.agent-session.prompt-chain :as prompt-chain]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.core :as turn-runtime]))

(defn- now []
  (java.time.Instant/now))

(defn- stub-provider
  [assistant-text]
  {:name :stub
   :execute (fn [_conversation _model _options]
              {:assistant-message {:role "assistant"
                                   :content [{:type :text :text assistant-text}]
                                   :stop-reason :stop
                                   :timestamp (now)}})})

(defn- prepared-request
  [session-id turn-id model]
  {:prepared-request/id turn-id
   :prepared-request/session-id session-id
   :prepared-request/provider-conversation {:id (str turn-id "-conversation")
                                            :status :active
                                            :created-at (now)
                                            :updated-at (now)
                                            :messages []
                                            :tools #{}}
   :prepared-request/model model
   :prepared-request/ai-options {}
   :prepared-request/session-snapshot {:response-mode :non-streaming}})

(defn- textual-tool-model
  []
  (assoc (models/get-model :claude-3-5-sonnet)
         :capabilities {:textual-tool-calls #{:xml}}))

(defn- execute-text-response!
  [ctx session-id model turn-id text]
  (turn-runtime/execute-prepared-request!
   {:provider-registry (atom {:anthropic (stub-provider text)})}
   ctx
   session-id
   (prepared-request session-id turn-id model)
   nil))

(defn- journal-messages
  [ctx session-id]
  (->> (ss/get-state-value-in ctx (ss/state-path :journal session-id))
       (filter #(= :message (:kind %)))
       (mapv #(get-in % [:data :message]))))

(defn- tool-result-messages
  [ctx session-id]
  (filterv #(= "toolResult" (:role %))
           (journal-messages ctx session-id)))

(deftest parsed-textual-bash-call-runs-through-existing-tool-path-test
  ;; Tests a recovered textual bash call is classified as ordinary tool-use and
  ;; recorded as a normal toolResult by the existing session tool machinery.
  (testing "capability-enabled textual bash call reaches tool execution"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false})
          model (textual-tool-model)
          execution-result (execute-text-response!
                            ctx
                            session-id
                            model
                            "turn-textual-bash"
                            (str "before "
                                 "<tool_call><function=bash>"
                                 "<parameter=command>printf textual-tool-ok</parameter>"
                                 "</function></tool_call>"
                                 " after"))
          continuation (prompt-chain/run-prompt-tools! ctx session-id execution-result nil)
          tool-results (tool-result-messages ctx session-id)
          result-message (first tool-results)]
      (is (= :turn.outcome/tool-use (:execution-result/turn-outcome execution-result)))
      (is (= [{:type :tool-call
               :id "turn-textual-bash/toolcall/1"
               :name "bash"
               :arguments "{\"command\":\"printf textual-tool-ok\"}"}]
             (:execution-result/tool-calls execution-result)))
      (is (= {:continued? true :tool-call-count 1}
             continuation))
      (is (= 1 (count tool-results)))
      (is (= "turn-textual-bash/toolcall/1"
             (:tool-call-id result-message)))
      (is (= "bash" (:tool-name result-message)))
      (is (false? (:is-error result-message)))
      (is (str/includes? (:result-text result-message) "textual-tool-ok")))))

(deftest parsed-unknown-tool-follows-existing-tool-error-policy-test
  ;; Tests unknown recovered tool names are not special-cased: they dispatch to
  ;; the same tool execution path and surface the same error-shaped toolResult.
  (let [[ctx session-id] (test-support/create-test-session {:persist? false})
        execution-result (execute-text-response!
                          ctx
                          session-id
                          (textual-tool-model)
                          "turn-textual-unknown"
                          (str "<tool_call><function=missing_tool>"
                               "<parameter=arg>value</parameter>"
                               "</function></tool_call>"))
        continuation (prompt-chain/run-prompt-tools! ctx session-id execution-result nil)
        [result-message] (tool-result-messages ctx session-id)]
    (is (= [{:type :tool-call
             :id "turn-textual-unknown/toolcall/0"
             :name "missing_tool"
             :arguments "{\"arg\":\"value\"}"}]
           (:execution-result/tool-calls execution-result)))
    (is (= {:continued? true :tool-call-count 1}
           continuation))
    (is (= "missing_tool" (:tool-name result-message)))
    (is (true? (:is-error result-message)))
    (is (str/includes? (:result-text result-message)
                       "Unknown tool: missing_tool"))))

(deftest parsed-unavailable-tool-follows-existing-tool-error-policy-test
  ;; Tests recovered calls to known-but-unavailable tools follow the same
  ;; runtime policy as canonical provider-emitted calls: execution is attempted
  ;; through the ordinary tool path and records an error-shaped toolResult.
  (let [executed-tools (atom [])
        [ctx* session-id] (test-support/create-test-session {:persist? false})
        ctx (assoc ctx* :runtime-tool-executor-fn
                   (fn [_ctx _session-id tool-name _args _opts]
                     (swap! executed-tools conj tool-name)
                     (throw (ex-info (str "Unknown tool: " tool-name)
                                     {:tool tool-name}))))
        execution-result (execute-text-response!
                          ctx
                          session-id
                          (textual-tool-model)
                          "turn-textual-unavailable"
                          (str "<tool_call><function=bash>"
                               "<parameter=command>printf should-not-run</parameter>"
                               "</function></tool_call>"))
        continuation (prompt-chain/run-prompt-tools! ctx session-id execution-result nil)
        [result-message] (tool-result-messages ctx session-id)]
    (is (= [{:type :tool-call
             :id "turn-textual-unavailable/toolcall/0"
             :name "bash"
             :arguments "{\"command\":\"printf should-not-run\"}"}]
           (:execution-result/tool-calls execution-result)))
    (is (= {:continued? true :tool-call-count 1}
           continuation))
    (is (= ["bash"] @executed-tools))
    (is (= "bash" (:tool-name result-message)))
    (is (true? (:is-error result-message)))
    (is (str/includes? (:result-text result-message)
                       "Unknown tool: bash"))))

(deftest default-model-preserves-textual-tool-markup-and-runs-no-tools-test
  ;; Tests frontier/default opt-out keeps textual markup as assistant text and
  ;; produces no session tool execution.
  (let [[ctx session-id] (test-support/create-test-session {:persist? false})
        markup "<tool_call><function=bash><parameter=command>pwd</parameter></function></tool_call>"
        execution-result (execute-text-response!
                          ctx
                          session-id
                          (models/get-model :claude-3-5-sonnet)
                          "turn-textual-disabled"
                          markup)
        continuation (prompt-chain/run-prompt-tools! ctx session-id execution-result nil)]
    (is (= :turn.outcome/stop (:execution-result/turn-outcome execution-result)))
    (is (= [] (:execution-result/tool-calls execution-result)))
    (is (= [{:type :text :text markup}]
           (get-in execution-result [:execution-result/assistant-message :content])))
    (is (= {:continued? false :tool-call-count 0}
           continuation))
    (is (empty? (tool-result-messages ctx session-id)))))
