(ns psi.app-runtime.selectors
  "Adapter-neutral selector/session-tree projections shared across interactive UIs."
  (:require
   [clojure.string :as str]
   [psi.agent-session.message-text :as message-text]
   [psi.session-persistence.core :as persist]
   [psi.session-state.state :as ss]))

(defn- session-item-id
  [session-id]
  [:session session-id])

(defn- fork-point-item-id
  [entry-id]
  [:fork-point entry-id])

(defn- session-action
  [session-id]
  {:action/kind :switch-session
   :action/session-id session-id})

(defn- fork-action
  [session-id entry-id]
  {:action/kind :fork-session
   :action/session-id session-id
   :action/entry-id entry-id})

(declare selector-item->default-label)

(defn- with-default-label
  [item]
  (assoc item :item/default-label (selector-item->default-label item)))

(defn- session->selector-item
  [active-session-id session-map]
  (let [sid (:session-id session-map)]
    {:item/id            (session-item-id sid)
     :item/kind          :session
     :item/parent-id     (some-> (:parent-session-id session-map) session-item-id)
     :item/session-id    sid
     :item/entry-id      nil
     :item/display-name  (or (:display-name session-map)
                             (:session-name session-map)
                             sid)
     :item/is-active     (= sid active-session-id)
     :item/is-streaming  (boolean (:is-streaming session-map))
     :item/worktree-path (:worktree-path session-map)
     :item/created-at    (:created-at session-map)
     :item/updated-at    (:updated-at session-map)
     :item/action        (session-action sid)}))

(defn- fork-point-entry->selector-item
  [session-id entry]
  (let [msg  (get-in entry [:data :message])
        text (message-text/user-message-display-text msg)]
    (when (and (= :message (:kind entry))
               (= "user" (:role msg))
               (string? text)
               (not (str/blank? text)))
      {:item/id           (fork-point-item-id (:id entry))
       :item/kind         :fork-point
       :item/parent-id    (session-item-id session-id)
       :item/session-id   session-id
       :item/entry-id     (:id entry)
       :item/display-name text
       :item/is-active    false
       :item/is-streaming false
       :item/worktree-path nil
       :item/created-at   (:timestamp entry)
       :item/updated-at   nil
       :item/action       (fork-action session-id (:id entry))})))

(defn- current-session-fork-point-items
  [ctx session-id]
  (if (str/blank? session-id)
    []
    (->> (persist/all-entries-in ctx session-id)
         (keep #(fork-point-entry->selector-item session-id %))
         vec)))

(defn- tree-sort-session-items
  [items]
  (let [items              (vec items)
        by-session-id      (into {} (map (juxt :item/session-id identity) items))
        input-order        (into {} (map-indexed (fn [i item] [(:item/session-id item) i]) items))
        children-by-parent (reduce (fn [acc item]
                                     (let [parent-session-id (some-> (:item/parent-id item) second)
                                           parent-exists?    (and parent-session-id
                                                                  (contains? by-session-id parent-session-id))
                                           parent-key        (if parent-exists?
                                                               parent-session-id
                                                               ::root)]
                                       (update acc parent-key (fnil conj []) item)))
                                   {}
                                   items)
        children-by-parent (update-vals children-by-parent
                                        (fn [xs]
                                          (vec (sort-by #(get input-order (:item/session-id %) Integer/MAX_VALUE)
                                                        xs))))
        roots              (let [rs (get children-by-parent ::root [])]
                             (if (seq rs) rs items))
        emitted            (volatile! #{})]
    (letfn [(walk [node visited]
              (let [sid      (:item/session-id node)
                    cycle?   (contains? visited sid)
                    emitted? (contains? @emitted sid)]
                (if (or cycle? emitted?)
                  []
                  (let [visited' (conj visited sid)
                        _        (vswap! emitted conj sid)
                        children (get children-by-parent sid [])]
                    (reduce (fn [acc child]
                              (into acc (walk child visited')))
                            [node]
                            children)))))]
      (vec (mapcat #(walk % #{}) roots)))))

(defn- descendant-session-id?
  [by-session-id ancestor-session-id candidate-session-id]
  (loop [sid candidate-session-id]
    (let [parent-id (some-> (get by-session-id sid) :item/parent-id second)]
      (cond
        (nil? parent-id) false
        (= parent-id ancestor-session-id) true
        :else (recur parent-id)))))

(defn- interleave-current-session-fork-points
  [session-items session-id fork-items]
  (if (or (str/blank? session-id) (empty? fork-items))
    session-items
    (let [by-session-id (into {} (map (juxt :item/session-id identity) session-items))]
      (loop [remaining session-items
             acc       []]
        (if-let [item (first remaining)]
          (let [acc'           (conj acc item)
                current-item?  (= session-id (:item/session-id item))
                descendants    (when current-item?
                                 (take-while (fn [next-item]
                                               (and (= :session (:item/kind next-item))
                                                    (descendant-session-id? by-session-id
                                                                            session-id
                                                                            (:item/session-id next-item))))
                                             (rest remaining)))
                after-subtree  (when current-item?
                                 (drop (count descendants) (rest remaining)))]
            (if current-item?
              (recur after-subtree (into acc' (concat descendants fork-items)))
              (recur (rest remaining) acc')))
          acc)))))

(defn- ensure-current-session-present
  [ctx session-id sessions]
  (let [sessions*        (vec (or sessions []))
        current-present? (some #(= session-id (:session-id %)) sessions*)]
    (cond
      (str/blank? session-id)
      sessions*

      current-present?
      sessions*

      :else
      (let [sd       (ss/get-session-data-in ctx session-id)
            messages (persist/messages-from-entries-in ctx session-id)]
        (if (map? sd)
          (into [(assoc (select-keys sd [:session-id :session-name :worktree-path
                                         :parent-session-id :parent-session-path
                                         :created-at :updated-at :is-streaming])
                        :display-name (message-text/session-display-name
                                       (:session-name sd)
                                       messages))]
                sessions*)
          sessions*)))))

(defn- enrich-session-runtime-state
  [ctx session-map]
  (let [sid (:session-id session-map)
        sd  (and sid (ss/get-session-data-in ctx sid))]
    (cond-> session-map
      (map? sd) (assoc :is-streaming (boolean (:is-streaming sd))))))

(defn context-session-selector-items
  [ctx active-session-id]
  (let [sessions   (->> (ss/list-context-sessions-in ctx)
                        (ensure-current-session-present ctx active-session-id)
                        (mapv #(enrich-session-runtime-state ctx %))
                        (mapv #(session->selector-item active-session-id %))
                        tree-sort-session-items)
        fork-items (current-session-fork-point-items ctx active-session-id)]
    (->> (interleave-current-session-fork-points sessions active-session-id fork-items)
         (mapv with-default-label))))

(defn context-session-selector
  [ctx active-session-id]
  (let [items (context-session-selector-items ctx active-session-id)]
    {:selector/kind           :context-session
     :selector/prompt         "Select a live session"
     :selector/active-item-id (some->> items
                                       (some (fn [item]
                                               (when (:item/is-active item)
                                                 (:item/id item)))))
     :selector/items          items}))

(defn selector-item->default-label
  [item]
  (case (:item/kind item)
    :fork-point (str "⎇ " (:item/display-name item))
    (:item/display-name item)))
