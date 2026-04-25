(ns psi.tui.app-test
  "Tests for the charm.clj Elm Architecture TUI.
   Exercises init/update/view as pure functions — no terminal needed.
   Includes a JLine integration smoke test for terminal + keymap creation."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [charm.components.text-input :as text-input]
   [charm.input.keymap :as keymap]
   [charm.terminal :as charm-terminal]
   [charm.message :as msg]
   [psi.app-runtime.projections :as projections]
   [psi.tui.ansi :as ansi]
   [psi.tui.app :as app]
   [psi.ui.state :as ui-state])
  (:import
   [java.util.concurrent LinkedBlockingQueue]))

;;;; Helpers

(defn- default-dispatch-fn
  "Minimal dispatch-fn for tests: handles /quit, /resume, /new.
   Returns result maps matching the commands.clj contract."
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
  "Create a fresh state from make-init.
   Accepts legacy :ui-state* atom in opts; wires it into ui-read-fn + ui-dispatch-fn."
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
  "Type all chars of s into the update loop, returning new state."
  [update-fn state s]
  (reduce (fn [st ch]
            (first (update-fn st (msg/key-press (str ch)))))
          state
          s))

(defn- stub-agent-fn
  "A stub run-agent-fn! that immediately puts a done result on the queue."
  [response-text]
  (fn [_text ^LinkedBlockingQueue queue]
    (.put queue {:kind :done
                 :result {:role    "assistant"
                          :content [{:type :text :text response-text}]}})))

;;;; Init

(deftest init-test
  (testing "init returns idle state and starts queue polling cmd"
    (let [init-fn (app/make-init "test-model")
          [state cmd] (init-fn)]
      (is (= :idle (:phase state)))
      (is (some? cmd))
      (is (empty? (:messages state)))
      (is (nil? (:error state)))
      (is (= "test-model" (:model-name state)))))

  (testing "init includes explicit prompt-input state shape"
    (let [[state _] ((app/make-init "test-model"))
          input-state (:prompt-input-state state)]
      (is (= {:prefix ""
              :candidates []
              :selected-index 0
              :context nil
              :trigger-mode nil}
             (:autocomplete input-state)))
      (is (= {:entries []
              :browse-index nil
              :max-entries 100}
             (:history input-state)))
      (is (= {:last-ctrl-c-ms nil
              :last-escape-ms nil}
             (:timing input-state))))))

;;;; Update — key input

(deftest typing-updates-input-test
  (testing "printable keys update the text input"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 _]    (update-fn state (msg/key-press "h"))
          [s2 _]    (update-fn s1 (msg/key-press "i"))]
      (is (= "hi" (text-input/value (:input s2)))))))

(deftest backspace-test
  (testing "backspace removes a character"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 _]    (update-fn state (msg/key-press "a"))
          [s2 _]    (update-fn s1 (msg/key-press "b"))
          [s3 _]    (update-fn s2 (msg/key-press :backspace))]
      (is (= "a" (text-input/value (:input s3)))))))

(deftest keyword-space-inserts-immediately-test
  (testing "keyword :space input inserts a space immediately"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state)
                           :input (text-input/set-value (:input (init-state)) "hi"))
          [s1 _]    (update-fn state (msg/key-press :space))]
      (is (= "hi " (text-input/value (:input s1)))))))

(deftest alt-backspace-deletes-word-test
  (testing "alt+backspace deletes previous word"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (-> (init-state)
                        (assoc :input (text-input/set-value (:input (init-state)) "hello world")))
          [s1 _]    (update-fn state (msg/key-press :backspace :alt true))]
      (is (= "hello " (text-input/value (:input s1)))))))

(deftest modifier-enter-adds-newline-test
  (testing "shift+enter inserts newline and does not submit"
    (let [submitted (atom nil)
          agent-fn  (fn [text _queue] (reset! submitted text))
          update-fn (app/make-update agent-fn)
          state     (assoc (init-state) :input (text-input/set-value (:input (init-state)) "line1"))
          [s1 _]    (update-fn state (msg/key-press :enter :shift true))]
      (is (= :idle (:phase s1)))
      (is (= "line1\n" (text-input/value (:input s1))))
      (is (nil? @submitted)))))

(deftest backslash-enter-adds-newline-test
  (testing "trailing backslash + enter inserts newline continuation"
    (let [submitted (atom nil)
          agent-fn  (fn [text _queue] (reset! submitted text))
          update-fn (app/make-update agent-fn)
          state     (assoc (init-state) :input (text-input/set-value (:input (init-state)) "line1\\"))
          [s1 _]    (update-fn state (msg/key-press :enter))]
      (is (= :idle (:phase s1)))
      (is (= "line1\n" (text-input/value (:input s1))))
      (is (nil? @submitted)))))

