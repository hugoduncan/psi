(ns psi.agent-session.compaction-test
  "Tests for compaction preparation and message rebuild behavior."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.compaction :as compaction]
   [psi.session-state.model :as session]
   [psi.session-persistence.core :as persist]))

(defn- user-message [text]
  {:role "user"
   :content [{:type :text :text text}]})

(defn- assistant-text-message [text]
  {:role "assistant"
   :content [{:type :text :text text}]})

(defn- system-message [text]
  {:role "system"
   :content [{:type :text :text text}]})

(defn- mid-system-entry [text]
  (session/make-entry :mid-system {:text text :source :test}))

(defn- assistant-toolcall-message [name path]
  {:role "assistant"
   :content [{:type :tool-call
              :id (str "tc-" name)
              :name name
              :arguments (str "{\"path\":\"" path "\"}")}]})

(defn- session-with-user-entries
  [n]
  (let [entries (mapv (fn [i]
                        (persist/message-entry (user-message (str "msg " i))))
                      (range n))]
    (assoc (session/initial-session)
           :session-entries entries
           :context-tokens  50000
           :context-window  100000)))

;; ── prepare-compaction ─────────────────────────────────────────────────────

(deftest prepare-compaction-test
  (testing "returns structured preparation with new fields"
    (let [sd   (session-with-user-entries 15)
          prep (compaction/prepare-compaction sd 20)]
      (is (vector? (:entries-to-summarise prep)))
      (is (vector? (:messages-to-summarize prep)))
      (is (vector? (:turn-prefix-messages prep)))
      (is (contains? prep :is-split-turn))
      (is (contains? prep :file-ops))
      (is (string? (:first-kept-entry-id prep)))))

  (testing "keeps everything when token budget is very large"
    (let [sd   (session-with-user-entries 5)
          prep (compaction/prepare-compaction sd 100000)]
      (is (= 0 (count (:entries-to-summarise prep))))
      (is (= (get-in sd [:session-entries 0 :id])
             (:first-kept-entry-id prep)))))

  (testing "returns nil when last entry is already a compaction entry"
    (let [sd      (session-with-user-entries 3)
          summary (persist/compaction-entry
                   {:summary "already compacted"
                    :first-kept-entry-id (get-in sd [:session-entries 1 :id])
                    :tokens-before 100
                    :details nil}
                   false)
          sd'     (update sd :session-entries conj summary)]
      (is (nil? (compaction/prepare-compaction sd' 100)))))

  (testing "extracts file ops from summarized messages"
    (let [entries [(persist/message-entry (user-message "u1"))
                   (persist/message-entry (assistant-toolcall-message "read" "src/a.clj"))
                   (persist/message-entry (assistant-toolcall-message "write" "src/b.clj"))
                   (persist/message-entry (user-message "tail"))]
          sd (assoc (session/initial-session)
                    :session-entries entries
                    :context-window 100000)
          prep (compaction/prepare-compaction sd 1)]
      (is (contains? (set (get-in prep [:file-ops :read-files])) "src/a.clj"))
      (is (contains? (set (get-in prep [:file-ops :modified-files])) "src/b.clj"))))

  (testing "marks split-turn when cut point lands on assistant message"
    (let [entries [(persist/message-entry (user-message "turn user"))
                   (persist/message-entry (assistant-text-message (apply str (repeat 120 "a"))))
                   (persist/message-entry (assistant-text-message (apply str (repeat 120 "b"))))]
          sd (assoc (session/initial-session)
                    :session-entries entries
                    :context-window 100000)
          prep (compaction/prepare-compaction sd 1)]
      (is (true? (:is-split-turn prep)))
      (is (pos? (count (:turn-prefix-messages prep)))))))

;; ── stub-compaction-fn ─────────────────────────────────────────────────────

(deftest stub-compaction-fn-test
  (testing "returns CompactionResult with summary string"
    (let [sd   (session-with-user-entries 10)
          prep (compaction/prepare-compaction sd 10)
          res  (compaction/stub-compaction-fn sd prep nil)]
      (is (string? (:summary res)))
      (is (re-find #"stub" (:summary res)))
      (is (= (:first-kept-entry-id prep) (:first-kept-entry-id res)))
      (is (= (:tokens-before prep) (:tokens-before res)))
      (is (nil? (:details res))))))

;; ── rebuild-messages-from-entries ─────────────────────────────────────────

(deftest rebuild-messages-test
  (testing "returns summary message when no kept entries"
    (let [sd     (session-with-user-entries 5)
          result {:summary "All summarised."
                  :first-kept-entry-id nil
                  :tokens-before 50000}
          msgs   (compaction/rebuild-messages-from-entries result sd)]
      (is (= 1 (count msgs)))
      (is (re-find #"Previous conversation summary" (get-in msgs [0 :content 0 :text])))))

  (testing "includes messages from kept entries after summary"
    (let [sd      (session-with-user-entries 5)
          kept-id (get-in sd [:session-entries 3 :id])
          result  {:summary "Summary."
                   :first-kept-entry-id kept-id
                   :tokens-before 50000}
          msgs    (compaction/rebuild-messages-from-entries result sd)]
      (is (= 3 (count msgs)))
      (is (re-find #"Summary" (get-in msgs [0 :content 0 :text])))))

  (testing "includes mid-system messages from kept entries after summary"
    (let [u1      (persist/message-entry (user-message "u1"))
          mid     (mid-system-entry "Use terse answers")
          sd      (assoc (session/initial-session)
                         :session-entries [u1 mid]
                         :context-tokens 50000)
          result  {:summary "Summary."
                   :first-kept-entry-id (:id u1)
                   :tokens-before 50000}
          msgs    (compaction/rebuild-messages-from-entries result sd)]
      (is (= ["user" "user" "system"] (mapv :role msgs)))
      (is (= (system-message "Use terse answers")
             (select-keys (nth msgs 2) [:role :content])))))

  (testing "preserves pre-cut mid-system instructions after summary when no retained user boundary exists"
    (let [u1     (persist/message-entry (user-message "u1"))
          mid    (mid-system-entry "Continue using constraints")
          sd     (assoc (session/initial-session)
                        :session-entries [u1 mid]
                        :context-tokens 50000)
          result {:summary "Summary."
                  :first-kept-entry-id nil
                  :tokens-before 50000}
          msgs   (compaction/rebuild-messages-from-entries result sd)]
      (is (= ["user" "system"] (mapv :role msgs)))
      (is (= (system-message "Continue using constraints")
             (select-keys (second msgs) [:role :content])))))

  (testing "attaches pre-cut mid-system instructions after latest retained pending user"
    (let [u1     (persist/message-entry (user-message "u1"))
          mid    (mid-system-entry "Keep JSON terse")
          u2     (persist/message-entry (user-message "u2"))
          sd     (assoc (session/initial-session)
                        :session-entries [u1 mid u2]
                        :context-tokens 50000)
          result {:summary "Summary."
                  :first-kept-entry-id (:id u2)
                  :tokens-before 50000}
          msgs   (compaction/rebuild-messages-from-entries result sd)]
      (is (= ["user" "user" "system"] (mapv :role msgs)))
      (is (= (system-message "Keep JSON terse")
             (select-keys (nth msgs 2) [:role :content])))))

  (testing "advances cut past completed retained exchanges before placing preserved mid-system"
    (let [u1     (persist/message-entry (user-message "u1"))
          mid    (mid-system-entry "Future-only instruction")
          u2     (persist/message-entry (user-message "u2"))
          a2     (persist/message-entry (assistant-text-message "a2"))
          sd     (assoc (session/initial-session)
                        :session-entries [u1 mid u2 a2]
                        :context-tokens 50000)
          result {:summary "Summary."
                  :first-kept-entry-id (:id u2)
                  :tokens-before 50000}
          msgs   (compaction/rebuild-messages-from-entries result sd)]
      (is (= ["user" "system"] (mapv :role msgs))
          "retained user/assistant exchange is summarized rather than retroactively rewritten")
      (is (= (system-message "Future-only instruction")
             (select-keys (second msgs) [:role :content])))))

  (testing "merges retained boundary mid-system entries with pre-cut instructions"
    (let [u1     (persist/message-entry (user-message "u1"))
          old    (mid-system-entry "Old active instruction")
          new    (mid-system-entry "Boundary instruction")
          u2     (persist/message-entry (user-message "u2"))
          sd     (assoc (session/initial-session)
                        :session-entries [u1 old new u2]
                        :context-tokens 50000)
          result {:summary "Summary."
                  :first-kept-entry-id (:id new)
                  :tokens-before 50000}
          msgs   (compaction/rebuild-messages-from-entries result sd)]
      (is (= ["user" "user" "system"] (mapv :role msgs)))
      (is (= "Old active instruction\n\nBoundary instruction"
             (get-in msgs [2 :content 0 :text]))))))

;; ── logprobs entries: compaction transparency ─────────────────────────────

(deftest logprobs-entries-skipped-by-compaction-test
  (testing ":logprobs entries are not message-like and not valid cut-points"
    (let [tokens [{:token "hi" :logprob -0.01 :top []}]
          u1     (persist/message-entry (user-message "u1"))
          a1     (persist/message-entry (assistant-text-message "a1"))
          lp     (persist/logprobs-entry "turn-1" tokens)
          u2     (persist/message-entry (user-message "u2"))
          a2     (persist/message-entry (assistant-text-message "a2"))]
      ;; :logprobs entry must not produce a provider message
      (let [msgs (compaction/rebuild-messages-from-journal-entries [u1 a1 lp u2 a2])]
        (is (= 4 (count msgs))
            ":logprobs entry must be skipped (not projected) in journal rebuild"))
      ;; :logprobs entry must not be a valid cut-point in prepare-compaction
      (let [sd (assoc (session/initial-session)
                      :session-entries [u1 a1 lp u2 a2]
                      :context-window 100000)
            prep (compaction/prepare-compaction sd 1)]
        (is (some? prep) "compaction preparation must succeed")
        ;; The first kept entry must be a real message entry, not the logprobs entry
        (is (not= (:id lp) (:first-kept-entry-id prep))
            ":logprobs entry must not be a valid cut-point")))))

(deftest rebuild-messages-from-journal-test
  (testing "latest compaction boundary is respected"
    (let [u1   (persist/message-entry (user-message "u1"))
          a1   (persist/message-entry (assistant-text-message "a1"))
          u2   (persist/message-entry (user-message "u2"))
          a2   (persist/message-entry (assistant-text-message "a2"))
          comp (persist/compaction-entry
                {:summary "Summary of first part"
                 :first-kept-entry-id (:id u2)
                 :tokens-before 123
                 :details nil}
                false)
          u3   (persist/message-entry (user-message "u3"))
          msgs (compaction/rebuild-messages-from-journal-entries [u1 a1 u2 a2 comp u3])]
      (is (= 4 (count msgs)))
      (is (re-find #"Previous conversation summary" (get-in msgs [0 :content 0 :text]))))))
