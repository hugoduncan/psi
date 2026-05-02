(ns psi.rpc-delegate-command-and-bridge-order-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.mutations :as mutations]
   [psi.rpc :as rpc]
   [psi.rpc-test-support :as support]))

(defn- write-line! [^java.io.Writer w line]
  (.write w (str line "\n"))
  (.flush w))

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
                               :connection {:focus-session-id session-id
                                            :subscribed-topics #{}}})
          handler       (support/make-handler ctx state)
          in-reader     (java.io.PipedReader.)
          in-writer     (java.io.PipedWriter. in-reader)
          out-writer    (java.io.StringWriter.)
          err-writer    (java.io.StringWriter.)
          loop-future   (future
                          (rpc/run-stdio-loop! {:in in-reader
                                                :out out-writer
                                                :err err-writer
                                                :state state
                                                :request-handler handler}))]
      (try
        (write-line! in-writer "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}")
        (write-line! in-writer "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\" \"command-result\" \"session/updated\" \"footer/updated\"]}}")
        (write-line! in-writer "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/fake-delegate-order\"}}")
        (let [compact-seq
              (support/await-frames!
               out-writer
               (fn [frames]
                 (let [events      (filter #(= :event (:kind %)) frames)
                       interesting (keep (fn [frame]
                                           (case (:event frame)
                                             "command-result" {:event :command-result
                                                               :message (get-in frame [:data :message])}
                                             "assistant/message" {:event :assistant-message
                                                                  :role (get-in frame [:data :role])
                                                                  :text (get-in frame [:data :text])}
                                             nil))
                                         events)
                       compact     (filterv identity
                                            (map (fn [x]
                                                   (case [(:event x) (:role x) (:message x) (:text x)]
                                                     [:command-result nil "Delegated to lambda-build — run run-1" nil] :ack
                                                     [:assistant-message "user" nil "Workflow run run-1 result:"] :user-bridge
                                                     [:assistant-message "assistant" nil "result text"] :assistant-result
                                                     nil))
                                                 interesting))]
                   (when (= [:ack :user-bridge :assistant-result] compact)
                     compact)))
               5000)]
          (is (= [:ack :user-bridge :assistant-result]
                 compact-seq)))
        (finally
          (future-cancel loop-future)
          (try (.close in-writer) (catch Exception _ nil))
          (try (.close in-reader) (catch Exception _ nil)))))))
