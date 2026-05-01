(ns psi.agent-session.core
  "AgentSession public façade.

   This namespace preserves the existing public API surface while delegating
   implementation to focused namespaces for context creation, prompting,
   settings, compaction, introspection, and lifecycle operations."
  (:require
   [psi.agent-session.compaction-runtime :as compaction-runtime]
   [psi.agent-session.context :as context]
   [psi.agent-session.introspection :as introspection]
   [psi.agent-session.prompt-control :as prompt-control]
   [psi.agent-session.session-lifecycle :as lifecycle]
   [psi.agent-session.session-settings :as settings]))

(defn register-resolvers-in!
  ([qctx] (context/register-resolvers-in! qctx))
  ([qctx rebuild?] (context/register-resolvers-in! qctx rebuild?)))

(defn register-mutations-in!
  ([qctx mutations] (context/register-mutations-in! qctx mutations))
  ([qctx mutations rebuild?] (context/register-mutations-in! qctx mutations rebuild?)))

(defn register-resolvers!
  []
  (context/register-resolvers!))

(defn register-mutations!
  [mutations]
  (context/register-mutations! mutations))

(defn create-context
  ([] (context/create-context))
  ([opts] (context/create-context opts)))

(defn shutdown-context!
  [ctx]
  (context/shutdown-context! ctx))

(defn new-session-in!
  [ctx source-session-id opts]
  (lifecycle/new-session-in! ctx source-session-id opts))

(defn create-top-level-session-in!
  [ctx source-session-id opts]
  (lifecycle/create-top-level-session-in! ctx source-session-id opts))

(defn resume-session-in!
  [ctx source-session-id session-path]
  (lifecycle/resume-session-in! ctx source-session-id session-path))

(defn fork-session-in!
  [ctx parent-session-id entry-id]
  (lifecycle/fork-session-in! ctx parent-session-id entry-id))

(defn ensure-session-loaded-in!
  [ctx source-session-id session-id]
  (lifecycle/ensure-session-loaded-in! ctx source-session-id session-id))

(defn close-session-in!
  [ctx session-id]
  (lifecycle/close-session-in! ctx session-id))

(defn close-session-tree-in!
  [ctx root-id]
  (lifecycle/close-session-tree-in! ctx root-id))

(defn prompt-in!
  ([ctx session-id text]
   (prompt-control/prompt-in! ctx session-id text))
  ([ctx session-id text images]
   (prompt-control/prompt-in! ctx session-id text images))
  ([ctx session-id text images opts]
   (prompt-control/prompt-in! ctx session-id text images opts)))

(defn last-assistant-message-in
  [ctx session-id]
  (prompt-control/last-assistant-message-in ctx session-id))

(defn steer-in!
  [ctx session-id text]
  (prompt-control/steer-in! ctx session-id text))

(defn follow-up-in!
  [ctx session-id text]
  (prompt-control/follow-up-in! ctx session-id text))

(defn queue-while-streaming-in!
  [ctx session-id text behavior]
  (prompt-control/queue-while-streaming-in! ctx session-id text behavior))

(defn request-interrupt-in!
  [ctx session-id]
  (prompt-control/request-interrupt-in! ctx session-id))

(defn abort-in!
  [ctx session-id]
  (prompt-control/abort-in! ctx session-id))

(defn consume-queued-input-text-in!
  [ctx session-id]
  (prompt-control/consume-queued-input-text-in! ctx session-id))

(defn set-model-in!
  [ctx session-id model]
  (settings/set-model-in! ctx session-id model))

(defn set-thinking-level-in!
  [ctx session-id level]
  (settings/set-thinking-level-in! ctx session-id level))

(defn cycle-model-in!
  [ctx session-id direction]
  (settings/cycle-model-in! ctx session-id direction))

(defn cycle-thinking-level-in!
  [ctx session-id]
  (settings/cycle-thinking-level-in! ctx session-id))

(defn set-session-name-in!
  [ctx session-id session-name]
  (settings/set-session-name-in! ctx session-id session-name))

(defn set-auto-compaction-in!
  [ctx session-id enabled?]
  (settings/set-auto-compaction-in! ctx session-id enabled?))

(defn set-auto-retry-in!
  [ctx session-id enabled?]
  (settings/set-auto-retry-in! ctx session-id enabled?))

(defn cancel-job-in!
  "Cancel a background job. Returns the job map."
  [ctx session-id job-id reason]
  (settings/cancel-job-in! ctx session-id job-id reason))

(defn remember-in!
  "Capture a remember note. Returns the memory capture result."
  [ctx session-id text]
  (settings/remember-in! ctx session-id text))

(defn login-begin-in!
  "Begin OAuth login. Returns the oauth begin-login result map."
  [ctx session-id provider-id]
  (settings/login-begin-in! ctx session-id provider-id))

(defn logout-in!
  "Logout the given OAuth providers."
  [ctx session-id provider-ids]
  (settings/logout-in! ctx session-id provider-ids))

(defn reload-models-in!
  "Reload user + project custom models from disk for the session worktree path.
   Returns {:error string-or-nil :count int}."
  [ctx session-id]
  (settings/reload-models-in! ctx session-id))

(defn reload-extension-installs-in!
  "Reload/apply extension installs for the session worktree path.
   Returns the extension-runtime reload report including `:install-state`."
  [ctx session-id]
  (settings/reload-extension-installs-in! ctx session-id))

(defn execute-compaction-in!
  [ctx session-id custom-instructions]
  (compaction-runtime/execute-compaction-in! ctx session-id custom-instructions))

(defn manual-compact-in!
  [ctx session-id custom-instructions]
  (compaction-runtime/manual-compact-in! ctx session-id custom-instructions))

(defn replay-dispatch-event-log-in!
  ([ctx] (introspection/replay-dispatch-event-log-in! ctx))
  ([ctx entries] (introspection/replay-dispatch-event-log-in! ctx entries)))

(defn diagnostics-in
  [ctx session-id]
  (introspection/diagnostics-in ctx session-id))

(defn query-in
  ([ctx q]
   (introspection/query-in ctx q))
  ([ctx x y]
   (introspection/query-in ctx x y))
  ([ctx session-id q extra-entity]
   (introspection/query-in ctx session-id q extra-entity)))
