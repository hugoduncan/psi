(ns psi.tui.app-test
  "Tests for the charm.clj Elm Architecture TUI.
   Exercises init/update/view as pure functions — no terminal needed.
   Includes a JLine integration smoke test for terminal + keymap creation."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [charm.components.text-input :as text-input]
   [charm.core :as charm]
   [charm.input.keymap :as keymap]
   [charm.message :as msg]
   [psi.agent-session.persistence :as persist]
   [psi.tui.app :as app]
   [psi.tui.extension-ui :as ext-ui])
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
    "/new"             {:type :new-session :message "[New session started]"}
    "/status"          {:type :text :message "test status"}
    "/help"            {:type :text :message "test help"}
    nil))

(defn- init-state
  "Create a fresh state from make-init."
  ([] (init-state "test-model" {}))
  ([model-name] (init-state model-name {}))
  ([model-name opts]
   (let [ui-state-atom (:ui-state-atom opts)
         opts'         (dissoc opts :ui-state-atom)
         init-fn       (app/make-init model-name nil ui-state-atom
                                      (merge {:dispatch-fn default-dispatch-fn} opts'))
         [state _cmd]  (init-fn)]
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

(defn- submit-text
  "Type s then press enter; if submission starts streaming, advance once to idle."
  [update-fn state s]
  (let [typed          (type-text update-fn state s)
        [submitted _]  (update-fn typed (msg/key-press :enter))]
    (if (= :streaming (:phase submitted))
      (first (update-fn submitted {:type :agent-result
                                   :result {:content [{:type :text :text "ok"}]}}))
      submitted)))

(defn- error-agent-fn
  "A stub run-agent-fn! that immediately puts an error on the queue."
  [error-msg]
  (fn [_text ^LinkedBlockingQueue queue]
    (.put queue {:kind :error :message error-msg})))

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
                           :input (charm/text-input-set-value (:input (init-state)) "hi"))
          [s1 _]    (update-fn state (msg/key-press :space))]
      (is (= "hi " (text-input/value (:input s1)))))))

(deftest alt-backspace-deletes-word-test
  (testing "alt+backspace deletes previous word"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (-> (init-state)
                        (assoc :input (charm/text-input-set-value (:input (init-state)) "hello world")))
          [s1 _]    (update-fn state (msg/key-press :backspace :alt true))]
      (is (= "hello " (text-input/value (:input s1)))))))

(deftest modifier-enter-adds-newline-test
  (testing "shift+enter inserts newline and does not submit"
    (let [submitted (atom nil)
          agent-fn  (fn [text _queue] (reset! submitted text))
          update-fn (app/make-update agent-fn)
          state     (assoc (init-state) :input (charm/text-input-set-value (:input (init-state)) "line1"))
          [s1 _]    (update-fn state (msg/key-press :enter :shift true))]
      (is (= :idle (:phase s1)))
      (is (= "line1\n" (text-input/value (:input s1))))
      (is (nil? @submitted)))))

