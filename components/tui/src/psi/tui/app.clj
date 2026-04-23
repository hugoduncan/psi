(ns psi.tui.app
  (:require
   [charm.core :as charm]
   [charm.message :as msg]
   [clojure.string :as str]
   [psi.tui.app.autocomplete :as autocomplete]
   [psi.tui.app.frontend-actions :as frontend-actions]
   [psi.tui.app.render :as render]
   [psi.tui.app.shared :as shared]
   [psi.tui.app.support :as support]
   [psi.tui.app.update :as app-update]))

(defn- update-tick-state
  [state]
  (let [state (cond-> state
                (:force-clear? state) (assoc :force-clear? false))
        state (if-let [read-fn (:ui-read-fn state)]
                (let [snap (read-fn)]
                  (assoc state :ui-snapshot snap :tools-expanded? (boolean (:tools-expanded? snap))))
                state)]
    (support/dispatch-ui-event! state :session/ui-dismiss-expired {})
    (support/dispatch-ui-event! state :session/ui-dismiss-overflow {})
    (support/refresh-extension-command-names state)))

(defn- log-key-debug!
  [m]
  (when (and (support/key-debug-enabled?) (msg/key-press? m))
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
    (support/agent-event? m)
    (let [[new-state cmd] (app-update/handle-agent-event state m)]
      [new-state (or cmd (support/poll-cmd (:queue state)))])

    (support/external-message? m)
    (let [text (external-message-text m)]
      [(cond-> state
         (seq text) (update :messages conj {:role :assistant
                                            :text text
                                            :custom-type (:custom-type m)}))
       (support/poll-cmd (:queue state))])

    (support/agent-result? m)
    (app-update/handle-agent-result state (:result m))

    (support/agent-error? m)
    [(assoc state :phase :idle :error (:error m))
     (support/poll-cmd (:queue state))]

    (= :agent-aborted (:type m))
    (let [merged-text (shared/merge-queued-and-draft (:queued-text m) (shared/input-value state))
          status-msg  (or (:message m) "Interrupted.")]
      [(-> state
           (shared/set-input-value merged-text)
           (assoc :phase :idle)
           app-update/clear-live-turn
           (shared/append-assistant-status status-msg))
       (support/poll-cmd (:queue state))])

    (support/agent-poll? m)
    (if (= :streaming (:phase state))
      (app-update/handle-agent-poll state)
      [state (support/poll-cmd (:queue state))])

    :else nil))

(defn- handle-streaming-input
  [state m]
  (cond
    (and (= :streaming (:phase state))
         (msg/key-match? m "escape"))
    (app-update/handle-streaming-escape state)

    (and (= :streaming (:phase state))
         (msg/key-match? m "enter"))
    (app-update/handle-streaming-submit state)

    (and (= :streaming (:phase state))
         (msg/key-match? m "backspace"))
    (let [[new-input cmd] (charm/text-input-update (:input state) m)]
      [(shared/set-input-model state new-input) cmd])

    (and (= :streaming (:phase state))
         (msg/key-match? m "space"))
    (let [[new-input cmd] (charm/text-input-update (:input state) (msg/key-press " "))]
      [(shared/set-input-model state new-input) cmd])

    (and (= :streaming (:phase state))
         (msg/key-press? m))
    (let [[new-input cmd] (charm/text-input-update (:input state) m)]
      [(shared/set-input-model state new-input) cmd])

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
    (support/dispatch-ui-event! state :session/ui-set-tools-expanded {:expanded? new-expanded?})
    [(assoc state :tools-expanded? new-expanded?) nil]))

(defn- delete-prev-word-update
  [state]
  (let [before    (charm/text-input-value (:input state))
        new-state (update state :input app-update/delete-prev-word)
        after     (charm/text-input-value (:input new-state))]
    (when (support/key-debug-enabled?)
      (println (str "[key-debug] branch=alt+backspace before=" (pr-str before)
                    " after=" (pr-str after)
                    " pos=" (:pos (:input new-state)))))
    [new-state nil]))

(defn- idle-edit-update
  [state update-message next-state-fn]
  (let [[new-input cmd] (charm/text-input-update (:input state) update-message)]
    [(next-state-fn (shared/set-input-model state new-input)) cmd]))

(defn- idle-next-state-after-edit
  [state key-token]
  (if (autocomplete/autocomplete-open? state)
    (autocomplete/refresh-autocomplete state (get-in state [:prompt-input-state :autocomplete :trigger-mode]))
    (autocomplete/maybe-auto-open-autocomplete state key-token)))

