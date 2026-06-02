(ns psi.tui.app-input-selector-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [charm.components.text-input :as text-input]
   [charm.message :as msg]
   [psi.app-runtime.projections :as projections]
   [psi.tui.app :as app]
   [psi.tui.app.support :as support]
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

(def ^:private sample-builtin-command-specs
  "Representative backend built-in command specs (single-sourced via the
   :psi.agent-session/builtin-command-specs resolver in production). Seeded into
   the test init state so slash autocomplete has built-in candidates without a
   live backend query."
  [{:name "quit" :description "exit the session"}
   {:name "status" :description "show session diagnostics"}
   {:name "help" :description "show this help"}
   {:name "reload-models" :description "reload custom model definitions"}
   {:name "reload-prompts" :description "re-discover prompt templates"}
   {:name "speed" :description "show or set speed mode"}
   {:name "effort" :description "show or set effort override"}])

(defn- init-state
  ([] (init-state {}))
  ([opts]
   (let [builtin-specs (get opts :builtin-command-specs sample-builtin-command-specs)
         opts          (dissoc opts :builtin-command-specs)
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
         [state _cmd] (init-fn)]
     (assoc state :builtin-command-specs (vec builtin-specs)))))

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

(deftest history-up-from-empty-enters-browsing-at-newest-test
  (testing "up from empty input enters history browse mode at newest entry"
    (let [update-fn (app/make-update (stub-agent-fn "ok"))
          submit-text (fn [state s]
                        (let [typed         (type-text update-fn state s)
                              [submitted _] (update-fn typed (msg/key-press :enter))]
                          (if (= :streaming (:phase submitted))
                            (first (update-fn submitted {:type :agent-result
                                                         :result {:content [{:type :text :text "ok"}]}}))
                            submitted)))
          base      (submit-text (submit-text (init-state) "alpha") "beta")
          state     (assoc base :phase :idle :input (text-input/set-value (:input base) ""))
          [s1 _]    (update-fn state (msg/key-press :up))]
      (is (= "beta" (text-input/value (:input s1))))
      (is (= 1 (get-in s1 [:prompt-input-state :history :browse-index]))))))

(deftest history-down-from-newest-exits-browsing-to-empty-test
  (testing "down at newest history entry exits browse mode and restores empty input"
    (let [update-fn (app/make-update (stub-agent-fn "ok"))
          submit-text (fn [state s]
                        (let [typed         (type-text update-fn state s)
                              [submitted _] (update-fn typed (msg/key-press :enter))]
                          (if (= :streaming (:phase submitted))
                            (first (update-fn submitted {:type :agent-result
                                                         :result {:content [{:type :text :text "ok"}]}}))
                            submitted)))
          base      (submit-text (submit-text (init-state) "alpha") "beta")
          state     (assoc base :phase :idle :input (text-input/set-value (:input base) ""))
          [s1 _]    (update-fn state (msg/key-press :up))
          [s2 _]    (update-fn s1 (msg/key-press :down))]
      (is (= "" (text-input/value (:input s2))))
      (is (nil? (get-in s2 [:prompt-input-state :history :browse-index]))))))

