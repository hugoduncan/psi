(ns psi.tui.app.shared
  (:require
   [charm.core :as charm]
   [clojure.string :as str]
   [psi.tui.patches :as patches]))

(patches/install!)

(def title-style   (charm/style :fg charm/magenta :bold true))
(def user-style    (charm/style :fg charm/cyan :bold true))
(def assist-style  (charm/style :fg charm/green :bold true))
(def error-style   (charm/style :fg charm/red))
(def dim-style     (charm/style :fg 240))
(def sep-style     (charm/style :fg 240))

(def spinner-frames
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(def prompt-history-max-entries 100)

(def builtin-slash-commands
  ["/quit" "/exit" "/resume" "/new" "/tree" "/status" "/help" "/remember"
   "/worktree" "/jobs" "/job" "/cancel-job"])

(defn initial-prompt-input-state
  []
  {:autocomplete {:prefix ""
                  :candidates []
                  :selected-index 0
                  :context nil
                  :trigger-mode nil}
   :history {:entries []
             :browse-index nil
             :max-entries prompt-history-max-entries}
   :timing {:last-ctrl-c-ms nil
            :last-escape-ms nil}})

(defn input-value [state]
  (charm/text-input-value (:input state)))

(defn input-pos [state]
  (let [v (input-value state)]
    (min (max 0 (or (get-in state [:input :pos]) (count v))) (count v))))

(defn set-input-value
  [state s]
  (assoc state :input (charm/text-input-set-value (:input state) s)))

(defn clear-history-browse
  [state]
  (assoc-in state [:prompt-input-state :history :browse-index] nil))

(defn set-input-model
  [state input]
  (-> state
      (assoc :input input)
      clear-history-browse))

(defn now-ms [] (System/currentTimeMillis))

(defn double-press-window-ms
  [state]
  (or (:double-press-window-ms state) 500))

(defn within-double-press-window?
  [last-ms now-ms* window-ms]
  (and (some? last-ms)
       (<= (- now-ms* last-ms) window-ms)))

(defn append-assistant-status
  [state text]
  (if (str/blank? text)
    state
    (update state :messages conj {:role :assistant :text text})))

(defn merge-queued-and-draft
  [queued-text draft-text]
  (let [queued (str/trim (or queued-text ""))
        draft  (str/trim (or draft-text ""))]
    (cond
      (and (str/blank? queued) (str/blank? draft)) ""
      (str/blank? queued) draft
      (str/blank? draft) queued
      :else (str queued "\n" draft))))

(defn history-entries
  [state]
  (vec (get-in state [:prompt-input-state :history :entries] [])))

(defn history-max-entries
  [state]
  (or (get-in state [:prompt-input-state :history :max-entries])
      prompt-history-max-entries))

(defn record-history-entry
  [state text]
  (let [entry (str/trim (or text ""))]
    (if (str/blank? entry)
      state
      (let [entries      (history-entries state)
            last-entry   (peek entries)
            max-entries  (history-max-entries state)
            next-entries (if (= last-entry entry)
                           entries
                           (let [appended (conj entries entry)
                                 extra    (max 0 (- (count appended) max-entries))]
                             (if (pos? extra)
                               (vec (subvec appended extra))
                               appended)))]
        (-> state
            (assoc-in [:prompt-input-state :history :entries] next-entries)
            (assoc-in [:prompt-input-state :history :browse-index] nil))))))

(defn history-current-entry
  [state idx]
  (let [entries (history-entries state)]
    (when (and (some? idx)
               (<= 0 idx)
               (< idx (count entries)))
      (nth entries idx))))

(defn browse-history
  [state direction]
  (let [entries (history-entries state)
        n       (count entries)
        idx     (get-in state [:prompt-input-state :history :browse-index])
        input   (input-value state)]
    (if (zero? n)
      state
      (case direction
        :up
        (cond
          (and (nil? idx) (str/blank? input))
          (let [new-idx (dec n)]
            (-> state
                (assoc-in [:prompt-input-state :history :browse-index] new-idx)
                (set-input-value (history-current-entry state new-idx))))

          (some? idx)
          (let [new-idx (max 0 (dec idx))]
            (-> state
                (assoc-in [:prompt-input-state :history :browse-index] new-idx)
                (set-input-value (history-current-entry state new-idx))))

          :else
          state)

        :down
        (if (some? idx)
          (if (>= idx (dec n))
            (-> state
                (assoc-in [:prompt-input-state :history :browse-index] nil)
                (set-input-value ""))
            (let [new-idx (min (dec n) (inc idx))]
              (-> state
                  (assoc-in [:prompt-input-state :history :browse-index] new-idx)
                  (set-input-value (history-current-entry state new-idx)))))
          state)

        state))))
