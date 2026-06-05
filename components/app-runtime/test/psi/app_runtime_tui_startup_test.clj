(ns psi.app-runtime-tui-startup-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.test-support :as test-support]
   [psi.app-runtime :as app-runtime]
   [psi.app-runtime.test-support :as app-test-support]
   [psi.memory.runtime :as memory-runtime]
   [psi.provider-auth.oauth.core :as oauth]
   [psi.session-journal.store :as journal-store]
   [psi.session-persistence.core :as persist]
   [psi.session-state.state :as ss]))

(deftest start-tui-runtime-forwards-memory-runtime-opts-to-bootstrap-sync-test
  ;; Characterizes public TUI startup memory-runtime option forwarding without
  ;; stubbing bootstrap-runtime-session!.
  (let [captured-sync-opts* (atom nil)
        memory-runtime-opts {:store-provider "in-memory"
                             :retention-snapshots 17
                             :retention-deltas 23}]
    (app-test-support/with-session-state-restore
      (fn []
        (with-redefs-fn (merge (app-test-support/bootstrap-stub-bindings)
                               {#'app-runtime/resolve-model (fn [_] app-test-support/test-ai-model)
                                #'ext/discover-extension-paths (fn [& _] [])
                                #'memory-runtime/sync-memory-layer! (fn [opts]
                                                                      (reset! captured-sync-opts* opts)
                                                                      {:ok? true})})
          (fn []
            (is (= :ok (app-runtime/start-tui-runtime!
                        (fn [_run-agent-fn _opts] :ok)
                        :ignored memory-runtime-opts {})))
            (is (= memory-runtime-opts
                   (select-keys @captured-sync-opts* (keys memory-runtime-opts))))
            (is (string? (:cwd @captured-sync-opts*)))))))))

