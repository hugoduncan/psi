(ns psi.agent-session.model-dispatch-test
  "Tests for model management, thinking level, and dispatch routing
  (prompt contributions, event log, projection setters)."
  (:require
   [clojure.edn :as edn]
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.bootstrap :as bootstrap]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.project-preferences :as project-prefs]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.statechart :as sc]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.test-support :as test-support])
  (:import
   (java.io File)))

(defn- retarget
  [ctx _sid-or-sd]
  ctx)
(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

;; ── Model management ────────────────────────────────────────────────────────

(deftest model-management-test
  (testing "set-model-in! updates model and persists entry"
    (let [[ctx session-id] (create-session-context)
          model      {:provider "anthropic" :id "claude-3-5-sonnet" :reasoning false}]
      (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model model} {:origin :core})
      (let [sd (ss/get-session-data-in ctx session-id)]
        (is (= model (:model sd))))
      (is (pos? (count (persist/all-entries-in ctx session-id))))))

  (testing "set-model-in! clamps thinking level for non-reasoning model"
    (let [[ctx session-id] (create-session-context {:session-defaults {:thinking-level :high}})]
      (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning false}} {:origin :core})
      (is (= :off (:thinking-level (ss/get-session-data-in ctx session-id))))))

  (testing "set-model-in! preserves thinking level for reasoning model"
    (let [[ctx session-id] (create-session-context {:session-defaults {:thinking-level :high}})]
      (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning true}} {:origin :core})
      (is (= :high (:thinking-level (ss/get-session-data-in ctx session-id)))))))

;; ── Thinking level ──────────────────────────────────────────────────────────

(deftest model-thinking-dispatch-test
  (testing "set-model-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)
          _                  (dispatch/clear-event-log!)
          model      {:provider "anthropic" :id "claude-3-5-sonnet" :reasoning false}]
      (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model model} {:origin :core})
      (let [entry (last (dispatch/event-log-entries))
            sd    (ss/get-session-data-in ctx session-id)]
        (is (= model (:model sd)))
        (is (= :session/set-model (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:model model} (dissoc (:event-data entry) :session-id))))))

  (testing "set-thinking-level-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/clear-event-log!)
      (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning true}} {:origin :core})
      (dispatch/clear-event-log!)
      (dispatch/dispatch! ctx :session/set-thinking-level {:session-id session-id :level :medium} {:origin :core})
      (let [entry (last (dispatch/event-log-entries))
            sd    (ss/get-session-data-in ctx session-id)]
        (is (= :medium (:thinking-level sd)))
        (is (= :session/set-thinking-level (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:level :medium} (dissoc (:event-data entry) :session-id))))))

  (testing "set-system-prompt-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/clear-event-log!)
      (dispatch/dispatch! ctx :session/set-system-prompt {:session-id session-id :prompt "graph-aware system prompt"} {:origin :core})
      (let [entry (last (dispatch/event-log-entries))
            sd    (ss/get-session-data-in ctx session-id)]
        (is (= "graph-aware system prompt" (:system-prompt sd)))
        (is (= :session/set-system-prompt (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:prompt "graph-aware system prompt"} (dissoc (:event-data entry) :session-id))))))

  (testing "refresh-system-prompt-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/clear-event-log!)
      (dispatch/dispatch! ctx :session/refresh-system-prompt {:session-id session-id} {:origin :core})
      (let [entry (last (dispatch/event-log-entries))
            sd    (ss/get-session-data-in ctx session-id)]
        (is (string? (:system-prompt sd)))
        (is (= :session/refresh-system-prompt (:event-type entry)))
        (is (= :core (:origin entry)))))))

(deftest prompt-contribution-dispatch-test
  (testing "register/update/unregister prompt contributions route through dispatch and preserve return payloads"
    (let [[ctx session-id] (create-session-context)
          _                  (dispatch/clear-event-log!)
          r1  (dispatch/dispatch! ctx :session/register-prompt-contribution
                                  {:session-id session-id :ext-path "/ext/a" :id "c1"
                                   :contribution {:section "Extension Capabilities"
                                                  :content "tool: x"
                                                  :priority 10}}
                                  {:origin :core})
          e1  (last (dispatch/event-log-entries))
          r2  (dispatch/dispatch! ctx :session/update-prompt-contribution
                                  {:session-id session-id :ext-path "/ext/a" :id "c1" :patch {:content "tool: y"}}
                                  {:origin :core})
          e2  (last (dispatch/event-log-entries))
          r3  (dispatch/dispatch! ctx :session/unregister-prompt-contribution
                                  {:session-id session-id :ext-path "/ext/a" :id "c1"}
                                  {:origin :core})
          e3  (last (dispatch/event-log-entries))]
      (is (true? (:registered? r1)))
      (is (= :session/register-prompt-contribution (:event-type e1)))
      (is (= :core (:origin e1)))

      (is (true? (:updated? r2)))
      (is (= "tool: y" (:content (:contribution r2))))
      (is (= :session/update-prompt-contribution (:event-type e2)))
      (is (= :core (:origin e2)))

      (is (true? (:removed? r3)))
      (is (= :session/unregister-prompt-contribution (:event-type e3)))
      (is (= :core (:origin e3))))))

