(ns psi.agent-session.compaction
  "Compaction support: preparation logic and injectable execute fn.

   This namespace owns:
     - CompactionPreparation construction (cut-point + split-turn detection)
     - Lightweight context token estimation
     - File-operation extraction for summaries
     - Stub/default compaction + branch-summary fns
     - Message rebuild helpers used after compaction and on resume"
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]))

;; ============================================================
;; Message helpers
;; ============================================================

(defn- ->kw [x]
  (cond
    (keyword? x) x
    (string? x)  (keyword x)
    :else        nil))

(defn- make-summary-message
  [prefix summary timestamp]
  {:role      "user"
   :content   [{:type :text
                :text (str prefix "\n\n" (or summary ""))}]
   :timestamp (or timestamp (java.time.Instant/now))})

(defn entry->message
  "Convert a journal SessionEntry to an agent message, when applicable.
   Returns nil for non-message entry kinds."
  [entry]
  (case (:kind entry)
    :message
    (get-in entry [:data :message])

    :custom-message
    {:role      "user"
     :content   (let [content (get-in entry [:data :content])]
                  (cond
                    (string? content) [{:type :text :text content}]
                    (sequential? content) (vec content)
                    :else [{:type :text :text (str content)}]))
     :timestamp (:timestamp entry)}

    :branch-summary
    (make-summary-message
     "Branch summary:"
     (get-in entry [:data :summary])
     (:timestamp entry))

    :mid-system
    {:role      "system"
     :content   [{:type :text
                  :text (or (get-in entry [:data :text]) "")}]
     :timestamp (:timestamp entry)}

    :compaction
    (make-summary-message
     "Previous conversation summary:"
     (get-in entry [:data :summary])
     (:timestamp entry))

    nil))

