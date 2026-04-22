(ns psi.history.git
  "Thin wrapper over the git CLI.  Nullable pattern: all fns take a
   GitContext record (created via `create-context` or `create-null-context`).
   Tests use `create-null-context` which builds an isolated temp git repo —
   no mocking, no shared state, no dependency on the real project repo."
  (:require [clojure.string :as str]
            [taoensso.timbre :as timbre])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;;; GitContext record

(defrecord GitContext [repo-dir])

(declare status)
(declare current-commit)

(defn create-context
  "Create a GitContext pointing at `repo-dir` (defaults to cwd)."
  ([]
   (create-context (System/getProperty "user.dir")))
  ([repo-dir]
   (->GitContext repo-dir)))

(defn create-null-context
  "Create an isolated GitContext backed by a fresh temp git repo.
   Seeds it with the provided `commits` seq of {:message :files} maps,
   where :files is a map of {filename content}.

   Returns a GitContext.  Caller is responsible for cleanup (temp dirs
   are in the JVM temp dir and will be cleaned up on JVM exit)."
  ([]
   (create-null-context [{:message "⚒ Initial commit"
                          :files   {"README.md" "# psi\n"}}
                         {:message "λ First learning"
                          :files   {"LEARNING.md" "## learned something\n"}}
                         {:message "Δ Show a delta"
                          :files   {"CHANGELOG.md" "## v0.1\n"}}]))
  ([commits]
   (let [tmp  (str (Files/createTempDirectory "psi-git-null-"
                                              (make-array FileAttribute 0)))
         run! (fn [args]
                (let [pb   (ProcessBuilder. ^java.util.List args)
                      _    (.directory pb (File. ^String tmp))
                      _    (doto (.environment pb)
                             (.put "GIT_AUTHOR_NAME"     "Test Author")
                             (.put "GIT_AUTHOR_EMAIL"    "test@example.com")
                             (.put "GIT_COMMITTER_NAME"  "Test Author")
                             (.put "GIT_COMMITTER_EMAIL" "test@example.com"))
                      proc (.start pb)
                      _    (slurp (.getInputStream proc))
                      _    (slurp (.getErrorStream proc))]
                  (.waitFor proc)))]
     (run! ["git" "init" "-b" "main"])
     (run! ["git" "config" "user.email" "test@example.com"])
     (run! ["git" "config" "user.name"  "Test Author"])
     (doseq [{:keys [message files]} commits]
       (doseq [[fname content] files]
         (let [f (File. (str tmp "/" fname))]
           (.mkdirs (.getParentFile f))
           (spit f content)))
       (run! ["git" "add" "."])
       (run! ["git" "commit" "-m" message]))
     (->GitContext tmp))))

;;; Internal helpers

(defn- run-git*
  "Run git `args` in ctx's repo-dir; return {:out :err :exit}."
  [^GitContext ctx args]
  (let [pb   (ProcessBuilder. ^java.util.List (into ["git"] args))
        _    (.directory pb (File. ^String (:repo-dir ctx)))
        proc (.start pb)
        out  (slurp (.getInputStream proc))
        err  (slurp (.getErrorStream proc))
        exit (.waitFor proc)]
    {:out  (str/trim out)
     :err  (str/trim err)
     :exit exit}))

(defn- run-git
  "Run git `args` in ctx's repo-dir; return trimmed stdout or throw."
  [^GitContext ctx args]
  (let [{:keys [out err exit]} (run-git* ctx args)]
    (when (pos? exit)
      (throw (ex-info "git command failed"
                      {:dir  (:repo-dir ctx)
                       :args args
                       :err  err
                       :exit exit})))
    out))

(defn- canonical-path
  ^String [p]
  (when (seq (str p))
    (try
      (.getCanonicalPath (File. (str p)))
      (catch Exception _
        (.getAbsolutePath (File. (str p)))))))

(defn- inside-path?
  [^String child ^String parent]
  (and (seq child)
       (seq parent)
       (or (= child parent)
           (str/starts-with? child (str parent File/separator)))))

(defn- select-containing-worktree
  [cwd worktrees]
  (let [cwd* (canonical-path cwd)]
    (->> worktrees
         (filter (fn [wt]
                   (inside-path? cwd* (canonical-path (:git.worktree/path wt)))))
         (sort-by (fn [wt] (count (canonical-path (:git.worktree/path wt)))) >)
         first)))

(def ^:private sep    "\u001F")
(def ^:private sep-re (re-pattern sep))

;;; Symbol extraction

(defn- extract-symbols
  "Return set of psi vocabulary symbols found in `text`."
  [text]
  (let [vocab #{"⚒" "◇" "⊘" "◈" "∿" "·" "λ" "Δ" "✓" "✗" "‖" "↺" "…" "刀" "ψ"}]
    (into #{} (filter #(str/includes? text %) vocab))))

;;; Worktree parsing

(defn- parse-worktree-block
  [lines]
  (reduce (fn [acc line]
            (cond
              (str/starts-with? line "worktree ")
              (assoc acc :git.worktree/path (subs line 9))

              (str/starts-with? line "HEAD ")
              (assoc acc :git.worktree/head (subs line 5))

              (str/starts-with? line "branch ")
              (let [ref (subs line 7)]
                (assoc acc
                       :git.worktree/branch-ref ref
                       :git.worktree/branch-name (when (str/starts-with? ref "refs/heads/")
                                                   (subs ref 11))
                       :git.worktree/detached? false))

              (= line "detached")
              (assoc acc :git.worktree/detached? true)

              (= line "bare")
              (assoc acc :git.worktree/bare? true)

              (str/starts-with? line "locked")
              (assoc acc
                     :git.worktree/locked? true
                     :git.worktree/lock-reason (some-> (subs line (min (count line) 7))
                                                       str/trim
                                                       not-empty))

              (str/starts-with? line "prunable")
              (assoc acc
                     :git.worktree/prunable? true
                     :git.worktree/prunable-reason (some-> (subs line (min (count line) 9))
                                                           str/trim
                                                           not-empty))

              :else acc))
          {:git.worktree/branch-ref nil
           :git.worktree/branch-name nil
           :git.worktree/detached? false
           :git.worktree/bare? false
           :git.worktree/locked? false
           :git.worktree/prunable? false
           :git.worktree/prunable-reason nil
           :git.worktree/lock-reason nil}
          lines))

(defn parse-worktree-porcelain
  "Parse `git worktree list --porcelain` output into worktree maps."
  [s]
  (if (str/blank? s)
    []
    (->> (str/split s #"\n\s*\n")
         (map str/split-lines)
         (map parse-worktree-block)
         (filter :git.worktree/path)
         vec)))

(defn inside-repo?
  "Return true when `ctx` points inside a git worktree/repository."
  [ctx]
  (try
    (= "true" (run-git ctx ["rev-parse" "--is-inside-work-tree"]))
    (catch Exception _ false)))

(defn- emit-worktree-parse-failed!
  [ctx e]
  (timbre/warn "git.worktree.parse_failed"
               {:repo-dir (:repo-dir ctx)
                :error (ex-message e)}))

(defn worktree-list
  "Return vector of worktree maps for `ctx`.

   Each map includes:
     :git.worktree/path
     :git.worktree/head
     :git.worktree/branch-ref
     :git.worktree/branch-name
     :git.worktree/detached?
     :git.worktree/bare?
     :git.worktree/locked?
     :git.worktree/prunable?
     :git.worktree/prunable-reason
     :git.worktree/lock-reason
     :git.worktree/current?

   On parse/command errors, returns [] and logs a telemetry marker:
     event: git.worktree.parse_failed."
  [ctx]
  (if-not (inside-repo? ctx)
    []
    (try
      (let [out       (run-git ctx ["worktree" "list" "--porcelain"])
            parsed    (parse-worktree-porcelain out)
            current   (select-containing-worktree (:repo-dir ctx) parsed)
            current-p (some-> current :git.worktree/path canonical-path)]
        (mapv (fn [wt]
                (assoc wt :git.worktree/current?
                       (= (canonical-path (:git.worktree/path wt)) current-p)))
              parsed))
      (catch Exception e
        (emit-worktree-parse-failed! ctx e)
        []))))

(defn current-worktree
  "Return the current worktree map for `ctx`, or nil when unavailable."
  [ctx]
  (first (filter :git.worktree/current? (worktree-list ctx))))

(defn git-dir
  "Return the canonical git dir for `ctx`, or nil when unavailable."
  [ctx]
  (try
    (not-empty (run-git ctx ["rev-parse" "--git-dir"]))
    (catch Exception _
      nil)))

(defn- path-exists?
  [path]
  (.exists (File. (str path))))

(defn head-reflog-latest
  "Return the latest HEAD reflog entry as {:head :selector :subject}, or nil."
  [ctx]
  (try
    (let [out (run-git ctx ["reflog" "-1" "--format=%H%x1f%gD%x1f%gs" "HEAD"])]
      (when-not (str/blank? out)
        (let [[head selector subject] (str/split out sep-re 3)]
          {:head head
           :selector selector
           :subject subject})))
    (catch Exception _
      nil)))

(defn commit-parent-count
  "Return the parent count for `sha`, or nil when unavailable."
  [ctx sha]
  (when (seq (str sha))
    (try
      (let [line (run-git ctx ["rev-list" "--parents" "-n" "1" (str sha)])
            parts (remove str/blank? (str/split line #"\s+"))]
        (when (seq parts)
          (max 0 (dec (count parts)))))
      (catch Exception _
        nil))))

(defn- branch-ref
  [branch]
  (str "refs/heads/" branch))

(defn- branch-exists?
  [ctx branch]
  (zero? (:exit (run-git* ctx ["show-ref" "--verify" "--quiet" (branch-ref branch)]))))

(defn- current-branch-name
  [ctx]
  (or (some-> (current-worktree ctx) :git.worktree/branch-name)
      (let [{:keys [out exit]} (run-git* ctx ["rev-parse" "--abbrev-ref" "HEAD"])]
        (when (and (zero? exit) (not= out "HEAD"))
          out))))

(defn- dirty-working-tree?
  [ctx]
  (not= :clean (status ctx)))

(defn- fast-forwardable?
  [ctx branch]
  (zero? (:exit (run-git* ctx ["merge-base" "--is-ancestor" "HEAD" branch]))))

(defn branch-tip-merged-into-current?
  "Return true when the tip of `branch` is an ancestor of current HEAD in `ctx`."
  [ctx branch]
  (zero? (:exit (run-git* ctx ["merge-base" "--is-ancestor" branch "HEAD"]))))

(defn- merge-in-progress?
  [ctx]
  (zero? (:exit (run-git* ctx ["rev-parse" "-q" "--verify" "MERGE_HEAD"]))))

(defn- rebase-in-progress?
  [ctx]
  (zero? (:exit (run-git* ctx ["rev-parse" "-q" "--verify" "REBASE_HEAD"]))))

(defn operation-state
  "Return best-effort transient git operation state for `ctx`."
  [ctx]
  (let [git-dir* (some-> (git-dir ctx) (str/trim))
        repo-dir (:repo-dir ctx)
        resolve-path (fn [rel]
                       (when (seq git-dir*)
                         (.getPath (File. (File. ^String repo-dir ^String git-dir*) ^String rel))))
        marker? (fn [rel]
                  (some-> rel resolve-path path-exists? boolean))
        merge? (or (merge-in-progress? ctx)
                   (marker? "MERGE_HEAD"))
        rebase? (or (rebase-in-progress? ctx)
                    (marker? "rebase-merge")
                    (marker? "rebase-apply"))
        cherry-pick? (marker? "CHERRY_PICK_HEAD")
        revert? (marker? "REVERT_HEAD")
        bisect? (marker? "BISECT_LOG")]
    {:merge? merge?
     :rebase? rebase?
     :cherry-pick? cherry-pick?
     :revert? revert?
     :bisect? bisect?
     :transient? (boolean (or merge? rebase? cherry-pick? revert? bisect?))}))

(defn- main-worktree-path
  [ctx]
  (some-> (first (worktree-list ctx)) :git.worktree/path canonical-path))

(defn- error-message
  [e]
  (or (not-empty (:err (ex-data e)))
      (ex-message e)
      "git command failed"))

(defn- normalize-merge-strategy
  [strategy]
  (case strategy
    :ff_only :ff-only
    :ff-only :ff-only
    :no_ff :no-ff
    :no-ff :no-ff
    :ff :ff
    :ff-only))

;;; Worktree and branch mutations

(defn worktree-add
  "Create a linked worktree.

   Request keys:
   - :path
   - :branch
   - :base-ref (defaults to HEAD)
   - :create-branch (defaults to true)

   Compatibility:
   - accepts both :base-ref and :base_ref
   - accepts both :create-branch and :create_branch"
  [ctx req]
  (let [{:keys [path branch] :as req*} req
        base-ref      (if (contains? req* :base-ref)
                        (:base-ref req*)
                        (when (contains? req* :base_ref)
                          (:base_ref req*)))
        create-branch (if (contains? req* :create-branch)
                        (:create-branch req*)
                        (if (contains? req* :create_branch)
                          (:create_branch req*)
                          true))]
    (cond
      (path-exists? path)
      {:path path
       :branch branch
       :head nil
       :success false
       :error "worktree path already exists"}

      (and create-branch (branch-exists? ctx branch))
      {:path path
       :branch branch
       :head nil
       :success false
       :error "branch already exists"}

      :else
      (let [base-ref* (or base-ref "HEAD")
            args      (if create-branch
                        (cond-> ["worktree" "add" "-b" branch path]
                          (seq base-ref*) (conj base-ref*))
                        ["worktree" "add" path branch])]
        (try
          (run-git ctx args)
          {:path path
           :branch branch
           :head (current-commit (create-context path))
           :success true
           :error nil}
          (catch Exception e
            {:path path
             :branch branch
             :head nil
             :success false
             :error (error-message e)}))))))

(defn worktree-remove
  "Remove a linked worktree.

   Request keys:
   - :path
   - :force (defaults to false)"
  [ctx {:keys [path force]
        :or   {force false}}]
  (let [path*           (canonical-path path)
        worktrees       (worktree-list ctx)
        target          (some (fn [wt]
                                (when (= (canonical-path (:git.worktree/path wt)) path*)
                                  wt))
                              worktrees)
        main-path       (main-worktree-path ctx)]
    (cond
      (nil? target)
      {:path path
       :success false
       :error "worktree path not found"}

      (= path* main-path)
      {:path path
       :success false
       :error "cannot remove main worktree"}

      :else
      (try
        (run-git ctx (cond-> ["worktree" "remove"]
                       force (conj "--force")
                       true  (conj path)))
        {:path path
         :success true
         :error nil}
        (catch Exception e
          {:path path
           :success false
           :error (error-message e)})))))

(defn branch-merge
  "Merge `branch` into the current branch in `ctx`.

   Request keys:
   - :branch
   - :strategy (:ff_only/:ff-only default, :ff, :no_ff/:no-ff)
   - :message (used for no-ff merges)"
  [ctx {:keys [branch strategy message]}]
  (let [strategy* (normalize-merge-strategy strategy)
        ff?       (fast-forwardable? ctx branch)]
    (cond
      (dirty-working-tree? ctx)
      {:branch branch
       :merged false
       :fast-forward false
       :conflict false
       :error "working tree is dirty"}

      (and (= strategy* :ff-only) (not ff?))
      {:branch branch
       :merged false
       :fast-forward false
       :conflict false
       :error "not fast-forwardable; rebase first"}

      :else
      (let [args (case strategy*
                   :no-ff (cond-> ["merge" "--no-ff"]
                            (seq message) (into ["-m" message])
                            true          (conj branch))
                   :ff    ["merge" "--ff" branch]
                   ["merge" "--ff-only" branch])
            before-head (current-commit ctx)
            before-branch (current-branch-name ctx)]
        (try
          (run-git ctx args)
          (let [after-head   (current-commit ctx)
                verified?    (branch-tip-merged-into-current? ctx branch)
                changed?     (not= before-head after-head)
                merged?      (and verified? changed?)
                target-branch (current-branch-name ctx)]
            (if merged?
              {:branch branch
               :merged true
               :fast-forward (boolean ff?)
               :conflict false
               :error nil}
              {:branch branch
               :merged false
               :fast-forward false
               :conflict false
               :error (str "merge reported success but target HEAD did not absorb branch"
                           " (current-branch=" before-branch
                           ", target-branch=" target-branch
                           ", before-head=" before-head
                           ", after-head=" after-head
                           ", changed=" changed?
                           ", verified=" verified? ")")}))
          (catch Exception e
            (let [conflict? (merge-in-progress? ctx)]
              (when conflict?
                (run-git* ctx ["merge" "--abort"]))
              {:branch branch
               :merged false
               :fast-forward false
               :conflict conflict?
               :error (if conflict?
                        "merge conflict; aborting"
                        (error-message e))})))))))

(defn branch-delete
  "Delete a local branch.

   Request keys:
   - :branch
   - :force (defaults to false)"
  [ctx {:keys [branch force]
        :or   {force false}}]
  (cond
    (not (branch-exists? ctx branch))
    {:branch branch
     :deleted false
     :error "branch not found"}

    (= branch (current-branch-name ctx))
    {:branch branch
     :deleted false
     :error "cannot delete current branch"}

    :else
    (try
      (run-git ctx ["branch" (if force "-D" "-d") branch])
      {:branch branch
       :deleted true
       :error nil}
      (catch Exception e
        {:branch branch
         :deleted false
         :error (error-message e)}))))

(defn branch-rebase
  "Rebase a branch onto another branch.

   Request keys:
   - :onto
   - :branch (defaults to current branch)"
  [ctx {:keys [onto branch]}]
  (let [branch* (or branch (current-branch-name ctx))
        args    (cond-> ["rebase" onto]
                  (seq branch) (conj branch))]
    (cond
      (dirty-working-tree? ctx)
      {:onto onto
       :branch branch*
       :success false
       :conflict false
       :error "working tree is dirty"}

      (not (seq onto))
      {:onto onto
       :branch branch*
       :success false
       :conflict false
       :error "missing rebase target"}

      :else
      (try
        (run-git ctx args)
        {:onto onto
         :branch branch*
         :success true
         :conflict false
         :error nil}
        (catch Exception e
          (let [conflict? (rebase-in-progress? ctx)]
            (when conflict?
              (run-git* ctx ["rebase" "--abort"]))
            {:onto onto
             :branch branch*
             :success false
             :conflict conflict?
             :error (if conflict?
                      "rebase conflict; aborting"
                      (error-message e))}))))))

(defn default-branch
  "Resolve the default branch.

   Resolution order:
   1. refs/remotes/origin/HEAD symbolic ref
   2. git config init.defaultBranch
   3. fallback main"
  [ctx]
  (let [{sym-out :out sym-exit :exit} (run-git* ctx ["symbolic-ref" "--short" "refs/remotes/origin/HEAD"])
        symbolic-branch               (when (and (zero? sym-exit) (seq sym-out))
                                        (last (str/split sym-out #"/")))
        {cfg-out :out cfg-exit :exit} (run-git* ctx ["config" "--get" "init.defaultBranch"])
        config-branch                 (when (and (zero? cfg-exit) (seq cfg-out))
                                        cfg-out)]
    (cond
      (seq symbolic-branch)
      {:branch symbolic-branch
       :source :symbolic_ref}

      (seq config-branch)
      {:branch config-branch
       :source :config}

      :else
      {:branch "main"
       :source :fallback})))

;;; Public API — all take a GitContext as first arg

(defn log
  "Return a seq of commit maps from `ctx`.

   Options:
     :n    — max commits (default 50)
     :grep — filter by message pattern
     :path — restrict to file/directory

   Each map: :git.commit/sha :git.commit/date :git.commit/author
              :git.commit/email :git.commit/subject :git.commit/symbols"
  ([ctx] (log ctx {}))
  ([ctx {:keys [n grep path] :or {n 50}}]
   (let [fmt  (str "--format=%H" sep "%aI" sep "%an" sep "%ae" sep "%s")
         args (cond-> ["log" fmt (str "-" n)]
                grep (conj (str "--grep=" grep))
                path (conj "--" path))
         out  (run-git ctx args)]
     (when (seq out)
       (for [line (str/split-lines out)
             :when (seq line)
             :let [parts                          (str/split line sep-re)
                   [sha date author email subject] parts]]
         {:git.commit/sha     sha
          :git.commit/date    date
          :git.commit/author  author
          :git.commit/email   email
          :git.commit/subject subject
          :git.commit/symbols (extract-symbols (or subject ""))})))))

(defn show
  "Return full detail map for commit `sha` in `ctx`.
   Adds :git.commit/body :git.commit/stat :git.commit/diff."
  [ctx sha]
  (let [fmt    (str "--format=%H" sep "%aI" sep "%an" sep "%ae" sep "%s" sep "%b")
        header (run-git ctx ["show" fmt "--stat" sha])
        diff   (run-git ctx ["show" "--format=" "--unified=3" sha])
        lines  (str/split-lines header)
        parts  (str/split (first lines) sep-re)
        [sha2 date author email subject body] parts]
    {:git.commit/sha     sha2
     :git.commit/date    date
     :git.commit/author  author
     :git.commit/email   email
     :git.commit/subject subject
     :git.commit/body    (str/trim (or body ""))
     :git.commit/stat    (str/join "\n" (rest lines))
     :git.commit/diff    diff
     :git.commit/symbols (extract-symbols (str subject " " body))}))

(defn status
  "Return :clean | :modified | :staged | :error for `ctx`."
  [ctx]
  (try
    (let [out (run-git ctx ["status" "--porcelain"])]
      (cond
        (str/includes? out "M ") :staged
        (seq out)                :modified
        :else                    :clean))
    (catch Exception _ :error)))

(defn current-branch
  "Return the current branch name in `ctx`, or nil when detached."
  [ctx]
  (current-branch-name ctx))

(defn current-commit
  "Return the SHA of HEAD in `ctx`."
  [ctx]
  (run-git ctx ["rev-parse" "HEAD"]))

(defn ls-files
  "Return a seq of tracked file paths in `ctx`.
   Optional `:path` restricts to a subdirectory."
  ([ctx] (ls-files ctx {}))
  ([ctx {:keys [path]}]
   (let [args (cond-> ["ls-files"] path (conj path))
         out  (run-git ctx args)]
     (when (seq out) (str/split-lines out)))))

(defn grep
  "Search `pattern` in file contents at HEAD of `ctx`.

   Options:
     :path — restrict to file/directory
     :n    — max results (default 200)

   Returns seq of {:git.grep/file :git.grep/line :git.grep/content}."
  ([ctx pattern] (grep ctx pattern {}))
  ([ctx pattern {:keys [path n] :or {n 200}}]
   (let [args (cond-> ["grep" "-n" "--no-color" "-e" pattern "HEAD"]
                path (conj "--" path))
         out  (try (run-git ctx args)
                   (catch Exception _ ""))]
     (when (seq out)
       (->> (str/split-lines out)
            (take n)
            (keep (fn [line]
                    (let [[_ file lineno content]
                          (re-matches #"HEAD:([^:]+):(\d+):(.*)" line)]
                      (when file
                        {:git.grep/file    file
                         :git.grep/line    (Long/parseLong lineno)
                         :git.grep/content content})))))))))
