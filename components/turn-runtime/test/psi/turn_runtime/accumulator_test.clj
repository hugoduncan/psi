(ns psi.turn-runtime.accumulator-test
  "Turn-runtime accumulator and stream-facing tests."
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent]
   [psi.ai.models :as models]
   [psi.ai.providers.anthropic :as anthropic]
   [psi.agent-session.conversation]
   [psi.session-persistence.core :as persist]
   [psi.agent-session.prompt-loop :as prompt-loop]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.accumulator :as accum]
   [psi.turn-runtime.core :as turn-runtime]
   [psi.turn-runtime.stream :as stream]
   [psi.turn-statechart.core :as turn-sc])
  (:import
   [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:private stub-model
  {:provider "stub" :id "stub-model"})

(defn- setup-agent-ctx!
  []
  (let [ctx (agent/create-context)]
    (agent/create-agent-in! ctx {:system-prompt "test prompt"
                                 :tools []})
    ctx))

(defn- setup-session-ctx!
  [agent-ctx]
  (test-support/make-session-ctx {:agent-ctx agent-ctx}))

(deftest text-only-response-test
  (let [agent-ctx    (setup-agent-ctx!)
        [session-ctx session-ctx-id]  (setup-session-ctx! agent-ctx)]
    (with-redefs [turn-runtime/do-stream!
                  (fn [_ai-ctx _conv _model _opts consume-fn]
                    (consume-fn {:type :start})
                    (consume-fn {:type :text-delta :delta "Hello! I'm here to help."})
                    (consume-fn {:type :done :reason :stop}))]
      (let [result (prompt-loop/run-agent-loop!
                    nil session-ctx session-ctx-id agent-ctx stub-model)]
        (is (= "assistant" (:role result)))
        (is (= :stop (:stop-reason result)))
        (is (= "Hello! I'm here to help."
               (some #(when (= :text (:type %)) (:text %))
                     (:content result))))
        (let [turn-ctx (ss/get-state-value-in session-ctx (ss/state-path :turn-ctx session-ctx-id))]
          (is (some? turn-ctx))
          (is (= :done (turn-sc/turn-phase turn-ctx))))))))

(deftest multiple-text-deltas-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :text-delta :delta "Hello"})
                      (consume-fn {:type :text-delta :delta " there"})
                      (consume-fn {:type :text-delta :delta "!"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [turn-runtime/do-stream! stream-fn]
      (let [result (prompt-loop/run-agent-loop!
                    nil session-ctx session-ctx-id agent-ctx stub-model)]
        (is (= "Hello there!"
               (some #(when (= :text (:type %)) (:text %))
                     (:content result))))))))

(deftest thinking-delta-emits-progress-event-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        q           (LinkedBlockingQueue.)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :thinking-delta :content-index 0 :delta "plan"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [turn-runtime/do-stream! stream-fn]
      (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model
                                   {:progress-queue q})
      (let [events (loop [acc []]
                     (if-let [e (.poll q 5 TimeUnit/MILLISECONDS)]
                       (recur (conj acc e))
                       acc))
            thinking (some #(when (= :thinking-delta (:event-kind %)) %) events)
            td       (turn-sc/get-turn-data (ss/get-state-value-in session-ctx (ss/state-path :turn-ctx session-ctx-id)))]
        (is (some? thinking))
        (is (= "plan" (:text thinking)))
        (is (= :done (get-in td [:last-provider-event :type])))
        (is (= :thinking (get-in td [:content-blocks 0 :kind])))
        (is (= 1 (get-in td [:content-blocks 0 :delta-count])))))))

(deftest cumulative-snapshot-text-deltas-replace-instead-of-repeating-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :text-delta :delta "H\n"})
                      (consume-fn {:type :text-delta :delta "He\n"})
                      (consume-fn {:type :text-delta :delta "Hel\n"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [turn-runtime/do-stream! stream-fn]
      (let [result (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model)]
        (is (= "Hel\n"
               (some #(when (= :text (:type %)) (:text %))
                     (:content result))))))))

(deftest incremental-short-prefix-delta-does-not-shrink-streamed-text-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :text-delta :delta "`deps.edn"})
                      (consume-fn {:type :text-delta :delta "`"})
                      (consume-fn {:type :text-delta :delta " contents:"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [turn-runtime/do-stream! stream-fn]
      (let [result (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model)]
        (is (= "`deps.edn` contents:"
               (some #(when (= :text (:type %)) (:text %))
                     (:content result))))))))

