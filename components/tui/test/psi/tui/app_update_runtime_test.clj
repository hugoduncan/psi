(ns psi.tui.app-update-runtime-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [charm.components.text-input :as text-input]
   [charm.message :as msg]
   [psi.app-runtime.projections :as projections]
   [psi.app-runtime.ui-actions :as ui-actions]
   [psi.tui.app :as app]
   [psi.tui.app.update :as app-update]
   [psi.ui.state :as ui-state])
  (:import
   [java.util.concurrent LinkedBlockingQueue]))

(defn- default-dispatch-fn
  [text]
  (case text
    ("/quit" "/exit") {:type :quit}
    "/resume" {:type :resume}
    "/tree" {:type :tree-open}
    "/new" {:type :new-session :message "[New session started]"}
    "/status" {:type :text :message "test status"}
    "/help" {:type :text :message "test help"}
    "/worktree" {:type :text :message "test worktree"}
    "/jobs" {:type :text :message "test jobs"}
    "/job" {:type :text :message "test job"}
    "/cancel-job" {:type :text :message "test cancel-job"}
    nil))

(defn- init-state
  ([] (init-state "test-model" {}))
  ([model-name] (init-state model-name {}))
  ([model-name opts]
   (let [ui-atom      (:ui-state* opts)
         ui-read-fn*  (:ui-read-fn opts)
         opts'        (dissoc opts :ui-state* :ui-read-fn)
         ui-read-fn   (or ui-read-fn*
                          (when ui-atom
                            (fn []
                              (projections/extension-ui-snapshot-from-state @ui-atom))))
         ui-disp-fn   (when ui-atom
                        (fn [event-type payload]
                          (case event-type
                            :session/ui-set-tools-expanded
                            (ui-state/set-tools-expanded! ui-atom (:expanded? payload))
                            nil)))
         init-fn      (app/make-init model-name nil ui-read-fn ui-disp-fn
                                     (merge {:dispatch-fn default-dispatch-fn} opts'))
         [state _cmd] (init-fn)]
     state)))

(defn- type-text
  [update-fn state s]
  (reduce (fn [st ch]
            (first (update-fn st (msg/key-press (str ch)))))
          state
          s))

(defn- stub-agent-fn
  [response-text]
  (fn [_text ^LinkedBlockingQueue queue]
    (.put queue {:kind :done
                 :result {:role "assistant"
                          :content [{:type :text :text response-text}]}})))

(defn- submit-text
  [update-fn state s]
  (let [typed         (type-text update-fn state s)
        [submitted _] (update-fn typed (msg/key-press :enter))]
    (if (= :streaming (:phase submitted))
      (first (update-fn submitted {:type :agent-result
                                   :result {:content [{:type :text :text "ok"}]}}))
      submitted)))

(defn- error-agent-fn
  [error-msg]
  (fn [_text ^LinkedBlockingQueue queue]
    (.put queue {:kind :error :message error-msg})))

(deftest agent-result-transitions-to-idle-test
  (testing "agent-result message adds assistant message and goes idle"
    (let [update-fn (app/make-update (stub-agent-fn "hello"))
          state     (init-state)
          streaming (assoc state :phase :streaming
                           :messages [{:role :user :text "hi"}])
          result    {:role "assistant" :content [{:type :text :text "hello"}]}
          [s1 cmd]  (update-fn streaming {:type :agent-result :result result})]
      (is (= :idle (:phase s1)))
      (is (= 2 (count (:messages s1))))
      (is (= "hello" (:text (second (:messages s1)))))
      (is (some? cmd)))))

(deftest context-updated-loads-context-widget-and-removes-it-authoritatively-test
  (testing "authoritative context-updated sets context widget when present and clears it when later omitted"
    (let [widget    {:placement "left"
                     :extension-id "psi-session"
                     :widget-id "session-tree"
                     :content-lines [{:text "main [s1] ← current [idle]"}]}
          update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state "test-model")
          [s1 _]    (update-fn state {:type :context-updated
                                      :active-session-id "s1"
                                      :session-tree-widget widget})
          [s2 _]    (update-fn s1 {:type :context-updated
                                   :active-session-id "s1"
                                   :session-tree-widget nil})]
      (is (= "session-tree" (get-in s1 [:context-session-tree-widget :widget-id])))
      (is (zero? (:context-session-tree-selected-index s1)))
      (is (nil? (:context-session-tree-widget s2)))
      (is (nil? (:context-session-tree-selected-index s2))))))

(deftest context-widget-preserves-across-unrelated-refreshes-while-authoritative-widget-remains-test
  (testing "unrelated refreshes preserve context widget while authoritative context state still includes it"
    (let [widget    {:placement "left"
                     :extension-id "psi-session"
                     :widget-id "session-tree"
                     :content-lines [{:text "main [s1] ← current [idle]"}
                                     {:text "  child [s2] [running]"}]}
          ui-atom    (atom {:widgets {[:ext "w1"] {:placement :above-editor
                                                   :extension-id "ext"
                                                   :widget-id "w1"
                                                   :content ["widget body"]}}
                            :widget-specs {}
                            :statuses {}
                            :notifications {}
                            :tool-renderers {}
                            :message-renderers {}
                            :dialog-queue {:active nil :pending []}
                            :tools-expanded? false})
          update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state "test-model"
                                {:ui-state* ui-atom
                                 :initial-context-session-tree-widget widget})
          [s1 _]    (update-fn state {:type :agent-poll})
          _         (swap! ui-atom assoc-in [:widgets [:ext "w2"]]
                           {:placement :below-editor
                            :extension-id "ext"
                            :widget-id "w2"
                            :content ["other widget"]})
          [s2 _]    (update-fn s1 {:type :agent-poll})]
      (is (= widget (:context-session-tree-widget s1)))
      (is (= widget (:context-session-tree-widget s2)))
      (is (= 2 (count (get-in s2 [:ui-snapshot :widgets])))))))

