(ns psi.agent-session.workflow-judge
  "Judge execution and projection for deterministic workflow runs.

   Pure projection functions extract a view of actor session messages for the judge.
   Routing functions evaluate judge signals against routing tables.
   Impure execution functions create and prompt judge sessions.")

;;; Projection — pure functions

(defn- text-block?
  "True if a content block is a text block (not tool_use or tool_result)."
  [block]
  (and (map? block)
       (= :text (:type block))))

(defn- strip-tool-blocks
  "Remove tool_use and tool_result content blocks from a message.
   Returns nil if all content blocks are stripped."
  [message]
  (if (string? (:content message))
    message
    (let [filtered (filterv text-block? (:content message))]
      (when (seq filtered)
        (assoc message :content filtered)))))

(defn- user-message?
  [msg]
  (= "user" (:role msg)))

(defn- collect-turns
  "Collect conversation turns from a message sequence.
   A turn starts with a user message and includes all subsequent messages
   (assistant, tool, assistant continuations) until the next user message.
   Returns a vector of turns, each turn being a vector of messages."
  [messages]
  (loop [msgs messages
         turns []
         current-turn []]
    (if (empty? msgs)
      (if (seq current-turn)
        (conj turns current-turn)
        turns)
      (let [msg (first msgs)
            rest-msgs (rest msgs)]
        (if (and (user-message? msg) (seq current-turn))
          ;; New user message starts a new turn; flush previous
          (recur rest-msgs (conj turns current-turn) [msg])
          ;; Everything else (including the first user message) accumulates
          (recur rest-msgs turns (conj current-turn msg)))))))

(defn project-messages
  "Apply a projection spec to a message sequence.

   Projection specs:
   - :none → empty vector
   - :full → all messages
   - {:type :tail :turns N} → last N conversation turns
   - {:type :tail :turns N :tool-output false} → last N turns, tool blocks stripped"
  [messages projection]
  (cond
    (= :none projection)
    []

    (or (= :full projection) (nil? projection))
    (vec messages)

    (and (map? projection) (= :tail (:type projection)))
    (let [{:keys [turns tool-output]} projection
          all-turns (collect-turns messages)
          tail-turns (vec (take-last turns all-turns))
          flat-msgs (into [] cat tail-turns)]
      (if (false? tool-output)
        (into [] (keep strip-tool-blocks) flat-msgs)
        flat-msgs))

    :else
    (vec messages)))
