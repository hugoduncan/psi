(ns psi.agent-session.textual-tool-call-execution-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.ai.models :as models]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.prompt-chain :as prompt-chain]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.core :as turn-runtime]))

(defn- now []
  (java.time.Instant/now))

(defn- stub-message-provider
  [assistant-message]
  {:name :stub
   :execute (fn [_conversation _model _options]
              {:assistant-message assistant-message})})

(defn- stub-provider
  [assistant-text]
  (stub-message-provider {:role "assistant"
                          :content [{:type :text :text assistant-text}]
                          :stop-reason :stop
                          :timestamp (now)}))

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

(defn- execute-provider-response!
  [ctx session-id model turn-id provider]
  (turn-runtime/execute-prepared-request!
   {:provider-registry (atom {:anthropic provider})}
   ctx
   session-id
   (prepared-request session-id turn-id model)
   nil))

(defn- execute-text-response!
  [ctx session-id model turn-id text]
  (execute-provider-response!
   ctx session-id model turn-id (stub-provider text)))

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
  ;; runtime policy as canonical provider-emitted calls: the ordinary tool path
  ;; resolves the known extension tool, executes it through the real runtime,
  ;; and records the standard error-shaped toolResult when that tool is
  ;; unavailable at execution time.
  (let [[ctx session-id] (test-support/create-test-session {:persist? false})
        _ (ext/register-extension-in! (:extension-registry ctx) "unavailable-tool-test")
        _ (ext/register-tool-in! (:extension-registry ctx)
                                 "unavailable-tool-test"
                                 {:name "known-unavailable"
                                  :description "Known test tool that is blocked by policy."
                                  :parameters {:type "object"
                                               :properties {"value" {:type "string"}}}
                                  :format-request (fn [_args] "known-unavailable …")
                                  :execute (fn [_args _opts]
                                             (throw (ex-info "Tool is unavailable in this session"
                                                             {:tool "known-unavailable"})))})
        execution-result (execute-text-response!
                          ctx
                          session-id
                          (textual-tool-model)
                          "turn-textual-unavailable"
                          (str "<tool_call><function=known-unavailable>"
                               "<parameter=value>abc</parameter>"
                               "</function></tool_call>"))
        continuation (prompt-chain/run-prompt-tools! ctx session-id execution-result nil)
        [result-message] (tool-result-messages ctx session-id)]
    (is (= [{:type :tool-call
             :id "turn-textual-unavailable/toolcall/0"
             :name "known-unavailable"
             :arguments "{\"value\":\"abc\"}"}]
           (:execution-result/tool-calls execution-result)))
    (is (= {:continued? true :tool-call-count 1}
           continuation))
    (is (= "known-unavailable" (:tool-name result-message)))
    (is (true? (:is-error result-message)))
    (is (str/includes? (:result-text result-message)
                       "Tool is unavailable in this session"))))

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