(deftest agent-error-transitions-to-idle-test
  (testing "agent-error message sets error and goes idle"
    (let [update-fn (app/make-update (error-agent-fn "boom"))
          streaming (assoc (init-state) :phase :streaming)
          [s1 cmd]  (update-fn streaming {:type :agent-error :error "boom"})]
      (is (= :idle (:phase s1)))
      (is (= "boom" (:error s1)))
      (is (some? cmd)))))

(deftest poll-advances-spinner-test
  (testing "agent-poll increments spinner-frame"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          streaming (assoc (init-state) :phase :streaming
                           :spinner-frame 0)
          [s1 cmd]  (update-fn streaming {:type :agent-poll})]
      (is (= 1 (:spinner-frame s1)))
      (is (some? cmd)))))

(deftest escape-idle-does-not-quit-by-default-test
  (testing "escape when idle and no menu does not quit"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 cmd]  (update-fn state (msg/key-press :escape))]
      (is (= :idle (:phase s1)))
      (is (nil? cmd)))))

(deftest ctrl-c-clears-first-then-quits-within-window-test
  (testing "first ctrl+c clears input; second ctrl+c within window quits"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state)
                           :input (text-input/set-value (:input (init-state)) "hello"))
          [s1 cmd1] (update-fn state (msg/key-press "c" :ctrl true))
          [_s2 cmd2] (update-fn s1 (msg/key-press "c" :ctrl true))]
      (is (= "" (text-input/value (:input s1))))
      (is (nil? cmd1))
      (is (some? cmd2)))))

(deftest ctrl-d-exits-only-when-input-empty-test
  (testing "ctrl+d exits when input empty and is ignored when input non-empty"
    (let [update-fn   (app/make-update (stub-agent-fn ""))
          non-empty   (assoc (init-state)
                             :input (text-input/set-value (:input (init-state)) "x"))
          [_s1 cmd1]  (update-fn non-empty (msg/key-press "d" :ctrl true))
          empty-state (assoc (init-state)
                             :input (text-input/set-value (:input (init-state)) ""))
          [_s2 cmd2]  (update-fn empty-state (msg/key-press "d" :ctrl true))]
      (is (nil? cmd1))
      (is (some? cmd2)))))