(deftest backslash-enter-adds-newline-test
  (testing "trailing backslash + enter inserts newline continuation"
    (let [submitted (atom nil)
          agent-fn  (fn [text _queue] (reset! submitted text))
          update-fn (app/make-update agent-fn)
          state     (assoc (init-state) :input (charm/text-input-set-value (:input (init-state)) "line1\\"))
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

;;;; Update — /resume session selector

(deftest resume-command-opens-selector-test
  (testing "/resume enters :selecting-session phase"
    (with-redefs [persist/session-dir-for (fn [_cwd] "/tmp/psi-test")
                  persist/list-sessions
                  (fn [_dir]
                    [{:path "/tmp/psi-test/a.ndedn"
                      :name "Session A"
                      :first-message "hello"
                      :message-count 3
                      :modified (java.time.Instant/now)
                      :cwd "/tmp/psi-test"}])]
      (let [update-fn (app/make-update (stub-agent-fn ""))
            state     (init-state "test-model" {:cwd "/tmp/psi-test"})
            typed     (type-text update-fn state "/resume")
            [s1 _]    (update-fn typed (msg/key-press :enter))]
        (is (= :selecting-session (:phase s1)))
        (is (some? (:session-selector s1)))))))

(deftest resume-selection-restores-messages-test
  (testing "selecting a session calls resume-fn! and loads returned messages"
    (let [selected-path (atom nil)
          restored      {:messages [{:role :user :text "restored user"}
                                    {:role :assistant :text "restored assistant"}]
                         :tool-calls {"t1" {:name "read"
                                            :args "{\"path\":\"foo.txt\"}"
                                            :status :success
                                            :result "ok"
                                            :is-error false
                                            :expanded? false}}
                         :tool-order ["t1"]}]
      (with-redefs [persist/session-dir-for (fn [_cwd] "/tmp/psi-test")
                    persist/list-sessions
                    (fn [_dir]
                      [{:path "/tmp/psi-test/a.ndedn"
                        :name "Session A"
                        :first-message "hello"
                        :message-count 3
                        :modified (java.time.Instant/now)
                        :cwd "/tmp/psi-test"}])]
        (let [update-fn (app/make-update (stub-agent-fn ""))
              state     (init-state "test-model"
                                    {:cwd "/tmp/psi-test"
                                     :resume-fn! (fn [path]
                                                   (reset! selected-path path)
                                                   restored)})
              typed     (type-text update-fn state "/resume")
              [s1 _]    (update-fn typed (msg/key-press :enter))
              [s2 _]    (update-fn s1 (msg/key-press :enter))]
          (is (= :idle (:phase s2)))
          (is (= (:messages restored) (:messages s2)))
          (is (= (:tool-order restored) (:tool-order s2)))
          (is (= "ok" (get-in s2 [:tool-calls "t1" :result])))
          (is (= "/tmp/psi-test/a.ndedn" @selected-path))
          (is (= "/tmp/psi-test/a.ndedn" (:current-session-file s2))))))))

(deftest resume-selector-escape-cancels-test
  (testing "escape from selector returns to idle without changing messages"
    (with-redefs [persist/session-dir-for (fn [_cwd] "/tmp/psi-test")
                  persist/list-sessions (fn [_dir] [])]
      (let [update-fn (app/make-update (stub-agent-fn ""))
            state     (assoc (init-state "test-model" {:cwd "/tmp/psi-test"})
                             :messages [{:role :assistant :text "keep me"}])
            typed     (type-text update-fn state "/resume")
            [s1 _]    (update-fn typed (msg/key-press :enter))
            [s2 _]    (update-fn s1 (msg/key-press :escape))]
        (is (= :idle (:phase s2)))
        (is (= [{:role :assistant :text "keep me"}] (:messages s2)))))))

(deftest resume-selector-keyword-key-ignored-test
  (testing "non-printable keyword keys in selector are ignored (no exception)"
    (with-redefs [persist/session-dir-for (fn [_cwd] "/tmp/psi-test")
                  persist/list-sessions
                  (fn [_dir]
                    [{:path "/tmp/psi-test/a.ndedn"
                      :name "Session A"
                      :first-message "hello"
                      :message-count 3
                      :modified (java.time.Instant/now)
                      :cwd "/tmp/psi-test"}])]
      (let [update-fn (app/make-update (stub-agent-fn ""))
            state     (init-state "test-model" {:cwd "/tmp/psi-test"})
            typed     (type-text update-fn state "/resume")
            [s1 _]    (update-fn typed (msg/key-press :enter))
            [s2 _]    (update-fn s1 (msg/key-press :left))]
        (is (= :selecting-session (:phase s2)))
        (is (= "" (get-in s2 [:session-selector :search])))))))

(deftest resume-selector-backspace-keyword-test
  (testing "keyword backspace edits selector search"
    (with-redefs [persist/session-dir-for (fn [_cwd] "/tmp/psi-test")
                  persist/list-sessions
                  (fn [_dir]
                    [{:path "/tmp/psi-test/a.ndedn"
                      :name "Session A"
                      :first-message "hello"
                      :message-count 3
                      :modified (java.time.Instant/now)
                      :cwd "/tmp/psi-test"}])]
      (let [update-fn (app/make-update (stub-agent-fn ""))
            state     (init-state "test-model" {:cwd "/tmp/psi-test"})
            typed     (type-text update-fn state "/resume")
            [s1 _]    (update-fn typed (msg/key-press :enter))
            [s2 _]    (update-fn s1 (msg/key-press "a"))
            [s3 _]    (update-fn s2 (msg/key-press :backspace))]
        (is (= "a" (get-in s2 [:session-selector :search])))
        (is (= "" (get-in s3 [:session-selector :search])))))))

;;;; Update — agent results

(deftest agent-result-transitions-to-idle-test
  (testing "agent-result message adds assistant message and goes idle"
    (let [update-fn (app/make-update (stub-agent-fn "hello"))
          state     (init-state)
          ;; Simulate streaming state
          streaming (assoc state :phase :streaming
                           :messages [{:role :user :text "hi"}])
          result    {:role "assistant" :content [{:type :text :text "hello"}]}
          [s1 cmd]  (update-fn streaming {:type :agent-result :result result})]
      (is (= :idle (:phase s1)))
      (is (= 2 (count (:messages s1))))
      (is (= "hello" (:text (second (:messages s1)))))
      (is (some? cmd)))))

(deftest agent-error-transitions-to-idle-test
  (testing "agent-error message sets error and goes idle"
    (let [update-fn (app/make-update (error-agent-fn "boom"))
          streaming (assoc (init-state) :phase :streaming)
          [s1 cmd]  (update-fn streaming {:type :agent-error :error "boom"})]
      (is (= :idle (:phase s1)))
      (is (= "boom" (:error s1)))
      (is (some? cmd)))))

;;;; Update — poll advances spinner

(deftest poll-advances-spinner-test
  (testing "agent-poll increments spinner-frame"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          streaming (assoc (init-state) :phase :streaming
                           :spinner-frame 0)
          [s1 cmd]  (update-fn streaming {:type :agent-poll})]
      (is (= 1 (:spinner-frame s1)))
      (is (some? cmd)))))

;;;; Update — interrupt / clear / exit semantics

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
                           :input (charm/text-input-set-value (:input (init-state)) "hello"))
          [s1 cmd1] (update-fn state (msg/key-press "c" :ctrl true))
          [_s2 cmd2] (update-fn s1 (msg/key-press "c" :ctrl true))]
      (is (= "" (text-input/value (:input s1))))
      (is (nil? cmd1))
      (is (some? cmd2)))))

