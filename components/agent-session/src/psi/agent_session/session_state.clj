(ns psi.agent-session.session-state
  "Compatibility wrapper over `psi.session-state.state`.

   Canonical lower-level session-state ownership now lives in
   `components/session-state/`. Keep this namespace temporarily so caller
   migration can proceed incrementally."
  (:require
   [psi.session-state.state :as state]))

(def agent-ctx-in state/agent-ctx-in)
(def sc-session-id-in state/sc-session-id-in)
(def session-data-path state/session-data-path)
(def session-telemetry-path state/session-telemetry-path)
(def session-journal-path state/session-journal-path)
(def session-flush-state-path state/session-flush-state-path)
(def session-turn-ctx-path state/session-turn-ctx-path)
(def session-scheduler-path state/session-scheduler-path)
(def session-scheduler-schedules-path state/session-scheduler-schedules-path)
(def session-scheduler-queue-path state/session-scheduler-queue-path)
(def state-path state/state-path)
(def get-state-value-in state/get-state-value-in)
(def assoc-state-value-in! state/assoc-state-value-in!)
(def update-state-value-in! state/update-state-value-in!)
(def get-session-data-in state/get-session-data-in)
(def session-update state/session-update)
(def apply-root-state-update-in! state/apply-root-state-update-in!)
(def session-worktree-path-in state/session-worktree-path-in)
(def journal-append-in! state/journal-append-in!)
(def get-sessions-map-in state/get-sessions-map-in)
(def list-context-sessions-in state/list-context-sessions-in)
(def sc-phase-in state/sc-phase-in)
(def idle-in? state/idle-in?)
(def sorted-prompt-contributions state/sorted-prompt-contributions)
(def list-prompt-contributions-in state/list-prompt-contributions-in)
(def children-of-in state/children-of-in)
(def descendants-of-in state/descendants-of-in)
