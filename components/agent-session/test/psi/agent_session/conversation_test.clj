(ns psi.agent-session.conversation-test
  "Tests for agent-messages->ai-conversation — pure translation, no session needed."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.turn-runtime.conversation :as conv-translate]))

(deftest custom-type-messages-excluded-from-llm-conversation-test
  (testing "messages with :custom-type are filtered out before LLM call"
    ;; PSL extension appends assistant-role notify/custom-type messages as transcript markers.
    ;; These must not reach the LLM — consecutive assistant messages cause Anthropic 400.
    (let [messages
          [{:role "user"    :content [{:type :text :text "hello"}]}
           {:role "assistant" :content [{:type :text :text "hi there"}]}
           ;; PSL notify! — assistant role, custom-type marker
           {:role "assistant" :content [{:type :text :text "PSL sync start."}]
            :custom-type "plan-state-learning"}
           {:role "user"    :content [{:type :text :text "PSL follow-up"}]}]
          conv (#'conv-translate/agent-messages->ai-conversation
                "sys" messages [] {})
          roles (mapv :role (:messages conv))]
      (is (= [:user :assistant :user] roles)
          "custom-type assistant message is excluded; no consecutive assistant messages")
      (is (not-any? :custom-type (:messages conv))
          "no custom-type keys in LLM conversation messages")))

  (testing "assistant messages with no text, thinking, or tool-call blocks are skipped"
    (let [messages
          [{:role "user"      :content [{:type :text :text "q"}]}
           {:role "assistant" :content [{:type :text :text ""}]}
           {:role "user"      :content [{:type :text :text "q2"}]}]
          conv (#'conv-translate/agent-messages->ai-conversation
                "sys" messages [] {})
          roles (mapv :role (:messages conv))]
      (is (= [:user :user] roles)
          "empty assistant text message should not produce an empty text content block")))

  (testing "non-custom-type messages are all included"
    (let [messages
          [{:role "user"      :content [{:type :text :text "q"}]}
           {:role "assistant" :content [{:type :text :text "a"}]}
           {:role "user"      :content [{:type :text :text "q2"}]}]
          conv (#'conv-translate/agent-messages->ai-conversation
                "sys" messages [] {})
          roles (mapv :role (:messages conv))]
      (is (= [:user :assistant :user] roles))))

  (testing "consecutive user messages remain separate conversation messages"
    (let [messages
          [{:role "user" :content [{:type :text :text "u1"}]}
           {:role "user" :content [{:type :text :text "u2"}]}
           {:role "assistant" :content [{:type :text :text "a"}]}]
          conv (#'conv-translate/agent-messages->ai-conversation
                "sys" messages [] {})]
      (is (= [:user :user :assistant]
             (mapv :role (:messages conv))))
      (is (= {:kind :text :text "u1"}
             (dissoc (:content (first (:messages conv))) :cache-control)))
      (is (= {:kind :text :text "u2"}
             (dissoc (:content (second (:messages conv))) :cache-control))))))

(deftest system-provider-messages-become-canonical-ai-system-messages-test
  ;; Inline system provider messages are normalized into schema-valid AI
  ;; conversation messages, not passed through as provider-shaped content.
  (testing "system-provider-messages-become-canonical-ai-system-messages"
    (let [messages [{:role "user" :content [{:type :text :text "q"}]}
                    {:role "system"
                     :content [{:type :text
                                :text "Use short answers."
                                :cache-control {:type :ephemeral}}]}]
          conv (#'conv-translate/agent-messages->ai-conversation
                "sys" messages [] {})
          system-msg (second (:messages conv))]
      (is (= [:user :system] (mapv :role (:messages conv))))
      (is (= {:kind :text
              :text "Use short answers."
              :cache-control {:type :ephemeral}}
             (:content system-msg))))))

