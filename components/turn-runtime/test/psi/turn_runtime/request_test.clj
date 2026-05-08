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
    (is (= "sys\n\ndev\n\n# Extension Prompt Contributions\n\n<prompt_contribution id=\"c1\" ext_path=\"/ext/a\">\nHint A\n</prompt_contribution>"
           (:prepared-request/system-prompt prepared)))
    (is (= (:prepared-request/system-prompt prepared)
           (get-in prepared [:prepared-request/provider-conversation :system-prompt])))
    (is (= {:cache-breakpoints #{:system}
            :system-cached? true
            :tools-cached? false
            :message-breakpoint-count 1}
           (:prepared-request/cache-projection prepared)))))

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
