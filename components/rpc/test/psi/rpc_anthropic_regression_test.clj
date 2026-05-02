(ns psi.rpc-anthropic-regression-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.persistence :as persist]
   [psi.rpc :as rpc]
   [psi.rpc-test-support :as support]))

(deftest rpc-new-session-rehydrates-agent-messages-not-tui-projection-test
  (testing "new_session emits canonical agent messages so the next Anthropic request preserves role/content shape"
    (let [[ctx _] (support/create-session-context)
          state   (atom {:transport {:ready? true :pending {}}
                         :connection {:subscribed-topics #{"session/rehydrated"}}})
          handler (rpc/make-session-request-handler ctx {:on-new-session! (fn [_source-session-id]
                                                                            {:agent-messages [{:role "assistant"
                                                                                               :content [{:type :text :text "[New session started]"}]}
                                                                                              {:role "user"
                                                                                               :content [{:type :text :text "who are you?"}]}]
                                                                             :messages [{:role :assistant :text "[New session started]"}
                                                                                        {:role :user :text "who are you?"}]
                                                                             :tool-calls {}
                                                                             :tool-order []})})
          input (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"n1\" :kind :request :op \"new_session\"}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames (support/parse-frames out-lines)
          rehydrate-event (some #(when (= "session/rehydrated" (:event %)) %) frames)]
      (is (some? rehydrate-event))
      (is (= [{:role "assistant"
               :content [{:type :text :text "[New session started]"}]}
              {:role "user"
               :content [{:type :text :text "who are you?"}]}]
             (get-in rehydrate-event [:data :messages]))))))

(deftest rpc-resume-session-rehydrates-agent-messages-not-tui-projection-test
  (testing "resume emits canonical agent messages from the journal"
    (let [cwd                (str (System/getProperty "java.io.tmpdir") "/psi-rpc-anthropic-resume-" (java.util.UUID/randomUUID))
          _                  (.mkdirs (java.io.File. cwd))
          [ctx _]      (support/create-session-context {:cwd cwd})
          sd1                (session/new-session-in! ctx nil {})
          session-id         (:session-id sd1)
          path1              (:session-file sd1)
          _                  (persist/flush-journal! (java.io.File. path1)
                                                     session-id
                                                     cwd
                                                     nil
                                                     nil
                                                     [(persist/message-entry {:role "assistant"
                                                                              :content [{:type :text :text "[New session started]"}]})
                                                      (persist/message-entry {:role "user"
                                                                              :content [{:type :text :text "who are you?"}]})])
          state              (atom {:transport {:ready? true :pending {}}
                                    :connection {:subscribed-topics #{"session/rehydrated"}}})
          handler (support/make-handler ctx state)
          input              (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                  "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/resume " path1 "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames             (support/parse-frames out-lines)
          rehydrate-event    (some #(when (= "session/rehydrated" (:event %)) %) frames)]
      (is (some? rehydrate-event))
      (is (= [{:role "assistant"
               :content [{:type :text :text "[New session started]"}]}
              {:role "user"
               :content [{:type :text :text "who are you?"}]}]
             (get-in rehydrate-event [:data :messages]))))))
