(ns extensions.logprobs
  "Logprobs out-of-band extension.

   Subscribes to session_turn_finished, stores per-session logprob snapshots,
   and registers the logprobs/perplexity deterministic operation."
  (:require
   [psi.workflow-runtime.turn-execution-contract :as turn-execution]))

;; ── State ────────────────────────────────────────────────────────────────────

(defonce ^:private store
  (atom {}))

(defn- store-logprobs!
  "Store logprobs snapshot for a session. Only replaces when logprobs non-empty."
  [session-id {:keys [logprobs assistant-message turn-id]}]
  (when (seq logprobs)
    (swap! store assoc session-id
           {:logprobs logprobs
            :assistant-message assistant-message
            :turn-id turn-id})))

(defn- get-logprobs
  "Retrieve stored logprob snapshot for a session, or nil."
  [session-id]
  (get @store session-id))

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
  "Build the perplexity result map from stored data for a session."
  [session-id]
  (if-let [{:keys [logprobs assistant-message turn-id]} (get-logprobs session-id)]
    {:perplexity (calculate-perplexity logprobs)
     :token-count (count logprobs)
     :turn-id turn-id
     :reply-text (turn-execution/assistant-message-text assistant-message)}
    {:perplexity nil
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

;; ── Event handler ────────────────────────────────────────────────────────────

(defn- on-turn-finished [payload]
  (when-let [session-id (:session-id payload)]
    (store-logprobs! session-id payload))
  nil)

;; ── Init ─────────────────────────────────────────────────────────────────────

(defn init [api]
  ((:on api) "session_turn_finished" on-turn-finished)
  ((:register-operation api)
   {:id "logprobs/perplexity"
    :description "Calculate perplexity of the most recent logprob-bearing reply for a session"
    :handler invoke-perplexity}))
