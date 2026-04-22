(ns psi.agent-session.turn-accumulator-test
  "Tests for turn accumulation — stream events → turn-ctx state."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent]
   [psi.ai.models :as models]
   [psi.ai.providers.anthropic :as anthropic]
   [psi.agent-session.conversation :as conv-translate]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-loop :as prompt-loop]
   [psi.agent-session.prompt-runtime :as prompt-runtime]
   [psi.agent-session.prompt-stream :as prompt-stream]
   [psi.agent-session.prompt-turn :as prompt-turn]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.turn-accumulator :as accum]
   [psi.agent-session.turn-statechart :as turn-sc])
  (:import
   [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:private stub-model
  {:provider "stub" :id "stub-model"})

(defn- stub-text-stream
  [text]
  (fn [_ai-ctx _conv _model _opts consume-fn]
    (consume-fn {:type :start})
    (consume-fn {:type :text-delta :delta text})
    (consume-fn {:type :done :reason :stop})))

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

(deftest text-only-response-test
  (let [agent-ctx    (setup-agent-ctx!)
        [session-ctx session-ctx-id]  (setup-session-ctx! agent-ctx)]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream!
                  (stub-text-stream "Hello! I'm here to help.")]
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

(deftest error-response-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream!
                  (fn [_ai-ctx _conv _model _opts consume-fn]
                    (consume-fn {:type :start})
                    (consume-fn {:type :error :error-message "Connection refused"}))]
      (let [result   (prompt-loop/run-agent-loop!
                      nil session-ctx session-ctx-id agent-ctx stub-model)
            turn-ctx (ss/get-state-value-in session-ctx (ss/state-path :turn-ctx session-ctx-id))]
        (is (= :error (:stop-reason result)))
        (is (= :error (turn-sc/turn-phase turn-ctx)))
        (is (= "Connection refused"
               (:error-message (turn-sc/get-turn-data turn-ctx))))))))

(deftest multiple-text-deltas-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :text-delta :delta "Hello"})
                      (consume-fn {:type :text-delta :delta " there"})
                      (consume-fn {:type :text-delta :delta "!"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn]
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
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn]
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

(deftest thinking-start-end-without-delta-still-produces-thinking-block-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :thinking-start :content-index 0})
                      (consume-fn {:type :thinking-end :content-index 0})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn]
      (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model)
      (let [td (turn-sc/get-turn-data
                (ss/get-state-value-in session-ctx
                                       (ss/state-path :turn-ctx session-ctx-id)))]
        (is (= :thinking (get-in td [:content-blocks 0 :kind])))
        (is (= :closed (get-in td [:content-blocks 0 :status])))))))

(deftest thinking-delta-cumulative-snapshot-normalised-test
  "Anthropic sends thinking_delta events as cumulative snapshots (each event
  contains the full thinking text so far). Verify that the executor normalises
  these into non-duplicating accumulated text so consumers can safely replace."
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        q           (LinkedBlockingQueue.)
        ;; Simulate Anthropic cumulative snapshots: each delta = full text so far
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :thinking-delta :content-index 0 :delta "Now"})
                      (consume-fn {:type :thinking-delta :content-index 0 :delta "Now I see"})
                      (consume-fn {:type :thinking-delta :content-index 0 :delta "Now I see the flow"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn]
      (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model
                                   {:progress-queue q})
      (let [events (loop [acc []]
                     (if-let [e (.poll q 5 TimeUnit/MILLISECONDS)]
                       (recur (conj acc e))
                       acc))
            thinking-events (filterv #(= :thinking-delta (:event-kind %)) events)
            last-thinking   (last thinking-events)]
        ;; Each emitted event should carry the full accumulated text (replace semantics)
        (is (= 3 (count thinking-events)))
        (is (= "Now"              (:text (nth thinking-events 0))))
        (is (= "Now I see"        (:text (nth thinking-events 1))))
        (is (= "Now I see the flow" (:text last-thinking)))))))

