(ns psi.rpc-prompt-test
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.ai.models :as ai-models]
   [psi.agent-session.core :as session]
   [psi.rpc.events :as rpc.events]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.tools :as tools]
   [psi.rpc.session.streams :as streams]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.core :as turn-runtime]
   [psi.rpc-test-support :as support]))

(deftest rpc-prompt-streams-events-and-interleaves-test
  (testing "prompt emits canonical events that interleave with accepted response"
    (let [[ctx _] (support/create-session-context)
          _   (session/dispatch-in! ctx :session/ui-set-status {:extension-id "ext.demo" :text "ready"} {:origin :test})
          state (atom {:transport {:ready? true :pending {}}
                       :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                       :execute-prepared-request-fn (fn [_ai-ctx _ctx _session-id _prepared-request progress-queue]
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :text-delta :text "Hello" :type :agent-event})
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :thinking-delta :text "thinking..." :type :agent-event})
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :tool-start :tool-id "tc-1" :tool-name "read" :type :agent-event})
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :tool-result
                                                               :tool-id "tc-1"
                                                               :tool-name "read"
                                                               :content [{:type :text :text "done"}]
                                                               :result-text "done"
                                                               :details nil
                                                               :is-error false
                                                               :type :agent-event})
                                                      (support/assistant-msg->execution-result _session-id {:role "assistant" :content [{:type :text :text "Hello final"}] :stop-reason :stop :usage {:total-tokens 3}}))})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"p1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/delta\" \"assistant/thinking-delta\" \"assistant/message\" \"tool/start\" \"tool/result\" \"session/updated\" \"footer/updated\"]}}\n"
                       "{:id \"r1\" :kind :request :op \"prompt\" :params {:message \"hi\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state 250)
          frames (->> out-lines
                      (keep (fn [line]
                              (try
                                (edn/read-string line)
                                (catch Throwable _ nil))))
                      vec)
          response-index (first (keep-indexed (fn [i f] (when (and (= :response (:kind f)) (= "prompt" (:op f))) i)) frames))
          event-indexes  (keep-indexed (fn [i f] (when (= :event (:kind f)) i)) frames)
          seqs           (->> frames (filter #(= :event (:kind %))) (map :seq) (remove nil?))
          topics         (->> frames (filter #(= :event (:kind %))) (map :event) set)]
      (is (number? response-index))
      (is (seq event-indexes))
      (is (some #(< response-index %) event-indexes))
      (is (contains? topics "assistant/delta"))
      (is (contains? topics "assistant/thinking-delta"))
      (is (contains? topics "assistant/message"))
      (is (contains? topics "tool/start"))
      (is (contains? topics "tool/result"))
      (is (contains? topics "session/updated"))
      (is (contains? topics "footer/updated"))
      (is (= seqs (sort seqs)))
      (is (every? #(contains? % :data) (filter #(= :event (:kind %)) frames)))
      (is (contains? #{:response :event} (:kind (last frames)))))))

(deftest rpc-prompt-footer-updated-tolerates-keyword-sentinel-values-test
  (testing "prompt completion does not fail when footer query returns keyword sentinels"
    (let [[ctx _] (support/create-session-context)
          state (atom {:transport {:ready? true :pending {}}
                       :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                       :execute-prepared-request-fn (fn [_ai-ctx _ctx _session-id _prepared-request progress-queue]
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :text-delta :text "Hello" :type :agent-event})
                                                      (support/assistant-msg->execution-result _session-id {:role "assistant" :content [{:type :text :text "Hello final"}] :stop-reason :stop :usage {:total-tokens 3}}))})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"p1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/delta\" \"assistant/message\" \"session/updated\" \"footer/updated\" \"error\"]}}\n"
                       "{:id \"r1\" :kind :request :op \"prompt\" :params {:message \"hi\"}}\n")
          footer-data {:psi.agent-session/worktree-path "/repo/project"
                       :psi.agent-session/git-branch :pathom/unknown
                       :psi.agent-session/session-display-name :pathom/unknown
                       :psi.agent-session/context-window 400000
                       :psi.agent-session/model-provider :pathom/unknown
                       :psi.agent-session/model-id "stub"
                       :psi.agent-session/model-reasoning false
                       :psi.agent-session/thinking-level :off
                       :psi.ui/statuses :pathom/unknown}
          orig-query-in session/query-in
          {:keys [out-lines]}
          (with-redefs [session/query-in
                        (fn
                          ([ctx q]
                           (if (= @#'rpc.events/footer-query q)
                             footer-data
                             (orig-query-in ctx q)))
                          ([ctx x y]
                           (if (or (= @#'rpc.events/footer-query x)
                                   (= @#'rpc.events/footer-query y))
                             footer-data
                             (orig-query-in ctx x y)))
                          ([ctx session-id q extra-entity]
                           (if (= @#'rpc.events/footer-query q)
                             footer-data
                             (orig-query-in ctx session-id q extra-entity))))]
            (support/run-loop input handler state 250))
          frames         (support/parse-frames out-lines)
          prompt-frame   (some #(when (and (= :response (:kind %))
                                           (= "prompt" (:op %))) %) frames)
          assistant-evt  (some #(when (= "assistant/message" (:event %)) %) frames)
          footer-events  (filterv #(= "footer/updated" (:event %)) frames)
          runtime-failed (filterv #(= "runtime/failed"
                                      (or (:error-code %)
                                          (get-in % [:data :error-code])))
                                  frames)]
      (is (some? prompt-frame))
      (is (true? (get-in prompt-frame [:data :accepted])))
      (is (some? assistant-evt))
      (is (seq footer-events))
      (is (= "/repo/project"
             (get-in (last footer-events) [:data :path-line])))
      (is (empty? runtime-failed)))))

(deftest rpc-prompt-provider-retry-state-publishes-footer-updated-test
  ;; Provider-boundary retry state changes drive Emacs-visible footer refreshes.
  (testing "provider retry activation and clear emit footer/updated with projected retry text"
    (let [emitted* (atom [])
          [ctx0 _] (support/create-session-context {:persist? false
                                                    :config {:auto-retry-base-delay-ms 8000
                                                             :auto-retry-max-retries 1}})
          ctx (assoc ctx0
                     :now-fn #(java.time.Instant/ofEpochMilli 10000)
                     :provider-retry-sleep-fn
                     (fn [_delay-ms]
                       (support/await-until
                        #(some (fn [event]
                                 (when (str/includes? (or (get-in event [:data :status-line]) "")
                                                      "retry in")
                                   event))
                               @emitted*)
                        500)))
          session-id (first (map :session-id (ss/list-context-sessions-in ctx)))
          attempts* (atom 0)
          progress-q (java.util.concurrent.LinkedBlockingQueue.)
          emit! (fn [event data] (swap! emitted* conj {:event event :data data}))
          {:keys [stop? thread]} (streams/start-progress-loop!
                                  {:start-daemon-thread! (fn [f name]
                                                           (doto (Thread. ^Runnable f name)
                                                             (.setDaemon true)
                                                             (.start)))
                                   :ctx ctx
                                   :session-id session-id
                                   :emit! emit!
                                   :progress-q progress-q
                                   :thread-name "rpc-retry-footer-test"})]
      (try
        (with-redefs [turn-runtime/execute-live-turn!
                      (fn [& _]
                        (if (= 1 (swap! attempts* inc))
                          {:turn-id "turn-retry-footer"
                           :model {:provider "anthropic" :id "stub"}
                           :ai-options {}
                           :turn-ctx nil
                           :assistant-message {:role "assistant"
                                               :content [{:type :error :text "Connection reset by peer"}]
                                               :stop-reason :error
                                               :error-message "Connection reset by peer"
                                               :timestamp (java.time.Instant/now)}}
                          {:turn-id "turn-retry-footer"
                           :model {:provider "anthropic" :id "stub"}
                           :ai-options {}
                           :turn-ctx nil
                           :assistant-message {:role "assistant"
                                               :content [{:type :text :text "recovered"}]
                                               :stop-reason :stop
                                               :timestamp (java.time.Instant/now)}}))]
          (turn-runtime/execute-prepared-request!
           {:provider-registry (atom {})}
           ctx
           session-id
           {:prepared-request/id "turn-retry-footer"
            :prepared-request/model {:provider :anthropic :id "stub"}
            :prepared-request/ai-options {}
            :prepared-request/response-mode :streaming}
           progress-q)
          (streams/stop-progress-loop! {:stop? stop?
                                        :thread thread
                                        :progress-q progress-q
                                        :emit! emit!
                                        :ctx ctx
                                        :session-id session-id})
          (let [footer-events (filterv #(= "footer/updated" (:event %)) @emitted*)
                retry-footer (some #(when (str/includes? (or (get-in % [:data :status-line]) "")
                                                         "retry in")
                                      %)
                                   footer-events)
                final-footer (last footer-events)]
            (is (= 2 @attempts*))
            (is (some? retry-footer) "retry activation must publish footer/updated with retry text")
            (is (= (get-in retry-footer [:data :session-id])
                   (get-in final-footer [:data :session-id])))
            (is (not (str/includes? (or (get-in final-footer [:data :status-line]) "")
                                    "retry in"))
                "retry clear must publish a footer without stale retry text")))
        (finally
          (reset! stop? true)
          (.join ^Thread thread 200))))))

(deftest rpc-thinking-delta-after-tool-start-begins-fresh-segment-test
  (testing "post-tool thinking delta can start a fresh cumulative segment"
    (let [[ctx _] (support/create-session-context)
          state (atom {:transport {:ready? true :pending {}}
                       :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                       :execute-prepared-request-fn (fn [_ai-ctx _ctx _session-id _prepared-request progress-queue]
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :thinking-delta :text "plan-1" :type :agent-event})
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :tool-start :tool-id "tc-1" :tool-name "read" :type :agent-event})
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :thinking-delta :text "plan-2" :type :agent-event})
                                                      (support/assistant-msg->execution-result _session-id {:role "assistant" :content [{:type :text :text "done"}] :stop-reason :stop :usage {:total-tokens 3}}))})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"p1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/thinking-delta\" \"tool/start\" \"assistant/message\"]}}\n"
                       "{:id \"r1\" :kind :request :op \"prompt\" :params {:message \"hi\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state 250)
          frames (support/parse-frames out-lines)
          thinking-events (->> frames
                               (filter #(and (= :event (:kind %))
                                             (= "assistant/thinking-delta" (:event %)))))
          tool-start-index (first (keep-indexed (fn [i f]
                                                  (when (and (= :event (:kind f))
                                                             (= "tool/start" (:event f)))
                                                    i))
                                                frames))
          second-thinking-index (first (keep-indexed (fn [i f]
                                                       (when (and (= :event (:kind f))
                                                                  (= "assistant/thinking-delta" (:event f))
                                                                  (= "plan-2" (get-in f [:data :text])))
                                                         i))
                                                     frames))]
      (is (= ["plan-1" "plan-2"] (mapv #(get-in % [:data :text]) thinking-events)))
      (is (number? tool-start-index))
      (is (number? second-thinking-index))
      (is (< tool-start-index second-thinking-index)))))

(deftest rpc-openai-codex-prompt-emits-tool-events-with-final-args-test
  (testing "openai codex tool args from response.output_item.done flow through RPC tool events"
    (let [[ctx session-id]   (support/create-session-context)
          _                  (session/dispatch-in! ctx :session/set-active-tools {:session-id session-id :tool-maps [tools/bash-tool]} {:origin :core})
          state              (atom {:transport {:ready? true :pending {}}
                                    :sync-on-git-head-change? false
                                    :rpc-ai-model (ai-models/get-model :gpt-5.3-codex)})
          handler            (support/make-handler ctx state)
          requests           (atom [])
          call-n             (atom 0)
          marker             (str "rpc-codex-tool-args-" (random-uuid))
          first-sse          (str
                              "data: " (json/generate-string
                                        {:type "response.output_item.added"
                                         :output_index 0
                                         :item {:type "function_call"
                                                :id "fc_1"
                                                :call_id "call_1"
                                                :name "bash"
                                                :arguments ""}}) "\n\n"
                              "data: " (json/generate-string
                                        {:type "response.output_item.done"
                                         :output_index 0
                                         :item {:type "function_call"
                                                :id "fc_1"
                                                :call_id "call_1"
                                                :name "bash"
                                                :arguments "{\"command\":\"pwd\"}"}}) "\n\n"
                              "data: " (json/generate-string
                                        {:type "response.completed"
                                         :response {:status "completed"}}) "\n\n")
          second-sse         (str
                              "data: " (json/generate-string
                                        {:type "response.output_item.added"
                                         :item {:type "message"
                                                :id "msg_2"
                                                :role "assistant"
                                                :status "in_progress"
                                                :content []}}) "\n\n"
                              "data: " (json/generate-string
                                        {:type "response.output_text.delta"
                                         :delta "Final response"}) "\n\n"
                              "data: " (json/generate-string
                                        {:type "response.completed"
                                         :response {:status "completed"}}) "\n\n")
          input              (str
                              "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                              "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"tool/start\" \"tool/executing\" \"tool/result\" \"assistant/message\"]}}\n"
                              "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"run pwd " marker "\"}}\n")
          {:keys [out-lines]}
          (with-redefs [runtime/resolve-api-key-in (fn [_ctx _session-id _model] support/openai-chatgpt-test-token)
                        http/post (fn [url req]
                                    (swap! requests conj {:url url :req req})
                                    (let [n (swap! call-n inc)]
                                      {:body (support/stream-body (if (= 1 n) first-sse second-sse))}))]
            (support/run-loop input handler state 900))
          relevant-requests (->> @requests
                                 (filter (fn [{:keys [url req]}]
                                           (and (= "https://chatgpt.com/backend-api/codex/responses" url)
                                                (str/includes? (str (:body req)) marker))))
                                 vec)
          frames         (support/parse-frames out-lines)
          events         (filter #(= :event (:kind %)) frames)
          prompt-frame   (some #(when (and (= :response (:kind %))
                                           (= "prompt" (:op %))) %) frames)
          tool-start-evt  (some #(when (and (= "tool/start" (:event %))
                                            (= "call_1|fc_1" (get-in % [:data :tool-id]))
                                            (= "bash" (get-in % [:data :tool-name]))) %) events)
          tool-exec-evt   (some #(when (and (= "tool/executing" (:event %))
                                            (= "call_1|fc_1" (get-in % [:data :tool-id]))
                                            (= "bash" (get-in % [:data :tool-name]))) %) events)
          tool-result-evt (some #(when (and (= "tool/result" (:event %))
                                            (= "call_1|fc_1" (get-in % [:data :tool-id]))
                                            (= "bash" (get-in % [:data :tool-name]))) %) events)
          assistant-evt   (some #(when (= "assistant/message" (:event %)) %) events)]
      (is (some? prompt-frame))
      (is (true? (get-in prompt-frame [:data :accepted])))
      (is (= 2 (count relevant-requests)))
      (is (= (str "Bearer " support/openai-chatgpt-test-token)
             (get-in (first relevant-requests) [:req :headers "Authorization"])))
      (is (= "acc_test"
             (get-in (first relevant-requests) [:req :headers "chatgpt-account-id"])))
      (let [body (json/parse-string (get-in (first relevant-requests) [:req :body]) true)]
        (is (= "gpt-5.3-codex" (:model body)))
        (is (= true (:stream body)))
        (is (= "bash" (get-in body [:tools 0 :name]))))
      (is (= "call_1|fc_1" (get-in tool-start-evt [:data :tool-id])))
      (is (= "bash" (get-in tool-start-evt [:data :tool-name])))
      (is (= {"command" "pwd"}
             (get-in tool-exec-evt [:data :parsed-args])))
      (is (false? (get-in tool-result-evt [:data :is-error])))
      (is (string? (get-in tool-result-evt [:data :result-text])))
      (is (not (str/blank? (get-in tool-result-evt [:data :result-text]))))
      (is (= "assistant" (get-in assistant-evt [:data :role])))
      (is (some #(= "Final response" (:text %))
                (get-in assistant-evt [:data :content]))))))
