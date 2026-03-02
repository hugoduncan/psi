(ns psi.agent-session.rpc-test
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.rpc :as rpc]
   [psi.tui.extension-ui :as ext-ui]))

(defn- run-loop
  ([input handler]
   (run-loop input handler (atom {}) 0))
  ([input handler state]
   (run-loop input handler state 0))
  ([input handler state wait-ms]
   (let [out (java.io.StringWriter.)
         err (java.io.StringWriter.)]
     (rpc/run-stdio-loop! {:in              (java.io.StringReader. input)
                           :out             out
                           :err             err
                           :state           state
                           :request-handler handler})
     (when (pos? wait-ms)
       (Thread/sleep wait-ms))
     {:out-lines (->> (str/split-lines (str out))
                      (remove str/blank?)
                      vec)
      :err-text  (str err)
      :state     @state})))

(defn- parse-frames [lines]
  (mapv edn/read-string lines))

(deftest run-stdio-loop-validates-request-envelopes-test
  (testing "returns canonical protocol/transport errors for invalid input frames"
    (let [{:keys [out-lines]}
          (run-loop (str "\n"
                         "{:kind :request :op \"ping\"}\n"
                         "{:id \"1\" :kind :response :op \"ping\"}\n"
                         "{:id \"2\" :kind :request :op \"ping\" :x 1}\n"
                         "not-edn\n")
                    (fn [_ _ _] nil))
          frames (parse-frames out-lines)]
      (is (= 5 (count frames)))
      (is (= ["transport/invalid-frame"
              "protocol/invalid-envelope"
              "protocol/invalid-envelope"
              "protocol/invalid-envelope"
              "protocol/invalid-envelope"]
             (mapv :error-code frames)))
      (is (every? #(= :error (:kind %)) frames)))))

(deftest run-stdio-loop-emits-canonical-response-frame-test
  (testing "writer strips non-canonical keys from response frames"
    (let [{:keys [out-lines]}
          (run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"42\" :kind :request :op \"ping\"}\n")
                    (fn [request _emit _state]
                      (assoc (rpc/response-frame (:id request) (:op request) true {:pong true})
                             :extra "drop-me")))
          frame (-> out-lines parse-frames second)]
      (is (= {:id "42"
              :kind :response
              :op "ping"
              :ok true
              :data {:pong true}}
             frame))
      (is (not (contains? frame :extra))))))

(deftest run-stdio-loop-routes-handler-stdout-to-stderr-test
  (testing "non-protocol output from request handler is redirected to stderr"
    (let [{:keys [out-lines err-text]}
          (run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"10\" :kind :request :op \"ping\"}\n")
                    (fn [request _ _]
                      (println "diagnostic line")
                      (rpc/response-frame (:id request) (:op request) true {:pong true})))
          frame (-> out-lines parse-frames second)]
      (is (= :response (:kind frame)))
      (is (str/includes? err-text "diagnostic line"))
      (is (= 2 (count out-lines))))))

(deftest run-stdio-loop-enforces-handshake-gate-test
  (testing "non-handshake requests are rejected before ready"
    (let [{:keys [out-lines]}
          (run-loop "{:id \"1\" :kind :request :op \"ping\"}\n"
                    (fn [_ _ _]
                      (rpc/response-frame "1" "ping" true {:pong true})))
          frame (-> out-lines parse-frames first)]
      (is (= :error (:kind frame)))
      (is (= "transport/not-ready" (:error-code frame)))
      (is (= "1" (:id frame)))
      (is (= "ping" (:op frame))))))

(deftest run-stdio-loop-handshake-compatibility-test
  (testing "unsupported major protocol is rejected and transport remains not-ready"
    (let [{:keys [out-lines]}
          (run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"2.0\"}}}\n"
                         "{:id \"p1\" :kind :request :op \"ping\"}\n")
                    (fn [_ _ _]
                      (rpc/response-frame "p1" "ping" true {:pong true})))
          [h p] (parse-frames out-lines)]
      (is (= :error (:kind h)))
      (is (= "protocol/unsupported-version" (:error-code h)))
      (is (= :error (:kind p)))
      (is (= "transport/not-ready" (:error-code p)))))

  (testing "supported major protocol sets ready and allows non-handshake ops"
    (let [{:keys [out-lines]}
          (run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"p1\" :kind :request :op \"ping\"}\n")
                    (fn [request _ _]
                      (rpc/response-frame (:id request) (:op request) true {:pong true})))
          [h p] (parse-frames out-lines)]
      (is (= :response (:kind h)))
      (is (= "handshake" (:op h)))
      (is (= :response (:kind p)))
      (is (= "ping" (:op p))))))

(deftest run-stdio-loop-pending-lifecycle-test
  (testing "accepted request adds pending and terminal response clears it"
    (let [state (atom {:max-pending-requests 2})]
      (run-loop (str "{:id \"h\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"r1\" :kind :request :op \"echo\"}\n")
                (fn [request _emit _state]
                  (if (= "echo" (:op request))
                    (rpc/response-frame (:id request) "echo" true {:ok true})
                    (rpc/response-frame (:id request) (:op request) true {})))
                state)
      (is (= {} (:pending @state)))
      (is (= true (:ready? @state)))))

  (testing "max pending guard returns canonical error"
    (let [state (atom {:max-pending-requests 1 :ready? true :pending {"existing" "op"}})
          {:keys [out-lines]} (run-loop "{:id \"r2\" :kind :request :op \"echo\"}\n"
                                        (fn [_ _ _] nil)
                                        state)
          frame (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "transport/max-pending-exceeded" (:error-code frame)))
      (is (= "r2" (:id frame)))))

  (testing "duplicate request id is rejected with request/invalid-id"
    (let [state (atom {:ready? true :pending {"dup" "existing-op"}})
          {:keys [out-lines]} (run-loop "{:id \"dup\" :kind :request :op \"echo\"}\n"
                                        (fn [_ _ _] nil)
                                        state)
          frame (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "request/invalid-id" (:error-code frame)))
      (is (= "dup" (:id frame))))))

(deftest session-request-handler-query-eql-and-op-mapping-test
  (testing "query_eql routes to session/query-in and returns canonical result envelope"
    (let [ctx     (session/create-context)
          state   (atom {:ready? true :pending {}})
          handler (rpc/make-session-request-handler ctx)
          {:keys [out-lines]}
          (run-loop "{:id \"q1\" :kind :request :op \"query_eql\" :params {:query \"[:psi.graph/domain-coverage :psi.memory/status]\"}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :response (:kind frame)))
      (is (= "query_eql" (:op frame)))
      (is (= true (:ok frame)))
      (is (map? (get-in frame [:data :result])))
      (is (contains? (get-in frame [:data :result]) :psi.graph/domain-coverage))
      (is (contains? (get-in frame [:data :result]) :psi.memory/status))))

  (testing "query_eql invalid query EDN returns request/invalid-query"
    (let [ctx     (session/create-context)
          state   (atom {:ready? true :pending {}})
          handler (rpc/make-session-request-handler ctx)
          {:keys [out-lines]}
          (run-loop "{:id \"q2\" :kind :request :op \"query_eql\" :params {:query \"not-edn\"}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "query_eql" (:op frame)))
      (is (= "request/invalid-query" (:error-code frame)))))

  (testing "query_eql non-vector query returns request/invalid-query"
    (let [ctx     (session/create-context)
          state   (atom {:ready? true :pending {}})
          handler (rpc/make-session-request-handler ctx)
          {:keys [out-lines]}
          (run-loop "{:id \"q3\" :kind :request :op \"query_eql\" :params {:query \"{:a 1}\"}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "request/invalid-query" (:error-code frame)))))

  (testing "unknown op returns request/op-not-supported with supported ops"
    (let [ctx     (session/create-context)
          state   (atom {:ready? true :pending {}})
          handler (rpc/make-session-request-handler ctx)
          {:keys [out-lines]}
          (run-loop "{:id \"u1\" :kind :request :op \"nope\"}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "request/op-not-supported" (:error-code frame)))
      (is (vector? (get-in frame [:data :supported-ops])))))

  (testing "subscribe/unsubscribe update shared state and return subscribed topics"
    (let [ctx     (session/create-context)
          state   (atom {:ready? true :pending {} :subscribed-topics #{}})
          handler (rpc/make-session-request-handler ctx)
          {:keys [out-lines state]}
          (run-loop (str "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/delta\" \"tool/start\"]}}\n"
                         "{:id \"s2\" :kind :request :op \"unsubscribe\" :params {:topics [\"tool/start\"]}}\n")
                    handler
                    state)
          [f1 f2] (parse-frames out-lines)]
      (is (= :response (:kind f1)))
      (is (= ["assistant/delta" "tool/start"] (get-in f1 [:data :subscribed])))
      (is (= :response (:kind f2)))
      (is (= ["assistant/delta"] (get-in f2 [:data :subscribed])))
      (is (= #{"assistant/delta"} (:subscribed-topics state))))))

(deftest rpc-handshake-server-info-test
  (testing "handshake emits server-info with protocol/session metadata"
    (let [ctx     (session/create-context)
          state   (atom {:handshake-server-info-fn (fn [] (rpc/session->handshake-server-info ctx))})
          handler (rpc/make-session-request-handler ctx)
          {:keys [out-lines]}
          (run-loop "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                    handler
                    state)
          frame   (-> out-lines first edn/read-string)
          info    (get-in frame [:data :server-info])]
      (is (= :response (:kind frame)))
      (is (= "handshake" (:op frame)))
      (is (= "1.0" (:protocol-version info)))
      (is (contains? info :session-id))
      (is (= ["eql-graph" "eql-memory"] (:features info))))))

(deftest rpc-prompt-streams-events-and-interleaves-test
  (testing "prompt emits canonical events that interleave with accepted response"
    (let [ctx (session/create-context)
          _   (ext-ui/set-status! (:ui-state-atom ctx) "ext.demo" "ready")
          state (atom {:ready? true
                       :pending {}
                       :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                       :run-agent-loop-fn (fn [_ai-ctx _ctx _agent-ctx _ai-model _new-messages {:keys [progress-queue]}]
                                            (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                    {:event-kind :text-delta :text "Hello" :type :agent-event})
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
                                            {:role "assistant"
                                             :content [{:type :text :text "Hello final"}]
                                             :stop-reason :stop
                                             :usage {:total-tokens 3}})})
          handler (rpc/make-session-request-handler ctx)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"p1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/delta\" \"assistant/message\" \"tool/start\" \"tool/result\" \"session/updated\" \"footer/updated\"]}}\n"
                       "{:id \"r1\" :kind :request :op \"prompt\" :params {:message \"hi\"}}\n")
          {:keys [out-lines]} (run-loop input handler state 250)
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
      (is (contains? topics "assistant/message"))
      (is (contains? topics "tool/start"))
      (is (contains? topics "tool/result"))
      (is (contains? topics "session/updated"))
      (is (contains? topics "footer/updated"))
      (is (= seqs (sort seqs)))
      (is (every? #(contains? % :data) (filter #(= :event (:kind %)) frames)))
      (is (contains? #{:response :event} (:kind (last frames)))))))

(deftest rpc-session-resume-and-rehydrate-events-test
  (testing "new_session emits session/resumed and session/rehydrated canonical events"
    (let [ctx (session/create-context)
          state (atom {:ready? true
                       :pending {}
                       :subscribed-topics #{"session/resumed" "session/rehydrated"}})
          handler (rpc/make-session-request-handler ctx)
          input (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"n1\" :kind :request :op \"new_session\"}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames (parse-frames out-lines)
          events (filter #(= :event (:kind %)) frames)
          event-topics (set (map :event events))]
      (is (contains? event-topics "session/resumed"))
      (is (contains? event-topics "session/rehydrated"))
      (is (some #(contains? (:data %) :session-id) events))
      (is (some #(contains? (:data %) :messages) events)))))

(deftest rpc-e2e-handshake-query-and-streaming-test
  (testing "handshake -> query_eql -> prompt with interleaved events"
    (let [ctx (session/create-context)
          state (atom {:ready? true
                       :pending {}
                       :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                       :run-agent-loop-fn (fn [_ai-ctx _ctx _agent-ctx _ai-model _new-messages {:keys [progress-queue]}]
                                            (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                    {:event-kind :text-delta :text "Hello" :type :agent-event})
                                            {:role "assistant"
                                             :content [{:type :text :text "Hello final"}]
                                             :stop-reason :stop
                                             :usage {:total-tokens 2}})})
          handler (rpc/make-session-request-handler ctx)
          input (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"q1\" :kind :request :op \"query_eql\" :params {:query \"[:psi.graph/domain-coverage :psi.memory/status]\"}}\n"
                     "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/delta\" \"assistant/message\" \"session/updated\" \"footer/updated\"]}}\n"
                     "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"hi\"}}\n")
          {:keys [out-lines]} (run-loop input handler state 250)
          frames (parse-frames out-lines)
          handshake-frame (some #(when (and (= :response (:kind %)) (= "handshake" (:op %))) %) frames)
          query-frame (some #(when (and (= :response (:kind %)) (= "query_eql" (:op %))) %) frames)
          prompt-response-index (first (keep-indexed (fn [i f] (when (and (= :response (:kind f)) (= "prompt" (:op f))) i)) frames))
          event-indexes (vec (keep-indexed (fn [i f] (when (= :event (:kind f)) i)) frames))]
      (is handshake-frame)
      (is (= true (:ok handshake-frame)))
      (is query-frame)
      (is (= true (:ok query-frame)))
      (is (contains? (get-in query-frame [:data :result]) :psi.graph/domain-coverage))
      (is (contains? (get-in query-frame [:data :result]) :psi.memory/status))
      (is (number? prompt-response-index))
      (is (seq event-indexes))
      (is (some #(< prompt-response-index %) event-indexes))
      (is (some #(= "assistant/delta" (:event %)) (filter #(= :event (:kind %)) frames))))))
