(ns psi.rpc-transport-test
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.ui-capabilities :as ui-capabilities]
   [psi.rpc :as rpc]
   [psi.rpc.transport :as rpc.transport]
   [psi.rpc-test-support :as support]))

(deftest run-stdio-loop-validates-request-envelopes-test
  (testing "returns canonical protocol/transport errors for invalid input frames"
    (let [{:keys [out-lines]}
          (support/run-loop (str "\n"
                                 "{:kind :request :op \"ping\"}\n"
                                 "{:id \"1\" :kind :response :op \"ping\"}\n"
                                 "{:id \"2\" :kind :request :op \"ping\" :x 1}\n"
                                 "not-edn\n")
                            (fn [_ _ _] nil))
          frames (support/parse-frames out-lines)]
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
          (support/run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                 "{:id \"42\" :kind :request :op \"ping\"}\n")
                            (fn [request _emit _state]
                              (assoc (rpc.transport/response-frame (:id request) (:op request) true {:pong true})
                                     :extra "drop-me")))
          frame (-> out-lines support/parse-frames second)]
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
          (support/run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                 "{:id \"10\" :kind :request :op \"ping\"}\n")
                            (fn [request _ _]
                              (println "diagnostic line")
                              (rpc.transport/response-frame (:id request) (:op request) true {:pong true})))
          frame (-> out-lines support/parse-frames second)]
      (is (= :response (:kind frame)))
      (is (str/includes? err-text "diagnostic line"))
      (is (= 2 (count out-lines))))))

(deftest rpc-start-runtime-redirects-bootstrap-stdout-to-stderr-test
  (testing "rpc runtime startup keeps bootstrap stdout off the protocol stream"
    (let [out            (java.io.StringWriter.)
          err            (java.io.StringWriter.)
          session-state* (atom nil)]
      (binding [*in*  (java.io.StringReader. "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n")
                *out* out
                *err* err]
        (rpc/start-runtime!
         {:model-key :claude-sonnet
          :memory-runtime-opts {}
          :session-config {}
          :rpc-trace-file nil
          :session-state* session-state*
          :nrepl-runtime (atom nil)
          :resolve-model (fn [_]
                           {:provider :anthropic :id "stub" :name "Stub" :supports-reasoning true})
          :session-ctx-factory (fn [_ai-model _session-config]
                                 (println "BOOTSTRAP BANNER")
                                 (let [ctx (session/create-context (test-support/safe-context-opts {}))
                                       sd  (session/new-session-in! ctx nil {})]
                                   {:ctx ctx :oauth-ctx nil :session-id (:session-id sd)}))
          :bootstrap-fn! (fn [_ctx _session-id _ai-model _memory-runtime-opts]
                           (println "BOOTSTRAP TRANSCRIPT")
                           nil)
          :on-new-session! (fn [_source-session-id]
                             {:messages [] :tool-calls {} :tool-order []})}))
      (let [out-lines (->> (str/split-lines (str out)) (remove str/blank?) vec)
            frames    (support/parse-frames out-lines)]
        (is (not (str/includes? (str out) "BOOTSTRAP BANNER")))
        (is (not (str/includes? (str out) "BOOTSTRAP TRANSCRIPT")))
        (is (str/includes? (str err) "BOOTSTRAP BANNER"))
        (is (str/includes? (str err) "BOOTSTRAP TRANSCRIPT"))
        (is (= 1 (count frames)))
        (is (= :response (:kind (first frames))))
        (is (= "handshake" (:op (first frames))))))))

(deftest rpc-start-runtime-installs-rpc-provider-before-bootstrap-test
  (testing "bootstrap observes the late-bound RPC provider, not the static Emacs default"
    (let [out            (java.io.StringWriter.)
          err            (java.io.StringWriter.)
          session-state* (atom nil)
          created-session-id* (atom nil)
          bootstrap-result* (atom nil)]
      (binding [*in*  (java.io.StringReader. "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n")
                *out* out
                *err* err]
        (rpc/start-runtime!
         {:model-key :claude-sonnet
          :memory-runtime-opts {}
          :session-config {}
          :rpc-trace-file nil
          :session-state* session-state*
          :nrepl-runtime (atom nil)
          :resolve-model (fn [_]
                           {:provider :anthropic :id "stub" :name "Stub" :supports-reasoning true})
          :session-ctx-factory (fn [_ai-model _session-config]
                                 (let [ctx (session/create-context
                                            (test-support/safe-context-opts
                                             {:ui-type :emacs
                                              :install-default-ui-capability-provider? false}))
                                       sd  (session/new-session-in! ctx nil {})]
                                   (reset! created-session-id* (:session-id sd))
                                   {:ctx ctx :oauth-ctx nil :session-id (:session-id sd)}))
          :bootstrap-fn! (fn [ctx _session-id _ai-model _memory-runtime-opts]
                           (reset! bootstrap-result* (session/query-in ctx [:psi.ui/type
                                                                            :psi.ui/available?
                                                                            :psi.ui/capabilities
                                                                            :psi.ui/actions
                                                                            :psi.ui/make-visible-action]))
                           nil)
          :on-new-session! (fn [_source-session-id]
                             {:messages [] :tool-calls {} :tool-order []})}))
      (let [action (:psi.ui/make-visible-action @bootstrap-result*)]
        (is (= :emacs (:psi.ui/type @bootstrap-result*)))
        (is (= true (:psi.ui/available? @bootstrap-result*)))
        (is (= [:psi.ui.capability/make-visible]
               (:psi.ui/capabilities @bootstrap-result*)))
        (is (= [action] (:psi.ui/actions @bootstrap-result*)))
        (is (= @created-session-id*
               (get-in action [:psi.ui.action/invocation :psi.ui.invocation/session-id])))))))