(defn- autocomplete-nav-update
  [state m autocomplete?]
  (cond
    (and autocomplete? (msg/key-match? m "up")) [(autocomplete/move-autocomplete-selection state -1) nil]
    (and autocomplete? (msg/key-match? m "down")) [(autocomplete/move-autocomplete-selection state 1) nil]
    (and (not autocomplete?) (msg/key-match? m "up")) [(shared/browse-history state :up) nil]
    (and (not autocomplete?) (msg/key-match? m "down")) [(shared/browse-history state :down) nil]
    :else nil))

(defn- autocomplete-submit-update
  [state m autocomplete? run-agent-fn!]
  (cond
    (and autocomplete? (msg/key-match? m "tab")) [(autocomplete/apply-selected-autocomplete state) nil]
    (msg/key-match? m "tab") [(autocomplete/open-tab-autocomplete state) nil]
    (continue-input-line? state m) (app-update/continue-input-line state)
    (and autocomplete? (msg/key-match? m "enter"))
    (let [slash?     (= :slash_command (get-in state [:prompt-input-state :autocomplete :context]))
          next-state (autocomplete/apply-selected-autocomplete state)]
      (if slash?
        (app-update/submit-input next-state run-agent-fn!)
        [next-state nil]))
    (msg/key-match? m "enter") (app-update/submit-input state run-agent-fn!)
    :else nil))

(defn- idle-special-key-update
  [state m autocomplete?]
  (cond
    (msg/key-match? m "ctrl+d") (app-update/handle-ctrl-d state)
    (and autocomplete? (msg/key-match? m "escape")) [(autocomplete/clear-autocomplete state) nil]
    (and (not (support/has-active-dialog? state)) (msg/key-match? m "escape")) (app-update/handle-idle-escape state)
    (msg/key-match? m "ctrl+o") (toggle-tools-expanded state)
    (msg/key-match? m "alt+backspace") (delete-prev-word-update state)
    :else nil))

(defn- idle-text-edit-update
  [state m autocomplete?]
  (cond
    (msg/key-match? m "backspace")
    (idle-edit-update state m #(if autocomplete?
                                 (autocomplete/refresh-autocomplete % (get-in state [:prompt-input-state :autocomplete :trigger-mode]))
                                 %))
    (msg/key-match? m "space")
    (idle-edit-update state (msg/key-press " ") #(idle-next-state-after-edit % :space))
    (msg/key-press? m)
    (let [key-token (:key m)]
      (idle-edit-update state m #(idle-next-state-after-edit % key-token)))
    :else nil))

(defn- handle-idle-key-message
  [state m run-agent-fn!]
  (let [autocomplete? (autocomplete/autocomplete-open? state)]
    (or (idle-special-key-update state m autocomplete?)
        (autocomplete-nav-update state m autocomplete?)
        (autocomplete-submit-update state m autocomplete? run-agent-fn!)
        (idle-text-edit-update state m autocomplete?))))

(defn make-init
  ([model-name] (make-init model-name nil nil nil {}))
  ([model-name query-fn] (make-init model-name query-fn nil nil {}))
  ([model-name query-fn ui-read-fn] (make-init model-name query-fn ui-read-fn nil {}))
  ([model-name query-fn ui-read-fn ui-dispatch-fn] (make-init model-name query-fn ui-read-fn ui-dispatch-fn {}))
  ([model-name query-fn ui-read-fn ui-dispatch-fn opts]
   (support/build-init model-name query-fn ui-read-fn ui-dispatch-fn opts shared/initial-prompt-input-state)))

(defn make-update
  [run-agent-fn!]
  (fn [state m]
    (let [state (update-tick-state state)]
      (log-key-debug! m)
      (or (when (msg/key-match? m "ctrl+c")
            (app-update/handle-ctrl-c state))
          (handle-window-size-message state m)
          (when (and (support/has-active-dialog? state)
                     (or (msg/key-press? m)
                         (msg/key-match? m "escape")
                         (msg/key-match? m "up")
                         (msg/key-match? m "down")
                         (msg/key-match? m "enter")
                         (msg/key-match? m "backspace")
                         (msg/key-match? m "space")))
            (or (frontend-actions/handle-frontend-action-dialog-key state m app-update/handle-dispatch-result)
                (support/handle-dialog-key state m)
                [state nil]))
          (handle-agent-message state m)
          (when (= :selecting-session (:phase state))
            (app-update/handle-selector-key state m))
          (when (= :idle (:phase state))
            (handle-idle-key-message state m run-agent-fn!))
          (handle-streaming-input state m)
          [state nil]))))

(def view render/render-view)

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
