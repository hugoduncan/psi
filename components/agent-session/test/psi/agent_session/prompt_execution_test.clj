(ns psi.agent-session.prompt-execution-test
  "Integration tests for prompt execution — lifecycle, session management, child sessions."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [psi.agent-core.core :as agent]
   [psi.agent-session.prompt-loop :as prompt-loop]
   [psi.agent-session.prompt-recording :as prompt-recording]
   [psi.agent-session.prompt-runtime :as prompt-runtime]
   [psi.agent-session.prompt-turn :as prompt-turn]
   [psi.agent-session.tool-batch :as tool-batch]
   [psi.agent-session.core :as session-core]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tool-execution :as tool-exec]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.agent-session.turn-statechart :as turn-sc])
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
  ;; After run-agent-loop!, the persistence journal contains user + assistant messages.
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream!
                  (stub-text-stream "response")]
      (ss/journal-append-in! session-ctx session-ctx-id (persist/message-entry user-msg))
      (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model)
      (let [session-id session-ctx-id
            msgs       (journal-messages session-ctx session-id)]
        (is (>= (count msgs) 2)
            (str "expected >= 2 messages, got " (count msgs)))
        (is (= "user" (:role (first msgs))))
        (is (= "assistant" (:role (second msgs))))))))

(deftest turn-ctx-atom-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream!
                  (stub-text-stream "ok")]
      (let [result (prompt-loop/run-agent-loop!
                    nil session-ctx session-ctx-id agent-ctx stub-model)]
        (is (= "assistant" (:role result))))))

  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream!
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
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn]
      (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model)
      (is (= :high (:thinking-level @seen-opts))))))

(deftest prompt-turn-reassembles-effective-system-prompt-from-canonical-layers-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        _           (ss/update-state-value-in! session-ctx (ss/state-path :session-data session-ctx-id)
                                               assoc
                                               :base-system-prompt "base"
                                               :developer-prompt "# Agent Profile: builder\n\nFollow the build plan carefully."
                                               :developer-prompt-source :explicit
                                               :system-prompt "stale"
                                               :prompt-contributions [{:id "c1"
                                                                       :ext-path "/ext/a"
                                                                       :content "Hint A"
                                                                       :priority 10
                                                                       :enabled true
                                                                       :created-at (java.time.Instant/now)
                                                                       :updated-at (java.time.Instant/now)}])
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        seen-conv   (atom nil)
        stream-fn   (fn [_ai-ctx conv _model _opts consume-fn]
                      (reset! seen-conv conv)
                      (consume-fn {:type :start})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn]
      (ss/journal-append-in! session-ctx session-ctx-id (persist/message-entry user-msg))
      (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model)
      (is (= "base\n\n# Agent Profile: builder\n\nFollow the build plan carefully.\n\n# Extension Prompt Contributions\n\n<prompt_contribution id=\"c1\" ext_path=\"/ext/a\">\nHint A\n</prompt_contribution>"
             (:system-prompt @seen-conv)))
      (is (not= "stale" (:system-prompt @seen-conv))))))

(deftest session-idle-timeout-config-is-forwarded-to-ai-options-test
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx* session-ctx-id] (setup-session-ctx! agent-ctx)
        session-ctx (assoc session-ctx* :config {:llm-stream-idle-timeout-ms 777})
        seen-opts   (atom nil)
        stream-fn   (fn [_ai-ctx _conv _model opts consume-fn]
                      (reset! seen-opts opts)
                      (consume-fn {:type :start})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream! stream-fn]
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
  ;; finish-agent-loop! sends :agent-end to session statechart and returns result.
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
  ;; Callers pre-journal user messages; run-agent-loop! runs body, then finishes.
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
        ;; Caller is responsible for journaling before invoking the loop
        (ss/journal-append-in! session-ctx session-ctx-id (persist/message-entry user-msg))
        (let [result (prompt-loop/run-agent-loop! nil session-ctx session-ctx-id stub-model
                                                  {:api-key "k"})]
          (is (= :stop (:stop-reason result)))
          (is (= :body (ffirst @calls)))
          (is (= :finish (first (second @calls))))
          ;; User message is in journal (added by caller)
          (is (= 1 (count (journal-messages session-ctx session-ctx-id)))))))))

