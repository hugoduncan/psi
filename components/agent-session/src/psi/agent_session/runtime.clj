(ns psi.agent-session.runtime
  "Shared runtime helpers for prompt execution across CLI, TUI, and RPC.

   Provides one path for:
   - memory recover/remember hooks
   - user message journaling
   - API key resolution"
  (:require
   [clojure.string :as str]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.provider-auth :as provider-auth]
   [psi.recursion.core :as recursion]
   [taoensso.timbre :as timbre]))

(defn make-user-message
  "Create a canonical user message map from text and optional image blocks."
  ([text]
   (make-user-message text nil))
  ([text images]
   {:role      "user"
    :content   (cond-> [{:type :text :text text}]
                 (seq images) (into images))
    :timestamp (java.time.Instant/now)}))

(defn- require-session-id!
  [session-id]
  (when-not (and (string? session-id) (not (str/blank? session-id)))
    (throw (ex-info "missing or invalid session-id"
                    {:error-code "request/invalid-session-id"
                     :session-id session-id})))
  session-id)

(defn journal-user-message-in!
  "Append a raw user message to the session journal and return it.
   Use for command traces that should not trigger expansion/memory hooks."
  [ctx session-id text images]
  (require-session-id! session-id)
  (let [user-msg (make-user-message text images)]
    (ss/journal-append-in! ctx session-id (persist/message-entry user-msg))
    user-msg))

(defn resolve-api-key-in
  "Resolve API key for the selected model provider.
   Shared provider-auth resolution keeps runtime-facing helper paths aligned
   with canonical request preparation. `session-id` is accepted for call-path
   symmetry; explicit per-call overrides still belong in runtime-opts passed to
   prompt preparation."
  [ctx _session-id ai-model]
  (provider-auth/provider-api-key ctx (:provider ai-model)))

(defn- sync->recursion-trigger-type
  [sync]
  (if (true? (get-in sync [:capture :changed?]))
    :graph-changed
    :memory-updated))

