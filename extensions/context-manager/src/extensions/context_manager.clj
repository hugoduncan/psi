(ns extensions.context-manager
  "Context manager extension scaffold.

   Subscribes to `session_turn_finished` events and registers a minimal
   pre-turn project-context augmenter when the runtime exposes that API.

   Also registers a second, model-backed `entity-resolution` pre-turn
   augmenter (task 238): it runs a bounded, bash-tool-enabled local-model
   helper session that applies the `entity-resolution` skill's method to the
   parent turn, and injects any confidently-resolved `surface → canonical
   (evidence)` mappings as a pre-turn context block."
  (:require
   [clojure.string :as str]
   [psi.ai.model-selection :as model-selection]))

(defn- on-turn-finished
  [log-fn payload]
  (try
    (let [session-id (get payload :session-id "nil")
          turn-id (get payload :turn-id "nil")]
      (log-fn (str "context-manager: session_turn_finished "
                   "session-id=" session-id
                   " turn-id=" turn-id)))
    (catch Exception e
      (try
        (log-fn (str "context-manager: handler error: " (.getMessage e)))
        (catch Exception _ nil))
      nil)))

(defonce helper-session-ids (atom #{}))

(defn- blank? [x]
  (or (nil? x)
      (and (string? x) (str/blank? x))))

(defn project-context-augmentation
  "Return the minimal v1 context-manager turn augmentation envelope."
  [turn-projection]
  (let [session-id (:turn-augmentation/session-id turn-projection)
        cwd        (:turn-augmentation/effective-cwd turn-projection)]
    (cond
      (contains? @helper-session-ids session-id)
      {:turn-augmentation/status :no-op
       :turn-augmentation/operations []
       :turn-augmentation/child-session-ids []}

      (blank? cwd)
      {:turn-augmentation/status :no-op
       :turn-augmentation/operations []
       :turn-augmentation/child-session-ids []
       :turn-augmentation/diagnostic "no effective cwd"}

      :else
      {:turn-augmentation/status :success
       :turn-augmentation/operations
       [{:op :append-context-block
         :id "project-context"
         :title "Project context"
         :content (str "Working directory: " cwd)}]
       :turn-augmentation/child-session-ids []})))

;; ---------------------------------------------------------------------------
;; Entity-resolution augmenter (task 238)
;; ---------------------------------------------------------------------------

(defonce entity-resolution-helper-session-ids (atom #{}))

;; v1 policy knobs (plan.md): finite bounds on the blocking helper run.
(def ^:private max-helper-rounds 8)
(def ^:private helper-wall-clock-ms 120000)
(def ^:private max-history-chars 4000)

(defn- slash-command-only?
  "True when the whole turn's user text is a slash-command invocation
   (trimmed, non-empty, starts with `/`) — same predicate shape as
   `auto-session-name`'s `slash-command-text?`, applied turn-level."
  [text]
  (let [trimmed (some-> text str/trim)]
    (boolean (and (seq trimmed)
                  (str/starts-with? trimmed "/")))))

(defn- no-op-envelope
  ([] {:turn-augmentation/status :no-op
       :turn-augmentation/operations []
       :turn-augmentation/child-session-ids []})
  ([diagnostic]
   (assoc (no-op-envelope) :turn-augmentation/diagnostic diagnostic)))

;; --- prompt construction (pure) -------------------------------------------

(def ^:private entity-resolution-method
  "Embedded `entity-resolution` skill Method (steps 1–5 only; Output Shape
   and step 6 'Act or ask' are deliberately excluded — they conflict with
   this augmenter's non-interactive, parse-only contract). Evidence-gathering
   is adapted to the helper's actual `bash` access."
  (str
   "You are resolving ambiguous or underspecified references in a user's "
   "request into concrete project entities (paths, tasks, workflows, skills, "
   "extensions, namespaces, vars, commands, docs, vocabulary symbols). Use "
   "the following method:\n\n"
   "1. Collect local context. Consider the current user turn and the "
   "conversation-history excerpt supplied below. Use `bash` to inspect the "
   "worktree under the current working directory when path or task references "
   "matter (e.g. `git status`, `git ls-files`, `find`, reading files).\n"
   "2. Identify referring expressions: pronouns/deixis (it, this, that, "
   "these, there, same, former, latter), definite descriptions (the resolver, "
   "the current task), aliases/shorthand, and path-like references.\n"
   "3. Generate candidates. Prefer already-mentioned entities in recency "
   "order. Search authoritative project surfaces with `bash` when not "
   "obvious: `git ls-files`/`find` for paths, `git grep` for terms, vars, "
   "namespaces, workflow ids, commands, and docs. You cannot query the Psi "
   "runtime/session graph — sessions are not a resolvable entity type here; "
   "rely only on evidence you can gather from the worktree with `bash`.\n"
   "4. Score candidates by evidence strength (exact id/path > exact "
   "symbol/name > nearby mention > fuzzy), context fit, recency, and "
   "uniqueness (one strong candidate > many plausible).\n"
   "5. Normalize to canonical project terms: task → munera/open/NNN-slug/ or "
   "munera/closed/NNN-slug/; workflow → .psi/workflows/<id>.edn or registered "
   "id; skill → .psi/skills/<name>/SKILL.md; extension → extension "
   "manifest/path/id; docs → README.md or doc/...; etc.\n\n"
   "Never guess. Only emit confident, evidence-backed mappings; drop "
   "ambiguous or unevidenced references entirely."))

(def ^:private entity-resolution-bash-safety
  (str
   "`bash` is for evidence gathering only. Do not mutate files, install "
   "dependencies, start long-running processes, or perform any unrelated "
   "side effects. Use read-only shell commands to inspect the worktree. Keep "
   "your investigation brief: use at most " max-helper-rounds " rounds of "
   "tool use before responding."))

(def ^:private entity-resolution-output-contract
  (str
   "Output contract: emit one line per confident mapping, in exactly this "
   "format:\n\n"
   "  surface → canonical (evidence; confidence)\n\n"
   "Emit a line only for a mapping you judge confident and evidence-backed. "
   "State your confidence explicitly in the trailing parentheses. Do not emit "
   "preamble, commentary, tables, headings, or clarification questions — only "
   "the mapping lines. If you cannot confidently resolve anything, output "
   "nothing."))

(defn- history-line
  [entry]
  (let [role (or (:role entry) (:psi.session-entry/role entry))
        text (some-> (or (:text entry) (:content entry)) str str/trim not-empty)]
    (when (and role text (not (slash-command-only? text)))
      (str (str/capitalize (name role)) ": " (str/replace text #"\s+" " ")))))

(defn- render-history-excerpt
  "Render a bounded, tail-truncated excerpt of the turn history for anaphora
   resolution. Drops slash-command lines and blank entries."
  [history]
  (let [lines (->> (or history [])
                   (keep history-line)
                   vec)
        text  (str/join "\n" lines)]
    (when (seq text)
      (if (<= (count text) max-history-chars)
        text
        (subs text (- (count text) max-history-chars))))))

(defn build-entity-resolution-prompt
  "Compose the helper system + user prompt from the turn projection.
   Returns {:system-prompt .. :user-prompt ..}."
  [turn-projection]
  (let [user-text (some-> (:turn-augmentation/user-text turn-projection) str str/trim)
        excerpt   (render-history-excerpt (:turn-augmentation/history turn-projection))]
    {:system-prompt (str/join "\n\n" [entity-resolution-method
                                      entity-resolution-bash-safety
                                      entity-resolution-output-contract])
     :user-prompt   (str/join "\n\n"
                              (cond-> []
                                excerpt   (conj (str "Conversation history excerpt:\n\n" excerpt))
                                :always   (conj (str "Current user request:\n\n" (or user-text "")))))}))

;; --- parsing & rendering (pure) -------------------------------------------

(def ^:private mapping-line-re
  ;; surface → canonical (evidence; confidence)
  ;;
  ;; The trailing `(evidence; confidence)` group is anchored to the *last*
  ;; parenthesized group on the line, so a `canonical` containing its own
  ;; parentheses (e.g. `foo/bar (arity 2)`) does not leak into the evidence
  ;; group. Within that final group, `evidence` is everything up to the
  ;; *last* `;`, so evidence text may itself contain `;` (e.g.
  ;; `git grep; 3 hits`) without truncating.
  #"^\s*(.+?)\s*(?:→|->)\s*(.+)\(\s*(.+)\s*;\s*(.+?)\s*\)\s*$")

(defn parse-mapping-lines
  "Parse only well-formed `surface → canonical (evidence; confidence)` lines
   from raw helper text. Every well-formed line is kept (model self-gating —
   no confidence-value threshold). All other text is discarded. Returns a
   vector of {:surface .. :canonical .. :evidence .. :confidence ..}."
  [raw]
  (->> (str/split-lines (or raw ""))
       (keep (fn [line]
               (when-let [[_ surface canonical evidence confidence]
                          (re-matches mapping-line-re line)]
                 {:surface    (str/trim surface)
                  :canonical  (str/trim canonical)
                  :evidence   (str/trim evidence)
                  :confidence (str/trim confidence)})))
       vec))

(defn render-mapping-content
  "Render parsed confident mappings as a compact three-field
   `surface → canonical (evidence)` list (confidence dropped)."
  [mappings]
  (->> mappings
       (map (fn [{:keys [surface canonical evidence]}]
              (str surface " → " canonical " (" evidence ")")))
       (str/join "\n")))

;; --- orchestration --------------------------------------------------------

(defn- helper-model-selection-request
  [model-ctx]
  {:mode                :resolve
   :required            [{:criterion :supports-text :match :true}
                         {:criterion :latency-tier :equals :low}
                         {:criterion :cost-tier :one-of [:zero :low]}]
   :strong-preferences  [{:criterion :locality :equals :local}
                         {:criterion :input-cost :prefer :lower}
                         {:criterion :output-cost :prefer :lower}]
   :weak-preferences    [{:criterion :same-provider-as-session :prefer :context-match}]
   :context             {:session-model {:provider (some-> (:psi.agent-session/model-provider model-ctx)
                                                           keyword)
                                         :id       (:psi.agent-session/model-id model-ctx)}}})

(defn- default-select-model
  "Select the single top-ranked local helper candidate for the parent
   session (no retry across the ranked list). Returns the candidate map or
   nil when no local model is available.

   `:locality :local` is only a strong preference in the selection request
   (it affects ranking, not filtering), so a non-local candidate can survive
   the required constraints and rank first when no local model is
   configured. This augmenter is local-only by acceptance criterion (never
   run a cloud helper on 237's per-turn blocking path), so the top-ranked
   candidate is additionally guarded here: a non-local winner yields nil
   (→ `:no-op`, no cloud helper run)."
  [api parent-session-id]
  (try
    (let [model-ctx (when-let [q (:query-session api)]
                      (q parent-session-id [:psi.agent-session/model-provider
                                            :psi.agent-session/model-id]))
          result    (model-selection/resolve-selection
                     {:request (helper-model-selection-request model-ctx)})]
      (when (= :ok (:outcome result))
        (let [candidate (first (get-in result [:ranking :ranked]))]
          (when (= :local (get-in candidate [:facts :locality]))
            candidate))))
    (catch Exception _ nil)))

(defn- default-run-helper
  "Create a bash-tool-enabled child helper session, run a bounded agent loop
   with the built prompt, and return {:child-session-id id :text raw} (text
   may be nil). The child id is tracked before the run for recursion safety
   and the session is closed/untracked afterward. Returns nil on failure.

   The child's effective cwd comes from parent-worktree inheritance (the
   parent session-id's worktree). `create-child-session` does not accept a
   `:worktree-path` argument, so cwd is *not* passed here — passing it would
   be a silently-ignored dead parameter. The `cwd` from the turn projection
   is the projected effective cwd of that same parent, so inheritance yields
   the intended bash working directory.

   Timeout teardown (turn-3 follow-up): `run-agent-loop-in-session` is a
   blocking dispatch driving a live model/HTTP call, so `future-cancel`
   cannot reliably unwind it (and `future-cancel` also makes a subsequent
   `deref` throw immediately rather than await the still-running thread, so a
   cancel-then-deref watcher cannot observe genuine settlement). We therefore
   make the run future *own its own teardown*: it always closes + untracks
   the child in a `finally`, once the in-flight call actually returns/throws.
   On wall-clock timeout the augmenter thread does NOT close/untrack (that
   would detach a session the orphan may still be prompting, past the budget,
   while narrowing recursion safety) — it just returns promptly with
   `:text nil`, honouring its own blocking budget, and leaves the child
   tracked until the future's own `finally` fires when the orphan settles.
   `future-cancel` is not attempted: it cannot unwind the blocking call and
   only obscures true completion.

   `wall-clock-ms` (optional) overrides the default 120s budget — injectable
   so a test can drive the real timeout branch with a small value."
  [api {:keys [parent-session-id system-prompt user-prompt model wall-clock-ms]}]
  (let [child (try
                ((:mutate-session api) parent-session-id 'psi.extension/create-child-session
                                       {:session-name    "entity-resolution"
                                        :system-prompt   system-prompt
                                        :tool-ids        ["bash"]
                                        :thinking-level  :off
                                        ;; The augmenter's constructed
                                        ;; `system-prompt` is authoritative
                                        ;; (Resolved decision 6 embeds only
                                        ;; Method steps 1–5). Suppress the
                                        ;; default full system-prompt assembly
                                        ;; — AGENTS.md context, skill/extension/
                                        ;; tool prompt fragments — exactly as
                                        ;; the auto-session-name precedent does,
                                        ;; keeping only `bash` in `:tool-names`.
                                        :prompt-component-selection
                                        {:agents-md? false
                                         :extension-prompt-contributions []
                                         :tool-names ["bash"]
                                         :skill-names []
                                         :components #{}}})
                (catch Exception _ nil))
        child-session-id (:psi.agent-session/session-id child)]
    (when child-session-id
      (swap! entity-resolution-helper-session-ids conj child-session-id)
      (let [budget-ms (or wall-clock-ms helper-wall-clock-ms)
            ;; The run future owns teardown: whenever the (uninterruptible)
            ;; blocking call actually returns or throws, it closes + untracks
            ;; the child in `finally`. Both the settled and the timed-out
            ;; paths rely on this single owner, so the child is never closed
            ;; while its in-flight call is still running, and is always
            ;; eventually closed.
            fut (future
                  (try
                    ((:mutate-session api) child-session-id 'psi.extension/run-agent-loop-in-session
                                           (cond-> {:prompt user-prompt}
                                             model (assoc :model model)))
                    (finally
                      (try ((:mutate api) 'psi.extension/close-session
                                          {:session-id child-session-id})
                           (catch Exception _ nil))
                      (swap! entity-resolution-helper-session-ids disj child-session-id))))
            run (try (deref fut budget-ms ::timeout)
                     (catch Exception _ ::error))]
        (if (= ::timeout run)
          ;; Timeout: return promptly to honour the augmenter's blocking
          ;; budget; leave the child tracked (recursion-safe) until the
          ;; future's own `finally` closes/untracks it once the orphan
          ;; settles. No `future-cancel` — it cannot unwind the blocking call.
          {:child-session-id child-session-id :text nil}
          ;; Settled (ok, failed, or exception): the future already
          ;; closed/untracked in its `finally`.
          {:child-session-id child-session-id
           ;; Gate on agent-run-ok?: a failed run returns ok? false with
           ;; agent-run-text = "Error: ...", which must be treated as a
           ;; failed run, not parsed for mapping lines (auto-session-name
           ;; precedent). On failure/exception, surface no text.
           :text (when (and (map? run)
                            (:psi.agent-session/agent-run-ok? run))
                   (:psi.agent-session/agent-run-text run))})))))

(defn entity-resolution-augmentation
  "Entity-resolution turn augmenter (task 238).

   `collaborators` (optional) injects `:select-model` (fn [parent-session-id]
   → model-or-nil) and `:run-helper` (fn [run-opts] → {:child-session-id
   :text} or nil) for tests; defaults call the real model-selection and
   child-session APIs via `api`."
  ([api turn-projection]
   (entity-resolution-augmentation api turn-projection nil))
  ([api turn-projection collaborators]
   (let [session-id (:turn-augmentation/session-id turn-projection)
         cwd        (:turn-augmentation/effective-cwd turn-projection)
         user-text  (:turn-augmentation/user-text turn-projection)
         select-model (or (:select-model collaborators)
                          #(default-select-model api %))
         run-helper   (or (:run-helper collaborators)
                          #(default-run-helper api %))]
     (cond
       (contains? @entity-resolution-helper-session-ids session-id)
       (no-op-envelope)

       (blank? cwd)
       (no-op-envelope "no effective cwd")

       (slash-command-only? user-text)
       (no-op-envelope "slash-command-only prompt")

       :else
       (let [model (select-model session-id)]
         (if (nil? model)
           (no-op-envelope "no local model")
           (let [{:keys [system-prompt user-prompt]}
                 (build-entity-resolution-prompt turn-projection)
                 ;; No :cwd — the helper child inherits the parent
                 ;; session's worktree as its effective cwd (see
                 ;; default-run-helper); create-child-session has no
                 ;; :worktree-path parameter.
                 result (run-helper {:parent-session-id session-id
                                     :system-prompt system-prompt
                                     :user-prompt user-prompt
                                     :model model})
                 mappings (parse-mapping-lines (:text result))
                 child-ids (vec (keep :child-session-id [result]))]
             (if (seq mappings)
               {:turn-augmentation/status :success
                :turn-augmentation/operations
                [{:op :append-context-block
                  :id "entity-resolution"
                  :title "Resolved entities"
                  :content (render-mapping-content mappings)}]
                :turn-augmentation/child-session-ids child-ids}
               (-> (no-op-envelope "no confident mapping")
                   (assoc :turn-augmentation/child-session-ids child-ids))))))))))

(defn- register-turn-augmenter!
  [api]
  (when-let [register (:register-turn-augmenter api)]
    (register {:augmenter-id "project-context"
               :description "Minimal working-directory project context"
               :version "1"
               :handler project-context-augmentation})
    (register {:augmenter-id "entity-resolution"
               :description "Model-backed pre-turn entity resolution"
               :version "1"
               :handler (fn [turn-projection]
                          (entity-resolution-augmentation api turn-projection))})))

(defonce initialized? (atom nil))

(defn init
  "Initialize the context-manager extension.

   Subscribes to `session_turn_finished` events via the extension API.
   Idempotent — repeated calls (e.g. on reload) are no-ops."
  [api]
  (if (and (map? api)
           (:on api)
           (compare-and-set! initialized? nil true))
    (do
      (register-turn-augmenter! api)
      ((:on api) "session_turn_finished"
                 (fn [payload]
                   (when (:log api)
                     (on-turn-finished (:log api) payload))
                   nil))
      true)
    (if (and (map? api) (:on api))
      nil ; already initialized
      (do
        (reset! initialized? nil) ; ensure we don't block future attempts if this one failed
        nil))))
