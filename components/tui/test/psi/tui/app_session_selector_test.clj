(ns psi.tui.app-session-selector-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [charm.message :as msg]
   [psi.agent-session.persistence :as persist]
   [psi.app-runtime.projections :as projections]
   [psi.app-runtime.ui-actions :as ui-actions]
   [psi.tui.app :as app]
   [psi.tui.app.update :as app-update]
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
  ([] (init-state {}))
  ([opts]
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
         init-fn      (app/make-init nil ui-read-fn ui-disp-fn
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
                 :result {:role    "assistant"
                          :content [{:type :text :text response-text}]}})))

(defn- selector-session-item
  [session-id display-name worktree-path & {:keys [parent-id is-active is-streaming]}]
  {:item/id [:session session-id]
   :item/kind :session
   :item/session-id session-id
   :item/parent-id (when parent-id [:session parent-id])
   :item/display-name display-name
   :item/is-active (boolean is-active)
   :item/is-streaming (boolean is-streaming)
   :item/worktree-path worktree-path})

(defn- selector-fork-item
  [session-id entry-id display-name & {:keys [parent-id]}]
  {:item/id [:fork-point entry-id]
   :item/kind :fork-point
   :item/session-id session-id
   :item/entry-id entry-id
   :item/parent-id (vector :session (or parent-id session-id))
   :item/display-name display-name})

(defn- make-session-selector-fn
  [active-id items]
  (let [selector {:selector/kind :context-session
                  :selector/active-item-id (some-> active-id (vector :session))
                  :selector/items items}]
    (fn []
      (ui-actions/context-session-action selector))))

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
                      :cwd "/tmp/psi-test"
                      :worktree-path "/tmp/psi-test"}])]
      (let [update-fn (app/make-update (stub-agent-fn ""))
            state     (init-state {:cwd "/tmp/psi-test"})
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
                        :cwd "/tmp/psi-test"
                        :worktree-path "/tmp/psi-test"}])]
        (let [update-fn (app/make-update (stub-agent-fn ""))
              state     (init-state {:cwd "/tmp/psi-test"
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
            state     (assoc (init-state {:cwd "/tmp/psi-test"})
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
                      :cwd "/tmp/psi-test"
                      :worktree-path "/tmp/psi-test"}])]
      (let [update-fn (app/make-update (stub-agent-fn ""))
            state     (init-state {:cwd "/tmp/psi-test"})
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
                      :cwd "/tmp/psi-test"
                      :worktree-path "/tmp/psi-test"}])]
      (let [update-fn (app/make-update (stub-agent-fn ""))
            state     (init-state {:cwd "/tmp/psi-test"})
            typed     (type-text update-fn state "/resume")
            [s1 _]    (update-fn typed (msg/key-press :enter))
            [s2 _]    (update-fn s1 (msg/key-press "a"))
            [s3 _]    (update-fn s2 (msg/key-press :backspace))]
        (is (= "a" (get-in s2 [:session-selector :search])))
        (is (= "" (get-in s3 [:session-selector :search])))))))

(deftest tree-command-opens-tree-selector-test
  (testing "/tree enters selector in :tree mode"
    (let [update-fn   (app/make-update (stub-agent-fn ""))
          selector-fn (make-session-selector-fn
                       "s1"
                       [(selector-session-item "s1" "Root" "/tmp/psi-test/root" :is-active true)])
          [state _]   ((app/make-init nil nil nil {:dispatch-fn default-dispatch-fn
                                                   :cwd "/tmp/psi-test"
                                                   :focus-session-id "s1"
                                                   :session-selector-fn selector-fn}))
          typed      (type-text update-fn state "/tree")
          [s1 _]     (update-fn typed (msg/key-press :enter))]
      (is (= :selecting-session (:phase s1)))
      (is (= :tree (:session-selector-mode s1)))
      (is (= :select-session (get-in s1 [:session-selector :ui/action :ui/action-name])))
      (is (= "s1" (get-in s1 [:session-selector :active-session-id]))))))

