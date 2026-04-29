(ns psi.tui.app-view-runtime-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [charm.message :as msg]
   [psi.app-runtime.footer :as footer]
   [psi.app-runtime.projections :as projections]
   [psi.tui.app :as app]
   [psi.tui.ansi :as ansi]
   [psi.tui.app.render :as render]
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
  ([] (init-state {}))
  ([opts]
   (let [launch-model (:launch-model opts)
         ui-atom      (:ui-state* opts)
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
         init-fn      (app/make-init nil ui-read-fn ui-disp-fn
                                     (merge {:dispatch-fn default-dispatch-fn} opts'))
         _            launch-model
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
  (testing "view includes canonical footer model text"
    (let [state (init-state {:launch-model "launch-model"
                             :footer-model-fn (constantly {:footer/model {:text "effective-model"}})})
          out   (app/view state)]
      (is (string? out))
      (is (str/includes? out "effective-model"))
      (is (not (str/includes? out "launch-model"))))))

(deftest banner-updates-when-footer-model-changes-test
  (testing "banner follows current canonical footer model without rebuilding init state"
    (let [model* (atom {:footer/model {:text "model-a"}})
          state  (init-state {:launch-model "launch-model"
                              :footer-model-fn (fn [] @model*)})
          out-a  (ansi/strip-ansi (app/view state))
          _      (reset! model* {:footer/model {:text "model-b"}})
          out-b  (ansi/strip-ansi (app/view state))]
      (is (str/includes? out-a "model-a"))
      (is (not (str/includes? out-a "model-b")))
      (is (str/includes? out-b "model-b"))
      (is (not (str/includes? out-b "model-a"))))))

(deftest banner-renders-default-effective-model-test
  (testing "banner renders effective default model text when provided canonically"
    (let [state (init-state {:launch-model "launch-model"
                             :footer-model-fn (constantly {:footer/model {:text "default-effective-model"}})})
          out   (ansi/strip-ansi (app/view state))]
      (is (str/includes? out "default-effective-model")))))

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

(deftest view-renders-new-session-message-test
  (testing "view renders new-session message after /new"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state)
                           :messages [{:role :assistant :text "line 1"}
                                      {:role :assistant :text "line 2"}
                                      {:role :assistant :text "line 3"}])
          typed     (type-text update-fn state "/new")
          [s1 _]    (update-fn typed (msg/key-press :enter))
          out       (app/view s1)]
      (is (str/includes? out "[New session started]"))
      ;; ESC[J (clear-to-end) is no longer emitted — JLine Display handles diffing
      (is (not (str/includes? out "\u001b[J"))))))

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
                                         {:extension-id "a" :text "Formatter\nformatter"}]}
          model   (footer/footer-model-from-data footer-data {:worktree-path "/repo/project"})
          init-fn (app/make-init nil nil nil
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
      (is (str/includes? out "Formatter formatter TS+ESL,Prett")))))

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
          init-fn (app/make-init nil nil nil
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
          init-fn (app/make-init nil nil nil
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
      ;; latest text is stored in active-turn-items, not :stream-thinking (removed)
      (is (= "Plan step" (get-in s2 [:active-turn-items "thinking/0" :text])))
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
      (is (= [:thinking :tool :thinking]
             (mapv #(get-in s3 [:active-turn-items % :item-kind]) (:active-turn-order s3))))
      (is (< a-pos tool-pos))
      (is (< tool-pos b-pos)))))

;; ── New tests for 054 ──────────────────────────────────────────────────────────

(deftest thinking-dedup-renders-single-line-test
  (testing "N thinking deltas for the same content-index render as exactly one line"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          ;; send 5 deltas — each carries the full cumulative text so far
          [s _]     (reduce (fn [[st _] txt]
                              (update-fn st {:type :agent-event :event-kind :thinking-delta
                                             :content-index 0 :text txt}))
                            [state nil]
                            ["P" "Pl" "Pla" "Plan" "Plan step"])
          out       (ansi/strip-ansi (app/view s))]
      ;; only one occurrence of the bullet prefix
      (is (= 1 (count (re-seq #"· " out))))
      ;; shows the latest text
      (is (str/includes? out "Plan step"))
      ;; does not show earlier partial texts as separate lines
      (is (not (str/includes? out "· P\n"))))))

(deftest tool-lifecycle-dedup-renders-single-row-test
  (testing "tool going through all lifecycle stages renders as exactly one row"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          events    [{:type :agent-event :event-kind :tool-call-assembly :phase :start
                      :content-index 0 :tool-id "t1" :tool-name "read" :arguments "{}"}
                     {:type :agent-event :event-kind :tool-call-assembly :phase :end
                      :content-index 0 :tool-id "t1" :tool-name "read" :arguments "{}"}
                     {:type :agent-event :event-kind :tool-start :tool-id "t1" :tool-name "read"}
                     {:type :agent-event :event-kind :tool-executing :tool-id "t1" :tool-name "read"
                      :parsed-args {:path "foo.clj"}}
                     {:type :agent-event :event-kind :tool-result :tool-id "t1" :tool-name "read"
                      :content [{:type :text :text "file content"}] :is-error false}]
          [s _]     (reduce (fn [[st _] ev] (update-fn st ev)) [state nil] events)]
      ;; single entry in active-turn-order for this tool
      (is (= ["tool/t1"] (:active-turn-order s)))
      ;; tool status is :success after :tool-result
      (is (= :success (get-in s [:tool-calls "tool/t1" :status]))))))

(deftest archive-on-done-thinking-visible-in-messages-test
  (testing "thinking blocks from result content are archived into messages before assistant reply"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          result    {:role "assistant"
                     :content [{:type :thinking :text "Let me reason about this."}
                               {:type :text :text "The answer is 42."}]}
          [s _]     (update-fn state {:type :agent-result :result result})
          msgs      (:messages s)]
      (is (= :idle (:phase s)))
      ;; thinking message appears before assistant reply
      (is (some #(= {:role :thinking :text "Let me reason about this."} %) msgs))
      (is (some #(= {:role :assistant :text "The answer is 42."} %) msgs))
      (let [thinking-idx (.indexOf msgs {:role :thinking :text "Let me reason about this."})
            assistant-idx (.indexOf msgs {:role :assistant :text "The answer is 42."})]
        (is (< thinking-idx assistant-idx))))))

(deftest archive-on-done-no-thinking-unchanged-test
  (testing "result with no thinking blocks produces only the assistant message"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          result    {:role "assistant"
                     :content [{:type :text :text "Plain answer."}]}
          [s _]     (update-fn state {:type :agent-result :result result})
          msgs      (:messages s)]
      (is (= [{:role :assistant :text "Plain answer."}] msgs)))))

(deftest render-message-thinking-role-test
  (testing "render-message with :thinking role uses · prefix and thinking style"
    (let [rendered (ansi/strip-ansi
                    (render/render-message {:role :thinking :text "some thought"} 80))]
      (is (str/includes? rendered "· some thought")))))

(deftest narrow-width-submitted-user-prompt-wraps-test
  (testing "submitted user prompts wrap with continuation aligned under content start"
    (let [state (assoc (init-state)
                       :width 24
                       :messages [{:role :user
                                   :text "this user prompt should wrap cleanly"}])
          plain (ansi/strip-ansi (app/view state))
          lines (str/split-lines plain)
          wrapped (filter #(or (str/includes? % "this user")
                               (str/starts-with? % "    "))
                          lines)]
      (is (some #(str/includes? % "刀: this user") wrapped))
      (is (> (count wrapped) 1))
      (is (every? #(<= (count %) 24) wrapped)))))

(deftest narrow-width-assistant-message-wraps-test
  (testing "assistant paragraph transcript wraps within available width"
    (let [state (assoc (init-state)
                       :width 24
                       :messages [{:role :assistant
                                   :text "this assistant message should wrap cleanly"}])
          plain (ansi/strip-ansi (app/view state))
          lines (str/split-lines plain)
          wrapped (filter #(or (str/includes? % "this assistant")
                               (str/starts-with? % "   "))
                          lines)]
      (is (some #(str/includes? % "ψ: this assistant") wrapped))
      (is (> (count wrapped) 1))
      (is (every? #(<= (count %) 24) wrapped)))))

(deftest narrow-width-thinking-line-wraps-test
  (testing "thinking transcript wraps after the · prefix budget"
    (let [rendered (ansi/strip-ansi (render/render-message {:role :thinking
                                                            :text "thinking text should wrap cleanly"}
                                                           20))
          lines    (str/split-lines rendered)]
      (is (> (count lines) 1))
      (is (str/starts-with? (first lines) "· "))
      (is (every? #(<= (count %) 20) lines))
      (is (some #(str/starts-with? % "  ") (rest lines))))))

(deftest archive-on-done-thinking-visible-in-view-test
  (testing "archived thinking messages render with · prefix in the view"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          result    {:role "assistant"
                     :content [{:type :thinking :text "My reasoning here."}
                               {:type :text :text "Done."}]}
          [s _]     (update-fn state {:type :agent-result :result result})
          out       (ansi/strip-ansi (app/view s))]
      (is (str/includes? out "· My reasoning here."))
      (is (str/includes? out "Done.")))))

(deftest archive-on-done-preserves-streaming-arrival-order-tool-then-thinking-test
  (testing "when tool arrives before thinking during streaming, post-turn messages preserve that order"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          ;; Tool arrives first (content-index 0), then thinking at content-index 2
          [s1 _]    (update-fn state {:type :agent-event :event-kind :tool-call-assembly
                                      :phase :end :content-index 0
                                      :tool-id "t1" :tool-name "read"
                                      :arguments "{\"path\":\"foo.clj\"}"})
          [s2 _]    (update-fn s1 {:type :agent-event :event-kind :tool-result
                                   :tool-id "t1" :tool-name "read"
                                   :content [{:type :text :text "file content"}]
                                   :is-error false})
          [s3 _]    (update-fn s2 {:type :agent-event :event-kind :thinking-delta
                                   :content-index 2 :text "Post-tool thinking."})
          ;; Result content has thinking first (build-final-content sorts it that way),
          ;; but active-turn-order must override that and keep tool before thinking.
          result    {:role "assistant"
                     :content [{:type :thinking :text "Post-tool thinking."}
                               {:type :tool-call :id "t1" :name "read"
                                :arguments "{\"path\":\"foo.clj\"}"}
                               {:type :text :text "Done."}]}
          [s4 _]    (update-fn s3 {:type :agent-result :result result})
          msgs      (:messages s4)
          tool-idx  (.indexOf msgs {:role :tool :tool-id "tool/t1"})
          think-idx (.indexOf msgs {:role :thinking :text "Post-tool thinking."})
          asst-idx  (.indexOf msgs {:role :assistant :text "Done."})]
      (is (= :idle (:phase s4)))
      ;; streaming arrival order: tool before thinking
      (is (= ["tool/t1" "thinking/2"] (:active-turn-order s3)))
      ;; post-turn messages must preserve that order
      (is (< tool-idx think-idx))
      (is (< think-idx asst-idx)))))

(deftest archive-on-done-view-preserves-streaming-arrival-order-tool-then-thinking-test
  (testing "rendered view after turn completes shows tool row before thinking line when tool arrived first"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          [s1 _]    (update-fn state {:type :agent-event :event-kind :tool-call-assembly
                                      :phase :end :content-index 0
                                      :tool-id "t1" :tool-name "read"
                                      :arguments "{\"path\":\"foo.clj\"}"})
          [s2 _]    (update-fn s1 {:type :agent-event :event-kind :tool-result
                                   :tool-id "t1" :tool-name "read"
                                   :content [{:type :text :text "file content"}]
                                   :is-error false})
          [s3 _]    (update-fn s2 {:type :agent-event :event-kind :thinking-delta
                                   :content-index 2 :text "Post-tool thinking."})
          result    {:role "assistant"
                     :content [{:type :thinking :text "Post-tool thinking."}
                               {:type :tool-call :id "t1" :name "read"
                                :arguments "{\"path\":\"foo.clj\"}"}
                               {:type :text :text "Done."}]}
          [s4 _]    (update-fn s3 {:type :agent-result :result result})
          out       (ansi/strip-ansi (app/view s4))
          tool-pos  (.indexOf out "foo.clj")
          think-pos (.indexOf out "· Post-tool thinking.")
          asst-pos  (.indexOf out "Done.")]
      (is (str/includes? out "foo.clj"))
      (is (str/includes? out "· Post-tool thinking."))
      (is (str/includes? out "Done."))
      ;; tool row before thinking, thinking before assistant text
      (is (< tool-pos think-pos))
      (is (< think-pos asst-pos)))))

;; ── Tool rendering post-turn and ctrl+o toggle ────────────────────────────────

(deftest tool-rows-visible-after-turn-completes-test
  (testing "tool rows remain visible in idle phase after turn completes, before assistant text"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          [s1 _]    (update-fn state {:type :agent-event :event-kind :tool-call-assembly
                                      :phase :end :content-index 0
                                      :tool-id "t1" :tool-name "read"
                                      :arguments "{\"path\":\"foo.clj\"}"})
          [s2 _]    (update-fn s1 {:type :agent-event :event-kind :tool-result
                                   :tool-id "t1" :tool-name "read"
                                   :content [{:type :text :text "file content"}]
                                   :is-error false})
          ;; result includes the tool-call block so handle-agent-result emits a :tool message
          result    {:role "assistant"
                     :content [{:type :tool-call :id "t1" :name "read"
                                :arguments "{\"path\":\"foo.clj\"}"}
                               {:type :text :text "Done."}]}
          [s3 _]    (update-fn s2 {:type :agent-result :result result})
          out       (ansi/strip-ansi (app/view s3))]
      (is (= :idle (:phase s3)))
      ;; header visible: tool name + path, no content in collapsed mode
      (is (str/includes? out "read"))
      (is (str/includes? out "foo.clj"))
      (is (not (str/includes? out "file content")))
      ;; tool row appears before assistant text
      (is (< (.indexOf out "foo.clj") (.indexOf out "Done."))))))

(deftest ctrl-o-toggles-tools-expanded-during-streaming-test
  (testing "ctrl+o expands tool output during a streaming turn; collapsed shows no content"
    (let [update-fn  (app/make-update (stub-agent-fn ""))
          state      (assoc (init-state) :phase :streaming :tools-expanded? false)
          [s1 _]     (update-fn state {:type :agent-event :event-kind :tool-result
                                       :tool-id "t1" :tool-name "bash"
                                       :content [{:type :text :text (str/join "\n" (repeat 20 "output-line"))}]
                                       :is-error false})
          out-before (ansi/strip-ansi (app/view s1))
          [s2 _]     (update-fn s1 (msg/key-press "o" :ctrl true))
          out-after  (ansi/strip-ansi (app/view s2))]
      (is (false? (:tools-expanded? s1)))
      (is (true?  (:tools-expanded? s2)))
      ;; collapsed: no content lines
      (is (not (str/includes? out-before "output-line")))
      ;; expanded: content lines visible
      (is (str/includes? out-after "output-line")))))

(deftest ctrl-o-toggles-tools-expanded-in-idle-test
  (testing "ctrl+o expands tool output in idle phase; collapsed shows no content"
    (let [update-fn  (app/make-update (stub-agent-fn ""))
          ;; Seed state with a :tool message + matching tool-calls entry
          state      (assoc (init-state)
                            :phase :idle
                            :tools-expanded? false
                            :messages [{:role :tool :tool-id "t1"}]
                            :tool-order ["t1"]
                            :tool-calls {"t1" {:name "bash"
                                               :args "{}"
                                               :status :success
                                               :result (str/join "\n" (repeat 20 "output-line"))
                                               :is-error false}})
          out-before (ansi/strip-ansi (app/view state))
          [s2 _]     (update-fn state (msg/key-press "o" :ctrl true))
          out-after  (ansi/strip-ansi (app/view s2))]
      (is (true? (:tools-expanded? s2)))
      (is (not (str/includes? out-before "output-line")))
      (is (str/includes? out-after "output-line")))))

;; ── Tool header format ────────────────────────────────────────────────────────

(deftest tool-header-read-with-line-range-test
  (testing "read tool header shows path:offset:end derived from offset+limit args"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          [s1 _]    (update-fn state {:type :agent-event :event-kind :tool-call-assembly
                                      :phase :end :content-index 0 :tool-id "t1" :tool-name "read"
                                      :arguments "{\"path\":\"src/foo.clj\",\"offset\":10,\"limit\":20}"})
          out       (ansi/strip-ansi (app/view s1))]
      (is (str/includes? out "read src/foo.clj:10:29")))))

(deftest tool-header-read-offset-only-test
  (testing "read tool header shows :offset suffix when limit absent"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          [s1 _]    (update-fn state {:type :agent-event :event-kind :tool-call-assembly
                                      :phase :end :content-index 0 :tool-id "t1" :tool-name "read"
                                      :arguments "{\"path\":\"src/foo.clj\",\"offset\":5}"})
          out       (ansi/strip-ansi (app/view s1))]
      (is (str/includes? out "read src/foo.clj:5")))))

(deftest tool-header-read-no-range-test
  (testing "read tool header shows path only when no offset/limit"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          [s1 _]    (update-fn state {:type :agent-event :event-kind :tool-call-assembly
                                      :phase :end :content-index 0 :tool-id "t1" :tool-name "read"
                                      :arguments "{\"path\":\"README.md\"}"})
          out       (ansi/strip-ansi (app/view s1))]
      (is (str/includes? out "read README.md"))
      (is (not (str/includes? out "read README.md:"))))))

(deftest tool-header-edit-with-line-range-test
  (testing "edit tool header shows path:firstChangedLine:end from details + oldText span"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          [s1 _]    (update-fn state {:type :agent-event :event-kind :tool-call-assembly
                                      :phase :end :content-index 0 :tool-id "t1" :tool-name "edit"
                                      :arguments "{\"path\":\"src/bar.clj\",\"oldText\":\"a\nb\nc\"}"})
          [s2 _]    (update-fn s1 {:type :agent-event :event-kind :tool-result
                                   :tool-id "t1" :tool-name "edit"
                                   :content [{:type :text :text "ok"}]
                                   :details {:firstChangedLine 20}
                                   :is-error false})
          out       (ansi/strip-ansi (app/view s2))]
      (is (str/includes? out "edit src/bar.clj:20:22")))))

(deftest tool-header-bash-test
  (testing "bash tool header uses $ prefix and shows command"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          [s1 _]    (update-fn state {:type :agent-event :event-kind :tool-call-assembly
                                      :phase :end :content-index 0 :tool-id "t1" :tool-name "bash"
                                      :arguments "{\"command\":\"git status\"}"})
          out       (ansi/strip-ansi (app/view s1))]
      (is (str/includes? out "$ git status")))))

(deftest tool-collapsed-no-content-test
  (testing "collapsed mode shows no content body regardless of result size"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :phase :streaming)
          [s1 _]    (update-fn state {:type :agent-event :event-kind :tool-result
                                      :tool-id "t1" :tool-name "read"
                                      :content [{:type :text :text (str/join "\n" (repeat 50 "content-line"))}]
                                      :is-error false})
          out       (ansi/strip-ansi (app/view s1))]
      (is (not (str/includes? out "content-line"))))))

