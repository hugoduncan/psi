(ns psi.agent-session.compaction-runtime
  (:require
   [psi.agent-session.compaction :as compaction]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-control :as prompt-control]
   [psi.session-state.state :as ss]))

(defn execute-compaction-in!
  "Execute a compaction cycle: prepare → dispatch before-compact → run
  compaction-fn → append entry → rebuild agent messages → dispatch compact.
  Returns the CompactionResult, or nil when cancelled or no-op."
  [ctx session-id custom-instructions]
  (let [sd          (ss/get-session-data-in ctx session-id)
        keep-recent (get-in ctx [:config :auto-compaction-keep-recent-tokens] 20000)
        preparation (compaction/prepare-compaction sd keep-recent)
        reg         (:extension-registry ctx)]
    (when preparation
      (let [{:keys [cancelled? override override-present?]}
            (ext/dispatch-in reg "session_before_compact"
                             {:preparation         preparation
                              :branch-entries      (:session-entries sd)
                              :custom-instructions custom-instructions})]
        (when-not cancelled?
          (let [from-extension? (boolean override-present?)
                result          (if override-present?
                                  override
                                  ((:compaction-fn ctx) sd preparation custom-instructions))
                entry           (persist/compaction-entry result from-extension?)
                new-msgs        (compaction/rebuild-messages-from-entries result sd)]
            (ss/journal-append-in! ctx session-id entry)
            (dispatch/dispatch! ctx :session/compaction-finished
                                {:session-id session-id :messages new-msgs} {:origin :core})
            (ext/dispatch-in reg "session_compact"
                             {:compaction-entry entry
                              :from-extension  from-extension?})
            result))))))

(defn manual-compact-in!
  "User-triggered compaction. Aborts agent if running, then compacts through
   a dispatch-shaped vertical slice:
   compact-start -> manual-compaction-execute -> compact-done.

   The execution step remains an intentional synchronous dispatch handler so the
   caller can still receive the compaction result directly."
  [ctx session-id custom-instructions]
  (when-not (ss/idle-in? ctx session-id)
    (prompt-control/abort-in! ctx session-id))
  (dispatch/dispatch! ctx :session/compact-start {:session-id session-id} {:origin :core})
  (let [result (dispatch/dispatch! ctx
                                   :session/manual-compaction-execute
                                   {:session-id session-id :custom-instructions custom-instructions}
                                   {:origin :core})]
    (dispatch/dispatch! ctx :session/compact-done {:session-id session-id} {:origin :core})
    result))