(deftest execute-one-turn-test
  (testing "single-turn execution returns assistant message and canonical classified outcome"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          user-msg    {:role "user" :content [{:type :text :text "hi"}]}]
      (agent/start-loop-in! agent-ctx [user-msg])
      (with-redefs [psi.agent-session.prompt-runtime/do-stream!
                    (stub-text-stream "one-turn")]
        (let [assistant-msg (#'prompt-turn/stream-turn! nil session-ctx session-ctx-id stub-model nil nil)
              outcome       (prompt-recording/classify-assistant-message assistant-msg)]
          (is (= "assistant" (:role assistant-msg)))
          (is (= :turn.outcome/stop (:turn/outcome outcome)))
          (is (= "one-turn"
                 (some #(when (= :text (:type %)) (:text %))
                       (:content assistant-msg)))))))))

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
                    psi.agent-session.tool-batch/run-tool-calls!
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
                    psi.agent-session.tool-batch/run-tool-calls!
                    (fn [_ _ tool-calls _]
                      (swap! tool-batches conj (mapv :id tool-calls))
                      [{:tool-call-id "call-1"}
                       {:tool-call-id "call-2"}])]
        (let [result (#'prompt-turn/run-turn-loop! nil session-ctx session-ctx-id stub-model nil nil)]
          (is (= 2 @turn-count))
          (is (= [["call-1" "call-2"]] @tool-batches))
          (is (= :stop (:stop-reason result))))))))