(deftest escape-streaming-interrupts-and-restores-queued-text-test
  (testing "escape during streaming calls interrupt hook and restores queued input"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state "test-model"
                                       {:on-interrupt-fn! (fn [_]
                                                            {:queued-text "queued one\nqueued two"
                                                             :message "Interrupted active work."})})
                           :phase :streaming
                           :input (text-input/set-value (:input (init-state)) "draft"))
          [s1 _cmd] (update-fn state (msg/key-press :escape))]
      (is (= :idle (:phase s1)))
      (is (= "queued one\nqueued two\ndraft" (text-input/value (:input s1))))
      (is (= "Interrupted active work."
             (:text (last (:messages s1))))))))

(deftest streaming-enter-queues-input-test
  (testing "enter during streaming queues draft text via callback and keeps streaming"
    (let [queued    (atom nil)
          update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state "test-model"
                                       {:on-queue-input-fn! (fn [text _]
                                                              (reset! queued text)
                                                              {:message "Queued steering message."})})
                           :phase :streaming
                           :input (text-input/set-value (:input (init-state)) "steer this"))
          [s1 _cmd] (update-fn state (msg/key-press :enter))]
      (is (= :streaming (:phase s1)))
      (is (= "steer this" @queued))
      (is (= "" (text-input/value (:input s1))))
      (is (= "Queued steering message." (:text (last (:messages s1))))))))

(deftest streaming-input-remains-editable-test
  (testing "printable input and backspace mutate editor while streaming"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state)
                           :phase :streaming
                           :input (text-input/set-value (:input (init-state)) "ab"))
          [s1 _]    (update-fn state (msg/key-press "c"))
          [s2 _]    (update-fn s1 (msg/key-press :backspace))]
      (is (= "abc" (text-input/value (:input s1))))
      (is (= "ab" (text-input/value (:input s2)))))))

(deftest context-widget-direct-activation-submits-dispatch-command-test
  (testing "visible context section can activate the selected line directly"
    (let [captured   (atom [])
          dispatch-fn (fn [text]
                        (swap! captured conj text)
                        (case text
                          "/tree s2" {:type :tree-switch :session-id "s2"}
                          {:type :text :message (str "unhandled: " text)}))
          switch-fn  (fn [sid]
                       {:messages [{:role :assistant :text (str "switched to " sid)}]
                        :tool-calls {}
                        :tool-order []
                        :session-id sid})
          widget     {:placement "left"
                      :extension-id "psi-session"
                      :widget-id "session-tree"
                      :content-lines [{:text "main [s1] ← current [waiting]"}
                                      {:text "  child [s2] [running]"
                                       :action {:type "command" :command "/tree s2"}}]}
          update-fn  (app/make-update (stub-agent-fn ""))
          state      (init-state "test-model" {:dispatch-fn dispatch-fn
                                               :switch-session-fn! switch-fn
                                               :initial-context-session-tree-widget widget})
          [s1 _]     (update-fn state (msg/key-press "j" :ctrl true))
          [s2 _]     (update-fn s1 (msg/key-press :enter :alt true))]
      (is (= ["/tree s2"] @captured))
      (is (= :idle (:phase s2)))
      (is (= "s2" (:focus-session-id s2)))
      (is (= "switched to s2" (get-in s2 [:messages 0 :text]))))))

(deftest double-escape-unsupported-action-is-safe-no-op-with-status-test
  (testing "unsupported double-escape action does not crash and emits status"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state "test-model" {:double-escape-action :tree})
                           :input (text-input/set-value (:input (init-state)) ""))
          [s1 cmd1] (update-fn state (msg/key-press :escape))
          [s2 cmd2] (update-fn s1 (msg/key-press :escape))]
      (is (nil? cmd1))
      (is (nil? cmd2))
      (is (str/includes? (:text (last (:messages s2))) "not available")))))

