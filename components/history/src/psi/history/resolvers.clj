(ns psi.history.resolvers
  "Pathom3 resolvers for the git-backed History component.

   Domain entities from the Allium spec:
     GitRepository  — repo status, current commit, file list, has_history
     GitCommit      — sha, date, author, subject, symbols, derived flags
     TrackedFile    — file paths in the repo
     GitGrep        — content search results

   Resolvers accept :git/context (a GitContext record) in the EQL input.
   Tests inject a null context (isolated temp repo) via `create-null-context`
   — no mocking, no shared state, full Nullable pattern."
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.history.git :as git]))

;;; GitRepository resolvers

(pco/defresolver git-repo-status
  "Resolve :git.repo/status, :git.repo/current-commit, :git.repo/has-changes."
  [{:keys [git/context]}]
  {::pco/input  [:git/context]
   ::pco/output [:git.repo/status
                 :git.repo/current-commit
                 :git.repo/has-changes]}
  (let [status (git/status context)
        sha    (try (git/current-commit context) (catch Exception _ nil))]
    {:git.repo/status         status
     :git.repo/current-commit sha
     :git.repo/has-changes    (contains? #{:modified :staged} status)}))

(pco/defresolver git-repo-commits
  "Resolve :git.repo/commits and :git.repo/has-history.

   Accepts optional EQL params:
     :n    — number of commits (default 50)
     :grep — filter by message text
     :path — restrict to a file/directory

   Returns empty results when the git context is not inside a repository."
  [env {:keys [git/context]}]
  {::pco/input  [:git/context]
   ::pco/output [:git.repo/commits
                 :git.repo/has-history]}
  (let [params  (pco/params env)
        commits (try (vec (git/log context params))
                     (catch Exception _ []))]
    {:git.repo/commits     commits
     :git.repo/has-history (pos? (count commits))}))

(pco/defresolver git-repo-files
  "Resolve :git.repo/files — seq of tracked file path strings.
   Returns empty when the git context is not inside a repository."
  [{:keys [git/context]}]
  {::pco/input  [:git/context]
   ::pco/output [:git.repo/files]}
  {:git.repo/files (try (vec (git/ls-files context))
                        (catch Exception _ []))})

;;; GitCommit resolvers

(pco/defresolver git-commit-detail
  "Resolve full commit detail from :git.commit/sha.
   Returns body, stat, diff and all base fields + derived flags."
  [{:keys [git/context git.commit/sha]}]
  {::pco/input  [:git/context :git.commit/sha]
   ::pco/output [:git.commit/date
                 :git.commit/author
                 :git.commit/email
                 :git.commit/subject
                 :git.commit/body
                 :git.commit/stat
                 :git.commit/diff
                 :git.commit/symbols
                 :git.commit/is-learning
                 :git.commit/is-delta]}
  (let [detail (git/show context sha)]
    (assoc detail
           :git.commit/is-learning (contains? (:git.commit/symbols detail) "λ")
           :git.commit/is-delta    (contains? (:git.commit/symbols detail) "Δ"))))

(pco/defresolver git-commit-derived
  "Compute :git.commit/is-learning and :git.commit/is-delta
   from already-resolved :git.commit/symbols (e.g. from log output)."
  [{:keys [git.commit/symbols]}]
  {::pco/input  [:git.commit/symbols]
   ::pco/output [:git.commit/is-learning
                 :git.commit/is-delta]}
  {:git.commit/is-learning (contains? symbols "λ")
   :git.commit/is-delta    (contains? symbols "Δ")})

;;; GitGrep resolver

(pco/defresolver git-grep
  "Resolve :git.repo/grep-results from :git.repo/grep-pattern.

   Optional EQL params:
     :path — restrict to file/directory
     :n    — max results (default 200)"
  [env {:keys [git/context git.repo/grep-pattern]}]
  {::pco/input  [:git/context :git.repo/grep-pattern]
   ::pco/output [:git.repo/grep-results]}
  (let [params  (pco/params env)
        results (vec (git/grep context grep-pattern params))]
    {:git.repo/grep-results results}))

;;; Learning commits (QueryHistory from spec — filters by λ symbol)

(pco/defresolver git-learning-commits
  "Resolve :git.repo/learning-commits — commits containing the λ symbol.
   Implements the QueryHistory surface from the Allium spec.
   Returns empty when the git context is not inside a repository."
  [{:keys [git/context]}]
  {::pco/input  [:git/context]
   ::pco/output [:git.repo/learning-commits]}
  {:git.repo/learning-commits (try (vec (git/log context {:grep "λ"}))
                                   (catch Exception _ []))})

;;; Git worktree resolvers

(pco/defresolver git-worktree-list
  "Resolve :git.worktree/list for the current git context.
   Returns [] outside git repositories or on parse failure."
  [{:keys [git/context]}]
  {::pco/input  [:git/context]
   ::pco/output [:git.worktree/list]}
  {:git.worktree/list (vec (git/worktree-list context))})

(pco/defresolver git-worktree-current
  "Resolve :git.worktree/current for the current git context.
   Returns nil outside git repositories or when no current worktree found."
  [{:keys [git/context]}]
  {::pco/input  [:git/context]
   ::pco/output [:git.worktree/current]}
  {:git.worktree/current (git/current-worktree context)})

(pco/defresolver git-worktree-count
  "Resolve :git.worktree/count as count of :git.worktree/list."
  [{:keys [git.worktree/list]}]
  {::pco/input  [:git.worktree/list]
   ::pco/output [:git.worktree/count]}
  {:git.worktree/count (count list)})

(pco/defresolver git-worktree-inside-repo
  "Resolve :git.worktree/inside-repo? for the current git context."
  [{:keys [git/context]}]
  {::pco/input  [:git/context]
   ::pco/output [:git.worktree/inside-repo?]}
  {:git.worktree/inside-repo? (boolean (git/inside-repo? context))})

(pco/defresolver git-default-branch
  "Resolve :git.branch/default-branch for the current git context."
  [{:keys [git/context]}]
  {::pco/input  [:git/context]
   ::pco/output [:git.branch/default-branch]}
  {:git.branch/default-branch (git/default-branch context)})

;;; Git mutations

(pco/defmutation git-worktree-add!
  [_ {:keys [git/context input]}]
  {::pco/op-name 'git.worktree/add!
   ::pco/params  [:git/context :input]
   ::pco/output  [:path :branch :head :success :error]}
  (git/worktree-add context input))

(pco/defmutation git-worktree-remove!
  [_ {:keys [git/context input]}]
  {::pco/op-name 'git.worktree/remove!
   ::pco/params  [:git/context :input]
   ::pco/output  [:path :success :error]}
  (git/worktree-remove context input))

(pco/defmutation git-branch-merge!
  [_ {:keys [git/context input]}]
  {::pco/op-name 'git.branch/merge!
   ::pco/params  [:git/context :input]
   ::pco/output  [:branch :merged :fast-forward :conflict :error]}
  (git/branch-merge context input))

(pco/defmutation git-branch-delete!
  [_ {:keys [git/context input]}]
  {::pco/op-name 'git.branch/delete!
   ::pco/params  [:git/context :input]
   ::pco/output  [:branch :deleted :error]}
  (git/branch-delete context input))

(pco/defmutation git-branch-rebase!
  [_ {:keys [git/context input]}]
  {::pco/op-name 'git.branch/rebase!
   ::pco/params  [:git/context :input]
   ::pco/output  [:onto :branch :success :conflict :error]}
  (git/branch-rebase context input))

(pco/defmutation git-branch-default
  [_ {:keys [git/context]}]
  {::pco/op-name 'git.branch/default
   ::pco/params  [:git/context]
   ::pco/output  [:branch :source]}
  (git/default-branch context))

;;; All resolvers/mutations

(def all-resolvers
  [git-repo-status
   git-repo-commits
   git-repo-files
   git-commit-detail
   git-commit-derived
   git-grep
   git-learning-commits
   git-worktree-list
   git-worktree-current
   git-worktree-count
   git-worktree-inside-repo
   git-default-branch])

(def all-mutations
  [git-worktree-add!
   git-worktree-remove!
   git-branch-merge!
   git-branch-delete!
   git-branch-rebase!
   git-branch-default])
