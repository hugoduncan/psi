(ns psi.tui.session-selector
  (:require
   [clojure.string :as str]
   [psi.agent-session.persistence :as persist])
  (:import
   [java.time Instant]))

(defn format-age
  [ts]
  (when ts
    (let [epoch-ms (cond
                     (instance? Instant ts) (.toEpochMilli ^Instant ts)
                     (instance? java.util.Date ts) (.getTime ^java.util.Date ts)
                     :else nil)]
      (when epoch-ms
        (let [diff-ms   (- (System/currentTimeMillis) epoch-ms)
              diff-mins (quot diff-ms 60000)
              diff-hrs  (quot diff-ms 3600000)
              diff-days (quot diff-ms 86400000)]
          (cond
            (< diff-mins 1)   "now"
            (< diff-mins 60)  (str diff-mins "m")
            (< diff-hrs 24)   (str diff-hrs "h")
            (< diff-days 7)   (str diff-days "d")
            (< diff-days 30)  (str (quot diff-days 7) "w")
            (< diff-days 365) (str (quot diff-days 30) "mo")
            :else             (str (quot diff-days 365) "y")))))))

(defn filter-sessions
  [sessions query]
  (if (str/blank? query)
    sessions
    (let [q (str/lower-case (str/trim query))]
      (filterv (fn [s]
                 (or (str/includes? (str/lower-case (or (:first-message s) "")) q)
                     (str/includes? (str/lower-case (or (:display-name s) "")) q)
                     (str/includes? (str/lower-case (or (:prompt-text s) "")) q)
                     (str/includes? (str/lower-case (or (:name s) "")) q)
                     (str/includes? (str/lower-case (or (:cwd s) "")) q)
                     (str/includes? (str/lower-case (or (:session-id s) "")) q)
                     (str/includes? (str/lower-case (or (:path s) "")) q)))
               sessions))))

(defn session-selector-init
  [cwd current-session-file]
  (let [dir      (persist/session-dir-for cwd)
        sessions (persist/list-sessions dir)]
    {:sessions             sessions
     :all-sessions         nil
     :scope                :current
     :search               ""
     :selected             0
     :loading?             false
     :current-session-file current-session-file
     :active-session-id    nil}))

(defn selected-index-for-session-id
  [sessions sid]
  (or (first (keep-indexed (fn [i s]
                             (when (and (= :session (:item-kind s :session))
                                        (= sid (:session-id s)))
                               i))
                           sessions))
      0))

(defn selector-item->tui-session
  [cwd item]
  (let [meta          (or (:ui.item/meta item) item)
        worktree-path (or (:item/worktree-path meta) cwd)
        parent-id     (some-> (:item/parent-id meta) second)]
    {:item-kind         (:item/kind meta)
     :session-id        (:item/session-id meta)
     :entry-id          (:item/entry-id meta)
     :action-value      (:ui.item/value item)
     :path              nil
     :name              (or (:ui.item/label item) (:item/display-name meta))
     :display-name      (or (:ui.item/label item) (:item/display-name meta))
     :parent-session-id parent-id
     :is-streaming      (boolean (:item/is-streaming meta))
     :first-message     ""
     :message-count     0
     :modified          (:item/updated-at meta)
     :created-at        (:item/created-at meta)
     :updated-at        (:item/updated-at meta)
     :cwd               worktree-path
     :worktree-path     worktree-path
     :tree-depth        0
     :tree-prefix       ""}))

(defn selector-items->tui-sessions
  [cwd items]
  (let [sessions         (mapv #(selector-item->tui-session cwd %) items)
        session-items    (filterv #(= :session (:item-kind % :session)) sessions)
        by-id            (into {} (map (juxt :session-id identity) session-items))
        children-by-parent
        (reduce (fn [acc s]
                  (let [parent-id      (:parent-session-id s)
                        parent-exists? (and parent-id (contains? by-id parent-id))
                        parent-key     (if parent-exists? parent-id ::root)]
                    (update acc parent-key (fnil conj []) s)))
                {}
                session-items)
        prefix-by-id
        (letfn [(tree-prefix [ancestor-has-next last-sibling?]
                  (str (apply str (map #(if % "│ " "  ") ancestor-has-next))
                       (if last-sibling? "└─ " "├─ ")))
                (walk [node depth ancestor-has-next last-sibling? acc]
                  (let [sid        (:session-id node)
                        node*      (assoc node
                                          :tree-depth depth
                                          :tree-prefix (if (zero? depth)
                                                         ""
                                                         (tree-prefix ancestor-has-next last-sibling?)))
                        children   (get children-by-parent sid [])
                        child-path (if (zero? depth)
                                     ancestor-has-next
                                     (conj ancestor-has-next (not last-sibling?)))
                        child-count (count children)
                        acc*       (assoc acc sid node*)]
                    (reduce (fn [acc2 [idx child]]
                              (walk child (inc depth) child-path (= idx (dec child-count)) acc2))
                            acc*
                            (map-indexed vector children))))]
          (let [roots (get children-by-parent ::root [])]
            (reduce (fn [acc [idx root]]
                      (walk root 0 [] (= idx (dec (count roots))) acc))
                    {}
                    (map-indexed vector roots))))]
    (mapv (fn [item]
            (if (= :session (:item-kind item :session))
              (get prefix-by-id (:session-id item) item)
              (let [parent-depth (or (some-> (get prefix-by-id (:parent-session-id item)) :tree-depth) 0)
                    spaces       (apply str (repeat parent-depth "  "))]
                (assoc item
                       :tree-depth (inc parent-depth)
                       :tree-prefix (str spaces "↳ ")))))
          sessions)))

(defn session-selector-init-from-action
  [cwd current-session-file active-id action]
  (let [items    (selector-items->tui-sessions cwd (:ui/items action))
        selected (selected-index-for-session-id items active-id)]
    {:sessions             items
     :all-sessions         nil
     :scope                :current
     :search               ""
     :selected             selected
     :loading?             false
     :current-session-file current-session-file
     :active-session-id    active-id
     :ui/action            action}))

(defn session-selector-init-from-context
  [session-selector-fn cwd current-session-file active-id]
  (if-not session-selector-fn
    (session-selector-init cwd current-session-file)
    (try
      (session-selector-init-from-action cwd current-session-file active-id (session-selector-fn))
      (catch Exception _
        (session-selector-init cwd current-session-file)))))

(defn selector-sessions
  [{:keys [scope sessions all-sessions]}]
  (if (= :current scope) sessions (or all-sessions [])))

(defn selector-filtered
  [sel-state]
  (filter-sessions (selector-sessions sel-state) (:search sel-state)))

(defn selector-clamp
  [sel-state]
  (let [n (count (selector-filtered sel-state))]
    (update sel-state :selected #(max 0 (min % (dec (max 1 n)))))))

(defn selector-move
  [sel-state delta]
  (let [n (count (selector-filtered sel-state))]
    (selector-clamp
     (update sel-state :selected #(max 0 (min (dec (max 1 n)) (+ % delta)))))))

(defn selector-type
  [sel-state key-token->string printable-key key-token]
  (let [key-str    (key-token->string key-token)
        new-search (cond
                     (= "backspace" key-str)
                     (let [s (:search sel-state)]
                       (if (pos? (count s)) (subs s 0 (dec (count s))) s))

                     :else
                     (if-let [ch (printable-key key-token)]
                       (str (:search sel-state) ch)
                       (:search sel-state)))]
    (selector-clamp (assoc sel-state :search new-search))))