(deftest tool-header-truncates-stably-at-narrow-width-test
  (testing "collapsed tool header truncates to fit narrow width"
    (let [state (assoc (init-state)
                       :width 24
                       :phase :idle
                       :messages [{:role :tool :tool-id "t1"}]
                       :tool-order ["t1"]
                       :tool-calls {"t1" {:name "bash"
                                          :args "{\"command\":\"printf an-extremely-long-command-name-for-width-testing\"}"
                                          :status :success}})
          plain (ansi/strip-ansi (app/view state))
          line  (some #(when (str/includes? % "$ ") %) (str/split-lines plain))]
      (is line)
      (is (<= (count line) 24))
      (is (or (str/includes? line "...")
              (str/includes? line "…"))))))

(deftest expanded-tool-body-wraps-at-narrow-width-test
  (testing "expanded tool body plain text wraps to the indented body width"
    (let [state (assoc (init-state)
                       :width 24
                       :phase :idle
                       :tools-expanded? true
                       :messages [{:role :tool :tool-id "t1"}]
                       :tool-order ["t1"]
                       :tool-calls {"t1" {:name "bash"
                                          :args "{\"command\":\"echo wrapped\"}"
                                          :status :success
                                          :result "tool body should wrap cleanly across lines"
                                          :is-error false}})
          plain (ansi/strip-ansi (app/view state))
          lines (filter #(or (str/includes? % "tool body")
                             (str/starts-with? % "    "))
                        (str/split-lines plain))]
      (is (> (count lines) 1))
      (is (every? #(<= (count %) 24) lines))
      (is (every? #(or (str/includes? % "tool body")
                       (str/starts-with? % "    "))
                  lines)))))
