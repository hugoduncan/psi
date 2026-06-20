(ns psi.turn-runtime.core-test
  "Turn-runtime execution tests — live turn execution, prepared request execution,
   and higher-level prompt-loop consumers that exercise the extracted runtime."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent]
   [psi.session-persistence.core :as persist]
   [psi.agent-session.prompt-loop :as prompt-loop]
   [psi.turn-runtime.recording :as prompt-recording]
   [psi.agent-session.prompt-turn :as prompt-turn]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tool-runtime-adapter :as tool-runtime-adapter]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.core :as turn-runtime]
   [psi.turn-statechart.core :as turn-sc])
  (:import
   (java.util.concurrent ExecutorService Executors)))

(def ^:private stub-model
  {:provider "stub" :id "stub-model"})

(defn- stub-text-stream
  [text]
  (fn [_ai-ctx _conv _model _opts consume-fn]
    (consume-fn {:type :start})
    (consume-fn {:type :text-delta :delta text})
    (consume-fn {:type :done :reason :stop})))

(defn- stub-event-stream
  [events]
  (fn [_ai-ctx _conv _model _opts consume-fn]
    (doseq [event events]
      (consume-fn event))))

(defn- setup-agent-ctx!
  []
  (let [ctx (agent/create-context)]
    (agent/create-agent-in! ctx {:system-prompt "test prompt"
                                 :tools []})
    ctx))

(defn- setup-session-ctx!
  "Returns [ctx session-id]."
  [agent-ctx]
  (test-support/make-session-ctx {:agent-ctx agent-ctx}))

(defn- journal-messages
  "Derive messages from the persistence journal in ctx."
  [ctx session-id]
  (let [journal (ss/get-state-value-in ctx (ss/state-path :journal session-id))]
    (->> journal
         (filter #(= :message (:kind %)))
         (mapv #(get-in % [:data :message])))))

(deftest agent-core-lifecycle-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}]
    (with-redefs [turn-runtime/do-stream!
                  (stub-text-stream "response")]
      (ss/append-journal-entry-in! session-ctx session-ctx-id (persist/message-entry user-msg))
      (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model)
      (let [session-id session-ctx-id
            msgs       (journal-messages session-ctx session-id)]
        (is (>= (count msgs) 2))
        (is (= "user" (:role (first msgs))))
        (is (= "assistant" (:role (second msgs))))))))

(deftest turn-ctx-atom-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)]
    (with-redefs [turn-runtime/do-stream!
                  (stub-text-stream "ok")]
      (let [result (prompt-loop/run-agent-loop!
                    nil session-ctx session-ctx-id agent-ctx stub-model)]
        (is (= "assistant" (:role result))))))

  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)]
    (with-redefs [turn-runtime/do-stream!
                  (stub-text-stream "hello world")]
      (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model)
      (let [turn-ctx (ss/get-state-value-in session-ctx (ss/state-path :turn-ctx session-ctx-id))
            td       (turn-sc/get-turn-data turn-ctx)]
        (is (= "hello world" (:text-buffer td)))
        (is (some? (:final-message td)))))))

(deftest thinking-level-forwarded-to-ai-options-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        _           (ss/update-state-value-in! session-ctx (ss/state-path :session-data session-ctx-id)
                                               assoc :thinking-level :high)
        seen-opts   (atom nil)
        stream-fn   (fn [_ai-ctx _conv _model opts consume-fn]
                      (reset! seen-opts opts)
                      (consume-fn {:type :start})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [turn-runtime/do-stream! stream-fn]
      (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model)
      (is (= :high (:thinking-level @seen-opts))))))

(deftest session-idle-timeout-config-is-forwarded-to-ai-options-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx* session-ctx-id] (setup-session-ctx! agent-ctx)
        session-ctx (assoc session-ctx* :config {:llm-stream-idle-timeout-ms 777})
        seen-opts   (atom nil)
        stream-fn   (fn [_ai-ctx _conv _model opts consume-fn]
                      (reset! seen-opts opts)
                      (consume-fn {:type :start})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [turn-runtime/do-stream! stream-fn]
      (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model)
      (is (= 777 (:llm-stream-idle-timeout-ms @seen-opts))))))

