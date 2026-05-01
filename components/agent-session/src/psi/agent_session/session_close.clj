(ns psi.agent-session.session-close
  "Per-session close lifecycle helpers.

   Owns targeted session removal from the in-memory context, including
   scheduler timer cleanup, statechart working-memory cleanup, and projected
   active-session fallback.
   Persistence files are preserved; close is runtime detachment, not deletion."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.protocols :as sp]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session-state :as ss]))

(defn- next-active-session-id
  [ctx closing-session-id]
  (some->> (ss/list-context-sessions-in ctx)
           (remove #(= closing-session-id (:session-id %)))
           last
           :session-id))

(defn- interrupt-scheduler-timer!
  [ctx schedule-id]
  (when-let [timers* (:scheduler-timers* ctx)]
    (when-let [thread (get @timers* schedule-id)]
      (try
        (.interrupt ^Thread thread)
        (catch Exception _
          nil))
      (swap! timers* dissoc schedule-id)
      true)))

(defn- cancel-owned-schedules!
  [ctx session-id sd]
  (doseq [[schedule-id schedule] (get-in sd [:scheduler :schedules] {})]
    (when (contains? #{:pending :queued} (:status schedule))
      (dispatch/dispatch! ctx
                          :scheduler/cancel
                          {:session-id session-id
                           :schedule-id schedule-id}
                          {:origin :core})))
  true)

(defn- remove-session-state!
  [ctx session-id]
  (swap! (:state* ctx) update-in [:agent-session :sessions] dissoc session-id)
  true)

(defn close-session-in!
  "Detach a single session from the in-memory context.

   Effects:
   - cancels any pending/queued scheduler entries owned by the session
   - interrupts/removes any remaining timer handles for owned schedules
   - deletes the statechart working memory for the session's sc-session-id
   - removes the session runtime slot from canonical root state
   - emits a context-changed projection with the fallback active-session-id

   Idempotent: if `session-id` is not found in the sessions map, returns
   `{:closed? false :session-id sid}` without throwing. Callers do not need
   to guard against already-closed sessions.

   No phase guard: closing a non-idle session (streaming, compacting, retrying)
   is the caller's responsibility. This primitive does not check statechart
   phase before closing.

   Returns {:closed? bool :session-id sid :active-session-id sid-or-nil}."
  [ctx session-id]
  (if-let [sd (ss/get-session-data-in ctx session-id)]
    (let [sc-session-id      (ss/sc-session-id-in ctx session-id)
          owned-schedule-ids (->> (get-in sd [:scheduler :schedules] {})
                                  keys
                                  vec)
          _                  (cancel-owned-schedules! ctx session-id sd)
          _                  (doseq [schedule-id owned-schedule-ids]
                               (interrupt-scheduler-timer! ctx schedule-id))
          _                  (when sc-session-id
                               (let [sc-env (:sc-env ctx)]
                                 (sp/delete-working-memory!
                                  (::sc/working-memory-store sc-env)
                                  sc-env
                                  sc-session-id)))
          _                  (remove-session-state! ctx session-id)
          active-session-id  (next-active-session-id ctx session-id)]
      (dispatch/dispatch! ctx
                          :session/context-closed
                          {:session-id session-id
                           :active-session-id active-session-id}
                          {:origin :core})
      {:closed? true
       :session-id session-id
       :active-session-id active-session-id})
    {:closed? false :session-id session-id}))

(defn close-session-tree-in!
  "Close `session-id` and all its descendants.

   Obtains the full descendant list in bottom-up (leaf-first) order via
   `descendants-of-in`, calls `close-session-in!` on each descendant, then
   on the root. Idempotency of `close-session-in!` means already-closed
   sessions in the tree are handled gracefully.

   Each individual `close-session-in!` call emits its own
   `:session/context-closed` projection.

   Returns {:closed-count N :closed-session-ids [...]} where N counts only
   the sessions that were actually open at the time of close."
  [ctx session-id]
  (let [descendants (ss/descendants-of-in ctx session-id)
        all-ids     (conj descendants session-id)
        results     (mapv #(close-session-in! ctx %) all-ids)
        closed-ids  (->> results
                         (filter :closed?)
                         (mapv :session-id))]
    {:closed-count      (count closed-ids)
     :closed-session-ids closed-ids}))
