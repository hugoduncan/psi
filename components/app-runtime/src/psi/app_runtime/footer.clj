(ns psi.app-runtime.footer
  "Adapter-neutral footer semantic projection shared across interactive UIs."
  (:require
   [clojure.string :as str]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.app-runtime.context :as app-context]))

(def footer-query
  [:psi.agent-session/worktree-path
   :psi.agent-session/git-branch
   :psi.agent-session/session-name
   :psi.agent-session/session-display-name
   :psi.agent-session/usage-input
   :psi.agent-session/usage-output
   :psi.agent-session/usage-cache-read
   :psi.agent-session/usage-cache-write
   :psi.agent-session/usage-cost-total
   :psi.agent-session/context-fraction
   :psi.agent-session/context-window
   :psi.agent-session/auto-compaction-enabled
   :psi.agent-session/model-provider
   :psi.agent-session/model-id
   :psi.agent-session/model-reasoning
   :psi.agent-session/thinking-level
   :psi.agent-session/effective-reasoning-effort
   :psi.ui/statuses])

(defn footer-data
  [ctx session-id]
  (try
    (or (session/query-in ctx session-id footer-query) {})
    (catch Throwable _
      {})))

(defn- format-token-count
  [n]
  (let [n (or n 0)]
    (cond
      (< n 1000) (str n)
      (< n 10000) (format "%.1fk" (/ n 1000.0))
      (< n 1000000) (str (Math/round (double (/ n 1000.0))) "k")
      (< n 10000000) (format "%.1fM" (/ n 1000000.0))
      :else (str (Math/round (double (/ n 1000000.0))) "M"))))

(defn- string-value
  [x]
  (when (string? x)
    x))

(defn- present-string
  [x]
  (let [s (some-> x string-value str/trim)]
    (when (seq s)
      s)))

(defn- replace-home-with-tilde
  [path]
  (let [home (System/getProperty "user.home")
        path* (or (string-value path) "")]
    (if (and (string? home)
             (str/starts-with? path* home))
      (str "~" (subs path* (count home)))
      path*)))

(defn- positive-number?
  [n]
  (and (number? n) (pos? (double n))))

(defn- normalize-thinking-level
  [level]
  (cond
    (keyword? level) (name level)
    (string? level)  level
    :else            "off"))

(defn- footer-context-text
  [fraction context-window auto-compact?]
  (let [suffix (if auto-compact? " (auto)" "")
        window (format-token-count (or context-window 0))]
    (if (number? fraction)
      (str (format "%.1f" (* 100.0 fraction)) "%/" window suffix)
      (str "?/" window suffix))))

(defn- usage-parts
  [d context-text]
  (let [usage-input       (or (:psi.agent-session/usage-input d) 0)
        usage-output      (or (:psi.agent-session/usage-output d) 0)
        usage-cache-read  (or (:psi.agent-session/usage-cache-read d) 0)
        usage-cache-write (or (:psi.agent-session/usage-cache-write d) 0)
        usage-cost-total  (or (:psi.agent-session/usage-cost-total d) 0.0)]
    (cond-> []
      (positive-number? usage-input)
      (conj (str "↑" (format-token-count usage-input)))

      (positive-number? usage-output)
      (conj (str "↓" (format-token-count usage-output)))

      (positive-number? usage-cache-read)
      (conj (str "CR" (format-token-count usage-cache-read)))

      (positive-number? usage-cache-write)
      (conj (str "CW" (format-token-count usage-cache-write)))

      (positive-number? usage-cost-total)
      (conj (format "$%.3f" (double usage-cost-total)))

      :always
      (conj context-text))))