(deftest classify-turn-outcome-test
  (testing "text-only assistant message is terminal stop"
    (let [assistant-msg {:role "assistant"
                         :content [{:type :text :text "done"}]
                         :stop-reason :stop}
          outcome (prompt-recording/classify-assistant-message assistant-msg)]
      (is (= :turn.outcome/stop (:turn/outcome outcome)))
      (is (= assistant-msg (:assistant-message outcome)))
      (is (= [] (:tool-calls outcome))))))

(deftest classify-turn-outcome-tool-use-test
  (testing "assistant message with tool-call content is a tool-use outcome"
    (let [assistant-msg {:role "assistant"
                         :content [{:type :text :text "checking"}
                                   {:type :tool-call :id "call-1" :name "read" :arguments "{}"}]
                         :stop-reason :tool_use}
          outcome (prompt-recording/classify-assistant-message assistant-msg)]
      (is (= :turn.outcome/tool-use (:turn/outcome outcome)))
      (is (= assistant-msg (:assistant-message outcome)))
      (is (= [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}]
             (:tool-calls outcome))))))

(deftest classify-turn-outcome-error-test
  (testing "error assistant message is terminal error even if malformed tool-call content is present"
    (let [assistant-msg {:role "assistant"
                         :content [{:type :error :text "boom"}
                                   {:type :tool-call :id "call-1" :name "read" :arguments "{}"}]
                         :stop-reason :error}
          outcome (prompt-recording/classify-assistant-message assistant-msg)]
      (is (= :turn.outcome/error (:turn/outcome outcome)))
      (is (= assistant-msg (:assistant-message outcome)))
      (is (= [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}]
             (:tool-calls outcome))))))

