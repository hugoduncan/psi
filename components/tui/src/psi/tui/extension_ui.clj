(ns psi.tui.extension-ui
  "Extension UI state: dialogs, widgets, status, notifications, render registry.

   Design
   ──────
   All extension-contributed UI state lives in a single atom (the UI state atom),
   fitting the centralized Elm Architecture model.  The TUI view fn reads this
   atom to render widgets, status lines, notifications, and active dialogs.

   Dialogs use a promise bridge: the extension thread blocks on a promise,
   and the TUI update loop resolves it when the user responds.  Only one dialog
   is active at a time; others queue FIFO.

   Nullable pattern: `create-ui-state` returns an isolated atom.
   All public fns take the ui-state atom as first arg.

   Headless fallback: when no TUI is active, dialog methods return defaults
   immediately (confirm → false, select → nil, input → nil).
   Fire-and-forget methods (notify, set-widget, set-status) are no-ops.")

;; ============================================================
;; UI state structure
;; ============================================================

(defn create-ui-state
  "Create an isolated UI state atom.

   State map:
     :dialog-queue   {:pending [Dialog ...] :active nil}
     :widgets        {[ext-id widget-id] Widget ...}
     :statuses       {ext-id StatusEntry ...}
     :notifications  [Notification ...]
     :tool-renderers {tool-name {:render-call-fn f :render-result-fn f :extension-path s}}
     :message-renderers {custom-type {:render-fn f :extension-path s}}
     :tools-expanded? boolean"
  []
  (atom {:dialog-queue      {:pending [] :active nil}
         :widgets           {}
         :statuses          {}
         :notifications     []
         :tool-renderers    {}
         :message-renderers {}
         :tools-expanded?   false}))

;; ============================================================
;; Dialog data
;; ============================================================

(defn- make-dialog
  "Create a dialog map with a promise for cross-thread resolution."
  [kind ext-id fields]
  (let [p (promise)]
    (merge {:id            (str (java.util.UUID/randomUUID))
            :kind          kind
            :extension-id  ext-id
            :promise       p
            :resolved?     false}
           fields)))

;; ============================================================
;; Dialog queue management
;; ============================================================

(defn- advance-queue!
  "Promote the next pending dialog to active if no dialog is showing.
   Returns the newly active dialog, or nil."
  [ui-state-atom]
  (let [result (atom nil)]
    (swap! ui-state-atom
           (fn [s]
             (let [q (:dialog-queue s)]
               (if (and (nil? (:active q))
                        (seq (:pending q)))
                 (let [next-dialog (first (:pending q))]
                   (reset! result next-dialog)
                   (assoc s :dialog-queue
                          {:pending (vec (rest (:pending q)))
                           :active  next-dialog}))
                 s))))
    @result))

(defn enqueue-dialog!
  "Add a dialog to the queue and advance if possible.
   Returns the dialog (caller blocks on its :promise)."
  [ui-state-atom dialog]
  (swap! ui-state-atom update-in [:dialog-queue :pending] conj dialog)
  (advance-queue! ui-state-atom)
  dialog)

(defn resolve-dialog!
  "Resolve the active dialog with `result`. Delivers the promise,
   clears the active slot, and advances the queue.
   Returns true if the dialog was resolved, false otherwise."
  [ui-state-atom dialog-id result]
  (let [resolved? (atom false)]
    (swap! ui-state-atom
           (fn [s]
             (let [active (get-in s [:dialog-queue :active])]
               (if (and active (= dialog-id (:id active)))
                 (do (deliver (:promise active) result)
                     (reset! resolved? true)
                     (assoc-in s [:dialog-queue :active] nil))
                 s))))
    (when @resolved?
      (advance-queue! ui-state-atom))
    @resolved?))

(defn cancel-dialog!
  "Cancel the active dialog (delivers nil to the promise).
   Returns true if a dialog was cancelled."
  [ui-state-atom]
  (let [cancelled? (atom false)]
    (swap! ui-state-atom
           (fn [s]
             (let [active (get-in s [:dialog-queue :active])]
               (if active
                 (do (deliver (:promise active) nil)
                     (reset! cancelled? true)
                     (assoc-in s [:dialog-queue :active] nil))
                 s))))
    (when @cancelled?
      (advance-queue! ui-state-atom))
    @cancelled?))

(defn active-dialog
  "Return the currently active dialog, or nil."
  [ui-state-atom]
  (get-in @ui-state-atom [:dialog-queue :active]))

(defn pending-dialog-count
  "Return the number of pending dialogs."
  [ui-state-atom]
  (count (get-in @ui-state-atom [:dialog-queue :pending])))

(defn dialog-queue-empty?
  "True when no dialogs are active or pending."
  [ui-state-atom]
  (let [q (:dialog-queue @ui-state-atom)]
    (and (nil? (:active q))
         (empty? (:pending q)))))

;; ============================================================
;; Dialog creation (blocking)
;; ============================================================

(defn request-confirm!
  "Request a confirm dialog. Blocks until the user responds.
   Returns true (confirmed) or false (cancelled).
   In headless mode (ui-state-atom is nil), returns false."
  [ui-state-atom ext-id title message]
  (if (nil? ui-state-atom)
    false
    (let [dialog (make-dialog :confirm ext-id {:title title :message message})]
      (enqueue-dialog! ui-state-atom dialog)
      (let [result (deref (:promise dialog))]
        (if (nil? result) false result)))))

(defn request-select!
  "Request a select dialog. Blocks until the user responds.
   `options` is a vector of {:value s :label s :description s?}.
   Returns the selected :value string, or nil if cancelled.
   In headless mode, returns nil."
  [ui-state-atom ext-id title options]
  (if (nil? ui-state-atom)
    nil
    (let [dialog (make-dialog :select ext-id {:title title :options options})]
      (enqueue-dialog! ui-state-atom dialog)
      (deref (:promise dialog)))))

(defn request-input!
  "Request an input dialog. Blocks until the user responds.
   Returns the entered text, or nil if cancelled.
   In headless mode, returns nil."
  [ui-state-atom ext-id title placeholder]
  (if (nil? ui-state-atom)
    nil
    (let [dialog (make-dialog :input ext-id {:title title :placeholder placeholder})]
      (enqueue-dialog! ui-state-atom dialog)
      (deref (:promise dialog)))))

;; ============================================================
;; Widgets
;; ============================================================

(defn set-widget!
  "Set or replace a widget. `placement` is :above-editor or :below-editor.
   `content` is a vector of strings (one per line).
   No-op when ui-state-atom is nil (headless)."
  [ui-state-atom ext-id widget-id placement content]
  (when ui-state-atom
    (swap! ui-state-atom assoc-in [:widgets [ext-id widget-id]]
           {:extension-id ext-id
            :widget-id    widget-id
            :placement    placement
            :content      content})))

(defn clear-widget!
  "Remove a widget. No-op when ui-state-atom is nil."
  [ui-state-atom ext-id widget-id]
  (when ui-state-atom
    (swap! ui-state-atom update :widgets dissoc [ext-id widget-id])))

(defn widgets-by-placement
  "Return widgets for a given placement (:above-editor or :below-editor)."
  [ui-state-atom placement]
  (when ui-state-atom
    (->> (vals (:widgets @ui-state-atom))
         (filter #(= placement (:placement %)))
         vec)))

(defn all-widgets
  "Return all widgets as a vector."
  [ui-state-atom]
  (when ui-state-atom
    (vec (vals (:widgets @ui-state-atom)))))

;; ============================================================
;; Status
;; ============================================================

(defn set-status!
  "Set a persistent status line in the footer for `ext-id`.
   No-op when ui-state-atom is nil."
  [ui-state-atom ext-id text]
  (when ui-state-atom
    (swap! ui-state-atom assoc-in [:statuses ext-id]
           {:extension-id ext-id :text text})))

(defn clear-status!
  "Remove the status line for `ext-id`.
   No-op when ui-state-atom is nil."
  [ui-state-atom ext-id]
  (when ui-state-atom
    (swap! ui-state-atom update :statuses dissoc ext-id)))

(defn all-statuses
  "Return all status entries as a vector."
  [ui-state-atom]
  (when ui-state-atom
    (vec (vals (:statuses @ui-state-atom)))))

;; ============================================================
;; Notifications
;; ============================================================

(def ^:private default-auto-dismiss-ms 5000)
(def ^:private default-max-visible 3)

(defn notify!
  "Create a non-blocking notification.
   `level` is :info, :warning, or :error.
   No-op when ui-state-atom is nil."
  [ui-state-atom ext-id message level]
  (when ui-state-atom
    (let [notification {:id           (str (java.util.UUID/randomUUID))
                        :extension-id ext-id
                        :message      message
                        :level        (or level :info)
                        :created-at   (System/currentTimeMillis)
                        :dismissed?   false}]
      (swap! ui-state-atom update :notifications conj notification))))

(defn dismiss-expired!
  "Dismiss notifications older than `max-age-ms` (default 5000).
   Returns the number of notifications dismissed."
  ([ui-state-atom] (dismiss-expired! ui-state-atom default-auto-dismiss-ms))
  ([ui-state-atom max-age-ms]
   (when ui-state-atom
     (let [now      (System/currentTimeMillis)
           expired? (fn [n] (and (not (:dismissed? n))
                                 (> (- now (:created-at n)) max-age-ms)))
           count-a  (atom 0)]
       (swap! ui-state-atom update :notifications
              (fn [ns]
                (mapv (fn [n]
                        (if (expired? n)
                          (do (swap! count-a inc)
                              (assoc n :dismissed? true))
                          n))
                      ns)))
       @count-a))))

(defn visible-notifications
  "Return non-dismissed notifications, capped at `max-visible`."
  ([ui-state-atom] (visible-notifications ui-state-atom default-max-visible))
  ([ui-state-atom max-visible]
   (when ui-state-atom
     (->> (:notifications @ui-state-atom)
          (remove :dismissed?)
          (take-last max-visible)
          vec))))

(defn dismiss-overflow!
  "Dismiss oldest notifications beyond `max-visible`.
   Returns the number dismissed."
  ([ui-state-atom] (dismiss-overflow! ui-state-atom default-max-visible))
  ([ui-state-atom max-visible]
   (when ui-state-atom
     (let [visible  (->> (:notifications @ui-state-atom) (remove :dismissed?))
           overflow (max 0 (- (count visible) max-visible))]
       (when (pos? overflow)
         (let [to-dismiss (set (map :id (take overflow visible)))]
           (swap! ui-state-atom update :notifications
                  (fn [ns]
                    (mapv (fn [n]
                            (if (to-dismiss (:id n))
                              (assoc n :dismissed? true)
                              n))
                          ns)))))
       overflow))))

;; ============================================================
;; Render registry
;; ============================================================

(defn register-tool-renderer!
  "Register custom render fns for a tool's call and/or result display.
   `render-call-fn` is (fn [args] → string) or nil.
   `render-result-fn` is (fn [result opts] → string) or nil."
  [ui-state-atom tool-name ext-path render-call-fn render-result-fn]
  (when ui-state-atom
    (swap! ui-state-atom assoc-in [:tool-renderers tool-name]
           {:tool-name        tool-name
            :extension-path   ext-path
            :render-call-fn   render-call-fn
            :render-result-fn render-result-fn})))

(defn register-message-renderer!
  "Register a custom render fn for extension-injected messages.
   `render-fn` is (fn [message opts] → string)."
  [ui-state-atom custom-type ext-path render-fn]
  (when ui-state-atom
    (swap! ui-state-atom assoc-in [:message-renderers custom-type]
           {:custom-type    custom-type
            :extension-path ext-path
            :render-fn      render-fn})))

(defn get-tool-renderer
  "Return the renderer map for `tool-name`, or nil."
  [ui-state-atom tool-name]
  (when ui-state-atom
    (get-in @ui-state-atom [:tool-renderers tool-name])))

(defn get-message-renderer
  "Return the renderer map for `custom-type`, or nil."
  [ui-state-atom custom-type]
  (when ui-state-atom
    (get-in @ui-state-atom [:message-renderers custom-type])))

(defn all-tool-renderers
  "Return all tool renderer maps as a vector."
  [ui-state-atom]
  (when ui-state-atom
    (vec (vals (:tool-renderers @ui-state-atom)))))

(defn all-message-renderers
  "Return all message renderer maps as a vector."
  [ui-state-atom]
  (when ui-state-atom
    (vec (vals (:message-renderers @ui-state-atom)))))

(defn get-tools-expanded
  "Return global tools-expanded flag.
   Headless fallback: false."
  [ui-state-atom]
  (if ui-state-atom
    (boolean (:tools-expanded? @ui-state-atom))
    false))

(defn set-tools-expanded!
  "Set global tools-expanded flag.
   No-op when ui-state-atom is nil (headless)."
  [ui-state-atom expanded?]
  (when ui-state-atom
    (swap! ui-state-atom assoc :tools-expanded? (boolean expanded?))))

;; ============================================================
;; Clear all (for extension reload)
;; ============================================================

(defn clear-all!
  "Clear all extension UI state. Cancels any active and pending dialogs.
   Used during extension reload."
  [ui-state-atom]
  (when ui-state-atom
    ;; Snapshot all dialogs before clearing — active + pending
    (let [s       @ui-state-atom
          active  (get-in s [:dialog-queue :active])
          pending (get-in s [:dialog-queue :pending])]
      ;; Reset state first to prevent advance-queue! from promoting
      (reset! ui-state-atom
              {:dialog-queue      {:pending [] :active nil}
               :widgets           {}
               :statuses          {}
               :notifications     []
               :tool-renderers    {}
               :message-renderers {}
               :tools-expanded?   false})
      ;; Deliver nil to all dialog promises
      (when active (deliver (:promise active) nil))
      (doseq [d pending]
        (deliver (:promise d) nil)))))

;; ============================================================
;; UI context map (passed to extension handlers)
;; ============================================================

(defn create-ui-context
  "Build the :ui context map for an extension at `ext-id`.
   When `ui-state-atom` is nil (headless), returns nil.

   The returned map provides:
     :confirm     (fn [title message]) → boolean
     :select      (fn [title options]) → string?
     :input       (fn [title placeholder?]) → string?
     :set-widget  (fn [widget-id placement content])
     :clear-widget (fn [widget-id])
     :set-status  (fn [text])
     :clear-status (fn [])
     :notify      (fn [message level])
     :register-tool-renderer    (fn [tool-name render-call-fn render-result-fn])
     :register-message-renderer (fn [custom-type render-fn])
     :get-tools-expanded (fn [] → boolean)
     :set-tools-expanded (fn [expanded?])"
  [ui-state-atom ext-id]
  (when ui-state-atom
    {:confirm
     (fn [title message]
       (request-confirm! ui-state-atom ext-id title message))

     :select
     (fn [title options]
       (request-select! ui-state-atom ext-id title options))

     :input
     (fn [title & [placeholder]]
       (request-input! ui-state-atom ext-id title placeholder))

     :set-widget
     (fn [widget-id placement content]
       (set-widget! ui-state-atom ext-id widget-id placement content))

     :clear-widget
     (fn [widget-id]
       (clear-widget! ui-state-atom ext-id widget-id))

     :set-status
     (fn [text]
       (set-status! ui-state-atom ext-id text))

     :clear-status
     (fn []
       (clear-status! ui-state-atom ext-id))

     :notify
     (fn [message level]
       (notify! ui-state-atom ext-id message level))

     :register-tool-renderer
     (fn [tool-name render-call-fn render-result-fn]
       (register-tool-renderer! ui-state-atom tool-name ext-id
                                render-call-fn render-result-fn))

     :register-message-renderer
     (fn [custom-type render-fn]
       (register-message-renderer! ui-state-atom custom-type ext-id render-fn))

     :get-tools-expanded
     (fn []
       (get-tools-expanded ui-state-atom))

     :set-tools-expanded
     (fn [expanded?]
       (set-tools-expanded! ui-state-atom expanded?))}))

;; ============================================================
;; EQL snapshot (for resolvers — excludes fns and promises)
;; ============================================================

(defn snapshot
  "Return a serialisable snapshot of UI state for EQL resolvers.
   Strips promises and render fns."
  [ui-state-atom]
  (when ui-state-atom
    (let [s @ui-state-atom]
      {:dialog-queue-empty?    (and (nil? (get-in s [:dialog-queue :active]))
                                    (empty? (get-in s [:dialog-queue :pending])))
       :active-dialog          (when-let [d (get-in s [:dialog-queue :active])]
                                 (dissoc d :promise))
       :pending-dialog-count   (count (get-in s [:dialog-queue :pending]))
       :widgets                (vec (vals (:widgets s)))
       :statuses               (vec (vals (:statuses s)))
       :visible-notifications  (visible-notifications ui-state-atom)
       :tool-renderers         (mapv #(dissoc % :render-call-fn :render-result-fn)
                                     (vals (:tool-renderers s)))
       :message-renderers      (mapv #(dissoc % :render-fn)
                                     (vals (:message-renderers s)))})))
