(ns extensions.logprobs
  "Logprobs out-of-band extension.

   Subscribes to session_turn_finished, stores the most recent logprob snapshot,
   registers the logprobs/perplexity deterministic operation, and exposes the
   /logprobs-table slash command."
  (:require
   [clojure.string :as str]
   [psi.workflow-runtime.turn-execution-contract :as turn-execution]))

;; ── State ────────────────────────────────────────────────────────────────────

(defonce ^:private store
  (atom nil))

(defn- store-logprobs!
  "Store the most recent logprobs snapshot. Only replaces when logprobs are non-empty."
  [{:keys [session-id turn-id logprobs assistant-message]}]
  (when (seq logprobs)
    (reset! store {:session-id session-id
                   :turn-id turn-id
                   :logprobs logprobs
                   :assistant-message assistant-message})))

(defn- get-logprobs
  "Retrieve the stored logprob snapshot, or nil."
  []
  @store)

;; ── Perplexity ───────────────────────────────────────────────────────────────

(defn calculate-perplexity
  "Calculate perplexity from a vector of logprob token maps.
   perplexity = exp(-1/N * Σ logprob_i)"
  [tokens]
  (when (seq tokens)
    (let [logprobs (keep :logprob tokens)
          n (count logprobs)]
      (when (pos? n)
        (let [sum (reduce + 0.0 logprobs)]
          (Math/exp (/ (- sum) n)))))))

(defn perplexity-result
  "Build the perplexity result map from stored data for a session.
   Returns the empty result when no snapshot exists or when the stored snapshot
   belongs to a different session than requested."
  [requested-session-id]
  (if-let [{:keys [session-id logprobs assistant-message turn-id]} (get-logprobs)]
    (if (= requested-session-id session-id)
      {:session-id session-id
       :perplexity (calculate-perplexity logprobs)
       :token-count (count (filter :logprob logprobs))
       :turn-id turn-id
       :reply-text (turn-execution/assistant-message-text assistant-message)}
      {:session-id nil
       :perplexity nil
       :token-count 0
       :turn-id nil
       :reply-text nil})
    {:session-id nil
     :perplexity nil
     :token-count 0
     :turn-id nil
     :reply-text nil}))

;; ── Operation handler ────────────────────────────────────────────────────────

(defn invoke-perplexity
  "Deterministic operation handler for logprobs/perplexity.
   Args: {:session-id \"...\"}"
  [{:keys [args]}]
  (let [session-id (:session-id args)]
    (if (some? session-id)
      {:status :ok
       :data (perplexity-result session-id)}
      {:status :error
       :reason :missing-session-id
       :message "logprobs/perplexity requires :session-id in args"})))

;; ── Logprobs table command ───────────────────────────────────────────────────

(defn- format-logprobs-table
  [logprobs]
  (let [rows (mapv (fn [{:keys [token logprob top]}]
                     (let [lp-str (if logprob (format "%.4f" logprob) "nil")
                           pp-str (if logprob (format "%.4f" (Math/exp logprob)) "nil")
                           top-entries (when (seq top)
                                         (mapv (fn [{tk :token tl :logprob}]
                                                 (str tk
                                                      " (" (format "%.4f" (if tl (Math/exp tl) 0.0)) ")"))
                                               top))]
                       {:token token
                        :logprob lp-str
                        :prob pp-str
                        :top (when top-entries (vec top-entries))}))
                   logprobs)]
    (str "#_(logprobs-table)\n"
         "[" (str/join "\n  " (mapv pr-str rows)) "]\n"
         "\n"
         (str/join ["| Token | LogProb | Prob | Top Alternatives |\n"
                    "|-------|---------|------|------------------|\n"
                    (str/join "\n"
                              (mapv (fn [{:keys [token logprob prob top]}]
                                      (str "|" token " | " logprob " | " prob " | "
                                           (if top (str/join ", " top) "—") " |"))
                                    rows))]))))

(defn- logprobs-table-handler
  [_args api]
  (if-let [{:keys [session-id logprobs]} (get-logprobs)]
    (let [table (format-logprobs-table logprobs)
          pp (calculate-perplexity logprobs)
          tc (count (filter :logprob logprobs))]
      ((:notify api)
       (str "## Logprobs Table\n\n"
            "**Session:** " session-id "\n"
            "**Perplexity:** " (format "%.4f" pp) " | **Tokens:** " tc "\n\n"
            "```edn\n" table "\n```")
       {:role "assistant" :custom-type :logprobs-table}))
    ((:notify api)
     "No logprobs data available."
     {:role "assistant" :custom-type :logprobs-table})))

;; ── Event handler ────────────────────────────────────────────────────────────

(defn- on-turn-finished [payload]
  (when (:session-id payload)
    (store-logprobs! payload))
  nil)

;; ── Init ─────────────────────────────────────────────────────────────────────

(defn init [api]
  ((:on api) "session_turn_finished" on-turn-finished)
  ((:register-operation api)
   {:id "logprobs/perplexity"
    :description "Calculate perplexity of the most recent logprob-bearing reply for a session"
    :handler invoke-perplexity})
  (when-let [register-command (:register-command api)]
    (register-command "logprobs-table"
                      {:description "Pretty-print the most recent logprobs as an EDN table"
                       :handler (fn [args] (logprobs-table-handler args api))}))
  nil)
