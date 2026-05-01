(ns psi.rpc-delegate-bridge-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.mutations :as mutations]
   [psi.rpc-test-support :as support]))

(deftest rpc-external-user-role-and-command-result-both-surface-for-delegate-like-flow-test
  (testing "RPC surfaces both the immediate command-result and the later user+assistant bridge messages"
    (let [[ctx session-id] (support/create-session-context {:mutations mutations/all-mutations
                                                            :event-queue (java.util.concurrent.LinkedBlockingQueue.)})
          reg           (:extension-registry ctx)
          ext-path      "/ext/delegate-test"
          _             (ext/register-extension-in! reg ext-path)
          runtime-fns*  (runtime-fns/make-extension-runtime-fns ctx session-id ext-path)
          api           (ext/create-extension-api reg ext-path runtime-fns*)
          _             ((:register-command api)
                         "fake-delegate"
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
                             "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/fake-delegate\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state 250)
          _              (Thread/sleep 100)
          frames         (support/parse-frames out-lines)
          events         (filter #(= :event (:kind %)) frames)
          command-text   (some #(when (= "command-result" (:event %)) %) events)
          assistant-msgs (filter #(= "assistant/message" (:event %)) events)
          user-texts     (keep #(when (= "user" (get-in % [:data :role]))
                                  (get-in % [:data :text]))
                               assistant-msgs)
          asst-texts     (keep #(when (= "assistant" (get-in % [:data :role]))
                                  (get-in % [:data :text]))
                               assistant-msgs)]
      (is (= "Delegated to lambda-build — run run-1"
             (get-in command-text [:data :message])))
      (is (some #{"Workflow run run-1 result:"} user-texts))
      (is (some #{"result text"} asst-texts)))))