(deftest rpc-context-can-start-before-late-bound-provider-install-test
  (testing "RPC context factory can suppress the static Emacs provider until runtime installs one"
    (let [ctx (session/create-context
               (test-support/safe-context-opts
                {:ui-type :emacs
                 :install-default-ui-capability-provider? false}))]
      (is (= :psi.ui.unavailable.reason/no-provider
             (get-in (session/query-in ctx [:psi.ui/make-visible-action])
                     [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason])))
      (ui-capabilities/install-provider! ctx (ui-capabilities/no-attached-provider :emacs))
      (is (= :psi.ui.unavailable.reason/no-attached-ui
             (get-in (session/query-in ctx [:psi.ui/make-visible-action])
                     [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason]))))))

(deftest run-stdio-loop-trace-fn-captures-inbound-and-outbound-test
  (let [traces  (atom [])
        input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"p1\" :kind :request :op \"ping\"}\n")
        out     (java.io.StringWriter.)
        err     (java.io.StringWriter.)
        handler (fn [request _emit _state]
                  (rpc.transport/response-frame (:id request) (:op request) true {:pong true}))]
    (rpc/run-stdio-loop! {:in (java.io.StringReader. input)
                          :out out
                          :err err
                          :state (atom {})
                          :request-handler handler
                          :trace-fn #(swap! traces conj %)})
    (let [in-frames  (filter #(= :in (:dir %)) @traces)
          out-frames (filter #(= :out (:dir %)) @traces)]
      (is (= 2 (count in-frames)))
      (is (every? map? (map :frame in-frames)))
      (is (= [:request :request]
             (mapv #(get-in % [:frame :kind]) in-frames)))
      (is (every? string? (map :raw in-frames)))
      (is (= 2 (count out-frames)))
      (is (= [:response :response]
             (mapv #(get-in % [:frame :kind]) out-frames)))
      (is (every? string? (map :raw out-frames))))))

(deftest run-stdio-loop-enforces-handshake-gate-test
  (testing "non-handshake requests are rejected before ready"
    (let [{:keys [out-lines]}
          (support/run-loop "{:id \"1\" :kind :request :op \"ping\"}\n"
                            (fn [_ _ _]
                              (rpc.transport/response-frame "1" "ping" true {:pong true})))
          frame (-> out-lines support/parse-frames first)]
      (is (= :error (:kind frame)))
      (is (= "transport/not-ready" (:error-code frame)))
      (is (= "1" (:id frame)))
      (is (= "ping" (:op frame))))))

(deftest run-stdio-loop-handshake-compatibility-test
  (testing "unsupported major protocol is rejected and transport remains not-ready"
    (let [{:keys [out-lines]}
          (support/run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"2.0\"}}}\n"
                                 "{:id \"p1\" :kind :request :op \"ping\"}\n")
                            (fn [_ _ _]
                              (rpc.transport/response-frame "p1" "ping" true {:pong true})))
          [h p] (support/parse-frames out-lines)]
      (is (= :error (:kind h)))
      (is (= "protocol/unsupported-version" (:error-code h)))
      (is (= :error (:kind p)))
      (is (= "transport/not-ready" (:error-code p)))))

  (testing "supported major protocol sets ready and allows non-handshake ops"
    (let [{:keys [out-lines]}
          (support/run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                 "{:id \"p1\" :kind :request :op \"ping\"}\n")
                            (fn [request _ _]
                              (rpc.transport/response-frame (:id request) (:op request) true {:pong true})))
          [h p] (support/parse-frames out-lines)]
      (is (= :response (:kind h)))
      (is (= "handshake" (:op h)))
      (is (= :response (:kind p)))
      (is (= "ping" (:op p))))))

(deftest run-stdio-loop-pending-lifecycle-test
  (testing "accepted request adds pending and terminal response clears it"
    (let [state (atom {:transport {:max-pending-requests 2}})]
      (support/run-loop (str "{:id \"h\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                             "{:id \"r1\" :kind :request :op \"echo\"}\n")
                        (fn [request _emit _state]
                          (if (= "echo" (:op request))
                            (rpc.transport/response-frame (:id request) "echo" true {:ok true})
                            (rpc.transport/response-frame (:id request) (:op request) true {})))
                        state)
      (is (= {} (get-in @state [:transport :pending])))
      (is (= true (get-in @state [:transport :ready?])))))

  (testing "max pending guard returns canonical error"
    (let [state (atom {:transport {:max-pending-requests 1
                                   :ready? true
                                   :pending {"existing" "op"}}})
          {:keys [out-lines]} (support/run-loop "{:id \"r2\" :kind :request :op \"echo\"}\n"
                                                (fn [_ _ _] nil)
                                                state)
          frame (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "transport/max-pending-exceeded" (:error-code frame)))
      (is (= "r2" (:id frame)))))

  (testing "duplicate request id is rejected with request/invalid-id"
    (let [state (atom {:transport {:ready? true
                                   :pending {"dup" "existing-op"}}})
          {:keys [out-lines]} (support/run-loop "{:id \"dup\" :kind :request :op \"echo\"}\n"
                                                (fn [_ _ _] nil)
                                                state)
          frame (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "request/invalid-id" (:error-code frame)))
      (is (= "dup" (:id frame))))))
