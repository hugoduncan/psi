(ns psi.tui.app-view-runtime-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [charm.message :as msg]
   [psi.app-runtime.footer :as footer]
   [psi.app-runtime.projections :as projections]
   [psi.tui.app :as app]
   [psi.tui.ansi :as ansi]
   [psi.ui.state :as ui-state])
  (:import
   [java.util.concurrent LinkedBlockingQueue]))

(defn- default-dispatch-fn
  [text]
  (case text
    ("/quit" "/exit")  {:type :quit}
    "/resume"          {:type :resume}
    "/tree"            {:type :tree-open}
    "/new"             {:type :new-session :message "[New session started]"}
    "/status"          {:type :text :message "test status"}
    "/help"            {:type :text :message "test help"}
    "/worktree"        {:type :text :message "test worktree"}
    "/jobs"            {:type :text :message "test jobs"}
    "/job"             {:type :text :message "test job"}
    "/cancel-job"      {:type :text :message "test cancel-job"}
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

(defn- stub-agent-fn
  [response-text]
  (fn [_text ^LinkedBlockingQueue queue]
    (.put queue {:kind :done
                 :result {:role "assistant"
                          :content [{:type :text :text response-text}]}})))

(defn- type-text
  [update-fn state s]
  (reduce (fn [st ch]
            (first (update-fn st (msg/key-press (str ch)))))
          state
          s))

(deftest view-renders-banner-test
  (testing "view includes model name"
    (let [state (init-state "gpt-4o")
          out   (app/view state)]
      (is (string? out))
      (is (str/includes? out "gpt-4o")))))

(deftest view-renders-discoverable-context-session-section-when-widget-present-test
  (testing "normal TUI view renders visible session/context section from authoritative context widget"
    (let [widget {:placement "left"
                  :extension-id "psi-session"
                  :widget-id "session-tree"
                  :content-lines [{:text "main [s1] ← current [idle]"}
                                  {:text "  child [s2] [running]"}]}
          state  (assoc (init-state)
                        :context-session-tree-widget widget)
          plain  (ansi/strip-ansi (app/view (assoc state :width 120)))]
      (is (str/includes? plain "Session Context"))
      (is (str/includes? plain "▸ main [s1] ← current [idle]"))
      (is (str/includes? plain "child [s2] [running]"))
      (is (str/includes? plain "Ctrl+J/K navigate • Alt+Enter activate")))))

(deftest view-omits-discoverable-context-session-section-when-widget-absent-test
  (testing "normal TUI view omits session/context section when authoritative context widget is absent"
    (let [plain (ansi/strip-ansi (app/view (assoc (init-state) :width 120)))]
      (is (not (str/includes? plain "Session Context"))))))

(deftest view-renders-messages-test
  (testing "view renders user and assistant messages"
    (let [state (assoc (init-state)
                       :messages [{:role :user :text "hello"}
                                  {:role :assistant :text "world"}])]
      (is (str/includes? (app/view state) "hello"))
      (is (str/includes? (app/view state) "world")))))

(deftest view-clears-to-end-of-screen-test
  (testing "view appends clear-to-end sequence after /new to avoid stale lines below footer"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state)
                           :messages [{:role :assistant :text "line 1"}
                                      {:role :assistant :text "line 2"}
                                      {:role :assistant :text "line 3"}])
          typed     (type-text update-fn state "/new")
          [s1 _]    (update-fn typed (msg/key-press :enter))
          out       (app/view s1)]
      (is (str/includes? out "[New session started]"))
      (is (str/ends-with? out "\u001b[J")))))