(deftest finish-agent-loop-test
  (testing "success path returns result"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          result      {:role "assistant" :content [] :stop-reason :stop}]
      (is (= result (#'prompt-loop/finish-agent-loop! session-ctx session-ctx-id result)))))

  (testing "error path returns result"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          result      {:role "assistant" :content [] :stop-reason :error :error-message "boom"}]
      (is (= result (#'prompt-loop/finish-agent-loop! session-ctx session-ctx-id result))))))

(deftest run-agent-loop-lifecycle-test
  (testing "run-agent-loop! runs body and finishes (caller pre-journals)"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          user-msg    {:role "user" :content [{:type :text :text "hi"}]}
          calls       (atom [])]
      (with-redefs [psi.agent-session.prompt-turn/run-turn-loop!
                    (fn [_ _ _ _ extra-ai-options progress-queue]
                      (swap! calls conj [:body extra-ai-options progress-queue])
                      {:role "assistant" :content [{:type :text :text "done"}] :stop-reason :stop})
                    psi.agent-session.prompt-loop/finish-agent-loop!
                    (fn [_ _ result]
                      (swap! calls conj [:finish (:stop-reason result)])
                      result)]
        (ss/append-journal-entry-in! session-ctx session-ctx-id (persist/message-entry user-msg))
        (let [result (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model
                                                  {:api-key "k"})]
          (is (= :stop (:stop-reason result)))
          (is (= :body (ffirst @calls)))
          (is (= :finish (first (second @calls))))
          (is (= 1 (count (journal-messages session-ctx session-ctx-id)))))))))

(deftest execute-one-turn-test
  (testing "single-turn execution returns assistant message and canonical classified outcome"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          user-msg    {:role "user" :content [{:type :text :text "hi"}]}]
      (agent/start-loop-in! agent-ctx [user-msg])
      (with-redefs [turn-runtime/do-stream!
                    (stub-text-stream "one-turn")]
        (let [assistant-msg (#'prompt-turn/stream-turn! nil session-ctx session-ctx-id stub-model nil nil)
              outcome       (prompt-recording/classify-assistant-message assistant-msg)]
          (is (= "assistant" (:role assistant-msg)))
          (is (= :turn.outcome/stop (:turn/outcome outcome)))
          (is (= "one-turn"
                 (some #(when (= :text (:type %)) (:text %))
                       (:content assistant-msg)))))))))

(deftest stream-turn-recovers-textual-tool-call-test
  ;; Tests streaming final assembly uses the textual tool-call normalizer when opted in.
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        model       (assoc stub-model :capabilities {:textual-tool-calls #{:xml}})]
    (agent/start-loop-in! agent-ctx [user-msg])
    (with-redefs [turn-runtime/do-stream!
                  (stub-text-stream "prefix <tool_call><function=bash><parameter=command>pwd</parameter></function></tool_call> suffix")]
      (let [assistant-msg (#'prompt-turn/stream-turn! nil session-ctx session-ctx-id model nil nil)
            turn-id       (:turn-id (turn-sc/get-turn-data
                                     (ss/get-state-value-in session-ctx (ss/state-path :turn-ctx session-ctx-id))))]
        (is (= [{:type :text :text "prefix "}
                {:type :tool-call
                 :id (str turn-id "/toolcall/0")
                 :name "bash"
                 :arguments "{\"command\":\"pwd\"}"}
                {:type :text :text " suffix"}]
               (:content assistant-msg)))))))

(deftest streaming-final-content-preserves-provider-index-order-test
  ;; Tests mixed streaming provider tool calls keep content-index order before textual recovery.
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        model       (assoc stub-model :capabilities {:textual-tool-calls #{:xml}})]
    (agent/start-loop-in! agent-ctx [user-msg])
    (with-redefs [turn-runtime/do-stream!
                  (stub-event-stream [{:type :start}
                                      {:type :toolcall-start :content-index 1 :id "provider-call" :name "read"}
                                      {:type :toolcall-delta :content-index 1 :delta "{}"}
                                      {:type :toolcall-end :content-index 1}
                                      {:type :text-delta
                                       :content-index 2
                                       :delta "prefix <tool_call><function=bash><parameter=command>pwd</parameter></function></tool_call> suffix"}
                                      {:type :done :reason :stop}])]
      (let [assistant-msg (#'prompt-turn/stream-turn! nil session-ctx session-ctx-id model nil nil)
            turn-id       (:turn-id (turn-sc/get-turn-data
                                     (ss/get-state-value-in session-ctx (ss/state-path :turn-ctx session-ctx-id))))]
        (is (= [{:type :tool-call :id "provider-call" :name "read" :arguments "{}" :call-summary nil}
                {:type :text :text "prefix "}
                {:type :tool-call
                 :id (str turn-id "/toolcall/0")
                 :name "bash"
                 :arguments "{\"command\":\"pwd\"}"}
                {:type :text :text " suffix"}]
               (:content assistant-msg)))))))

(deftest run-turn-loop-test
  (testing "multi-turn loop separates one-turn execution from recursive control"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          calls       (atom [])]
      (with-redefs [psi.agent-session.prompt-turn/stream-turn!
                    (fn [_ _ _ _ _ _]
                      (let [n (count @calls)]
                        (if (zero? n)
                          {:role "assistant"
                           :content [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}]
                           :stop-reason :tool_use}
                          {:role "assistant"
                           :content [{:type :text :text "done"}]
                           :stop-reason :stop})))
                    psi.agent-session.tool-runtime-adapter/run-tool-calls!
                    (fn [_ _ tool-calls _]
                      (swap! calls conj (mapv :id tool-calls))
                      [{:tool-call-id "call-1"}])]
        (let [result (#'prompt-turn/run-turn-loop! nil session-ctx session-ctx-id stub-model nil nil)]
          (is (= [["call-1"]] @calls))
          (is (= :stop (:stop-reason result)))
          (is (= "done"
                 (some #(when (= :text (:type %)) (:text %))
                       (:content result))))))))

  (testing "one tool batch still yields exactly one follow-up assistant turn"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          turn-count   (atom 0)
          tool-batches (atom [])]
      (with-redefs [psi.agent-session.prompt-turn/stream-turn!
                    (fn [_ _ _ _ _ _]
                      (swap! turn-count inc)
                      (if (= 1 @turn-count)
                        {:role "assistant"
                         :content [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}
                                   {:type :tool-call :id "call-2" :name "bash" :arguments "{}"}]
                         :stop-reason :tool_use}
                        {:role "assistant"
                         :content [{:type :text :text "done"}]
                         :stop-reason :stop}))
                    psi.agent-session.tool-runtime-adapter/run-tool-calls!
                    (fn [_ _ tool-calls _]
                      (swap! tool-batches conj (mapv :id tool-calls))
                      [{:tool-call-id "call-1"}
                       {:tool-call-id "call-2"}])]
        (let [result (#'prompt-turn/run-turn-loop! nil session-ctx session-ctx-id stub-model nil nil)]
          (is (= 2 @turn-count))
          (is (= [["call-1" "call-2"]] @tool-batches))
          (is (= :stop (:stop-reason result))))))))

(deftest run-tool-calls-test
  (testing "run-tool-calls! executes prepared+record phases and returns results in tool-call order"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          tool-calls   [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}
                        {:type :tool-call :id "call-2" :name "bash" :arguments "{}"}]
          prepared     (atom [])
          records      (atom [])]
      (with-redefs [psi.agent-session.tool-runtime-adapter/execute-tool-call-prepared!
                    (fn [_ _ tc _ _]
                      (swap! prepared conj (:id tc))
                      {:tool-call tc
                       :tool-result {:content (str "ok-" (:id tc)) :is-error false}
                       :result-message {:role "toolResult"
                                        :tool-call-id (:id tc)
                                        :tool-name (:name tc)
                                        :content [{:type :text :text (str "ok-" (:id tc))}]}
                       :effective-policy nil})
                    psi.agent-session.tool-runtime-adapter/record-tool-call-prepared-result!
                    (fn [_ _ shaped _]
                      (swap! records conj (get-in shaped [:result-message :tool-call-id]))
                      (:result-message shaped))]
        (let [results (#'tool-runtime-adapter/run-tool-calls! session-ctx session-ctx-id tool-calls nil)]
          (is (= #{"call-1" "call-2"} (set @prepared)))
          (is (= ["call-1" "call-2"] @records))
          (is (= ["call-1" "call-2"] (mapv :tool-call-id results))))))))

(deftest run-tool-calls-bounded-parallelism-test
  (testing "run-tool-calls! executes concurrently with the ctx-owned shared executor"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx* session-ctx-id] (setup-session-ctx! agent-ctx)
          session-ctx  (assoc session-ctx* :tool-batch-executor (Executors/newFixedThreadPool 2))
          tool-calls   [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}
                        {:type :tool-call :id "call-2" :name "bash" :arguments "{}"}
                        {:type :tool-call :id "call-3" :name "write" :arguments "{}"}]
          active       (atom 0)
          max-active   (atom 0)
          started      (promise)
          release      (promise)]
      (with-redefs [psi.agent-session.tool-runtime-adapter/execute-tool-call-prepared!
                    (fn [_ _ tc _ _]
                      (let [n (swap! active inc)]
                        (swap! max-active max n)
                        (when (= 2 n) (deliver started true))
                        (when (= "call-1" (:id tc))
                          @started
                          @release)
                        (Thread/sleep 20)
                        (swap! active dec)
                        {:tool-call tc
                         :tool-result {:content (str "ok-" (:id tc)) :is-error false}
                         :result-message {:role "toolResult"
                                          :tool-call-id (:id tc)
                                          :tool-name (:name tc)
                                          :content [{:type :text :text (str "ok-" (:id tc))}]}
                         :effective-policy nil}))
                    psi.agent-session.tool-runtime-adapter/record-tool-call-prepared-result!
                    (fn [_ _ shaped _]
                      (:result-message shaped))]
        (let [runner (future (#'tool-runtime-adapter/run-tool-calls! session-ctx session-ctx-id tool-calls nil))]
          @started
          (deliver release true)
          (let [results @runner]
            (is (= 2 @max-active))
            (is (= ["call-1" "call-2" "call-3"] (mapv :tool-call-id results))))))
      (.shutdown ^ExecutorService (:tool-batch-executor session-ctx)))))
