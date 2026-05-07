(ns psi.session-journal.store
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.session-journal.codec :as codec])
  (:import
   (java.io File RandomAccessFile)
   (java.nio.channels FileLock OverlappingFileLockException)
   (java.nio.file Files OpenOption Path StandardOpenOption)
   (java.time Instant ZoneOffset)
   (java.time.format DateTimeFormatter)))

(def ^:private current-version 4)

(def ^:private sessions-root
  (delay (io/file (System/getProperty "user.home") ".psi" "agent" "sessions")))

(def ^:dynamic *session-file-lock-retry-ms* 25)
(def ^:dynamic *session-file-lock-max-attempts* 400)

(defn- sessions-root-dir
  ([] @sessions-root)
  ([root] (io/file root)))

(defn session-dir-for
  "Return the session directory (java.io.File) for `worktree-path`.
  Creates the directory if it does not exist.

  Optional `root` overrides the default sessions root for tests and controlled callers."
  ([worktree-path]
   (session-dir-for nil worktree-path))
  ([root worktree-path]
   (let [encoded (-> (str worktree-path)
                     (str/replace #"^[/\\]" "")
                     (str/replace #"[/\\:]" "-"))
         dir     (io/file (sessions-root-dir root) (str "--" encoded "--"))]
     (.mkdirs dir)
     dir)))

(defn- timestamp-prefix
  "Return a filesystem-safe timestamp string for now."
  []
  (let [fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm-ss-SSS")]
    (.format fmt (java.time.LocalDateTime/now ZoneOffset/UTC))))

(defn new-session-file-path
  "Return a new session file (java.io.File) under `session-dir`."
  [session-dir session-id]
  (io/file session-dir (str (timestamp-prefix) "_" session-id ".ndedn")))

(defn- make-header
  [session-id worktree-path parent-session-id parent-session-path]
  {:type              :session
   :version           current-version
   :id                session-id
   :timestamp         (Instant/now)
   :worktree-path     (str worktree-path)
   :parent-session-id (when parent-session-id (str parent-session-id))
   :parent-session    (when parent-session-path (str parent-session-path))})

(defn- valid-header?
  "True if `m` looks like a session header."
  [m]
  (and (map? m)
       (= :session (:type m))
       (string? (:id m))
       (not (str/blank? (:id m)))))

(defn- with-session-file-lock
  "Execute `f` while holding an exclusive file lock for `file`."
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
  ([^File file session-id worktree-path parent-session-path]
   (write-header! file session-id worktree-path nil parent-session-path))
  ([^File file session-id worktree-path parent-session-id parent-session-path]
   (with-session-file-lock
     file
     (fn []
       (let [header (make-header session-id worktree-path parent-session-id parent-session-path)
             ^Path path (.toPath file)
             ^bytes bytes (.getBytes (str (codec/entry->line header) "\n") "UTF-8")
             ^"[Ljava.nio.file.OpenOption;" opts
             (into-array OpenOption [StandardOpenOption/CREATE
                                     StandardOpenOption/TRUNCATE_EXISTING
                                     StandardOpenOption/WRITE])]
         (Files/write path bytes opts))))))

(defn append-entry-to-disk!
  "Append a single `entry` line to `file` (under lock)."
  [^File file entry]
  (append-line! file (codec/entry->line entry)))

(defn flush-journal!
  "Write the header + all `entries` to `file` in one operation.
  Overwrites any existing content. Uses exclusive file lock."
  ([^File file session-id worktree-path parent-session-path entries]
   (flush-journal! file session-id worktree-path nil parent-session-path entries))
  ([^File file session-id worktree-path parent-session-id parent-session-path entries]
   (with-session-file-lock
     file
     (fn []
       (let [header (make-header session-id worktree-path parent-session-id parent-session-path)
             lines  (str/join "\n"
                              (cons (codec/entry->line header)
                                    (map codec/entry->line entries)))
             ^Path path (.toPath file)
             ^bytes bytes (.getBytes (str lines "\n") "UTF-8")
             ^"[Ljava.nio.file.OpenOption;" opts
             (into-array OpenOption [StandardOpenOption/CREATE
                                     StandardOpenOption/TRUNCATE_EXISTING
                                     StandardOpenOption/WRITE])]
         (Files/write path bytes opts))))))

(defn- short-id
  "Generate a short random hex ID."
  []
  (str (java.util.UUID/randomUUID)))

(defn- migrate-v1->v2
  "Add :id and :parent-id to entries that lack them (v1 format).
  Returns updated entries vector."
  [entries]
  (let [indexed (map-indexed vector entries)]
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
    (some-> parent-path
            io/file
            .getName
            (#(re-matches #".*_([^_]+)\.ndedn$" %))
            second)))

(defn- migrate-v3->v4-header
  "Ensure header has :parent-session-id (derive from :parent-session path when possible)."
  [header]
  (let [existing (:parent-session-id header)
        derived  (or existing (parent-id-from-path (:parent-session header)))]
    (assoc header :parent-session-id derived)))

(defn- migrate-entries
  "Apply all necessary migrations to bring `entries` to current-version."
  [header entries]
  (let [version (or (:version header) 1)]
    (cond-> {:header (assoc header :version current-version)
             :entries entries}
      (< version 2) (update :entries migrate-v1->v2)
      (< version 3) (update :entries migrate-v2->v3)
      (< version 4) (update :header migrate-v3->v4-header))))

(defn load-session-file
  "Parse a session file from `file` (java.io.File or path string).
  Returns {:header header-map :entries [entry-map ...]}
  or nil if the file is missing, empty, or has no valid header.
  Malformed lines are silently skipped."
  [file]
  (let [f (io/file file)]
    (when (.exists f)
      (let [lines   (str/split-lines (slurp f))
            parsed  (keep codec/parse-line lines)]
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
          (let [m (codec/parse-line first-line)]
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

(defn- modified-sort-key
  [info]
  (let [m (:modified info)]
    (cond
      (instance? Instant m)        (- (.toEpochMilli ^Instant m))
      (instance? java.util.Date m) (- (.getTime ^java.util.Date m))
      :else                        0)))

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
           (sort-by modified-sort-key <)
           vec))))

(defn list-all-sessions
  "Return a vector of SessionInfo maps across all project directories
  under the sessions root, sorted by :modified descending.

  Optional `root` overrides the default sessions root for tests and controlled callers."
  ([]
   (list-all-sessions nil))
  ([root]
   (let [^File root-dir (sessions-root-dir root)]
     (if-not (.isDirectory root-dir)
       []
       (->> (.listFiles root-dir)
            (filter #(.isDirectory ^File %))
            (mapcat #(list-sessions (.getAbsolutePath ^File %)))
            (sort-by modified-sort-key <)
            vec)))))