(deftest history-records-trimmed-non-empty-and-skips-consecutive-duplicates-test
  (testing "submitted prompts are trimmed, blank ignored, and consecutive duplicates skipped"
    (let [update-fn (app/make-update (stub-agent-fn "ok"))
          s1        (submit-text update-fn (init-state) "   ")
          s2        (submit-text update-fn s1 "  alpha  ")
          s3        (submit-text update-fn s2 "alpha")
          s4        (submit-text update-fn s3 "beta")
          entries   (get-in s4 [:prompt-input-state :history :entries])]
      (is (= ["alpha" "beta"] entries)))))

(deftest history-cap-is-100-most-recent-entries-test
  (testing "history keeps at most 100 latest entries"
    (let [update-fn (app/make-update (stub-agent-fn "ok"))
          end-state (reduce (fn [st i]
                              (submit-text update-fn st (str "p-" i)))
                            (init-state)
                            (range 105))
          entries   (get-in end-state [:prompt-input-state :history :entries])]
      (is (= 100 (count entries)))
      (is (= "p-5" (first entries)))
      (is (= "p-104" (last entries))))))

(deftest frontend-action-select-model-submit-test
  (testing "backend-requested select-model opens TUI affordance and submit returns canonical action-result semantics"
    (let [captured    (atom nil)
          update-fn   (app/make-update (stub-agent-fn ""))
          action      (ui-actions/model-picker-action [{:provider "openai"
                                                        :id "gpt-5.3"
                                                        :reasoning true}
                                                       {:provider "anthropic"
                                                        :id "claude-sonnet"
                                                        :reasoning false}])
          state       (init-state "test-model" {:frontend-action-handler-fn! #(do (reset! captured %) nil)})
          [opened _]  (app-update/handle-dispatch-result state {:type :frontend-action
                                                                :request-id "req-1"
                                                                :ui/action action})
          [submitted _] (update-fn opened (msg/key-press :enter))]
      (is (= "Select a model" (get-in opened [:frontend-action/dialog :title])))
      (is (= :submitted (:ui.result/status @captured)))
      (is (= "req-1" (:ui.result/request-id @captured)))
      (is (= :select-model (:ui.result/action-key @captured)))
      (is (= action (:ui.result/ui-action @captured)))
      (is (= {:provider "openai" :id "gpt-5.3"} (:ui.result/value @captured)))
      (is (nil? (:frontend-action/dialog submitted))))))

(deftest frontend-action-select-model-cancel-test
  (testing "backend-requested select-model cancel returns canonical cancelled semantics"
    (let [captured  (atom nil)
          update-fn (app/make-update (stub-agent-fn ""))
          action    (ui-actions/model-picker-action [{:provider "openai" :id "gpt-5.3" :reasoning true}])
          state     (init-state "test-model" {:frontend-action-handler-fn! #(do (reset! captured %) nil)})
          [opened _] (app-update/handle-dispatch-result state {:type :frontend-action
                                                               :request-id "req-2"
                                                               :ui/action action})
          [closed _] (update-fn opened (msg/key-press :escape))]
      (is (= :cancelled (:ui.result/status @captured)))
      (is (= "req-2" (:ui.result/request-id @captured)))
      (is (= :select-model (:ui.result/action-key @captured)))
      (is (= action (:ui.result/ui-action @captured)))
      (is (= "Cancelled select-model." (:ui.result/message @captured)))
      (is (nil? (:frontend-action/dialog closed))))))

(deftest frontend-action-select-thinking-level-submit-and-cancel-test
  (testing "backend-requested select-thinking-level submit and cancel preserve canonical semantics"
    (let [captured  (atom [])
          update-fn (app/make-update (stub-agent-fn ""))
          action    (ui-actions/thinking-picker-action)
          state     (init-state "test-model" {:frontend-action-handler-fn! #(swap! captured conj %)})
          [opened _] (app-update/handle-dispatch-result state {:type :frontend-action
                                                               :request-id "req-3"
                                                               :ui/action action})
          [moved _]  (update-fn opened (msg/key-press :down))
          [_ _]      (update-fn moved (msg/key-press :enter))
          [opened2 _] (app-update/handle-dispatch-result state {:type :frontend-action
                                                                :request-id "req-4"
                                                                :ui/action action})
          [_ _]      (update-fn opened2 (msg/key-press :escape))
          [submitted cancelled] @captured]
      (is (= :submitted (:ui.result/status submitted)))
      (is (= "req-3" (:ui.result/request-id submitted)))
      (is (= :select-thinking-level (:ui.result/action-key submitted)))
      (is (= action (:ui.result/ui-action submitted)))
      (is (= "minimal" (:ui.result/value submitted)))
      (is (= :cancelled (:ui.result/status cancelled)))
      (is (= "req-4" (:ui.result/request-id cancelled)))
      (is (= :select-thinking-level (:ui.result/action-key cancelled)))
      (is (= action (:ui.result/ui-action cancelled)))
      (is (= "Cancelled select-thinking-level." (:ui.result/message cancelled))))))

(deftest unsupported-frontend-action-is-bounded-and-state-safe-test
  (testing "unsupported frontend action reports clearly and clears stale frontend-action state"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          stale-action (ui-actions/model-picker-action [{:provider "openai" :id "gpt-5.3" :reasoning true}])
          state     (assoc (init-state)
                           :messages [{:role :assistant :text "keep me"}]
                           :phase :selecting-session
                           :frontend-action/request-id "req-stale"
                           :frontend-action/ui-action stale-action
                           :frontend-action/dialog {:frontend-action? true
                                                    :kind :select
                                                    :title "Stale"
                                                    :options [{:label "old" :value :old}]}
                           :session-selector {:frontend-action? true :selected 0}
                           :session-selector-mode :resume
                           :dialog-selected-index 0)
          action    {:ui/action-name :select-unknown
                     :ui/prompt "Unsupported"}
          [s1 cmd]  (app-update/handle-dispatch-result state {:type :frontend-action
                                                              :request-id "req-bad"
                                                              :ui/action action})
          [s2 _]    (update-fn s1 (msg/key-press "x"))]
      (is (nil? cmd))
      (is (= :idle (:phase s1)))
      (is (nil? (:frontend-action/request-id s1)))
      (is (nil? (:frontend-action/ui-action s1)))
      (is (nil? (:frontend-action/dialog s1)))
      (is (nil? (:session-selector s1)))
      (is (nil? (:session-selector-mode s1)))
      (is (nil? (:dialog-selected-index s1)))
      (is (= "keep me" (get-in s1 [:messages 0 :text])))
      (is (= "Unsupported frontend action: :select-unknown"
             (get-in s1 [:messages 1 :text])))
      (is (= "x" (text-input/value (:input s2)))))))

(deftest frontend-action-cancel-feedback-visible-in-transcript-test
  (testing "cancelled model picker produces a visible assistant message in the transcript"
    (let [update-fn (app/make-update (fn [_ _]))
          action    (ui-actions/model-picker-action [{:provider "openai"
                                                      :id "gpt-5.3"
                                                      :reasoning true}])
          handler   (fn [action-result]
                      (let [{:ui.result/keys [status message]} action-result]
                        (when (= :cancelled status)
                          {:type :text :message message})))
          state     (init-state "test-model" {:frontend-action-handler-fn! handler})
          [opened _]  (app-update/handle-dispatch-result state {:type :frontend-action
                                                                :request-id "req-cancel"
                                                                :ui/action action})
          [closed _]  (update-fn opened (msg/key-press :escape))
          last-msg    (last (:messages closed))]
      (is (= :assistant (:role last-msg)))
      (is (= "Cancelled select-model." (:text last-msg))))))
