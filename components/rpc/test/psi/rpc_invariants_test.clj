(ns psi.rpc-invariants-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.prompt-runtime :as prompt-runtime]
   [psi.agent-session.runtime :as runtime]
   [psi.rpc :as rpc]
   [psi.rpc.events :as rpc.events]
   [psi.rpc.state :as rpc.state]
   [psi.rpc-test-support :as support]))

(deftest rpc-state-shape-and-helpers-invariant-test
  (testing "rpc.state owns connection-local transport/connection/worker state through helpers"
    (let [err   (java.io.StringWriter.)
          state (rpc.state/make-rpc-state {:session-id "s1"
                                           :err err
                                           :max-pending-requests 3})]
      (is (contains? @state :transport))
      (is (contains? @state :connection))
      (is (contains? @state :workers))
      (is (= err (rpc.state/err-writer state)))
      (is (= "s1" (rpc.state/focus-session-id state)))
      (is (= #{} (rpc.state/subscribed-topics state)))
      (is (= {} (rpc.state/pending state)))
      (is (= 3 (rpc.state/max-pending-requests state)))
      (is (= 1 (rpc.state/next-event-seq! state)))
      (is (= 2 (rpc.state/next-event-seq! state)))
      (rpc.state/add-pending! state "r1" "op")
      (is (= {"r1" "op"} (rpc.state/pending state)))
      (rpc.state/clear-pending! state "r1")
      (is (= {} (rpc.state/pending state)))
      (rpc.state/subscribe-topics! state #{"assistant/message"})
      (is (= #{"assistant/message"} (rpc.state/subscribed-topics state)))
      (rpc.state/unsubscribe-topics! state #{"assistant/message"})
      (is (= #{} (rpc.state/subscribed-topics state)))
      (rpc.state/set-focus-session-id! state "s2")
      (is (= "s2" (rpc.state/focus-session-id state)))
      (rpc.state/mark-ready! state "1.0")
      (is (true? (rpc.state/ready? state)))
      (is (= "1.0" (get-in @state [:transport :negotiated-protocol-version]))))))

(deftest rpc-state-initialize-preserves-canonical-nested-state-invariant-test
  (testing "transport initialization preserves canonical nested state and refreshes err writer"
    (let [old-err (java.io.StringWriter.)
          new-err (java.io.StringWriter.)
          worker  (future :ok)
          state   (atom {:transport {:ready? true
                                     :pending {"dup" "existing-op"}
                                     :max-pending-requests 7
                                     :err old-err}
                         :connection {:focus-session-id "nested-session"
                                      :subscribed-topics #{"assistant/message"}
                                      :event-seq 9}
                         :workers {:inflight-futures [worker]
                                   :rpc-run-fn-registered? true}})]
      (rpc.state/initialize-transport-state! state new-err)
      (is (true? (rpc.state/ready? state)))
      (is (= {"dup" "existing-op"} (rpc.state/pending state)))
      (is (= 7 (rpc.state/max-pending-requests state)))
      (is (= "nested-session" (rpc.state/focus-session-id state)))
      (is (= #{"assistant/message"} (rpc.state/subscribed-topics state)))
      (is (= 10 (rpc.state/next-event-seq! state)))
      (is (= new-err (rpc.state/err-writer state)))
      (is (= {"dup" "existing-op"} (get-in @state [:transport :pending])))
      (is (= "nested-session" (get-in @state [:connection :focus-session-id])))
      (is (= [worker] (get-in @state [:workers :inflight-futures]))))))

(deftest rpc-handshake-uses-explicit-transport-deps-invariant-test
  (testing "handshake server-info comes from explicit transport deps, not mutable state magic"
    (let [[ctx sid] (support/create-session-context)
          wrong-called?   (atom false)
          right-called?   (atom false)
          state           (atom {:handshake-server-info-fn (fn [_] (reset! wrong-called? true)
                                                             {:protocol-version "WRONG"})
                                 :handshake-context-updated-payload-fn :obsolete})
          handler         (rpc/make-session-request-handler ctx)
          out             (java.io.StringWriter.)
          err             (java.io.StringWriter.)]
      (rpc/run-stdio-loop! {:in (java.io.StringReader. "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n")
                            :out out
                            :err err
                            :state state
                            :request-handler handler
                            :handshake-server-info-fn (fn [_state]
                                                        (reset! right-called? true)
                                                        (assoc (rpc.events/session->handshake-server-info ctx sid)
                                                               :ui-type :emacs))})
      (let [[frame] (support/parse-frames (->> (str/split-lines (str out))
                                               (remove str/blank?)
                                               vec))]
        (is (true? @right-called?))
        (is (false? @wrong-called?))
        (is (= sid (get-in frame [:data :server-info :session-id])))
        (is (= :emacs (get-in frame [:data :server-info :ui-type])))))))

(deftest rpc-new-session-uses-explicit-session-deps-invariant-test
  (testing "new_session uses explicit session deps callback, not mutable state callback"
    (let [[ctx _]       (support/create-session-context)
          wrong-called? (atom false)
          right-called? (atom 0)
          state         (atom {:transport {:ready? true :pending {}}
                               :connection {:subscribed-topics #{"session/rehydrated"}}
                               :on-new-session! (fn []
                                                  (reset! wrong-called? true)
                                                  {:agent-messages [{:role "assistant"
                                                                     :content [{:type :text :text "wrong"}]}]
                                                   :messages []
                                                   :tool-calls {}
                                                   :tool-order []})})
          handler       (rpc/make-session-request-handler
                         ctx
                         {:on-new-session! (fn [_source-session-id]
                                             (swap! right-called? inc)
                                             {:session-id "explicit-session"
                                              :agent-messages [{:role "assistant"
                                                                :content [{:type :text :text "right"}]}]
                                              :messages []
                                              :tool-calls {"call-1" {:name "read"}}
                                              :tool-order ["call-1"]})})
          input         (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                             "{:id \"n1\" :kind :request :op \"new_session\"}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames         (support/parse-frames out-lines)
          rehydrate-event (some #(when (= "session/rehydrated" (:event %)) %) frames)]
      (is (= 1 @right-called?))
      (is (false? @wrong-called?))
      (is (= [{:role "assistant"
               :content [{:type :text :text "right"}]}]
             (get-in rehydrate-event [:data :messages])))
      (is (= ["call-1"]
             (get-in rehydrate-event [:data :tool-order]))))))

(deftest rpc-prompt-uses-dispatch-lifecycle-invariant-test
  (testing "prompt routes through dispatch-visible prompt lifecycle, not mutable executor"
    (let [[ctx _]        (support/create-session-context)
          lifecycle-used? (promise)
          state          (atom {:transport {:ready? true :pending {}}
                                :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}})
          handler        (rpc/make-session-request-handler
                          ctx
                          {:rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}})
          input          (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                              "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"plain text\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts] nil)
                    runtime/resolve-api-key-in (fn [_ctx _session-id _model] nil)
                    prompt-runtime/execute-prepared-request!
                    (fn [_ai-ctx _ctx session-id prepared _pq]
                      (deliver lifecycle-used? true)
                      {:execution-result/turn-id (:prepared-request/id prepared)
                       :execution-result/session-id session-id
                       :execution-result/assistant-message {:role "assistant"
                                                            :content [{:type :text :text "done"}]
                                                            :stop-reason :stop
                                                            :timestamp (java.time.Instant/now)}
                       :execution-result/turn-outcome :turn.outcome/stop
                       :execution-result/tool-calls []
                       :execution-result/stop-reason :stop})]
        (support/run-loop input handler state 250)
        (is (= true (support/await-promise! lifecycle-used? 1000))
            "timed out waiting for prompt lifecycle execution")))))
