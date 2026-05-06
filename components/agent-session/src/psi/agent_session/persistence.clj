(ns psi.agent-session.persistence
  "Session entry journal — append-only in-memory log with disk write.

   Format: NDEDN — one EDN map per line (newline-delimited EDN).

   In-memory journal
   ─────────────────
   The canonical journal lives in ctx `:state*` at the per-session path
   [:agent-session :sessions sid :persistence :journal].
   Low-level atom-oriented helpers remain for focused persistence tests.
   Runtime code should prefer the ctx-based `*-in` functions.

   Disk write (lazy flush)
   ───────────────────────
   Entries are held in memory until the first assistant message arrives.
   At that point the full journal (header + all entries) is written at
   once.  Subsequent entries are appended one line at a time.

   Cross-process safety
   ────────────────────
   Every write path acquires an exclusive sidecar file lock
   (<session-file>.lock) before mutating the session file.

   The flush state is tracked in a separate atom returned by
   `create-flush-state`.  Both atoms live in the session context.

   Session directory layout
   ────────────────────────
   ~/.psi/agent/sessions/--<encoded-cwd>--/<timestamp>_<uuid>.ndedn

   cwd encoding: strip leading slash, replace / and : with -.

   Migration
   ─────────
   v1 — no :version, no :id/:parent-id on entries
        → add linear :id/:parent-id chain
   v2 — :version 2, entries have :id/:parent-id
        → rename :hook-message role to :custom in message entries
   v3 — header has :parent-session path hint only
        → add :parent-session-id in header
   v4 — current

   Public API
   ──────────
   Journal (in-memory):
     create-journal, append-entry!, all-entries, entries-of-kind,
     entries-up-to, last-entry-of-kind, messages-from-entries,
     messages-up-to

   Flush state:
     create-flush-state

   Disk write:
     session-dir-for, new-session-file-path,
     write-header!, append-entry-to-disk!, flush-journal!

   Disk read:
     load-session-file, find-most-recent-session,
     list-sessions, list-all-sessions

   Entry constructors:
     message-entry, thinking-level-entry, model-entry, compaction-entry,
     branch-summary-entry, custom-message-entry, label-entry,
     session-info-entry"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.session-state.model :as session-model]
   [psi.session-state.state :as session-state])
  (:import
   (java.io File RandomAccessFile)
   (java.nio.channels FileLock OverlappingFileLockException)
   (java.nio.file Files OpenOption Path StandardOpenOption)
   (java.time Instant ZoneOffset)
   (java.time.format DateTimeFormatter)))

;;; ============================================================
;;; Constants
;;; ============================================================

(def ^:private current-version 4)

(def ^:private sessions-root
  (delay (io/file (System/getProperty "user.home") ".psi" "agent" "sessions")))

(def ^:dynamic *session-file-lock-retry-ms* 25)
(def ^:dynamic *session-file-lock-max-attempts* 400)

(defn- journal-path
  "Build the per-session journal path for `session-id` in ctx."
  [_ctx session-id]
  (session-state/state-path :journal session-id))

(defn- flush-state-path
  "Build the per-session flush-state path for `session-id` in ctx."
  [_ctx session-id]
  (session-state/state-path :flush-state session-id))

(defn- state*
  [ctx]
  (:state* ctx))

(defn- get-state-in
  [ctx path]
  (get-in @(state* ctx) path))

(defn- assoc-state-in!
  [ctx path value]
  (swap! (state* ctx) assoc-in path value))

;;; ============================================================
;;; Journal operations (in-memory atom)
;;; ============================================================

(defn create-journal
  "Create a fresh journal atom (vector of SessionEntry maps)."
  []
  (atom []))

(defn append-entry!
  "Append `entry` to `journal-atom` atomically. Returns `entry`."
  [journal-atom entry]
  (swap! journal-atom conj entry)
  entry)

(defn all-entries
  "Return all entries from `journal-atom` as a vector."
  [journal-atom]
  @journal-atom)