(deftest ctrl-d-exits-only-when-input-empty-test
  (testing "ctrl+d exits when input empty and is ignored when input non-empty"
    (let [update-fn   (app/make-update (stub-agent-fn ""))
          non-empty   (assoc (init-state)
                             :input (charm/text-input-set-value (:input (init-state)) "x"))
          [_s1 cmd1]  (update-fn non-empty (msg/key-press "d" :ctrl true))
          empty-state (assoc (init-state)
                             :input (charm/text-input-set-value (:input (init-state)) ""))
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
                           :input (charm/text-input-set-value (:input (init-state)) "draft"))
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
                           :input (charm/text-input-set-value (:input (init-state)) "steer this"))
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
                           :input (charm/text-input-set-value (:input (init-state)) "ab"))
          [s1 _]    (update-fn state (msg/key-press "c"))
          [s2 _]    (update-fn s1 (msg/key-press :backspace))]
      (is (= "abc" (text-input/value (:input s1))))
      (is (= "ab" (text-input/value (:input s2)))))))

(deftest double-escape-unsupported-action-is-safe-no-op-with-status-test
  (testing "unsupported double-escape action does not crash and emits status"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state "test-model" {:double-escape-action :tree})
                           :input (charm/text-input-set-value (:input (init-state)) ""))
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

