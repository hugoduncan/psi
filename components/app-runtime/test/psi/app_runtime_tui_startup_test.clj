(ns psi.app-runtime-tui-startup-test
  (:require
   [clojure.test :refer [deftest is]]
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