(defn entries-of-kind
  "Return all entries of `kind` keyword from `journal-atom`."
  [journal-atom kind]
  (filterv #(= (:kind %) kind) @journal-atom))

(defn entries-up-to
  "Return all entries up to and including the entry with `entry-id`.
  Returns the full journal if `entry-id` is nil or not found."
  [journal-atom entry-id]
  (if (nil? entry-id)
    @journal-atom
    (let [entries @journal-atom
          idx     (first (keep-indexed #(when (= (:id %2) entry-id) %1) entries))]
      (if idx
        (subvec entries 0 (inc idx))
        entries))))

(defn last-entry-of-kind
  "Return the most recent entry of `kind` from `journal-atom`, or nil."
  [journal-atom kind]
  (last (entries-of-kind journal-atom kind)))

(defn messages-from-entries
  "Extract agent message maps from all :message entries in the journal."
  [journal-atom]
  (keep (fn [entry]
          (when (= (:kind entry) :message)
            (get-in entry [:data :message])))
        @journal-atom))

(defn messages-up-to
  "Extract agent messages from journal entries up to `entry-id`."
  [journal-atom entry-id]
  (keep (fn [entry]
          (when (= (:kind entry) :message)
            (get-in entry [:data :message])))
        (entries-up-to journal-atom entry-id)))

(declare persist-entry-in!)

(defn append-entry-in!
  "Append `entry` to the canonical journal for `session-id` in ctx and preserve
   existing persistence semantics. Returns `entry`."
  [ctx session-id entry]
  (session-state/append-journal-entry-in! ctx session-id entry)
  (let [sd (session-state/get-session-data-in ctx session-id)]
    (persist-entry-in! ctx
                       session-id
                       (:worktree-path sd)
                       (:parent-session-id sd)
                       (:parent-session-path sd)))
  entry)

(defn- entry-coll
  [x]
  (cond
    (vector? x)     x
    (sequential? x) x
    :else           []))

(defn all-entries-in
  "Return all canonical journal entries for `session-id` from ctx as a vector."
  [ctx session-id]
  (-> (get-state-in ctx (journal-path ctx session-id))
      entry-coll
      vec))

(defn entries-of-kind-in
  "Return canonical journal entries of `kind` for `session-id` from ctx."
  [ctx session-id kind]
  (filterv #(= (:kind %) kind) (all-entries-in ctx session-id)))

(defn entries-up-to-in
  "Return canonical journal entries for `session-id` up to and including `entry-id`.
   Returns full journal if `entry-id` is nil or not found."
  [ctx session-id entry-id]
  (let [entries (all-entries-in ctx session-id)]
    (if (nil? entry-id)
      entries
      (let [idx (first (keep-indexed #(when (= (:id %2) entry-id) %1) entries))]
        (if idx
          (subvec entries 0 (inc idx))
          entries)))))

(defn last-entry-of-kind-in
  "Return the most recent canonical journal entry of `kind` for `session-id`, or nil."
  [ctx session-id kind]
  (last (entries-of-kind-in ctx session-id kind)))

(defn messages-from-entries-in
  "Extract agent messages from canonical journal message entries for `session-id` in ctx."
  [ctx session-id]
  (keep (fn [entry]
          (when (= (:kind entry) :message)
            (get-in entry [:data :message])))
        (all-entries-in ctx session-id)))

(defn messages-up-to-in
  "Extract agent messages from canonical journal entries for `session-id` up to `entry-id`."
  [ctx session-id entry-id]
  (keep (fn [entry]
          (when (= (:kind entry) :message)
            (get-in entry [:data :message])))
        (entries-up-to-in ctx session-id entry-id)))

;;; ============================================================
;;; Flush state
;;; ============================================================

(defn create-flush-state
  "Create a flush-state atom.
  {:flushed? false :session-file nil}
  :flushed? — true once the initial bulk write has happened
  :session-file — java.io.File for the session file, or nil"
  []
  (atom {:flushed? false :session-file nil}))

(declare append-entry-to-disk!)
(declare flush-journal!)

(defn persist-state-entry!
  "Persist journal `entries` using plain flush-state data.
   Calls `save-flush-state!` with the updated flush-state when it changes."
  ([entries flush-state session-id cwd parent-session-path save-flush-state!]
   (persist-state-entry! entries flush-state session-id cwd nil parent-session-path save-flush-state!))
  ([entries flush-state session-id cwd parent-session-id parent-session-path save-flush-state!]
   (let [{:keys [flushed? session-file]} flush-state]
     (when session-file
       (let [has-assistant (some (fn [e]
                                   (and (= :message (:kind e))
                                        (= "assistant" (get-in e [:data :message :role]))))
                                 entries)]
         (when has-assistant
           (if flushed?
             (append-entry-to-disk! session-file (last entries))
             (do
               (flush-journal! session-file session-id cwd parent-session-id parent-session-path entries)
               (when save-flush-state!
                 (save-flush-state! (assoc flush-state :flushed? true)))))))))))

;;; ============================================================
;;; NDEDN serialisation
;;; ============================================================

(defn- instant->date
  "Recursively convert any java.time.Instant values in `x` to java.util.Date
  so that pr-str produces #inst literals readable by clojure.edn/read-string."
  [x]
  (cond
    (instance? Instant x)    (java.util.Date/from x)
    (map? x)                 (reduce-kv (fn [m k v] (assoc m k (instant->date v))) {} x)
    (sequential? x)          (mapv instant->date x)
    :else                    x))

(defn- entry->line
  "Serialise a single map to a single EDN line (no newlines inside).
  Converts java.time.Instant to #inst so edn/read-string can round-trip."
  [m]
  (pr-str (instant->date m)))

(defn- parse-line
  "Parse a single NDEDN line. Returns nil on blank, parse error, or non-map."
  [line]
  (let [trimmed (str/trim line)]
    (when-not (str/blank? trimmed)
      (try
        (let [v (edn/read-string
                 {:readers {'inst #(java.util.Date/from (Instant/parse %))}}
                 trimmed)]
          (when (map? v) v))
        (catch Exception _
          nil)))))

;;; ============================================================
;;; Session directory and file naming
;;; ============================================================

(defn session-dir-for
  "Return the session directory (java.io.File) for `cwd`.
  Creates the directory if it does not exist."
  [cwd]
  (let [encoded (-> (str cwd)
                    (str/replace #"^[/\\]" "")
                    (str/replace #"[/\\:]" "-"))
        dir     (io/file @sessions-root (str "--" encoded "--"))]
    (.mkdirs dir)
    dir))

(defn- timestamp-prefix
  "Return a filesystem-safe timestamp string for now."
  []
  (let [fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm-ss-SSS")]
    (.format fmt (java.time.LocalDateTime/now ZoneOffset/UTC))))

(defn new-session-file-path
  "Return a new session file (java.io.File) under `session-dir`."
  [session-dir session-id]
  (io/file session-dir (str (timestamp-prefix) "_" session-id ".ndedn")))

;;; ============================================================
;;; Session file header
;;; ============================================================

(defn- make-header
  [session-id worktree-path parent-session-id parent-session-path]
  {:type              :session
   :version           current-version
   :id                session-id
   :timestamp         (Instant/now)
   :worktree-path     (str worktree-path)
   :parent-session-id (when parent-session-id (str parent-session-id))
   :parent-session    (when parent-session-path (str parent-session-path))})

;;; ============================================================
;;; Disk write
;;; ============================================================

(defn- with-session-file-lock
  "Execute `f` while holding an exclusive file lock for `file`.

  Cross-process safety:
  - uses a dedicated sidecar lock file: <session-file>.lock
  - retries lock acquisition for a bounded interval
  - throws ex-info if lock cannot be acquired

  Returns the result of `f`."
  [^File file f]
  (let [lock-file (io/file (str (.getAbsolutePath file) ".lock"))
        _         (when-let [parent (.getParentFile lock-file)]
                    (when-not (.exists parent) (.mkdirs parent)))
        raf       (RandomAccessFile. lock-file "rw")]
    (try
      (let [channel (.getChannel raf)
            lock    (loop [attempt 0]
                      (when (< attempt *session-file-lock-max-attempts*)
                        (or (try
                              (.tryLock channel)
                              (catch OverlappingFileLockException _
                                nil))
                            (do
                              (Thread/sleep *session-file-lock-retry-ms*)
                              (recur (inc attempt))))))]
        (when-not lock
          (throw (ex-info "Failed to acquire session file lock"
                          {:lock-path (.getAbsolutePath lock-file)
                           :session-file (.getAbsolutePath file)
                           :max-attempts *session-file-lock-max-attempts*
                           :retry-ms *session-file-lock-retry-ms*})))
        (try
          (f)
          (finally
            (.release ^FileLock lock))))
      (finally
        (.close raf)))))

(defn- append-line!
  "Append `line` + newline to `file` atomically via NIO under an exclusive lock."
  [^File file line]
  (with-session-file-lock
    file
    (fn []
      (let [^Path path (.toPath file)
            ^bytes bytes (.getBytes (str line "\n") "UTF-8")
            ^"[Ljava.nio.file.OpenOption;" opts
            (into-array OpenOption [StandardOpenOption/CREATE
                                    StandardOpenOption/APPEND])]
        (Files/write path bytes opts)))))

(defn write-header!
  "Write the session header as the first line of `file`.
  Overwrites any existing content. Uses exclusive file lock."
  ([^File file session-id cwd parent-session-path]
   (write-header! file session-id cwd nil parent-session-path))
  ([^File file session-id cwd parent-session-id parent-session-path]
   (with-session-file-lock
     file
     (fn []
       (let [header (make-header session-id cwd parent-session-id parent-session-path)
             ^Path path (.toPath file)
             ^bytes bytes (.getBytes (str (entry->line header) "\n") "UTF-8")
             ^"[Ljava.nio.file.OpenOption;" opts
             (into-array OpenOption [StandardOpenOption/CREATE
                                     StandardOpenOption/TRUNCATE_EXISTING
                                     StandardOpenOption/WRITE])]
         (Files/write path bytes opts))))))

(defn append-entry-to-disk!
  "Append a single `entry` line to `file` (under lock)."
  [^File file entry]
  (append-line! file (entry->line entry)))

(defn flush-journal!
  "Write the header + all `entries` to `file` in one operation.
  Overwrites any existing content. Uses exclusive file lock."
  ([^File file session-id cwd parent-session-path entries]
   (flush-journal! file session-id cwd nil parent-session-path entries))
  ([^File file session-id cwd parent-session-id parent-session-path entries]
   (with-session-file-lock
     file
     (fn []
       (let [header (make-header session-id cwd parent-session-id parent-session-path)
             lines  (str/join "\n"
                              (cons (entry->line header)
                                    (map entry->line entries)))
             ^Path path (.toPath file)
             ^bytes bytes (.getBytes (str lines "\n") "UTF-8")
             ^"[Ljava.nio.file.OpenOption;" opts
             (into-array OpenOption [StandardOpenOption/CREATE
                                     StandardOpenOption/TRUNCATE_EXISTING
                                     StandardOpenOption/WRITE])]
         (Files/write path bytes opts))))))

;;; ============================================================
;;; Persist-on-append (called by core.clj after append-entry!)
;;; ============================================================

(defn persist-entry!
  "Conditionally write `entry` to disk.

  Rules:
    - If :session-file is nil → no-op (in-memory only mode).
    - If the journal has no assistant message yet → no-op (lazy flush).
    - If not yet :flushed? → bulk-write header + all entries, set :flushed? true.
    - Otherwise → append single entry line.

  `journal-atom`   — the session journal atom
  `flush-state-atom` — atom from create-flush-state
  `session-id`     — string
  `cwd`            — string
  `parent-session-id` — string or nil
  `parent-session-path` — string or nil"
  ([journal-atom flush-state-atom session-id cwd parent-session-path]
   (persist-entry! journal-atom flush-state-atom session-id cwd nil parent-session-path))
  ([journal-atom flush-state-atom session-id cwd parent-session-id parent-session-path]
   (let [{:keys [flushed? session-file]} @flush-state-atom]
     (when session-file
       (let [entries       @journal-atom
             has-assistant (some (fn [e]
                                   (and (= :message (:kind e))
                                        (= "assistant" (get-in e [:data :message :role]))))
                                 entries)]
         (when has-assistant
           (if flushed?
             (append-entry-to-disk! session-file (last entries))
             (do
               (flush-journal! session-file session-id cwd parent-session-id parent-session-path entries)
               (swap! flush-state-atom assoc :flushed? true)))))))))

(defn persist-entry-in!
  "Ctx-based runtime persistence helper.

   Reads journal + flush-state from canonical ctx state and writes any flush-state
   transition back to canonical state."
  ([ctx session-id cwd parent-session-path]
   (persist-entry-in! ctx session-id cwd nil parent-session-path))
  ([ctx session-id cwd parent-session-id parent-session-path]
   (let [fp           (flush-state-path ctx session-id)
         flush-state  (get-state-in ctx fp)
         session-file (:session-file flush-state)
         entries      (all-entries-in ctx session-id)]
     (when session-file
       (let [has-assistant (some (fn [e]
                                   (and (= :message (:kind e))
                                        (= "assistant" (get-in e [:data :message :role]))))
                                 entries)]
         (when has-assistant
           (if (:flushed? flush-state)
             (append-entry-to-disk! session-file (last entries))
             (do
               (flush-journal! session-file session-id cwd parent-session-id parent-session-path entries)
               (assoc-state-in! ctx fp (assoc flush-state :flushed? true))))))))))

;;; ============================================================
;;; Migration
;;; ============================================================

(defn- short-id
  "Generate a short random hex ID."
  []
  (str (java.util.UUID/randomUUID)))

(defn- migrate-v1->v2
  "Add :id and :parent-id to entries that lack them (v1 format).
  Returns updated entries vector."
  [entries]
  (let [indexed (map-indexed vector entries)]
    (reduce
     (fn [{:keys [result prev-id]} [_i entry]]
       (if (:id entry)
         ;; already has id — leave alone
         {:result (conj result entry) :prev-id (:id entry)}
         (let [new-id (short-id)
               entry' (assoc entry :id new-id :parent-id prev-id)]
           {:result (conj result entry') :prev-id new-id})))
     {:result [] :prev-id nil}
     indexed)
    (:result
     (reduce
      (fn [{:keys [result prev-id]} [_i entry]]
        (if (:id entry)
          {:result (conj result entry) :prev-id (:id entry)}
          (let [new-id (short-id)
                entry' (assoc entry :id new-id :parent-id prev-id)]
            {:result (conj result entry') :prev-id new-id})))
      {:result [] :prev-id nil}
      indexed))))

(defn- migrate-v2->v3
  "Rename :hook-message role to :custom in message entries."
  [entries]
  (mapv (fn [entry]
          (if (and (= :message (:kind entry))
                   (= "hook-message" (get-in entry [:data :message :role])))
            (assoc-in entry [:data :message :role] "custom")
            entry))
        entries))

(defn- parent-id-from-path
  "Best-effort parent session id derivation from parent session path.
   Expects filenames like <timestamp>_<id>.ndedn. Returns nil on mismatch."
  [parent-path]
  (when (string? parent-path)
    (some->> (re-matches #".*_([^/_\\]+)\\.ndedn$" parent-path)
             second)))

(defn- migrate-v3->v4-header
  "Ensure header has :parent-session-id (derive from :parent-session path when possible)."
  [header]
  (let [existing (:parent-session-id header)
        derived  (or existing (parent-id-from-path (:parent-session header)))]
    (assoc header :parent-session-id derived)))

(defn- migrate-entries
  "Apply all necessary migrations to bring `entries` to current-version.
  `header` is the parsed header map (mutated version field is ignored here;
  we derive version from the header passed in).
  Returns {:header header' :entries entries'}."
  [header entries]
  (let [version (or (:version header) 1)]
    (cond-> {:header (assoc header :version current-version)
             :entries entries}
      (< version 2) (update :entries migrate-v1->v2)
      (< version 3) (update :entries migrate-v2->v3)
      (< version 4) (update :header migrate-v3->v4-header))))

;;; ============================================================
;;; Disk read
;;; ============================================================

(defn- valid-header?
  "True if `m` looks like a session header."
  [m]
  (and (map? m)
       (= :session (:type m))
       (string? (:id m))
       (not (str/blank? (:id m)))))

(defn load-session-file
  "Parse a session file from `file` (java.io.File or path string).
  Returns {:header header-map :entries [entry-map ...]}
  or nil if the file is missing, empty, or has no valid header.
  Malformed lines are silently skipped."
  [file]
  (let [f (io/file file)]
    (when (.exists f)
      (let [lines   (str/split-lines (slurp f))
            parsed  (keep parse-line lines)]
        (when (seq parsed)
          (let [header (first parsed)]
            (when (valid-header? header)
              (let [entries (vec (rest parsed))
                    {:keys [header entries]} (migrate-entries header entries)]
                {:header  header
                 :entries entries}))))))))

(defn- peek-header
  "Read only the first line of `file` and parse it.
  Returns the header map or nil. Cheap validity check."
  [^File file]
  (try
    (with-open [^java.io.BufferedReader rdr (io/reader file)]
      (let [first-line (.readLine rdr)]
        (when first-line
          (let [m (parse-line first-line)]
            (when (valid-header? m) m)))))
    (catch Exception _ nil)))

(defn find-most-recent-session
  "Return the path (string) of the most recently modified valid session
  file in `session-dir`, or nil if none found."
  [session-dir]
  (let [dir (io/file session-dir)]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName ^File %) ".ndedn"))
           (filter #(peek-header %))
           (sort-by #(.lastModified ^File %) >)
           first
           (#(when % (.getAbsolutePath ^File %)))))))

;;; ============================================================
;;; Session info (lightweight metadata for listing)
;;; ============================================================

(defn- extract-session-info
  "Build a SessionInfo map from a loaded session {:header :entries}."
  [file-path {:keys [header entries]}]
  (let [name          (some (fn [e]
                              (when (= :session-info (:kind e))
                                (get-in e [:data :name])))
                            (rseq (vec entries)))
        msg-entries   (filter #(= :message (:kind %)) entries)
        messages      (keep #(get-in % [:data :message]) msg-entries)
        user-messages (filter #(= "user" (:role %)) messages)
        first-text    (some (fn [m]
                              (let [c (:content m)]
                                (cond
                                  (string? c) (when-not (str/blank? c) c)
                                  (sequential? c)
                                  (some #(when (= :text (:type %)) (:text %)) c))))
                            user-messages)
        all-text      (str/join " "
                                (keep (fn [m]
                                        (let [c (:content m)]
                                          (cond
                                            (string? c) c
                                            (sequential? c)
                                            (str/join " "
                                                      (keep #(when (= :text (:type %)) (:text %)) c)))))
                                      messages))
        ;; modified = latest user/assistant message timestamp, else header
        last-activity (reduce (fn [acc m]
                                (let [ts (:timestamp m)]
                                  (cond
                                    (nil? ts)   acc
                                    (nil? acc)  ts
                                    (instance? Instant ts)
                                    (if (instance? Instant acc)
                                      (if (.isAfter ^Instant ts ^Instant acc) ts acc)
                                      ts)
                                    :else acc)))
                              nil
                              messages)
        modified      (or last-activity (:timestamp header))]
    {:path                file-path
     :id                  (:id header)
     :cwd                 (:worktree-path header)
     :worktree-path       (:worktree-path header)
     :name                name
     :parent-session-id   (:parent-session-id header)
     :parent-session-path (:parent-session header)
     :created             (:timestamp header)
     :modified            modified
     :message-count       (count msg-entries)
     :first-message       (or first-text "(no messages)")
     :all-messages-text   all-text}))

(defn list-sessions
  "Return a vector of SessionInfo maps for all valid session files in
  `session-dir`, sorted by :modified descending."
  [session-dir]
  (let [^File dir (io/file session-dir)]
    (if-not (.isDirectory dir)
      []
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName ^File %) ".ndedn"))
           (keep (fn [^File f]
                   (when-let [loaded (load-session-file f)]
                     (extract-session-info (.getAbsolutePath f) loaded))))
           (sort-by (fn [info]
                      (let [m (:modified info)]
                        (cond
                          (instance? Instant m)       (- (.toEpochMilli ^Instant m))
                          (instance? java.util.Date m) (- (.getTime ^java.util.Date m))
                          :else                        0)))
                    <)
           vec))))

(defn list-all-sessions
  "Return a vector of SessionInfo maps across all project directories
  under the sessions root, sorted by :modified descending."
  []
  (let [^File root @sessions-root]
    (if-not (.isDirectory root)
      []
      (->> (.listFiles root)
           (filter #(.isDirectory ^File %))
           (mapcat #(list-sessions (.getAbsolutePath ^File %)))
           (sort-by (fn [info]
                      (let [m (:modified info)]
                        (cond
                          (instance? Instant m)       (- (.toEpochMilli ^Instant m))
                          (instance? java.util.Date m) (- (.getTime ^java.util.Date m))
                          :else                        0)))
                    <)
           vec))))

;;; ============================================================
;;; Convenience entry constructors
;;; ============================================================

(defn message-entry [message]
  (session-model/make-entry :message {:message message}))

(defn thinking-level-entry [level]
  (session-model/make-entry :thinking-level {:thinking-level level}))

(defn model-entry [provider model-id]
  (session-model/make-entry :model {:provider provider :model-id model-id}))

(defn compaction-entry [result from-hook?]
  (session-model/make-entry :compaction
                            {:summary             (:summary result)
                             :first-kept-entry-id (:first-kept-entry-id result)
                             :tokens-before       (:tokens-before result)
                             :details             (:details result)
                             :from-hook           (boolean from-hook?)}))

(defn branch-summary-entry [from-id summary details label from-hook?]
  (session-model/make-entry :branch-summary
                            {:from-id   from-id
                             :summary   summary
                             :details   details
                             :label     label
                             :from-hook (boolean from-hook?)}))

(defn custom-message-entry [custom-type content details display?]
  (session-model/make-entry :custom-message
                            {:custom-type custom-type
                             :content     content
                             :details     details
                             :display     (boolean display?)}))

(defn label-entry [target-id label]
  (session-model/make-entry :label {:target-id target-id :label label}))

(defn session-info-entry [name]
  (session-model/make-entry :session-info {:name name}))
