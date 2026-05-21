(ns psi.app-runtime.tui-session-nav
  "TUI session navigation callbacks: resume, switch, fork.

   Each callback follows the same pattern:
     nav action → update focus atom → emit context-updated event → return resume state.

   Extracted from `psi.app-runtime` to keep that namespace under the 800-line limit."
  (:require
   [taoensso.timbre :as timbre]
   [psi.agent-session.core :as session]
   [psi.agent-core.core :as agent]
   [psi.session-state.state :as ss]
   [psi.app-runtime.context :as app-context]
   [psi.app-runtime.context-summary :as context-summary]
   [psi.app-runtime.transcript :as transcript]))

(defn current-context-widget
  "Return a TUI context widget map for `active-session-id`, or nil when not visible."
  [ctx active-session-id]
  (let [snapshot (app-context/context-snapshot ctx active-session-id active-session-id)
        widget   (context-summary/context-widget snapshot)]
    (when (:widget/visible? widget)
      {:placement     (some-> (:widget/placement widget) name)
       :extension-id  (:widget/extension-id widget)
       :widget-id     (:widget/widget-id widget)
       :content-lines (:widget/content-lines widget)})))

(defn context-event!
  "Put a :context-updated event on `event-queue` for `active-session-id`."
  [ctx event-queue active-session-id]
  (.put event-queue {:type                      :context-updated
                     :active-session-id         active-session-id
                     :session-tree-widget       (current-context-widget ctx active-session-id)}))

(defn resume-fn!
  "Return a TUI resume callback.
   Loads `session-path`, updates `tui-focus*`, emits a context event, and returns
   TUI resume state."
  [ctx tui-focus* event-queue]
  (fn [session-path]
    (try
      (let [current-sid @tui-focus*
            sd          (session/resume-session-in! ctx current-sid session-path)
            sid         (:session-id sd)
            _           (reset! tui-focus* sid)
            _           (context-event! ctx event-queue sid)
            msgs        (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
        (transcript/agent-messages->tui-resume-state msgs))
      (catch Exception e
        (timbre/error e "Resume failed:" session-path)
        {:messages   [{:role :assistant
                       :text (str "✗ Resume failed: " (ex-message e))}]
         :tool-calls {}
         :tool-order []}))))

(defn switch-session-fn!
  "Return a TUI session-switch callback.
   Loads `session-id`, updates `tui-focus*`, emits a context event, and returns
   TUI resume state."
  [ctx tui-focus* event-queue]
  (fn [session-id]
    (try
      (let [source-session-id @tui-focus*
            sd                (session/ensure-session-loaded-in! ctx source-session-id session-id)
            sid               (:session-id sd)
            _                 (reset! tui-focus* sid)
            _                 (context-event! ctx event-queue sid)
            msgs              (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
        (transcript/agent-messages->tui-resume-state msgs))
      (catch Exception e
        (timbre/error e "Session switch failed:" session-id)
        {:messages   [{:role :assistant
                       :text (str "✗ Session switch failed: " (ex-message e))}]
         :tool-calls {}
         :tool-order []}))))

(defn fork-session-fn!
  "Return a TUI session-fork callback.
   Forks from `entry-id`, updates `tui-focus*`, emits a context event, and returns
   TUI resume state with :session-id."
  [ctx tui-focus* event-queue]
  (fn [entry-id]
    (try
      (let [source-session-id @tui-focus*
            sd                (session/fork-session-in! ctx source-session-id entry-id)
            sid               (:session-id sd)
            _                 (reset! tui-focus* sid)
            _                 (context-event! ctx event-queue sid)
            msgs              (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
        (assoc (transcript/agent-messages->tui-resume-state msgs)
               :session-id sid))
      (catch Exception e
        (timbre/error e "Session fork failed:" entry-id)
        {:messages   [{:role :assistant
                       :text (str "✗ Session fork failed: " (ex-message e))}]
         :tool-calls {}
         :tool-order []}))))
