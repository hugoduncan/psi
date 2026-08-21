(ns psi.agent-session.introspection
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.state-kernel.dispatch :as kernel]
   [psi.agent-session.resolvers :as resolvers]
   [psi.session-state.model :as session]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.stream]
   [psi.agent-session.extension-workflow-runtime :as extension-workflow-runtime]))

(defn replay-dispatch-event-log-in!
  "Replay retained dispatch entries against this session context.

   Accepts either an explicit `entries` collection or, with one arity, replays
   the full retained dispatch event log in order. Effects are suppressed;
   only pure state application is performed.

   Returns the updated root state map."
  ([ctx]
   (replay-dispatch-event-log-in! ctx (kernel/event-log-entries)))
  ([ctx entries]
   (kernel/replay-event-log! dispatch/dispatch! ctx entries)
   @(:state* ctx)))

(defn diagnostics-in
  "Return a diagnostic snapshot map."
  [ctx session-id]
  (let [sd          (ss/get-session-data-in ctx session-id)
        phase       (ss/sc-phase-in ctx session-id)
        turn-ctx    (ss/get-state-value-in ctx (ss/state-path :turn-ctx session-id))
        stream-live? (boolean
                      (when-let [stream-handle (some-> turn-ctx :turn-data deref :stream-handle)]
                        (not (psi.turn-runtime.stream/cancelled-stream-handle? stream-handle))))]
    {:phase                   phase
     :session-id              session-id
     :is-idle                 (= phase :idle)
     :is-streaming            (= phase :streaming)
     :is-compacting           (= phase :compacting)
     :is-retrying             (= phase :retrying)
     :model                   (:model sd)
     :thinking-level          (:thinking-level sd)
     :pending-messages        (session/pending-message-count sd)
     :retry-attempt           (:retry-attempt sd)
     :retry-deadline-ms       (:retry-deadline-ms sd)
     :auto-retry-enabled      (:auto-retry-enabled sd)
     :auto-compaction-enabled (:auto-compaction-enabled sd)
     :context-fraction        (session/context-fraction-used sd)
     :extension-count         (ext/extension-count-in (:extension-registry ctx))
     :workflow-count          (extension-workflow-runtime/workflow-count-in (:workflow-registry ctx))
     :workflow-running-count  (extension-workflow-runtime/running-count-in (:workflow-registry ctx))
     :journal-entries         (count (ss/get-state-value-in ctx (ss/state-path :journal session-id)))
     :turn-stream-live?       stream-live?
     :agent-diagnostics       (agent/diagnostics-in (ss/agent-ctx-in ctx session-id))}))

(defn query-in
  "Run EQL `q` against `ctx` through the component's Pathom resolvers.
   Session-scoped queries require an explicit `session-id`; non-session/global
   attrs may be queried without one.

   Arity:
   - (query-in ctx q)
   - (query-in ctx q extra-entity)
   - (query-in ctx session-id q)
   - (query-in ctx session-id q extra-entity)"
  ([ctx q]
   (resolvers/query-in ctx q))
  ([ctx x y]
   (if (or (vector? x) (list? x))
     (resolvers/query-in ctx x y)
     (resolvers/query-in ctx y {:psi.agent-session/session-id x})))
  ([ctx session-id q extra-entity]
   (resolvers/query-in ctx q (assoc (or extra-entity {}) :psi.agent-session/session-id session-id))))
