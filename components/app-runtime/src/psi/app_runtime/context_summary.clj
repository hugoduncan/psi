(ns psi.app-runtime.context-summary
  "Adapter-neutral public summaries derived from canonical context snapshots."
  (:require
   [clojure.string :as str]))

(defn session-tree-line-label
  [slot]
  (let [item-kind  (or (:item-kind slot) "session")
        base-name  (or (:display-name slot)
                       (:session-display-name slot)
                       (:name slot)
                       (:session-name slot)
                       (when-let [id (:id slot)]
                         (format "(session %s)" (subs id 0 (min 8 (count id))))))
        short-id   (when (and (= item-kind "session") (string? (:id slot)))
                     (subs (:id slot) 0 (min 8 (count (:id slot)))))
        created-at (:created-at slot)
        updated-at (:updated-at slot)
        worktree   (some-> (:worktree-path slot) str/trim not-empty)
        time-label (let [parse-ts (fn [ts]
                                    (when (string? ts)
                                      (try (java.time.Instant/parse ts)
                                           (catch Throwable _ nil))))
                         fmt      (fn [^java.time.Instant inst]
                                    (when inst
                                      (.format (java.time.format.DateTimeFormatter/ofPattern "HH:mm")
                                               (.atZone inst (java.time.ZoneId/systemDefault)))))
                         start    (fmt (parse-ts created-at))
                         changed  (fmt (parse-ts updated-at))]
                     (cond
                       (and start changed) (str start " / " changed)
                       start start
                       changed changed
                       :else nil))]
    (str (when (= item-kind "fork-point") "⎇ ")
         base-name
         (when (and short-id (not (str/blank? short-id)))
           (str " [" short-id "]"))
         (when time-label
           (str " — " time-label))
         (when worktree
           (str " — " worktree)))))

(defn- runtime-state-badge
  [slot]
  (when (= "session" (or (:item-kind slot) "session"))
    (some-> (:runtime-state slot)
            str/trim
            not-empty
            (as-> state (str " [" state "]")))))

(defn session-tree-widget-lines
  [slots _active-id]
  (let [slot-ids    (->> slots (map :id) (filter string?) vec)
        slot-id-set (set slot-ids)]
    (mapv (fn [slot]
            (let [id           (or (:id slot) "")
                  item-kind    (or (:item-kind slot) "session")
                  entry-id     (:entry-id slot)
                  is-active    (boolean (:is-active slot))
                  parent-id    (:parent-session-id slot)
                  indent       (if (or (= item-kind "fork-point")
                                       (and parent-id (contains? slot-id-set parent-id)))
                                 "  " "")
                  base-label   (session-tree-line-label slot)
                  current-text (when is-active " ← current")
                  runtime-text (runtime-state-badge slot)
                  text         (str indent base-label current-text runtime-text)]
              (cond-> {:text text}
                (= item-kind "session")
                (assoc :runtime-state (:runtime-state slot)
                       :is-current is-active)

                (= item-kind "fork-point")
                (assoc :action {:type "command"
                                :command (str "/fork " entry-id)})

                (and (not= item-kind "fork-point")
                     (not is-active))
                (assoc :action {:type "command"
                                :command (str "/tree " id)}))))
          slots)))

(defn context-widget
  [snapshot]
  (let [slots (:sessions snapshot)
        active-id (:active-session-id snapshot)]
    {:widget/extension-id "psi-session"
     :widget/widget-id "session-tree"
     :widget/placement :left
     :widget/content-lines (session-tree-widget-lines slots active-id)
     :widget/visible? (> (count slots) 1)}))