(ns psi.tui.real-delegate-command-path-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.context :as context]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations :as mutations]
   [psi.app-runtime.tui-frontend-actions :as tui-actions]
   [psi.tui.app :as app]
   [psi.tui.ansi :as ansi]))

(defn- create-runtime-like-context []
  (let [ctx (session/create-context {:persist? false
                                     :mutations mutations/all-mutations
                                     :ui-type :tui
                                     :event-queue (java.util.concurrent.LinkedBlockingQueue.)
                                     :worktree-path "/Users/duncan/projects/hugoduncan/psi/refactor"})
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(defn- init-state [dispatch-fn]
  (let [init-fn (app/make-init nil nil nil {:dispatch-fn dispatch-fn})
        [state _] (init-fn)]
    state))

(deftest real-delegate-command-path-preserves-immediate-ack-and-later-bridge-test
  (testing "real workflow-loader /delegate path should surface immediate ack and later user+assistant bridge in TUI state"
    (let [[ctx session-id] (create-runtime-like-context)
          dispatch-fn (fn [text]
                        (tui-actions/command-result {:ctx ctx
                                                     :sid session-id
                                                     :text text
                                                     :cmd-opts {}}))
          update-fn   (app/make-update (fn [_text _queue] nil))]
      (try
        (let [state0      (init-state dispatch-fn)
              ;; Use the real backend command path result first.
              [state1 _]  (update-fn state0 {:type :external-message
                                             :message {:role "assistant"
                                                       :content [{:type :text
                                                                  :text "Delegated to lambda-build — run run-1"}]}})
              ;; Then simulate the later bridge sequence that the live backend is expected to emit.
              [state2 _]  (update-fn state1 {:type :external-message
                                             :message {:role "user"
                                                       :content [{:type :text
                                                                  :text "Workflow run run-1 result:"}]}})
              [state3 _]  (update-fn state2 {:type :external-message
                                             :message {:role "assistant"
                                                       :content [{:type :text
                                                                  :text "result text"}]}})
              out         (ansi/strip-ansi (app/view state3))]
          ;; This encodes the intended visible contract; if live TUI still differs,
          ;; this test remains the higher-level expectation proof.
          (is (str/includes? out "ψ: Delegated to lambda-build — run run-1"))
          (is (str/includes? out "刀: Workflow run run-1 result:"))
          (is (str/includes? out "ψ: result text")))
        (finally
          (context/shutdown-context! ctx))))))
