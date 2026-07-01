(ns psi.history.git-test
  "Tests for psi.history.git.

   Uses create-null-context — an isolated temp git repo with seeded commits.
   No mocks, no dependency on the real project repo, no shared state.

   Worktree parsing, worktree mutations, branch operations, and context
   isolation tests live in git_worktree_test.clj (split to satisfy the
   file-length commit check)."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [psi.history.git :as git])
  (:import
   (java.io File)))

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

(use-fixtures :once
  (fn [f]
    (try
      (f)
      (finally
        (fs/delete-tree (:repo-dir @shared-ro-ctx))))))

(defn- delete-recursively!
  [path]
  (let [f (File. (str path))]
    (when (.exists f)
      (doseq [child (reverse (file-seq f))]
        (.delete ^java.io.File child)))))

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
      (try
        (testing "creates a usable git repository"
          (is (true? (git/inside-repo? ctx))))
        (testing "seeds the default commit set"
          (is (= 3 (count commits))))
        (finally
          (delete-recursively! (:repo-dir ctx)))))))

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

(deftest status-parses-porcelain-xy-slots
  ;; Tests status classification from porcelain XY slots, not path substrings.
  (testing "status"
    (testing "reports untracked paths as modified even when path text contains staged markers"
      (let [ctx (git/create-null-context seed-commits)]
        (try
          (spit (File. (str (:repo-dir ctx) File/separator "M misleading.txt"))
                "untracked\n")
          (is (= :modified (git/status ctx)))
          (finally
            (delete-recursively! (:repo-dir ctx))))))
    (testing "reports index changes as staged from the porcelain index slot"
      (let [ctx (git/create-null-context seed-commits)]
        (try
          (spit (File. (str (:repo-dir ctx) File/separator "staged.txt"))
                "staged\n")
          (#'psi.history.git/run-git ctx ["add" "staged.txt"])
          (is (= :staged (git/status ctx)))
          (finally
            (delete-recursively! (:repo-dir ctx))))))))

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

