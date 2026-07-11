(ns extensions.context-manager-friction-collaborators-test
  "Direct unit tests for the friction analyzer's real (non-injected)
   `:fetch-history`/`:session-info` collaborators added in slice 4
   (`default-fetch-history`, `default-session-info`) and their pure
   supporting fns in `extensions.context-manager.friction`
   (`message-snippet`, `session-info-of`, `group-into-turns`,
   `last-n-turns`) — driven against realistic EQL query-session result
   shapes (task 239, implementation review rounds 2, 3, and 7). Message
   fixtures use the real agent-core `:role` string shape (`\"user\"`/
   `\"assistant\"`/`\"tool\"`), not keywords (round-7 follow-up: keyword
   fixtures previously masked a keyword-vs-string boundary-check bug in
   `group-into-turns`)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.context-manager :as context-manager]
   [extensions.context-manager.friction :as friction]))

(deftest message-snippet-test
  (testing "joins :text entries from a raw agent-core message's :content"
    (is (= "hello world"
           (friction/message-snippet
            {:role "user"
             :content [{:type :text :text "hello"}
                       {:type :text :text "world"}]}))))

  (testing "ignores non-:text content entries"
    (is (= "hello"
           (friction/message-snippet
            {:role "assistant"
             :content [{:type :text :text "hello"}
                       {:type :tool-use :id "t1" :name "bash" :input {}}]}))))

  (testing "no :content or no :text entries yields empty string"
    (is (= "" (friction/message-snippet {:role "user"})))
    (is (= "" (friction/message-snippet {:role "user" :content []}))))

  (testing "includes :type :error blocks (round-7 follow-up: tool errors/
            timeouts must not be hidden from the friction excerpt)"
    (is (= "hello timed out"
           (friction/message-snippet
            {:role "assistant"
             :content [{:type :text :text "hello"}
                       {:type :error :text "timed out"}]}))))

  (testing "an :error-only message still yields its error text"
    (is (= "connection refused"
           (friction/message-snippet
            {:role "assistant"
             :content [{:type :error :text "connection refused"}]})))))