(deftest tree-direct-switch-restores-state-test
  (testing "/tree <id> dispatch result triggers switch-session callback"
    (let [switched-id (atom nil)
          dispatch-fn (fn [text]
                        (when (= text "/tree s2")
                          {:type :tree-switch :session-id "s2"}))
          update-fn   (app/make-update (stub-agent-fn ""))
          state       (init-state {:dispatch-fn dispatch-fn
                                   :switch-session-fn! (fn [sid]
                                                         (reset! switched-id sid)
                                                         {:nav/session-id sid
                                                          :nav/rehydration {:messages [{:role :assistant :text "switched"}]
                                                                            :tool-calls {"t1" {:name "read" :status :success :result "ok"}}
                                                                            :tool-order ["t1"]}})})
          typed       (type-text update-fn state "/tree s2")
          [s1 _]      (update-fn typed (msg/key-press :enter))]
      (is (= "s2" @switched-id))
      (is (= :idle (:phase s1)))
      (is (= "switched" (get-in s1 [:messages 0 :text])))
      (is (= ["t1"] (:tool-order s1)))
      (is (:force-clear? s1)))))

(deftest tree-selector-enter-switches-via-callback-test
  (testing "enter in :tree selector switches highlighted session"
    (let [switched-id (atom nil)
          update-fn   (app/make-update (stub-agent-fn ""))
          selector-fn (make-session-selector-fn
                       "s1"
                       [(selector-session-item "s1" "Root" "/tmp/psi-test/root" :is-active true)
                        (selector-session-item "s2" "Child" "/tmp/psi-test/child" :parent-id "s1")])
          [state _]   ((app/make-init nil nil nil {:dispatch-fn default-dispatch-fn
                                                   :cwd "/tmp/psi-test"
                                                   :focus-session-id "s1"
                                                   :session-selector-fn selector-fn
                                                   :switch-session-fn! (fn [sid]
                                                                         (reset! switched-id sid)
                                                                         {:nav/session-id sid
                                                                          :nav/rehydration {:messages [{:role :assistant
                                                                                                        :text (str "switched " sid)}]
                                                                                            :tool-calls {}
                                                                                            :tool-order []}})}))
          typed       (type-text update-fn state "/tree")
          [s1 _]      (update-fn typed (msg/key-press :enter))
          [s2 _]      (update-fn s1 (msg/key-press :down))
          [s3 _]      (update-fn s2 (msg/key-press :enter))]
      (is (= "s2" @switched-id))
      (is (= :idle (:phase s3)))
      (is (= "switched s2" (get-in s3 [:messages 0 :text])))
      (is (:force-clear? s3)))))

(deftest tree-selector-view-renders-hierarchy-and-active-badge-test
  (testing "tree selector view renders parent-child connectors and active marker"
    (let [update-fn   (app/make-update (stub-agent-fn ""))
          selector-fn (make-session-selector-fn
                       "s1"
                       [(selector-session-item "s1" "Root" "/tmp/psi-test/root" :is-active true)
                        (selector-session-item "s2" "Child" "/tmp/psi-test/child" :parent-id "s1")])
          [state _]   ((app/make-init nil nil nil {:dispatch-fn default-dispatch-fn
                                                   :cwd "/tmp/psi-test"
                                                   :focus-session-id "s1"
                                                   :session-selector-fn selector-fn}))
          typed     (type-text update-fn state "/tree")
          [s1 _]    (update-fn typed (msg/key-press :enter))
          plain     (ansi/strip-ansi (app/view (assoc s1 :width 120)))
          root-idx  (.indexOf plain "Root")
          child-idx (.indexOf plain "└─ Child")]
      (is (str/includes? plain "Session Tree"))
      (is (str/includes? plain "● Root"))
      (is (str/includes? plain "/tmp/psi-test/root"))
      (is (str/includes? plain "/tmp/psi-test/child"))
      (is (str/includes? plain "[active]"))
      (is (str/includes? plain "└─ Child"))
      (is (and (>= root-idx 0)
               (>= child-idx 0)
               (< root-idx child-idx))))))

