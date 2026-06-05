(ns psi.app-runtime.tui-wiring
  "TUI callback and options-map construction.

   Owns the wiring between session runtime and the TUI interface function:
   - Command dispatch (with login-state management)
   - Agent execution (prompt submission + login code handling)
   - Interrupt handling
   - Queue/steering input
   - TUI options map assembly

   Extracted from `psi.app-runtime/start-tui-runtime!` to separate
   presentation wiring from runtime setup."
  (:require
   [clojure.string :as str]
   [psi.agent-session.core :as session]
   [psi.session-state.state :as ss]
   [psi.provider-auth.oauth.core :as oauth]
   [psi.app-runtime.footer :as footer]
   [psi.app-runtime.projections :as projections]
   [psi.app-runtime.selectors :as selectors]
   [psi.app-runtime.tui-frontend-actions :as tui-frontend-actions]
   [psi.app-runtime.ui-actions :as ui-actions]))

(defn make-dispatch-fn
  "Build the TUI command dispatch function.

   Returns nil when a login is pending (falls through to run-agent-fn!).
   Otherwise dispatches text as a command via `tui-frontend-actions/command-result`,
   journals the command, and handles login-start results.

   `session-state` is the shared app-runtime atom for login-state tracking."
  [{:keys [ctx tui-focus* session-state cmd-opts]}]
  (fn [text]
    (if (:pending-login @session-state)
      nil
      (let [sid    @tui-focus*
            result (tui-frontend-actions/command-result
                    {:ctx ctx :sid sid :text text :cmd-opts cmd-opts})
            _      (tui-frontend-actions/journal-command-result!
                    {:ctx ctx :sid sid :text text :result result})]
        (when (= :login-start (:type result))
          (when-not (:uses-callback-server result)
            (swap! session-state assoc :pending-login
                   {:provider-id   (get-in result [:provider :id])
                    :provider-name (get-in result [:provider :name])
                    :login-state   (:login-state result)})))
        result))))

(defn make-run-agent-fn
  "Build the TUI agent execution function.

   Handles two cases:
   - Step 2 of a pending login (auth code submission)
   - Normal agent prompt submission via `submit-prompt-fn!`

   `submit-prompt-fn!` signature: (fn [ctx sid ai-model text images opts])
   `session-state` is the shared app-runtime atom for login-state tracking."
  [{:keys [ctx tui-focus* session-state ai-model oauth-ctx submit-prompt-fn!]}]
  (fn [text ^java.util.concurrent.LinkedBlockingQueue queue]
    (let [trimmed (str/trim text)
          pending (:pending-login @session-state)]
      (cond
        ;; Step 2: pending login — this input IS the auth code
        pending
        (future
          (try
            (let [{:keys [provider-id provider-name login-state]} pending]
              (swap! session-state dissoc :pending-login)
              (oauth/complete-login! oauth-ctx provider-id trimmed login-state)
              (.put queue {:kind :done
                           :result {:role    "assistant"
                                    :content [{:type :text :text (str "✓ Logged in to " provider-name)}]}}))
            (catch Exception e
              (swap! session-state dissoc :pending-login)
              (.put queue {:kind :done
                           :result {:role    "assistant"
                                    :content [{:type :text :text (str "✗ Login failed: " (ex-message e))}]}}))))

        ;; Everything else — send to agent
        :else
        (future
          (try
            (let [sid @tui-focus*
                  {:keys [assistant-message]}
                  (submit-prompt-fn! ctx sid ai-model text nil
                                     {:progress-queue queue
                                      :sync-on-git-head-change? true})]
              (.put queue {:kind :done :result assistant-message}))
            (catch Exception e
              (.put queue {:kind :error :message (ex-message e)}))))))))

(defn make-on-interrupt-fn
  "Build the TUI interrupt handler (Escape during active work)."
  [{:keys [ctx tui-focus*]}]
  (fn [_state]
    (let [sid @tui-focus*]
      (session/abort-in! ctx sid)
      {:queued-text (session/consume-queued-input-text-in! ctx sid)
       :message "Interrupted active work."})))

(defn make-frontend-action-handler-fn
  "Build the TUI frontend action handler (model picker, session selector, etc.)."
  [{:keys [ctx tui-focus* resolve-model-by-provider+id switch-session-fn! fork-session-fn!]}]
  (fn [action-result]
    (tui-frontend-actions/handle-action-result
     {:ctx ctx
      :sid @tui-focus*
      :action-result action-result
      :resolve-model-by-provider+id resolve-model-by-provider+id
      :switch-session-fn! switch-session-fn!
      :fork-session-fn! fork-session-fn!
      :set-focus! #(reset! tui-focus* %)})))

(defn build-tui-opts
  "Assemble the TUI options map from wired callbacks and session state.

   Takes already-constructed callback fns plus session/context data, and returns
   the map passed to `tui-start-fn!`."
  [{:keys [ctx tui-focus* event-queue cwd
           startup-rehydrate
           dispatch-fn on-interrupt-fn!
           frontend-action-handler-fn!
           resume-fn! switch-session-fn! fork-session-fn!
           current-context-widget]}]
  (let [initial-sid @tui-focus*]
    {:query-fn             (fn [q] (session/query-in ctx @tui-focus* q))
     :footer-model-fn      (fn [] (footer/footer-model ctx @tui-focus*))
     :session-selector-fn  (fn [] (ui-actions/context-session-action
                                   (selectors/context-session-selector ctx @tui-focus*)))
     :initial-context-session-tree-widget current-context-widget
     :ui-read-fn       (fn [] (projections/extension-ui-snapshot ctx))
     :ui-dispatch-fn   (fn [event-type payload]
                         (session/dispatch-in! ctx event-type payload {:origin :tui}))
     :frontend-action-handler-fn! frontend-action-handler-fn!
     :dispatch-fn          dispatch-fn
     :on-interrupt-fn!     on-interrupt-fn!
     :on-queue-input-fn!   (fn [text _state]
                             (let [sid @tui-focus*]
                               (if (= :streaming (ss/sc-phase-in ctx sid))
                                 (do
                                   (session/queue-while-streaming-in! ctx sid text :steer)
                                   {:message "Queued steering message."})
                                 (do
                                   (session/follow-up-in! ctx sid text)
                                   {:message "Queued follow-up message."}))))
     :double-press-window-ms 500
     :double-escape-action :none
     :cwd                  cwd
     :focus-session-id     initial-sid
     :current-session-file (:session-file (ss/get-session-data-in ctx initial-sid))
     :initial-messages     (vec (or (:messages startup-rehydrate) []))
     :initial-tool-calls   (or (:tool-calls startup-rehydrate) {})
     :initial-tool-order   (vec (or (:tool-order startup-rehydrate) []))
     :resume-fn!           resume-fn!
     :switch-session-fn!   switch-session-fn!
     :fork-session-fn!     fork-session-fn!
     :event-queue          event-queue
     :alt-screen           false}))