;;;; Update — submit

(deftest submit-starts-streaming-test
  (testing "enter with text transitions to :streaming"
    (let [submitted (atom nil)
          agent-fn  (fn [text queue]
                      (reset! submitted text)
                      (.put ^LinkedBlockingQueue queue
                            {:kind :done
                             :result {:role "assistant"
                                      :content [{:type :text :text "ok"}]}}))
          update-fn (app/make-update agent-fn)
          state     (init-state)
          [s1 _]    (update-fn state (msg/key-press "g"))
          [s2 _]    (update-fn s1 (msg/key-press "o"))
          [s3 cmd]  (update-fn s2 (msg/key-press :enter))]
      (is (= :streaming (:phase s3)))
      (is (some? cmd))
      (is (= "go" @submitted))
      ;; User message recorded
      (is (= 1 (count (:messages s3))))
      (is (= :user (:role (first (:messages s3))))))))

(deftest submit-blank-does-nothing-test
  (testing "enter with empty input stays idle"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 cmd]  (update-fn state (msg/key-press :enter))]
      (is (= :idle (:phase s1)))
      (is (nil? cmd)))))

;;;; Input/selector tests moved to psi.tui.app-input-selector-test

;;;; View/runtime tests moved to psi.tui.app-view-runtime-test

;;;; Window resize

(deftest window-resize-updates-dimensions-test
  (testing "window-size message updates width and height and sets force-clear?"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 _]    (update-fn state (msg/window-size 120 40))
          out       (app/view s1)]
      (is (= 120 (:width s1)))
      (is (= 40 (:height s1)))
      (is (true? (:force-clear? s1)))
      ;; ESC[2J is no longer emitted in the view string — JLine Display handles
      ;; full repaints via repaint! on resize; the view string stays clean
      (is (not (str/includes? out "\u001b[2J")))
      (is (str/includes? (ansi/strip-ansi out) "ψ Psi Agent Session")))))

(deftest external-message-appended-to-transcript-test
  (testing "external-message appends assistant text and keeps polling"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          event     {:type :external-message
                     :message {:role "assistant"
                               :content [{:type :text :text "Agent complete"}]}}
          [s1 cmd]  (update-fn state event)]
      (is (= 1 (count (:messages s1))))
      (is (= :assistant (:role (first (:messages s1)))))
      (is (= "Agent complete" (:text (first (:messages s1)))))
      (is (some? cmd)))))

(deftest context-updated-updates-visible-context-widget-test
  (testing "context-updated updates the discoverable session context surface"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          widget    {:placement "left"
                     :extension-id "psi-session"
                     :widget-id "session-tree"
                     :content-lines [{:text "main [s1] ← current [waiting]"}]}
          [s1 cmd]  (update-fn state {:type :context-updated
                                      :active-session-id "s1"
                                      :session-tree-widget widget})]
      (is (= widget (:context-session-tree-widget s1)))
      (is (zero? (:context-session-tree-selected-index s1)))
      (is (some? cmd)))))

(deftest agent-result-rich-render-test
  (testing "agent-result custom-type renders rich heading"
    (let [state (assoc (init-state)
                       :messages [{:role :assistant
                                   :custom-type "agent-result"
                                   :text "Agent #1 finished \"do thing\" in 2s\n\nResult:\nAll good"}])
          out   (app/view state)]
      (is (str/includes? out "Agent Result"))
      (is (str/includes? out "Agent #1 finished"))
      (is (str/includes? out "All good")))))

(deftest plan-state-learning-rich-render-test
  (testing "plan-state-learning custom-type renders dedicated heading"
    (let [state (assoc (init-state)
                       :messages [{:role :assistant
                                   :custom-type "plan-state-learning"
                                   :text "PSL committed munera/mementum sync at abc1234."}])
          out   (app/view state)]
      (is (str/includes? out "Plan/State/Learning"))
      (is (str/includes? out "PSL committed munera/mementum sync")))))

;;;; Text input word wrap

(deftest wrap-text-input-short-test
  (testing "short text renders on one line with prompt"
    (let [state (init-state)
          s1    (assoc state :width 80)
          ;; Type "hello"
          update-fn (app/make-update (stub-agent-fn ""))
          [s2 _] (update-fn s1 (msg/key-press "h"))
          [s3 _] (update-fn s2 (msg/key-press "i"))
          out    (app/view s3)]
      ;; Should have prompt + text on one line, no extra newlines in input area
      (is (str/includes? out "hi")))))