(deftest view-renders-default-footer-from-query-test
  (testing "footer renders path, stats, provider/model, and statuses from footer-model-fn"
    (let [footer-data {:psi.agent-session/worktree-path "/repo/project"
                       :psi.agent-session/git-branch "master"
                       :psi.agent-session/session-name "session-a"
                       :psi.agent-session/session-display-name "session-a"
                       :psi.agent-session/usage-input 76000
                       :psi.agent-session/usage-output 3600
                       :psi.agent-session/usage-cache-read 1400000
                       :psi.agent-session/usage-cache-write 0
                       :psi.agent-session/usage-cost-total 0.434
                       :psi.agent-session/context-fraction 0.176
                       :psi.agent-session/context-window 400000
                       :psi.agent-session/auto-compaction-enabled true
                       :psi.agent-session/model-provider "openai"
                       :psi.agent-session/model-id "gpt-5.3-codex"
                       :psi.agent-session/model-reasoning true
                       :psi.agent-session/thinking-level :xhigh
                       :psi.ui/statuses [{:extension-id "b" :text "TS+ESL,Prett"}
                                         {:extension-id "a" :text "Clojure-LSP\nclojure-lsp"}]}
          model   (footer/footer-model-from-data footer-data {:worktree-path "/repo/project"})
          init-fn (app/make-init "test-model" nil nil nil
                                 {:cwd "/repo/project"
                                  :footer-model-fn (constantly model)})
          [state _] (init-fn)
          out (app/view (assoc state :width 120))]
      (is (str/includes? out "/repo/project (master) • session-a"))
      (is (str/includes? out "↑76k"))
      (is (str/includes? out "↓3.6k"))
      (is (str/includes? out "CR1.4M"))
      (is (str/includes? out "$0.434"))
      (is (str/includes? out "17.6%/400k (auto)"))
      (is (str/includes? out "(openai) gpt-5.3-codex • thinking xhigh"))
      (is (str/includes? out "Clojure-LSP clojure-lsp TS+ESL,Prett")))))

(deftest view-renders-footer-using-session-display-name-test
  (testing "footer uses derived display name when explicit session name is absent"
    (let [footer-data {:psi.agent-session/worktree-path "/repo/project"
                       :psi.agent-session/git-branch "master"
                       :psi.agent-session/session-name nil
                       :psi.agent-session/session-display-name "Investigate failing tests"
                       :psi.agent-session/usage-input 0
                       :psi.agent-session/usage-output 0
                       :psi.agent-session/usage-cache-read 0
                       :psi.agent-session/usage-cache-write 0
                       :psi.agent-session/usage-cost-total 0.0
                       :psi.agent-session/context-fraction nil
                       :psi.agent-session/context-window 400000
                       :psi.agent-session/auto-compaction-enabled false
                       :psi.agent-session/model-provider "openai"
                       :psi.agent-session/model-id "gpt-5.3-codex"
                       :psi.agent-session/model-reasoning true
                       :psi.agent-session/thinking-level :high
                       :psi.ui/statuses []}
          model   (footer/footer-model-from-data footer-data {:worktree-path "/repo/project"})
          init-fn (app/make-init "test-model" nil nil nil
                                 {:cwd "/repo/project"
                                  :footer-model-fn (constantly model)})
          [state _] (init-fn)
          out (app/view (assoc state :width 120))]
      (is (str/includes? out "/repo/project (master) • Investigate failing tests")))))

(deftest view-renders-session-activity-line-when-present-test
  (testing "footer renders session-activity-line from footer model when multiple sessions are active"
    (let [footer-data {:psi.agent-session/worktree-path "/repo/project"
                       :psi.agent-session/git-branch "main"
                       :psi.agent-session/session-display-name "root"
                       :psi.agent-session/usage-input 0
                       :psi.agent-session/usage-output 0
                       :psi.agent-session/usage-cache-read 0
                       :psi.agent-session/usage-cache-write 0
                       :psi.agent-session/usage-cost-total 0.0
                       :psi.agent-session/context-fraction nil
                       :psi.agent-session/context-window 200000
                       :psi.agent-session/auto-compaction-enabled false
                       :psi.agent-session/model-provider "anthropic"
                       :psi.agent-session/model-id "claude-sonnet"
                       :psi.agent-session/model-reasoning false
                       :psi.agent-session/thinking-level :off
                       :psi.ui/statuses []}
          context-sessions [{:session-id "s1" :display-name "root" :runtime-state "waiting"}
                            {:session-id "s2" :display-name "builder" :parent-session-id "s1" :runtime-state "running"}]
          model   (footer/footer-model-from-data footer-data {:worktree-path "/repo/project"
                                                              :context-sessions context-sessions})
          init-fn (app/make-init "test-model" nil nil nil
                                 {:cwd "/repo/project"
                                  :footer-model-fn (constantly model)})
          [state _] (init-fn)
          plain (ansi/strip-ansi (app/view (assoc state :width 120)))]
      (is (str/includes? plain "sessions: waiting root · running builder")))))

