(ns extensions.context-manager.friction
  "Post-turn tooling-friction analyzer (task 239): pure prompt-building,
   output-parsing, and design.md-rendering core, plus task-file creation
   and listing helpers, factored out of `extensions.context-manager` to
   keep that namespace within the project's file-length ratchet."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Post-turn tooling-friction analyzer (task 239)
;; ---------------------------------------------------------------------------

(def friction-history-turn-count
  "Number of most-recent *conversational turns* (grouped via
   [[last-n-turns]] on `:role :user` boundaries — not raw messages) fed to
   the friction helper (design.md AC1: 'Analysis input', 'the last 4
   turns'). Public: also used by the ns's real `:fetch-history`
   collaborator (slice 4) to bound the raw message-history query."
  4)

;; friction-task-cap (2) and friction-recent-closed-limit (20) — design.md's
;; decided cap and closed-task dedup-list bound — are introduced in slices
;; 2/3 (task-listing, orchestration) where they are first referenced, to
;; avoid an unused-private-var lint warning here.

;; ---------------------------------------------------------------------------
;; Pure history-excerpt rendering helpers (shared by the ns's
;; render-history-excerpt; factored here to keep extensions.context-manager
;; within the file-length ratchet — round-10 review follow-up).
;; ---------------------------------------------------------------------------

(defn slash-command-only?
  "True when the whole turn's user text is a slash-command invocation
   (trimmed, non-empty, starts with `/`) — same predicate shape as
   `auto-session-name`'s `slash-command-text?`, applied turn-level."
  [text]
  (let [trimmed (some-> text str/trim)]
    (boolean (and (seq trimmed)
                  (str/starts-with? trimmed "/")))))

(defn history-line
  "Render a single history tail entry (237 projection shape:
   `{:role .. :snippet ..}`) into a `Role: text` line, or nil to drop it.

   When the entry carries `:is-error true` (persisted tool-result
   failures set `:role \"toolResult\"` + `:is-error true` out-of-band from
   the content blocks — see `default-fetch-history` / [[message-snippet]]),
   the line is prefixed with an `[error]` marker so the friction helper can
   distinguish a failed tool result from a successful one (design.md names
   'tool errors/retries' as a primary friction target; round-9 follow-up)."
  [entry]
  (let [role (:role entry)
        text (some-> (:snippet entry) str str/trim not-empty)]
    (when (and role text (not (slash-command-only? text)))
      (str (when (:is-error entry) "[error] ")
           (str/capitalize (name role)) ": " (str/replace text #"\s+" " ")))))

(defn tail-lines-within
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

(defn- render-task-list
  "Render `[{:id .. :title ..} ...]` as `NNN-slug: title` lines, one per
   task, for embedding in the dedup section of the helper prompt. Empty
   input renders as `(none)`."
  [tasks]
  (if (seq tasks)
    (str/join "\n" (map (fn [{:keys [id title]}] (str id ": " title)) tasks))
    "(none)"))

(def ^:private friction-detection-instructions
  (str
   "You are looking for developer-experience-for-the-agent friction in the "
   "conversation excerpt below: awkward tool contracts, missing tools, "
   "missing/outdated dependencies, slow or noisy feedback loops, or "
   "discoverability gaps in tooling — friction that could be *fixed by a "
   "change to tooling or dependencies*.\n\n"
   "Exclude: project bugs, feature requests, and user mistakes. Only surface "
   "issues an agent working on this project would want fixed in its own "
   "working environment."))

(def ^:private friction-output-contract
  (str
   "Output contract (strict — one block per issue):\n\n"
   "ISSUE: <slug> | <title>\n"
   "FRICTION: <what friction was observed>\n"
   "EVIDENCE: <which turns / what happened>\n"
   "SUGGESTION: <suggested tooling/dependency change>\n\n"
   "Before emitting an ISSUE block, check it against the existing open and "
   "recently-closed tasks listed below. If it matches an existing task, "
   "instead emit a single line:\n\n"
   "DUPLICATE: <slug> ~ <existing-task-id>\n\n"
   "and emit no ISSUE block for it. If nothing qualifies, output exactly:\n\n"
   "NONE\n\n"
   "Emit only these block/line forms — no preamble, commentary, headings, or "
   "clarification questions. Expect at most a few genuine issues per run."))

(defn build-friction-prompt
  "Compose the friction-analysis helper's system + user prompt from
   `{:history-excerpt :open-tasks :recent-closed-tasks}`, where
   `:open-tasks`/`:recent-closed-tasks` are `[{:id .. :title ..} ...]`.
   Returns `{:system-prompt .. :user-prompt ..}`."
  [{:keys [history-excerpt open-tasks recent-closed-tasks]}]
  {:system-prompt (str/join "\n\n" [friction-detection-instructions
                                    friction-output-contract])
   :user-prompt   (str/join "\n\n"
                            [(str "Conversation history excerpt (last "
                                  friction-history-turn-count " turns):\n\n"
                                  (or history-excerpt "(none)"))
                             (str "Open tasks:\n\n" (render-task-list open-tasks))
                             (str "Recently-closed tasks:\n\n"
                                  (render-task-list recent-closed-tasks))])})

;; --- parsing (pure) --------------------------------------------------------

(def ^:private friction-issue-header-re
  #"^ISSUE:\s*(.+?)\s*\|\s*(.+)$")

(def ^:private friction-duplicate-re
  #"^DUPLICATE:\s*(.+?)\s*~\s*(.+)$")

(defn- friction-field
  [prefix line]
  (when (str/starts-with? line prefix)
    (str/trim (subs line (count prefix)))))

(def ^:private friction-slug-re
  "Plain kebab-case token, matching munera's `slug ∈ kebab_case` convention
   (AGENTS.md). Enforced here — not just at `create-friction-task!`'s I/O
   layer — so path-traversal-shaped model output (e.g. `../../tmp/pwned`)
   never reaches filesystem-path construction at all."
  #"^[a-z0-9]+(-[a-z0-9]+)*$")

(defn- parse-friction-block
  "Parse a single ISSUE-headed block of lines (header + following
   FRICTION/EVIDENCE/SUGGESTION lines) into
   `{:slug :title :friction :evidence :suggestion}`, or nil when any
   required field is missing or `slug` isn't a plain kebab-case token
   (malformed block dropped, fail-safe)."
  [lines]
  (when-let [[_ slug title] (re-matches friction-issue-header-re (first lines))]
    (let [rest-lines (rest lines)
          friction   (some #(friction-field "FRICTION:" %) rest-lines)
          evidence   (some #(friction-field "EVIDENCE:" %) rest-lines)
          suggestion (some #(friction-field "SUGGESTION:" %) rest-lines)]
      (when (and (re-matches friction-slug-re (or slug ""))
                 (seq title) (seq friction) (seq evidence) (seq suggestion))
        {:slug slug :title title :friction friction
         :evidence evidence :suggestion suggestion}))))

(defn parse-friction-output
  "Parse the friction helper's raw text output into
   `{:issues [{:slug :title :friction :evidence :suggestion}] :duplicates
   [{:slug :existing-id}]}`. Malformed ISSUE blocks are dropped
   (fail-safe: no task rather than a garbage task); `nil`/blank/`NONE`
   input yields empty vectors for both."
  [raw]
  (let [lines (str/split-lines (or raw ""))]
    (loop [lines lines issues [] duplicates []]
      (if (empty? lines)
        {:issues issues :duplicates duplicates}
        (let [line (first lines)]
          (cond
            (re-matches friction-issue-header-re line)
            (let [block (cons line (take-while #(not (or (re-matches friction-issue-header-re %)
                                                         (re-matches friction-duplicate-re %)))
                                               (rest lines)))
                  parsed (parse-friction-block block)]
              (recur (drop (count block) lines)
                     (cond-> issues parsed (conj parsed))
                     duplicates))

            (re-matches friction-duplicate-re line)
            (let [[_ slug existing-id] (re-matches friction-duplicate-re line)]
              (recur (rest lines) issues (conj duplicates {:slug slug :existing-id existing-id})))

            :else
            (recur (rest lines) issues duplicates)))))))

(defn cap-issues
  "Split `issues` into the first `cap` (`:selected`) and the remainder
   (`:dropped`), preserving order."
  [issues cap]
  {:selected (vec (take cap issues))
   :dropped  (vec (drop cap issues))})

;; --- rendering (pure) -------------------------------------------------------

(defn render-friction-design-md
  "Render a generated task's `design.md` content for a friction `issue`
   map `{:slug :title :friction :evidence :suggestion}`. Includes an
   auto-generated marker naming this analyzer, the observed friction,
   evidence, and the suggested tooling/dependency change."
  [{:keys [title friction evidence suggestion]}]
  (str "# " title "\n\n"
       "> Auto-generated by the context-manager post-turn "
       "tooling-friction analyzer (task 239). No human review has "
       "occurred yet — verify before acting.\n\n"
       "## Friction\n\n" friction "\n\n"
       "## Evidence\n\n" evidence "\n\n"
       "## Suggested change\n\n" suggestion "\n"))

;; --- task-file creation (side-effecting, injectable via collaborators) ----

(def ^:private task-dir-re
  #"^(\d+)-.+$")

(defn- task-dirs
  "List task directory names (`NNN-slug`) immediately under `dir`
   (a `java.io.File`). Returns `[]` when `dir` doesn't exist."
  [dir]
  (if (.isDirectory dir)
    (->> (.listFiles dir)
         (filter #(.isDirectory ^java.io.File %))
         (map #(.getName ^java.io.File %)))
    []))

(defn allocate-task-id
  "Scan `munera/open/` and `munera/closed/` under `worktree-root` (a path
   string) for existing `NNN-slug` task directories and return the next
   available zero-padded (≥3 digits) NNN as a string. Non-numeric-prefixed
   directory names are ignored. Returns `\"001\"` when no tasks exist."
  [worktree-root]
  (let [root      (io/file worktree-root)
        open-dir  (io/file root "munera" "open")
        closed-dir (io/file root "munera" "closed")
        nums (->> (concat (task-dirs open-dir) (task-dirs closed-dir))
                  (keep #(when-let [[_ n] (re-matches task-dir-re %)]
                           (Long/parseLong n))))
        next-n (inc (if (seq nums) (apply max nums) 0))]
    (format "%03d" next-n)))

(defn next-free-task-id
  "Starting at `start-n`, find the first `NNN-slug` id for which
   `(taken? id)` is false, incrementing NNN on each collision, up to
   `max-retries` attempts total. Returns the id or `nil` once
   `max-retries` collisions have been exhausted. Pure with respect to
   `taken?` — used both by [[create-friction-task!]] (backed by real
   filesystem existence checks) and directly testable with a fake
   `taken?` predicate."
  [start-n slug taken? max-retries]
  (loop [attempts-left max-retries n start-n]
    (if (zero? attempts-left)
      nil
      (let [id (str (format "%03d" n) "-" slug)]
        (if (taken? id)
          (recur (dec attempts-left) (inc n))
          id)))))

(defn create-friction-task!
  "Write a generated friction-analysis task's `design.md` (only) to
   `munera/open/NNN-slug/design.md` under `worktree-root`, where NNN is
   allocated via [[allocate-task-id]] and slug comes from `issue`'s
   `:slug`. On a pre-existing directory collision (e.g. a concurrent
   writer), re-allocates NNN via [[next-free-task-id]] and retries up to
   `max-retries` times, then gives up returning `nil`. Returns the
   created task id (`NNN-slug`) or `nil`."
  ([worktree-root issue] (create-friction-task! worktree-root issue 5))
  ([worktree-root issue max-retries]
   (let [root    (io/file worktree-root)
         slug    (:slug issue)
         start-n (Long/parseLong (allocate-task-id worktree-root))
         taken?  (fn [id] (.exists (io/file root "munera" "open" id)))
         id      (next-free-task-id start-n slug taken? max-retries)]
     (when id
       (let [task-dir (io/file root "munera" "open" id)]
         (.mkdirs task-dir)
         (spit (io/file task-dir "design.md") (render-friction-design-md issue))
         id)))))

(def ^:private friction-recent-closed-limit
  "Number of most-recently-closed tasks (by closure order) passed to the
   helper for dedup matching (design.md: 'Dedup')."
  20)

(defn- task-title
  "Best-effort title for a task directory: first `# ` heading line of its
   design.md, falling back to the directory id itself."
  [task-dir id]
  (let [design-file (io/file task-dir "design.md")]
    (or (when (.exists design-file)
          (some->> (str/split-lines (slurp design-file))
                   (some #(when (str/starts-with? % "# ") (subs % 2)))
                   str/trim
                   not-empty))
        id)))

(defn open-tasks
  "List all open tasks under `worktree-root` as `[{:id .. :title ..} ...]`,
   sorted by id."
  [worktree-root]
  (let [open-dir (io/file (io/file worktree-root) "munera" "open")]
    (->> (task-dirs open-dir)
         sort
         (mapv (fn [id] {:id id :title (task-title (io/file open-dir id) id)})))))

(defn- closed-ids-by-git-order
  "Return closed-task directory names under `worktree-root`, most-recently
   closed first, ordered by git commit order of moves into
   `munera/closed/`. Returns `nil` when the git query fails or yields
   nothing usable (caller falls back to name order)."
  [worktree-root]
  (try
    (let [{:keys [exit out]}
          (shell/sh "git" "log" "--format=%H" "--name-only" "--diff-filter=A"
                    "--" "munera/closed/"
                    :dir worktree-root)]
      (when (zero? exit)
        (let [closed-prefix "munera/closed/"
              ids (->> (str/split-lines out)
                       (keep (fn [line]
                               (when (str/starts-with? line closed-prefix)
                                 (let [rest-path (subs line (count closed-prefix))
                                       id (first (str/split rest-path #"/"))]
                                   (when (re-matches task-dir-re id) id)))))
                       distinct)]
          (when (seq ids) ids))))
    (catch Exception _ nil)))

(defn- recent-closed-ids-git-filtered
  [worktree-root all-ids]
  (when-let [ids (closed-ids-by-git-order worktree-root)]
    (filter all-ids ids)))

(defn recent-closed-tasks
  "List the `N` most-recently-closed tasks under `worktree-root` as
   `[{:id .. :title ..} ...]`, ordered most-recent-first, where `N`
   defaults to [[friction-recent-closed-limit]]. Ordering prefers git
   commit order of moves into `munera/closed/`; falls back to
   name-descending order when git is unavailable/fails."
  ([worktree-root] (recent-closed-tasks worktree-root friction-recent-closed-limit))
  ([worktree-root n]
   (let [closed-dir (io/file (io/file worktree-root) "munera" "closed")
         all-ids    (set (task-dirs closed-dir))
         git-ids    (recent-closed-ids-git-filtered worktree-root all-ids)
         ordered    (or git-ids (sort #(compare %2 %1) all-ids))]
     (->> ordered
          (take n)
          (mapv (fn [id] {:id id :title (task-title (io/file closed-dir id) id)}))))))

;; ---------------------------------------------------------------------------
;; Real collaborator support (task 239, slice 4)
;; ---------------------------------------------------------------------------

(defn message-snippet
  "Extract joined text content from a raw agent-core message's `:content`
   vector — the `:fetch-history` collaborator's own minimal extraction
   (does not depend on `psi.agent-session.message-text`, which is not on
   this extension's classpath). Includes both `{:type :text :text ..}`
   and `{:type :error :text ..}` blocks — the latter is the representation
   used throughout the codebase for provider/tool failures and timeouts
   (e.g. `psi.ai.providers.anthropic.error`, `psi.turn-runtime.core`,
   `psi.agent-session.turn`); design.md names 'tool errors/retries' as a
   primary friction example, so dropping error blocks would hide exactly
   the signal this excerpt is meant to surface."
  [message]
  (->> (:content message)
       (filter #(contains? #{:text :error} (:type %)))
       (map :text)
       (str/join " ")))

(defn- user-turn-boundary?
  "True when `message`'s `:role` is the *user* role. Real agent-core
   messages persist `:role` as the string `\"user\"`/`\"assistant\"` (see
   e.g. `psi.ai.providers.anthropic`, `psi.agent-session.turn`), not a
   keyword — `name` normalizes either representation so this matches both
   the production string shape and (for backwards test-fixture
   compatibility) a `:user` keyword."
  [message]
  (= "user" (some-> (:role message) name)))

(defn group-into-turns
  "Group a flat seq of raw agent-core messages (`{:role .. :content ..}`)
   into per-turn groups, where a new turn begins at each user-role message
   (see [[user-turn-boundary?]]). Any messages preceding the first
   user-role message (unusual, but possible) form their own leading group.
   Returns a vector of message vectors, in original order — used by
   [[last-n-turns]] to bound the friction helper's input to a number of
   *conversational turns* rather than a number of raw messages (a single
   turn can span several raw messages: one user message plus any number of
   assistant/tool-call/tool-result messages before the next user
   message)."
  [messages]
  (reduce
   (fn [turns message]
     (if (or (empty? turns) (user-turn-boundary? message))
       (conj turns [message])
       (conj (pop turns) (conj (peek turns) message))))
   []
   messages))

(defn workflow-step-session?
  "True when `session-name` matches the workflow runtime's dynamic
   step-attempt child-session naming convention `\"workflow <step-id>
   attempt\"` (`statechart_runtime.clj`'s `create-step-attempt-session!`),
   e.g. \"workflow builder attempt\". Used by `known-helper-session?` to
   exclude 'other workflow helper sessions' (design.md, AC5) — these names
   are never in the fixed `known-helper-session-names` literal set, since
   they're parameterized on `step-id`."
  [session-name]
  (boolean
   (and session-name
        (str/starts-with? session-name "workflow ")
        (str/ends-with? session-name " attempt"))))

(defn last-n-turns
  "The raw messages belonging to the last `n` turns of `messages` (see
   [[group-into-turns]]), flattened back into a single ordered seq — bounds
   `default-fetch-history`'s query result to `friction-history-turn-count`
   *turns* (design.md AC1: 'the last 4 turns'), not the last N raw
   messages (a prior gap: `take-last` on the raw tail undercounted turns
   whenever a turn contained more than one message, e.g. any tool-heavy
   turn). `n` nil or non-positive returns all `messages` unchanged."
  [messages n]
  (if (and n (pos? n))
    (->> (group-into-turns messages) (take-last n) (apply concat) vec)
    (vec messages)))

(defn session-info-of
  "Build the `:session-info` collaborator's `{:worktree-root ..
   :session-name ..}` map from an EQL query-session result."
  [eql-result]
  {:worktree-root (:psi.agent-session/worktree-path eql-result)
   :session-name  (:psi.agent-session/session-name eql-result)})

;; ---------------------------------------------------------------------------
;; Orchestration core (task 239, slice 3), decoupled from
;; `extensions.context-manager`'s default-collaborator wiring
;; ---------------------------------------------------------------------------

(defn run-analysis
  "Core friction-analysis cond/orchestration logic, fully decoupled from
   `extensions.context-manager`'s default-collaborator resolution (kept
   there — it needs that ns's other private helpers) so this can live
   outside its file-length ratchet. `opts` is a fully-resolved
   collaborator map:

   `:session-id`, `:log` (fn [msg] → _), `:known-helper-session?`
   (fn [session-info] → bool), `:select-model` (fn [session-id] →
   model-or-nil), `:run-helper` (fn [run-opts] → {:child-session-id :text}
   or nil), `:fetch-history` (fn [session-id] → excerpt-or-nil),
   `:session-info` (fn [session-id] → {:worktree-root .. :session-name ..}
   or nil), `:list-tasks` (fn [worktree-root] → {:open [..]
   :recent-closed [..]}), `:create-task!` (fn [worktree-root issue] →
   task-id-or-nil), `:task-cap` (int).

   Every collaborator call is guarded so no exception escapes (design.md
   AC4: helper failure/missing model/missing worktree never disrupts the
   turn). Returns `{:status :no-op :diagnostic ..}` or on completion
   `{:status :success :created-task-ids [..] :duplicate-diagnostics [..]
   :dropped-count n}`."
  [{:keys [session-id log known-helper-session? select-model run-helper
           fetch-history session-info list-tasks create-task! task-cap]}]
  (try
    (let [info (try (session-info session-id) (catch Throwable _ nil))]
      (cond
        (known-helper-session? info)
        {:status :no-op :diagnostic "known helper/infra session excluded"}

        (str/blank? (:worktree-root info))
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
                (let [{:keys [selected dropped]} (cap-issues issues task-cap)
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
      (try (log (str "context-manager: friction-analysis: error: " (.getMessage e)))
           (catch Exception _ nil))
      {:status :no-op :diagnostic "error"})))

(def friction-history-raw-message-cap
  "Upper bound on the number of most-recent raw messages considered before
   grouping into turns (round-4 implementation-review follow-up):
   `default-fetch-history` previously ran [[group-into-turns]]/
   [[last-n-turns]] — both O(total-messages) — over a session's *entire*
   unbounded message history every single completed turn, merely to keep
   the last `friction-history-turn-count` turns; for a long-running
   session that's O(n) work per turn, O(n²) cumulative over the session's
   life. Generously large relative to `friction-history-turn-count` (4) so
   ordinary turns are never truncated before grouping — only a
   pathological single turn spanning more raw messages than this cap could
   lose leading messages (a size, not a correctness, trade-off; mirrors
   `build-augmentation-history-projection`'s bounded pre-turn `take-last 8`
   precedent in `psi.agent-session.dispatch-effects`)."
  200)

(defn bounded-message-tail
  "The last `cap` raw messages of `messages` (or all of them, if there are
   fewer), as a vector. Uses `subvec` (O(1) on a vector) so bounding the
   tail doesn't itself require scanning the full input — used by
   `default-fetch-history` to keep per-turn grouping work bounded to a
   small constant tail instead of the whole session history. `cap` nil or
   non-positive returns `messages` unchanged."
  [messages cap]
  (let [messages (if (vector? messages) messages (vec messages))
        total    (count messages)]
    (if (and cap (pos? cap) (> total cap))
      (subvec messages (- total cap))
      messages)))