(deftest wrap-text-input-long-test
  (testing "long text wraps at terminal width"
    (let [state   (init-state)
          ;; Set narrow width so wrapping kicks in
          state   (assoc state :width 20)
          update-fn (app/make-update (stub-agent-fn ""))
          ;; Type enough text to exceed width (prompt "刀: " is ~4 cols)
          ;; Available = 20 - 4 = 16 cols
          long-text "the quick brown fox jumps over"
          s (reduce (fn [s ch]
                      (first (update-fn s (msg/key-press (str ch)))))
                    state
                    long-text)
          out (app/view s)
          lines (str/split-lines out)]
      ;; The input area should span multiple lines
      ;; Find lines containing parts of our text
      (is (str/includes? out "the quick"))
      (is (str/includes? out "fox"))
      ;; Verify continuation lines are indented (prompt width spaces)
      (let [input-lines (filter #(or (str/includes? % "the quick")
                                     (str/includes? % "fox")
                                     (str/includes? % "jumps"))
                                lines)]
        (is (> (count input-lines) 1)
            "text should wrap to multiple lines")))))

(deftest wrap-text-input-placeholder-test
  (testing "empty input shows placeholder"
    (let [state (assoc (init-state) :width 80)
          out   (app/view state)]
      ;; Cursor renders on first char, so check for rest of placeholder
      (is (str/includes? out "ype a message")))))

;;;; Extension command output capture

(deftest extension-cmd-println-captured-as-message-test
  (testing "extension command println output appears as assistant message"
    (let [dispatch-fn (fn [text]
                        (when (= text "/hello")
                          {:type    :extension-cmd
                           :name    "hello"
                           :args    ""
                           :handler (fn [_args] (println "Hello from extension!"))}))
          update-fn   (app/make-update (stub-agent-fn ""))
          state       (init-state "test-model" {:dispatch-fn dispatch-fn})
          typed       (type-text update-fn state "/hello")
          [s1 _]      (update-fn typed (msg/key-press :enter))]
      (is (= :idle (:phase s1)))
      (is (= 1 (count (:messages s1))))
      (is (= :assistant (:role (first (:messages s1)))))
      (is (= "Hello from extension!" (:text (first (:messages s1))))))))

(deftest extension-cmd-no-output-no-message-test
  (testing "extension command with no println adds no message"
    (let [called      (atom false)
          dispatch-fn (fn [text]
                        (when (= text "/silent")
                          {:type    :extension-cmd
                           :name    "silent"
                           :args    ""
                           :handler (fn [_args] (reset! called true))}))
          update-fn   (app/make-update (stub-agent-fn ""))
          state       (init-state "test-model" {:dispatch-fn dispatch-fn})
          typed       (type-text update-fn state "/silent")
          [s1 _]      (update-fn typed (msg/key-press :enter))]
      (is @called)
      (is (= :idle (:phase s1)))
      (is (empty? (:messages s1))))))

(deftest extension-cmd-error-shown-as-message-test
  (testing "extension command exception appears as error message"
    (let [dispatch-fn (fn [text]
                        (when (= text "/boom")
                          {:type    :extension-cmd
                           :name    "boom"
                           :args    ""
                           :handler (fn [_args] (throw (ex-info "kaboom" {})))}))
          update-fn   (app/make-update (stub-agent-fn ""))
          state       (init-state "test-model" {:dispatch-fn dispatch-fn})
          typed       (type-text update-fn state "/boom")
          [s1 _]      (update-fn typed (msg/key-press :enter))]
      (is (= :idle (:phase s1)))
      (is (= 1 (count (:messages s1))))
      (is (str/includes? (:text (first (:messages s1))) "kaboom")))))

(deftest extension-cmd-multiline-output-test
  (testing "extension command with multiple printlns captured as single message"
    (let [dispatch-fn (fn [text]
                        (when (= text "/multi")
                          {:type    :extension-cmd
                           :name    "multi"
                           :args    ""
                           :handler (fn [_args]
                                      (println "Line 1")
                                      (println "Line 2"))}))
          update-fn   (app/make-update (stub-agent-fn ""))
          state       (init-state "test-model" {:dispatch-fn dispatch-fn})
          typed       (type-text update-fn state "/multi")
          [s1 _]      (update-fn typed (msg/key-press :enter))]
      (is (= 1 (count (:messages s1))))
      (is (str/includes? (:text (first (:messages s1))) "Line 1"))
      (is (str/includes? (:text (first (:messages s1))) "Line 2")))))

;;;; JLine integration smoke test

(deftest jline-terminal-keymap-test
  (testing "JLine terminal + keymap creation works (catches JLine API compat bugs)"
    (let [terminal (charm-terminal/create-terminal)]
      (try
        (let [km (keymap/create-keymap terminal)]
          (is (some? km) "keymap created successfully"))
        (finally
          (.close terminal))))))