(deftest dispatch-registry-and-event-log-eql-test
  (testing "query-in resolves registered dispatch handler metadata attrs"
    (let [[ctx _session-id] (create-session-context)
          result (session/query-in ctx
                                   [:psi.agent-session/registered-dispatch-event-count
                                    {:psi.agent-session/registered-dispatch-events
                                     [:psi.dispatch-handler/event-type]}])]
      (is (pos? (:psi.agent-session/registered-dispatch-event-count result)))
      (is (seq (:psi.agent-session/registered-dispatch-events result)))
      (is (some #(= :session/set-session-name
                    (:psi.dispatch-handler/event-type %))
                (:psi.agent-session/registered-dispatch-events result)))))

  (testing "query-in resolves dispatch event log attrs"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/clear-event-log!)
      (dispatch/dispatch! ctx :session/set-session-name {:session-id session-id :name "dispatch-visible"} {:origin :core})
      (let [result (session/query-in ctx
                                     [:psi.agent-session/dispatch-event-log-count
                                      {:psi.agent-session/dispatch-event-log
                                       [:psi.dispatch-event/event-type
                                        :psi.dispatch-event/origin
                                        :psi.dispatch-event/event-data
                                        :psi.dispatch-event/replaying?
                                        :psi.dispatch-event/statechart-claimed?
                                        :psi.dispatch-event/pure-result-kind
                                        :psi.dispatch-event/declared-effects
                                        :psi.dispatch-event/applied-effects
                                        :psi.dispatch-event/db-summary-before
                                        :psi.dispatch-event/db-summary-after]}])]
        (is (pos? (:psi.agent-session/dispatch-event-log-count result)))
        (let [entry (last (:psi.agent-session/dispatch-event-log result))]
          (is (= :session/set-session-name (:psi.dispatch-event/event-type entry)))
          (is (= :core (:psi.dispatch-event/origin entry)))
          (is (= {:name "dispatch-visible"} (dissoc (:psi.dispatch-event/event-data entry) :session-id)))
          (is (false? (:psi.dispatch-event/replaying? entry)))
          (is (false? (:psi.dispatch-event/statechart-claimed? entry)))
          (is (= :root-state-update (:psi.dispatch-event/pure-result-kind entry)))
          (is (= [{:effect/type :persist/journal-append-session-info-entry
                   :name "dispatch-visible"}
                  {:effect/type :projection/context-changed
                   :session-id session-id
                   :reason :session/set-session-name}]
                 (:psi.dispatch-event/declared-effects entry)))
          (is (= [{:effect/type :persist/journal-append-session-info-entry
                   :name "dispatch-visible"}
                  {:effect/type :projection/context-changed
                   :session-id session-id
                   :reason :session/set-session-name}]
                 (:psi.dispatch-event/applied-effects entry)))
          (is (map? (:psi.dispatch-event/db-summary-before entry)))
          (is (map? (:psi.dispatch-event/db-summary-after entry)))))))

  (testing "replay-dispatch-event-log-in! replays retained entries against session state"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/clear-event-log!)
      (dispatch/dispatch! ctx :session/set-session-name {:session-id session-id :name "replay me"} {:origin :core})
      (dispatch/dispatch! ctx :session/set-worktree-path {:session-id session-id :worktree-path "/repo/replay"} {:origin :core})
      (is (= "replay me" (:session-name (ss/get-session-data-in ctx session-id))))
      (is (= "/repo/replay" (:worktree-path (ss/get-session-data-in ctx session-id))))
      (ss/apply-root-state-update-in! ctx (ss/session-update session-id #(assoc % :session-name "before" :worktree-path "/repo/main")))
      (let [state (session/replay-dispatch-event-log-in! ctx)
            sd    (get-in state [:agent-session :sessions session-id :data])]
        (is (= "replay me" (:session-name sd)))
        (is (= "/repo/replay" (:worktree-path sd))))))

  (testing "session ids are explicit when reading sibling sessions"
    (let [[ctx _session-id] (create-session-context)
          sd1               (session/new-session-in! ctx nil {})
          first-id           (:session-id sd1)
          sd2                (session/new-session-in! ctx first-id {})
          second-id          (:session-id sd2)]
      (is (= first-id (:session-id (ss/get-session-data-in ctx first-id))))
      (is (= second-id (:session-id (ss/get-session-data-in ctx second-id))))))

  (testing "new-session-in! initialization is logged through dispatch"
    (let [[ctx session-id] (create-session-context {:persist? false})
          old-id           session-id
          _                (dispatch/clear-event-log!)
          sd               (session/new-session-in! ctx nil {:session-name "dispatch-new"
                                                             :worktree-path "/repo/dispatch-new"})
          session-id       (:session-id sd)
          ctx              (retarget ctx sd)
          entry            (first (filter #(= :session/new-initialize (:event-type %))
                                          (dispatch/event-log-entries)))]
      (is (some? entry))
      (is (not= old-id session-id))
      (is (= session-id (:session-id (ss/get-session-data-in ctx session-id))))
      (is (= :core (:origin entry)))
      (is (= "dispatch-new" (get-in entry [:event-data :session-name])))
      (is (= "/repo/dispatch-new" (get-in entry [:event-data :worktree-path]))))))