(deftest canonical-and-recovered-tool-errors-have-same-policy-shape-test
  ;; Tests textual recovery does not introduce a separate unavailable/unknown
  ;; tool policy surface: recovered calls and provider-emitted canonical calls
  ;; both reach the same ordinary tool-result error machinery.
  (letfn [(run-case [turn-id assistant-message]
            (let [[ctx session-id] (test-support/create-test-session {:persist? false})
                  execution-result (execute-provider-response!
                                    ctx
                                    session-id
                                    (textual-tool-model)
                                    turn-id
                                    (stub-message-provider assistant-message))
                  continuation (prompt-chain/run-prompt-tools! ctx session-id execution-result nil)
                  [result-message] (tool-result-messages ctx session-id)]
              {:tool-call (first (:execution-result/tool-calls execution-result))
               :continuation continuation
               :result result-message}))]
    (testing "unknown tool policy shape matches canonical provider calls"
      (let [canonical (run-case "turn-canonical-unknown"
                                {:role "assistant"
                                 :content [{:type :tool-call
                                            :id "canonical-unknown"
                                            :name "missing_tool"
                                            :arguments "{\"arg\":\"value\"}"}]
                                 :stop-reason :tool_use
                                 :timestamp (now)})
            recovered (run-case "turn-recovered-unknown"
                                {:role "assistant"
                                 :content [{:type :text
                                            :text (str "<tool_call><function=missing_tool>"
                                                       "<parameter=arg>value</parameter>"
                                                       "</function></tool_call>")}]
                                 :stop-reason :stop
                                 :timestamp (now)})]
        (is (= {:continued? true :tool-call-count 1} (:continuation canonical) (:continuation recovered)))
        (is (= [{:name "missing_tool" :arguments "{\"arg\":\"value\"}"}
                {:name "missing_tool" :arguments "{\"arg\":\"value\"}"}]
               (mapv #(select-keys (:tool-call %) [:name :arguments]) [canonical recovered])))
        (is (= ["missing_tool" "missing_tool"]
               (mapv #(get-in % [:result :tool-name]) [canonical recovered])))
        (is (= [true true]
               (mapv #(get-in % [:result :is-error]) [canonical recovered])))
        (is (every? #(str/includes? (get-in % [:result :result-text])
                                    "Unknown tool: missing_tool")
                    [canonical recovered]))))
    (testing "known unavailable tool policy shape matches canonical provider calls"
      (letfn [(run-unavailable-case [turn-id assistant-message]
                (let [[ctx session-id] (test-support/create-test-session {:persist? false})
                      _ (ext/register-extension-in! (:extension-registry ctx) "unavailable-baseline-test")
                      _ (ext/register-tool-in! (:extension-registry ctx)
                                               "unavailable-baseline-test"
                                               {:name "known-unavailable"
                                                :description "Known test tool that is blocked by policy."
                                                :parameters {:type "object"
                                                             :properties {"value" {:type "string"}}}
                                                :format-request (fn [_args] "known-unavailable …")
                                                :execute (fn [_args _opts]
                                                           (throw (ex-info "Tool is unavailable in this session"
                                                                           {:tool "known-unavailable"})))})
                      execution-result (execute-provider-response!
                                        ctx
                                        session-id
                                        (textual-tool-model)
                                        turn-id
                                        (stub-message-provider assistant-message))
                      continuation (prompt-chain/run-prompt-tools! ctx session-id execution-result nil)
                      [result-message] (tool-result-messages ctx session-id)]
                  {:tool-call (first (:execution-result/tool-calls execution-result))
                   :continuation continuation
                   :result result-message}))]
        (let [canonical (run-unavailable-case
                         "turn-canonical-unavailable"
                         {:role "assistant"
                          :content [{:type :tool-call
                                     :id "canonical-unavailable"
                                     :name "known-unavailable"
                                     :arguments "{\"value\":\"abc\"}"}]
                          :stop-reason :tool_use
                          :timestamp (now)})
              recovered (run-unavailable-case
                         "turn-recovered-unavailable"
                         {:role "assistant"
                          :content [{:type :text
                                     :text (str "<tool_call><function=known-unavailable>"
                                                "<parameter=value>abc</parameter>"
                                                "</function></tool_call>")}]
                          :stop-reason :stop
                          :timestamp (now)})]
          (is (= {:continued? true :tool-call-count 1} (:continuation canonical) (:continuation recovered)))
          (is (= [{:name "known-unavailable" :arguments "{\"value\":\"abc\"}"}
                  {:name "known-unavailable" :arguments "{\"value\":\"abc\"}"}]
                 (mapv #(select-keys (:tool-call %) [:name :arguments]) [canonical recovered])))
          (is (= ["known-unavailable" "known-unavailable"]
                 (mapv #(get-in % [:result :tool-name]) [canonical recovered])))
          (is (= [true true]
                 (mapv #(get-in % [:result :is-error]) [canonical recovered])))
          (is (every? #(str/includes? (get-in % [:result :result-text])
                                      "Tool is unavailable in this session")
                      [canonical recovered])))))))
