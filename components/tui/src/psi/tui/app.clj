(ns psi.tui.app
  (:require
   [charm.core :as charm]
   [charm.message :as msg]
   [clojure.string :as str]
   [psi.tui.patches :as patches]))

(patches/install!)

(def ^:private title-style   (charm/style :fg charm/magenta :bold true))
(def ^:private user-style    (charm/style :fg charm/cyan :bold true))
(def ^:private assist-style  (charm/style :fg charm/green :bold true))
(def ^:private error-style   (charm/style :fg charm/red))
(def ^:private dim-style     (charm/style :fg 240))
(def ^:private sep-style     (charm/style :fg 240))

(def ^:private spinner-frames
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(def ^:private prompt-history-max-entries 100)

(defn- initial-prompt-input-state
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

(def ^:private builtin-slash-commands
  ["/quit" "/exit" "/resume" "/new" "/tree" "/status" "/help" "/remember"
   "/worktree" "/jobs" "/job" "/cancel-job"])

(defn- input-value [state]
  (charm/text-input-value (:input state)))

(defn- input-pos [state]
  (let [v (input-value state)]
    (min (max 0 (or (get-in state [:input :pos]) (count v))) (count v))))

(defn- set-input-value
  [state s]
  (assoc state :input (charm/text-input-set-value (:input state) s)))

(defn- clear-history-browse
  [state]
  (assoc-in state [:prompt-input-state :history :browse-index] nil))

(defn- set-input-model
  [state input]
  (-> state
      (assoc :input input)
      clear-history-browse))

(defn- now-ms [] (System/currentTimeMillis))

(defn- double-press-window-ms
  [state]
  (or (:double-press-window-ms state) 500))

(defn- within-double-press-window?
  [last-ms now-ms window-ms]
  (and (some? last-ms)
       (<= (- now-ms last-ms) window-ms)))

(defn- append-assistant-status
  [state text]
  (if (str/blank? text)
    state
    (update state :messages conj {:role :assistant :text text})))

(defn- merge-queued-and-draft
  [queued-text draft-text]
  (let [queued (str/trim (or queued-text ""))
        draft  (str/trim (or draft-text ""))]
    (cond
      (and (str/blank? queued) (str/blank? draft)) ""
      (str/blank? queued) draft
      (str/blank? draft) queued
      :else (str queued "\n" draft))))

(defn- history-entries
  [state]
  (vec (get-in state [:prompt-input-state :history :entries] [])))

(defn- history-max-entries
  [state]
  (or (get-in state [:prompt-input-state :history :max-entries])
      prompt-history-max-entries))

(defn- record-history-entry
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

(defn- history-current-entry
  [state idx]
  (let [entries (history-entries state)]
    (when (and (some? idx)
               (<= 0 idx)
               (< idx (count entries)))
      (nth entries idx))))

(defn- browse-history
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

(declare dispatch-ui-event!
         refresh-extension-command-names
         key-debug-enabled?
         agent-event?
         external-message?
         agent-result?
         agent-error?
         agent-poll?
         poll-cmd
         has-active-dialog?
         handle-dialog-key
         make-init
         handle-agent-event
         handle-agent-result
         handle-agent-poll
         handle-streaming-escape
         handle-streaming-submit
         handle-ctrl-d
         handle-idle-escape
         handle-ctrl-c
         clear-live-turn
         delete-prev-word
         autocomplete-open?
         refresh-autocomplete
         maybe-auto-open-autocomplete
         move-autocomplete-selection
         apply-selected-autocomplete
         open-tab-autocomplete
         continue-input-line
         submit-input
         clear-autocomplete
         handle-selector-key
         view)

(load "app_autocomplete")
(load "app_support")
(load "app_update_helpers")

;; ── Update ──────────────────────────────────────────────────

(defn- update-tick-state
  [state]
  (let [state (cond-> state
                (:force-clear? state) (assoc :force-clear? false))
        state (if-let [read-fn (:ui-read-fn state)]
                (let [snap (read-fn)]
                  (assoc state :ui-snapshot snap :tools-expanded? (boolean (:tools-expanded? snap))))
                state)]
    (dispatch-ui-event! state :session/ui-dismiss-expired {})
    (dispatch-ui-event! state :session/ui-dismiss-overflow {})
    (refresh-extension-command-names state)))

(defn- log-key-debug!
  [m]
  (when (and (key-debug-enabled?) (msg/key-press? m))
    (println (str "[key-debug] key=" (pr-str (:key m))
                  " ctrl=" (boolean (:ctrl m))
                  " alt=" (boolean (:alt m))
                  " shift=" (boolean (:shift m))))))

(defn- handle-window-size-message
  [state m]
  (when (msg/window-size? m)
    [(assoc state
            :width (:width m)
            :height (:height m)
            :force-clear? true)
     nil]))

(defn- external-message-text
  [m]
  (or (some #(when (= :text (:type %)) (:text %)) (get-in m [:message :content]))
      ""))

(defn- handle-agent-message
  [state m]
  (cond
    (agent-event? m)
    (let [[new-state cmd] (handle-agent-event state m)]
      [new-state (or cmd (poll-cmd (:queue state)))])

    (external-message? m)
    (let [text (external-message-text m)]
      [(cond-> state
         (seq text) (update :messages conj {:role :assistant
                                            :text text
                                            :custom-type (:custom-type m)}))
       (poll-cmd (:queue state))])

    (agent-result? m)
    (handle-agent-result state (:result m))

    (agent-error? m)
    [(assoc state :phase :idle :error (:error m))
     (poll-cmd (:queue state))]

    (= :agent-aborted (:type m))
    (let [merged-text (merge-queued-and-draft (:queued-text m) (input-value state))
          status-msg  (or (:message m) "Interrupted.")]
      [(-> state
           (set-input-value merged-text)
           (assoc :phase :idle)
           clear-live-turn
           (append-assistant-status status-msg))
       (poll-cmd (:queue state))])

    (agent-poll? m)
    (if (= :streaming (:phase state))
      (handle-agent-poll state)
      [state (poll-cmd (:queue state))])

    :else nil))

(defn- handle-streaming-input
  [state m]
  (cond
    (and (= :streaming (:phase state))
         (msg/key-match? m "escape"))
    (handle-streaming-escape state)

    (and (= :streaming (:phase state))
         (msg/key-match? m "enter"))
    (handle-streaming-submit state)

    (and (= :streaming (:phase state))
         (msg/key-match? m "backspace"))
    (let [[new-input cmd] (charm/text-input-update (:input state) m)]
      [(set-input-model state new-input) cmd])

    (and (= :streaming (:phase state))
         (msg/key-match? m "space"))
    (let [[new-input cmd] (charm/text-input-update (:input state) (msg/key-press " "))]
      [(set-input-model state new-input) cmd])

    (and (= :streaming (:phase state))
         (msg/key-press? m))
    (let [[new-input cmd] (charm/text-input-update (:input state) m)]
      [(set-input-model state new-input) cmd])

    :else nil))

(defn- continue-input-line?
  [state m]
  (and (msg/key-match? m "enter")
       (or (:shift m)
           (:alt m)
           (and (:ctrl m) (:alt m))
           (str/ends-with? (charm/text-input-value (:input state)) "\\"))))

(defn- toggle-tools-expanded
  [state]
  (let [new-expanded? (not (:tools-expanded? state))]
    (dispatch-ui-event! state :session/ui-set-tools-expanded {:expanded? new-expanded?})
    [(assoc state :tools-expanded? new-expanded?) nil]))

(defn- delete-prev-word-update
  [state]
  (let [before    (charm/text-input-value (:input state))
        new-state (update state :input delete-prev-word)
        after     (charm/text-input-value (:input new-state))]
    (when (key-debug-enabled?)
      (println (str "[key-debug] branch=alt+backspace before=" (pr-str before)
                    " after=" (pr-str after)
                    " pos=" (:pos (:input new-state)))))
    [new-state nil]))

(defn- idle-edit-update
  [state update-message next-state-fn]
  (let [[new-input cmd] (charm/text-input-update (:input state) update-message)]
    [(next-state-fn (set-input-model state new-input)) cmd]))

(defn- idle-next-state-after-edit
  [state key-token]
  (if (autocomplete-open? state)
    (refresh-autocomplete state (get-in state [:prompt-input-state :autocomplete :trigger-mode]))
    (maybe-auto-open-autocomplete state key-token)))

(defn- autocomplete-nav-update
  [state m autocomplete?]
  (cond
    (and autocomplete? (msg/key-match? m "up")) [(move-autocomplete-selection state -1) nil]
    (and autocomplete? (msg/key-match? m "down")) [(move-autocomplete-selection state 1) nil]
    (and (not autocomplete?) (msg/key-match? m "up")) [(browse-history state :up) nil]
    (and (not autocomplete?) (msg/key-match? m "down")) [(browse-history state :down) nil]
    :else nil))

(defn- autocomplete-submit-update
  [state m autocomplete? run-agent-fn!]
  (cond
    (and autocomplete? (msg/key-match? m "tab")) [(apply-selected-autocomplete state) nil]
    (msg/key-match? m "tab") [(open-tab-autocomplete state) nil]
    (continue-input-line? state m) (continue-input-line state)
    (and autocomplete? (msg/key-match? m "enter"))
    (let [slash?     (= :slash_command (get-in state [:prompt-input-state :autocomplete :context]))
          next-state (apply-selected-autocomplete state)]
      (if slash?
        (submit-input next-state run-agent-fn!)
        [next-state nil]))
    (msg/key-match? m "enter") (submit-input state run-agent-fn!)
    :else nil))

(defn- idle-special-key-update
  [state m autocomplete?]
  (cond
    (msg/key-match? m "ctrl+d") (handle-ctrl-d state)
    (and autocomplete? (msg/key-match? m "escape")) [(clear-autocomplete state) nil]
    (and (not (has-active-dialog? state)) (msg/key-match? m "escape")) (handle-idle-escape state)
    (msg/key-match? m "ctrl+o") (toggle-tools-expanded state)
    (msg/key-match? m "alt+backspace") (delete-prev-word-update state)
    :else nil))

(defn- idle-text-edit-update
  [state m autocomplete?]
  (cond
    (msg/key-match? m "backspace")
    (idle-edit-update state m #(if autocomplete?
                                 (refresh-autocomplete % (get-in state [:prompt-input-state :autocomplete :trigger-mode]))
                                 %))
    (msg/key-match? m "space")
    (idle-edit-update state (msg/key-press " ") #(idle-next-state-after-edit % :space))
    (msg/key-press? m)
    (let [key-token (:key m)]
      (idle-edit-update state m #(idle-next-state-after-edit % key-token)))
    :else nil))

(defn- handle-idle-key-message
  [state m run-agent-fn!]
  (let [autocomplete? (autocomplete-open? state)]
    (or (idle-special-key-update state m autocomplete?)
        (autocomplete-nav-update state m autocomplete?)
        (autocomplete-submit-update state m autocomplete? run-agent-fn!)
        (idle-text-edit-update state m autocomplete?))))

(defn make-update
  "Create an update function.

   `run-agent-fn!` is called with (text queue) and should start the
   agent in a background thread that puts {:kind :done :result msg}
   or {:kind :error :message str} on queue when finished."
  [run-agent-fn!]
  (fn [state m]
    (let [state (update-tick-state state)]
      (log-key-debug! m)
      (or (when (msg/key-match? m "ctrl+c")
            (handle-ctrl-c state))
          (handle-window-size-message state m)
          (when (and (has-active-dialog? state) (msg/key-press? m))
            (or (handle-dialog-key state m) [state nil]))
          (handle-agent-message state m)
          (when (= :selecting-session (:phase state))
            (handle-selector-key state m))
          (when (= :idle (:phase state))
            (handle-idle-key-message state m run-agent-fn!))
          (handle-streaming-input state m)
          [state nil]))))

(load "app_render")

;; ── Public entry point ──────────────────────────────────────

(defn start!
  ([model-name run-agent-fn!]
   (start! model-name run-agent-fn! {}))
  ([model-name run-agent-fn! opts]
   (charm/run {:init       (make-init model-name (:query-fn opts) (:ui-read-fn opts) (:ui-dispatch-fn opts) opts)
               :update     (make-update run-agent-fn!)
               :view       view
               :alt-screen (if (contains? opts :alt-screen)
                             (boolean (:alt-screen opts))
                             true)})))