(deftest history-editing-clears-browse-index-test
  (testing "normal edit and programmatic set-text clear history browse mode"
    (let [update-fn (app/make-update (stub-agent-fn "ok"))
          submit-text (fn [state s]
                        (let [typed         (type-text update-fn state s)
                              [submitted _] (update-fn typed (msg/key-press :enter))]
                          (if (= :streaming (:phase submitted))
                            (first (update-fn submitted {:type :agent-result
                                                         :result {:content [{:type :text :text "ok"}]}}))
                            submitted)))
          base      (submit-text (submit-text (init-state) "alpha") "beta")
          state     (assoc base :phase :idle :input (text-input/set-value (:input base) ""))
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
      (is (some #(= "/help" (:value %)) (:candidates ac)))
      (is (not-any? #(= "/logprobs" (:value %)) (:candidates ac))))))

(deftest autocomplete-slash-skills-use-canonical-name-order-test
  (testing "slash autocomplete does not expose raw session skill vector order"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state)
                           :skills [{:name "zeta"}
                                    {:name "alpha"}
                                    {:name "middle"}])
          s1        (type-text update-fn state "/skill:")
          values    (mapv :value (get-in s1 [:prompt-input-state :autocomplete :candidates]))]
      (is (= ["/skill:alpha" "/skill:middle" "/skill:zeta"] values)))))

(deftest autocomplete-slash-includes-extension-commands-test
  (testing "slash autocomplete includes extension command names"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :extension-command-names ["chain" "chain-list"])
          [s1 _]    (update-fn state (msg/key-press "/"))
          cand-vals (set (map :value (get-in s1 [:prompt-input-state :autocomplete :candidates])))]
      (is (contains? cand-vals "/chain"))
      (is (contains? cand-vals "/chain-list")))))

(deftest refresh-extension-command-names-folds-builtin-specs-test
  (testing "one query refreshes both extension command names and built-in specs"
    (let [builtin [{:name "reload-models" :description "reload models"}]
          query-fn (fn [_query]
                     {:psi.extension/command-names ["chain"]
                      :psi.agent-session/builtin-command-specs builtin})
          state    {:query-fn query-fn}
          refreshed (support/refresh-extension-command-names state)]
      (is (= ["chain"] (:extension-command-names refreshed)))
      (is (= builtin (:builtin-command-specs refreshed)))))
  (testing "non-vector values leave the corresponding slot untouched"
    (let [query-fn (fn [_query]
                     {:psi.extension/command-names nil
                      :psi.agent-session/builtin-command-specs nil})
          state    {:query-fn query-fn
                    :extension-command-names ["existing"]
                    :builtin-command-specs [{:name "help" :description "show this help"}]}
          refreshed (support/refresh-extension-command-names state)]
      (is (= ["existing"] (:extension-command-names refreshed)))
      (is (= [{:name "help" :description "show this help"}]
             (:builtin-command-specs refreshed))))))

(deftest autocomplete-slash-includes-backend-builtin-commands-test
  (testing "slash autocomplete includes built-in commands sourced from backend specs"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          ;; /reload-models was previously missing from the hardcoded TUI list.
          state     (assoc (init-state)
                           :builtin-command-specs
                           [{:name "help" :description "show this help"}
                            {:name "reload-models" :description "reload custom model definitions"}
                            {:name "speed" :description "show or set speed mode"}])
          [s1 _]    (update-fn state (msg/key-press "/"))
          cand-vals (set (map :value (get-in s1 [:prompt-input-state :autocomplete :candidates])))]
      (is (contains? cand-vals "/reload-models"))
      (is (contains? cand-vals "/speed"))
      (is (contains? cand-vals "/help"))))
  (testing "built-in candidates come from state, not a hardcoded list"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state) :builtin-command-specs [])
          [s1 _]    (update-fn state (msg/key-press "/"))
          cand-vals (set (map :value (get-in s1 [:prompt-input-state :autocomplete :candidates])))]
      (is (not (contains? cand-vals "/quit"))
          "with empty backend specs no built-in slash command is offered"))))

(deftest autocomplete-slash-dedupes-builtin-template-collision-test
  ;; TS1: built-in specs are folded into the same candidate set as prompt
  ;; templates / extension commands and deduped via `distinct` in
  ;; slash-candidates. A built-in name colliding with a template (or extension
  ;; command) name must yield exactly one candidate, not two.
  (testing "a built-in colliding with a prompt template yields one /resume candidate"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state)
                           :builtin-command-specs [{:name "resume" :description "resume the session"}]
                           :prompt-templates [{:name "resume"}])
          [s1 _]    (update-fn state (msg/key-press "/"))
          cand-vals (mapv :value (get-in s1 [:prompt-input-state :autocomplete :candidates]))]
      (is (= 1 (count (filter #{"/resume"} cand-vals)))
          "built-in/template name collision is deduped to a single candidate")))
  (testing "a built-in colliding with an extension command yields one /resume candidate"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (assoc (init-state)
                           :builtin-command-specs [{:name "resume" :description "resume the session"}]
                           :extension-command-names ["resume"])
          [s1 _]    (update-fn state (msg/key-press "/"))
          cand-vals (mapv :value (get-in s1 [:prompt-input-state :autocomplete :candidates]))]
      (is (= 1 (count (filter #{"/resume"} cand-vals)))
          "built-in/extension name collision is deduped to a single candidate"))))

(deftest autocomplete-accept-on-enter-submits-slash-test
  (testing "enter accepts selected slash suggestion and submits"
    (let [submitted (atom nil)
          agent-fn  (fn [text _queue] (reset! submitted text))
          update-fn (app/make-update agent-fn)
          state     (assoc (init-state) :prompt-templates [{:name "deploy"}])
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
          state     (assoc state :input (text-input/set-value (:input state) "alp"))
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
                        (assoc :input (text-input/set-value (:input (init-state)) "@\"foo\""))
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
                        (assoc :input (text-input/set-value (:input (init-state)) "")))
          [s1 cmd]  (update-fn streaming (msg/key-press "x"))]
      (is (= :streaming (:phase s1)))
      (is (= "x" (text-input/value (:input s1))))
      (is (nil? cmd)))))

