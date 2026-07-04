(ns extensions.context-manager-friction-collaborators-test
  "Direct unit tests for the friction analyzer's real (non-injected)
   `:fetch-history`/`:session-info` collaborators added in slice 4
   (`default-fetch-history`, `default-session-info`) and their pure
   supporting fns in `extensions.context-manager.friction`
   (`message-snippet`, `session-info-of`, `group-into-turns`,
   `last-n-turns`) — driven against realistic EQL query-session result
   shapes (task 239, implementation review rounds 2 and 3)."
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

(deftest group-into-turns-test
  (testing "groups messages into per-turn vectors starting at each :user message"
    (is (= [[{:role :user :n 1}]
            [{:role :user :n 2} {:role :assistant :n 3} {:role :tool :n 4}]]
           (friction/group-into-turns
            [{:role :user :n 1}
             {:role :user :n 2} {:role :assistant :n 3} {:role :tool :n 4}]))))

  (testing "messages preceding the first :user message form their own leading group"
    (is (= [[{:role :assistant :n 1}] [{:role :user :n 2}]]
           (friction/group-into-turns
            [{:role :assistant :n 1} {:role :user :n 2}]))))

  (testing "empty input yields no turns"
    (is (= [] (friction/group-into-turns [])))))

(deftest last-n-turns-test
  (testing "bounds a multi-message tool-heavy turn as a single turn, not several messages"
    (let [messages [{:role :user :n 1} {:role :assistant :n 2}
                    {:role :user :n 3} {:role :assistant :n 4} {:role :tool :n 5}
                    {:role :tool :n 6} {:role :assistant :n 7}
                    {:role :user :n 8} {:role :assistant :n 9}]]
      ;; turn A (excluded) = n1..n2, turn B (tool-heavy) = n3..n7 (5
      ;; messages), turn C = n8..n9 (2 messages). Last 2 turns = turn B +
      ;; turn C = the last 7 of the 9 messages here, even though a naive
      ;; `take-last 2` on raw messages would keep only the last 2.
      (is (= (subvec messages 2) (friction/last-n-turns messages 2)))))

  (testing "n nil or non-positive returns all messages unchanged"
    (let [messages [{:role :user :n 1} {:role :assistant :n 2}]]
      (is (= messages (friction/last-n-turns messages nil)))
      (is (= messages (friction/last-n-turns messages 0)))))

  (testing "n at or beyond the turn count returns all messages"
    (let [messages [{:role :user :n 1} {:role :assistant :n 2}]]
      (is (= messages (friction/last-n-turns messages 5))))))

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
      (is (nil? (#'context-manager/default-fetch-history api "s1")))))

  (testing "a tool-heavy turn (several messages) still counts as one turn"
    ;; implementation review round 3: `take-last` on raw messages would
    ;; undercount turns whenever a turn spans more than one message; here
    ;; the excluded turn's 2 messages plus the tool-heavy turn's 4 messages
    ;; total 6 raw messages, so a naive `take-last 4` on raw messages would
    ;; wrongly still include part of the excluded turn's assistant message
    ;; and miss part of the tool-heavy turn. Turn-grouping keeps exactly
    ;; the last `friction-history-turn-count` (4) turns regardless of how
    ;; many raw messages each spans.
    (let [excluded [{:role :user :content [{:type :text :text "excluded turn"}]}
                    {:role :assistant :content [{:type :text :text "excluded reply"}]}]
          tool-heavy-turn [{:role :user :content [{:type :text :text "turn A"}]}
                           {:role :assistant :content [{:type :text :text "turn A step 1"}]}
                           {:role :tool :content [{:type :text :text "turn A tool result"}]}
                           {:role :assistant :content [{:type :text :text "turn A final"}]}]
          other-turns (vec (for [n (range (dec friction/friction-history-turn-count))]
                             {:role :user :content [{:type :text :text (str "turn " n)}]}))
          messages (vec (concat excluded tool-heavy-turn other-turns))
          api {:query-session (fn [_ _] {:psi.agent-session/message-history messages})}
          excerpt (#'context-manager/default-fetch-history api "s1")]
      (is (not (re-find #"excluded" excerpt))
          "the turn before the windowed turns is fully excluded")
      (is (every? #(re-find (re-pattern (str "turn A" %)) excerpt) ["" " step 1" " tool result" " final"])
          "every message of the tool-heavy turn is present, not truncated")
      (is (every? #(re-find (re-pattern (str "turn " %)) excerpt)
                  (range (dec friction/friction-history-turn-count)))
          "every subsequent single-message turn is present"))))

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
