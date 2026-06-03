(ns psi.agent-session.extensions.runtime-eql
  (:require
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.session-state.state :as ss]
   [psi.history.git :as history-git]
   [psi.query.core :as query]))

(defn- ctx-all-mutations
  "Read the current all-mutations from ctx.
   Uses :all-mutations-atom when present (updated by reload-code) so that
   new mutations from reloaded namespaces are visible."
  [ctx]
  (if-let [a (:all-mutations-atom ctx)]
    @a
    (:all-mutations ctx)))

(def ^:private session-scoped-extension-mutation-ops
  #{'psi.extension/set-session-name
    'psi.extension/set-active-tools
    'psi.extension/set-model
    'psi.extension/set-worktree-path
    'psi.extension/create-child-session
    'psi.extension/set-rpc-trace
    'psi.extension/interrupt
    'psi.extension/compact
    'psi.extension/append-entry
    'psi.extension/register-prompt-contribution
    'psi.extension/update-prompt-contribution
    'psi.extension/unregister-prompt-contribution
    'psi.extension/set-allowed-events
    'psi.extension.tool/read
    'psi.extension.tool/bash
    'psi.extension.tool/write
    'psi.extension.tool/update
    'psi.extension/run-tool-plan
    'psi.extension.tool/chain
    'psi.extension/notify
    'psi.extension/append-message
    'psi.extension/inject-mid-system-message
    'psi.extension/send-prompt
    'psi.extension/schedule-event
    ;; Workflow run creation: the invoking session must be injected as
    ;; `:session-id` so `create-workflow-run` can snapshot the invoking
    ;; session's inherited defaults at invoke time (task 207). The top-level
    ;; invoke (`workflow/core.clj`) and terminal-run continuation
    ;; (`continue-terminal-run-async!`) call `mutate! 'psi.workflow/create-run`
    ;; with no explicit `:session-id`, relying on this injection (Decision 5b /
    ;; 6a). Without it the snapshot is never captured on those paths.
    'psi.workflow/create-run})

(def ^:private lifecycle-extension-mutation-param-builders
  {'psi.extension/create-session
   (fn [session-id params]
     (assoc params :parent-session-id session-id))

   'psi.extension/switch-session
   (fn [session-id params]
     (assoc params :source-session-id session-id))})

(defn run-extension-mutation-in!
  "Execute a single EQL mutation op against `ctx` and return its payload.
   `op-sym` must be a qualified mutation symbol.

   `session-id` is the invoking session used for runtime seed context. For
   session-scoped mutations, an explicit `:session-id` already present in
   `params` remains authoritative so callers can intentionally target a
   different session than the invoker (for example close-session admin flows)."
  [ctx session-id op-sym params]
  (let [register-resolvers! (:register-resolvers-fn ctx)
        register-mutations! (:register-mutations-fn ctx)
        qctx                (query/create-query-context)
        _                   (register-resolvers! qctx false)
        _                   (register-mutations! qctx (ctx-all-mutations ctx) true)
        git-ctx             (history-git/create-context (ss/session-worktree-path-in ctx session-id))
        seed                {:psi/agent-session-ctx ctx
                             :git/context git-ctx}
        full-params         (let [base-params (assoc params
                                                     :psi/agent-session-ctx ctx
                                                     :git/context git-ctx)]
                              (cond
                                (contains? session-scoped-extension-mutation-ops op-sym)
                                (if (contains? base-params :session-id)
                                  base-params
                                  (assoc base-params :session-id session-id))

                                (contains? lifecycle-extension-mutation-param-builders op-sym)
                                ((get lifecycle-extension-mutation-param-builders op-sym) session-id base-params)

                                :else
                                base-params))
        payload             (get (query/query-in qctx seed [(list op-sym full-params)]) op-sym)]
    (bg-rt/maybe-track-background-workflow-job! ctx session-id op-sym full-params payload)
    payload))

(defn query-extension-state
  [register-resolvers! register-mutations! ctx session-id eql-query]
  (let [qctx (query/create-query-context)
        _    (register-resolvers! qctx false)
        _    (register-mutations! qctx (ctx-all-mutations ctx) true)]
    (query/query-in qctx
                    {:psi/agent-session-ctx        ctx
                     :psi.agent-session/session-id session-id}
                    eql-query)))