(testing "resume-session-in! loaded state is logged through dispatch"
  (let [[ctx session-id] (create-session-context {:session-defaults {:model {:provider "openai"
                                                                             :id "gpt-5.3-codex"
                                                                             :reasoning true}}
                                                  :persist? false})
        _   (dispatch/clear-event-log!)
        f   (File/createTempFile "psi-resume-dispatch" ".ndedn")]
    (.deleteOnExit f)
    (spit f (str "{:type :session :version 4 :id \"sess-dispatch\" :timestamp #inst \"2024-01-01T00:00:00Z\" :cwd \"/legacy/cwd\" :worktree-path \"/repo/resume-dispatch\"}\n"
                 "{:id \"e1\" :parent-id nil :timestamp #inst \"2024-01-01T00:00:01Z\" :kind :thinking-level :data {:thinking-level :medium}}\n"
                 "{:id \"e2\" :parent-id \"e1\" :timestamp #inst \"2024-01-01T00:00:02Z\" :kind :session-info :data {:name \"Resume Dispatch\"}}\n"))
    (let [sd                 (session/resume-session-in! ctx session-id (.getAbsolutePath f))
          session-id         (:session-id sd)
          ctx                (retarget ctx sd)
          entry              (first (filter #(= :session/resume-loaded (:event-type %))
                                            (dispatch/event-log-entries)))]
      (is (= "Resume Dispatch" (:session-name (ss/get-session-data-in ctx session-id))))
      (is (= :core (:origin entry)))
      (is (= "sess-dispatch" (get-in entry [:event-data :session-id])))
      (is (= "/repo/resume-dispatch" (get-in entry [:event-data :worktree-path])))
      (is (= :medium (get-in entry [:event-data :thinking-level]))))))

(testing "fork-session-in! initialization is logged through dispatch"
  (let [[ctx _session-id] (create-session-context {:persist? false})
        _                 (dispatch/clear-event-log!)
        parent-sd          (session/new-session-in! ctx nil {})
        parent-id          (:session-id parent-sd)
        entry-id  (:id (ss/journal-append-in! ctx parent-id (persist/message-entry {:role "user"
                                                                                    :content [{:type :text :text "fork-dispatch"}]
                                                                                    :timestamp (java.time.Instant/now)})))]
    (session/fork-session-in! ctx parent-id entry-id)
    (let [entry (first (filter #(= :session/fork-initialize (:event-type %))
                               (dispatch/event-log-entries)))]
      (is (= :core (:origin entry)))
      (is (= parent-id (get-in entry [:event-data :parent-session-id])))
      (is (= entry-id (get-in entry [:event-data :entry-id])))
      (is (pos? (get-in entry [:event-data :entry-count]))))))