(deftest history-up-from-empty-enters-browsing-at-newest-test
  (testing "up from empty input enters history browse mode at newest entry"
    (let [update-fn (app/make-update (stub-agent-fn "ok"))
          base      (submit-text update-fn
                                 (submit-text update-fn (init-state) "alpha")
                                 "beta")
          state     (assoc base :phase :idle
                           :input (charm/text-input-set-value (:input base) ""))
          [s1 _]    (update-fn state (msg/key-press :up))]
      (is (= "beta" (text-input/value (:input s1))))
      (is (= 1 (get-in s1 [:prompt-input-state :history :browse-index]))))))

(deftest history-down-from-newest-exits-browsing-to-empty-test
  (testing "down at newest history entry exits browse mode and restores empty input"
    (let [update-fn (app/make-update (stub-agent-fn "ok"))
          base      (submit-text update-fn
                                 (submit-text update-fn (init-state) "alpha")
                                 "beta")
          state     (assoc base :phase :idle
                           :input (charm/text-input-set-value (:input base) ""))
          [s1 _]    (update-fn state (msg/key-press :up))
          [s2 _]    (update-fn s1 (msg/key-press :down))]
      (is (= "" (text-input/value (:input s2))))
      (is (nil? (get-in s2 [:prompt-input-state :history :browse-index]))))))

(deftest history-editing-clears-browse-index-test
  (testing "normal edit and programmatic set-text clear history browse mode"
    (let [update-fn (app/make-update (stub-agent-fn "ok"))
          base      (submit-text update-fn
                                 (submit-text update-fn (init-state) "alpha")
                                 "beta")
          state     (assoc base :phase :idle
                           :input (charm/text-input-set-value (:input base) ""))
          [s1 _]    (update-fn state (msg/key-press :up))
          [s2 _]    (update-fn s1 (msg/key-press "x"))
          [s3 _]    (update-fn s2 (msg/key-press :enter))]
      (is (nil? (get-in s2 [:prompt-input-state :history :browse-index])))
      (is (nil? (get-in s3 [:prompt-input-state :history :browse-index]))))))

(deftest autocomplete-slash-opens-on-leading-slash-test
  (testing "typing leading / opens slash autocomplete with slash context"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 _]    (update-fn state (msg/key-press "/"))
          ac        (get-in s1 [:prompt-input-state :autocomplete])]
      (is (= :slash_command (:context ac)))
      (is (= :auto (:trigger-mode ac)))
      (is (seq (:candidates ac)))
      (is (some #(= "/help" (:value %)) (:candidates ac))))))

(deftest autocomplete-accept-on-enter-submits-slash-test
  (testing "enter accepts selected slash suggestion and submits"
    (let [submitted (atom nil)
          agent-fn  (fn [text _queue] (reset! submitted text))
          update-fn (app/make-update agent-fn)
          state     (init-state)
          state     (assoc state :prompt-templates [{:name "deploy"}])
          [s1 _]    (update-fn state (msg/key-press "/"))
          [s2 _]    (update-fn s1 (msg/key-press "d"))
          [s3 _]    (update-fn s2 (msg/key-press :enter))]
      (is (= :streaming (:phase s3)))
      (is (= "/deploy" @submitted))
      (is (empty? (get-in s3 [:prompt-input-state :autocomplete :candidates]))))))

(deftest autocomplete-escape-closes-menu-not-quit-test
  (testing "escape closes open autocomplete without quitting"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          [s1 _]    (update-fn (init-state) (msg/key-press "/"))
          [s2 cmd]  (update-fn s1 (msg/key-press :escape))]
      (is (= :idle (:phase s2)))
      (is (nil? cmd))
      (is (empty? (get-in s2 [:prompt-input-state :autocomplete :candidates]))))))

(deftest autocomplete-tab-opens-path-and-single-auto-applies-test
  (testing "tab opens path completion and single match auto-applies"
    (let [tmp-dir (java.nio.file.Files/createTempDirectory "psi-ac" (make-array java.nio.file.attribute.FileAttribute 0))
          root    (.toFile tmp-dir)
          _       (spit (io/file root "alpha.txt") "x")
          update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :cwd (.getAbsolutePath root))
          state     (assoc state :input (charm/text-input-set-value (:input state) "alp"))
          [s1 _]    (update-fn state (msg/key-press :tab))]
      (is (= "alpha.txt" (text-input/value (:input s1))))
      (is (empty? (get-in s1 [:prompt-input-state :autocomplete :candidates]))))))