(deftest tree-selector-view-aligns-status-columns-test
  (testing "tree selector status badges align across rows"
    (let [update-fn   (app/make-update (stub-agent-fn ""))
          selector-fn (make-session-selector-fn
                       "s1"
                       [(selector-session-item "s1" "Root" "/tmp/psi-test/root" :is-active true)
                        (selector-session-item "s2" "Child" "/tmp/psi-test/child" :parent-id "s1" :is-streaming true)])
          [state _]   ((app/make-init nil nil nil {:dispatch-fn default-dispatch-fn
                                                   :cwd "/tmp/psi-test"
                                                   :focus-session-id "s1"
                                                   :session-selector-fn selector-fn}))
          typed      (type-text update-fn state "/tree")
          [s1 _]     (update-fn typed (msg/key-press :enter))
          plain      (ansi/strip-ansi (app/view (assoc s1 :width 120)))
          lines      (str/split-lines plain)
          row-root       (first (filter #(str/includes? % "Root") lines))
          row-child      (first (filter #(str/includes? % "Child") lines))
          active-col     (when row-root (.indexOf row-root "[active]"))
          stream-col     (when row-child (.indexOf row-child "[stream]"))
          root-id-col    (when row-root (.indexOf row-root "s1"))
          child-id-col   (when row-child (.indexOf row-child "s2"))
          expected-stream-col (when (number? active-col)
                                (+ active-col (count "[active]") 1))]
      (is (string? row-root))
      (is (string? row-child))
      (is (number? active-col))
      (is (number? stream-col))
      (is (= expected-stream-col stream-col))
      (is (= root-id-col child-id-col)))))

(deftest tree-selector-view-prefers-display-name-test
  (testing "tree selector uses derived display-name for live sessions"
    (let [update-fn   (app/make-update (stub-agent-fn ""))
          selector-fn (make-session-selector-fn
                       "s1"
                       [(selector-session-item "s1" "Investigate failing tests" "/tmp/psi-test/root" :is-active true)
                        (selector-session-item "s2" "Refactor selector" "/tmp/psi-test/child" :parent-id "s1")])
          [state _]   ((app/make-init nil nil nil {:dispatch-fn default-dispatch-fn
                                                   :cwd "/tmp/psi-test"
                                                   :focus-session-id "s1"
                                                   :session-selector-fn selector-fn}))
          typed     (type-text update-fn state "/tree")
          [s1 _]    (update-fn typed (msg/key-press :enter))
          plain     (ansi/strip-ansi (app/view (assoc s1 :width 120)))]
      (is (str/includes? plain "Investigate failing tests"))
      (is (str/includes? plain "Refactor selector"))
      (is (not (str/includes? plain "session-s1")))
      (is (not (str/includes? plain "session-s2"))))))

(deftest tree-selector-view-renders-current-session-fork-points-test
  (testing "tree selector renders abbreviated user prompts under the active session"
    (let [update-fn   (app/make-update (stub-agent-fn ""))
          selector-fn (make-session-selector-fn
                       "s1"
                       [(selector-session-item "s1" "Root" "/tmp/psi-test/root" :is-active true)
                        (selector-session-item "s2" "Child" "/tmp/psi-test/child" :parent-id "s1")
                        (selector-fork-item "s1" "e1" "Investigate prompt lifecycle convergence")])
          [state _]   ((app/make-init nil nil nil {:dispatch-fn default-dispatch-fn
                                                   :cwd "/tmp/psi-test"
                                                   :focus-session-id "s1"
                                                   :session-selector-fn selector-fn}))
          typed     (type-text update-fn state "/tree")
          [s1 _]    (update-fn typed (msg/key-press :enter))
          plain     (ansi/strip-ansi (app/view (assoc s1 :width 120)))
          root-idx  (.indexOf plain "● Root")
          child-idx (.indexOf plain "└─ Child")
          fork-idx  (.indexOf plain "⎇ Investigate prompt lifecycle convergence")]
      (is (str/includes? plain "Root"))
      (is (str/includes? plain "Investigate prompt lifecycle convergence"))
      (is (str/includes? plain "fork"))
      (is (not (str/includes? plain "⎇ /tree")))
      (is (and (>= root-idx 0)
               (>= child-idx 0)
               (>= fork-idx 0)
               (< root-idx child-idx)
               (< child-idx fork-idx))))))

(deftest tree-selector-view-renders-shared-selector-order-test
  (testing "tree selector can consume shared app-runtime selector order directly"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          selector-fn (fn []
                        (ui-actions/context-session-action
                         {:selector/kind :context-session
                          :selector/active-item-id [:session "s1"]
                          :selector/items [{:item/id [:session "s1"]
                                            :item/kind :session
                                            :item/session-id "s1"
                                            :item/display-name "Root"
                                            :item/is-active true
                                            :item/worktree-path "/tmp/psi-test/root"}
                                           {:item/id [:session "s2"]
                                            :item/kind :session
                                            :item/session-id "s2"
                                            :item/parent-id [:session "s1"]
                                            :item/display-name "Child"
                                            :item/worktree-path "/tmp/psi-test/child"}
                                           {:item/id [:fork-point "e1"]
                                            :item/kind :fork-point
                                            :item/session-id "s1"
                                            :item/entry-id "e1"
                                            :item/parent-id [:session "s1"]
                                            :item/display-name "Branch from here"}]}))
          [state _] ((app/make-init nil nil nil {:dispatch-fn default-dispatch-fn
                                                 :cwd "/tmp/psi-test"
                                                 :focus-session-id "s1"
                                                 :session-selector-fn selector-fn}))
          typed     (type-text update-fn state "/tree")
          [s1 _]    (update-fn typed (msg/key-press :enter))
          plain     (ansi/strip-ansi (app/view (assoc s1 :width 120)))
          lines     (vec (str/split-lines plain))
          root-idx  (first (keep-indexed (fn [i line] (when (str/includes? line "Root") i)) lines))
          child-idx (first (keep-indexed (fn [i line] (when (str/includes? line "Child") i)) lines))
          fork-idx  (first (keep-indexed (fn [i line] (when (str/includes? line "Branch from here") i)) lines))]
      (is (and (some? root-idx)
               (some? child-idx)
               (some? fork-idx)
               (< root-idx child-idx)
               (< child-idx fork-idx))))))

(deftest tree-selector-enter-on-fork-point-forks-and-selects-fork-test
  (testing "enter on a prompt row forks from its entry and selects the fork"
    (let [forked-entry-id (atom nil)
          update-fn       (app/make-update (stub-agent-fn ""))
          selector-fn     (make-session-selector-fn
                           "s1"
                           [(selector-session-item "s1" "Root" "/tmp/psi-test/root" :is-active true)
                            (selector-session-item "s2" "Child" "/tmp/psi-test/child" :parent-id "s1")
                            (selector-fork-item "s1" "e1" "Branch from here")])
          [state _]       ((app/make-init nil nil nil {:dispatch-fn default-dispatch-fn
                                                       :cwd "/tmp/psi-test"
                                                       :focus-session-id "s1"
                                                       :session-selector-fn selector-fn
                                                       :fork-session-fn! (fn [entry-id]
                                                                           (reset! forked-entry-id entry-id)
                                                                           {:nav/session-id "s3"
                                                                            :nav/rehydration {:messages [{:role :user
                                                                                                          :text "Branch from here"}
                                                                                                         {:role :assistant
                                                                                                          :text "reply included"}]
                                                                                              :tool-calls {}
                                                                                              :tool-order []}})}))
          typed           (type-text update-fn state "/tree")
          [s1 _]          (update-fn typed (msg/key-press :enter))
          [s2 _]          (update-fn s1 (msg/key-press :down))
          [s3 _]          (update-fn s2 (msg/key-press :down))
          [s4 _]          (update-fn s3 (msg/key-press :enter))]
      (is (= "e1" @forked-entry-id))
      (is (= :idle (:phase s4)))
      (is (= "s3" (:focus-session-id s4)))
      (is (= "Branch from here" (get-in s4 [:messages 0 :text])))
      (is (= "reply included" (get-in s4 [:messages 1 :text])))
      (is (:force-clear? s4)))))

(deftest frontend-action-select-resume-session-semantic-convergence-test
  (testing "select-resume-session preserves canonical ui/action, status, and selected session-path semantics"
    (let [captured   (atom nil)
          update-fn  (app/make-update (stub-agent-fn ""))
          action     (ui-actions/resume-session-action
                      {:psi.session/list [{:psi.session-info/path "/tmp/psi-test/a.ndedn"
                                           :psi.session-info/name "Session A"
                                           :psi.session-info/worktree-path "/tmp/psi-test"
                                           :psi.session-info/first-message "hello"
                                           :psi.session-info/modified (java.time.Instant/now)}
                                          {:psi.session-info/path "/tmp/psi-test/b.ndedn"
                                           :psi.session-info/name "Session B"
                                           :psi.session-info/worktree-path "/tmp/psi-test"
                                           :psi.session-info/first-message "world"
                                           :psi.session-info/modified (java.time.Instant/now)}]})
          state      (init-state {:frontend-action-handler-fn! #(do (reset! captured %) nil)})
          [opened _] (app-update/handle-dispatch-result state {:type :frontend-action
                                                               :request-id "req-r1"
                                                               :ui/action action})
          [moved _]  (update-fn opened (msg/key-press :down))
          [closed _] (update-fn moved (msg/key-press :enter))]
      (is (= :selecting-session (:phase opened)))
      (is (= :resume (:session-selector-mode opened)))
      (is (= action (get-in opened [:session-selector :ui/action])))
      (is (= :submitted (:ui.result/status @captured)))
      (is (= "req-r1" (:ui.result/request-id @captured)))
      (is (= :select-resume-session (:ui.result/action-key @captured)))
      (is (= action (:ui.result/ui-action @captured)))
      (is (= "/tmp/psi-test/b.ndedn" (:ui.result/value @captured)))
      (is (nil? (:session-selector closed))))))

(deftest frontend-action-select-session-switch-submit-test
  (testing "select-session switch submit preserves canonical ui/action, status, and selected value shape"
    (let [captured   (atom nil)
          update-fn  (app/make-update (stub-agent-fn ""))
          action     (ui-actions/context-session-action
                      {:selector/kind :context-session
                       :selector/active-item-id [:session "s1"]
                       :selector/items [{:item/id [:session "s1"]
                                         :item/kind :session
                                         :item/session-id "s1"
                                         :item/display-name "Root"
                                         :item/is-active true
                                         :item/worktree-path "/tmp/psi-test/root"
                                         :item/action {:action/kind :switch-session
                                                       :action/session-id "s1"}}
                                        {:item/id [:session "s2"]
                                         :item/kind :session
                                         :item/session-id "s2"
                                         :item/parent-id [:session "s1"]
                                         :item/display-name "Child"
                                         :item/worktree-path "/tmp/psi-test/child"
                                         :item/action {:action/kind :switch-session
                                                       :action/session-id "s2"}}
                                        {:item/id [:fork-point "e1"]
                                         :item/kind :fork-point
                                         :item/session-id "s1"
                                         :item/entry-id "e1"
                                         :item/parent-id [:session "s1"]
                                         :item/display-name "Branch from here"
                                         :item/action {:action/kind :fork-session
                                                       :action/entry-id "e1"}}]})
          state      (init-state {:frontend-action-handler-fn! #(do (reset! captured %) nil)})
          [opened _] (app-update/handle-dispatch-result state {:type :frontend-action
                                                               :request-id "req-s1"
                                                               :ui/action action})
          [moved _]  (update-fn opened (msg/key-press :down))
          [closed _] (update-fn moved (msg/key-press :enter))]
      (is (= :selecting-session (:phase opened)))
      (is (= :tree (:session-selector-mode opened)))
      (is (= action (get-in opened [:session-selector :ui/action])))
      (is (= :submitted (:ui.result/status @captured)))
      (is (= "req-s1" (:ui.result/request-id @captured)))
      (is (= :select-session (:ui.result/action-key @captured)))
      (is (= action (:ui.result/ui-action @captured)))
      (is (= {:action/kind :switch-session :action/session-id "s2"}
             (:ui.result/value @captured)))
      (is (nil? (:session-selector closed))))))

(deftest frontend-action-select-session-fork-submit-test
  (testing "select-session fork submit preserves canonical ui/action, status, and selected value shape"
    (let [captured   (atom nil)
          update-fn  (app/make-update (stub-agent-fn ""))
          action     (ui-actions/context-session-action
                      {:selector/kind :context-session
                       :selector/active-item-id [:session "s1"]
                       :selector/items [{:item/id [:session "s1"]
                                         :item/kind :session
                                         :item/session-id "s1"
                                         :item/display-name "Root"
                                         :item/is-active true
                                         :item/worktree-path "/tmp/psi-test/root"
                                         :item/action {:action/kind :switch-session
                                                       :action/session-id "s1"}}
                                        {:item/id [:session "s2"]
                                         :item/kind :session
                                         :item/session-id "s2"
                                         :item/parent-id [:session "s1"]
                                         :item/display-name "Child"
                                         :item/worktree-path "/tmp/psi-test/child"
                                         :item/action {:action/kind :switch-session
                                                       :action/session-id "s2"}}
                                        {:item/id [:fork-point "e1"]
                                         :item/kind :fork-point
                                         :item/session-id "s1"
                                         :item/entry-id "e1"
                                         :item/parent-id [:session "s1"]
                                         :item/display-name "Branch from here"
                                         :item/action {:action/kind :fork-session
                                                       :action/entry-id "e1"}}]})
          state      (init-state {:frontend-action-handler-fn! #(do (reset! captured %) nil)})
          [opened _] (app-update/handle-dispatch-result state {:type :frontend-action
                                                               :request-id "req-s2"
                                                               :ui/action action})
          [moved1 _] (update-fn opened (msg/key-press :down))
          [moved2 _] (update-fn moved1 (msg/key-press :down))
          [closed _] (update-fn moved2 (msg/key-press :enter))]
      (is (= :submitted (:ui.result/status @captured)))
      (is (= "req-s2" (:ui.result/request-id @captured)))
      (is (= :select-session (:ui.result/action-key @captured)))
      (is (= action (:ui.result/ui-action @captured)))
      (is (= {:action/kind :fork-session :action/entry-id "e1" :action/session-id nil}
             (:ui.result/value @captured)))
      (is (nil? (:session-selector closed))))))

(deftest frontend-action-select-session-cancel-test
  (testing "select-session cancel preserves canonical cancelled semantics"
    (let [captured   (atom nil)
          update-fn  (app/make-update (stub-agent-fn ""))
          action     (ui-actions/context-session-action
                      {:selector/kind :context-session
                       :selector/active-item-id [:session "s1"]
                       :selector/items [{:item/id [:session "s1"]
                                         :item/kind :session
                                         :item/session-id "s1"
                                         :item/display-name "Root"
                                         :item/is-active true
                                         :item/worktree-path "/tmp/psi-test/root"
                                         :item/action {:action/kind :switch-session
                                                       :action/session-id "s1"}}]})
          state      (init-state {:frontend-action-handler-fn! #(do (reset! captured %) nil)})
          [opened _] (app-update/handle-dispatch-result state {:type :frontend-action
                                                               :request-id "req-s3"
                                                               :ui/action action})
          [closed _] (update-fn opened (msg/key-press :escape))]
      (is (= :cancelled (:ui.result/status @captured)))
      (is (= "req-s3" (:ui.result/request-id @captured)))
      (is (= :select-session (:ui.result/action-key @captured)))
      (is (= action (:ui.result/ui-action @captured)))
      (is (= "Cancelled select-session." (:ui.result/message @captured)))
      (is (nil? (:session-selector closed))))))

(deftest tree-rename-result-renders-status-message-test
  (testing "tree rename command result appends confirmation text"
    (let [dispatch-fn (fn [text]
                        (when (= text "/tree name s1 Focus on prompt lifecycle")
                          {:type :tree-rename :session-id "s1" :session-name "Focus on prompt lifecycle"}))
          update-fn   (app/make-update (stub-agent-fn ""))
          state       (init-state {:dispatch-fn dispatch-fn})
          typed       (type-text update-fn state "/tree name s1 Focus on prompt lifecycle")
          [s1 _]      (update-fn typed (msg/key-press :enter))]
      (is (= :idle (:phase s1)))
      (is (str/includes? (get-in s1 [:messages 0 :text]) "Renamed session s1 to")))))