(defn- message-like-entry?
  [entry]
  (contains? #{:message :custom-message :branch-summary :mid-system} (:kind entry)))

(defn- turn-start-entry?
  [entry]
  (or (contains? #{:custom-message :branch-summary} (:kind entry))
      (and (= :message (:kind entry))
           (let [role (get-in entry [:data :message :role])]
             (or (= "user" role)
                 (= "bashExecution" role))))))

(defn- valid-cut-point?
  [entry]
  (cond
    (contains? #{:custom-message :branch-summary} (:kind entry))
    true

    (= :message (:kind entry))
    (not= "toolResult" (get-in entry [:data :message :role]))

    :else
    false))

;; ============================================================
;; Token estimation
;; ============================================================

(defn- usage-number
  [usage k1 k2]
  (let [v (or (get usage k1) (get usage k2) 0)]
    (if (number? v) v 0)))

(defn usage-total-tokens
  "Best-effort total token count from a usage map."
  [usage]
  (when (map? usage)
    (let [total (or (:total-tokens usage)
                    (:totalTokens usage)
                    (:total usage))]
      (if (number? total)
        total
        (+ (usage-number usage :input-tokens :input)
           (usage-number usage :output-tokens :output)
           (usage-number usage :cache-read-tokens :cache-read)
           (usage-number usage :cache-write-tokens :cache-write))))))

(defn- estimate-block-tokens
  [block]
  (let [t (->kw (:type block))]
    (case t
      :text      (long (Math/ceil (/ (double (count (or (:text block) ""))) 4.0)))
      :thinking  (long (Math/ceil (/ (double (count (or (:thinking block) ""))) 4.0)))
      :tool-call (long (Math/ceil (/ (double (+ (count (str (:name block)))
                                                (count (str (:arguments block)))))
                                     4.0)))
      :image     1200
      0)))

(defn estimate-message-tokens
  "Heuristic token estimate for one agent message."
  [message]
  (let [role (:role message)]
    (cond
      (nil? message) 0

      (contains? #{"user" "assistant" "toolResult" "custom"} role)
      (let [content (:content message)]
        (cond
          (string? content)
          (long (Math/ceil (/ (double (count content)) 4.0)))

          (sequential? content)
          (reduce + 0 (map estimate-block-tokens content))

          :else
          (long (Math/ceil (/ (double (count (str content))) 4.0)))))

      (= "bashExecution" role)
      (long (Math/ceil (/ (double (+ (count (str (:command message)))
                                     (count (str (:output message)))))
                          4.0)))

      (contains? #{"branchSummary" "compactionSummary"} role)
      (long (Math/ceil (/ (double (count (or (:summary message) ""))) 4.0)))

      :else
      (long (Math/ceil (/ (double (count (pr-str message))) 4.0))))))

(defn estimate-context-tokens
  "Estimate context tokens from message history.

   If we can find the latest assistant usage, use that as a base and only
   estimate trailing messages. Otherwise estimate all messages."
  [messages]
  (let [n (count messages)
        usage-info
        (first
         (keep (fn [idx]
                 (let [m (nth messages idx)
                       usage (:usage m)
                       stop-reason (->kw (:stop-reason m))]
                   (when (and (= "assistant" (:role m))
                              (map? usage)
                              (not (contains? #{:aborted :error} stop-reason))
                              (number? (usage-total-tokens usage)))
                     {:index idx :usage usage})))
               (range (dec n) -1 -1)))]
    (if-not usage-info
      (let [estimated (reduce + 0 (map estimate-message-tokens messages))]
        {:tokens estimated
         :usage-tokens 0
         :trailing-tokens estimated
         :last-usage-index nil})
      (let [usage-tokens (usage-total-tokens (:usage usage-info))
            trailing (reduce + 0 (map estimate-message-tokens
                                      (subvec (vec messages) (inc (:index usage-info)))))
            total (+ usage-tokens trailing)]
        {:tokens total
         :usage-tokens usage-tokens
         :trailing-tokens trailing
         :last-usage-index (:index usage-info)}))))

;; ============================================================
;; File operation extraction
;; ============================================================

(defn- parse-tool-arguments
  [arguments]
  (cond
    (map? arguments)
    arguments

    (string? arguments)
    (try
      (json/parse-string arguments)
      (catch Exception _ nil))

    :else
    nil))

(defn- normalize-details
  [details]
  (cond
    (map? details)
    details

    (string? details)
    (try
      (json/parse-string details)
      (catch Exception _ nil))

    :else
    nil))

(defn- details-list
  [m k1 k2]
  (let [v (or (get m k1) (get m k2))]
    (if (sequential? v) v [])))

(defn- extract-file-ops-from-message
  [file-ops message]
  (if (and (= "assistant" (:role message))
           (sequential? (:content message)))
    (reduce
     (fn [acc block]
       (if (= :tool-call (->kw (:type block)))
         (let [name (str (:name block))
               args (parse-tool-arguments (:arguments block))
               path (or (get args "path")
                        (get args :path))]
           (if (string? path)
             (case name
               "read"  (update acc :read conj path)
               "write" (update acc :written conj path)
               "edit"  (update acc :edited conj path)
               acc)
             acc))
         acc))
     file-ops
     (:content message))
    file-ops))

(defn- file-op-lists
  [file-ops]
  (let [modified (into #{} (concat (:written file-ops) (:edited file-ops)))
        read-only (->> (:read file-ops)
                       (remove modified)
                       sort
                       vec)
        modified-files (->> modified sort vec)]
    {:read-files read-only
     :modified-files modified-files}))

(defn- extract-file-ops
  [entries messages-to-summarize turn-prefix-messages prev-compaction-idx]
  (let [base {:read #{} :written #{} :edited #{}}
        base'
        (if (neg? prev-compaction-idx)
          base
          (let [prev-entry (nth entries prev-compaction-idx)
                from-hook? (boolean (get-in prev-entry [:data :from-hook]))
                details (normalize-details (get-in prev-entry [:data :details]))]
            (if (or from-hook? (not (map? details)))
              base
              (let [reads (details-list details :read-files "readFiles")
                    mods  (details-list details :modified-files "modifiedFiles")]
                (-> base
                    (update :read into (filter string? reads))
                    (update :edited into (filter string? mods)))))))
        all-messages (concat messages-to-summarize turn-prefix-messages)]
    (file-op-lists (reduce extract-file-ops-from-message base' all-messages))))

;; ============================================================
;; Cut-point selection
;; ============================================================

(defn- find-valid-cut-points
  [entries start-index end-index]
  (reduce
   (fn [acc idx]
     (if (valid-cut-point? (nth entries idx))
       (conj acc idx)
       acc))
   []
   (range start-index end-index)))

(defn- find-turn-start-index
  [entries entry-index start-index]
  (loop [idx entry-index]
    (cond
      (< idx start-index)
      -1

      (turn-start-entry? (nth entries idx))
      idx

      :else
      (recur (dec idx)))))

(defn find-cut-point
  "Find cut-point in `entries` for [start-index, end-index) keeping roughly
   `keep-recent-tokens` in the kept suffix."
  [entries start-index end-index keep-recent-tokens]
  (let [entries (vec entries)
        cut-points (find-valid-cut-points entries start-index end-index)]
    (if (empty? cut-points)
      {:first-kept-entry-index start-index
       :turn-start-index -1
       :is-split-turn false}
      (let [initial-cut (first cut-points)
            cut-index
            (loop [idx (dec end-index)
                   accumulated 0
                   chosen initial-cut]
              (if (< idx start-index)
                chosen
                (let [entry (nth entries idx)
                      msg (entry->message entry)
                      next-acc (if msg (+ accumulated (estimate-message-tokens msg)) accumulated)]
                  (if (>= next-acc keep-recent-tokens)
                    (or (first (filter #(>= % idx) cut-points)) chosen)
                    (recur (dec idx) next-acc chosen)))))
            cut-index'
            (loop [idx cut-index]
              (if (<= idx start-index)
                idx
                (let [prev-entry (nth entries (dec idx))]
                  (if (or (= :compaction (:kind prev-entry))
                          (message-like-entry? prev-entry))
                    idx
                    (recur (dec idx))))))
            cut-entry (nth entries cut-index')
            split? (not (turn-start-entry? cut-entry))
            turn-start (if split?
                         (find-turn-start-index entries cut-index' start-index)
                         -1)
            split-turn? (and split? (>= turn-start start-index))]
        {:first-kept-entry-index cut-index'
         :turn-start-index turn-start
         :is-split-turn split-turn?}))))

;; ============================================================
;; CompactionPreparation
;; ============================================================

(defn prepare-compaction
  "Compute a CompactionPreparation from `session-data`.

   Returns nil when the latest entry is already a compaction entry.

   Keys:
     :entries-to-summarise   [SessionEntry ...]
     :messages-to-summarize  [AgentMessage ...]
     :turn-prefix-messages   [AgentMessage ...]
     :is-split-turn          Boolean
     :first-kept-entry-id    String or nil
     :tokens-before          Integer or nil
     :previous-summary       String or nil
     :file-ops               {:read-files [...] :modified-files [...]}"
  ([session-data]
   (prepare-compaction session-data 20000))
  ([session-data keep-recent-tokens]
   (let [entries (vec (:session-entries session-data))
         n      (count entries)]
     (when-not (and (pos? n)
                    (= :compaction (:kind (peek entries))))
       (if (zero? n)
         {:entries-to-summarise  []
          :messages-to-summarize []
          :turn-prefix-messages  []
          :is-split-turn         false
          :first-kept-entry-id   nil
          :tokens-before         (:context-tokens session-data)
          :previous-summary      nil
          :file-ops              {:read-files [] :modified-files []}
          :settings              {:enabled true
                                  :reserve-tokens 16384
                                  :keep-recent-tokens keep-recent-tokens}}
         (let [prev-compaction-idx
               (loop [idx (dec n)]
                 (cond
                   (< idx 0) -1
                   (= :compaction (:kind (nth entries idx))) idx
                   :else (recur (dec idx))))
               boundary-start (inc prev-compaction-idx)
               boundary-end   n
               usage-start    (if (neg? prev-compaction-idx) 0 prev-compaction-idx)
               usage-messages (->> (subvec entries usage-start boundary-end)
                                   (keep entry->message)
                                   vec)
               estimated-tokens (:tokens (estimate-context-tokens usage-messages))
               tokens-before (or (:context-tokens session-data) estimated-tokens)
               cut (find-cut-point entries boundary-start boundary-end keep-recent-tokens)
               first-kept-entry (nth entries (:first-kept-entry-index cut) nil)
               first-kept-id (:id first-kept-entry)
               history-end   (if (:is-split-turn cut)
                               (:turn-start-index cut)
                               (:first-kept-entry-index cut))
               to-summarise-entries (vec (subvec entries boundary-start (max boundary-start history-end)))
               turn-prefix-entries (if (:is-split-turn cut)
                                     (vec (subvec entries
                                                  (:turn-start-index cut)
                                                  (:first-kept-entry-index cut)))
                                     [])
               messages-to-summarize (->> to-summarise-entries (keep entry->message) vec)
               turn-prefix-messages (->> turn-prefix-entries (keep entry->message) vec)
               previous-summary (when-not (neg? prev-compaction-idx)
                                  (get-in (nth entries prev-compaction-idx) [:data :summary]))
               file-ops (extract-file-ops entries
                                          messages-to-summarize
                                          turn-prefix-messages
                                          prev-compaction-idx)]
           {:entries-to-summarise  to-summarise-entries
            :messages-to-summarize messages-to-summarize
            :turn-prefix-messages  turn-prefix-messages
            :is-split-turn         (boolean (:is-split-turn cut))
            :first-kept-entry-id   first-kept-id
            :tokens-before         tokens-before
            :previous-summary      previous-summary
            :file-ops              file-ops
            :settings              {:enabled true
                                    :reserve-tokens 16384
                                    :keep-recent-tokens keep-recent-tokens}}))))))

;; ============================================================
;; Stub compaction fn (default / test)
;; ============================================================

(defn stub-compaction-fn
  "Default (stub) compaction function. Returns a placeholder CompactionResult
   without calling the LLM. Replace in production with a real implementation."
  [_session-data preparation _custom-instructions]
  (let [summary-count (or (count (:messages-to-summarize preparation))
                          (count (:entries-to-summarise preparation))
                          0)]
    {:summary            (str "Compacted "
                              summary-count
                              " entries. [stub — replace :compaction-fn for real summarisation]")
     :first-kept-entry-id (:first-kept-entry-id preparation)
     :tokens-before      (:tokens-before preparation)
     :details            nil}))

;; ============================================================
;; Branch summary stub
;; ============================================================

(defn stub-branch-summary-fn
  "Default (stub) branch summarisation fn. Returns a placeholder summary."
  [_session-data _abandoned-entries _custom-instructions]
  {:summary "Branch summary. [stub — replace :branch-summary-fn for real summarisation]"
   :details nil})

;; ============================================================
;; Message rebuild helpers
;; ============================================================

(defn- mid-system-entry-text
  [entry]
  (when (= :mid-system (:kind entry))
    (or (get-in entry [:data :text]) "")))

(defn- coalesced-system-message
  [texts timestamp]
  {:role      "system"
   :content   [{:type :text
                :text (str/join "\n\n" texts)}]
   :timestamp timestamp})

(defn- split-kept-entries
  [result session-data]
  (let [entries (vec (:session-entries session-data))]
    (if-let [kept-id (:first-kept-entry-id result)]
      (let [kept-index (first (keep-indexed (fn [idx entry]
                                              (when (= (:id entry) kept-id)
                                                idx))
                                            entries))]
        (if kept-index
          [(subvec entries 0 kept-index) (subvec entries kept-index)]
          [entries []]))
      [entries []])))

(defn- split-boundary-mid-system-entries
  [entries]
  (let [boundary (take-while #(= :mid-system (:kind %)) entries)]
    [(vec boundary) (vec (drop (count boundary) entries))]))

(defn- last-role-index
  [messages role]
  (last (keep-indexed (fn [idx msg]
                        (when (= role (:role msg))
                          idx))
                      messages)))

(defn- normalize-retained-suffix-for-mid-system
  [messages]
  ;; Preserved mid-system instructions must attach to the next generation
  ;; boundary, not to already-generated retained assistant history. Dropping
  ;; everything through the last retained assistant mirrors advancing the
  ;; compaction cut past completed retained exchanges.
  (if-let [last-assistant-index (last-role-index messages "assistant")]
    (subvec (vec messages) (inc last-assistant-index))
    (vec messages)))

(defn- insert-after-latest-user
  [messages system-message]
  (if-let [last-user-index (last-role-index messages "user")]
    (vec (concat (subvec messages 0 (inc last-user-index))
                 [system-message]
                 (subvec messages (inc last-user-index))))
    (into [system-message] messages)))

(defn- rebuild-kept-entry-messages-with-mid-system-preservation
  [pre-cut kept-entries summary-msg]
  (let [[boundary-mid-entries kept-entries*] (split-boundary-mid-system-entries kept-entries)
        preserved-texts (vec (keep mid-system-entry-text pre-cut))
        boundary-texts  (vec (keep mid-system-entry-text boundary-mid-entries))
        retained-msgs   (->> kept-entries* (keep entry->message) vec)
        texts           (vec (concat preserved-texts boundary-texts))]
    (if (seq texts)
      (let [retained-msgs* (normalize-retained-suffix-for-mid-system retained-msgs)
            system-msg     (coalesced-system-message texts (:timestamp summary-msg))]
        (if (last-role-index retained-msgs* "user")
          (insert-after-latest-user retained-msgs* system-msg)
          (into [system-msg] retained-msgs*)))
      retained-msgs)))

(defn- rebuild-kept-messages-with-mid-system-preservation
  [result session-data summary-msg]
  (let [[pre-cut kept-entries] (split-kept-entries result session-data)]
    (rebuild-kept-entry-messages-with-mid-system-preservation
     pre-cut
     kept-entries
     summary-msg)))

(defn rebuild-messages-from-entries
  "Given a compaction `result` and current `session-data`, return the
   vector of agent messages that should replace the agent message list.

   Inserts a synthetic user summary message, then all kept messages.
   Includes :message, :custom-message, :branch-summary, and :mid-system
   entry kinds. Preserves active pre-cut and boundary :mid-system entries by
   coalescing them at a valid next-generation boundary."
  [result session-data]
  (let [summary-msg (make-summary-message
                     "Previous conversation summary:"
                     (:summary result)
                     (java.time.Instant/now))
        kept-msgs   (rebuild-kept-messages-with-mid-system-preservation
                     result
                     session-data
                     summary-msg)]
    (into [summary-msg] kept-msgs)))

(defn rebuild-messages-from-journal-entries
  "Rebuild agent messages from all session journal `entries`, honoring the
   latest compaction boundary when present.

   When a compaction entry exists, output order is:
     1) compaction summary message
     2) kept entries before the compaction entry (from first-kept-entry-id)
     3) entries after the compaction entry"
  [entries]
  (let [entries (vec entries)
        n       (count entries)
        compaction-idx
        (loop [idx (dec n)]
          (cond
            (< idx 0) -1
            (= :compaction (:kind (nth entries idx))) idx
            :else (recur (dec idx))))]
    (if (neg? compaction-idx)
      (->> entries (keep entry->message) vec)
      (let [compaction-entry (nth entries compaction-idx)
            summary-msg (entry->message compaction-entry)
            first-kept-id (get-in compaction-entry [:data :first-kept-entry-id])
            before (subvec entries 0 compaction-idx)
            kept-before (if first-kept-id
                          (drop-while #(not= (:id %) first-kept-id) before)
                          [])
            pre-cut (if first-kept-id
                      (take-while #(not= (:id %) first-kept-id) before)
                      before)
            after (subvec entries (inc compaction-idx))
            kept-msgs (rebuild-kept-entry-messages-with-mid-system-preservation
                       pre-cut
                       (vec kept-before)
                       summary-msg)
            after-msgs (->> after (keep entry->message) vec)]
        (into (if summary-msg [summary-msg] []) (concat kept-msgs after-msgs))))))