(defn- model-text
  [d]
  (let [model-provider   (string-value (:psi.agent-session/model-provider d))
        model-id         (string-value (:psi.agent-session/model-id d))
        model-reasoning? (boolean (:psi.agent-session/model-reasoning d))
        thinking-level   (:psi.agent-session/thinking-level d)
        thinking-label   (normalize-thinking-level thinking-level)
        effort-label     (or (string-value (:psi.agent-session/effective-reasoning-effort d))
                             (when (not= "off" thinking-label)
                               thinking-label))
        model-label      (or model-id "no-model")
        provider-label   (or model-provider "no-provider")
        right-base       (if model-reasoning?
                           (if (= "off" thinking-label)
                             (str model-label " • thinking off")
                             (str model-label " • thinking " effort-label))
                           model-label)]
    (str "(" provider-label ") " right-base)))

(defn- path-text
  [d fallback-worktree-path]
  (let [worktree-path        (or (string-value (:psi.agent-session/worktree-path d))
                                 (string-value fallback-worktree-path)
                                 "")
        git-branch           (present-string (:psi.agent-session/git-branch d))
        session-display-name (or (present-string (:psi.agent-session/session-display-name d))
                                 (present-string (:psi.agent-session/session-name d)))
        path0                (replace-home-with-tilde worktree-path)
        path1                (if git-branch
                               (str path0 " (" git-branch ")")
                               path0)]
    (if session-display-name
      (str path1 " • " session-display-name)
      path1)))

