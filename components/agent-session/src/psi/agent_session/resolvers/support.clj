(ns psi.agent-session.resolvers.support
  "Shared helpers for agent-session resolver namespaces.

   Provides explicit session-scoped data access and EQL projection utilities
   that domain-specific resolver namespaces depend on."
  (:require
   [psi.agent-core.core :as agent]
   [psi.session-state.state :as session]))

(defn session-data
  "Get session data for an explicit session-id.

   Fails explicitly when the session-id is missing or unknown so targeted
   introspection cannot silently collapse to some other ambient session."
  [agent-session-ctx session-id]
  (when-not (seq session-id)
    (throw (ex-info "session-scoped resolver requires :psi.agent-session/session-id"
                    {:session-id session-id
                     :callback :session-data})))
  (or (session/get-session-data-in agent-session-ctx session-id)
      (throw (ex-info "unknown session-id for session-scoped resolver"
                      {:session-id session-id
                       :callback :session-data}))))

(defn session-worktree-path
  "Get the required worktree-path for an explicit session-id."
  [agent-session-ctx session-id]
  (or (:worktree-path (session-data agent-session-ctx session-id))
      (throw (ex-info "session is missing required :worktree-path"
                      {:session-id session-id
                       :callback :session-worktree-path}))))

(defn agent-data
  "Get agent-core data for an explicit session-id."
  [agent-session-ctx session-id]
  (agent/get-data-in (session/agent-ctx-in agent-session-ctx session-id)))

(defn agent-core-messages
  "Extract the message vec from agent-core inside a session context."
  [agent-session-ctx session-id]
  (:messages (agent-data agent-session-ctx session-id)))

(defn contribution->attrs
  "Project a prompt contribution map to :psi.extension.prompt-contribution/* attributes."
  [c]
  {:psi.extension.prompt-contribution/id         (:id c)
   :psi.extension.prompt-contribution/ext-path   (:ext-path c)
   :psi.extension.prompt-contribution/section    (:section c)
   :psi.extension.prompt-contribution/content    (:content c)
   :psi.extension.prompt-contribution/priority   (:priority c)
   :psi.extension.prompt-contribution/enabled    (:enabled c)
   :psi.extension.prompt-contribution/created-at (:created-at c)
   :psi.extension.prompt-contribution/updated-at (:updated-at c)})
