(ns psi.tui.app.support
  (:require
   [charm.components.text-input :as text-input]
   [charm.message :as msg]
   [charm.program :as charm-program]
   [clojure.string :as str])
  (:import
   [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def agent-result? (fn [m] (= :agent-result (:type m))))
(def agent-error?  (fn [m] (= :agent-error  (:type m))))
(def agent-poll?   (fn [m] (= :agent-poll   (:type m))))
(def agent-event?  (fn [m] (= :agent-event  (:type m))))
(def external-message? (fn [m] (= :external-message (:type m))))
(def context-updated? (fn [m] (= :context-updated (:type m))))

(defn key-token->string
  "Normalize charm key token to a string when possible."
  [k]
  (cond
    (string? k)  k
    (keyword? k) (name k)
    (char? k)    (str k)
    :else        nil))

(defn printable-key
  "Return a single printable character string for key token, else nil."
  [k]
  (let [s (key-token->string k)]
    (when (and (string? s)
               (= 1 (count s))
               (>= (int (.charAt ^String s 0)) 32))
      s)))

(defn active-dialog
  [state]
  (or (:frontend-action/dialog state)
      (get-in state [:ui-snapshot :active-dialog])))

(defn has-active-dialog? [state]
  (boolean (active-dialog state)))

(defn clear-dialog-local-state
  [state]
  (dissoc state :dialog-selected-index :dialog-input-text))

(defn dispatch-ui-event!
  [state event data]
  (when-let [f (:ui-dispatch-fn state)]
    (f event data)))

(defn resolve-dialog!
  [state dialog result]
  (dispatch-ui-event! state :session/ui-resolve-dialog {:dialog-id (:id dialog)
                                                        :result result}))

(defn cancel-dialog!
  [state]
  (dispatch-ui-event! state :session/ui-cancel-dialog {}))

(defn dialog-select-index
  [state]
  (or (:dialog-selected-index state) 0))

(defn move-dialog-selection
  [state dialog delta]
  (let [idx      (dialog-select-index state)
        last-idx (max 0 (dec (count (:options dialog))))]
    [(assoc state :dialog-selected-index
            (-> idx (+ delta) (max 0) (min last-idx)))
     nil]))

(defn selected-dialog-value
  [state dialog]
  (let [idx     (dialog-select-index state)
        options (:options dialog)]
    (when (seq options)
      (:value (nth options idx nil)))))

(defn backspace-dialog-input
  [state]
  [(assoc state :dialog-input-text
          (let [s (or (:dialog-input-text state) "")]
            (if (pos? (count s)) (subs s 0 (dec (count s))) s)))
   nil])

(defn append-dialog-input
  [state m]
  (let [ch (printable-key (:key m))]
    [(if ch
       (assoc state :dialog-input-text (str (or (:dialog-input-text state) "") ch))
       state)
     nil]))

(defn submit-dialog
  [state dialog]
  (case (:kind dialog)
    :confirm
    (do
      (resolve-dialog! state dialog true)
      [(clear-dialog-local-state state) nil])

    :select
    (let [value (selected-dialog-value state dialog)]
      (when value
        (resolve-dialog! state dialog value))
      [(clear-dialog-local-state state) nil])

    :input
    (do
      (resolve-dialog! state dialog (or (:dialog-input-text state) ""))
      [(clear-dialog-local-state state) nil])

    [state nil]))

(defn handle-dialog-key
  [state m]
  (when-let [dialog (active-dialog state)]
    (cond
      (msg/key-match? m "escape")
      (do
        (cancel-dialog! state)
        [(clear-dialog-local-state state) nil])

      (msg/key-match? m "enter")
      (submit-dialog state dialog)

      (and (= :select (:kind dialog)) (msg/key-match? m "up"))
      (move-dialog-selection state dialog -1)

      (and (= :select (:kind dialog)) (msg/key-match? m "down"))
      (move-dialog-selection state dialog 1)

      (and (= :input (:kind dialog)) (msg/key-match? m "backspace"))
      (backspace-dialog-input state)

      (and (= :input (:kind dialog)) (msg/key-press? m))
      (append-dialog-input state m)

      :else [state nil])))

(defn poll-cmd
  "Command that polls the shared event queue with a short timeout."
  ([^LinkedBlockingQueue queue]
   (poll-cmd queue 120))
  ([^LinkedBlockingQueue queue timeout-ms]
   (charm-program/cmd
    (fn []
      (if-let [event (.poll queue timeout-ms TimeUnit/MILLISECONDS)]
        (cond
          (= :done (:kind event))
          {:type :agent-result :result (:result event)}

          (= :error (:kind event))
          {:type :agent-error :error (:message event)}

          (= :aborted (:kind event))
          {:type :agent-aborted
           :message (:message event)
           :queued-text (:queued-text event)}

          (= :agent-event (:type event))
          event

          (= :external-message (:type event))
          event

          :else
          {:type :agent-poll})
        {:type :agent-poll})))))

(defn key-debug-enabled?
  []
  (contains? #{"1" "true" "yes" "on"}
             (some-> (System/getenv "PSI_TUI_DEBUG_KEYS") str/lower-case)))

(def ^:private command-refresh-query
  [:psi.extension/command-names])

(defn refresh-extension-command-names
  [state]
  (if-let [query-fn (:query-fn state)]
    (try
      (let [data     (or (query-fn command-refresh-query) {})
            ext-cmds (:psi.extension/command-names data)]
        (if (vector? ext-cmds)
          (assoc state :extension-command-names (vec ext-cmds))
          state))
      (catch Exception _
        state))
    state))

(defn build-init
  ([initial-prompt-input-state-fn] (build-init nil nil nil {} initial-prompt-input-state-fn))
  ([query-fn initial-prompt-input-state-fn] (build-init query-fn nil nil {} initial-prompt-input-state-fn))
  ([query-fn ui-read-fn initial-prompt-input-state-fn] (build-init query-fn ui-read-fn nil {} initial-prompt-input-state-fn))
  ([query-fn ui-read-fn ui-dispatch-fn initial-prompt-input-state-fn] (build-init query-fn ui-read-fn ui-dispatch-fn {} initial-prompt-input-state-fn))
  ([query-fn ui-read-fn ui-dispatch-fn opts initial-prompt-input-state-fn]
   (fn []
     (let [introspected (when query-fn
                          (query-fn [:psi.agent-session/prompt-templates
                                     :psi.agent-session/skills
                                     :psi.agent-session/extension-summary
                                     :psi.agent-session/session-id
                                     :psi.agent-session/session-file
                                     :psi.extension/command-names]))
           queue        (or (:event-queue opts) (LinkedBlockingQueue.))
           ui-snap      (when ui-read-fn (ui-read-fn))]
       [{:messages                (vec (or (:initial-messages opts) []))
         :phase                   :idle
         :error                   nil
         :input                   (text-input/text-input :prompt "刀: "
                                                         :placeholder "Type a message…"
                                                         :focused true)
         :spinner-frame           0
         :prompt-templates        (or (:psi.agent-session/prompt-templates introspected) [])
         :skills                  (or (:psi.agent-session/skills introspected) [])
         :extension-summary       (or (:psi.agent-session/extension-summary introspected) {})
         :extension-command-names (vec (:psi.extension/command-names introspected))
         :query-fn                query-fn
         :footer-model-fn         (or (:footer-model-fn opts) (constantly {}))
         :ui-read-fn              ui-read-fn
         :ui-dispatch-fn          ui-dispatch-fn
         :frontend-action-handler-fn! (:frontend-action-handler-fn! opts)
         :ui-snapshot             ui-snap
         :dialog-selected-index   nil
         :dialog-input-text       nil
         :dispatch-fn             (:dispatch-fn opts)
         :on-interrupt-fn!        (:on-interrupt-fn! opts)
         :on-queue-input-fn!      (:on-queue-input-fn! opts)
         :double-press-window-ms  (or (:double-press-window-ms opts) 500)
         :double-escape-action    (or (:double-escape-action opts) :none)
         :cwd                     (or (:cwd opts) (System/getProperty "user.dir"))
         :focus-session-id        (or (:focus-session-id opts)
                                      (:psi.agent-session/session-id introspected))
         :current-session-file    (or (:current-session-file opts)
                                      (:psi.agent-session/session-file introspected))
         :resume-fn!              (:resume-fn! opts)
         :switch-session-fn!      (:switch-session-fn! opts)
         :fork-session-fn!        (:fork-session-fn! opts)
         :session-selector-fn     (:session-selector-fn opts)
         :context-session-tree-widget (:initial-context-session-tree-widget opts)
         :context-session-tree-selected-index (when (seq (get-in opts [:initial-context-session-tree-widget :content-lines])) 0)
         :session-selector        nil
         :session-selector-mode   nil
         :prompt-input-state      (initial-prompt-input-state-fn)
         :queue                   queue
         :width                   80
         :height                  24
         :stream-text             nil
         :tool-calls              (or (:initial-tool-calls opts) {})
         :tool-order              (vec (or (:initial-tool-order opts) []))
         :active-turn-order       []
         :active-turn-items       {}
         :tool-ui-id-by-tool-id   {}
         :tool-ui-id-by-content-index {}
         :tools-expanded?         (boolean (:tools-expanded? ui-snap))
         :repaint-generation      0}
        (poll-cmd queue)]))))