(deftest cross-provider-thinking-is-not-replayed-into-anthropic-request-test
  (testing "OpenAI thinking deltas remain transient and are not included in later Anthropic messages"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          _           (ss/update-state-value-in! session-ctx (ss/state-path :session-data session-ctx-id)
                                                 assoc :thinking-level :high)
          user-msg    {:role "user" :content [{:type :text :text "hi"}]}
          openai-model {:provider "openai" :id "gpt-5.4"}
          openai-turn (fn [_ai-ctx _conv _model _opts consume-fn]
                        (consume-fn {:type :start})
                        (consume-fn {:type :thinking-delta :content-index 0 :delta "Plan step"})
                        (consume-fn {:type :text-delta :content-index 1 :delta "Done"})
                        (consume-fn {:type :done :reason :stop}))]
      (with-redefs [turn-runtime/do-stream! openai-turn]
        (ss/append-journal-entry-in! session-ctx session-ctx-id (persist/message-entry user-msg))
        (let [result (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id openai-model)]
          (is (= :stop (:stop-reason result)))
          (is (= "Done"
                 (some #(when (= :text (:type %)) (:text %))
                       (:content result))))))
      (let [messages        (let [journal (ss/get-state-value-in session-ctx (ss/state-path :journal session-ctx-id))]
                              (->> journal
                                   (filter #(= :message (:kind %)))
                                   (mapv #(get-in % [:data :message]))))
            assistant       (last messages)
            anthropic-model (models/get-model :sonnet-4.6)
            conv            (#'psi.agent-session.conversation/agent-messages->ai-conversation
                             "sys" messages [] {:cache-breakpoints #{:system}})
            body            (json/parse-string
                             (:body (#'anthropic/build-request conv anthropic-model {:api-key "test-key"
                                                                                     :thinking-level :high}))
                             true)]
        (is (= "assistant" (:role assistant)))
        (is (= [{:type :text :text "Done"}]
               (:content assistant)))
        (is (not (re-find #"Plan step" (pr-str body))))
        (is (= ["user" "assistant"]
               (mapv :role (:messages body))))
        (is (= "Done"
               (get-in body [:messages 1 :content 0 :text])))))))

(deftest idle-timeout-resets-on-stream-progress-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (future
                        (consume-fn {:type :start})
                        (Thread/sleep 120)
                        (consume-fn {:type :thinking-delta :delta "plan-1"})
                        (Thread/sleep 120)
                        (consume-fn {:type :thinking-delta :delta "plan-2"})
                        (Thread/sleep 120)
                        (consume-fn {:type :done :reason :stop})))]
    (with-redefs [turn-runtime/do-stream! stream-fn
                  turn-runtime/llm-stream-idle-timeout-ms 200
                  turn-runtime/llm-stream-wait-poll-ms 20]
      (let [result (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model)]
        (is (= :stop (:stop-reason result)))))))

(deftest idle-timeout-errors-when-stream-stalls-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (future
                        (consume-fn {:type :start})
                        (Thread/sleep 260)
                        (consume-fn {:type :done :reason :stop})))]
    (with-redefs [turn-runtime/do-stream! stream-fn
                  turn-runtime/llm-stream-idle-timeout-ms 120
                  turn-runtime/llm-stream-wait-poll-ms 20]
      (let [result   (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model)
            turn-ctx (ss/get-state-value-in session-ctx (ss/state-path :turn-ctx session-ctx-id))
            td       (turn-sc/get-turn-data turn-ctx)]
        (is (= :error (:stop-reason result)))
        (is (= "Timeout waiting for LLM response" (:error-message result)))
        (is (= :error (get-in td [:last-provider-event :type])))
        (is (= "Timeout waiting for LLM response"
               (get-in td [:last-provider-event :error-message])))))))

(deftest turn-runtime-wait-for-turn-result-uses-canonical-stream-sentinels-test
  (testing "turn runtime exposes canonical stream timeout sentinel"
    (let [done-p           (promise)
          last-progress-ms (atom (stream/now-ms))]
      (with-redefs [stream/wait-for-turn-result
                    (fn [_done _last _opts]
                      ::stream/timeout)]
        (is (= ::turn-runtime/timeout
               (turn-runtime/wait-for-turn-result done-p last-progress-ms {}))))))
  (testing "turn runtime exposes canonical stream aborted sentinel"
    (let [done-p           (promise)
          last-progress-ms (atom (stream/now-ms))]
      (with-redefs [stream/wait-for-turn-result
                    (fn [_done _last _opts]
                      ::stream/aborted)]
        (is (= ::turn-runtime/aborted
               (turn-runtime/wait-for-turn-result done-p last-progress-ms {})))))))

(deftest tool-lifecycle-progress-derived-from-canonical-event-test
  (testing "progress projection uses the same canonical lifecycle event shape"
    (let [q      (LinkedBlockingQueue.)
          event  {:event-kind :tool-result
                  :tool-id "call-proj"
                  :tool-name "read"
                  :content [{:type :text :text "ok"}]
                  :result-text "ok"
                  :details {:phase :done}
                  :is-error false}]
      (#'accum/emit-progress! q event)
      (let [projected (.poll q 5 TimeUnit/MILLISECONDS)]
        (is (= :agent-event (:type projected)))
        (is (= event (dissoc projected :type)))))))
