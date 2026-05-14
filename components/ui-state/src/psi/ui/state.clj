(ns psi.ui.state
  "Shared UI state management - extracted to break circular dependencies.

   Extension UI state: dialogs, widgets, status, notifications, render registry.

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
   Fire-and-forget methods (notify, set-widget, set-status) are no-ops."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [psi.ui.widget-spec :as widget-spec]))

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
         :widget-specs      {}
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

(defn- request-dialog!
  "Request a dialog and block on its promise.
   Returns `headless-default` when `ui-state-atom` is nil."
  [ui-state-atom headless-default kind ext-id fields]
  (if (nil? ui-state-atom)
    headless-default
    (let [dialog (make-dialog kind ext-id fields)]
      (enqueue-dialog! ui-state-atom dialog)
      (deref (:promise dialog)))))

(defn request-confirm!
  "Request a confirm dialog. Blocks until the user responds.
   Returns true (confirmed) or false (cancelled).
   In headless mode (ui-state-atom is nil), returns false."
  [ui-state-atom ext-id title message]
  (let [result (request-dialog! ui-state-atom false :confirm ext-id
                                {:title title :message message})]
    (if (nil? result) false result)))

(defn request-select!
  "Request a select dialog. Blocks until the user responds.
   `options` is a vector of {:value s :label s :description s?}.
   Returns the selected :value string, or nil if cancelled.
   In headless mode, returns nil."
  [ui-state-atom ext-id title options]
  (request-dialog! ui-state-atom nil :select ext-id
                   {:title title :options options}))

(defn request-input!
  "Request an input dialog. Blocks until the user responds.
   Returns the entered text, or nil if cancelled.
   In headless mode, returns nil."
  [ui-state-atom ext-id title placeholder]
  (request-dialog! ui-state-atom nil :input ext-id
                   {:title title :placeholder placeholder}))

;; ============================================================
;; Widgets
;; ============================================================

(defn- normalize-widget-action
  [x]
  (when (map? x)
    (let [raw-type    (or (:type x) (get x "type"))
          action-type (cond
                        (keyword? raw-type) raw-type
                        (symbol? raw-type) (keyword (name raw-type))
                        (string? raw-type) (-> raw-type str/lower-case keyword)
                        :else nil)
          command     (some-> (or (:command x) (get x "command")) str str/trim)]
      (when (and (= :command action-type)
                 (seq command))
        {:type :command
         :command command}))))

(defn- normalize-widget-line
  [line]
  (cond
    (string? line)
    {:text line}

    (map? line)
    (let [text*  (or (:text line) (get line "text"))
          action (normalize-widget-action (or (:action line) (get line "action")))
          text   (cond
                   (string? text*) text*
                   (nil? text*) (or (:command action) "")
                   :else (str text*))]
      {:text text
       :action action})

    (nil? line)
    nil

    :else
    {:text (str line)}))

(defn- normalize-widget-content
  [content]
  (let [xs (cond
             (vector? content) content
             (sequential? content) (vec content)
             (nil? content) []
             :else [content])]
    (->> xs
         (map normalize-widget-line)
         (remove nil?)
         vec)))

(defn set-widget!
  "Set or replace a widget. `placement` is :above-editor or :below-editor.
   `content` may be vector<string> or vector<line-map> where line-map is
   {:text string :action {:type :command :command string}}.
   `:content` stores plain text fallback lines; `:content-lines` stores
   canonical structured lines.
   No-op when ui-state-atom is nil (headless)."
  [ui-state-atom ext-id widget-id placement content]
  (when ui-state-atom
    (let [content-lines (normalize-widget-content content)
          content-text  (mapv :text content-lines)]
      (swap! ui-state-atom assoc-in [:widgets [ext-id widget-id]]
             {:extension-id ext-id
              :widget-id    widget-id
              :placement    placement
              :content      content-text
              :content-lines content-lines}))))

(defn clear-widget!
  "Remove a widget. No-op when ui-state-atom is nil."
  [ui-state-atom ext-id widget-id]
  (when ui-state-atom
    (swap! ui-state-atom update :widgets dissoc [ext-id widget-id])))

;; ============================================================
;; Widget specs (declarative node-tree widgets)
;; ============================================================

(defn set-widget-spec!
  "Register or replace a declarative WidgetSpec for ext-id.
   spec must satisfy psi.ui.widget-spec/validate-spec.
   Returns nil on success, or {:errors [...]} when validation fails.
   No-op when ui-state-atom is nil (headless)."
  [ui-state-atom ext-id spec]
  (when ui-state-atom
    (if-let [err (widget-spec/validate-spec spec)]
      err
      (do (swap! ui-state-atom assoc-in [:widget-specs [ext-id (:id spec)]]
                 (assoc spec :extension-id ext-id))
          nil))))

(defn clear-widget-spec!
  "Remove a declarative WidgetSpec. No-op when ui-state-atom is nil."
  [ui-state-atom ext-id widget-id]
  (when ui-state-atom
    (swap! ui-state-atom update :widget-specs dissoc [ext-id widget-id])))

(defn clear-all-widget-specs-for-extension!
  "Remove all declarative WidgetSpecs for ext-id. Used on extension unload."
  [ui-state-atom ext-id]
  (when ui-state-atom
    (swap! ui-state-atom update :widget-specs
           (fn [specs]
             (into {} (remove (fn [[[eid _] _]] (= eid ext-id)) specs))))))

(defn all-widget-specs
  "Return all registered WidgetSpec maps as a vector."
  [ui-state-atom]
  (when ui-state-atom
    (vec (vals (:widget-specs @ui-state-atom)))))

(defn widget-spec-for
  "Return the WidgetSpec for [ext-id widget-id], or nil."
  [ui-state-atom ext-id widget-id]
  (when ui-state-atom
    (get-in @ui-state-atom [:widget-specs [ext-id widget-id]])))

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

(defn- dismiss-notifications!
  "Dismiss notifications matching `pred`.
   Returns the number dismissed."
  [ui-state-atom pred]
  (let [count-a (atom 0)]
    (swap! ui-state-atom update :notifications
           (fn [ns]
             (mapv (fn [n]
                     (if (pred n)
                       (do (swap! count-a inc)
                           (assoc n :dismissed? true))
                       n))
                   ns)))
    @count-a))

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
     (let [now (System/currentTimeMillis)]
       (dismiss-notifications! ui-state-atom
                               #(and (not (:dismissed? %))
                                     (> (- now (:created-at %)) max-age-ms)))))))

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
       (if (pos? overflow)
         (let [to-dismiss (set (map :id (take overflow visible)))]
           (dismiss-notifications! ui-state-atom
                                   #(to-dismiss (:id %))))
         overflow)))))

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

