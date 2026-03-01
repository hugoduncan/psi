(ns psi.recursion.future-state
  "FUTURE_STATE snapshot lifecycle: creation, versioning, and goal management.

   Pure functions only — no side effects or atoms."
  (:require
   [psi.recursion.policy :as policy]))

(defn initial-future-state
  "Return a version-0 FUTURE_STATE snapshot with empty goals."
  []
  {:version 0
   :generated-at (java.time.Instant/now)
   :horizon :medium
   :goals []
   :assumptions #{}
   :stop-conditions #{}})

(defn next-version
  "Increment version and update generated-at timestamp on `snapshot`."
  [snapshot]
  (-> snapshot
      (update :version inc)
      (assoc :generated-at (java.time.Instant/now))))

(defn advance-goals
  "Mark goals in `completed-goal-ids` as :complete. Returns new snapshot
   with incremented version."
  [snapshot completed-goal-ids]
  (let [ids (set completed-goal-ids)]
    (-> snapshot
        (update :goals
                (fn [goals]
                  (mapv (fn [g]
                          (if (contains? ids (:id g))
                            (assoc g :status :complete)
                            g))
                        goals)))
        next-version)))

(defn add-blockers
  "Add blocker hypotheses from `evidence` set to snapshot.
   Creates new :blocked goals from evidence strings. Returns new snapshot
   with incremented version."
  [snapshot evidence]
  (let [new-goals (mapv (fn [e]
                          {:id (str "goal-" (hash e))
                           :title (str "Investigate: " e)
                           :description (str "Blocker discovered: " e)
                           :priority :high
                           :success-criteria #{}
                           :constraints #{}
                           :status :blocked})
                        evidence)]
    (-> snapshot
        (update :goals into new-goals)
        next-version)))

(defn valid?
  "Check if `snapshot` conforms to the FutureStateSnapshot schema."
  [snapshot]
  (policy/valid-future-state? snapshot))
