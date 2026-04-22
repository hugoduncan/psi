(ns psi.agent-session.session-lifecycle-test
  "Tests for context creation, isolation, and session lifecycle
  (new / fork / resume / index)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-runtime]
   [psi.agent-session.session-state :as ss]
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

;; ── Context creation and isolation ─────────────────────────────────────────

(deftest context-isolation-test
  (testing "two contexts are independent"
    (let [[ctx-a sid-a] (create-session-context)
          [ctx-b sid-b] (create-session-context)]
      (dispatch/dispatch! ctx-a :session/set-session-name {:session-id sid-a :name "alpha"} {:origin :core})
      (is (= "alpha" (:session-name (ss/get-session-data-in ctx-a sid-a))))
      (is (nil? (:session-name (ss/get-session-data-in ctx-b sid-b))))))

  (testing "create-context starts in :idle phase"
    (let [[ctx sid] (create-session-context)]
      (is (= :idle (ss/sc-phase-in ctx sid)))
      (is (ss/idle-in? ctx sid))))

  (testing "session lifecycle events can be routed through dispatch statechart boundary"
    (let [[ctx _session-id] (create-session-context {:persist? false})
          _                 (dispatch/clear-event-log!)
          sd                (session/new-session-in! ctx nil {})
          session-id        (:session-id sd)
          ctx               (retarget ctx sd)]
      (is (= :idle (ss/sc-phase-in ctx session-id)))
      (with-redefs [psi.agent-session.prompt-runtime/execute-prepared-request!
                    (fn [_ai-ctx _ctx sid prepared _progress-queue]
                      {:execution-result/turn-id (:prepared-request/id prepared)
                       :execution-result/session-id sid
                       :execution-result/assistant-message {:role "assistant"
                                                            :content [{:type :text :text "ok"}]
                                                            :stop-reason :stop
                                                            :timestamp (java.time.Instant/now)}
                       :execution-result/turn-outcome :turn.outcome/stop
                       :execution-result/tool-calls []
                       :execution-result/stop-reason :stop})]
        (session/prompt-in! ctx session-id "hello"))
      (is (= :idle (ss/sc-phase-in ctx session-id)))
      (let [entries   (dispatch/event-log-entries)
            submit-e  (first (filter #(= :session/prompt-submit (:event-type %)) entries))
            prompt-e  (first (filter #(= :session/prompt (:event-type %)) entries))
            prepare-e (first (filter #(= :session/prompt-prepare-request (:event-type %)) entries))
            record-e  (first (filter #(= :session/prompt-record-response (:event-type %)) entries))
            user-msg  (:user-msg (:event-data submit-e))]
        (is (= :core (:origin submit-e)))
        (is (= :core (:origin prompt-e)))
        (is (= :core (:origin prepare-e)))
        (is (= :core (:origin record-e)))
        (is (= "user" (:role user-msg)))
        (is (= [{:type :text :text "hello"}] (:content user-msg)))
        (is (instance? java.time.Instant (:timestamp user-msg))))))

  (testing "statechart action handlers use pure session-update results"
    (let [[ctx _session-id] (create-session-context {:persist? false})
          _                 (dispatch/clear-event-log!)
          sd                (session/new-session-in! ctx nil {})
          session-id        (:session-id sd)
          ctx               (retarget ctx sd)]
      (is (false? (:is-streaming (ss/get-session-data-in ctx session-id))))
      (dispatch/dispatch! ctx :on-streaming-entered {:session-id session-id} {:origin :statechart})
      (let [sd    (ss/get-session-data-in ctx session-id)
            entry (last (dispatch/event-log-entries))]
        (is (true? (:is-streaming sd)))
        (is (= :root-state-update (:pure-result-kind entry)))))))

;; ── Session lifecycle ───────────────────────────────────────────────────────

(deftest context-index-registry-test
  (testing "create-context starts with an empty context session index"
    (let [[ctx session-id] (create-session-context)]
      (is (some? (:session-id (ss/get-session-data-in ctx session-id))))
      (is (seq (ss/get-sessions-map-in ctx)))))

  (testing "new-session-in! registers a new session alongside existing ones"
    (let [[ctx session-id]       (create-session-context)
          sd-after           (session/new-session-in! ctx nil {})
          ctx                (retarget ctx sd-after)
          sid-after          (:session-id sd-after)
          session-keys       (set (keys (ss/get-sessions-map-in ctx)))]
      (is (not= session-id sid-after))
      (is (= sid-after (:session-id (ss/get-session-data-in ctx sid-after))))
      (is (contains? session-keys sid-after))
      (is (contains? session-keys session-id))))

  (testing "ensure-session-loaded-in! resumes by context session id"
    (let [cwd                (str (System/getProperty "java.io.tmpdir") "/psi-context-load-" (java.util.UUID/randomUUID))
          _                  (.mkdirs (java.io.File. cwd))
          [ctx _session-id] (create-session-context {:cwd cwd})
          sd1                (session/new-session-in! ctx nil {})
          sid1               (:session-id sd1)
          path1              (:session-file sd1)
          _                  (persist/flush-journal! (java.io.File. path1)
                                                     sid1
                                                     cwd
                                                     nil
                                                     nil
                                                     [(persist/thinking-level-entry :off)])
          sd2                (session/new-session-in! (retarget ctx sd1) sid1 {})
          sid2               (:session-id sd2)
          sd1*               (session/ensure-session-loaded-in! (retarget ctx sd2) sid2 sid1)
          ctx                (retarget ctx sd1*)]
      (is (not= sid1 sid2))
      (is (= sid1 (:session-id (ss/get-session-data-in ctx sid1))))
      (is (= sid1 (:session-id (ss/get-session-data-in ctx sid1))))))

  (testing "explicit session-id selects the parent when creating another session"
    (let [[ctx session-id] (create-session-context)
          sd1           (session/new-session-in! ctx nil {})
          first-id      (:session-id sd1)
          sd2           (session/new-session-in! ctx first-id {})
          second-id     (:session-id sd2)]
      (is (not= session-id first-id))
      (is (not= first-id second-id))))

  (testing "list-context-sessions-in ignores malformed placeholder slots"
    (let [[ctx session-id] (create-session-context)]
      (swap! (:state* ctx) assoc-in [:agent-session :sessions nil :turn] {:ctx nil})
      (swap! (:state* ctx) assoc-in [:agent-session :sessions "placeholder-only" :telemetry] {})
      (let [listed (ss/list-context-sessions-in ctx)]
        (is (= 1 (count listed)))
        (is (= session-id (:session-id (first listed)))))))

  (testing "list-context-sessions-in carries canonical inferred display-name"
    (let [[ctx session-id] (create-session-context)]
      (ss/journal-append-in! ctx session-id
                             (persist/message-entry {:role "user"
                                                     :content [{:type :text :text "Investigate failing tests"}]
                                                     :timestamp (java.time.Instant/parse "2026-03-16T10:47:00Z")}))
      (let [listed (ss/list-context-sessions-in ctx)
            slot   (first (filter #(= session-id (:session-id %)) listed))]
        (is (= "Investigate failing tests" (:display-name slot))))))

  (testing "new-session-in! accepts explicit worktree-path and session-name and keeps prior context peer"
    (let [[ctx _session-id] (create-session-context {:cwd "/repo/main"
                                                     :session-defaults {:worktree-path "/repo/main"}})
          sd-1               (session/new-session-in! ctx nil {:session-name "main"
                                                               :worktree-path "/repo/main"})
          ctx-1              (retarget ctx sd-1)
          sid-1              (:session-id sd-1)
          created-1          (:created-at (ss/get-session-data-in ctx-1 sid-1))]
      (Thread/sleep 5)
      (let [sd-2      (session/new-session-in! ctx-1 sid-1 {:session-name "feature work"
                                                            :worktree-path "/repo/feature-work"})
            ctx-2     (retarget ctx sd-2)
            sid-2     (:session-id sd-2)
            sd        (ss/get-session-data-in ctx-2 sid-2)
            sessions  (ss/get-sessions-map-in ctx-2)
            created-2 (:created-at (ss/get-session-data-in ctx-2 sid-2))]
        (is (= "feature work" (:session-name sd)))
        (is (= "/repo/feature-work" (:worktree-path sd)))
        (is (= sid-2 (:session-id (ss/get-session-data-in ctx-2 sid-2))))
        (is (contains? (set (keys sessions)) sid-1))
        (is (contains? (set (keys sessions)) sid-2))
        (is (= "/repo/main" (:worktree-path (ss/get-session-data-in ctx-2 sid-1))))
        (is (instance? java.time.Instant created-1))
        (is (instance? java.time.Instant created-2))
        (is (= created-1 (:created-at (ss/get-session-data-in ctx-2 sid-1))) "existing session keeps original created-at")
        (is (not= created-1 created-2) "new session gets distinct created-at")))))

(deftest new-session-test
  (testing "new-session-in! resets session-id"
    (let [[ctx session-id] (create-session-context)
          old-id          session-id
          sd              (session/new-session-in! ctx nil {})
          new-id          (:session-id sd)]
      (is (not= old-id new-id))))

  (testing "new-session-in! clears queues"
    (let [[ctx session-id]       (create-session-context)]
      (session/steer-in! ctx session-id "steer me")
      (let [sd         (session/new-session-in! ctx nil {})
            session-id (:session-id sd)
            ctx        (retarget ctx sd)
            sd         (ss/get-session-data-in ctx session-id)]
        (is (= [] (:steering-messages sd))))))

  (testing "new-session-in! rebuilds a schema-safe runtime from a clean session baseline"
    (let [[ctx session-id] (create-session-context)
          _                (swap! (:state* ctx) update-in [:agent-session :sessions session-id :data]
                                  assoc
                                  :messages [{:role "assistant" :content "runtime-only"}]
                                  :steering-mode :all
                                  :follow-up-mode :all
                                  :stream-message {:role "assistant" :content "partial"}
                                  :pending-tool-calls #{"call-1"}
                                  :error "boom")
          sd               (session/new-session-in! ctx session-id {})
          new-id           (:session-id sd)
          new-agent        (ss/agent-ctx-in ctx new-id)
          agent-data       ((resolve 'psi.agent-core.core/get-data-in) new-agent)
          new-session      (ss/get-session-data-in ctx new-id)]
      (is (= [] (:messages agent-data)))
      (is (= :one-at-a-time (:steering-mode agent-data)))
      (is (= :one-at-a-time (:follow-up-mode agent-data)))
      (is (nil? (:stream-message agent-data)))
      (is (= #{} (:pending-tool-calls agent-data)))
      (is (nil? (:error agent-data)))
      (is (= [] (:steering-messages new-session)))
      (is (= [] (:follow-up-messages new-session)))))

  (testing "new-session-in! is cancelled when extension returns cancel"
    (let [[ctx session-id] (create-session-context)
          reg (:extension-registry ctx)]
      (ext/register-extension-in! reg "/ext/cancel")
      (ext/register-handler-in! reg "/ext/cancel" "session_before_switch"
                                (fn [_] {:cancel true}))
      (session/new-session-in! ctx nil {})
      (is (= session-id (:session-id (ss/get-session-data-in ctx session-id))))))

  (testing "new-session-in! appends active model entry when model exists"
    (let [model              {:provider "anthropic" :id "claude-3-5-sonnet" :reasoning false}
          [ctx _session-id] (create-session-context {:session-defaults {:model model}})
          sd                 (session/new-session-in! ctx nil {})
          session-id         (:session-id sd)
          ctx                (retarget ctx sd)]
      (is (some #(and (= :model (:kind %))
                      (= "anthropic" (get-in % [:data :provider]))
                      (= "claude-3-5-sonnet" (get-in % [:data :model-id])))
                (persist/all-entries-in ctx session-id)))))

  (testing "fork-session-in! rebuilds schema-safe session data while preserving fork lineage and config"
    (let [model            {:provider "anthropic" :id "claude-3-5-sonnet" :reasoning false}
          [ctx session-id] (create-session-context {:session-defaults {:model model
                                                                       :thinking-level :high
                                                                       :prompt-mode :prose}})
          _                (swap! (:state* ctx) update-in [:agent-session :sessions session-id :data]
                                  assoc
                                  :messages [{:role "assistant" :content "runtime-only"}]
                                  :stream-message {:role "assistant" :content "partial"}
                                  :pending-tool-calls #{"call-1"}
                                  :error "boom")
          entry-id         (:id (first (persist/all-entries-in ctx session-id)))
          forked           (session/fork-session-in! ctx session-id entry-id)
          fork-id          (:session-id forked)
          fork-agent       (ss/agent-ctx-in ctx fork-id)
          agent-data       ((resolve 'psi.agent-core.core/get-data-in) fork-agent)
          fork-session     (ss/get-session-data-in ctx fork-id)]
      (is (= session-id (:parent-session-id fork-session)))
      (is (= (:session-file (ss/get-session-data-in ctx session-id))
             (:parent-session-path fork-session)))
      (is (= model (:model fork-session)))
      (is (= :high (:thinking-level fork-session)))
      (is (= :prose (:prompt-mode fork-session)))
      (is (= [] (:messages agent-data)))
      (is (nil? (:stream-message agent-data)))
      (is (= #{} (:pending-tool-calls agent-data)))
      (is (nil? (:error agent-data))))))

(deftest fork-session-persists-child-file-with-parent-lineage-test
  (let [cwd                (str (System/getProperty "java.io.tmpdir") "/psi-fork-" (java.util.UUID/randomUUID))
        _                  (.mkdirs (java.io.File. cwd))
        [ctx _session-id] (create-session-context {:cwd cwd})
        parent-sd          (session/new-session-in! ctx nil {})
        parent-id          (:session-id parent-sd)
        ctx                (retarget ctx parent-sd)
        parent-file        (:session-file parent-sd)
        entry-id           (:id (ss/journal-append-in! ctx parent-id (persist/message-entry {:role "user"
                                                                                             :content [{:type :text :text "branch-here"}]
                                                                                             :timestamp (java.time.Instant/now)})))
        _                  (ss/journal-append-in! ctx parent-id (persist/message-entry {:role "assistant"
                                                                                        :content [{:type :text :text "reply-here"}]
                                                                                        :timestamp (java.time.Instant/now)}))
        child-sd           (session/fork-session-in! ctx parent-id entry-id)
        child-id           (:session-id child-sd)
        ctx                (retarget ctx child-sd)
        child-sd           (ss/get-session-data-in ctx child-id)
        child-file         (:session-file child-sd)
        loaded-child       (persist/load-session-file child-file)]
    (is (string? child-file))
    (is (.exists (java.io.File. child-file)))
    (is (= parent-file (get-in loaded-child [:header :parent-session])))
    (is (= parent-id (get-in loaded-child [:header :parent-session-id])))
    (is (= child-id (get-in loaded-child [:header :id])))
    (is (= (count (persist/all-entries-in ctx child-id)) (count (:entries loaded-child))))
    (is (= ["user" "assistant"]
           (->> (:entries loaded-child)
                (filter #(= :message (:kind %)))
                (mapv #(get-in % [:data :message :role])))))))

(deftest fork-session-includes-selected-user-reply-turn-test
  (let [[ctx _session-id] (test-support/make-session-ctx {})
        parent-sd        (session/new-session-in! ctx nil {})
        parent-id        (:session-id parent-sd)
        _                (dispatch/dispatch! ctx :session/update-context-usage {:session-id parent-id :tokens 22000 :window 200000} {:origin :core})
        ctx              (retarget ctx parent-sd)
        user-entry       (persist/message-entry {:role "user"
                                                 :content [{:type :text :text "branch-here"}]
                                                 :timestamp (java.time.Instant/now)})
        _                (ss/journal-append-in! ctx parent-id user-entry)
        _                (ss/journal-append-in! ctx parent-id
                                                (persist/message-entry {:role "assistant"
                                                                        :content [{:type :text :text "reply-here"}]
                                                                        :timestamp (java.time.Instant/now)}))
        _                (ss/journal-append-in! ctx parent-id
                                                (persist/message-entry {:role "user"
                                                                        :content [{:type :text :text "later-turn"}]
                                                                        :timestamp (java.time.Instant/now)}))
        child-sd         (session/fork-session-in! ctx parent-id (:id user-entry))
        child-id         (:session-id child-sd)
        ctx              (retarget ctx child-sd)
        entries          (persist/all-entries-in ctx child-id)
        child-state      (ss/get-session-data-in ctx child-id)
        fork-query       (session/query-in ctx child-id [:psi.agent-session/model-provider
                                                         :psi.agent-session/model-id
                                                         :psi.agent-session/context-window
                                                         :psi.agent-session/context-fraction])
        message-entries  (filterv #(= :message (:kind %)) entries)]
    (is (= ["user" "assistant"]
           (mapv #(get-in % [:data :message :role]) message-entries)))
    (is (= ["branch-here" "reply-here"]
           (mapv #(-> % :data :message :content first :text) message-entries)))
    (is (= 22000 (:context-tokens child-state)))
    (is (= 200000 (:context-window child-state)))
    (is (= 0.11 (:psi.agent-session/context-fraction fork-query)))))

(deftest resume-session-model-fallback-test
  (testing "resume-session-in! keeps current model when resumed journal has no model entry"
    (let [initial-model {:provider "openai" :id "gpt-5.3-codex" :reasoning true}
          [ctx session-id] (create-session-context {:session-defaults {:model initial-model
                                                                       :thinking-level :high}})
          f             (File/createTempFile "psi-resume-no-model" ".ndedn")
          entries       [(persist/thinking-level-entry :minimal)
                         (persist/message-entry {:role "user"
                                                 :content [{:type :text :text "hello"}]
                                                 :timestamp (java.time.Instant/now)})]]
      (.deleteOnExit f)
      (persist/flush-journal! f "sess-no-model" "/tmp/project" nil entries)
      (let [sd                 (session/resume-session-in! ctx session-id (.getAbsolutePath f))
            resumed-id         (:session-id sd)
            ctx                (retarget ctx sd)
            sd                 (ss/get-session-data-in ctx resumed-id)
            r                  (session/query-in ctx resumed-id [:psi.agent-session/model-provider
                                                                 :psi.agent-session/model-id
                                                                 :psi.agent-session/thinking-level])]
        (is (= initial-model (:model sd)))
        (is (= :minimal (:thinking-level sd)))
        (is (= "openai" (:psi.agent-session/model-provider r)))
        (is (= "gpt-5.3-codex" (:psi.agent-session/model-id r)))
        (is (= :minimal (:psi.agent-session/thinking-level r))))))

  (testing "resume-session-in! preserves context usage baseline for footer/query projections"
    (let [initial-model {:provider "openai" :id "gpt-5.4" :reasoning false}
          [ctx session-id] (create-session-context {:session-defaults {:model initial-model}})
          _                (dispatch/dispatch! ctx :session/update-context-usage {:session-id session-id :tokens 22000 :window 200000} {:origin :core})
          f                (File/createTempFile "psi-resume-context" ".ndedn")]
      (.deleteOnExit f)
      (persist/flush-journal! f "sess-resume-context" "/tmp/project" nil [])
      (let [sd         (session/resume-session-in! ctx session-id (.getAbsolutePath f))
            resumed-id (:session-id sd)
            ctx        (retarget ctx sd)
            result     (session/query-in ctx resumed-id [:psi.agent-session/model-provider
                                                         :psi.agent-session/model-id
                                                         :psi.agent-session/context-window
                                                         :psi.agent-session/context-fraction])]
        (is (= initial-model (:model (ss/get-session-data-in ctx resumed-id))))
        (is (= 200000 (:context-window (ss/get-session-data-in ctx resumed-id))))
        (is (= 22000 (:context-tokens (ss/get-session-data-in ctx resumed-id))))
        (is (= "openai" (:psi.agent-session/model-provider result)))
        (is (= "gpt-5.4" (:psi.agent-session/model-id result)))
        (is (= 200000 (:psi.agent-session/context-window result)))
        (is (= 0.11 (:psi.agent-session/context-fraction result))))))

  (testing "resume-session-in! restores persisted worktree-path and runtime prompt metadata from header"
    (let [[ctx session-id] (create-session-context {:cwd "/repo/main"
                                                    :session-defaults {:system-prompt "base prompt"
                                                                       :model {:provider "openai"
                                                                               :id "gpt-5.3-codex"
                                                                               :reasoning true}}})
          f   (File/createTempFile "psi-resume-worktree" ".ndedn")]
      (.deleteOnExit f)
      (spit f (str "{:type :session :version 4 :id \"sess-worktree\" :timestamp #inst \"2024-01-01T00:00:00Z\" :cwd \"/legacy/cwd\" :worktree-path \"/repo/feature-x\"}\n"
                   "{:id \"e1\" :parent-id nil :timestamp #inst \"2024-01-01T00:00:01Z\" :kind :thinking-level :data {:thinking-level :medium}}\n"
                   "{:id \"e2\" :parent-id \"e1\" :timestamp #inst \"2024-01-01T00:00:02Z\" :kind :session-info :data {:name \"Feature X\"}}\n"
                   "{:id \"e3\" :parent-id \"e2\" :timestamp #inst \"2024-01-01T00:00:03Z\" :kind :message :data {:message {:role \"assistant\" :content [{:type :text :text \"hello\"}]}}}\n"))
      (let [sd                 (session/resume-session-in! ctx session-id (.getAbsolutePath f))
            resumed-id         (:session-id sd)
            ctx                (retarget ctx sd)
            sd                 (ss/get-session-data-in ctx resumed-id)
            result             (session/query-in ctx resumed-id [:psi.agent-session/worktree-path
                                                                 :psi.agent-session/session-name
                                                                 :psi.agent-session/system-prompt])]
        (is (= "/repo/feature-x" (:worktree-path sd)))
        (is (= "/repo/feature-x" (ss/session-worktree-path-in ctx resumed-id)))
        (is (= "/repo/feature-x" (:psi.agent-session/worktree-path result)))
        (is (= "Feature X" (:session-name sd)))
        (is (= "Feature X" (:psi.agent-session/session-name result)))
        (is (= "base prompt" (:psi.agent-session/system-prompt result))))))

  (testing "resume-session-in! missing-file fallback is logged through dispatch"
    (let [f          (str (System/getProperty "java.io.tmpdir") "/psi-missing-" (java.util.UUID/randomUUID) ".ndedn")
          [ctx session-id] (create-session-context {:persist? false})
          _          (dispatch/clear-event-log!)
          sd         (session/resume-session-in! ctx session-id f)
          session-id (:session-id sd)
          ctx        (retarget ctx sd)
          entry      (first (filter #(= :session/resume-missing-initialize (:event-type %))
                                    (dispatch/event-log-entries)))]
      (is (= f (:session-file sd)))
      (is (= [] (persist/all-entries-in ctx session-id)))
      (is (= :core (:origin entry)))
      (is (= {:session-path f} (dissoc (:event-data entry) :session-id))))))
