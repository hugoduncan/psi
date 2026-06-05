(ns psi.app-runtime-tui-startup-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.extensions :as ext]
   [psi.app-runtime :as app-runtime]
   [psi.app-runtime.test-support :as app-test-support]
   [psi.memory.runtime :as memory-runtime]))

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
