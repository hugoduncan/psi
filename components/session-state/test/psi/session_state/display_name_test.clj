(ns psi.session-state.display-name-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.session-state.display-name :as display-name]))

(deftest short-display-text-normalizes-and-truncates-test
  (testing "whitespace is normalized"
    (is (= "hello world"
           (display-name/short-display-text " hello\n\tworld  "))))

  (testing "long text is truncated with ellipsis"
    (is (= (str (apply str (repeat 47 "a")) "…")
           (display-name/short-display-text (apply str (repeat 60 "a")))))))

(deftest user-message-display-text-suppresses-slash-commands-test
  (testing "plain user text is preserved"
    (is (= "Investigate prompt lifecycle"
           (display-name/user-message-display-text
            {:role "user"
             :content [{:type :text :text "Investigate prompt lifecycle"}]}))))

  (testing "slash commands are ignored"
    (is (nil? (display-name/user-message-display-text
               {:role "user"
                :content [{:type :text :text "/tree"}]}))))

  (testing "non-user messages are ignored"
    (is (nil? (display-name/user-message-display-text
               {:role "assistant"
                :content [{:type :text :text "reply"}]})))))

(deftest user-message-display-text-supports-canonical-content-shapes-test
  (testing "string content works"
    (is (= "string content works"
           (display-name/user-message-display-text
            {:role "user" :content "string content works"}))))

  (testing "vector block content extracts text/thinking/message keys"
    (is (= "hello\nthought\ntool result"
           (display-name/user-message-display-text
            {:role "user"
             :content [{:type :text :text "hello"}
                       {:type :thinking :thinking "thought"}
                       {:type :tool_result :message "tool result"}]})))))

(deftest session-display-name-prefers-explicit-name-over-last-user-message-test
  (testing "explicit session name wins"
    (is (= "Named"
           (display-name/session-display-name
            "Named"
            [{:role "user" :content [{:type :text :text "ignored"}]}]))))

  (testing "falls back to most recent non-command user text"
    (is (= "latest user text"
           (display-name/session-display-name
            nil
            [{:role "user" :content [{:type :text :text "first"}]}
             {:role "assistant" :content [{:type :text :text "reply"}]}
             {:role "user" :content [{:type :text :text "/status"}]}
             {:role "user" :content [{:type :text :text "latest user text"}]}]))))

  (testing "returns nil when no usable source exists"
    (is (nil? (display-name/session-display-name nil [])))))