(deftest thinking-delta-resets-after-toolcall-start-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        q           (LinkedBlockingQueue.)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :thinking-delta :content-index 0 :delta "plan-1"})
                      (consume-fn {:type :toolcall-start :content-index 0 :id "t1" :name "read"})
                      (consume-fn {:type :thinking-delta :content-index 0 :delta "plan-2"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn]
      (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model
                                   {:progress-queue q})
      (let [events (loop [acc []]
                     (if-let [e (.poll q 5 TimeUnit/MILLISECONDS)]
                       (recur (conj acc e))
                       acc))
            thinking-events (filterv #(= :thinking-delta (:event-kind %)) events)]
        (is (= ["plan-1" "plan-2"] (mapv :text thinking-events)))))))

(deftest openai-thinking-delta-resets-after-toolcall-start-with-different-index-test
  (let [agent-ctx    (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        q            (LinkedBlockingQueue.)
        openai-model {:provider "openai" :id "gpt-5.3-codex"}
        stream-fn    (fn [_ai-ctx _conv _model _opts consume-fn]
                       (consume-fn {:type :start})
                       ;; thinking on idx 0
                       (consume-fn {:type :thinking-delta :content-index 0 :delta "plan-1"})
                       ;; tool call on idx 1 (different index)
                       (consume-fn {:type :toolcall-start :content-index 1 :id "t1" :name "read"})
                       ;; next thinking segment should start fresh (not plan-1plan-2)
                       (consume-fn {:type :thinking-delta :content-index 0 :delta "plan-2"})
                       (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn]
      (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id openai-model
                                   {:progress-queue q})
      (let [events          (loop [acc []]
                              (if-let [e (.poll q 5 TimeUnit/MILLISECONDS)]
                                (recur (conj acc e))
                                acc))
            thinking-events (filterv #(= :thinking-delta (:event-kind %)) events)]
        (is (= ["plan-1" "plan-2"]
               (mapv :text thinking-events)))))))

(deftest toolcall-assembly-emits-live-progress-events-with-canonical-id-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        q           (LinkedBlockingQueue.)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :thinking-delta :content-index 0 :delta "plan"})
                      (consume-fn {:type :toolcall-start :content-index 1 :name "read"})
                      (consume-fn {:type :toolcall-delta :content-index 1 :delta "{\"path\":\"foo.clj\"}"})
                      (consume-fn {:type :toolcall-end :content-index 1})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn]
      (let [assistant-message (#'prompt-turn/stream-turn! nil session-ctx session-ctx-id stub-model nil q)
            events      (loop [acc []]
                          (if-let [e (.poll q 5 TimeUnit/MILLISECONDS)]
                            (recur (conj acc e))
                            acc))
            kinds       (mapv :event-kind events)
            assembly    (filterv #(= :tool-call-assembly (:event-kind %)) events)
            first-start (first assembly)
            first-delta (second assembly)
            first-end   (nth assembly 2)
            tool-call-block (some #(when (= :tool-call (:type %)) %) (:content assistant-message))]
        (is (= [:thinking-delta :tool-call-assembly :tool-call-assembly :tool-call-assembly]
               kinds))
        (is (= :start (:phase first-start)))
        (is (= :delta (:phase first-delta)))
        (is (= :end (:phase first-end)))
        (is (= "read" (:tool-name first-start)))
        (is (= "{\"path\":\"foo.clj\"}" (:arguments first-delta)))
        (is (string? (:tool-id first-start)))
        (is (str/includes? (:tool-id first-start) "/toolcall/1"))
        (is (= (:tool-id first-start) (:id tool-call-block)))))))

(deftest toolcall-assembly-preserves-provider-id-when-present-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        q           (LinkedBlockingQueue.)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :toolcall-start :content-index 2 :id "call-xyz" :name "bash"})
                      (consume-fn {:type :toolcall-delta :content-index 2 :delta "{\"command\":\"pwd\"}"})
                      (consume-fn {:type :toolcall-end :content-index 2})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn]
      (let [assistant-message (#'prompt-turn/stream-turn! nil session-ctx session-ctx-id stub-model nil q)
            events (loop [acc []]
                     (if-let [e (.poll q 5 TimeUnit/MILLISECONDS)]
                       (recur (conj acc e))
                       acc))
            assembly (filterv #(= :tool-call-assembly (:event-kind %)) events)
            tool-call-block (some #(when (= :tool-call (:type %)) %) (:content assistant-message))]
        (is (= ["call-xyz" "call-xyz" "call-xyz"] (mapv :tool-id assembly)))
        (is (= "call-xyz" (:id tool-call-block)))))))

(deftest text-boundary-events-are-recorded-in-turn-data-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :text-start :content-index 0})
                      (consume-fn {:type :text-delta :content-index 0 :delta "Hello"})
                      (consume-fn {:type :text-end :content-index 0})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn]
      (let [result   (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model)
            turn-ctx (ss/get-state-value-in session-ctx (ss/state-path :turn-ctx session-ctx-id))
            td       (turn-sc/get-turn-data turn-ctx)]
        (is (= "Hello"
               (some #(when (= :text (:type %)) (:text %))
                     (:content result))))
        (is (= :done (get-in td [:last-provider-event :type])))
        (is (= :text (get-in td [:content-blocks 0 :kind])))
        (is (= :closed (get-in td [:content-blocks 0 :status])))
        (is (= 1 (get-in td [:content-blocks 0 :delta-count])))))))

