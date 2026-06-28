(ns psi.turn-runtime.request-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.turn-runtime.request :as request]))

(deftest build-prepared-request-surfaces-system-layers-and-provider-conversation-test
  (let [normalized-turn {:turn/id "t1"
                         :turn/session-id "s1"
                         :turn/user-message {:role "user"
                                             :content [{:type :text :text "hello"}]}
                         :turn/input-expansion nil
                         :turn/queued-steering-messages nil
                         :turn/messages [{:role "user"
                                          :content [{:type :text :text "hello"}]}]
                         :turn/runtime-model {:provider "stub" :id "m1"}
                         :turn/ai-options {:thinking-level :high}
                         :turn/cache-breakpoints #{:system}
                         :turn/session-model {:provider "stub" :id "m1"}
                         :turn/thinking-level :high
                         :turn/prompt-mode :default
                         :turn/active-tools [:read]
                         :turn/developer-prompt "dev"
                         :turn/developer-prompt-source :explicit
                         :turn/base-system-prompt "sys"
                         :turn/sorted-prompt-contributions [{:content "Hint A"
                                                             :priority 10
                                                             :enabled true
                                                             :ext-path "/ext/a"
                                                             :id "c1"}]
                         :turn/filtered-tool-defs []}
        prepared        (request/build-prepared-request normalized-turn)]
    (is (= "t1" (:prepared-request/id prepared)))
    (is (= "s1" (:prepared-request/session-id prepared)))
    (is (= [:system/base :system/developer :system/contributions]
           (mapv :id (:prepared-request/prompt-layers prepared))))
    (is (= "sys\n\ndev\n\n# Extension Prompt Contributions\n\nHint A"
           (:prepared-request/system-prompt prepared)))
    (is (= (:prepared-request/system-prompt prepared)
           (get-in prepared [:prepared-request/provider-conversation :system-prompt])))
    (is (= {:cache-breakpoints #{:system}
            :system-cached? true
            :tools-cached? false
            :message-breakpoint-count 1}
           (:prepared-request/cache-projection prepared)))))

(deftest build-prepared-request-without-developer-prompt-or-contributions-test
  (let [normalized-turn {:turn/id "t2"
                         :turn/session-id "s2"
                         :turn/user-message nil
                         :turn/input-expansion nil
                         :turn/queued-steering-messages [{:role "user"
                                                          :content [{:type :text :text "steer"}]}]
                         :turn/messages [{:role "user"
                                          :content [{:type :text :text "steer"}]}]
                         :turn/runtime-model {:provider "stub" :id "m2"}
                         :turn/ai-options {}
                         :turn/cache-breakpoints #{}
                         :turn/session-model {:provider "stub" :id "m2"}
                         :turn/thinking-level :off
                         :turn/prompt-mode :default
                         :turn/active-tools []
                         :turn/developer-prompt nil
                         :turn/developer-prompt-source nil
                         :turn/base-system-prompt "sys"
                         :turn/sorted-prompt-contributions []
                         :turn/filtered-tool-defs []}
        prepared        (request/build-prepared-request normalized-turn)]
    (is (= [:system/base]
           (mapv :id (:prepared-request/prompt-layers prepared))))
    (is (= "sys" (:prepared-request/system-prompt prepared)))
    (is (= [{:role :user
             :content {:kind :text
                       :text "steer"
                       :cache-control {:type :ephemeral}}}]
           (mapv #(select-keys % [:role :content])
                 (:prepared-request/messages prepared))))))

(deftest build-provider-conversation-tools-and-cache-variant-test
  (let [normalized-turn {:turn/base-system-prompt "sys"
                         :turn/developer-prompt nil
                         :turn/sorted-prompt-contributions []
                         :turn/cache-breakpoints #{:system :tools}
                         :turn/filtered-tool-defs [{:name "read"
                                                    :description "Read"
                                                    :parameters {:type "object"}}
                                                   {:name "bash"
                                                    :description "Bash"
                                                    :parameters {:type "object"}}]
                         :turn/messages [{:role "user"
                                          :content [{:type :text :text "u1"}]}
                                         {:role "assistant"
                                          :content [{:type :text :text "a1"}]}
                                         {:role "user"
                                          :content [{:type :text :text "u2"}]}]}
        provider-conv   (request/build-provider-conversation normalized-turn)]
    (is (= 2 (count (:tools provider-conv))))
    (is (= {:type :ephemeral}
           (:cache-control (first (:tools provider-conv)))))
    (is (= [{:kind :text :text "sys" :cache-control {:type :ephemeral}}]
           (:system-prompt-blocks provider-conv)))
    (is (= 3 (count (:messages provider-conv))))
    (is (= [:user :assistant :user]
           (mapv :role (:messages provider-conv))))))

(deftest prepared-request-query-text-prefers-user-message-then-steering-test
  (testing "uses user-message text when present"
    (is (= "hello"
           (request/prepared-request-query-text
            {:prepared-request/user-message {:content [{:type :text :text "hello"}]}
             :prepared-request/queued-steering-messages [{:content [{:type :text :text "steer"}]}]}))))
  (testing "falls back to queued steering text"
    (is (= "steer"
           (request/prepared-request-query-text
            {:prepared-request/user-message nil
             :prepared-request/queued-steering-messages [{:content [{:type :text :text "steer"}]}]})))))