(deftest view-separators-track-terminal-width-test
  (testing "chat separators render to current terminal width"
    (let [width 97
          out   (app/view (assoc (init-state) :width width))
          plain (ansi/strip-ansi out)
          sep   (apply str (repeat width "─"))]
      (is (>= (count (re-seq (re-pattern (java.util.regex.Pattern/quote sep)) plain)) 2)))))

(deftest view-resume-selector-separator-tracks-terminal-width-test
  (testing "resume selector separator renders to current terminal width"
    (let [width 93
          state (assoc (init-state) :phase :selecting-session
                       :session-selector {:scope :current
                                          :cwd "/repo/project"
                                          :search ""
                                          :selected 0
                                          :sessions []})
          out   (app/view (assoc state :width width))
          plain (ansi/strip-ansi out)
          sep   (apply str (repeat width "─"))]
      (is (str/includes? plain sep)))))

(deftest view-shows-spinner-during-streaming-test
  (testing "view shows spinner while streaming"
    (let [state (assoc (init-state) :phase :streaming)
          out   (app/view state)]
      (is (str/includes? out "thinking"))
      (is (str/includes? out "Enter queues input"))
      (is (not (str/includes? out "⠋ waiting for response…"))))))

(deftest thinking-delta-updates-stream-thinking-and-renders-test
  (testing "thinking progress replaces (not appends) and is visible in streaming view"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          [s1 _]    (update-fn state {:type :agent-event :event-kind :thinking-delta :text "Plan"})
          [s2 _]    (update-fn s1 {:type :agent-event :event-kind :thinking-delta :text "Plan step"})
          out       (app/view s2)]
      (is (= "Plan step" (:stream-thinking s2)))
      (is (= ["thinking/0"] (:active-turn-order s2)))
      (is (str/includes? out "Plan step")))))

(deftest active-turn-renders-thinking-before-tool-in-arrival-order-test
  (testing "streaming view renders active-turn items in arrival order"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          [s1 _]    (update-fn state {:type :agent-event :event-kind :thinking-delta :content-index 0 :text "Plan first"})
          [s2 _]    (update-fn s1 {:type :agent-event :event-kind :tool-call-assembly :phase :start :content-index 1 :tool-id "call-1" :tool-name "read" :arguments "{\"path\":\"foo.clj\"}"})
          out       (app/view s2)
          thinking-pos (.indexOf out "Plan first")
          tool-pos     (.indexOf out "foo.clj")]
      (is (= ["thinking/0" "tool/call-1"] (:active-turn-order s2)))
      (is (<= 0 thinking-pos))
      (is (<= 0 tool-pos))
      (is (< thinking-pos tool-pos)))))

(deftest active-turn-renders-multiple-thinking-blocks-around-tool-test
  (testing "distinct content-index thinking blocks remain separate and ordered around a tool row"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          [s1 _]    (update-fn state {:type :agent-event :event-kind :thinking-delta :content-index 0 :text "Plan A"})
          [s2 _]    (update-fn s1 {:type :agent-event :event-kind :tool-call-assembly :phase :start :content-index 1 :tool-id "call-1" :tool-name "read" :arguments "{\"path\":\"a.clj\"}"})
          [s3 _]    (update-fn s2 {:type :agent-event :event-kind :thinking-delta :content-index 2 :text "Plan B"})
          out       (app/view s3)
          a-pos      (.indexOf out "Plan A")
          tool-pos   (.indexOf out "a.clj")
          b-pos      (.indexOf out "Plan B")]
      (is (= ["thinking/0" "tool/call-1" "thinking/2"] (:active-turn-order s3)))
      (is (= [:thinking :tool :thinking] (mapv :item-kind (:active-turn-events s3))))
      (is (< a-pos tool-pos))
      (is (< tool-pos b-pos)))))