(defn clear-tool-renderer!
  "Remove a tool renderer entry by tool-name."
  [ui-state-atom tool-name]
  (when (and ui-state-atom (string? tool-name))
    (swap! ui-state-atom update :tool-renderers dissoc tool-name)))

(defn register-tool-def-renderers!
  "Project canonical tool-definition render hooks into interactive UI state.
   Removes any stale renderer entry when the tool definition carries neither hook."
  [ui-state-atom tool-def]
  (let [tool-name        (:name tool-def)
        ext-path         (or (:extension-path tool-def)
                             (:ext-path tool-def)
                             (:source tool-def))
        render-call-fn   (:render-call-fn tool-def)
        render-result-fn (:render-result-fn tool-def)
        hook-present?    (or (contains? tool-def :render-call-fn)
                             (contains? tool-def :render-result-fn))]
    (when (and ui-state-atom
               (string? tool-name))
      (if hook-present?
        (register-tool-renderer! ui-state-atom tool-name ext-path render-call-fn render-result-fn)
        (clear-tool-renderer! ui-state-atom tool-name)))))

(defn replace-tool-def-renderers!
  "Replace the tool renderer projection with the canonical tool-def set.
   Removes stale entries for omitted tools and projects current render hooks."
  [ui-state-atom tool-defs]
  (when ui-state-atom
    (let [tool-defs*   (vec (filter map? (or tool-defs [])))
          current-keys (set (keys (:tool-renderers @ui-state-atom)))
          next-keys    (into #{} (keep :name) tool-defs*)
          stale-keys   (set/difference current-keys next-keys)]
      (doseq [tool-name stale-keys]
        (clear-tool-renderer! ui-state-atom tool-name))
      (doseq [tool-def tool-defs*]
        (register-tool-def-renderers! ui-state-atom tool-def)))))

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
;; Pure(ish) reducers over a UI state map
;; ============================================================

(defn- reduce-ui
  "Apply atom-based op `f` over a plain ui-state map.
   Returns {:state ui-state' :result x}."
  [ui-state f & args]
  (let [ui* (atom (or ui-state {}))
        ret (apply f ui* args)]
    {:state @ui*
     :result ret}))

(defn set-widget
  [ui-state ext-id widget-id placement content]
  (reduce-ui ui-state set-widget! ext-id widget-id placement content))

(defn clear-widget
  [ui-state ext-id widget-id]
  (reduce-ui ui-state clear-widget! ext-id widget-id))

(defn set-widget-spec
  [ui-state ext-id spec]
  (reduce-ui ui-state set-widget-spec! ext-id spec))

(defn clear-widget-spec
  [ui-state ext-id widget-id]
  (reduce-ui ui-state clear-widget-spec! ext-id widget-id))

(defn resolve-dialog
  [ui-state dialog-id result]
  (reduce-ui ui-state resolve-dialog! dialog-id result))

(defn cancel-dialog
  [ui-state]
  (reduce-ui ui-state cancel-dialog!))

(defn set-status
  [ui-state ext-id text]
  (reduce-ui ui-state set-status! ext-id text))

(defn clear-status
  [ui-state ext-id]
  (reduce-ui ui-state clear-status! ext-id))

(defn notify
  [ui-state ext-id message level]
  (reduce-ui ui-state notify! ext-id message level))

(defn register-tool-renderer
  [ui-state tool-name ext-path render-call-fn render-result-fn]
  (reduce-ui ui-state register-tool-renderer! tool-name ext-path render-call-fn render-result-fn))

(defn register-tool-def-renderers
  [ui-state tool-def]
  (reduce-ui ui-state register-tool-def-renderers! tool-def))

(defn replace-tool-def-renderers
  [ui-state tool-defs]
  (reduce-ui ui-state replace-tool-def-renderers! tool-defs))

(defn register-message-renderer
  [ui-state custom-type ext-path render-fn]
  (reduce-ui ui-state register-message-renderer! custom-type ext-path render-fn))

(defn set-tools-expanded
  [ui-state expanded?]
  (reduce-ui ui-state set-tools-expanded! expanded?))

(defn dismiss-expired
  "Pure wrapper: dismiss expired notifications in a ui-state map.
   Delegates to `dismiss-expired!` via `reduce-ui`."
  ([ui-state]
   (reduce-ui ui-state dismiss-expired!))
  ([ui-state max-age-ms]
   (reduce-ui ui-state dismiss-expired! max-age-ms)))

(defn dismiss-overflow
  "Pure wrapper: dismiss overflow notifications in a ui-state map.
   Delegates to `dismiss-overflow!` via `reduce-ui`."
  ([ui-state]
   (reduce-ui ui-state dismiss-overflow!))
  ([ui-state max-visible]
   (reduce-ui ui-state dismiss-overflow! max-visible)))

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
               :widget-specs      {}
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
     :set-widget      (fn [widget-id placement content])  — flat content-lines (legacy)
     :clear-widget    (fn [widget-id])
     :set-widget-spec  (fn [spec]) → nil | {:errors [...]}  — declarative WidgetSpec
     :clear-widget-spec (fn [widget-id])
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

     :set-widget-spec
     (fn [spec]
       (set-widget-spec! ui-state-atom ext-id spec))

     :clear-widget-spec
     (fn [widget-id]
       (clear-widget-spec! ui-state-atom ext-id widget-id))

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

(defn snapshot-state
  "Return a serialisable snapshot of raw UI state.
   Strips promises and render fns."
  [s]
  (when s
    {:dialog-queue-empty?    (and (nil? (get-in s [:dialog-queue :active]))
                                  (empty? (get-in s [:dialog-queue :pending])))
     :active-dialog          (when-let [d (get-in s [:dialog-queue :active])]
                               (dissoc d :promise))
     :pending-dialog-count   (count (get-in s [:dialog-queue :pending]))
     :widgets                (vec (vals (:widgets s)))
     :widget-specs           (vec (vals (:widget-specs s)))
     :statuses               (vec (vals (:statuses s)))
     :visible-notifications  (->> (:notifications s)
                                  (remove :dismissed?)
                                  (sort-by :created-at)
                                  reverse
                                  (take default-max-visible)
                                  reverse
                                  vec)
     :tool-renderers         (mapv #(dissoc % :render-call-fn :render-result-fn)
                                   (vals (:tool-renderers s)))
     :message-renderers      (mapv #(dissoc % :render-fn)
                                   (vals (:message-renderers s)))}))

(defn snapshot
  "Return a serialisable snapshot of UI state for EQL resolvers.
   Strips promises and render fns."
  [ui-state-atom]
  (when ui-state-atom
    (snapshot-state @ui-state-atom)))
