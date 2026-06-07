(ns psi.agent-session.session-settings
  (:require
   [clojure.string :as str]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extension-runtime :as extension-runtime]
   [psi.shared-config.session-profiles :as session-profiles]
   [psi.session-state.model :as session]
   [psi.session-state.state :as ss]))

(defn set-model-in!
  "Set the session model for `session-id`.
   Optional `scope` forwards unchanged to `:session/set-model`."
  ([ctx session-id model]
   (dispatch/dispatch! ctx :session/set-model
                       {:session-id session-id :model model}
                       {:origin :core}))
  ([ctx session-id model scope]
   (dispatch/dispatch! ctx :session/set-model
                       (cond-> {:session-id session-id :model model}
                         scope (assoc :scope scope))
                       {:origin :core})))

(defn set-thinking-level-in!
  "Set the thinking level for `session-id`."
  [ctx session-id level]
  (dispatch/dispatch! ctx :session/set-thinking-level {:session-id session-id :level level} {:origin :core}))

(defn set-speed-mode-in!
  "Set the speed mode for `session-id`. Optional `scope` controls persistence."
  ([ctx session-id mode]
   (dispatch/dispatch! ctx :session/set-speed-mode {:session-id session-id :mode mode} {:origin :core}))
  ([ctx session-id mode scope]
   (dispatch/dispatch! ctx :session/set-speed-mode
                       (cond-> {:session-id session-id :mode mode}
                         scope (assoc :scope scope))
                       {:origin :core})))

(defn set-effort-override-in!
  "Set the effort override for `session-id`. Optional `scope` controls persistence."
  ([ctx session-id effort]
   (dispatch/dispatch! ctx :session/set-effort-override {:session-id session-id :effort effort} {:origin :core}))
  ([ctx session-id effort scope]
   (dispatch/dispatch! ctx :session/set-effort-override
                       (cond-> {:session-id session-id :effort effort}
                         scope (assoc :scope scope))
                       {:origin :core})))

(defn cycle-model-in!
  "Cycle to the next available scoped model for `session-id`."
  [ctx session-id direction]
  (let [sd         (ss/get-session-data-in ctx session-id)
        candidates (seq (:scoped-models sd))
        next-m     (when candidates
                     (session/next-model candidates (:model sd) direction))]
    (when next-m
      (set-model-in! ctx session-id next-m))
    (ss/get-session-data-in ctx session-id)))

(defn cycle-thinking-level-in!
  "Cycle to the next thinking level for `session-id`."
  [ctx session-id]
  (let [sd    (ss/get-session-data-in ctx session-id)
        model (:model sd)]
    (when (:reasoning model)
      (let [next-l (session/next-thinking-level (:thinking-level sd) model)]
        (set-thinking-level-in! ctx session-id next-l)))
    (ss/get-session-data-in ctx session-id)))

(defn- selected-profile-metadata
  [profile]
  {:name (:name profile)
   :settings (:settings profile)
   :readable-settings (session-profiles/readable-settings (:settings profile))})

(defn apply-session-profile-in!
  "Apply an already validated resolved profile to `session-id` atomically.

   The dispatch handler owns model-before-thinking ordering, transient speed and
   effort application, and selected-profile metadata storage."
  [ctx session-id profile]
  (dispatch/dispatch! ctx :session/apply-session-profile
                      {:session-id session-id
                       :profile (selected-profile-metadata profile)}
                      {:origin :core}))

(defn clear-session-profile-in!
  "Clear selected profile metadata without changing concrete session settings."
  [ctx session-id]
  (dispatch/dispatch! ctx :session/clear-session-profile
                      {:session-id session-id}
                      {:origin :core}))

(defn set-session-name-in!
  "Set the session name for `session-id`."
  [ctx session-id session-name]
  (dispatch/dispatch! ctx :session/set-session-name {:session-id session-id :name session-name} {:origin :core}))

(defn set-auto-compaction-in!
  "Enable or disable auto-compaction for `session-id`."
  [ctx session-id enabled?]
  (dispatch/dispatch! ctx :session/set-auto-compaction {:session-id session-id :enabled? enabled?} {:origin :core}))

(defn set-auto-retry-in!
  "Enable or disable auto-retry for `session-id`."
  [ctx session-id enabled?]
  (dispatch/dispatch! ctx :session/set-auto-retry {:session-id session-id :enabled? enabled?} {:origin :core}))

(defn cancel-job-in!
  "Cancel a background job for `session-id`.
   Returns the job map."
  [ctx session-id job-id reason]
  (let [schedule-id (cond
                      (str/starts-with? (str job-id) "schedule/")
                      (subs (str job-id) (count "schedule/"))

                      (str/starts-with? (str job-id) "sch-")
                      (str job-id)

                      :else nil)]
    (if schedule-id
      (dispatch/dispatch! ctx :scheduler/cancel
                          {:session-id session-id
                           :schedule-id schedule-id}
                          {:origin :core})
      (dispatch/dispatch! ctx :session/cancel-job
                          {:session-id session-id :job-id job-id :reason reason}
                          {:origin :core}))))

(defn- remember-provenance
  [ctx session-id]
  (let [worktree-path (ss/session-worktree-path-in ctx session-id)
        git-branch    (try
                        (:psi.agent-session/git-branch
                         ((requiring-resolve 'psi.agent-session.core/query-in)
                          ctx session-id [:psi.agent-session/git-branch]))
                        (catch Exception _
                          nil))]
    {:source       :remember
     :sessionId    session-id
     :cwd          worktree-path
     :worktreePath worktree-path
     :gitBranch    git-branch}))

(defn remember-in!
  "Capture a remember note for `session-id`.
   Returns the memory capture result."
  [ctx session-id text]
  (let [reason (or (not-empty (some-> text str/trim)) "manual /remember")]
    (dispatch/dispatch! ctx :session/remember
                        {:session-id  session-id
                         :text        reason
                         :memory-ctx  (:memory-ctx ctx)
                         :provenance  (remember-provenance ctx session-id)}
                        {:origin :core})))

(defn login-begin-in!
  "Begin OAuth login for `provider-id`.
   Returns the oauth begin-login result map."
  [ctx session-id provider-id]
  (dispatch/dispatch! ctx :session/login-begin
                      {:session-id session-id
                       :provider-id provider-id
                       :oauth-ctx (:oauth-ctx ctx)}
                      {:origin :core}))

(defn logout-in!
  "Logout all listed OAuth providers.
   Returns nil."
  [ctx session-id provider-ids]
  (dispatch/dispatch! ctx :session/logout
                      {:session-id session-id
                       :provider-ids provider-ids
                       :oauth-ctx (:oauth-ctx ctx)}
                      {:origin :core}))

(defn reload-models-in!
  "Reload user + project custom models from disk for `session-id`'s worktree path.
   Returns {:error string-or-nil :count int}."
  [ctx session-id]
  (dispatch/dispatch! ctx :session/reload-models {:session-id session-id} {:origin :core}))

(defn reload-prompts-in!
  "Re-discover prompt templates from disk for `session-id`'s worktree path and
   replace the session's `:prompt-templates`.
   Returns {:reloaded? bool :count int :worktree string}."
  [ctx session-id]
  (dispatch/dispatch! ctx :session/reload-prompts {:session-id session-id} {:origin :core}))

(defn reload-extension-installs-in!
  "Reload/apply extension installs for `session-id`'s worktree path.
   Returns the extension-runtime reload report including `:install-state`."
  [ctx session-id]
  (extension-runtime/reload-extensions-in! ctx session-id [] (ss/session-worktree-path-in ctx session-id)))