(deftest autocomplete-file-reference-filters-git-and-adds-space-test
  (testing "@ completion excludes .git entries and appends trailing space for files"
    (let [tmp-dir  (java.nio.file.Files/createTempDirectory "psi-fr" (make-array java.nio.file.attribute.FileAttribute 0))
          root     (.toFile tmp-dir)
          _        (.mkdir (io/file root ".git"))
          _        (spit (io/file root ".git" "ignored.txt") "x")
          _        (spit (io/file root ".hidden") "x")
          _        (spit (io/file root "file.txt") "x")
          update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :cwd (.getAbsolutePath root))
          [s1 _]    (update-fn state (msg/key-press "@"))
          cands     (get-in s1 [:prompt-input-state :autocomplete :candidates])
          cand-vals (set (map :value cands))
          _         (is (contains? cand-vals ".hidden"))
          _         (is (contains? cand-vals "file.txt"))
          _         (is (not-any? #(str/starts-with? % ".git") cand-vals))
          ;; choose file.txt explicitly
          s2        (assoc-in s1 [:prompt-input-state :autocomplete :selected-index]
                              (or (first (keep-indexed (fn [idx c]
                                                         (when (= "file.txt" (:value c)) idx))
                                                       cands))
                                  0))
          [s3 _]    (update-fn s2 (msg/key-press :tab))]
      (is (= "@file.txt " (text-input/value (:input s3)))))))

(deftest autocomplete-quoted-acceptance-avoids-duplicate-closing-quote-test
  (testing "accepting quoted completion does not duplicate closing quote"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (-> (init-state)
                        (assoc :input (charm/text-input-set-value (:input (init-state)) "@\"foo\""))
                        (assoc-in [:prompt-input-state :autocomplete]
                                  {:prefix "@\"f"
                                   :candidates [{:value "\"foo\""
                                                 :label "\"foo\""
                                                 :description nil
                                                 :kind :file_reference
                                                 :is-directory false}]
                                   :selected-index 0
                                   :context :file_reference
                                   :trigger-mode :auto
                                   :token-start 0
                                   :token-end 5}))
          [s1 _]    (update-fn state (msg/key-press :tab))]
      (is (= "@\"foo\" " (text-input/value (:input s1)))))))

(deftest keys-edit-input-during-streaming-test
  (testing "printable keys edit draft input while streaming"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          streaming (-> (init-state)
                        (assoc :phase :streaming)
                        (assoc :input (charm/text-input-set-value (:input (init-state)) "")))
          [s1 cmd]  (update-fn streaming (msg/key-press "x"))]
      (is (= :streaming (:phase s1)))
      (is (= "x" (text-input/value (:input s1))))
      (is (nil? cmd)))))

;;;; View

