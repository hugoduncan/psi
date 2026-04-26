(ns psi.agent-session.workflow-judge
  "Judge execution and projection for deterministic workflow runs.

   Pure projection functions extract a view of actor session messages for the judge.
   Routing functions evaluate judge signals against routing tables.
   Impure execution functions create and prompt judge sessions."
  (:require
   [clojure.string :as str]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-control :as prompt-control]))

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

;;; Routing — pure functions

(defn match-signal
  "Match a signal string against a routing table.
   Returns the matched directive map or nil. Exact match after trim."
  [signal routing-table]
  (when (and signal routing-table)
    (get routing-table (str/trim signal))))

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

(defn check-iteration-limit
  "Check a target step-run's iteration count against a directive's max-iterations.
   Returns :within-limit or :exhausted."
  [iteration-count max-iterations]
  (if (and max-iterations (>= (or iteration-count 0) max-iterations))
    :exhausted
    :within-limit))

(defn evaluate-routing
  "Evaluate a judge signal against a routing table with iteration checks.

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
            {:action :fail :reason :iteration-exhausted :step-id target :iteration-count iter-count}
            resolved))

        ;; :complete or :fail — pass through
        resolved))
    {:action :no-match}))

;;; Judge session execution — impure

(def ^:private max-judge-retries 2)

(defn- assistant-message-text
  "Extract text content from an assistant message.
   Handles both string content and structured content blocks.
   Mirrors workflow-execution/assistant-message-text (cannot share due to dep direction)."
  [assistant-message]
  (or (some->> (:content assistant-message)
               (filter map?)
               (keep (fn [block]
                       (when (= :text (:type block))
                         (:text block))))
               seq
               (str/join "\n"))
      (when (string? (:content assistant-message))
        (:content assistant-message))
      ""))

(defn- judge-retry-feedback
  "Build a feedback message for a judge retry when no signal matched."
  [judge-output expected-signals]
  (str "Your response '" judge-output "' did not match any expected signal. "
       "Expected exactly one of: " (str/join ", " (sort expected-signals)) ". "
       "Respond with exactly one of those words, nothing else."))

(defn execute-judge!
  "Execute the judge phase for a workflow step.

   Creates a judge child session with projected actor messages as preloaded context,
   prompts it, and matches the response against the routing table.
   Retries up to `max-judge-retries` times on no-match with feedback injection.

   `routing-context` is {:current-step-id :step-order :step-runs}.

   Returns {:judge-session-id :judge-output :judge-event :routing-result}."
  [ctx parent-session-id actor-session-id judge-spec routing-table routing-context]
  (let [{:keys [current-step-id step-order step-runs]} routing-context
        projection    (or (:projection judge-spec) :full)
        actor-msgs    (vec (persist/messages-from-entries-in ctx actor-session-id))
        projected     (project-messages actor-msgs projection)
        judge-sid     (str (java.util.UUID/randomUUID))
        expected-sigs (keys routing-table)
        _             ((:create-workflow-child-session-fn ctx)
                       ctx
                       parent-session-id
                       {:child-session-id           judge-sid
                        :session-name               "workflow judge"
                        :system-prompt              (:system-prompt judge-spec)
                        :tool-defs                  []
                        :thinking-level             :off
                        :preloaded-messages         projected
                        :workflow-owned?            true})]
    ;; First attempt
    (prompt-control/prompt-in! ctx judge-sid (:prompt judge-spec))
    (loop [attempt 0
           last-output (str/trim (assistant-message-text (prompt-control/last-assistant-message-in ctx judge-sid)))]
      (let [routing-result (evaluate-routing last-output routing-table
                                             current-step-id step-order step-runs)]
        (if (and (= :no-match (:action routing-result))
                 (< attempt max-judge-retries))
          ;; Retry: inject feedback into the same judge session
          (do
            (prompt-control/prompt-in! ctx judge-sid
                                       (judge-retry-feedback last-output expected-sigs))
            (recur (inc attempt)
                   (str/trim (assistant-message-text (prompt-control/last-assistant-message-in ctx judge-sid)))))
          ;; Matched, or retries exhausted
          {:judge-session-id judge-sid
           :judge-output     last-output
           :judge-event      (when (not= :no-match (:action routing-result))
                               last-output)
           :routing-result   routing-result})))))
