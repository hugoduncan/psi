(ns psi.agent-session.config-compaction-test
  "Tests for session naming, config flags, context token tracking,
  manual compaction, extension dispatch, and diagnostics."
  (:require
   [psi.agent-session.test-support :as test-support]
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session :as session-data]
   [psi.agent-session.session-state :as ss]))
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
      (dispatch/dispatch! ctx :session/set-session-name {:session-id session-id :name "my session"} {:origin :core})
      (is (= "my session" (:session-name (ss/get-session-data-in ctx session-id))))
      (is (some #(= :session-info (:kind %)) (persist/all-entries-in ctx session-id))))))

(deftest session-config-dispatch-test
  (testing "set-session-name-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/clear-event-log!)
      (dispatch/dispatch! ctx :session/set-session-name {:session-id session-id :name "my session"} {:origin :core})
      (let [entry (last (dispatch/event-log-entries))
            sd    (ss/get-session-data-in ctx session-id)]
        (is (= "my session" (:session-name sd)))
        (is (= :session/set-session-name (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:name "my session"} (dissoc (:event-data entry) :session-id))))))

  (testing "set-worktree-path-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/clear-event-log!)
      (dispatch/dispatch! ctx :session/set-worktree-path {:session-id session-id :worktree-path "/repo/feature-z"} {:origin :core})
      (is (= "/repo/feature-z" (:worktree-path (ss/get-session-data-in ctx session-id))))
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-worktree-path (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:worktree-path "/repo/feature-z"} (dissoc (:event-data entry) :session-id))))))

  (testing "set-cache-breakpoints-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/clear-event-log!)
      (dispatch/dispatch! ctx :session/set-cache-breakpoints {:session-id session-id :breakpoints #{:system :tools}} {:origin :core})
      (is (= #{:system :tools} (:cache-breakpoints (ss/get-session-data-in ctx session-id))))
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-cache-breakpoints (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:breakpoints #{:system :tools}} (dissoc (:event-data entry) :session-id))))))

  (testing "set-active-tools-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)
          _                  (dispatch/clear-event-log!)
          tool-maps  [{:name "read"} {:name "bash"}]]
      (dispatch/dispatch! ctx :session/set-active-tools {:session-id session-id :tool-maps tool-maps} {:origin :core})
      (is (= #{"read" "bash"} (:active-tools (ss/get-session-data-in ctx session-id))))
      (is (= [{:name "read" :label "read" :description "" :parameters {:type "object" :properties {}} :lambda-description nil :source nil :ext-path nil :enabled? true}
              {:name "bash" :label "bash" :description "" :parameters {:type "object" :properties {}} :lambda-description nil :source nil :ext-path nil :enabled? true}]
             (:tool-defs (ss/get-session-data-in ctx session-id))))
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-active-tools (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:tool-maps tool-maps} (dissoc (:event-data entry) :session-id)))))))

;; ── Context token tracking ──────────────────────────────────────────────────

(deftest context-usage-test
  (testing "update-context-usage-in! stores tokens and window"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/dispatch! ctx :session/update-context-usage {:session-id session-id :tokens 5000 :window 100000} {:origin :core})
      (let [sd (ss/get-session-data-in ctx session-id)]
        (is (= 5000 (:context-tokens sd)))
        (is (= 100000 (:context-window sd))))))

  (testing "update-context-usage-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/clear-event-log!)
      (dispatch/dispatch! ctx :session/update-context-usage {:session-id session-id :tokens 5000 :window 100000} {:origin :core})
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/update-context-usage (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:tokens 5000 :window 100000} (dissoc (:event-data entry) :session-id))))))

  (testing "context fraction reflects stored usage"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/dispatch! ctx :session/update-context-usage {:session-id session-id :tokens 80000 :window 100000} {:origin :core})
      (let [sd (ss/get-session-data-in ctx session-id)]
        (is (= 0.8 (session-data/context-fraction-used sd))))))

  (testing "queued input helpers and bootstrap resource registration route through dispatch"
    (let [[ctx session-id] (create-session-context)
          _                  (dispatch/clear-event-log!)
          template   {:name "greet" :description "d" :content "c" :source :project :file-path "/tmp/greet.md"}
          skill      {:name "coding" :description "d" :file-path "/tmp/SKILL.md"
                      :base-dir "/tmp" :source :project :disable-model-invocation false}]
      (session/steer-in! ctx session-id "steer")
      (session/follow-up-in! ctx session-id "follow")
      (dispatch/dispatch! ctx :session/register-prompt-template {:session-id session-id :template template} {:origin :core})
      (dispatch/dispatch! ctx :session/register-skill {:session-id session-id :skill skill} {:origin :core})
      (session/consume-queued-input-text-in! ctx session-id)
      (let [sd         (ss/get-session-data-in ctx session-id)
            entries    (dispatch/event-log-entries)
            event-types (mapv :event-type entries)]
        (is (= [] (:steering-messages sd)))
        (is (= [] (:follow-up-messages sd)))
        (is (= 1 (count (:prompt-templates sd))))
        (is (= 1 (count (:skills sd))))
        (is (= [:session/enqueue-steering-message
                :session/enqueue-follow-up-message
                :session/register-prompt-template
                :session/register-skill
                :session/clear-queued-messages]
               event-types))))))