(deftest view-renders-banner-test
  (testing "view includes model name"
    (let [state (init-state "gpt-4o")
          out   (app/view state)]
      (is (string? out))
      (is (str/includes? out "gpt-4o")))))

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
  (testing "footer renders path, stats, provider/model, and statuses from EQL query data"
    (let [qfn (fn [_q]
                {:psi.agent-session/cwd "/repo/project"
                 :psi.agent-session/git-branch "master"
                 :psi.agent-session/session-name "session-a"
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
                                   {:extension-id "a" :text "Clojure-LSP
clojure-lsp"}]})
          init-fn (app/make-init "test-model" qfn nil {:cwd "/repo/project"})
          [state _] (init-fn)
          out (app/view (assoc state :width 120))]
      (is (str/includes? out "/repo/project (master) • session-a"))
      (is (str/includes? out "↑76k"))
      (is (str/includes? out "↓3.6k"))
      (is (str/includes? out "R1.4M"))
      (is (str/includes? out "$0.434"))
      (is (str/includes? out "17.6%/400k (auto)"))
      (is (str/includes? out "(openai) gpt-5.3-codex • xhigh"))
      ;; status sort + sanitization (a before b, newline collapsed to space)
      (is (str/includes? out "Clojure-LSP clojure-lsp TS+ESL,Prett")))))

(deftest view-shows-spinner-during-streaming-test
  (testing "view shows spinner while streaming"
    (let [state (assoc (init-state) :phase :streaming)
          out   (app/view state)]
      (is (str/includes? out "thinking"))
      (is (str/includes? out "Enter queues input"))
      (is (not (str/includes? out "⠋ waiting for response…"))))))

(deftest view-shows-spinner-in-waiting-indicator-with-tool-history-test
  (testing "streaming view keeps a visible spinner even after tools complete"
    (let [state (-> (init-state)
                    (assoc :phase :streaming
                           :spinner-frame 0
                           :stream-text nil
                           :tool-order ["t1"]
                           :tool-calls {"t1" {:name "bash"
                                              :args "{\"command\":\"echo hi\"}"
                                              :status :success
                                              :result "ok"
                                              :is-error false}}))
          out   (app/view state)]
      (is (str/includes? out "⠋ waiting for response…"))
      (is (not (str/includes? out "(waiting for response…)"))))))

(deftest view-hides-waiting-spinner-when-tool-spinner-visible-test
  (testing "streaming hint remains static when tool list already shows spinner"
    (let [state (-> (init-state)
                    (assoc :phase :streaming
                           :spinner-frame 0
                           :stream-text nil
                           :tool-order ["t1"]
                           :tool-calls {"t1" {:name "bash"
                                              :args "{\"command\":\"echo hi\"}"
                                              :status :running
                                              :result nil
                                              :is-error false}}))
          out   (app/view state)]
      (is (str/includes? out "Enter queues input"))
      (is (not (str/includes? out "⠋ waiting for response…"))))))

(deftest ctrl-o-toggles-tools-expanded-test
  (testing "ctrl+o toggles global tools-expanded state"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 _]    (update-fn state (msg/key-press "o" :ctrl true))
          [s2 _]    (update-fn s1 (msg/key-press "o" :ctrl true))]
      (is (false? (:tools-expanded? state)))
      (is (true? (:tools-expanded? s1)))
      (is (false? (:tools-expanded? s2))))))

(deftest ctrl-o-updates-extension-tools-expanded-state-test
  (testing "ctrl+o updates extension ui tools-expanded state"
    (let [ui        (ext-ui/create-ui-state)
          update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state "test-model" {:ui-state-atom ui})
          [s1 _]    (update-fn state (msg/key-press "o" :ctrl true))]
      (is (true? (:tools-expanded? s1)))
      (is (true? (ext-ui/get-tools-expanded ui)))
      (let [[s2 _] (update-fn s1 (msg/key-press "o" :ctrl true))]
        (is (false? (:tools-expanded? s2)))
        (is (false? (ext-ui/get-tools-expanded ui)))))))

(deftest app-syncs-tools-expanded-from-extension-ui-state-test
  (testing "update loop syncs tools-expanded from extension ui state"
    (let [ui       (ext-ui/create-ui-state)
          update-fn (app/make-update (stub-agent-fn ""))
          state    (init-state "test-model" {:ui-state-atom ui})]
      (ext-ui/set-tools-expanded! ui true)
      (let [[s1 _] (update-fn state {:type :agent-poll})]
        (is (true? (:tools-expanded? s1)))))))

(deftest tool-start-inherits-tools-expanded-test
  (testing "new tool rows inherit current tools-expanded setting"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :tools-expanded? true)
          [s1 _]    (update-fn state {:type :agent-event
                                      :event-kind :tool-start
                                      :tool-id "t1"
                                      :tool-name "bash"})]
      (is (true? (get-in s1 [:tool-calls "t1" :expanded?]))))))

