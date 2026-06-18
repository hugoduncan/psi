(ns psi.workflow-judge
  "Pure judge projection and routing logic for deterministic workflow runs."
  (:require
   [clojure.string :as str]))

;;; Projection

(defn- text-block?
  "True if a content block is a text block (not tool_use or tool_result)."
  [block]
  (and (map? block)
       (= :text (:type block))))

(defn- user-message?
  [msg]
  (= "user" (:role msg)))

(defn- strip-tool-blocks
  "Remove tool_use and tool_result content blocks from a message.
   Returns nil if all content blocks are stripped."
  [message]
  (if (string? (:content message))
    message
    (let [filtered (filterv text-block? (:content message))]
      (when (seq filtered)
        (assoc message :content filtered)))))

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

;;; Routing

(defn check-iteration-limit
  "Check a target step-run's iteration count against a directive's max-iterations.
   Returns :within-limit or :exhausted."
  [iteration-count max-iterations]
  (if (and max-iterations (>= (or iteration-count 0) max-iterations))
    :exhausted
    :within-limit))

(defn match-signal
  "Match a signal against a routing table.
   String signals match after trim; keyword/data signals match exactly."
  [signal routing-table]
  (when (and signal routing-table)
    (get routing-table
         (if (string? signal)
           (str/trim signal)
           signal))))

(defn resolve-goto-target
  "Resolve a :goto value to a concrete action.

   Returns one of:
   - {:action :goto :target step-id}
   - {:action :complete}
   - {:action :fail :reason ...}"
  [goto current-step-id step-order]
  (let [idx (.indexOf ^java.util.List step-order current-step-id)
        last-idx (dec (count step-order))]
    (cond
      (= :done goto)
      {:action :complete}

      (= :next goto)
      (if (>= idx last-idx)
        {:action :complete}
        {:action :goto :target (nth step-order (inc idx))})

      (= :previous goto)
      (if (<= idx 0)
        {:action :fail :reason :no-previous-step}
        {:action :goto :target (nth step-order (dec idx))})

      (string? goto)
      (if (contains? (set step-order) goto)
        {:action :goto :target goto}
        {:action :fail :reason :unknown-step :step-id goto})

      :else
      {:action :fail :reason :invalid-goto :goto goto})))

(defn evaluate-routing
  "Evaluate a judge signal against a routing table with iteration checks.

   This is the runtime-governing exhaustion site for judged loops (DI-6): on
   exhaustion it either hard-fails (`:iteration-exhausted`) or, when the directive
   carries `:on-max-iterations`, routes to that author-chosen target via the same
   `resolve-goto-target` resolution used for `:goto` — so the run is never marked
   failed and continues to the author's handback step.

   Returns one of:
   - {:action :goto :target step-id}
   - {:action :complete}
   - {:action :fail :reason ...}
   - {:action :no-match}"
  [signal routing-table current-step-id step-order step-runs]
  (if-let [directive (match-signal signal routing-table)]
    (let [resolved (resolve-goto-target (:goto directive) current-step-id step-order)]
      (case (:action resolved)
        :goto
        (let [target (:target resolved)
              target-run (get step-runs target)
              iter-count (get target-run :iteration-count 0)
              limit-check (check-iteration-limit iter-count (:max-iterations directive))]
          (if (= :exhausted limit-check)
            (if (contains? directive :on-max-iterations)
              (resolve-goto-target (:on-max-iterations directive) current-step-id step-order)
              {:action :fail :reason :iteration-exhausted :step-id target :iteration-count iter-count})
            resolved))

        ;; :complete or :fail — pass through
        resolved))
    {:action :no-match}))
