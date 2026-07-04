(ns extensions.context-manager-friction-collaborators-test
  "Direct unit tests for the friction analyzer's real (non-injected)
   `:fetch-history`/`:session-info` collaborators added in slice 4
   (`default-fetch-history`, `default-session-info`) and their pure
   supporting fns in `extensions.context-manager.friction`
   (`message-snippet`, `session-info-of`) — driven against realistic EQL
   query-session result shapes (task 239, implementation review round 2)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.context-manager :as context-manager]
   [extensions.context-manager.friction :as friction]))

(deftest message-snippet-test
  (testing "joins :text entries from a raw agent-core message's :content"
    (is (= "hello world"
           (friction/message-snippet
            {:role :user
             :content [{:type :text :text "hello"}
                       {:type :text :text "world"}]}))))

  (testing "ignores non-:text content entries"
    (is (= "hello"
           (friction/message-snippet
            {:role :assistant
             :content [{:type :text :text "hello"}
                       {:type :tool-use :id "t1" :name "bash" :input {}}]}))))

  (testing "no :content or no :text entries yields empty string"
    (is (= "" (friction/message-snippet {:role :user})))
    (is (= "" (friction/message-snippet {:role :user :content []})))))

(deftest session-info-of-test
  (testing "shapes an EQL query-session result into the collaborator contract"
    (is (= {:worktree-root "/repo" :session-name "main"}
           (friction/session-info-of
            {:psi.agent-session/worktree-path "/repo"
             :psi.agent-session/session-name "main"}))))

  (testing "missing keys yield nil values, not an exception"
    (is (= {:worktree-root nil :session-name nil}
           (friction/session-info-of {})))))

(deftest default-fetch-history-test
  (testing "renders a bounded excerpt from realistic raw agent-core messages"
    (let [messages [{:role :user :content [{:type :text :text "do X"}]}
                    {:role :assistant :content [{:type :text :text "did X via bash workaround"}]}]
          queried (atom nil)
          api {:query-session (fn [session-id q]
                                (reset! queried {:session-id session-id :query q})
                                {:psi.agent-session/message-history messages})}
          excerpt (#'context-manager/default-fetch-history api "s1")]
      (is (= {:session-id "s1" :query [:psi.agent-session/message-history]}
             @queried)
          "queries the session's raw message history via EQL")
      (is (= "User: do X\nAssistant: did X via bash workaround" excerpt))))

  (testing "only the last friction-history-turn-count entries are considered"
    (let [excluded [{:role :user :content [{:type :text :text "excluded turn 1"}]}
                    {:role :assistant :content [{:type :text :text "excluded turn 2"}]}]
          included (vec (for [n (range friction/friction-history-turn-count)]
                          {:role :user :content [{:type :text :text (str "included turn " n)}]}))
          messages (vec (concat excluded included))
          api {:query-session (fn [_ _] {:psi.agent-session/message-history messages})}
          excerpt (#'context-manager/default-fetch-history api "s1")]
      (is (not (re-find #"excluded turn" excerpt))
          "entries beyond the tail-count window are excluded")
      (is (every? #(re-find (re-pattern (str "included turn " %)) excerpt)
                  (range friction/friction-history-turn-count))
          "every entry within the tail-count window is present")))

  (testing "empty message history yields nil (no excerpt)"
    (let [api {:query-session (fn [_ _] {})}]
      (is (nil? (#'context-manager/default-fetch-history api "s1"))))))

(deftest default-session-info-test
  (testing "queries and shapes worktree-path/session-name via EQL"
    (let [queried (atom nil)
          api {:query-session (fn [session-id q]
                                (reset! queried {:session-id session-id :query q})
                                {:psi.agent-session/worktree-path "/repo"
                                 :psi.agent-session/session-name "main"})}
          info (#'context-manager/default-session-info api "s1")]
      (is (= {:session-id "s1"
              :query [:psi.agent-session/worktree-path
                      :psi.agent-session/session-name]}
             @queried))
      (is (= {:worktree-root "/repo" :session-name "main"} info))))

  (testing "empty query-session result yields nil worktree/session-name"
    (let [api {:query-session (fn [_ _] {})}]
      (is (= {:worktree-root nil :session-name nil}
             (#'context-manager/default-session-info api "s1"))))))