(deftest cumulative-snapshot-text-deltas-replace-instead-of-repeating-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      ;; Snapshot-style chunks that differ near tail (newline churn)
                      ;; should converge to the latest snapshot, not repeat cumulatively.
                      (consume-fn {:type :text-delta :delta "H\n"})
                      (consume-fn {:type :text-delta :delta "He\n"})
                      (consume-fn {:type :text-delta :delta "Hel\n"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn]
      (let [result (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model)]
        (is (= "Hel\n"
               (some #(when (= :text (:type %)) (:text %))
                     (:content result))))))))

(deftest incremental-short-prefix-delta-does-not-shrink-streamed-text-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      ;; Reproduces the real regression where a short incremental delta
                      ;; ("`") replaced the whole in-progress text.
                      (consume-fn {:type :text-delta :delta "`deps.edn"})
                      (consume-fn {:type :text-delta :delta "`"})
                      (consume-fn {:type :text-delta :delta " contents:"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn]
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
      (with-redefs [psi.agent-session.prompt-runtime/do-stream! openai-turn]
        (ss/journal-append-in! session-ctx session-ctx-id (persist/message-entry user-msg))
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
            conv            (#'conv-translate/agent-messages->ai-conversation
                             "sys" messages [] {:cache-breakpoints #{:system}})
            body            (json/parse-string
                             (:body (#'anthropic/build-request conv anthropic-model {:api-key "test-key"
                                                                                     :thinking-level :high}))
                             true)]
        (is (= "assistant" (:role assistant)))
        (is (= [{:type :text :text "Done"}]
               (:content assistant))
            "final persisted assistant message should exclude transient thinking")
        (is (not (re-find #"Plan step" (pr-str body)))
            "OpenAI thinking must not be replayed into Anthropic request body")
        (is (= ["user" "assistant"]
               (mapv :role (:messages body))))
        (is (= "Done"
               (get-in body [:messages 1 :content 0 :text])))))))

(deftest anthropic-thinking-blocks-roundtrip-into-follow-up-request-test
  (testing "Anthropic thinking blocks with signatures are preserved for the next Anthropic request"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          _           (ss/update-state-value-in! session-ctx (ss/state-path :session-data session-ctx-id)
                                                 assoc :thinking-level :high)
          user-msg    {:role "user" :content [{:type :text :text "hi"}]}
          anthropic-model {:provider "anthropic" :id "claude-sonnet-4-6"}
          stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                        (consume-fn {:type :start})
                        (consume-fn {:type :thinking-start :content-index 0 :thinking "" :signature ""})
                        (consume-fn {:type :thinking-delta :content-index 0 :delta "Plan"})
                        (consume-fn {:type :thinking-signature-delta :content-index 0 :signature "sig-1"})
                        (consume-fn {:type :toolcall-start :content-index 1 :id "call_1" :name "read"})
                        (consume-fn {:type :toolcall-delta :content-index 1 :delta "{\"path\":\"README.md\"}"})
                        (consume-fn {:type :toolcall-end :content-index 1})
                        (consume-fn {:type :done :reason :tool_use}))]
      (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn]
        (agent/start-loop-in! agent-ctx [user-msg])
        (ss/journal-append-in! session-ctx session-ctx-id (persist/message-entry user-msg))
        (let [result (#'prompt-turn/stream-turn! nil session-ctx session-ctx-id anthropic-model nil nil)
              msgs   (let [journal (ss/get-state-value-in session-ctx (ss/state-path :journal session-ctx-id))]
                       (->> journal
                            (filter #(= :message (:kind %)))
                            (mapv #(get-in % [:data :message]))))
              conv   (#'conv-translate/agent-messages->ai-conversation
                      "sys" msgs [] {:cache-breakpoints #{:system}})
              body   (json/parse-string
                      (:body (#'anthropic/build-request conv (models/get-model :sonnet-4.6)
                                                        {:api-key "test-key"
                                                         :thinking-level :high}))
                      true)
              assistant-blocks (get-in body [:messages 1 :content])]
          (is (= :tool_use (:stop-reason result)))
          (is (= "Plan" (some #(when (= :thinking (:type %)) (:text %))
                              (:content result))))
          (is (= "thinking" (get-in assistant-blocks [0 :type])))
          (is (= "Plan" (get-in assistant-blocks [0 :thinking])))
          (is (= "sig-1" (get-in assistant-blocks [0 :signature])))
          (is (= "tool_use" (get-in assistant-blocks [1 :type])))
          (is (= "read" (get-in assistant-blocks [1 :name])))
          (is (str/includes? (pr-str body) "sig-1")))))))

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
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn
                  psi.agent-session.prompt-runtime/llm-stream-idle-timeout-ms 200
                  psi.agent-session.prompt-runtime/llm-stream-wait-poll-ms 20]
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
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn
                  psi.agent-session.prompt-runtime/llm-stream-idle-timeout-ms 120
                  psi.agent-session.prompt-runtime/llm-stream-wait-poll-ms 20]
      (let [result   (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model)
            turn-ctx (ss/get-state-value-in session-ctx (ss/state-path :turn-ctx session-ctx-id))
            td       (turn-sc/get-turn-data turn-ctx)]
        (is (= :error (:stop-reason result)))
        (is (= "Timeout waiting for LLM response" (:error-message result)))
        (is (= :error (get-in td [:last-provider-event :type])))
        (is (= "Timeout waiting for LLM response"
               (get-in td [:last-provider-event :error-message])))))))

(deftest prompt-runtime-wait-for-turn-result-uses-canonical-prompt-stream-sentinels-test
  (testing "prompt runtime exposes canonical prompt-stream timeout sentinel"
    (let [done-p            (promise)
          last-progress-ms  (atom (prompt-stream/now-ms))]
      (with-redefs [psi.agent-session.prompt-stream/wait-for-turn-result
                    (fn [_done _last _opts]
                      ::prompt-stream/timeout)]
        (is (= ::prompt-runtime/timeout
               (prompt-runtime/wait-for-turn-result done-p last-progress-ms {}))))))
  (testing "prompt runtime exposes canonical prompt-stream aborted sentinel"
    (let [done-p            (promise)
          last-progress-ms  (atom (prompt-stream/now-ms))]
      (with-redefs [psi.agent-session.prompt-stream/wait-for-turn-result
                    (fn [_done _last _opts]
                      ::prompt-stream/aborted)]
        (is (= ::prompt-runtime/aborted
               (prompt-runtime/wait-for-turn-result done-p last-progress-ms {})))))))

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
