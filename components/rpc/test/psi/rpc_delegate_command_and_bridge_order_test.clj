(ns psi.rpc-delegate-command-and-bridge-order-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.mutations :as mutations]
   [psi.rpc-test-support :as support]))

(deftest rpc-delegate-command-result-precedes-bridge-events-test
  (testing "RPC command op emits delegated ack before later bridge user/assistant messages"
    (let [[ctx session-id] (support/create-session-context {:mutations mutations/all-mutations
                                                            :event-queue (java.util.concurrent.LinkedBlockingQueue.)})
          reg           (:extension-registry ctx)
          ext-path      "/ext/delegate-order-test"
          _             (ext/register-extension-in! reg ext-path)
          runtime-fns*  (runtime-fns/make-extension-runtime-fns ctx session-id ext-path)
          api           (ext/create-extension-api reg ext-path runtime-fns*)
          _             ((:register-command api)
                         "fake-delegate-order"
                         {:description "Emit delegate-style immediate result and delayed bridge"
                          :handler (fn [_args]
                                     (future
                                       (Thread/sleep 20)
                                       ((:append-message api) "user" "Workflow run run-1 result:")
                                       ((:append-message api) "assistant" "result text"))
                                     "Delegated to lambda-build — run run-1")})
          state         (atom {:transport {:ready? true :pending {}}
                               :connection {:subscribed-topics #{"assistant/message"
                                                                 "command-result"
                                                                 "session/updated"
                                                                 "footer/updated"}}})
          handler       (support/make-handler ctx state)
          input         (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                             "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\" \"command-result\" \"session/updated\" \"footer/updated\"]}}\n"
                             "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/fake-delegate-order\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state 250)
          frames         (support/parse-frames out-lines)
          events         (filter #(= :event (:kind %)) frames)
          interesting    (keep (fn [frame]
                                 (case (:event frame)
                                   "command-result" {:event :command-result
                                                     :message (get-in frame [:data :message])}
                                   "assistant/message" {:event :assistant-message
                                                        :role (get-in frame [:data :role])
                                                        :text (get-in frame [:data :text])}
                                   nil))
                               events)
          compact-seq    (filterv identity
                                  (map (fn [x]
                                         (case [(:event x) (:role x) (:message x) (:text x)]
                                           [:command-result nil "Delegated to lambda-build — run run-1" nil] :ack
                                           [:assistant-message "user" nil "Workflow run run-1 result:"] :user-bridge
                                           [:assistant-message "assistant" nil "result text"] :assistant-result
                                           nil))
                                       interesting))]
      (is (= [:ack :user-bridge :assistant-result]
             compact-seq)))))
