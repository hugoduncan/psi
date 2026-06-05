(ns psi.app-runtime-tui-startup-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.test-support :as test-support]
   [psi.app-runtime :as app-runtime]
   [psi.app-runtime.test-support :as app-test-support]
   [psi.memory.runtime :as memory-runtime]
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