(defn- sanitize-status-text
  [text]
  (-> (or (string-value text) "")
      (str/replace #"[\r\n\t]" " ")
      (str/replace #" +" " ")
      (str/trim)))

(defn- status-seq
  [statuses]
  (cond
    (map? statuses)        (vals statuses)
    (or (vector? statuses)
        (sequential? statuses)
        (set? statuses))   statuses
    :else                  []))

(defn- status-items
  [statuses]
  (->> (status-seq statuses)
       (sort-by #(or (:extension-id %) (:extensionId %) ""))
       (mapv (fn [status]
               {:status/extension-id (or (:extension-id status) (:extensionId status))
                :status/text         (sanitize-status-text (or (:text status) (:message status)))}))))

(defn- footer-session-label
  [session-map]
  (or (present-string (:display-name session-map))
      (present-string (:session-display-name session-map))
      (present-string (:session-name session-map))
      (when-let [sid (present-string (:session-id session-map))]
        (subs sid 0 (min 8 (count sid))))))

(defn- order-context-sessions-parent-first
  [context-sessions]
  (let [sessions           (vec (or context-sessions []))
        by-session-id      (into {} (keep (fn [session-map]
                                            (when-let [sid (:session-id session-map)]
                                              [sid session-map])))
                                 sessions)
        input-order        (into {} (keep-indexed (fn [i session-map]
                                                    (when-let [sid (:session-id session-map)]
                                                      [sid i])))
                                 sessions)
        children-by-parent (reduce (fn [acc session-map]
                                     (let [sid               (:session-id session-map)
                                           parent-session-id (:parent-session-id session-map)
                                           parent-exists?    (and parent-session-id
                                                                  (contains? by-session-id parent-session-id))
                                           parent-key        (if parent-exists?
                                                               parent-session-id
                                                               ::root)]
                                       (if sid
                                         (update acc parent-key (fnil conj []) session-map)
                                         acc)))
                                   {}
                                   sessions)
        children-by-parent (update-vals children-by-parent
                                        (fn [xs]
                                          (vec (sort-by #(get input-order (:session-id %) Integer/MAX_VALUE)
                                                        xs))))
        roots              (let [rs (get children-by-parent ::root [])]
                             (if (seq rs) rs sessions))
        emitted            (volatile! #{})]
    (letfn [(walk [node visited]
              (let [sid      (:session-id node)
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

(def ^:private runtime-state-order ["waiting" "running" "retrying" "compacting"])

(defn- footer-session-activity-buckets
  [context-sessions]
  (let [sessions* (->> (order-context-sessions-parent-first context-sessions)
                       (keep (fn [session-map]
                               (when-let [label (footer-session-label session-map)]
                                 {:label label
                                  :runtime-state (:runtime-state session-map)}))))
        grouped   (group-by :runtime-state sessions*)]
    (->> runtime-state-order
         (keep (fn [state]
                 (when-let [xs (seq (get grouped state))]
                   {:state state
                    :labels (mapv :label xs)})))
         vec)))

(defn- footer-session-activity-line
  [context-sessions]
  (let [sessions* (->> (order-context-sessions-parent-first context-sessions)
                       (keep footer-session-label)
                       vec)
        buckets   (footer-session-activity-buckets context-sessions)
        parts     (mapv (fn [{:keys [state labels]}]
                          (str state " " (str/join ", " labels)))
                        buckets)]
    (when (> (count sessions*) 1)
      (str "sessions: " (str/join " · " parts)))))

(defn footer-model-from-data
  ([d]
   (footer-model-from-data d {}))
  ([d {:keys [worktree-path cwd context-sessions]}]
   (let [fallback-worktree-path   (or worktree-path cwd)
         context-fraction         (:psi.agent-session/context-fraction d)
         context-window           (:psi.agent-session/context-window d)
         auto-compact?            (boolean (:psi.agent-session/auto-compaction-enabled d))
         context-text             (footer-context-text context-fraction context-window auto-compact?)
         usage-parts*             (usage-parts d context-text)
         model-text*              (model-text d)
         path-text*               (path-text d fallback-worktree-path)
         statuses*                (status-items (:psi.ui/statuses d))
         session-activity-buckets (footer-session-activity-buckets context-sessions)
         session-activity-line    (footer-session-activity-line context-sessions)
         status-line              (->> statuses*
                                       (map :status/text)
                                       (remove str/blank?)
                                       (str/join " "))
         stats-line               (str/join " " (concat usage-parts* [model-text*]))
         thinking-level           (normalize-thinking-level (:psi.agent-session/thinking-level d))]
     {:footer/path {:cwd                  (or (:psi.agent-session/worktree-path d)
                                              fallback-worktree-path
                                              "")
                    :git-branch           (:psi.agent-session/git-branch d)
                    :session-name         (:psi.agent-session/session-name d)
                    :session-display-name (or (:psi.agent-session/session-display-name d)
                                              (:psi.agent-session/session-name d))
                    :text                 path-text*}
      :footer/usage {:input       (or (:psi.agent-session/usage-input d) 0)
                     :output      (or (:psi.agent-session/usage-output d) 0)
                     :cache-read  (or (:psi.agent-session/usage-cache-read d) 0)
                     :cache-write (or (:psi.agent-session/usage-cache-write d) 0)
                     :cost-total  (or (:psi.agent-session/usage-cost-total d) 0.0)
                     :parts       usage-parts*}
      :footer/context {:fraction                 context-fraction
                       :window                   context-window
                       :auto-compaction-enabled auto-compact?
                       :text                     context-text}
      :footer/model {:provider                   (:psi.agent-session/model-provider d)
                     :id                         (:psi.agent-session/model-id d)
                     :reasoning                  (boolean (:psi.agent-session/model-reasoning d))
                     :thinking-level             thinking-level
                     :effective-reasoning-effort (:psi.agent-session/effective-reasoning-effort d)
                     :text                       model-text*}
      :footer/statuses statuses*
      :footer/session-activity {:line session-activity-line
                                :buckets session-activity-buckets}
      :footer/lines {:path-line             path-text*
                     :stats-line            stats-line
                     :session-activity-line session-activity-line
                     :status-line           (when (seq status-line) status-line)}})))

(defn- enrich-context-session-runtime-state
  [ctx session-map]
  (let [sid (:session-id session-map)]
    (cond-> session-map
      sid (assoc :runtime-state (app-context/phase->runtime-state (ss/sc-phase-in ctx sid))))))

(defn footer-model
  [ctx session-id]
  (footer-model-from-data (footer-data ctx session-id)
                          {:worktree-path  (ss/session-worktree-path-in ctx session-id)
                           :context-sessions (->> (ss/list-context-sessions-in ctx)
                                                  (mapv #(enrich-context-session-runtime-state ctx %)))}))