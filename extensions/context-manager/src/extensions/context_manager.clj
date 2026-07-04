(ns extensions.context-manager
  "Context manager extension scaffold.

   Subscribes to `session_turn_finished` events; registers a minimal
   pre-turn `project-context` augmenter, and a model-backed
   `entity-resolution` pre-turn augmenter (task 238, bash-tool helper
   session, resolves surfaces to canonical entities). Also runs a
   fire-and-forget post-turn tooling-friction analyzer (task 239, see
   `friction-analysis` and `extensions.context-manager.friction`) that
   analyzes recent turns via a bounded no-tools local-model helper session
   for tooling/dependency friction and opens capped, deduped
   `munera/open/NNN-slug/design.md` tasks. See `doc/extensions.md` for full
   behaviour details."
  (:require
   [clojure.string :as str]
   [extensions.context-manager.friction :as friction]
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
  "Render a single history tail entry (237 projection shape:
   `{:role .. :snippet ..}`) into a `Role: text` line, or nil to drop it."
  [entry]
  (let [role (:role entry)
        text (some-> (:snippet entry) str str/trim not-empty)]
    (when (and role text (not (slash-command-only? text)))
      (str (str/capitalize (name role)) ": " (str/replace text #"\s+" " ")))))

(defn- tail-lines-within
  "Keep the longest tail-suffix of `lines` whose newline-joined length is
   `<= limit`, dropping whole leading lines. Every surviving line stays intact
   with its `Role:` prefix — no mid-line/mid-word cut. If the last (most
   recent) line alone exceeds `limit`, keep it alone (it is the highest-value
   anaphora context) rather than emit nothing."
  [lines limit]
  (loop [kept (list (last lines))
         remaining (butlast lines)]
    (let [candidate (cons (last remaining) kept)]
      (if (or (empty? remaining)
              (> (count (str/join "\n" candidate)) limit))
        kept
        (recur candidate (butlast remaining))))))

(defn- render-history-excerpt
  "Render a bounded, tail-truncated excerpt of the turn history for anaphora
   resolution. Consumes the 237 `:turn-augmentation/history` projection map
   `{:message-count N :tail [{:role .. :snippet ..} ...]}`; iterates `:tail`.
   Drops slash-command lines and blank entries. When the excerpt exceeds
   `char-cap` (default `max-history-chars`), truncates at a *line boundary*
   (drops whole leading lines) so every surviving line keeps its `Role:`
   prefix and is never cut mid-word.

   `turn-count` (optional, default nil = all tail entries) additionally caps
   how many of the most-recent `:tail` entries are considered, before the
   char-cap truncation is applied — used by the friction analyzer to bound
   input to the last N turns."
  ([history] (render-history-excerpt history nil max-history-chars))
  ([history turn-count char-cap]
   (let [tail  (:tail history)
         tail  (if (and turn-count (pos? turn-count))
                 (vec (take-last turn-count tail))
                 tail)
         lines (->> tail
                    (keep history-line)
                    vec)]
     (when (seq lines)
       (let [text (str/join "\n" lines)]
         (if (<= (count text) char-cap)
           text
           (str/join "\n" (tail-lines-within lines char-cap))))))))

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

(def ^:private arrow-re
  ;; `surface → canonical` arrow (unicode or ascii), splitting on the first
  ;; arrow so an evidence/confidence clause containing `->` cannot re-split.
  #"(?:→|->)")

(defn- balanced-parens?
  "True when every `(` in `s` has a matching `)` and vice versa, never
   dipping below zero depth. A genuine entity reference (path, symbol, task
   id) is paren-balanced; incidental echoed code (e.g. `(foo x))`) is not,
   so this rejects code-shaped false-positive mappings without guessing."
  [s]
  (loop [i 0 depth 0]
    (cond
      (= i (count s)) (zero? depth)
      :else
      (let [c (.charAt ^String s i)
            depth (cond (= c \() (inc depth) (= c \)) (dec depth) :else depth)]
        (if (neg? depth) false (recur (inc i) depth))))))

(defn- balanced-trailing-group
  "If `s` ends with a balanced parenthesized group `(...)`, return
   `[prefix inner]` where `prefix` is `s` with that final group and any
   trailing whitespace removed and `inner` is the group's contents (parens
   stripped). Returns nil if `s` does not end in a balanced final group.

   Scans right-to-left counting paren depth so nested parens inside the
   group (e.g. `(baz (qux); high)`) stay wholly inside `inner` and do not
   leak across the evidence boundary, and a trailing group is only accepted
   when it is genuinely balanced (rejecting code-shaped lines like
   `(foo x))`)."
  [s]
  (let [s (str/trimr s)]
    (when (str/ends-with? s ")")
      (loop [i (dec (count s)) depth 0]
        (cond
          (neg? i) nil
          :else
          (let [c (.charAt ^String s i)
                depth (cond (= c \)) (inc depth)
                            (= c \() (dec depth)
                            :else depth)]
            (cond
              (zero? depth) [(str/trimr (subs s 0 i))
                             (subs s (inc i) (dec (count s)))]
              (neg? depth)  nil
              :else         (recur (dec i) depth))))))))

(def ^:private list-marker-re
  ;; Leading markdown/ordered list marker (`- `, `* `, `+ `, `1. `, `2) `)
  ;; plus following whitespace. A local model told to "emit one line per
  ;; confident mapping" routinely emits a markdown list; the marker is list
  ;; formatting, not part of the referring expression, so it is stripped
  ;; before the arrow split to keep `:surface` the bare reference.
  #"^\s*(?:[-*+]|\d+[.)])\s+")

(defn- strip-list-marker
  "Strip a leading ordered/unordered list marker (and following whitespace)
   from `s` so the captured surface is the bare referring expression, not a
   list marker the parent-visible block would otherwise show verbatim."
  [s]
  (str/replace-first s list-marker-re ""))

(defn- parse-mapping-line
  "Parse a single line into a confident mapping map, or nil.

   A line is a mapping only when it (a) contains an arrow, (b) ends in a
   balanced `(evidence; confidence)` group whose inner text contains a `;`,
   and (c) yields a non-empty trimmed surface *and* canonical. A leading list
   marker (`- `, `* `, `+ `, `N. `, `N) `) is stripped first so `:surface` is
   the bare reference, not the list formatting. Evidence is everything up to
   the *last* `;` (so evidence may contain `;`), and both evidence and
   confidence must be non-empty, and both surface and canonical must have
   balanced parentheses. Degenerate lines (empty
   surface/canonical/evidence/confidence, no balanced trailing group, or an
   incidental code-shaped arrow-plus-clause line whose surface/canonical has
   unbalanced parens) are rejected so no guessed/bogus entity is emitted."
  [line]
  (when-let [[prefix inner] (balanced-trailing-group line)]
    (let [semi (str/last-index-of inner ";")]
      (when semi
        (let [evidence   (str/trim (subs inner 0 semi))
              confidence (str/trim (subs inner (inc semi)))
              parts      (str/split (strip-list-marker prefix) arrow-re 2)]
          (when (= 2 (count parts))
            (let [surface   (str/trim (first parts))
                  canonical (str/trim (second parts))]
              (when (and (seq surface) (seq canonical)
                         (seq evidence) (seq confidence)
                         (balanced-parens? surface)
                         (balanced-parens? canonical))
                {:surface    surface
                 :canonical  canonical
                 :evidence   evidence
                 :confidence confidence}))))))))

(defn parse-mapping-lines
  "Parse only well-formed `surface → canonical (evidence; confidence)` lines
   from raw helper text. Every well-formed line is kept (model self-gating —
   no confidence-value threshold). All other text — preamble, malformed
   lines, degenerate/empty mappings, and incidental code-shaped lines — is
   discarded. Returns a vector of
   {:surface .. :canonical .. :evidence .. :confidence ..}."
  [raw]
  (->> (str/split-lines (or raw ""))
       (keep parse-mapping-line)
       vec))

(defn render-mapping-content
  "Render parsed confident mappings as a compact three-field
   `surface → canonical (evidence)` list (confidence dropped)."
  [mappings]
  (->> mappings
       (map (fn [{:keys [surface canonical evidence]}]
              (str surface " → " canonical " (" evidence ")")))
       (str/join "\n")))

;; ---------------------------------------------------------------------------
;; Post-turn tooling-friction analyzer (task 239)
;; ---------------------------------------------------------------------------
;; Pure prompt/parsing/rendering core and task-file creation/listing live in
;; `extensions.context-manager.friction` (kept out of this ns to stay within
;; the file-length ratchet); re-exported here as the ns's public surface.

(def build-friction-prompt friction/build-friction-prompt)
(def parse-friction-output friction/parse-friction-output)
(def cap-issues friction/cap-issues)
(def render-friction-design-md friction/render-friction-design-md)
(def allocate-task-id friction/allocate-task-id)
(def next-free-task-id friction/next-free-task-id)
(def create-friction-task! friction/create-friction-task!)
(def open-tasks friction/open-tasks)
(def recent-closed-tasks friction/recent-closed-tasks)

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
   (→ `:no-op`, no cloud helper run).

   `catalog` (optional) is threaded into `resolve-selection` as its
   injectable candidate pool (defaulting to the live `catalog-view`), so a
   test can pass a nullable candidate pool as a parameter rather than
   `with-redefs`-ing the model-registry infrastructure boundary."
  ([api parent-session-id]
   (default-select-model api parent-session-id nil))
  ([api parent-session-id catalog]
   (try
     (let [model-ctx (when-let [q (:query-session api)]
                       (q parent-session-id [:psi.agent-session/model-provider
                                             :psi.agent-session/model-id]))
           result    (model-selection/resolve-selection
                      (cond-> {:request (helper-model-selection-request model-ctx)}
                        catalog (assoc :catalog catalog)))]
       (when (= :ok (:outcome result))
         (let [candidate (first (get-in result [:ranking :ranked]))]
           (when (= :local (get-in candidate [:facts :locality]))
             candidate))))
     (catch Exception _ nil))))

(defn- bounded-helper-session-run
  "Shared bounded-child-helper-session mechanism underlying both
   `default-run-helper` (entity-resolution, task 238) and
   `default-friction-run-helper` (task 239): create a child helper session,
   run a bounded agent loop with the built prompt, and return
   `{:child-session-id id :text raw}` (text may be nil). The child id is
   tracked (in the caller-supplied `tracking-atom`) before the run for
   recursion safety and is closed/untracked afterward. Returns nil on
   failure.

   `session-config` supplies the two axes the two callers differ on:
   `:session-name` (child session-name), `:tool-ids`/`:tool-names` (tool
   grant + prompt-component tool-name list — `[]` for the friction helper,
   which needs no tools), and `:tracking-atom` (the recursion-guard atom to
   swap the child id into/out of).

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
  [api {:keys [parent-session-id system-prompt user-prompt model wall-clock-ms]}
   {:keys [session-name tool-ids tool-names tracking-atom]}]
  (let [child (try
                ((:mutate-session api) parent-session-id 'psi.extension/create-child-session
                                       {:session-name    session-name
                                        :system-prompt   system-prompt
                                        :tool-ids        tool-ids
                                        :thinking-level  :off
                                        ;; The augmenter's constructed
                                        ;; `system-prompt` is authoritative
                                        ;; (Resolved decision 6 embeds only
                                        ;; Method steps 1–5). Suppress the
                                        ;; default full system-prompt assembly
                                        ;; — AGENTS.md context, skill/extension/
                                        ;; tool prompt fragments — exactly as
                                        ;; the auto-session-name precedent does,
                                        ;; keeping only `tool-names` in the
                                        ;; prompt-component-selection.
                                        :prompt-component-selection
                                        {:agents-md? false
                                         :extension-prompt-contributions []
                                         :tool-names tool-names
                                         :skill-names []
                                         :components #{}}})
                (catch Exception _ nil))
        child-session-id (:psi.agent-session/session-id child)]
    (when child-session-id
      (swap! tracking-atom conj child-session-id)
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
                      (swap! tracking-atom disj child-session-id))))
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

(defn- default-run-helper
  "Create a bash-tool-enabled child helper session, run a bounded agent loop
   with the built prompt, and return {:child-session-id id :text raw} (text
   may be nil). Thin `entity-resolution`-specific wrapper (session-name
   \"entity-resolution\", `bash`-tool grant, `entity-resolution-helper-
   session-ids` tracking) over the shared `bounded-helper-session-run`
   mechanism. See its docstring for the full timeout/teardown behaviour."
  [api opts]
  (bounded-helper-session-run
   api opts
   {:session-name   "entity-resolution"
    :tool-ids       ["bash"]
    :tool-names     ["bash"]
    :tracking-atom  entity-resolution-helper-session-ids}))

;; ---------------------------------------------------------------------------
;; Post-turn tooling-friction analyzer (task 239) — orchestration
;; ---------------------------------------------------------------------------

(defonce friction-helper-session-ids
  ;; Deliberate planning-stage choice (plan.md decision 1): extension-local
  ;; `defonce` atom, not a ctx-keyed managed service (`ramora/META.md`'s
  ;; process-scoped-managed-service model) — the extension API map exposes
  ;; no ctx to key such a service on, and both pre-existing guards
  ;; (`helper-session-ids`, `entity-resolution-helper-session-ids`) already
  ;; use this same pattern. Migrating all three to a ctx-keyed managed
  ;; service is a coherent separate follow-up task, not something to do
  ;; piecemeal here.
  (atom #{}))

(def ^:private known-helper-session-names
  "Session names of other extensions'/runtime's known helper/infra
   sessions (design.md: 'Scope of sessions') — excluded as non-
   representative friction-analysis inputs, in addition to this
   analyzer's own tracked helper sessions and the entity-resolution
   augmenter's tracked helper sessions."
  #{"entity-resolution" "friction-analysis"})

(def ^:private friction-task-cap
  "Maximum number of tasks created per friction-analysis run (design.md:
   Constraints, 'at most 2 tasks created per turn analysis')."
  2)

(defn- known-helper-session?
  "True when `session-id` is one of this analyzer's own tracked helper
   sessions, the entity-resolution augmenter's tracked helper sessions, or
   (via `session-info`, a `{:worktree-root .. :session-name ..}` map or
   nil) identifiable by name as another known helper/infra session."
  [session-id session-info]
  (boolean
   (or (contains? @friction-helper-session-ids session-id)
       (contains? @entity-resolution-helper-session-ids session-id)
       (contains? known-helper-session-names (:session-name session-info)))))

(defn- default-friction-run-helper
  "Run the friction-detection+dedup helper as a bounded, no-tools child
   session (design.md/plan.md: no bash needed — the helper only reasons
   over the excerpt + task list in its prompt). Thin `friction-analysis`-
   specific wrapper (session-name \"friction-analysis\", no tool grant,
   `friction-helper-session-ids` tracking) over the shared
   `bounded-helper-session-run` mechanism. See its docstring for the full
   timeout/teardown behaviour (mirrored here, not duplicated)."
  [api opts]
  (bounded-helper-session-run
   api opts
   {:session-name   "friction-analysis"
    :tool-ids       []
    :tool-names     []
    :tracking-atom  friction-helper-session-ids}))

(defn- default-fetch-history
  "Real `:fetch-history` collaborator: query the session's raw message
   history via EQL and render a bounded, tail-truncated excerpt of the last
   `friction/friction-history-turn-count` turns (design.md: 'Analysis
   input')."
  [api session-id]
  (let [messages (:psi.agent-session/message-history
                  ((:query-session api) session-id [:psi.agent-session/message-history]))
        tail     (mapv (fn [m] {:role (:role m) :snippet (friction/message-snippet m)})
                       messages)]
    (render-history-excerpt {:tail tail} friction/friction-history-turn-count max-history-chars)))

(defn- default-session-info
  "Real `:session-info` collaborator: the analyzed session's own effective
   worktree (design.md: 'Task location' — never walks up to an originating
   session) and session name (for the known-helper-session-name backstop)."
  [api session-id]
  (friction/session-info-of
   ((:query-session api) session-id
                         [:psi.agent-session/worktree-path
                          :psi.agent-session/session-name])))

(defn friction-analysis
  "Post-turn tooling-friction analysis orchestration (task 239).

   `collaborators` (optional) injects `:select-model` (fn [session-id] →
   model-or-nil), `:run-helper` (fn [run-opts] → {:child-session-id :text}
   or nil), `:fetch-history` (fn [session-id] → history-excerpt string or
   nil), `:session-info` (fn [session-id] → {:worktree-root .. :session-name
   ..} or nil), `:list-tasks` (fn [worktree-root] → {:open [..] :recent-closed
   [..]}), and `:create-task!` (fn [worktree-root issue] → task-id or nil).
   Every collaborator call is guarded so no exception escapes to the
   fire-and-forget caller (AC4: helper failure/missing model/missing
   worktree never disrupts the turn).

   Returns a result map for testability: `{:status :no-op :diagnostic ..}`,
   or on completion `{:status :success :created-task-ids [..]
   :duplicate-diagnostics [..] :dropped-count n}`. The event-subscription
   wiring (slice 4) discards this return value — it exists for tests."
  ([api payload] (friction-analysis api payload nil))
  ([api payload collaborators]
   (try
     (let [session-id     (:session-id payload)
           log            (or (:log api) (fn [_]))
           select-model   (or (:select-model collaborators)
                              #(default-select-model api %))
           run-helper     (or (:run-helper collaborators)
                              #(default-friction-run-helper api %))
           fetch-history  (or (:fetch-history collaborators)
                              #(default-fetch-history api %))
           session-info   (or (:session-info collaborators)
                              #(default-session-info api %))
           list-tasks     (or (:list-tasks collaborators)
                              (fn [root] {:open (open-tasks root)
                                          :recent-closed (recent-closed-tasks root)}))
           create-task!   (or (:create-task! collaborators) create-friction-task!)
           info           (try (session-info session-id) (catch Throwable _ nil))]
       (cond
         (known-helper-session? session-id info)
         {:status :no-op :diagnostic "known helper/infra session excluded"}

         (blank? (:worktree-root info))
         (do (log "context-manager: friction-analysis: no worktree, skipping")
             {:status :no-op :diagnostic "no worktree"})

         :else
         (let [model (try (select-model session-id) (catch Throwable _ nil))]
           (if (nil? model)
             (do (log "context-manager: friction-analysis: no local model, skipping")
                 {:status :no-op :diagnostic "no local model"})
             (let [worktree-root (:worktree-root info)
                   history-excerpt (try (fetch-history session-id) (catch Throwable _ nil))
                   {:keys [open recent-closed]} (try (list-tasks worktree-root)
                                                     (catch Throwable _ nil))
                   {:keys [system-prompt user-prompt]}
                   (build-friction-prompt {:history-excerpt history-excerpt
                                           :open-tasks open
                                           :recent-closed-tasks recent-closed})
                   result (try
                            (run-helper {:parent-session-id session-id
                                         :system-prompt system-prompt
                                         :user-prompt user-prompt
                                         :model model})
                            (catch Throwable _ nil))
                   {:keys [issues duplicates]} (parse-friction-output (:text result))]
               (doseq [{:keys [slug existing-id]} duplicates]
                 (log (str "context-manager: friction-analysis: duplicate " slug
                           " ~ " existing-id ", skipped")))
               (if (empty? issues)
                 {:status :success :created-task-ids [] :duplicate-diagnostics duplicates
                  :dropped-count 0}
                 (let [{:keys [selected dropped]} (cap-issues issues friction-task-cap)
                       created (->> selected
                                    (keep (fn [issue]
                                            (try (create-task! worktree-root issue)
                                                 (catch Throwable _ nil))))
                                    vec)]
                   (when (seq dropped)
                     (log (str "context-manager: friction-analysis: "
                               (count dropped) " issue(s) dropped by per-run cap")))
                   {:status :success
                    :created-task-ids created
                    :duplicate-diagnostics duplicates
                    :dropped-count (count dropped)})))))))
     (catch Throwable e
       (try (when (:log api)
              ((:log api) (str "context-manager: friction-analysis: error: "
                               (.getMessage e))))
            (catch Exception _ nil))
       {:status :no-op :diagnostic "error"}))))

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
       ;; A throwing model selection (any failure that escapes the default
       ;; collaborator's own catch, or an injected :select-model that throws)
       ;; collapses to the same well-formed :no-op as no-local-model, mirroring
       ;; the run-helper try/catch below: the augmenter never propagates a
       ;; collaborator exception onto 237's blocking pre-turn path.
       (let [model (try (select-model session-id)
                        (catch Throwable _ nil))]
         (if (nil? model)
           (no-op-envelope "no local model")
           (let [{:keys [system-prompt user-prompt]}
                 (build-entity-resolution-prompt turn-projection)
                 ;; No :cwd — the helper child inherits the parent
                 ;; session's worktree as its effective cwd (see
                 ;; default-run-helper); create-child-session has no
                 ;; :worktree-path parameter.
                 ;; A throwing helper run (any failure that escapes
                 ;; `default-run-helper`'s own catch) collapses to the same
                 ;; well-formed :no-op as a failed/empty run (Required
                 ;; behaviour item 5): the augmenter never propagates a
                 ;; collaborator exception onto 237's blocking pre-turn path.
                 result (try
                          (run-helper {:parent-session-id session-id
                                       :system-prompt system-prompt
                                       :user-prompt user-prompt
                                       :model model})
                          (catch Throwable _ nil))
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
                   ;; Fire-and-forget (task 239): the friction analysis runs
                   ;; on its own thread and never blocks/delays the turn
                   ;; pipeline; `friction-analysis` itself never throws
                   ;; (belt-and-braces outer catch), but the future body adds
                   ;; its own catch-all as a last line of defence so a future
                   ;; exception can never surface anywhere the turn path
                   ;; would observe it.
                   (future
                     (try
                       (friction-analysis api payload)
                       (catch Throwable e
                         (try (when (:log api)
                                ((:log api) (str "context-manager: friction-analysis: "
                                                 "uncaught error: " (.getMessage e))))
                              (catch Exception _ nil)))))
                   nil))
      true)
    (if (and (map? api) (:on api))
      nil ; already initialized
      (do
        (reset! initialized? nil) ; ensure we don't block future attempts if this one failed
        nil))))