(deftest run-tool-calls-test
  (testing "run-tool-calls! executes a batch and returns results in tool-call order"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          tool-calls   [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}
                        {:type :tool-call :id "call-2" :name "bash" :arguments "{}"}]
          starts       (atom [])
          executes     (atom [])
          records      (atom [])]
      (with-redefs [psi.agent-session.tool-execution/start-tool-call!
                    (fn [_ _ tc _]
                      (swap! starts conj (:id tc)))
                    psi.agent-session.tool-execution/execute-tool-call!
                    (fn [_ _ tc _]
                      (swap! executes conj (:id tc))
                      {:tool-call tc
                       :tool-result {:content (str "ok-" (:id tc)) :is-error false}
                       :result-message {:role "toolResult"
                                        :tool-call-id (:id tc)
                                        :tool-name (:name tc)
                                        :content [{:type :text :text (str "ok-" (:id tc))}]}
                       :effective-policy nil})
                    psi.agent-session.tool-execution/record-tool-call-result!
                    (fn [_ _ shaped _]
                      (swap! records conj (get-in shaped [:result-message :tool-call-id]))
                      (:result-message shaped))]
        (let [results (#'tool-batch/run-tool-calls! session-ctx session-ctx-id tool-calls nil)]
          (is (= #{"call-1" "call-2"} (set @starts)))
          (is (= #{"call-1" "call-2"} (set @executes)))
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
      (with-redefs [psi.agent-session.tool-execution/start-tool-call! (fn [_ _ _ _] nil)
                    psi.agent-session.tool-execution/execute-tool-call!
                    (fn [_ _ tc _]
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
                    psi.agent-session.tool-execution/record-tool-call-result!
                    (fn [_ _ shaped _]
                      (:result-message shaped))]
        (let [runner (future (#'tool-batch/run-tool-calls! session-ctx session-ctx-id tool-calls nil))]
          @started
          (deliver release true)
          (let [results @runner]
            (is (= 2 @max-active))
            (is (= ["call-1" "call-2" "call-3"] (mapv :tool-call-id results))))))
      (.shutdown ^ExecutorService (:tool-batch-executor session-ctx)))))

(deftest run-tool-calls-records-results-in-stable-order-test
  (testing "out-of-order execution still records canonical tool results in assistant order"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          tool-calls   [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}
                        {:type :tool-call :id "call-2" :name "bash" :arguments "{}"}]
          record-order (atom [])]
      (with-redefs [psi.agent-session.tool-execution/start-tool-call! (fn [_ _ _ _] nil)
                    psi.agent-session.tool-execution/execute-tool-call!
                    (fn [_ _ tc _]
                      (when (= "call-1" (:id tc))
                        (Thread/sleep 40))
                      (when (= "call-2" (:id tc))
                        (Thread/sleep 5))
                      {:tool-call tc
                       :tool-result {:content (str "ok-" (:id tc)) :is-error false}
                       :result-message {:role "toolResult"
                                        :tool-call-id (:id tc)
                                        :tool-name (:name tc)
                                        :content [{:type :text :text (str "ok-" (:id tc))}]}
                       :effective-policy nil})
                    psi.agent-session.tool-execution/record-tool-call-result!
                    (fn [_ _ shaped _]
                      (swap! record-order conj (get-in shaped [:result-message :tool-call-id]))
                      (:result-message shaped))]
        (let [results (#'tool-batch/run-tool-calls! session-ctx session-ctx-id tool-calls nil)]
          (is (= ["call-1" "call-2"] @record-order))
          (is (= ["call-1" "call-2"] (mapv :tool-call-id results))))))))

(deftest run-tool-calls-preserves-per-tool-error-isolation-test
  (testing "one failing tool still yields a recorded tool result without aborting the batch"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          tool-calls   [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}
                        {:type :tool-call :id "call-2" :name "bash" :arguments "{}"}]]
      (with-redefs [psi.agent-session.tool-execution/start-tool-call! (fn [_ _ _ _] nil)
                    psi.agent-session.tool-execution/execute-tool-call!
                    (fn [_ _ tc _]
                      (if (= "call-1" (:id tc))
                        (throw (ex-info "boom" {}))
                        {:tool-call tc
                         :tool-result {:content "ok-call-2" :is-error false}
                         :result-message {:role "toolResult"
                                          :tool-call-id "call-2"
                                          :tool-name "bash"
                                          :content [{:type :text :text "ok-call-2"}]
                                          :is-error false
                                          :result-text "ok-call-2"}
                         :effective-policy nil}))
                    psi.agent-session.tool-execution/record-tool-call-result!
                    (fn [_ _ shaped _]
                      (:result-message shaped))]
        (let [results (#'tool-batch/run-tool-calls! session-ctx session-ctx-id tool-calls nil)
              first-result (first results)
              second-result (second results)]
          (is (= ["call-1" "call-2"] (mapv :tool-call-id results)))
          (is (true? (:is-error first-result)))
          (is (false? (:is-error second-result)))
          (is (str/includes? (:result-text first-result) "boom")))))))

(deftest parallel-tool-batch-telemetry-and-journal-test
  (testing "parallel batches preserve telemetry attribution and deterministic journal ordering"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          outcome      {:turn/outcome :turn.outcome/tool-use
                        :assistant-message {:role "assistant"
                                            :content [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}
                                                      {:type :tool-call :id "call-2" :name "bash" :arguments "{}"}]
                                            :stop-reason :tool_use}
                        :tool-calls [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}
                                     {:type :tool-call :id "call-2" :name "bash" :arguments "{}"}]}
          starts       (atom [])]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ tool-name _ opts]
                      (swap! starts conj [tool-name (:tool-call-id opts)])
                      (when (= "read" tool-name)
                        (Thread/sleep 40))
                      (when (= "bash" tool-name)
                        (Thread/sleep 5))
                      {:content (str "ok-" (:tool-call-id opts))
                       :is-error false
                       :details {:truncation {:truncated false}}})]
        (let [results (#'tool-batch/run-tool-calls! session-ctx session-ctx-id (:tool-calls outcome) nil)
              lifecycle (ss/get-state-value-in session-ctx (ss/state-path :tool-lifecycle-events session-ctx-id))
              result-events (filterv #(= :tool-result (:event-kind %)) lifecycle)
              output-stats (ss/get-state-value-in session-ctx (ss/state-path :tool-output-stats session-ctx-id))
              output-tool-ids (set (mapv :tool-call-id (:calls output-stats)))]
          (is (= #{["read" "call-1"] ["bash" "call-2"]} (set @starts)))
          (is (= ["call-1" "call-2"] (mapv :tool-call-id results)))
          (is (= #{"call-1" "call-2"} output-tool-ids))
          (is (= #{"call-1" "call-2"} (set (mapv :tool-id result-events)))))))))

(deftest run-tool-calls-uses-ctx-owned-executor-test
  (testing "run-tool-calls! submits parallel work to the ctx-owned executor"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          tool-calls   [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}
                        {:type :tool-call :id "call-2" :name "bash" :arguments "{}"}]
          submitted    (atom nil)
          fake-futures (atom [])
          fake-executor (proxy [java.util.concurrent.AbstractExecutorService] []
                          (invokeAll [tasks]
                            (reset! submitted (vec tasks))
                            (let [futures (mapv (fn [task]
                                                  (reify java.util.concurrent.Future
                                                    (get [_] (.call ^java.util.concurrent.Callable task))
                                                    (get [_ _ _] (.call ^java.util.concurrent.Callable task))
                                                    (cancel [_ _] false)
                                                    (isCancelled [_] false)
                                                    (isDone [_] true)))
                                                tasks)]
                              (reset! fake-futures futures)
                              futures))
                          (shutdown [] nil)
                          (shutdownNow [] [])
                          (isShutdown [] false)
                          (isTerminated [] false)
                          (awaitTermination [_ _] true)
                          (execute [_] nil))]
      (with-redefs [psi.agent-session.tool-execution/start-tool-call! (fn [_ _ _ _] nil)
                    psi.agent-session.tool-execution/execute-tool-call!
                    (fn [_ _ tc _]
                      {:tool-call tc
                       :tool-result {:content (str "ok-" (:id tc)) :is-error false}
                       :result-message {:role "toolResult"
                                        :tool-call-id (:id tc)
                                        :tool-name (:name tc)
                                        :content [{:type :text :text (str "ok-" (:id tc))}]}
                       :effective-policy nil})
                    psi.agent-session.tool-execution/record-tool-call-result!
                    (fn [_ _ shaped _]
                      (:result-message shaped))]
        (let [ctx*    (assoc session-ctx :tool-batch-executor fake-executor)
              results (#'tool-batch/run-tool-calls! ctx* session-ctx-id tool-calls nil)]
          (is (= 2 (count @submitted)))
          (is (= ["call-1" "call-2"] (mapv :tool-call-id results))))))))

(deftest create-context-provides-shared-tool-batch-executor-test
  (testing "create-context provisions a shared tool batch executor from config"
    (let [ctx (session-core/create-context {:persist? false
                                            :config {:tool-batch-max-parallelism 2}})]
      (try
        (is (some? (:tool-batch-executor ctx)))
        (finally
          (session-core/shutdown-context! ctx))))))

;;; Child session infrastructure tests

(defn- make-child-session-id [] (str (java.util.UUID/randomUUID)))

(defn- add-child-session-to-state!
  "Directly insert a minimal child session entry into ctx's state atom."
  [ctx child-id child-sd]
  (let [state* (:state* ctx)]
    (swap! state*
           (fn [state]
             (-> state
                 (assoc-in [:agent-session :sessions child-id :data]
                           child-sd)
                 (assoc-in [:agent-session :sessions child-id :persistence]
                           {:journal []
                            :flush-state {:flushed? false :session-file nil}})
                 (assoc-in [:agent-session :sessions child-id :telemetry]
                           {:tool-output-stats {:calls []
                                                :aggregates {:total-context-bytes 0
                                                             :by-tool {}
                                                             :limit-hits-by-tool {}}}
                            :tool-call-attempts []
                            :tool-lifecycle-events []
                            :provider-requests []
                            :provider-replies []})
                 (assoc-in [:agent-session :sessions child-id :turn] {:ctx nil}))))))

(defn- scoped-ctx
  "Return ctx unchanged; child routing is now explicit via session-id args."
  [ctx _child-id]
  ctx)

(defn- journal-for-session
  "Return the raw journal vector for session-id sid."
  [ctx sid]
  (ss/get-state-value-in ctx (ss/state-path :journal sid)))

(deftest child-session-target-routing-test
  ;; Child session reads are explicit via session-id args.
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        parent-id   session-ctx-id
        child-id    (make-child-session-id)
        child-sd    {:session-id  child-id
                     :session-name "child"
                     :spawn-mode  :agent
                     :parent-session-id parent-id}
        _           (add-child-session-to-state! session-ctx child-id child-sd)]
    (testing "child session routing is explicit"
      (testing "get-session-data-in returns child data when child id is passed"
        (is (= "child" (:session-name (ss/get-session-data-in session-ctx child-id)))
            "should read from child session, not parent"))
      (testing "parent session data is unaffected"
        (is (= parent-id session-ctx-id)
            "parent ctx still sees parent session")))))

(deftest child-session-journal-isolation-test
  ;; Writes via journal-append-in! on a scoped ctx land in the child journal
  ;; without touching the parent journal.
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        parent-id   session-ctx-id
        child-id    (make-child-session-id)
        child-sd    {:session-id child-id :spawn-mode :agent
                     :parent-session-id parent-id}
        _           (add-child-session-to-state! session-ctx child-id child-sd)
        scoped      (scoped-ctx session-ctx child-id)
        test-msg    {:role "user"
                     :content [{:type :text :text "child msg"}]
                     :timestamp (java.time.Instant/now)}]
    (testing "child session journal isolation"
      (ss/journal-append-in! scoped child-id (persist/message-entry test-msg))
      (testing "entry appears in child journal"
        (let [child-journal (journal-for-session session-ctx child-id)]
          (is (= 1 (count child-journal))
              (str "child journal should have 1 entry, got " (count child-journal)))
          (is (= "user" (get-in (first child-journal) [:data :message :role]))
              "journalled entry should be user message")))
      (testing "parent journal is untouched"
        (let [parent-journal (journal-for-session session-ctx parent-id)]
          (is (= 0 (count parent-journal))
              (str "parent journal should be empty, got " (count parent-journal))))))))

(deftest executor-child-session-end-to-end-test
  ;; run-agent-loop! with explicit child session-id writes messages into the
  ;; child journal and does not modify the parent journal.
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        parent-id   session-ctx-id
        child-id    (make-child-session-id)
        child-sd    {:session-id     child-id
                     :spawn-mode     :agent
                     :parent-session-id parent-id
                     :system-prompt  "child sys"
                     :tool-defs      []}
        _           (add-child-session-to-state! session-ctx child-id child-sd)
        scoped      (scoped-ctx session-ctx child-id)
        user-msg    {:role "user"
                     :content [{:type :text :text "hi child"}]
                     :timestamp (java.time.Instant/now)}]
    (testing "prompt execution child session end-to-end"
      (with-redefs [psi.agent-session.prompt-runtime/do-stream!
                    (stub-text-stream "child response")]
        (ss/journal-append-in! scoped child-id (persist/message-entry user-msg))
        (prompt-loop/run-agent-loop! nil scoped child-id stub-model))
      (testing "child journal contains both user and assistant messages"
        (let [child-journal  (journal-for-session session-ctx child-id)
              child-messages (->> child-journal
                                  (filter #(= :message (:kind %)))
                                  (mapv #(get-in % [:data :message :role])))]
          (is (>= (count child-messages) 2)
              (str "expected >=2 messages in child journal, got " child-messages))
          (is (= "user" (first child-messages))
              "first message should be user")
          (is (= "assistant" (second child-messages))
              "second message should be assistant")))
      (testing "parent journal remains empty"
        (let [parent-journal (journal-for-session session-ctx parent-id)]
          (is (= 0 (count parent-journal))
              (str "parent journal should be empty, got " (count parent-journal))))))))

(deftest finish-agent-loop-skips-statechart-for-child-test
  ;; finish-agent-loop! must not crash or attempt statechart dispatch when
  ;; session has spawn-mode :agent (child session path has no statechart lifecycle).
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx _] (setup-session-ctx! agent-ctx)
        child-id    (make-child-session-id)
        child-sd    {:session-id child-id :spawn-mode :agent}
        _           (add-child-session-to-state! session-ctx child-id child-sd)
        result      {:role "assistant"
                     :content [{:type :text :text "done"}]
                     :stop-reason :stop}]
    (testing "finish-agent-loop! with child session (spawn-mode :agent)"
      (testing "returns result without error"
        (let [returned (#'prompt-loop/finish-agent-loop!
                        session-ctx child-id result)]
          (is (= result returned)
              "should return the result unchanged"))))))

(deftest run-tool-calls-serialises-same-file-test
  (testing "tool calls targeting the same file path execute sequentially, not concurrently"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx* session-ctx-id] (setup-session-ctx! agent-ctx)
          session-ctx  (assoc session-ctx* :tool-batch-executor (Executors/newFixedThreadPool 4))
          path         "/tmp/shared.txt"
          tool-calls   [{:type :tool-call :id "call-1" :name "write"
                         :arguments (str "{\"path\":\"" path "\"}")}
                        {:type :tool-call :id "call-2" :name "edit"
                         :arguments (str "{\"path\":\"" path "\"}")}
                        {:type :tool-call :id "call-3" :name "read"
                         :arguments (str "{\"path\":\"" path "\"}")}]
          active       (atom 0)
          max-active   (atom 0)]
      (with-redefs [psi.agent-session.tool-execution/start-tool-call! (fn [_ _ _ _] nil)
                    psi.agent-session.tool-execution/execute-tool-call!
                    (fn [_ _ tc _]
                      (let [n (swap! active inc)]
                        (swap! max-active max n)
                        (Thread/sleep 20)
                        (swap! active dec)
                        {:tool-call tc
                         :tool-result {:content (str "ok-" (:id tc)) :is-error false}
                         :result-message {:role "toolResult"
                                          :tool-call-id (:id tc)
                                          :tool-name (:name tc)
                                          :content [{:type :text :text (str "ok-" (:id tc))}]}
                         :effective-policy nil}))
                    psi.agent-session.tool-execution/record-tool-call-result!
                    (fn [_ _ shaped _] (:result-message shaped))]
        (let [results (#'tool-batch/run-tool-calls! session-ctx session-ctx-id tool-calls nil)]
          (is (= 1 @max-active)
              "same-file calls must not overlap — max concurrent should be 1")
          (is (= ["call-1" "call-2" "call-3"] (mapv :tool-call-id results)))))
      (.shutdown ^ExecutorService (:tool-batch-executor session-ctx)))))

(deftest run-tool-calls-parallelises-different-files-test
  (testing "tool calls targeting different files still execute in parallel"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx* session-ctx-id] (setup-session-ctx! agent-ctx)
          session-ctx  (assoc session-ctx* :tool-batch-executor (Executors/newFixedThreadPool 4))
          tool-calls   [{:type :tool-call :id "call-1" :name "read"
                         :arguments "{\"path\":\"/tmp/a.txt\"}"}
                        {:type :tool-call :id "call-2" :name "read"
                         :arguments "{\"path\":\"/tmp/b.txt\"}"}
                        {:type :tool-call :id "call-3" :name "bash"
                         :arguments "{}"}]
          active       (atom 0)
          max-active   (atom 0)
          started      (promise)
          release      (promise)]
      (with-redefs [psi.agent-session.tool-execution/start-tool-call! (fn [_ _ _ _] nil)
                    psi.agent-session.tool-execution/execute-tool-call!
                    (fn [_ _ tc _]
                      (let [n (swap! active inc)]
                        (swap! max-active max n)
                        (when (= 2 n) (deliver started true))
                        (when (= "call-1" (:id tc))
                          @started @release)
                        (Thread/sleep 10)
                        (swap! active dec)
                        {:tool-call tc
                         :tool-result {:content (str "ok-" (:id tc)) :is-error false}
                         :result-message {:role "toolResult"
                                          :tool-call-id (:id tc)
                                          :tool-name (:name tc)
                                          :content [{:type :text :text (str "ok-" (:id tc))}]}
                         :effective-policy nil}))
                    psi.agent-session.tool-execution/record-tool-call-result!
                    (fn [_ _ shaped _] (:result-message shaped))]
        (let [runner (future (#'tool-batch/run-tool-calls! session-ctx session-ctx-id tool-calls nil))]
          @started
          (deliver release true)
          (let [results @runner]
            (is (>= @max-active 2)
                "different-file calls should overlap — max concurrent should be >=2")
            (is (= ["call-1" "call-2" "call-3"] (mapv :tool-call-id results))))))
      (.shutdown ^ExecutorService (:tool-batch-executor session-ctx)))))