(deftest completed-tool-rows-are-retained-after-result-test
  (testing "tool rows remain after final agent result"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (-> (init-state)
                        (assoc :phase :streaming
                               :tool-order ["t1"]
                               :tool-calls {"t1" {:name "bash"
                                                  :args "{\"command\":\"echo hi\"}"
                                                  :status :success
                                                  :result "ok"
                                                  :is-error false}}))
          result    {:role "assistant" :content [{:type :text :text "done"}]}
          [s1 _]    (update-fn state {:type :agent-result :result result})]
      (is (= :idle (:phase s1)))
      (is (= ["t1"] (:tool-order s1)))
      (is (= "ok" (get-in s1 [:tool-calls "t1" :result]))))))

(deftest collapsed-tool-render-truncates-and-expanded-renders-full-test
  (testing "tool rendering honors collapsed vs expanded modes"
    (let [base-state (-> (init-state)
                         (assoc :phase :streaming
                                :stream-text ""
                                :tool-order ["t1"]
                                :tool-calls {"t1" {:name "read"
                                                   :args "{\"path\":\"foo.txt\"}"
                                                   :status :success
                                                   :result (str/join "\n" (map str (range 1 13)))
                                                   :is-error false
                                                   :expanded? false}}))
          collapsed (app/view base-state)
          expanded  (app/view (assoc base-state :tools-expanded? true))]
      (is (str/includes? collapsed "… (2 more lines, ctrl+o to expand)"))
      (is (not (str/includes? expanded "ctrl+o to expand")))
      (is (str/includes? expanded "12")))))