(deftest session-update-helper-consolidation-test
  (testing "session-update wrapper composes with apply-root-state-update-in! to update session data and context index"
    (let [[ctx session-id] (create-session-context)]
      (ss/apply-root-state-update-in! ctx (ss/session-update session-id #(assoc % :session-name "helper-name")))
      (let [sd  (ss/get-session-data-in ctx session-id)
            sid (:session-id sd)]
        (is (= "helper-name" (:session-name sd)))
        (is (= "helper-name" (:session-name (ss/get-session-data-in ctx sid))))
        (is (= sid (:session-id (ss/get-session-data-in ctx session-id))))))))

(deftest projection-and-transition-helper-dispatch-test
  (testing "projection setters still route through dispatch after transition-helper extraction"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/clear-event-log!)
      (dispatch/dispatch! ctx :session/set-rpc-trace {:enabled? true :file "/tmp/rpc-trace.ndedn"} {:origin :core})
      (sa/set-nrepl-runtime-in! ctx session-id {:host "localhost" :port 5555 :endpoint "localhost:5555"})
      (sa/set-oauth-projection-in! ctx {:authenticated-providers ["anthropic"]})
      (sa/set-recursion-state-in! ctx session-id {:status :idle})
      (let [state  @(:state* ctx)
            events (mapv :event-type (dispatch/event-log-entries))]
        (is (= {:enabled? true :file "/tmp/rpc-trace.ndedn"}
               (get-in state [:runtime :rpc-trace])))
        (is (= {:host "localhost" :port 5555 :endpoint "localhost:5555"}
               (get-in state [:runtime :nrepl])))
        (is (= {:authenticated-providers ["anthropic"]}
               (get-in state [:oauth])))
        (is (= {:status :idle}
               (get-in state [:recursion])))
        (is (= [:session/set-rpc-trace
                :session/set-nrepl-runtime
                :session/set-oauth-projection
                :session/set-recursion-state]
               events)))))

  (testing "telemetry setters now route through dispatch too"
    (let [[ctx session-id] (create-session-context)
          _                  (dispatch/clear-event-log!)
          stat       {:tool-name "bash" :context-bytes-added 12}
          _          (sa/set-turn-context-in! ctx session-id {:turn-id "t-1"})
          _          (sa/append-tool-call-attempt-in! ctx session-id {:id "tc-1" :name "read"})
          _          (sa/append-provider-request-capture-in! ctx session-id {:provider "anthropic" :turn-id "t-1"})
          _          (sa/append-provider-reply-capture-in! ctx session-id {:provider "anthropic" :turn-id "t-1" :event {:type :done}})
          _          (sa/record-tool-output-stat-in! ctx session-id stat 12 false)
          state      @(:state* ctx)
          events     (mapv :event-type (dispatch/event-log-entries))]
      (let [sid session-id]
        (is (= {:turn-id "t-1"} (get-in state [:agent-session :sessions sid :turn :ctx])))
        (is (= "tc-1" (get-in state [:agent-session :sessions sid :telemetry :tool-call-attempts 0 :id])))
        (is (= "anthropic" (get-in state [:agent-session :sessions sid :telemetry :provider-requests 0 :provider])))
        (is (= "anthropic" (get-in state [:agent-session :sessions sid :telemetry :provider-replies 0 :provider])))
        (is (= [stat] (get-in state [:agent-session :sessions sid :telemetry :tool-output-stats :calls]))))
      (is (= [:session/set-turn-context
              :session/append-tool-call-attempt
              :session/append-provider-request-capture
              :session/append-provider-reply-capture
              :session/record-tool-output-stat]
             events)))))

(deftest interrupt-and-bootstrap-prompt-dispatch-test
  (testing "request-interrupt-in! routes session-data changes through dispatch"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/clear-event-log!)
      (test-support/update-state! ctx :session-data assoc
                                  :interrupt-pending false
                                  :steering-messages ["queued steer"])
      ;; Force streaming deterministically so interrupt behavior does not depend
      ;; on prompt runtime timing.
      (sc/send-event! (:sc-env ctx)
                      (ss/sc-session-id-in ctx session-id)
                      :session/prompt
                      {:ctx ctx :session-id session-id})
      (session/request-interrupt-in! ctx session-id)
      (let [sd    (ss/get-session-data-in ctx session-id)
            entry (last (dispatch/event-log-entries))]
        (is (true? (:interrupt-pending sd)))
        (is (= [] (:steering-messages sd)))
        (is (instance? java.time.Instant (:interrupt-requested-at sd)))
        (is (= :session/request-interrupt (:event-type entry)))
        (is (= :core (:origin entry))))))

  (testing "bootstrap-in! prompt metadata initialization is logged through dispatch"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/clear-event-log!)
      (bootstrap/bootstrap-in!
       ctx session-id
       {:register-global-query? false
        :system-prompt          "sys"
        :developer-prompt       "dev"
        :developer-prompt-source :explicit})
      (let [sd    (ss/get-session-data-in ctx session-id)
            entry (first (filter #(= :session/bootstrap-prompt-state (:event-type %))
                                 (dispatch/event-log-entries)))]
        (is (= "sys" (:base-system-prompt sd)))
        (is (= "sys" (:system-prompt sd)))
        (is (= "dev" (:developer-prompt sd)))
        (is (= :explicit (:developer-prompt-source sd)))
        (is (= :core (:origin entry)))
        (is (= {:system-prompt "sys"
                :developer-prompt "dev"
                :developer-prompt-source :explicit}
               (dissoc (:event-data entry) :session-id))))))

  (testing "bootstrap-in! leaves developer layer unset when none is provided"
    (let [[ctx session-id] (create-session-context)]
      (bootstrap/bootstrap-in!
       ctx session-id
       {:register-global-query? false
        :system-prompt          "sys"})
      (let [sd (ss/get-session-data-in ctx session-id)]
        (is (= "sys" (:base-system-prompt sd)))
        (is (nil? (:developer-prompt sd)))
        (is (nil? (:developer-prompt-source sd)))))))

