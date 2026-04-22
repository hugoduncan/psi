(ns psi.agent-session.extension-output-semantics-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-core.core :as agent-core]
   [psi.agent-session.conversation :as conversation]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.query.core :as query]))

(defn- mutate-fn
  [ctx session-id]
  (let [qctx (query/create-query-context)]
    (session/register-resolvers-in! qctx false)
    (session/register-mutations-in! qctx mutations/all-mutations true)
    (fn [op params]
      (get (query/query-in qctx
                           {:psi/agent-session-ctx ctx}
                           [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                       (not (contains? params :session-id))
                                       (assoc :session-id session-id)))])
           op))))

(defn- conversation-texts
  [conv]
  (mapv (fn [msg]
          (or (get-in msg [:content :text])
              (some->> (get-in msg [:content :blocks])
                       (keep :text)
                       (remove nil?)
                       seq
                       (str/join "\n"))))
        (:messages conv)))

(deftest notify-vs-append-message-semantics-test
  (let [[ctx session-id] (test-support/make-session-ctx {})
        mutate!          (mutate-fn ctx session-id)]
    (testing "notify is transcript-visible but excluded from future LLM-visible conversation assembly"
      (agent-core/append-message-in! (ss/agent-ctx-in ctx session-id)
                                     {:role "user"
                                      :content [{:type :text :text "hello"}]})
      (mutate! 'psi.extension/notify
               {:role "assistant"
                :content "status only"
                :custom-type "workflow-status"})
      (agent-core/append-message-in! (ss/agent-ctx-in ctx session-id)
                                     {:role "assistant"
                                      :content [{:type :text :text "real reply"}]})
      (let [messages (:messages (agent-core/get-data-in (ss/agent-ctx-in ctx session-id)))
            visible  (some #(when (= "workflow-status" (:custom-type %)) %) messages)
            conv     (#'conversation/agent-messages->ai-conversation "sys" messages [] {})
            roles    (mapv :role (:messages conv))
            texts    (conversation-texts conv)]
        (is (some? visible) "notification remains in runtime message history for rendering/replay")
        (is (= 2 (count roles))
            "notification is excluded from LLM-visible conversation assembly")
        (is (= #{:user :assistant} (set roles)))
        (is (= #{"hello" "real reply"} (set texts)))
        (is (not (some #{"status only"} texts))))))

  (testing "append-message becomes part of future LLM-visible conversation assembly"
    (let [[ctx2 session-id2] (test-support/make-session-ctx {})
          mutate2!           (mutate-fn ctx2 session-id2)]
      (agent-core/append-message-in! (ss/agent-ctx-in ctx2 session-id2)
                                     {:role "user"
                                      :content [{:type :text :text "hello"}]})
      (mutate2! 'psi.extension/append-message
                {:role "assistant"
                 :content "synthetic reply"})
      (let [messages (:messages (agent-core/get-data-in (ss/agent-ctx-in ctx2 session-id2)))
            conv     (#'conversation/agent-messages->ai-conversation "sys" messages [] {})
            texts    (conversation-texts conv)]
        (is (= 2 (count texts)))
        (is (= #{"hello" "synthetic reply"} (set texts)))))))
