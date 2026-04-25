(ns psi.tui.session-selector-render
  (:require
   [charm.style.core :as charm-style]
   [clojure.string :as str]
   [psi.tui.ansi :as ansi]
   [psi.tui.session-selector :as session-selector]))

(def ^:private selector-title-style  (charm-style/style :fg charm-style/magenta :bold true))
(def ^:private selector-sel-style    (charm-style/style :fg charm-style/cyan :bold true))
(def ^:private selector-cur-style    (charm-style/style :fg charm-style/yellow))
(def ^:private selector-hint-style   (charm-style/style :fg 240))
(def ^:private selector-search-style (charm-style/style :fg charm-style/green))

(def ^:private tree-active-badge "[active]")
(def ^:private tree-stream-badge "[stream]")

(defn- spaces
  [n]
  (apply str (repeat (max 0 n) " ")))

(defn- render-tree-status-cell
  [dim-style active? streaming?]
  (let [active-cell (if active?
                      (charm-style/render selector-cur-style tree-active-badge)
                      (spaces (count tree-active-badge)))
        stream-cell (if streaming?
                      (charm-style/render dim-style tree-stream-badge)
                      (spaces (count tree-stream-badge)))]
    (str active-cell " " stream-cell)))

(defn- shorten-path [p]
  (let [home (System/getProperty "user.home")]
    (if (and p (.startsWith ^String p home))
      (str "~" (subs p (count home)))
      (or p ""))))

(defn- selector-scope-string
  [scope tree-mode?]
  (if tree-mode?
    ""
    (if (= :current scope)
      (str (charm-style/render selector-sel-style "◉ Current") "  ○ All")
      (str "○ Current  " (charm-style/render selector-sel-style "◉ All")))))

(defn- selector-title
  [scope tree-mode?]
  (let [title-label (if tree-mode? "Session Tree" "Resume Session")
        title-hints (if tree-mode?
                      "[↑↓=nav Enter=switch Esc=cancel]"
                      "[Tab=scope ↑↓=nav Enter=select Esc=cancel]")
        scope-str   (selector-scope-string scope tree-mode?)]
    (str (charm-style/render selector-title-style title-label)
         (when (seq scope-str) (str "  " scope-str))
         "  " (charm-style/render selector-hint-style title-hints))))

(defn- selector-label-base
  [info]
  (let [sid-str (some-> (:session-id info) str)]
    (-> (or (:prompt-text info)
            (:display-name info)
            (:name info)
            (:first-message info)
            (when sid-str
              (str "session-" (subs sid-str 0 (min 8 (count sid-str)))))
            "(empty)")
        (str/replace #"\n" " "))))

(defn- tree-selector-label
  [dim-style info]
  (let [session-item? (= :session (:item-kind info :session))
        glyph         (when (and session-item?
                                 (zero? (int (or (:tree-depth info) 0))))
                        (charm-style/render dim-style "● "))
        prompt-prefix (when (= :fork-point (:item-kind info))
                        (charm-style/render dim-style "⎇ "))
        worktree      (shorten-path (or (:worktree-path info) (:cwd info)))]
    (str (or (:tree-prefix info) "")
         (or glyph "")
         (or prompt-prefix "")
         (selector-label-base info)
         (when (and session-item? (seq worktree))
           (charm-style/render dim-style (str "  " worktree))))))

(defn- selector-right-cell
  [dim-style sel-state info scope tree-mode?]
  (let [worktree (shorten-path (or (:worktree-path info) (:cwd info)))]
    (if tree-mode?
      (case (:item-kind info :session)
        :fork-point
        (charm-style/render dim-style "fork")

        (let [sid         (:session-id info)
              sid-str     (some-> sid str)
              active?     (= sid (:active-session-id sel-state))
              streaming?  (boolean (:is-streaming info))]
          (str (render-tree-status-cell dim-style active? streaming?)
               (when sid-str
                 (charm-style/render dim-style
                                     (str "  " (subs sid-str 0 (min 8 (count sid-str)))))))))
      (str (charm-style/render dim-style (str (:message-count info) " " (session-selector/format-age (:modified info))))
           (when (= :all scope)
             (str " " (charm-style/render dim-style worktree)))))))

(defn- selector-entry-line
  [dim-style current-session-file width tree-mode? scope sel-state selected i info]
  (let [is-sel     (= i selected)
        is-current (if tree-mode?
                     (and (= :session (:item-kind info :session))
                          (or (= (:session-id info) (:active-session-id sel-state))
                              (= (:session-id info) (:id info))))
                     (= (:path info) current-session-file))
        label      (if tree-mode?
                     (tree-selector-label dim-style info)
                     (selector-label-base info))
        right      (selector-right-cell dim-style sel-state info scope tree-mode?)
        right-w    (ansi/visible-width right)
        avail      (max 10 (- width 4 right-w))
        label-tr   (if (> (ansi/visible-width label) avail)
                     (ansi/strip-ansi (ansi/truncate-to-width label avail "…"))
                     label)
        cursor     (if is-sel
                     (charm-style/render selector-sel-style "▸ ")
                     "  ")
        styled-lbl (cond
                     is-current (charm-style/render selector-cur-style label-tr)
                     is-sel (charm-style/render selector-sel-style label-tr)
                     :else label-tr)
        pad        (str/join (repeat (max 1 (- width 2 (ansi/visible-width label-tr) right-w)) " "))]
    (str cursor styled-lbl pad right)))

(defn render-session-selector
  [dim-style render-separator sel-state current-session-file width mode]
  (let [{:keys [scope search selected]} sel-state
        filtered   (session-selector/selector-filtered sel-state)
        n          (count filtered)
        tree-mode? (= :tree mode)]
    (str (selector-title scope tree-mode?) "\n"
         (charm-style/render selector-search-style (str "Search: " search "█")) "\n"
         (render-separator width) "\n"
         (if (zero? n)
           (charm-style/render dim-style "  (no sessions found)\n")
           (str/join "\n"
                     (map-indexed (fn [i info]
                                    (selector-entry-line dim-style current-session-file width tree-mode? scope sel-state selected i info))
                                  filtered)))
         "\n"
         (when (pos? n)
           (charm-style/render dim-style (str "  " (inc selected) "/" n "\n"))))))