(deftest bash-collapsed-renders-tail-preview-test
  (testing "bash collapsed mode shows tail with earlier-lines hint"
    (let [state (-> (init-state)
                    (assoc :phase :streaming
                           :stream-text ""
                           :tool-order ["t1"]
                           :tool-calls {"t1" {:name "bash"
                                              :args "{\"command\":\"echo hi\"}"
                                              :status :success
                                              :result "1\n2\n3\n4\n5\n6\n7"
                                              :is-error false}}))
          out   (app/view state)]
      (is (str/includes? out "7"))
      (is (nil? (re-find #"(?m)^\s+1$" out)))
      (is (str/includes? out "earlier lines hidden, ctrl+o to expand")))))

(deftest tool-details-warnings-are-rendered-test
  (testing "tool details metadata appears in warning lines"
    (let [state (-> (init-state)
                    (assoc :phase :streaming
                           :stream-text ""
                           :tool-order ["t1"]
                           :tool-calls {"t1" {:name "grep"
                                              :args "{\"pattern\":\"foo\"}"
                                              :status :success
                                              :result "a.clj:1:foo"
                                              :details {:truncation {:truncated true :truncated-by :bytes}
                                                        :full-output-path "/tmp/full.log"
                                                        :match-limit-reached 100
                                                        :lines-truncated true}
                                              :is-error false}}))
          out   (app/view state)]
      (is (str/includes? out "Truncated output"))
      (is (str/includes? out "Full output: /tmp/full.log"))
      (is (str/includes? out "Match limit reached: 100"))
      (is (str/includes? out "Long lines truncated")))))

(deftest tool-result-event-preserves-content-and-details-test
  (testing "tool-result event stores structured content/details for rendering"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          base      (-> (init-state)
                        (assoc :phase :streaming)
                        (assoc :tool-order ["t1"]
                               :tool-calls {"t1" {:name "bash" :status :running}}))
          event     {:type :agent-event
                     :event-kind :tool-result
                     :tool-id "t1"
                     :content [{:type :text :text "ok"}
                               {:type :image :mime-type "image/png" :data "abc"}]
                     :details {:full-output-path "/tmp/all.log"}
                     :is-error false}
          [s1 _]    (update-fn base event)]
      (is (= "ok" (get-in s1 [:tool-calls "t1" :result])))
      (is (= [{:type :text :text "ok"}
              {:type :image :mime-type "image/png" :data "abc"}]
             (get-in s1 [:tool-calls "t1" :content])))
      (is (= {:full-output-path "/tmp/all.log"}
             (get-in s1 [:tool-calls "t1" :details]))))))

(deftest non-text-content-fallback-is-safe-test
  (testing "non-text/unknown content blocks render safe fallback"
    (let [state (-> (init-state)
                    (assoc :phase :streaming
                           :stream-text ""
                           :tool-order ["t1"]
                           :tool-calls {"t1" {:name "write"
                                              :args "{\"path\":\"/tmp/x\"}"
                                              :status :success
                                              :content [{:type :image :mime-type "image/png" :data "abc"}
                                                        {:type :custom :payload "x"}]
                                              :is-error false}}))
          out   (app/view state)]
      (is (str/includes? out "[image image/png]"))
      (is (str/includes? out "[unsupported content block: custom]")))))

(deftest extension-tool-renderers-override-builtins-test
  (testing "registered extension renderer output is used for call + result"
    (let [ui (ext-ui/create-ui-state)]
      (ext-ui/register-tool-renderer! ui
                                      "read"
                                      "ext-a"
                                      (fn [_args] "EXT call render")
                                      (fn [_tc _opts] "EXT result render"))
      (let [state (-> (init-state "test-model" {:ui-state-atom ui})
                      (assoc :phase :streaming
                             :stream-text ""
                             :tool-order ["t1"]
                             :tool-calls {"t1" {:name "read"
                                                :args "{\"path\":\"foo.txt\"}"
                                                :status :success
                                                :result "ignored builtin"
                                                :is-error false}}))
            out   (app/view state)]
        (is (str/includes? out "EXT call render"))
        (is (str/includes? out "EXT result render"))
        (is (not (str/includes? out "foo.txt")))))))

(deftest extension-tool-renderer-exception-falls-back-to-builtin-test
  (testing "renderer exceptions fall back to built-in tool rendering"
    (let [ui (ext-ui/create-ui-state)]
      (ext-ui/register-tool-renderer! ui
                                      "read"
                                      "ext-a"
                                      (fn [_args] (throw (ex-info "boom-call" {})))
                                      (fn [_tc _opts] (throw (ex-info "boom-result" {}))))
      (let [state (-> (init-state "test-model" {:ui-state-atom ui})
                      (assoc :phase :streaming
                             :stream-text ""
                             :tool-order ["t1"]
                             :tool-calls {"t1" {:name "read"
                                                :args "{\"path\":\"foo.txt\"}"
                                                :status :success
                                                :result "line-1"
                                                :is-error false}}))
            out   (app/view state)]
        (is (str/includes? out "foo.txt"))
        (is (str/includes? out "line-1"))))))

(deftest view-shows-error-test
  (testing "view shows error message"
    (let [state (assoc (init-state) :error "something broke")]
      (is (str/includes? (app/view state) "something broke")))))

;;;; Window resize

(deftest window-resize-updates-dimensions-test
  (testing "window-size message updates width and height and requests a hard clear"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 _]    (update-fn state (msg/window-size 120 40))
          out       (app/view s1)]
      (is (= 120 (:width s1)))
      (is (= 40 (:height s1)))
      (is (true? (:force-clear? s1)))
      (is (str/starts-with? out "\u001b[2J\u001b[H")))))

(deftest external-message-appended-to-transcript-test
  (testing "external-message appends assistant text and keeps polling"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          event     {:type :external-message
                     :message {:role "assistant"
                               :content [{:type :text :text "Subagent complete"}]}}
          [s1 cmd]  (update-fn state event)]
      (is (= 1 (count (:messages s1))))
      (is (= :assistant (:role (first (:messages s1)))))
      (is (= "Subagent complete" (:text (first (:messages s1)))))
      (is (some? cmd)))))

(deftest subagent-result-rich-render-test
  (testing "subagent-result custom-type renders rich heading"
    (let [state (assoc (init-state)
                       :messages [{:role :assistant
                                   :custom-type "subagent-result"
                                   :text "Subagent #1 finished \"do thing\" in 2s\n\nResult:\nAll good"}])
          out   (app/view state)]
      (is (str/includes? out "Subagent Result"))
      (is (str/includes? out "Subagent #1 finished"))
      (is (str/includes? out "All good")))))

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
    (let [terminal (charm/create-terminal)]
      (try
        (let [km (keymap/create-keymap terminal)]
          (is (some? km) "keymap created successfully"))
        (finally
          (.close terminal))))))