(deftest history-line-test
  ;; Round-9 follow-up: `history-line` gates the *entire* rendered line —
  ;; `[error]` prefix included — on a non-blank, non-slash snippet. Pin the
  ;; boundary that the round-9 `[error]` marker rides on a real snippet, and
  ;; that a text-less tool failure contributes nothing to the excerpt (so the
  ;; error marker never emits a content-free `[error] Role: ` line, and
  ;; conversely never silently loses a real failure's text). The intended
  ;; behaviour is the current one: the marker surfaces only when the failure
  ;; also carries renderable text — an error signal with no text to hang on is
  ;; dropped whole, not surfaced as a bare marker.
  (testing "an :is-error entry with a real snippet renders the [error]-prefixed line"
    (is (= "[error] Toolresult: boom"
           (friction/history-line {:role "toolResult" :is-error true :snippet "boom"}))))

  (testing "an :is-error entry with a blank/nil/whitespace snippet is dropped whole"
    (is (nil? (friction/history-line {:role "toolResult" :is-error true :snippet ""})))
    (is (nil? (friction/history-line {:role "toolResult" :is-error true :snippet nil})))
    (is (nil? (friction/history-line {:role "toolResult" :is-error true :snippet "   "}))))

  (testing "an :is-error entry whose snippet is a slash-command is dropped whole"
    (is (nil? (friction/history-line {:role "toolResult" :is-error true :snippet "/help"}))))

  (testing "a non-error entry with a real snippet renders without the [error] prefix"
    (is (= "Assistant: hello"
           (friction/history-line {:role "assistant" :snippet "hello"})))))

(deftest group-into-turns-test
  (testing "groups messages into per-turn vectors starting at each :user message"
    (is (= [[{:role "user" :n 1}]
            [{:role "user" :n 2} {:role "assistant" :n 3} {:role "tool" :n 4}]]
           (friction/group-into-turns
            [{:role "user" :n 1}
             {:role "user" :n 2} {:role "assistant" :n 3} {:role "tool" :n 4}]))))

  (testing "messages preceding the first :user message form their own leading group"
    (is (= [[{:role "assistant" :n 1}] [{:role "user" :n 2}]]
           (friction/group-into-turns
            [{:role "assistant" :n 1} {:role "user" :n 2}]))))

  (testing "empty input yields no turns"
    (is (= [] (friction/group-into-turns []))))

  (testing "a :user keyword role (legacy fixture shape) still starts a new turn"
    (is (= [[{:role :user :n 1}] [{:role :user :n 2} {:role :assistant :n 3}]]
           (friction/group-into-turns
            [{:role :user :n 1} {:role :user :n 2} {:role :assistant :n 3}]))))

  (testing "round-7 direct repro: real string-role messages group into one
            turn per user message, not one giant turn"
    (let [messages (vec (mapcat (fn [n]
                                  [{:role "user" :n n}
                                   {:role "assistant" :n n}])
                                (range 20)))]
      (is (= 20 (count (friction/group-into-turns messages)))
          "40 real-shaped messages (20 turns) group into 20 turns, not 1"))))

(deftest bounded-message-tail-test
  (testing "returns messages unchanged when at or under cap"
    (let [messages (vec (for [n (range 5)] {:n n}))]
      (is (= messages (friction/bounded-message-tail messages 5)))
      (is (= messages (friction/bounded-message-tail messages 10)))))

  (testing "keeps only the last cap messages when over cap"
    (let [messages (vec (for [n (range 10)] {:n n}))]
      (is (= (subvec messages 7) (friction/bounded-message-tail messages 3)))))

  (testing "cap nil or non-positive returns messages unchanged"
    (let [messages (vec (for [n (range 5)] {:n n}))]
      (is (= messages (friction/bounded-message-tail messages nil)))
      (is (= messages (friction/bounded-message-tail messages 0)))))

  (testing "coerces non-vector input to a vector"
    (is (= [1 2 3] (friction/bounded-message-tail '(1 2 3) 10)))))

(deftest last-n-turns-test
  (testing "bounds a multi-message tool-heavy turn as a single turn, not several messages"
    (let [messages [{:role "user" :n 1} {:role "assistant" :n 2}
                    {:role "user" :n 3} {:role "assistant" :n 4} {:role "tool" :n 5}
                    {:role "tool" :n 6} {:role "assistant" :n 7}
                    {:role "user" :n 8} {:role "assistant" :n 9}]]
      ;; turn A (excluded) = n1..n2, turn B (tool-heavy) = n3..n7 (5
      ;; messages), turn C = n8..n9 (2 messages). Last 2 turns = turn B +
      ;; turn C = the last 7 of the 9 messages here, even though a naive
      ;; `take-last 2` on raw messages would keep only the last 2.
      (is (= (subvec messages 2) (friction/last-n-turns messages 2)))))

  (testing "n nil or non-positive returns all messages unchanged"
    (let [messages [{:role "user" :n 1} {:role "assistant" :n 2}]]
      (is (= messages (friction/last-n-turns messages nil)))
      (is (= messages (friction/last-n-turns messages 0)))))

  (testing "n at or beyond the turn count returns all messages"
    (let [messages [{:role "user" :n 1} {:role "assistant" :n 2}]]
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
    (let [messages [{:role "user" :content [{:type :text :text "do X"}]}
                    {:role "assistant" :content [{:type :text :text "did X via bash workaround"}]}]
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
    (let [excluded [{:role "user" :content [{:type :text :text "excluded turn 1"}]}
                    {:role "assistant" :content [{:type :text :text "excluded turn 2"}]}]
          included (vec (for [n (range friction/friction-history-turn-count)]
                          {:role "user" :content [{:type :text :text (str "included turn " n)}]}))
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
    (let [excluded [{:role "user" :content [{:type :text :text "excluded turn"}]}
                    {:role "assistant" :content [{:type :text :text "excluded reply"}]}]
          tool-heavy-turn [{:role "user" :content [{:type :text :text "turn A"}]}
                           {:role "assistant" :content [{:type :text :text "turn A step 1"}]}
                           {:role "tool" :content [{:type :text :text "turn A tool result"}]}
                           {:role "assistant" :content [{:type :text :text "turn A final"}]}]
          other-turns (vec (for [n (range (dec friction/friction-history-turn-count))]
                             {:role "user" :content [{:type :text :text (str "turn " n)}]}))
          messages (vec (concat excluded tool-heavy-turn other-turns))
          api {:query-session (fn [_ _] {:psi.agent-session/message-history messages})}
          excerpt (#'context-manager/default-fetch-history api "s1")]
      (is (not (re-find #"excluded" excerpt))
          "the turn before the windowed turns is fully excluded")
      (is (every? #(re-find (re-pattern (str "turn A" %)) excerpt) ["" " step 1" " tool result" " final"])
          "every message of the tool-heavy turn is present, not truncated")
      (is (every? #(re-find (re-pattern (str "turn " %)) excerpt)
                  (range (dec friction/friction-history-turn-count)))
          "every subsequent single-message turn is present")))

  (testing "a failed tool-result message (:role \"toolResult\" :is-error true)
            is marked with an [error] prefix (round-9 follow-up: the
            out-of-band :is-error flag is the tool-error signal the friction
            detector keys on)"
    (let [messages [{:role "user" :content [{:type :text :text "run the tests"}]}
                    {:role "toolResult" :is-error true
                     :content [{:type :text :text "bash: command not found"}]}]
          api {:query-session (fn [_ _] {:psi.agent-session/message-history messages})}
          excerpt (#'context-manager/default-fetch-history api "s1")]
      (is (re-find #"\[error\] Toolresult: bash: command not found" excerpt)
          "the failed tool result line is prefixed with [error]")))

  (testing "a successful tool-result message (no :is-error) has no error marker"
    (let [messages [{:role "user" :content [{:type :text :text "run the tests"}]}
                    {:role "toolResult"
                     :content [{:type :text :text "all tests passed"}]}]
          api {:query-session (fn [_ _] {:psi.agent-session/message-history messages})}
          excerpt (#'context-manager/default-fetch-history api "s1")]
      (is (not (re-find #"\[error\]" excerpt))
          "a successful tool result is not marked as an error")
      (is (re-find #"Toolresult: all tests passed" excerpt)))))

(deftest default-fetch-history-bounds-a-long-session-history-test
  (testing "a session history far larger than the raw-message cap still
            renders correctly (round-4 review follow-up: bounding avoids
            O(total-messages) turn-grouping work every turn on a long
            session, without losing the last friction-history-turn-count
            turns)"
    (let [many-old-turns (vec (for [n (range 500)]
                                {:role "user" :content [{:type :text :text (str "old turn " n)}]}))
          recent-turns (vec (for [n (range friction/friction-history-turn-count)]
                              {:role "user" :content [{:type :text :text (str "recent turn " n)}]}))
          messages (vec (concat many-old-turns recent-turns))
          api {:query-session (fn [_ _] {:psi.agent-session/message-history messages})}
          excerpt (#'context-manager/default-fetch-history api "s1")]
      (is (not (re-find #"old turn" excerpt))
          "messages beyond the raw-message cap and the turn window are excluded")
      (is (every? #(re-find (re-pattern (str "recent turn " %)) excerpt)
                  (range friction/friction-history-turn-count))
          "every recent turn within the tail-count window is present"))))

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
