(ns psi.agent-session.config-compaction-test
  "Tests for session naming, config flags, context token tracking,
  manual compaction, extension dispatch, and diagnostics."
  (:require
   [psi.agent-session.test-support :as test-support]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.bootstrap]
   [psi.agent-session.core :as session]
   [psi.state-kernel.dispatch :as kernel]
   [psi.agent-session.extensions :as ext]
   [psi.session-persistence.core :as persist]
   [psi.session-state.model :as session-data]
   [psi.session-state.state :as ss]))
(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

;; ── Session naming ──────────────────────────────────────────────────────────

(deftest session-naming-test
  (testing "set-session-name-in! updates name and appends entry"
    (let [[ctx session-id] (create-session-context)]
      (session/dispatch-in! ctx :session/set-session-name {:session-id session-id :name "my session"} {:origin :core})
      (is (= "my session" (:session-name (ss/get-session-data-in ctx session-id))))
      (is (some #(= :session-info (:kind %)) (persist/all-entries-in ctx session-id))))))

(deftest session-config-dispatch-test
  (testing "set-session-name-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (kernel/clear-event-log!)
      (session/dispatch-in! ctx :session/set-session-name {:session-id session-id :name "my session"} {:origin :core})
      (let [entry (last (kernel/event-log-entries))
            sd    (ss/get-session-data-in ctx session-id)]
        (is (= "my session" (:session-name sd)))
        (is (= :session/set-session-name (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:name "my session"} (dissoc (:event-data entry) :session-id))))))

  (testing "set-worktree-path-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (kernel/clear-event-log!)
      (session/dispatch-in! ctx :session/set-worktree-path {:session-id session-id :worktree-path "/repo/feature-z"} {:origin :core})
      (is (= "/repo/feature-z" (:worktree-path (ss/get-session-data-in ctx session-id))))
      (let [entry (last (kernel/event-log-entries))]
        (is (= :session/set-worktree-path (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:worktree-path "/repo/feature-z"} (dissoc (:event-data entry) :session-id))))))

  (testing "set-cache-breakpoints-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (kernel/clear-event-log!)
      (session/dispatch-in! ctx :session/set-cache-breakpoints {:session-id session-id :breakpoints #{:system :tools}} {:origin :core})
      (is (= #{:system :tools} (:cache-breakpoints (ss/get-session-data-in ctx session-id))))
      (let [entry (last (kernel/event-log-entries))]
        (is (= :session/set-cache-breakpoints (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:breakpoints #{:system :tools}} (dissoc (:event-data entry) :session-id))))))

  (testing "set-active-tools-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)
          _                  (kernel/clear-event-log!)
          tool-maps  [{:name "read"} {:name "bash"}]]
      (session/dispatch-in! ctx :session/set-active-tools {:session-id session-id :tool-maps tool-maps} {:origin :core})
      (is (= ["read" "bash"] (:tool-ids (ss/get-session-data-in ctx session-id))))
      (let [entry (last (filter #(= :session/set-active-tools (:event-type %))
                                (kernel/event-log-entries)))]
        (is (= :session/set-active-tools (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:tool-maps tool-maps} (dissoc (:event-data entry) :session-id)))))))

;; ── Context token tracking ──────────────────────────────────────────────────

(deftest context-usage-test
  (testing "update-context-usage-in! stores tokens and window"
    (let [[ctx session-id] (create-session-context)]
      (session/dispatch-in! ctx :session/update-context-usage {:session-id session-id :tokens 5000 :window 100000} {:origin :core})
      (let [sd (ss/get-session-data-in ctx session-id)]
        (is (= 5000 (:context-tokens sd)))
        (is (= 100000 (:context-window sd))))))

  (testing "update-context-usage-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (kernel/clear-event-log!)
      (session/dispatch-in! ctx :session/update-context-usage {:session-id session-id :tokens 5000 :window 100000} {:origin :core})
      (let [entry (last (kernel/event-log-entries))]
        (is (= :session/update-context-usage (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:tokens 5000 :window 100000} (dissoc (:event-data entry) :session-id))))))

  (testing "context fraction reflects stored usage"
    (let [[ctx session-id] (create-session-context)]
      (session/dispatch-in! ctx :session/update-context-usage {:session-id session-id :tokens 80000 :window 100000} {:origin :core})
      (let [sd (ss/get-session-data-in ctx session-id)]
        (is (= 0.8 (session-data/context-fraction-used sd))))))

  (testing "queued input helpers and bootstrap resource registration route through dispatch"
    (let [[ctx session-id] (create-session-context)
          _                  (kernel/clear-event-log!)
          template   {:name "greet" :description "d" :content "c" :source :project :file-path "/tmp/greet.md"}
          skill      {:name "coding" :description "d" :file-path "/tmp/SKILL.md"
                      :base-dir "/tmp" :source :project :disable-model-invocation false}]
      (session/steer-in! ctx session-id "steer")
      (session/follow-up-in! ctx session-id "follow")
      (session/dispatch-in! ctx :session/register-prompt-template {:session-id session-id :template template} {:origin :core})
      (session/dispatch-in! ctx :session/register-skill {:session-id session-id :skill skill} {:origin :core})
      (session/consume-queued-input-text-in! ctx session-id)
      (let [sd          (ss/get-session-data-in ctx session-id)
            entries      (kernel/event-log-entries)
            event-types   (mapv :event-type entries)
            event-indexes (zipmap event-types (range))]
        (is (= [] (:steering-messages sd)))
        (is (= [] (:follow-up-messages sd)))
        (is (= 1 (count (:prompt-templates sd))))
        (is (= ["coding"] (:skill-ids sd)))
        (is (nil? (:skills sd)))
        (is (some #{:session/enqueue-steering-message} event-types)
            {:event-types event-types})
        (is (some #{:session/enqueue-follow-up-message} event-types)
            {:event-types event-types})
        (is (some #{:session/register-prompt-template} event-types)
            {:event-types event-types})
        (is (some #{:session/refresh-system-prompt} event-types)
            {:event-types event-types})
        (is (some #{:session/register-skill} event-types)
            {:event-types event-types})
        (is (= :session/clear-queued-messages (last event-types))
            {:event-types event-types})
        (is (< (get event-indexes :session/enqueue-steering-message)
               (get event-indexes :session/enqueue-follow-up-message)
               (get event-indexes :session/register-prompt-template)
               (get event-indexes :session/register-skill)
               (get event-indexes :session/clear-queued-messages))
            {:event-types event-types}))))

  (testing "register-skill refreshes the system prompt so newly added skills appear"
    (let [[ctx session-id] (create-session-context)
          tool-defs [{:name "read"
                      :label "Read"
                      :description "Read files"
                      :lambda-description "λf. content(f)"
                      :parameters {:type "object" :properties {} :required ["path"]}}
                     {:name "bash"
                      :label "Bash"
                      :description "Run shell commands"
                      :lambda-description "λcmd. shell(cmd)"
                      :parameters {:type "object" :properties {} :required ["command"]}}
                     {:name "edit"
                      :label "Edit"
                      :description "Edit files"
                      :lambda-description "λf. find(exact) → replace"
                      :parameters {:type "object" :properties {} :required ["path" "oldText" "newText"]}}
                     {:name "write"
                      :label "Write"
                      :description "Write files"
                      :lambda-description "λf. create(f) ∨ overwrite(f)"
                      :parameters {:type "object" :properties {} :required ["path" "content"]}}
                     {:name "psi-tool"
                      :label "psi-tool"
                      :description "Query Psi state"
                      :lambda-description "λquery. graph(psi)"
                      :parameters {:type "object" :properties {} :required ["action"]}}]
          skill {:name "coding"
                 :description "Use coding guidance"
                 :file-path "/tmp/SKILL.md"
                 :base-dir "/tmp"
                 :source :project
                 :disable-model-invocation false}]
      (session/dispatch-in! ctx :session/set-active-tools {:session-id session-id :tool-maps tool-defs} {:origin :test})
      (session/dispatch-in! ctx :session/set-system-prompt-build-opts
                            {:session-id session-id
                             :opts {:cwd "/tmp"
                                    :selected-tools ["read" "bash" "edit" "write" "psi-tool"]
                                    :skills []}}
                            {:origin :test})
      (session/dispatch-in! ctx :session/refresh-system-prompt {:session-id session-id} {:origin :test})
      (let [before-prompt (:system-prompt (ss/get-session-data-in ctx session-id))]
        (is (str/includes? (or before-prompt "") "λ tools.\nread → λf. content(f)"))
        (is (not (str/includes? (or before-prompt "") "coding → Use coding guidance @ /tmp/SKILL.md"))))
      (let [result (session/dispatch-in! ctx :session/register-skill {:session-id session-id :skill skill} {:origin :core})]
        (is (= {:added? true :changed? true :count 1
                :skills [skill]}
               result)))
      (let [after-prompt (:system-prompt (ss/get-session-data-in ctx session-id))]
        (is (str/includes? (or after-prompt "") "λ tools.\nread → λf. content(f)"))
        (is (str/includes? (or after-prompt "") "coding → Use coding guidance @ /tmp/SKILL.md")))))

  (testing "duplicate register-skill leaves skills and prompt unchanged"
    (let [[ctx session-id] (create-session-context)
          tool-defs [{:name "read"
                      :label "Read"
                      :description "Read files"
                      :lambda-description "λf. content(f)"
                      :parameters {:type "object" :properties {} :required ["path"]}}
                     {:name "bash"
                      :label "Bash"
                      :description "Run shell commands"
                      :lambda-description "λcmd. shell(cmd)"
                      :parameters {:type "object" :properties {} :required ["command"]}}
                     {:name "edit"
                      :label "Edit"
                      :description "Edit files"
                      :lambda-description "λf. find(exact) → replace"
                      :parameters {:type "object" :properties {} :required ["path" "oldText" "newText"]}}
                     {:name "write"
                      :label "Write"
                      :description "Write files"
                      :lambda-description "λf. create(f) ∨ overwrite(f)"
                      :parameters {:type "object" :properties {} :required ["path" "content"]}}
                     {:name "psi-tool"
                      :label "psi-tool"
                      :description "Query Psi state"
                      :lambda-description "λquery. graph(psi)"
                      :parameters {:type "object" :properties {} :required ["action"]}}]
          skill {:name "coding"
                 :description "Use coding guidance"
                 :file-path "/tmp/SKILL.md"
                 :base-dir "/tmp"
                 :source :project
                 :disable-model-invocation false}]
      (session/dispatch-in! ctx :session/set-active-tools {:session-id session-id :tool-maps tool-defs} {:origin :test})
      (session/dispatch-in! ctx :session/set-system-prompt-build-opts
                            {:session-id session-id
                             :opts {:cwd "/tmp"
                                    :selected-tools ["read" "bash" "edit" "write" "psi-tool"]
                                    :skills []}}
                            {:origin :test})
      (session/dispatch-in! ctx :session/refresh-system-prompt {:session-id session-id} {:origin :test})
      (session/dispatch-in! ctx :session/register-skill {:session-id session-id :skill skill} {:origin :core})
      (kernel/clear-event-log!)
      (let [before-prompt (:system-prompt (ss/get-session-data-in ctx session-id))
            result        (session/dispatch-in! ctx :session/register-skill {:session-id session-id :skill (assoc skill :description "Replacement attempt")} {:origin :core})
            after-prompt  (:system-prompt (ss/get-session-data-in ctx session-id))
            sd            (ss/get-session-data-in ctx session-id)
            event-types   (mapv :event-type (kernel/event-log-entries))]
        (is (= {:added? false :changed? false :count 1
                :skills [skill]}
               result))
        (is (= before-prompt after-prompt))
        (is (str/includes? (or after-prompt "") "λ tools.\nread → λf. content(f)"))
        (is (= ["coding"] (:skill-ids sd)))
        (is (nil? (:skills sd)))
        (is (= [:session/register-skill] event-types)))))

  (testing "duplicate register-skill canonicalizes stored skills without refreshing the prompt"
    (let [z-skill {:name "z-skill"
                   :description "Z"
                   :file-path "/tmp/z/SKILL.md"
                   :base-dir "/tmp/z"
                   :source :project
                   :disable-model-invocation false}
          a-skill {:name "a-skill"
                   :description "A"
                   :file-path "/tmp/a/SKILL.md"
                   :base-dir "/tmp/a"
                   :source :project
                   :disable-model-invocation false}
          [ctx session-id] (create-session-context)
          _ (psi.agent-session.bootstrap/load-startup-resources-in! ctx session-id {:skills [z-skill a-skill]})
          _ (session/dispatch-in! ctx :session/set-system-prompt {:session-id session-id :prompt "stable prompt"} {:origin :test})]
      (kernel/clear-event-log!)
      (let [before-prompt (:system-prompt (ss/get-session-data-in ctx session-id))
            result        (session/dispatch-in! ctx :session/register-skill
                                                {:session-id session-id
                                                 :skill (assoc z-skill :description "Replacement attempt")}
                                                {:origin :core})
            after-prompt  (:system-prompt (ss/get-session-data-in ctx session-id))
            sd            (ss/get-session-data-in ctx session-id)
            event-types   (mapv :event-type (kernel/event-log-entries))]
        (is (= {:added? false :changed? false :count 2
                :skills [a-skill z-skill]}
               result))
        (is (= before-prompt after-prompt))
        (is (= ["z-skill" "a-skill"] (:skill-ids sd)))
        (is (nil? (:skills sd)))
        (is (= [:session/register-skill] event-types))))))

;; ── Auto-retry and compaction config ───────────────────────────────────────

(deftest config-flags-test
  (testing "set-auto-retry-in! enables/disables retry"
    (let [[ctx session-id] (create-session-context)]
      (session/dispatch-in! ctx :session/set-auto-retry {:session-id session-id :enabled? false} {:origin :core})
      (is (false? (:auto-retry-enabled (ss/get-session-data-in ctx session-id))))))

  (testing "set-auto-compaction-in! enables/disables compaction"
    (let [[ctx session-id] (create-session-context)]
      (session/dispatch-in! ctx :session/set-auto-compaction {:session-id session-id :enabled? true} {:origin :core})
      (is (true? (:auto-compaction-enabled (ss/get-session-data-in ctx session-id)))))))

(deftest config-flags-dispatch-test
  (testing "set-auto-retry-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (kernel/clear-event-log!)
      (session/dispatch-in! ctx :session/set-auto-retry {:session-id session-id :enabled? false} {:origin :core})
      (let [entry (last (kernel/event-log-entries))]
        (is (= :session/set-auto-retry (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:enabled? false} (dissoc (:event-data entry) :session-id))))))

  (testing "set-auto-compaction-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (kernel/clear-event-log!)
      (session/dispatch-in! ctx :session/set-auto-compaction {:session-id session-id :enabled? true} {:origin :core})
      (let [entry (last (kernel/event-log-entries))]
        (is (= :session/set-auto-compaction (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:enabled? true} (dissoc (:event-data entry) :session-id))))))

  (testing "set-ui-type-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (kernel/clear-event-log!)
      (session/dispatch-in! ctx :session/set-ui-type {:session-id session-id :ui-type :emacs} {:origin :core})
      (is (= :emacs (:ui-type (ss/get-session-data-in ctx session-id))))
      (let [entry (last (kernel/event-log-entries))]
        (is (= :session/set-ui-type (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:ui-type :emacs} (dissoc (:event-data entry) :session-id)))))))

;; ── Manual compaction ───────────────────────────────────────────────────────

(deftest manual-compaction-test
  (testing "manual-compact-in! runs stub compaction and returns to idle"
    (let [[ctx session-id] (create-session-context)
          _                  (kernel/clear-event-log!)
          result     (session/manual-compact-in! ctx session-id nil)]
      (is (string? (:summary result)))
      (is (= :idle (ss/sc-phase-in ctx session-id)))
      (let [event-types (mapv :event-type (kernel/event-log-entries))]
        (is (= [:session/compact-start
                :session/append-journal-entry
                :session/compaction-finished
                :session/manual-compaction-execute
                :session/compact-done]
               event-types)))))

  (testing "manual-compact-in! with custom compaction-fn uses that fn"
    (kernel/clear-event-log!)
    (let [custom-fn  (fn [_sd _prep _instr]
                       {:summary "custom summary"
                        :first-kept-entry-id nil
                        :tokens-before nil
                        :details nil})
          [ctx session-id] (create-session-context {:compaction-fn custom-fn})
          result     (session/manual-compact-in! ctx session-id nil)]
      (is (= "custom summary" (:summary result)))
      (let [entry (first (filter #(= :session/manual-compaction-execute (:event-type %))
                                 (kernel/event-log-entries)))]
        (is (= :core (:origin entry)))
        (is (= {:custom-instructions nil} (dissoc (:event-data entry) :session-id))))))

  (testing "manual-compact-in! can be cancelled by extension"
    (let [[ctx session-id] (create-session-context)
          _                  (kernel/clear-event-log!)
          reg        (:extension-registry ctx)]
      (ext/register-extension-in! reg "/ext/c")
      (ext/register-handler-in! reg "/ext/c" "session_before_compact"
                                (fn [_] {:cancel true}))
      (let [result (session/manual-compact-in! ctx session-id nil)]
        (is (nil? result))
        (let [event-types (mapv :event-type (kernel/event-log-entries))]
          (is (= [:session/compact-start
                  :session/manual-compaction-execute
                  :session/compact-done]
                 event-types))))))

  (testing "extension can supply custom CompactionResult"
    (let [custom-result {:summary "ext summary"
                         :first-kept-entry-id nil
                         :tokens-before nil
                         :details nil}
          [ctx session-id] (create-session-context)
          _             (kernel/clear-event-log!)
          reg           (:extension-registry ctx)]
      (ext/register-extension-in! reg "/ext/c")
      (ext/register-handler-in! reg "/ext/c" "session_before_compact"
                                (fn [_] {:compaction custom-result}))
      (let [result (session/manual-compact-in! ctx session-id nil)]
        (is (= "ext summary" (:summary result)))
        (let [event-types (mapv :event-type (kernel/event-log-entries))]
          (is (contains?
               #{[:session/compact-start
                  :session/append-journal-entry
                  :session/compaction-finished
                  :session/manual-compaction-execute
                  :session/compact-done]
                 [:session/compact-start
                  :session/append-journal-entry
                  :session/compaction-finished
                  :session/manual-compaction-execute
                  :session/ui-clear-widget
                  :session/compact-done]}
               event-types)))))))

;; ── Extension dispatch ──────────────────────────────────────────────────────

(deftest extension-dispatch-test
  (testing "dispatch-extension-event-in! fires handlers"
    (let [[ctx _] (create-session-context)
          fired (atom false)]
      (ext/register-extension-in! (:extension-registry ctx) "/ext/a")
      (ext/register-handler-in! (:extension-registry ctx) "/ext/a" "my_event"
                                (fn [_] (reset! fired true) nil))
      (ext/dispatch-in (:extension-registry ctx) "my_event" {:x 1})
      (is (true? @fired)))))

;; ── Diagnostics ─────────────────────────────────────────────────────────────

(deftest diagnostics-test
  (testing "diagnostics-in returns all expected keys"
    (let [[ctx session-id] (create-session-context)
          d          (session/diagnostics-in ctx session-id)]
      (is (contains? d :phase))
      (is (contains? d :session-id))
      (is (contains? d :is-idle))
      (is (contains? d :is-streaming))
      (is (contains? d :is-compacting))
      (is (contains? d :is-retrying))
      (is (contains? d :model))
      (is (contains? d :thinking-level))
      (is (contains? d :pending-messages))
      (is (contains? d :retry-attempt))
      (is (contains? d :retry-deadline-ms))
      (is (contains? d :extension-count))
      (is (contains? d :journal-entries))
      (is (contains? d :agent-diagnostics))))

  (testing "diagnostics-in reflects session state"
    (let [[ctx session-id] (create-session-context)]
      (session/dispatch-in! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning false}} {:origin :core})
      (session/dispatch-in! ctx :session/set-session-name {:session-id session-id :name "test"} {:origin :core})
      (let [d  (session/diagnostics-in ctx session-id)
            sd (ss/get-session-data-in ctx session-id)]
        (is (= :idle (:phase d)))
        (is (true? (:is-idle d)))
        (is (= {:provider "x" :id "y" :reasoning false} (:model d)))
        (is (= "test" (:session-name sd)))
        (is (pos? (:journal-entries d)))))))