(defn- sync->recursion-system-state
  [sync]
  (let [activation    (:activation sync)
        graph-status  (get-in sync [:capability-graph :status])
        query-ready?  (true? (:query-env-built? activation))
        graph-ready?  (contains? #{:stable :expanding} graph-status)
        memory-ready? (= :ready (:memory-status activation))]
    {:query-ready query-ready?
     :graph-ready graph-ready?
     :introspection-ready query-ready?
     :memory-ready memory-ready?}))

(defn- sync->recursion-graph-state
  [sync]
  (let [cg (:capability-graph sync)]
    {:node-count (or (:node-count cg) 0)
     :capability-count (count (or (:capability-ids cg) []))
     :status (or (:status cg) :emerging)}))

(defn- sync->recursion-memory-state
  [sync]
  (let [activation (:activation sync)
        history-sync (:history-sync sync)]
    {:entry-count (or (:memory-entry-count history-sync) 0)
     :status (if (= :ready (:memory-status activation)) :ready :initializing)
     :recovery-count 0}))

(defn- maybe-trigger-recursion-from-git-sync!
  [ctx git-sync]
  (let [recursion-ctx (:recursion-ctx ctx)
        sync          (:sync git-sync)]
    (when (and recursion-ctx (:changed? git-sync) (map? sync))
      (let [trigger-type  (sync->recursion-trigger-type sync)
            trigger-signal {:type trigger-type
                            :reason "git-head-changed"
                            :payload {:source :git-head-change-hook
                                      :head (:head git-sync)
                                      :previous-head (:previous-head git-sync)
                                      :graph-changed? (true? (get-in sync [:capture :changed?]))
                                      :history-imported-count (or (get-in sync [:history-sync :imported-count]) 0)}
                            :timestamp (java.time.Instant/now)}]
        (recursion/orchestrate-manual-trigger-in!
         recursion-ctx
         trigger-signal
         {:system-state (sync->recursion-system-state sync)
          :graph-state (sync->recursion-graph-state sync)
          :memory-state (sync->recursion-memory-state sync)
          :memory-ctx (:memory-ctx ctx)
          :approval-decision :approve
          :approver "git-head-change-hook"
          :approval-notes "auto-approved from git head change hook"})))))

(defn invoke-git-head-sync!
  [opts]
  (let [sync-fn (requiring-resolve 'psi.memory.runtime/maybe-sync-on-git-head-change!)]
    (sync-fn opts)))

(defn- maybe-dispatch-git-head-changed-event!
  [ctx session-id git-sync]
  (let [classification (:classification git-sync)]
    (when (and (true? (:changed? git-sync))
               (string? (:head git-sync))
               (not (str/blank? (:head git-sync)))
               (true? (:notify-extensions? classification)))
      (let [payload {:session-id session-id
                     :workspace-dir (ss/session-worktree-path-in ctx session-id)
                     :cwd (ss/session-worktree-path-in ctx session-id)
                     :head (:head git-sync)
                     :previous-head (:previous-head git-sync)
                     :reason "head-changed"
                     :classification classification
                     :timestamp (java.time.Instant/now)}]
        (ext/dispatch-in (:extension-registry ctx)
                         "git_commit_created"
                         payload)))))

(defn safe-maybe-sync-on-git-head-change!
  [ctx session-id]
  (try
    (let [git-sync (invoke-git-head-sync!
                    {:cwd (ss/session-worktree-path-in ctx session-id)
                     :memory-ctx (:memory-ctx ctx)})]
      (try
        (maybe-trigger-recursion-from-git-sync! ctx git-sync)
        (catch Exception _
          nil))
      (try
        (maybe-dispatch-git-head-changed-event! ctx session-id git-sync)
        (catch Exception _
          nil))
      git-sync)
    (catch Exception _
      nil)))

(defn- submit-prompt-turn-in!
  "Dispatch the shared prompt lifecycle for a raw user message."
  [ctx session-id text images {:keys [progress-queue runtime-opts sync-on-git-head-change?]}]
  (when-not (ss/idle-in? ctx session-id)
    (throw (ex-info "Session is not idle" {:phase (ss/sc-phase-in ctx session-id)})))
  (let [user-msg {:role      "user"
                  :content   (cond-> [{:type :text :text text}]
                               images (into images))
                  :timestamp (java.time.Instant/now)}
        submit-r (dispatch/dispatch! ctx :session/prompt-submit
                                     {:session-id session-id :user-msg user-msg}
                                     {:origin :core})
        turn-id  (:turn-id submit-r)
        _        (dispatch/dispatch! ctx :session/prompt {:session-id session-id} {:origin :core})
        result   (dispatch/dispatch! ctx :session/prompt-prepare-request
                                     (cond-> {:session-id session-id
                                              :turn-id    turn-id
                                              :user-msg   user-msg}
                                       progress-queue
                                       (assoc :progress-queue progress-queue)
                                       runtime-opts
                                       (assoc :runtime-opts runtime-opts))
                                     {:origin :core})]
    (when sync-on-git-head-change?
      (safe-maybe-sync-on-git-head-change! ctx session-id))
    result))

(defn register-extension-run-fn-in!
  "Register a background prompt-lifecycle runner for extension-initiated prompts.

   After this is called, `send-extension-prompt-in!` can always invoke the
   runner in a background thread.

   The runner fn is (fn [text source]) — it waits until the session is idle,
   resolves runtime opts, and routes the text through the dispatch-visible
   prompt lifecycle.

   Call this once after bootstrap, passing the live ai-ctx and ai-model."
  ([ctx session-id _ai-ctx ai-model]
   (let [run-fn (fn [text _source]
                  (try
                    (loop [attempt 0]
                      (if (ss/idle-in? ctx session-id)
                        (let [api-key  (resolve-api-key-in ctx session-id ai-model)]
                          (dispatch/dispatch! ctx :session/set-model
                                              {:session-id session-id
                                               :model {:provider  (some-> (:provider ai-model) name)
                                                       :id        (:id ai-model)
                                                       :reasoning (boolean (:supports-reasoning ai-model))}
                                               :scope :session}
                                              {:origin :core})
                          (submit-prompt-turn-in! ctx session-id text nil
                                                  {:runtime-opts (cond-> {}
                                                                   api-key (assoc :api-key api-key))
                                                   :sync-on-git-head-change? true}))
                        (when (< attempt 1200) ;; ~5m max wait (250ms backoff)
                          (Thread/sleep 250)
                          (recur (inc attempt)))))
                    (catch Exception e
                      (timbre/warn e "Extension run-fn failed"))))]
     (ext-rt/set-extension-run-fn-in! ctx session-id run-fn))))