(deftest cache-breakpoints-are-projected-into-ai-conversation-test
  ;; System and tools cache breakpoints are applied to the provider conversation.
  ;; The entire system prompt is now one cacheable block (time+cwd frozen).
  ;; Message breakpoints target the last N user messages.
  (testing "cache-breakpoints-are-projected-into-ai-conversation"
    (testing "marks system prompt as single cached block and tools"
      (let [prompt "stable prompt with frozen time"
            conv   (#'conv-translate/agent-messages->ai-conversation
                    prompt [] [{:name "read" :description "Read" :parameters {:type "object"}}]
                    {:cache-breakpoints #{:system :tools}})]
        (is (= [{:kind :text :text prompt :cache-control {:type :ephemeral}}]
               (:system-prompt-blocks conv)))
        (is (= {:type :ephemeral}
               (:cache-control (first (:tools conv)))))))

    (testing "places breakpoints on last 3 user messages with default config"
      (let [messages [{:role "user" :content [{:type :text :text "u1"}]}
                      {:role "assistant" :content [{:type :text :text "a1"}]}
                      {:role "user" :content [{:type :text :text "u2"}]}
                      {:role "assistant" :content [{:type :text :text "a2"}]}
                      {:role "user" :content [{:type :text :text "u3"}]}
                      {:role "assistant" :content [{:type :text :text "a3"}]}
                      {:role "user" :content [{:type :text :text "u4"}]}]
            conv     (#'conv-translate/agent-messages->ai-conversation
                      "prompt" messages [] {:cache-breakpoints #{:system}})]
        (is (nil? (:cache-control (:content (nth (:messages conv) 0))))
            "u1 should not have breakpoint")
        (is (= {:type :ephemeral}
               (:cache-control (:content (nth (:messages conv) 2))))
            "u2 should have breakpoint")
        (is (= {:type :ephemeral}
               (:cache-control (:content (nth (:messages conv) 4))))
            "u3 should have breakpoint")
        (is (= {:type :ephemeral}
               (:cache-control (:content (nth (:messages conv) 6))))
            "u4 should have breakpoint")))

    (testing "uses 2 message slots when system+tools cached"
      (let [messages [{:role "user" :content [{:type :text :text "u1"}]}
                      {:role "assistant" :content [{:type :text :text "a1"}]}
                      {:role "user" :content [{:type :text :text "u2"}]}
                      {:role "assistant" :content [{:type :text :text "a2"}]}
                      {:role "user" :content [{:type :text :text "u3"}]}]
            conv     (#'conv-translate/agent-messages->ai-conversation
                      "prompt" messages [] {:cache-breakpoints #{:system :tools}})]
        (is (nil? (:cache-control (:content (nth (:messages conv) 0))))
            "u1 should not have breakpoint")
        (is (= {:type :ephemeral}
               (:cache-control (:content (nth (:messages conv) 2))))
            "u2 should have breakpoint")
        (is (= {:type :ephemeral}
               (:cache-control (:content (nth (:messages conv) 4))))
            "u3 should have breakpoint")))

    (testing "marks all user messages when fewer than available slots"
      (let [messages [{:role "user" :content [{:type :text :text "u1"}]}]
            conv     (#'conv-translate/agent-messages->ai-conversation
                      "prompt" messages [] {:cache-breakpoints #{:system}})]
        (is (= {:type :ephemeral}
               (:cache-control (:content (first (:messages conv))))))))

    (testing "all 4 slots on messages when no system/tools caching"
      (let [messages (vec (mapcat (fn [i]
                                    [{:role "user" :content [{:type :text :text (str "u" i)}]}
                                     {:role "assistant" :content [{:type :text :text (str "a" i)}]}])
                                  (range 1 6)))
            conv     (#'conv-translate/agent-messages->ai-conversation
                      "prompt" messages [] {:cache-breakpoints #{}})
            user-msgs (filterv #(= :user (:role %)) (:messages conv))
            cached    (filterv #(some? (:cache-control (:content %))) user-msgs)]
        (is (= 4 (count cached))
            "last 4 user messages should have breakpoints")
        (is (nil? (:cache-control (:content (first user-msgs))))
            "first user message should not have breakpoint")))))