(deftest start-tui-runtime-forwards-session-config-and-thinking-override-to-context-test
  ;; Characterizes public TUI startup runtime configuration forwarding through
  ;; the created context/session, not by stubbing create-runtime-session-context.
  (test-support/with-temp-session-root
    (fn [session-root]
      (app-test-support/with-session-state-restore
        (fn []
          (let [session-config {:llm-stream-idle-timeout-ms 65432
                                :tool-timeout-ms 3210}
                startup-opts   {:session-root session-root
                                :thinking-level-override :high}]
            (with-redefs-fn (merge (app-test-support/bootstrap-stub-bindings)
                                   {#'app-runtime/resolve-model
                                    (fn [_]
                                      (assoc app-test-support/test-ai-model
                                             :supports-reasoning true))
                                    #'ext/discover-extension-paths (fn [& _] [])})
              (fn []
                (is (= :ok (app-runtime/start-tui-runtime!
                            (fn [_run-agent-fn _opts] :ok)
                            :ignored {} session-config startup-opts)))
                (let [{:keys [ctx]} @app-runtime/session-state
                      session-id    (-> (ss/list-context-sessions-in ctx) first :session-id)
                      sd            (ss/get-session-data-in ctx session-id)]
                  (is (= session-config
                         (select-keys (:config ctx) (keys session-config))))
                  (is (= :high (:thinking-level sd))))))))))))

(deftest start-tui-runtime-wires-active-input-callbacks-to-focused-session-test
  ;; Characterizes public TUI active-input callbacks against real session
  ;; state.  No tui-wiring/session callback helpers are stubbed.
  (app-test-support/with-session-state-restore
    (fn []
      (with-redefs-fn (merge (app-test-support/bootstrap-stub-bindings)
                             {#'app-runtime/resolve-model (fn [_] app-test-support/test-ai-model)
                              #'ext/discover-extension-paths (fn [& _] [])})
        (fn []
          (let [captured-opts* (atom nil)]
            (is (= :ok (app-runtime/start-tui-runtime!
                        (fn [_run-agent-fn opts]
                          (reset! captured-opts* opts)
                          :ok)
                        :ignored {} {})))
            (let [opts       @captured-opts*
                  ctx        (:ctx @app-runtime/session-state)
                  session-id (:focus-session-id opts)]
              (session/dispatch-in! ctx :session/prompt {:session-id session-id} {:origin :core})
              (session/dispatch-in! ctx :on-streaming-entered {:session-id session-id} {:origin :statechart})
              (swap! (:data-atom (ss/agent-ctx-in ctx session-id))
                     assoc :pending-tool-calls #{"keep-streaming-for-queue"})
              (is (= {:message "Queued steering message."}
                     ((:on-queue-input-fn! opts) "steer while streaming" {})))
              (is (= ["steer while streaming"]
                     (:steering-messages (ss/get-session-data-in ctx session-id))))
              (is (= {:queued-text "steer while streaming"
                      :message "Interrupted active work."}
                     ((:on-interrupt-fn! opts) {})))
              (let [sd (ss/get-session-data-in ctx session-id)]
                (is (= :idle (ss/sc-phase-in ctx session-id)))
                (is (= [] (:steering-messages sd)))
                (is (= [] (:follow-up-messages sd)))))))))))

(deftest start-tui-runtime-wires-ui-projection-dispatch-footer-and-selector-test
  ;; Characterizes public TUI UI projection/chrome callbacks against real
  ;; session and extension-UI state.  No tui-wiring/lower option assembly is stubbed.
  (app-test-support/with-session-state-restore
    (fn []
      (with-redefs-fn (merge (app-test-support/bootstrap-stub-bindings)
                             {#'app-runtime/resolve-model (fn [_] app-test-support/test-ai-model)
                              #'ext/discover-extension-paths (fn [& _] [])})
        (fn []
          (let [captured-opts* (atom nil)]
            (is (= :ok (app-runtime/start-tui-runtime!
                        (fn [_run-agent-fn opts]
                          (reset! captured-opts* opts)
                          :ok)
                        :ignored {} {})))
            (let [opts       @captured-opts*
                  ctx        (:ctx @app-runtime/session-state)
                  session-id (:focus-session-id opts)
                  target-id  (:session-id
                              (session/create-top-level-session-in!
                               ctx session-id {:session-name "selector target"}))]
              ((:ui-dispatch-fn opts) :session/ui-set-widget
                                      {:extension-id "ext-tt8"
                                       :widget-id "status-card"
                                       :placement :above-editor
                                       :content ["Projected status"]})
              ((:ui-dispatch-fn opts) :session/ui-set-status
                                      {:extension-id "ext-tt8" :text "ready"})
              (let [snapshot ((:ui-read-fn opts))
                    footer   ((:footer-model-fn opts))
                    selector ((:session-selector-fn opts))]
                (is (= ["ext-tt8" "status-card" ["Projected status"]]
                       (some (fn [widget]
                               (when (= ["ext-tt8" "status-card"]
                                        [(:extension-id widget) (:widget-id widget)])
                                 [(:extension-id widget) (:widget-id widget) (:content widget)]))
                             (:widgets snapshot))))
                (is (= "ready"
                       (some (fn [status]
                               (when (= "ext-tt8" (:extension-id status))
                                 (:text status)))
                             (:statuses snapshot))))
                (is (= "ready"
                       (some (fn [status]
                               (when (= "ext-tt8" (:status/extension-id status))
                                 (:status/text status)))
                             (:footer/statuses footer))))
                (is (string? (get-in footer [:footer/model :id])))
                (is (= :select-session (:ui/action-id selector)))
                (is (= :preserve (:ui/order selector)))
                (is (some #(= {:action/kind :switch-session
                               :action/session-id session-id}
                              (:ui.item/value %))
                          (:ui/items selector)))
                (is (some #(= {:action/kind :switch-session
                               :action/session-id target-id}
                              (:ui.item/value %))
                          (:ui/items selector)))
                (is (some #(and (= session-id (get-in % [:ui.item/meta :item/session-id]))
                                (true? (get-in % [:ui.item/meta :item/is-active])))
                          (:ui/items selector)))))))))))

(deftest start-tui-runtime-frontend-action-fork-and-resume-use-real-navigation-test
  ;; Characterizes the public frontend-action/fork/resume callback semantics,
  ;; not just their callability, against real session state.
  (test-support/with-temp-session-root
    (fn [session-root]
      (app-test-support/with-session-state-restore
        (fn []
          (with-redefs-fn (merge (app-test-support/bootstrap-stub-bindings)
                                 {#'app-runtime/resolve-model (fn [_] app-test-support/test-ai-model)
                                  #'ext/discover-extension-paths (fn [& _] [])})
            (fn []
              (let [captured-opts*  (atom nil)
                    direct-fork-id* (atom nil)]
                (is (= :ok (app-runtime/start-tui-runtime!
                            (fn [_run-agent-fn opts]
                              (reset! captured-opts* opts)
                              :ok)
                            :ignored {} {} {:session-root session-root})))
                (let [opts               @captured-opts*
                      ctx                (:ctx @app-runtime/session-state)
                      session-id         (:focus-session-id opts)
                      parent-user-entry  (persist/message-entry {:role "user"
                                                                 :content [{:type :text :text "fork from here"}]
                                                                 :timestamp (java.time.Instant/now)})
                      parent-user-id     (:id (ss/append-journal-entry-in! ctx session-id parent-user-entry))
                      _                  (ss/append-journal-entry-in!
                                          ctx session-id
                                          (persist/message-entry {:role "assistant"
                                                                  :content [{:type :text :text "parent reply"}]
                                                                  :timestamp (java.time.Instant/now)}))
                      fork-action-result {:ui.result/action-key :select-session
                                          :ui.result/status :submitted
                                          :ui.result/value {:action/kind :fork-session
                                                            :action/entry-id parent-user-id}}
                      fork-result        ((:frontend-action-handler-fn! opts) fork-action-result)
                      fork-id            (:session-id fork-result)]
                  (is (= :session-switch-restored (:type fork-result)))
                  (is (= [{:role :user :text "fork from here"}
                          {:role :assistant :text "parent reply"}]
                         (get-in fork-result [:restored :messages])))
                  (is (= fork-id (:psi.agent-session/session-id
                                  ((:query-fn opts) [:psi.agent-session/session-id]))))
                  (let [fork-event (.poll (:event-queue opts) 2000 java.util.concurrent.TimeUnit/MILLISECONDS)]
                    (is (= :context-updated (:type fork-event)))
                    (is (= fork-id (:active-session-id fork-event))))
                  (let [direct-fork-result ((:fork-session-fn! opts) parent-user-id)
                        direct-fork-id     (:session-id direct-fork-result)]
                    (reset! direct-fork-id* direct-fork-id)
                    (is (= [{:role :user :text "fork from here"}
                            {:role :assistant :text "parent reply"}]
                           (:messages direct-fork-result)))
                    (is (= direct-fork-id (:psi.agent-session/session-id
                                           ((:query-fn opts) [:psi.agent-session/session-id]))))
                    (let [direct-fork-event (.poll (:event-queue opts) 2000 java.util.concurrent.TimeUnit/MILLISECONDS)]
                      (is (= :context-updated (:type direct-fork-event)))
                      (is (= direct-fork-id (:active-session-id direct-fork-event)))))
                  (let [resume-file   (java.io.File/createTempFile "psi-tui-resume" ".ndedn")
                        resume-path   (.getAbsolutePath resume-file)
                        resume-entry  (persist/message-entry {:role "assistant"
                                                              :content [{:type :text :text "resumed transcript"}]
                                                              :timestamp (java.time.Instant/now)})]
                    (.deleteOnExit resume-file)
                    (journal-store/flush-journal! resume-file "resumed-session" session-root nil [resume-entry])
                    (is (= {:messages [{:role :assistant :text "resumed transcript"}]
                            :tool-calls {}
                            :tool-order []}
                           ((:resume-fn! opts) resume-path)))
                    (is (= "resumed-session" (:psi.agent-session/session-id
                                              ((:query-fn opts) [:psi.agent-session/session-id]))))
                    (let [resume-sd    (ss/get-session-data-in ctx "resumed-session")
                          resume-event (.poll (:event-queue opts) 2000 java.util.concurrent.TimeUnit/MILLISECONDS)]
                      (is (= (:model (ss/get-session-data-in ctx @direct-fork-id*))
                             (:model resume-sd)))
                      (is (= :context-updated (:type resume-event)))
                      (is (= "resumed-session" (:active-session-id resume-event))))))))))))))

(deftest start-tui-runtime-queues-idle-follow-up-input-test
  ;; Characterizes the non-streaming on-queue-input branch through public TUI
  ;; startup opts and real session state.
  (app-test-support/with-session-state-restore
    (fn []
      (with-redefs-fn (merge (app-test-support/bootstrap-stub-bindings)
                             {#'app-runtime/resolve-model (fn [_] app-test-support/test-ai-model)
                              #'ext/discover-extension-paths (fn [& _] [])})
        (fn []
          (let [captured-opts* (atom nil)]
            (is (= :ok (app-runtime/start-tui-runtime!
                        (fn [_run-agent-fn opts]
                          (reset! captured-opts* opts)
                          :ok)
                        :ignored {} {})))
            (let [opts       @captured-opts*
                  ctx        (:ctx @app-runtime/session-state)
                  session-id (:focus-session-id opts)]
              (is (= :idle (ss/sc-phase-in ctx session-id)))
              (is (= {:message "Queued follow-up message."}
                     ((:on-queue-input-fn! opts) "follow up after idle" {})))
              (is (= ["follow up after idle"]
                     (:follow-up-messages (ss/get-session-data-in ctx session-id))))
              (is (= {:queued-text "follow up after idle"
                      :message "Interrupted active work."}
                     ((:on-interrupt-fn! opts) {})))
              (is (= [] (:follow-up-messages (ss/get-session-data-in ctx session-id)))))))))))

(deftest start-tui-runtime-completes-pending-login-from-auth-code-input-test
  ;; Characterizes the public TUI pending-login handoff: /login command dispatch
  ;; stores pending login, then run-agent-fn consumes the next input as auth code.
  (app-test-support/with-session-state-restore
    (fn []
      (let [completed* (atom nil)
            oauth-ctx  (oauth/create-null-context
                        {:providers [{:id                   :anthropic
                                      :name                 "Anthropic OAuth"
                                      :uses-callback-server false
                                      :begin-login          (fn []
                                                              {:url "https://auth.example/start"
                                                               :login-state {:nonce "login-nonce"}})
                                      :complete-login       (fn [code login-state]
                                                              (reset! completed* {:code code
                                                                                  :login-state login-state})
                                                              {:type :oauth
                                                               :access (str "token-for-" code)
                                                               :refresh "refresh-token"
                                                               :expires (+ (System/currentTimeMillis) 3600000)})
                                      :refresh-token        (fn [credential] credential)
                                      :get-api-key          :access}]})]
        (with-redefs-fn (merge (app-test-support/bootstrap-stub-bindings)
                               {#'app-runtime/resolve-model (fn [_] app-test-support/test-ai-model)
                                #'ext/discover-extension-paths (fn [& _] [])
                                #'oauth/create-context (fn [] oauth-ctx)})
          (fn []
            (let [captured* (atom nil)]
              (is (= :ok (app-runtime/start-tui-runtime!
                          (fn [run-agent-fn opts]
                            (reset! captured* {:run-agent-fn run-agent-fn
                                               :opts opts})
                            :ok)
                          :ignored {} {})))
              (let [{:keys [run-agent-fn opts]} @captured*
                    dispatch-result ((:dispatch-fn opts) "/login")]
                (is (= :login-start (:type dispatch-result)))
                (is (= "https://auth.example/start" (:url dispatch-result)))
                (is (= {:provider-id :anthropic
                        :provider-name "Anthropic OAuth"
                        :login-state {:nonce "login-nonce"}}
                       (:pending-login @app-runtime/session-state)))
                (is (nil? ((:dispatch-fn opts) "auth-code-123"))
                    "while login is pending, non-command input falls through to run-agent-fn")
                (let [queue  (java.util.concurrent.LinkedBlockingQueue.)
                      _      (run-agent-fn " auth-code-123 " queue)
                      result (.poll queue 2000 java.util.concurrent.TimeUnit/MILLISECONDS)]
                  (is (= {:kind :done
                          :result {:role "assistant"
                                   :content [{:type :text
                                              :text "✓ Logged in to Anthropic OAuth"}]}}
                         result))
                  (is (nil? (:pending-login @app-runtime/session-state)))
                  (is (= {:code "auth-code-123"
                          :login-state {:nonce "login-nonce"}}
                         @completed*))
                  (is (= "token-for-auth-code-123"
                         (oauth/get-api-key oauth-ctx :anthropic))))))))))))