;; ── Auto-retry and compaction config ───────────────────────────────────────

(deftest config-flags-test
  (testing "set-auto-retry-in! enables/disables retry"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/dispatch! ctx :session/set-auto-retry {:session-id session-id :enabled? false} {:origin :core})
      (is (false? (:auto-retry-enabled (ss/get-session-data-in ctx session-id))))))

  (testing "set-auto-compaction-in! enables/disables compaction"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/dispatch! ctx :session/set-auto-compaction {:session-id session-id :enabled? true} {:origin :core})
      (is (true? (:auto-compaction-enabled (ss/get-session-data-in ctx session-id)))))))

(deftest config-flags-dispatch-test
  (testing "set-auto-retry-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/clear-event-log!)
      (dispatch/dispatch! ctx :session/set-auto-retry {:session-id session-id :enabled? false} {:origin :core})
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-auto-retry (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:enabled? false} (dissoc (:event-data entry) :session-id))))))

  (testing "set-auto-compaction-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/clear-event-log!)
      (dispatch/dispatch! ctx :session/set-auto-compaction {:session-id session-id :enabled? true} {:origin :core})
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-auto-compaction (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:enabled? true} (dissoc (:event-data entry) :session-id))))))

  (testing "set-ui-type-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/clear-event-log!)
      (dispatch/dispatch! ctx :session/set-ui-type {:session-id session-id :ui-type :emacs} {:origin :core})
      (is (= :emacs (:ui-type (ss/get-session-data-in ctx session-id))))
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-ui-type (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:ui-type :emacs} (dissoc (:event-data entry) :session-id)))))))

;; ── Manual compaction ───────────────────────────────────────────────────────

(deftest manual-compaction-test
  (testing "manual-compact-in! runs stub compaction and returns to idle"
    (let [[ctx session-id] (create-session-context)
          _                  (dispatch/clear-event-log!)
          result     (session/manual-compact-in! ctx session-id nil)]
      (is (string? (:summary result)))
      (is (= :idle (ss/sc-phase-in ctx session-id)))
      (let [event-types (mapv :event-type (dispatch/event-log-entries))]
        (is (= [:session/compact-start
                :session/compaction-finished
                :session/manual-compaction-execute
                :session/compact-done]
               event-types)))))

  (testing "manual-compact-in! with custom compaction-fn uses that fn"
    (dispatch/clear-event-log!)
    (let [custom-fn  (fn [_sd _prep _instr]
                       {:summary "custom summary"
                        :first-kept-entry-id nil
                        :tokens-before nil
                        :details nil})
          [ctx session-id] (create-session-context {:compaction-fn custom-fn})
          result     (session/manual-compact-in! ctx session-id nil)]
      (is (= "custom summary" (:summary result)))
      (let [entry (first (filter #(= :session/manual-compaction-execute (:event-type %))
                                 (dispatch/event-log-entries)))]
        (is (= :core (:origin entry)))
        (is (= {:custom-instructions nil} (dissoc (:event-data entry) :session-id))))))

  (testing "manual-compact-in! can be cancelled by extension"
    (let [[ctx session-id] (create-session-context)
          _                  (dispatch/clear-event-log!)
          reg        (:extension-registry ctx)]
      (ext/register-extension-in! reg "/ext/c")
      (ext/register-handler-in! reg "/ext/c" "session_before_compact"
                                (fn [_] {:cancel true}))
      (let [result (session/manual-compact-in! ctx session-id nil)]
        (is (nil? result))
        (let [event-types (mapv :event-type (dispatch/event-log-entries))]
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
          _             (dispatch/clear-event-log!)
          reg           (:extension-registry ctx)]
      (ext/register-extension-in! reg "/ext/c")
      (ext/register-handler-in! reg "/ext/c" "session_before_compact"
                                (fn [_] {:compaction custom-result}))
      (let [result (session/manual-compact-in! ctx session-id nil)]
        (is (= "ext summary" (:summary result)))
        (let [event-types (mapv :event-type (dispatch/event-log-entries))]
          (is (= [:session/compact-start
                  :session/compaction-finished
                  :session/manual-compaction-execute
                  :session/compact-done]
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
      (is (contains? d :extension-count))
      (is (contains? d :journal-entries))
      (is (contains? d :agent-diagnostics))))

  (testing "diagnostics-in reflects session state"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning false}} {:origin :core})
      (dispatch/dispatch! ctx :session/set-session-name {:session-id session-id :name "test"} {:origin :core})
      (let [d  (session/diagnostics-in ctx session-id)
            sd (ss/get-session-data-in ctx session-id)]
        (is (= :idle (:phase d)))
        (is (true? (:is-idle d)))
        (is (= {:provider "x" :id "y" :reasoning false} (:model d)))
        (is (= "test" (:session-name sd)))
        (is (pos? (:journal-entries d)))))))
