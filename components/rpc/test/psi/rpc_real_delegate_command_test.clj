(ns psi.rpc-real-delegate-command-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.app-runtime :as app]
   [psi.agent-session.context :as context]
   [psi.app-runtime.messages :as app-messages]
   [psi.rpc :as rpc]
   [psi.rpc-test-support :as support]))

(defn- create-runtime-like-context
  ([]
   (create-runtime-like-context nil))
  ([execute-prepared-request-fn]
   (let [ai-model (app/resolve-model :gpt-5.4)
         {:keys [ctx session-id cwd]} (app/create-runtime-session-context ai-model {:event-queue (java.util.concurrent.LinkedBlockingQueue.)
                                                                                    :ui-type :rpc})
         ctx (cond-> ctx
               execute-prepared-request-fn (assoc :execute-prepared-request-fn execute-prepared-request-fn))]
     (app/bootstrap-runtime-session! ctx session-id ai-model {:memory-runtime-opts {} :cwd cwd})
     [ctx session-id])))

(defn- write-line! [^java.io.Writer w line]
  (.write w (str line "\n"))
  (.flush w))

(deftest real-delegate-command-op-immediate-ack-current-behavior-test
  (testing "real workflow-loader /delegate on the RPC command op emits the immediate ack before async completion"
    (let [[ctx session-id] (create-runtime-like-context)
          state            (atom {:transport {:ready? true :pending {}}
                                  :connection {:focus-session-id session-id
                                               :subscribed-topics #{"assistant/message"
                                                                    "command-result"
                                                                    "session/updated"
                                                                    "footer/updated"}}})
          handler          (support/make-handler ctx state)
          input            (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\" \"command-result\" \"session/updated\" \"footer/updated\"]}}\n"
                                "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/delegate lambda-build munera tracks work, mementum tracks state and knowledge\"}}\n")]
      (try
        (let [{:keys [out-lines]} (support/run-loop input handler state 250)
              frames         (support/parse-frames out-lines)
              events         (filter #(= :event (:kind %)) frames)
              command-result (some #(when (= "command-result" (:event %)) %) events)]
          (is (= "text" (get-in command-result [:data :type])))
          (is (.startsWith ^String
               (or (get-in command-result [:data :message]) "")
                           "Delegated to lambda-build — run "))
          ;; This harness proves only the immediate command-result ack.
          ;; The transport may still emit later async assistant/message frames
          ;; before stdin teardown, especially when the delegated workflow
          ;; completes or fails quickly in the same process.
          ;; So we intentionally do not assert their absence here.
          )
        (finally
          (context/shutdown-context! ctx))))))

(deftest real-delegate-command-op-persistent-connection-emits-later-bridge-events-test
  (testing "real workflow-loader /delegate emits ack first and later bridge user+assistant messages on a persistent RPC connection"
    (let [result-text       "λx.use(munera, track(work)) ∧ use(mementum, track(state ∧ knowledge))"
          execute-fn        (fn [_ai-ctx _ctx sid _prepared-request _progress-queue]
                              (support/ok-execution-result sid [{:type :text :text result-text}]))
          [ctx session-id]  (create-runtime-like-context execute-fn)
          state             (atom {:transport {:ready? true :pending {}}
                                   :connection {:focus-session-id session-id
                                                :subscribed-topics #{}}})
          handler           (support/make-handler ctx state)
          in-reader         (java.io.PipedReader.)
          in-writer         (java.io.PipedWriter. in-reader)
          out-writer        (java.io.StringWriter.)
          err-writer        (java.io.StringWriter.)
          loop-future       (future
                              (rpc/run-stdio-loop! {:in in-reader
                                                    :out out-writer
                                                    :err err-writer
                                                    :state state
                                                    :request-handler handler}))]
      (try
        (write-line! in-writer "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}")
        (write-line! in-writer "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\" \"command-result\" \"session/updated\" \"footer/updated\"]}}")
        (write-line! in-writer "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/delegate lambda-compiler munera tracks work, mementum tracks state and knowledge\"}}")
        (let [interesting
              (support/await-frames!
               out-writer
               (fn [frames]
                 (let [events (filter #(= :event (:kind %)) frames)
                       xs     (keep (fn [frame]
                                      (case (:event frame)
                                        "command-result" {:kind :ack
                                                          :message (get-in frame [:data :message])}
                                        "assistant/message" {:kind :message
                                                             :role (get-in frame [:data :role])
                                                             :text (or (get-in frame [:data :text])
                                                                       (get-in frame [:data :content 0 :text]))}
                                        nil))
                                    events)
                       compact (filterv identity
                                        (map (fn [x]
                                               (cond
                                                 (and (= :ack (:kind x))
                                                      (.startsWith ^String (or (:message x) "") "Delegated to lambda-compiler — run "))
                                                 :ack

                                                 (and (= :message (:kind x))
                                                      (= "user" (:role x))
                                                      (re-matches #"Workflow run .+ result:" (or (:text x) "")))
                                                 :user-bridge

                                                 (and (= :message (:kind x))
                                                      (= "assistant" (:role x))
                                                      (= result-text (:text x)))
                                                 :assistant-result

                                                 :else nil))
                                             xs))]
                   (when (= [:ack :user-bridge :assistant-result] compact)
                     xs)))
               5000)
              messages (support/await-until
                        (fn []
                          (let [msgs (app-messages/session-messages ctx session-id)]
                            (when (>= (count msgs) 3)
                              msgs)))
                        5000)]
          (.close in-writer)
          (deref loop-future 1000 support/timeout-token)
          (is (not= support/timeout-token interesting) "timed out waiting for delegate bridge events")
          (is (not= support/timeout-token messages) "timed out waiting for session messages")
          (let [compact-seq (filterv identity
                                     (map (fn [x]
                                            (cond
                                              (and (= :ack (:kind x))
                                                   (.startsWith ^String (or (:message x) "") "Delegated to lambda-compiler — run "))
                                              :ack
                                              (and (= :message (:kind x))
                                                   (= "user" (:role x))
                                                   (re-matches #"Workflow run .+ result:" (or (:text x) "")))
                                              :user-bridge
                                              (and (= :message (:kind x))
                                                   (= "assistant" (:role x))
                                                   (= result-text (:text x)))
                                              :assistant-result
                                              :else nil))
                                          interesting))
                last-messages (take-last 3 messages)
                second-text   (or (some-> (nth last-messages 1 nil) :content first :text)
                                  (:text (nth last-messages 1 nil))
                                  "")]
            (is (= [:ack :user-bridge :assistant-result] compact-seq))
            (is (= "user" (:role (nth last-messages 0))))
            (is (= "user" (:role (nth last-messages 1))))
            (is (= "assistant" (:role (nth last-messages 2))))
            (is (= "/delegate lambda-compiler munera tracks work, mementum tracks state and knowledge"
                   (or (some-> (nth last-messages 0 nil) :content first :text)
                       (:text (nth last-messages 0 nil)))))
            (is (.startsWith ^String second-text "Workflow run lambda-compiler-"))
            (is (.endsWith ^String second-text " result:"))
            (is (= result-text
                   (or (some-> (nth last-messages 2 nil) :content first :text)
                       (:text (nth last-messages 2 nil)))))
            (is (not-any? #(= "(workflow context bridge)" %)
                          (map (fn [m]
                                 (or (:text m)
                                     (some-> (:content m) first :text)))
                               last-messages)))))
        (finally
          (future-cancel loop-future)
          (try (.close in-writer) (catch Exception _ nil))
          (try (.close in-reader) (catch Exception _ nil))
          (context/shutdown-context! ctx))))))
