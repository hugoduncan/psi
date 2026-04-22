(ns psi.history.git-test
  "Tests for psi.history.git.

   Uses create-null-context — an isolated temp git repo with seeded commits.
   No mocks, no dependency on the real project repo, no shared state."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [psi.history.git :as git])
  (:import
   (java.io File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(def ^:private seed-commits
  [{:message "⚒ Initial commit"
    :files   {"README.md"    "# psi\n"
              "src/core.clj" "(ns core)\n(defresolver foo [] {})\n"}}
   {:message "λ First learning captured"
    :files   {"LEARNING.md" "## learned something\n"}}
   {:message "Δ Show a delta here"
    :files   {"CHANGELOG.md" "## v0.1\n"}}
   {:message "⚒ Add another feature"
    :files   {"src/extra.clj" "(ns extra)\n"}}])

(def ^:private shared-ro-ctx
  "Delayed null context for read-only tests. Created once per test run."
  (delay (git/create-null-context seed-commits)))

(defn- canonical-path
  [path]
  (.getCanonicalPath (File. (str path))))

(defn- worktree-listed?
  [worktrees path]
  (some #(= (canonical-path path)
            (canonical-path (:git.worktree/path %)))
        worktrees))

(defn- linked-worktree-path
  [ctx name]
  (let [repo-file (File. (:repo-dir ctx))
        parent    (.getCanonicalPath (.getParentFile repo-file))
        suffix    (.getName repo-file)
        uniq      (str (java.util.UUID/randomUUID))]
    (str parent File/separator name "-" suffix "-" uniq)))

(defn- append-and-commit!
  [repo-dir rel-path content message]
  (let [f (File. (str repo-dir File/separator rel-path))
        run! (fn [& args]
               (let [pb   (ProcessBuilder. ^java.util.List (vec args))
                     _    (.directory pb (File. ^String repo-dir))
                     _    (doto (.environment pb)
                            (.put "GIT_AUTHOR_NAME"     "Test Author")
                            (.put "GIT_AUTHOR_EMAIL"    "test@example.com")
                            (.put "GIT_COMMITTER_NAME"  "Test Author")
                            (.put "GIT_COMMITTER_EMAIL" "test@example.com"))
                     proc (.start pb)
                     _    (slurp (.getInputStream proc))
                     err  (slurp (.getErrorStream proc))
                     exit (.waitFor proc)]
                 (when (pos? exit)
                   (throw (ex-info "git helper failed" {:args args :err err :exit exit})))))]
    (.mkdirs (.getParentFile f))
    (spit f content)
    (run! "git" "add" rel-path)
    (run! "git" "commit" "-m" message)))

;;; git/log

(deftest create-context-defaults-to-cwd
  ;; Tests create-context default arity against the current working directory contract.
  (testing "create-context"
    (testing "defaults repo-dir to cwd"
      (let [ctx (git/create-context)]
        (is (= (.getCanonicalPath (File. (System/getProperty "user.dir")))
               (.getCanonicalPath (File. (:repo-dir ctx)))))))))

(deftest create-null-context-default-seeds-a-repo
  ;; Tests create-null-context default seeding for an isolated readable repository.
  (testing "create-null-context"
    (let [ctx     (git/create-null-context)
          commits (git/log ctx)]
      (testing "creates a usable git repository"
        (is (true? (git/inside-repo? ctx))))
      (testing "seeds the default commit set"
        (is (= 3 (count commits)))))))

(deftest log-returns-commits
  ;; Tests log for default arity, required fields, and path filtering.
  (testing "log"
    (let [ctx     @shared-ro-ctx
          commits (git/log ctx)
          by-path (git/log ctx {:path "src"})]
      (testing "returns the seeded commits"
        (is (= 4 (count commits))))
      (testing "includes required commit fields"
        (is (every? :git.commit/sha commits))
        (is (every? :git.commit/subject commits))
        (is (every? :git.commit/author commits))
        (is (every? :git.commit/date commits))
        (is (every? :git.commit/symbols commits)))
      (testing "always returns symbols as a set"
        (is (every? set? (map :git.commit/symbols commits))))
      (testing "single-arity log matches explicit empty options"
        (is (= commits (git/log ctx {}))))
      (testing "path filtering restricts commits to the requested subtree"
        (is (seq by-path))
        (is (every? #(or (str/includes? (:git.commit/subject %) "Initial")
                         (str/includes? (:git.commit/subject %) "another feature"))
                    by-path))))))

(deftest log-n-limits-results
  ;; Tests log option handling for result count limiting.
  (testing "log"
    (testing "respects the :n option"
      (let [ctx     @shared-ro-ctx
            commits (git/log ctx {:n 2})]
        (is (<= (count commits) 2))))))

(deftest log-grep-filters-by-message
  ;; Tests log message filtering through git grep arguments.
  (testing "log"
    (testing "filters commits by subject pattern"
      (let [ctx     @shared-ro-ctx
            grepped (git/log ctx {:grep "λ"})]
        (is (= 1 (count grepped)))
        (is (str/includes? (:git.commit/subject (first grepped)) "λ"))))))

(deftest log-symbols-extracted
  ;; Tests log symbol extraction for psi vocabulary markers in commit subjects.
  (testing "log"
    (let [ctx     @shared-ro-ctx
          commits (git/log ctx {})]
      (testing "extracts λ from matching commit subjects"
        (let [lambda-c (first (filter #(str/includes? (:git.commit/subject %) "λ") commits))]
          (is (contains? (:git.commit/symbols lambda-c) "λ"))))
      (testing "extracts Δ from matching commit subjects"
        (let [delta-c (first (filter #(str/includes? (:git.commit/subject %) "Δ") commits))]
          (is (contains? (:git.commit/symbols delta-c) "Δ")))))))

;;; git/show

(deftest show-returns-detail
  ;; Tests show for detailed commit metadata and diff/stat payloads.
  (testing "show"
    (let [ctx    @shared-ro-ctx
          sha    (git/current-commit ctx)
          detail (git/show ctx sha)]
      (testing "returns the requested sha"
        (is (= sha (:git.commit/sha detail))))
      (testing "includes diff and stat strings"
        (is (string? (:git.commit/diff detail)))
        (is (string? (:git.commit/stat detail))))
      (testing "includes a string subject"
        (is (string? (:git.commit/subject detail))))
      (testing "returns symbols as a set"
        (is (set? (:git.commit/symbols detail)))))))

;;; git/status

(deftest status-clean-on-fresh-repo
  ;; Tests status on a freshly committed repository.
  (testing "status"
    (testing "returns :clean after seeded commits"
      (let [ctx @shared-ro-ctx]
        (is (= :clean (git/status ctx)))))))

;;; git/current-commit

(deftest current-commit-is-40-char-sha
  ;; Tests current-commit for canonical git SHA formatting.
  (testing "current-commit"
    (let [ctx @shared-ro-ctx
          sha (git/current-commit ctx)]
      (testing "returns a 40 character sha"
        (is (= 40 (count sha))))
      (testing "returns lowercase hexadecimal"
        (is (re-matches #"[0-9a-f]+" sha))))))

(deftest head-reflog-and-parent-count-available
  (testing "reflog + topology helpers"
    (let [ctx    @shared-ro-ctx
          sha    (git/current-commit ctx)
          reflog (git/head-reflog-latest ctx)]
      (is (map? reflog))
      (is (= sha (:head reflog)))
      (is (string? (:subject reflog)))
      (is (= 1 (git/commit-parent-count ctx sha))))))

(deftest operation-state-clean-on-seeded-repo
  (testing "operation-state"
    (let [ctx @shared-ro-ctx
          state (git/operation-state ctx)]
      (is (false? (:merge? state)))
      (is (false? (:rebase? state)))
      (is (false? (:transient? state))))))

;;; git/current-branch + git/ls-files

(deftest current-branch-returns-branch-name
  ;; Tests current-branch for the branch name visible from a normal worktree.
  (testing "current-branch"
    (testing "returns the current branch name"
      (let [ctx @shared-ro-ctx]
        (is (= "main" (git/current-branch ctx)))))))

(deftest ls-files-returns-tracked-paths
  ;; Tests ls-files for default arity and path-restricted listing.
  (testing "ls-files"
    (let [ctx       @shared-ro-ctx
          files     (git/ls-files ctx)
          src-files (git/ls-files ctx {:path "src"})]
      (testing "returns tracked file paths"
        (is (seq files))
        (is (every? string? files)))
      (testing "includes seeded top-level files"
        (is (some #(= % "README.md") files))
        (is (some #(= % "LEARNING.md") files)))
      (testing "single-arity ls-files matches explicit empty options"
        (is (= files (git/ls-files ctx {}))))
      (testing "path filtering restricts results to the requested subtree"
        (is (seq src-files))
        (is (every? #(str/starts-with? % "src/") src-files))))))

;;; git/grep

(deftest grep-finds-pattern
  ;; Tests grep for default arity, result limiting, and path filtering.
  (testing "grep"
    (let [ctx         @shared-ro-ctx
          results     (git/grep ctx "defresolver")
          limited     (git/grep ctx "ns" {:n 1})
          src-results (git/grep ctx "ns" {:path "src"})]
      (testing "finds matching lines"
        (is (seq results)))
      (testing "includes required result fields"
        (is (every? :git.grep/file results))
        (is (every? :git.grep/line results))
        (is (every? :git.grep/content results)))
      (testing "uses numeric line numbers"
        (is (every? number? (map :git.grep/line results))))
      (testing "single-arity grep matches explicit empty options"
        (is (= results (git/grep ctx "defresolver" {}))))
      (testing "respects the :n option"
        (is (= 1 (count limited))))
      (testing "path filtering restricts matches to the requested subtree"
        (is (seq src-results))
        (is (every? #(str/starts-with? (:git.grep/file %) "src/") src-results))))))

(deftest grep-no-match-returns-empty
  ;; Tests grep behavior when there are no matches.
  (testing "grep"
    (testing "returns nil or empty when nothing matches"
      (let [ctx     @shared-ro-ctx
            results (git/grep ctx "XYZZY_NOTHING_HERE_9999" {})]
        (is (or (nil? results) (empty? results)))))))

;;; worktree parsing + listing

(deftest parse-worktree-porcelain-empty
  ;; Tests parse-worktree-porcelain on blank porcelain output.
  (testing "parse-worktree-porcelain"
    (testing "returns no worktrees for blank output"
      (is (= [] (git/parse-worktree-porcelain ""))))))

(deftest parse-worktree-porcelain-linked-and-detached
  ;; Tests parse-worktree-porcelain for linked and detached worktree entries.
  (testing "parse-worktree-porcelain"
    (let [porcelain (str "worktree /repo/main\n"
                         "HEAD 1111111111111111111111111111111111111111\n"
                         "branch refs/heads/master\n\n"
                         "worktree /repo/wt-1\n"
                         "HEAD 2222222222222222222222222222222222222222\n"
                         "branch refs/heads/feature-x\n\n"
                         "worktree /repo/wt-detached\n"
                         "HEAD 3333333333333333333333333333333333333333\n"
                         "detached\n")
          worktrees (git/parse-worktree-porcelain porcelain)]
      (testing "parses all worktrees"
        (is (= 3 (count worktrees))))
      (testing "parses branch names for linked worktrees"
        (is (= "master" (get-in worktrees [0 :git.worktree/branch-name])))
        (is (= "feature-x" (get-in worktrees [1 :git.worktree/branch-name]))))
      (testing "marks detached worktrees"
        (is (true? (get-in worktrees [2 :git.worktree/detached?])))))))

(deftest parse-worktree-porcelain-covers-optional-markers
  ;; Tests parse-worktree-porcelain for optional worktree markers and ignored lines.
  (testing "parse-worktree-porcelain"
    (let [porcelain (str "worktree /repo/main\n"
                         "HEAD 1111111111111111111111111111111111111111\n"
                         "branch refs/heads/main\n"
                         "locked reason from git\n"
                         "prunable stale checkout\n"
                         "ignored metadata\n\n"
                         "worktree /repo/bare\n"
                         "HEAD 2222222222222222222222222222222222222222\n"
                         "bare\n")
          worktrees (git/parse-worktree-porcelain porcelain)]
      (testing "parses locked and prunable markers with reasons"
        (is (true? (get-in worktrees [0 :git.worktree/locked?])))
        (is (= "reason from git" (get-in worktrees [0 :git.worktree/lock-reason])))
        (is (true? (get-in worktrees [0 :git.worktree/prunable?])))
        (is (= "stale checkout" (get-in worktrees [0 :git.worktree/prunable-reason]))))
      (testing "parses bare marker"
        (is (true? (get-in worktrees [1 :git.worktree/bare?])))))))

(deftest emit-worktree-parse-failed-does-not-throw
  ;; Tests emit-worktree-parse-failed! for safe telemetry emission.
  (testing "emit-worktree-parse-failed!"
    (testing "returns nil after emitting telemetry"
      (let [ctx @shared-ro-ctx]
        (is (nil? (#'psi.history.git/emit-worktree-parse-failed!
                   ctx
                   (ex-info "parse boom" {}))))))))

(deftest inside-repo-detects-null-context
  ;; Tests inside-repo? on a seeded null context.
  (testing "inside-repo?"
    (testing "recognizes a null context as being inside a repo"
      (let [ctx @shared-ro-ctx]
        (is (true? (git/inside-repo? ctx)))))))

(deftest worktree-list-includes-current-on-null-context
  ;; Tests worktree-list and current-worktree on a normal null context.
  (testing "worktree-list"
    (let [ctx       @shared-ro-ctx
          worktrees (git/worktree-list ctx)
          current   (git/current-worktree ctx)]
      (testing "lists at least one worktree"
        (is (seq worktrees)))
      (testing "identifies the current worktree"
        (is (map? current))
        (is (true? (:git.worktree/current? current))))
      (testing "marks one listed worktree as current"
        (is (some :git.worktree/current? worktrees))))))

(deftest worktree-list-outside-repo-is-empty
  ;; Tests worktree listing behavior outside a git repository.
  (testing "worktree-list"
    (let [tmp (str (Files/createTempDirectory "psi-no-git-"
                                              (make-array FileAttribute 0)))
          ctx (git/create-context tmp)]
      (testing "reports the context as outside a repo"
        (is (false? (git/inside-repo? ctx))))
      (testing "returns no worktrees"
        (is (= [] (git/worktree-list ctx))))
      (testing "returns no current worktree"
        (is (nil? (git/current-worktree ctx)))))))

(deftest worktree-list-command-failure-emits-telemetry-and-empty
  ;; Tests worktree-list failure handling and telemetry callback behavior.
  (testing "worktree-list"
    (let [ctx    @shared-ro-ctx
          warned (atom nil)]
      (with-redefs [psi.history.git/inside-repo? (fn [_] true)
                    psi.history.git/run-git (fn [_ _]
                                              (throw (ex-info "boom" {:type :boom})))
                    psi.history.git/emit-worktree-parse-failed!
                    (fn [_ctx e]
                      (reset! warned {:event "git.worktree.parse_failed"
                                      :error (ex-message e)}))]
        (testing "returns an empty worktree list on command failure"
          (is (= [] (git/worktree-list ctx))))
        (testing "emits parse-failure telemetry"
          (is (= "git.worktree.parse_failed" (:event @warned)))
          (is (= "boom" (:error @warned))))))))

(deftest worktree-list-marks-nested-cwd-as-current
  ;; Tests current worktree selection when cwd is nested inside a worktree path.
  (testing "worktree-list"
    (let [ctx        @shared-ro-ctx
          root       (:repo-dir ctx)
          nested     (str root File/separator "src")
          _          (.mkdirs (File. nested))
          nested-ctx (git/create-context nested)
          worktrees  (git/worktree-list nested-ctx)
          current    (git/current-worktree nested-ctx)]
      (testing "still lists worktrees from a nested cwd"
        (is (seq worktrees)))
      (testing "selects the containing worktree as current"
        (is (map? current))
        (is (true? (:git.worktree/current? current)))
        (is (= (canonical-path root)
               (canonical-path (:git.worktree/path current))))))))

;;; worktree and branch mutations

(deftest worktree-add-and-remove-roundtrip
  ;; Tests linked worktree creation, listing, and removal end-to-end.
  (testing "worktree add/remove"
    (let [ctx     (git/create-null-context seed-commits)
          wt-path (linked-worktree-path ctx "feature-alpha")
          added   (git/worktree-add ctx {:path wt-path
                                         :branch "feature-alpha"})
          listed  (git/worktree-list ctx)
          removed (git/worktree-remove ctx {:path wt-path})]
      (testing "adds the worktree successfully"
        (is (true? (:success added)))
        (is (= wt-path (:path added)))
        (is (= "feature-alpha" (:branch added)))
        (is (string? (:head added))))
      (testing "lists the added worktree"
        (is (worktree-listed? listed wt-path)))
      (testing "removes the worktree successfully"
        (is (true? (:success removed))))
      (testing "removes the worktree directory from disk"
        (is (false? (.exists (File. wt-path))))))))

(deftest worktree-add-fails-when-path-already-exists
  ;; Tests worktree-add path validation before invoking git.
  (testing "worktree-add"
    (testing "rejects an existing target path"
      (let [ctx     (git/create-null-context seed-commits)
            wt-path (linked-worktree-path ctx "existing-path")
            _       (.mkdirs (File. wt-path))
            result  (git/worktree-add ctx {:path wt-path
                                           :branch "feature-existing-path"})]
        (is (false? (:success result)))
        (is (= "worktree path already exists" (:error result)))))))

(deftest worktree-add-fails-when-branch-exists
  ;; Tests worktree-add branch validation when creating a new branch.
  (testing "worktree-add"
    (testing "rejects creating a worktree for an existing branch"
      (let [ctx     (git/create-null-context seed-commits)
            wt-path (linked-worktree-path ctx "main-dup")
            result  (git/worktree-add ctx {:path wt-path
                                           :branch "main"})]
        (is (false? (:success result)))
        (is (= "branch already exists" (:error result)))))))

(deftest worktree-add-supports-legacy-create-branch-key
  ;; Tests worktree-add compatibility with the legacy :create_branch request key.
  (testing "worktree-add"
    (testing "accepts the legacy create_branch flag"
      (let [ctx     (git/create-null-context seed-commits)
            wt-path (linked-worktree-path ctx "legacy-create-branch")
            result  (git/worktree-add ctx {:path wt-path
                                           :branch "legacy-create-branch"
                                           :create_branch true})]
        (is (true? (:success result)))
        (is (= "legacy-create-branch" (:branch result)))))))

(deftest worktree-add-with-existing-branch-fails-while-branch-is-checked-out-elsewhere
  ;; Tests worktree-add refusal when attaching to a branch already checked out elsewhere.
  (testing "worktree-add"
    (let [ctx     (git/create-null-context seed-commits)
          wt-src  (linked-worktree-path ctx "feature-attached-src")
          wt-path (linked-worktree-path ctx "feature-attached")
          _       (git/worktree-add ctx {:path wt-src :branch "feature-attached-src"})
          result  (git/worktree-add ctx {:path wt-path
                                         :branch "feature-attached-src"
                                         :create-branch false})
          listed  (git/worktree-list ctx)]
      (testing "fails when the target branch is already checked out elsewhere"
        (is (false? (:success result)))
        (is (string? (:error result))))
      (testing "does not add the rejected worktree"
        (is (not (worktree-listed? listed wt-path)))))))

(deftest worktree-add-with-existing-branch-succeeds-after-branch-is-no-longer-checked-out-elsewhere
  ;; Tests worktree-add success after the branch is detached from another worktree.
  (testing "worktree-add"
    (let [ctx     (git/create-null-context seed-commits)
          wt-src  (linked-worktree-path ctx "feature-attached-src")
          wt-path (linked-worktree-path ctx "feature-attached")
          _       (git/worktree-add ctx {:path wt-src :branch "feature-attached-src"})
          _       (git/worktree-remove ctx {:path wt-src})
          result  (git/worktree-add ctx {:path wt-path
                                         :branch "feature-attached-src"
                                         :create-branch false})
          listed  (git/worktree-list ctx)]
      (testing "succeeds once the branch is no longer checked out elsewhere"
        (is (true? (:success result)))
        (is (= wt-path (:path result)))
        (is (= "feature-attached-src" (:branch result))))
      (testing "adds the new worktree to the registry"
        (is (worktree-listed? listed wt-path))))))

(deftest worktree-remove-fails-when-path-is-unknown
  ;; Tests worktree-remove lookup behavior when the target path is absent.
  (testing "worktree-remove"
    (testing "rejects a path that is not a known worktree"
      (let [ctx    (git/create-null-context seed-commits)
            result (git/worktree-remove ctx {:path (linked-worktree-path ctx "missing")})]
        (is (false? (:success result)))
        (is (= "worktree path not found" (:error result)))))))

(deftest worktree-remove-fails-for-main-worktree
  ;; Tests worktree-remove protection of the primary worktree.
  (testing "worktree-remove"
    (testing "rejects removing the main worktree"
      (let [ctx    (git/create-null-context seed-commits)
            result (git/worktree-remove ctx {:path (:repo-dir ctx)})]
        (is (false? (:success result)))
        (is (= "cannot remove main worktree" (:error result)))))))

(deftest worktree-remove-command-failure-returns-error-map
  ;; Tests worktree-remove error reporting when git removal fails.
  (testing "worktree-remove"
    (testing "returns a failure map with the git error"
      (let [ctx     (git/create-null-context seed-commits)
            wt-path (linked-worktree-path ctx "remove-fail")
            _       (git/worktree-add ctx {:path wt-path :branch "remove-fail"})
            listed  [{:git.worktree/path wt-path}]]
        (with-redefs [psi.history.git/worktree-list (fn [_] listed)
                      psi.history.git/main-worktree-path (fn [_] "/definitely-not-the-target")
                      psi.history.git/run-git (fn [_ _]
                                                (throw (ex-info "remove failed" {:err "cannot remove now"})))]
          (let [result (git/worktree-remove ctx {:path wt-path})]
            (is (false? (:success result)))
            (is (= "cannot remove now" (:error result)))))))))

(deftest branch-merge-fast-forward
  ;; Tests branch-merge on a simple fast-forwardable feature branch.
  (testing "branch-merge"
    (let [ctx     (git/create-null-context seed-commits)
          wt-path (linked-worktree-path ctx "feature-merge")
          _       (git/worktree-add ctx {:path wt-path :branch "feature-merge"})
          _       (append-and-commit! wt-path "src/merge_feature.clj" "(ns merge-feature)\n" "⚒ feature merge commit")
          result  (git/branch-merge ctx {:branch "feature-merge"})
          files   (git/ls-files ctx)]
      (testing "reports a successful fast-forward merge"
        (is (true? (:merged result)))
        (is (true? (:fast-forward result)))
        (is (false? (:conflict result)))
        (is (nil? (:error result))))
      (testing "makes merged files visible on the target branch"
        (is (some #(= % "src/merge_feature.clj") files))))))

(deftest branch-merge-rejects-dirty-working-tree
  ;; Tests branch-merge precondition enforcement for dirty worktrees.
  (testing "branch-merge"
    (testing "rejects merge when the working tree is dirty"
      (let [ctx  (git/create-null-context seed-commits)
            file (File. (str (:repo-dir ctx) File/separator "README.md"))]
        (spit file "dirty\n")
        (let [result (git/branch-merge ctx {:branch "main"})]
          (is (false? (:merged result)))
          (is (= "working tree is dirty" (:error result))))))))

(deftest branch-merge-supports-ff-strategy
  ;; Tests branch-merge support for the explicit :ff strategy.
  (testing "branch-merge"
    (testing "supports the :ff strategy on a fast-forwardable branch"
      (let [ctx     (git/create-null-context seed-commits)
            wt-path (linked-worktree-path ctx "feature-merge-ff")
            _       (git/worktree-add ctx {:path wt-path :branch "feature-merge-ff"})
            _       (append-and-commit! wt-path "src/merge_ff.clj" "(ns merge-ff)\n" "⚒ feature merge ff")
            result  (git/branch-merge ctx {:branch "feature-merge-ff"
                                           :strategy :ff})]
        (is (true? (:merged result)))
        (is (true? (:fast-forward result)))
        (is (nil? (:error result)))))))

(deftest branch-merge-supports-no-ff-strategy
  ;; Tests branch-merge support for the explicit :no_ff strategy and merge commit message.
  (testing "branch-merge"
    (testing "supports the :no_ff strategy"
      (let [ctx     (git/create-null-context seed-commits)
            wt-path (linked-worktree-path ctx "feature-merge-no-ff")
            _       (git/worktree-add ctx {:path wt-path :branch "feature-merge-no-ff"})
            _       (append-and-commit! wt-path "src/merge_no_ff.clj" "(ns merge-no-ff)\n" "⚒ feature merge no ff")
            _       (append-and-commit! (:repo-dir ctx) "src/main_no_ff.clj" "(ns main-no-ff)\n" "⚒ main no ff base")
            result  (git/branch-merge ctx {:branch "feature-merge-no-ff"
                                           :strategy :no_ff
                                           :message "⚒ merge feature-merge-no-ff"})
            detail  (git/show ctx (git/current-commit ctx))]
        (is (true? (:merged result)))
        (is (false? (:fast-forward result)))
        (is (= "⚒ merge feature-merge-no-ff" (:git.commit/subject detail)))))))

(deftest branch-merge-conflict-path-reports-abort
  ;; Tests branch-merge conflict handling and abort reporting.
  (testing "branch-merge"
    (testing "reports conflict and aborts the in-progress merge"
      (let [ctx @shared-ro-ctx]
        (with-redefs [psi.history.git/dirty-working-tree? (fn [_] false)
                      psi.history.git/fast-forwardable? (fn [_ _] true)
                      psi.history.git/current-commit (let [calls (atom 0)]
                                                       (fn [_]
                                                         (if (zero? (swap! calls inc))
                                                           "before"
                                                           "after")))
                      psi.history.git/current-branch-name (fn [_] "main")
                      psi.history.git/branch-tip-merged-into-current? (fn [_ _] false)
                      psi.history.git/run-git (fn [_ _]
                                                (throw (ex-info "merge exploded" {})))
                      psi.history.git/merge-in-progress? (fn [_] true)
                      psi.history.git/run-git* (fn [_ _] {:out "" :err "" :exit 0})]
          (let [result (git/branch-merge ctx {:branch "feature-conflict"})]
            (is (false? (:merged result)))
            (is (true? (:conflict result)))
            (is (= "merge conflict; aborting" (:error result)))))))))

(deftest branch-merge-rejects-false-positive-success-when-run-on-source-branch
  ;; Tests branch-merge guard against reporting success when HEAD does not change.
  (testing "branch-merge"
    (let [ctx         (git/create-null-context seed-commits)
          wt-path     (linked-worktree-path ctx "feature-self")
          _           (git/worktree-add ctx {:path wt-path :branch "feature-self"})
          _           (append-and-commit! wt-path "src/feature_self.clj" "(ns feature-self)\n" "⚒ feature self commit")
          feature-ctx (git/create-context wt-path)
          result      (git/branch-merge feature-ctx {:branch "feature-self"})]
      (testing "does not report a merge when the target head did not change"
        (is (false? (:merged result)))
        (is (false? (:conflict result))))
      (testing "returns a diagnostic explaining the false positive"
        (is (re-find #"merge reported success but target HEAD did not absorb branch"
                     (:error result)))))))

(deftest branch-merge-ff-only-fails-when-not-fast-forwardable
  ;; Tests branch-merge ff-only behavior on diverged histories.
  (testing "branch-merge"
    (testing "rejects non-fast-forward merges under ff-only strategy"
      (let [ctx     (git/create-null-context seed-commits)
            wt-path (linked-worktree-path ctx "feature-diverged")
            _       (git/worktree-add ctx {:path wt-path :branch "feature-diverged"})
            _       (append-and-commit! wt-path "src/diverged_feature.clj" "(ns diverged-feature)\n" "⚒ feature diverged commit")
            _       (append-and-commit! (:repo-dir ctx) "src/main_side.clj" "(ns main-side)\n" "⚒ main side commit")
            result  (git/branch-merge ctx {:branch "feature-diverged"
                                           :strategy :ff_only})]
        (is (false? (:merged result)))
        (is (false? (:conflict result)))
        (is (= "not fast-forwardable; rebase first" (:error result)))))))

(deftest branch-rebase-success
  ;; Tests branch-rebase on a feature branch rebased onto main.
  (testing "branch-rebase"
    (testing "rebases the feature branch onto main"
      (let [ctx     (git/create-null-context seed-commits)
            wt-path (linked-worktree-path ctx "feature-rebase")
            _       (git/worktree-add ctx {:path wt-path :branch "feature-rebase"})
            _       (append-and-commit! wt-path "src/rebase_feature.clj" "(ns rebase-feature)\n" "⚒ feature before rebase")
            _       (append-and-commit! (:repo-dir ctx) "src/main_rebase.clj" "(ns main-rebase)\n" "⚒ main before rebase")
            wt-ctx  (git/create-context wt-path)
            result  (git/branch-rebase wt-ctx {:onto "main"})]
        (is (true? (:success result)))
        (is (= "feature-rebase" (:branch result)))
        (is (false? (:conflict result)))))))

(deftest branch-rebase-rejects-dirty-working-tree
  ;; Tests branch-rebase precondition enforcement for dirty worktrees.
  (testing "branch-rebase"
    (testing "rejects rebase when the working tree is dirty"
      (let [ctx  (git/create-null-context seed-commits)
            file (File. (str (:repo-dir ctx) File/separator "README.md"))]
        (spit file "dirty\n")
        (let [result (git/branch-rebase ctx {:onto "main"})]
          (is (false? (:success result)))
          (is (= "working tree is dirty" (:error result))))))))

(deftest branch-rebase-requires-target
  ;; Tests branch-rebase request validation for a missing target branch.
  (testing "branch-rebase"
    (testing "requires an onto target"
      (let [ctx    (git/create-null-context seed-commits)
            result (git/branch-rebase ctx {:onto nil})]
        (is (false? (:success result)))
        (is (= "missing rebase target" (:error result)))))))

(deftest branch-rebase-conflict-path-reports-abort
  ;; Tests branch-rebase conflict handling and abort reporting.
  (testing "branch-rebase"
    (testing "reports conflict and aborts the in-progress rebase"
      (let [ctx @shared-ro-ctx]
        (with-redefs [psi.history.git/status (fn [_] :clean)
                      psi.history.git/current-branch-name (fn [_] "feature-rebase-conflict")
                      psi.history.git/run-git (fn [_ _]
                                                (throw (ex-info "rebase exploded" {})))
                      psi.history.git/rebase-in-progress? (fn [_] true)
                      psi.history.git/run-git* (fn [_ _] {:out "" :err "" :exit 0})]
          (let [result (git/branch-rebase ctx {:onto "main"})]
            (is (false? (:success result)))
            (is (true? (:conflict result)))
            (is (= "rebase conflict; aborting" (:error result)))))))))

(deftest branch-delete-success
  ;; Tests branch-delete for a removable non-current branch.
  (testing "branch-delete"
    (testing "deletes a non-current local branch"
      (let [ctx     (git/create-null-context seed-commits)
            wt-path (linked-worktree-path ctx "feature-delete")
            _       (git/worktree-add ctx {:path wt-path :branch "feature-delete"})
            _       (git/worktree-remove ctx {:path wt-path})
            result  (git/branch-delete ctx {:branch "feature-delete"})]
        (is (true? (:deleted result)))
        (is (nil? (:error result)))))))

(deftest branch-delete-fails-when-branch-is-missing
  ;; Tests branch-delete validation for a missing branch.
  (testing "branch-delete"
    (testing "rejects deletion of a branch that does not exist"
      (let [ctx    (git/create-null-context seed-commits)
            result (git/branch-delete ctx {:branch "missing-branch"})]
        (is (false? (:deleted result)))
        (is (= "branch not found" (:error result)))))))

(deftest branch-delete-fails-for-current-branch
  ;; Tests branch-delete guard against deleting the checked-out branch.
  (testing "branch-delete"
    (testing "rejects deletion of the current branch"
      (let [ctx    (git/create-null-context seed-commits)
            result (git/branch-delete ctx {:branch "main"})]
        (is (false? (:deleted result)))
        (is (= "cannot delete current branch" (:error result)))))))

(deftest branch-delete-command-failure-uses-error-message
  ;; Tests branch-delete catch-path error shaping when git deletion fails.
  (testing "branch-delete"
    (testing "returns the exception message when ex-data has no :err"
      (let [ctx     (git/create-null-context seed-commits)
            wt-path (linked-worktree-path ctx "feature-delete-failure")
            _       (git/worktree-add ctx {:path wt-path :branch "feature-delete-failure"})
            _       (git/worktree-remove ctx {:path wt-path})]
        (with-redefs [psi.history.git/run-git (fn [_ _]
                                                (throw (ex-info "delete failed" {})))]
          (let [result (git/branch-delete ctx {:branch "feature-delete-failure"})]
            (is (false? (:deleted result)))
            (is (= "delete failed" (:error result)))))))))

(deftest default-branch-prefers-symbolic-ref
  ;; Tests default-branch resolution priority for origin/HEAD symbolic refs.
  (testing "default-branch"
    (testing "prefers the origin HEAD symbolic ref over config"
      (let [ctx @shared-ro-ctx]
        (with-redefs [psi.history.git/run-git* (fn [_ args]
                                                 (case args
                                                   ["symbolic-ref" "--short" "refs/remotes/origin/HEAD"] {:out "origin/trunk" :err "" :exit 0}
                                                   ["config" "--get" "init.defaultBranch"] {:out "main" :err "" :exit 0}))]
          (let [result (git/default-branch ctx)]
            (is (= "trunk" (:branch result)))
            (is (= :symbolic_ref (:source result)))))))))

(deftest default-branch-falls-back-to-config
  ;; Tests default-branch fallback to init.defaultBranch when no symbolic ref exists.
  (testing "default-branch"
    (testing "falls back to config when origin HEAD is unavailable"
      (let [ctx @shared-ro-ctx]
        (with-redefs [psi.history.git/run-git* (fn [_ args]
                                                 (case args
                                                   ["symbolic-ref" "--short" "refs/remotes/origin/HEAD"] {:out "" :err "missing" :exit 1}
                                                   ["config" "--get" "init.defaultBranch"] {:out "develop" :err "" :exit 0}))]
          (let [result (git/default-branch ctx)]
            (is (= "develop" (:branch result)))
            (is (= :config (:source result)))))))))

(deftest default-branch-falls-back-to-main
  ;; Tests default-branch terminal fallback when no higher-priority source resolves.
  (testing "default-branch"
    (testing "falls back to main when no symbolic ref or config is available"
      (let [ctx @shared-ro-ctx]
        (with-redefs [psi.history.git/run-git* (fn [_ args]
                                                 (case args
                                                   ["symbolic-ref" "--short" "refs/remotes/origin/HEAD"] {:out "" :err "missing" :exit 1}
                                                   ["config" "--get" "init.defaultBranch"] {:out "" :err "missing" :exit 1}))]
          (let [result (git/default-branch ctx)]
            (is (= "main" (:branch result)))
            (is (= :fallback (:source result)))))))))

;;; context isolation

(deftest two-null-contexts-are-independent
  ;; Tests null context isolation across independent temporary repositories.
  (testing "create-null-context isolation"
    (let [ctx-a (git/create-null-context [{:message "only in A"
                                           :files   {"a.txt" "a\n"}}])
          ctx-b (git/create-null-context [{:message "only in B"
                                           :files   {"b.txt" "b\n"}}])]
      (testing "ctx-a sees only its own commit history"
        (is (str/includes?
             (:git.commit/subject (first (git/log ctx-a {})))
             "only in A")))
      (testing "ctx-b sees only its own commit history"
        (is (str/includes?
             (:git.commit/subject (first (git/log ctx-b {})))
             "only in B")))
      (testing "ctx-a does not see ctx-b commits"
        (is (not (some #(str/includes? (:git.commit/subject %) "only in B")
                       (git/log ctx-a {}))))))))