(deftest thinking-level-test
  (testing "set-thinking-level-in! updates level"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning true}} {:origin :core})
      (dispatch/dispatch! ctx :session/set-thinking-level {:session-id session-id :level :medium} {:origin :core})
      (is (= :medium (:thinking-level (ss/get-session-data-in ctx session-id))))))

  (testing "set-model-in! persists project preferences to the local project layer"
    (let [cwd        (str (System/getProperty "java.io.tmpdir") "/psi-project-prefs-" (java.util.UUID/randomUUID))
          _          (.mkdirs (java.io.File. cwd))
          shared-f   (project-prefs/project-preferences-file cwd)
          local-f    (project-prefs/project-local-preferences-file cwd)
          _          (.mkdirs (.getParentFile shared-f))
          _          (spit shared-f (pr-str {:version 1
                                             :agent-session {:prompt-mode :prose}}))
          [ctx session-id] (create-session-context {:cwd cwd})
          model      {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}]
      (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model model} {:origin :core})
      (let [prefs (project-prefs/read-preferences cwd)]
        (is (= "anthropic" (get-in prefs [:agent-session :model-provider])))
        (is (= "claude-sonnet-4-6" (get-in prefs [:agent-session :model-id])))
        (is (= :off (get-in prefs [:agent-session :thinking-level])))
        (is (= :prose (get-in prefs [:agent-session :prompt-mode]))))
      (is (= {:version 1
              :agent-session {:prompt-mode :prose}}
             (edn/read-string (slurp shared-f))))
      (is (.exists local-f))
      (is (= "anthropic" (get-in (edn/read-string (slurp local-f)) [:agent-session :model-provider])))))

  (testing "set-thinking-level-in! persists project preferences to the local project layer"
    (let [cwd      (str (System/getProperty "java.io.tmpdir") "/psi-project-prefs-" (java.util.UUID/randomUUID))
          _        (.mkdirs (java.io.File. cwd))
          shared-f (project-prefs/project-preferences-file cwd)
          local-f  (project-prefs/project-local-preferences-file cwd)
          _        (.mkdirs (.getParentFile shared-f))
          _        (spit shared-f (pr-str {:version 1
                                           :agent-session {:prompt-mode :prose}}))
          [ctx session-id] (create-session-context {:cwd cwd})]
      (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning true}} {:origin :core})
      (dispatch/dispatch! ctx :session/set-thinking-level {:session-id session-id :level :high} {:origin :core})
      (let [prefs (project-prefs/read-preferences cwd)]
        (is (= :high (get-in prefs [:agent-session :thinking-level])))
        (is (= :prose (get-in prefs [:agent-session :prompt-mode]))))
      (is (= {:version 1
              :agent-session {:prompt-mode :prose}}
             (edn/read-string (slurp shared-f))))
      (is (= :high (get-in (edn/read-string (slurp local-f)) [:agent-session :thinking-level])))))

  (testing "cycle-thinking-level-in! advances level for reasoning model"
    (let [[ctx session-id] (create-session-context {:session-defaults {:thinking-level :off}})]
      (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning true}} {:origin :core})
      (session/cycle-thinking-level-in! ctx session-id)
      (is (= :minimal (:thinking-level (ss/get-session-data-in ctx session-id))))))

  (testing "cycle-thinking-level-in! is no-op for non-reasoning model"
    (let [[ctx session-id] (create-session-context {:session-defaults {:thinking-level :off}})]
      (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning false}} {:origin :core})
      (session/cycle-thinking-level-in! ctx session-id)
      (is (= :off (:thinking-level (ss/get-session-data-in ctx session-id)))))))
