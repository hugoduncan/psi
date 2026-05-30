(ns psi.agent-session.model-dispatch-test
  "Tests for model management, thinking level, and dispatch routing
  (prompt contributions, event log, projection setters)."
  (:require
   [clojure.edn :as edn]
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent]
   [psi.agent-session.bootstrap :as bootstrap]
   [psi.ai.models :as models]
   [psi.agent-session.core :as session]
   [psi.state-kernel.dispatch :as kernel]
   [psi.session-persistence.core :as persist]
   [psi.shared-config.project :as project-prefs]
   [psi.shared-config.user :as user-config]
   [psi.session-state.state :as ss]
   [psi.agent-session.statechart :as sc]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.test-support :as test-support]
   [psi.turn-runtime.state :as turn-state])
  (:import
   (java.io File)))

(defn- retarget
  [ctx _sid-or-sd]
  ctx)
(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts (merge {:persist? false} opts)))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

;; ── Model management ────────────────────────────────────────────────────────

(deftest model-management-test
  (testing "set-model-in! updates model and persists entry"
    (let [[ctx session-id] (create-session-context)
          model      {:provider "anthropic" :id "claude-3-5-sonnet" :reasoning false}]
      (session/dispatch-in! ctx :session/set-model {:session-id session-id :model model} {:origin :core})
      (let [sd (ss/get-session-data-in ctx session-id)]
        (is (= model (:model sd))))
      (is (pos? (count (persist/all-entries-in ctx session-id))))))

  (testing "set-model-in! clamps thinking level for non-reasoning model"
    (let [[ctx session-id] (create-session-context {:session-defaults {:thinking-level :high}})]
      (session/dispatch-in! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning false}} {:origin :core})
      (is (= :off (:thinking-level (ss/get-session-data-in ctx session-id))))))

  (testing "set-model-in! preserves thinking level for reasoning model"
    (let [[ctx session-id] (create-session-context {:session-defaults {:thinking-level :high}})]
      (session/dispatch-in! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning true}} {:origin :core})
      (is (= :high (:thinking-level (ss/get-session-data-in ctx session-id)))))))

;; ── Thinking level ──────────────────────────────────────────────────────────

(deftest mid-system-capability-dispatch-test
  ;; Tests runtime model capability resolution and dispatch gating for
  ;; mid-conversation system-message injection.
  (testing "resolver reports explicit Opus 4.8 and inferred OpenAI chat-completions support"
    (let [[ctx session-id] (create-session-context)]
      (session/set-model-in! ctx session-id (models/get-model :opus-4.8) :session)
      (is (= true (:psi.agent-session/model-supports-mid-system-messages
                   (session/query-in ctx session-id [:psi.agent-session/model-supports-mid-system-messages]))))
      (session/set-model-in! ctx session-id (models/get-model :gpt-4o) :session)
      (is (= true (:psi.agent-session/model-supports-mid-system-messages
                   (session/query-in ctx session-id [:psi.agent-session/model-supports-mid-system-messages]))))
      (session/set-model-in! ctx session-id (assoc (models/get-model :gpt-4o)
                                                   :id "custom-openai-chat"
                                                   :supports-mid-conversation-system-messages nil)
                             :session)
      (is (= true (:psi.agent-session/model-supports-mid-system-messages
                   (session/query-in ctx session-id [:psi.agent-session/model-supports-mid-system-messages]))))))

  (testing "resolver reports unsupported Anthropic and OpenAI Codex transports as false"
    (let [[ctx session-id] (create-session-context)]
      (session/set-model-in! ctx session-id (models/get-model :claude-3-5-sonnet) :session)
      (is (= false (:psi.agent-session/model-supports-mid-system-messages
                    (session/query-in ctx session-id [:psi.agent-session/model-supports-mid-system-messages]))))
      (session/set-model-in! ctx session-id (models/get-model :gpt-5-codex) :session)
      (is (= false (:psi.agent-session/model-supports-mid-system-messages
                    (session/query-in ctx session-id [:psi.agent-session/model-supports-mid-system-messages]))))))

  (testing "dispatch persists mid-system through the canonical journal append path"
    (let [[ctx session-id] (create-session-context)
          session-file     (File/createTempFile "psi-mid-system-journal" ".ndedn")]
      (ss/assoc-state-value-in! ctx
                                (ss/state-path :flush-state session-id)
                                {:flushed? true :session-file session-file})
      (session/set-model-in! ctx session-id (models/get-model :opus-4.8) :session)
      (session/dispatch-in! ctx :session/append-journal-entry
                            {:session-id session-id
                             :entry (persist/message-entry {:role "user" :content "question"})}
                            {:origin :test})
      (session/dispatch-in! ctx :session/append-journal-entry
                            {:session-id session-id
                             :entry (persist/model-entry :anthropic "claude-opus-4-8")}
                            {:origin :test})
      (kernel/clear-event-log!)
      (is (= {:ok true}
             (session/inject-mid-system-message-in! ctx session-id "Prefer concise answers" {:source :test})))
      (let [entry          (last (persist/all-entries-in ctx session-id))
            injection-log  (last (kernel/event-log-entries))
            persist-effect (first (:declared-effects injection-log))]
        (is (= :mid-system (:kind entry)))
        (is (= {:text "Prefer concise answers" :source :test} (:data entry)))
        (is (= :session/inject-mid-system-message (:event-type injection-log)))
        (is (= :persist/session-journal-io (:effect/type persist-effect)))
        (is (= :append-entry (get-in persist-effect [:request :op])))
        (is (= entry (get-in persist-effect [:request :entry])))
        (is (re-find #"Prefer concise answers" (slurp session-file))))))

  (testing "dispatch rejects unsupported capability and invalid placements without journal mutation"
    (let [[ctx session-id] (create-session-context)
          count-before    #(count (persist/all-entries-in ctx session-id))]
      (session/set-model-in! ctx session-id (models/get-model :claude-3-5-sonnet) :session)
      (is (= {:ok false :error :capability-not-supported}
             (session/inject-mid-system-message-in! ctx session-id "instruction")))
      (is (= 2 (count-before)))

      (session/set-model-in! ctx session-id (models/get-model :opus-4.8) :session)
      (let [n (count-before)]
        (is (= {:ok false :error :invalid-placement :reason :no-preceding-user}
               (session/inject-mid-system-message-in! ctx session-id "instruction")))
        (is (= n (count-before))))

      (session/dispatch-in! ctx :session/append-journal-entry
                            {:session-id session-id
                             :entry (persist/message-entry {:role "user" :content "question"})}
                            {:origin :test})
      (is (= {:ok true}
             (session/inject-mid-system-message-in! ctx session-id "one")))
      (let [n (count-before)]
        (is (= {:ok false :error :invalid-placement :reason :pending-mid-system}
               (session/inject-mid-system-message-in! ctx session-id "two")))
        (is (= n (count-before))))

      (let [[ctx2 session-id2] (create-session-context)]
        (session/set-model-in! ctx2 session-id2 (models/get-model :opus-4.8) :session)
        (session/dispatch-in! ctx2 :session/append-journal-entry
                              {:session-id session-id2
                               :entry (persist/message-entry {:role "assistant" :content "answer"})}
                              {:origin :test})
        (let [n (count (persist/all-entries-in ctx2 session-id2))]
          (is (= {:ok false :error :invalid-placement :reason :after-assistant}
                 (session/inject-mid-system-message-in! ctx2 session-id2 "late")))
          (is (= n (count (persist/all-entries-in ctx2 session-id2)))))))))

(deftest model-thinking-dispatch-test
  (testing "set-model-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)
          _                  (kernel/clear-event-log!)
          model      {:provider "anthropic" :id "claude-3-5-sonnet" :reasoning false}]
      (session/dispatch-in! ctx :session/set-model {:session-id session-id :model model} {:origin :core})
      (let [entry (last (kernel/event-log-entries))
            sd    (ss/get-session-data-in ctx session-id)]
        (is (= model (:model sd)))
        (is (= :session/set-model (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:model model} (dissoc (:event-data entry) :session-id))))))

  (testing "set-thinking-level-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (kernel/clear-event-log!)
      (session/dispatch-in! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning true}} {:origin :core})
      (kernel/clear-event-log!)
      (session/dispatch-in! ctx :session/set-thinking-level {:session-id session-id :level :medium} {:origin :core})
      (let [entry (last (kernel/event-log-entries))
            sd    (ss/get-session-data-in ctx session-id)]
        (is (= :medium (:thinking-level sd)))
        (is (= :session/set-thinking-level (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:level :medium} (dissoc (:event-data entry) :session-id))))))

  (testing "set-system-prompt-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (kernel/clear-event-log!)
      (session/dispatch-in! ctx :session/set-system-prompt {:session-id session-id :prompt "graph-aware system prompt"} {:origin :core})
      (let [entry (last (kernel/event-log-entries))
            sd    (ss/get-session-data-in ctx session-id)]
        (is (= "graph-aware system prompt" (:system-prompt sd)))
        (is (= :session/set-system-prompt (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:prompt "graph-aware system prompt"} (dissoc (:event-data entry) :session-id))))))

  (testing "refresh-system-prompt-in! routes through dispatch log"
    (let [[ctx session-id] (create-session-context)]
      (kernel/clear-event-log!)
      (session/dispatch-in! ctx :session/refresh-system-prompt {:session-id session-id} {:origin :core})
      (let [entry (last (kernel/event-log-entries))
            sd    (ss/get-session-data-in ctx session-id)]
        (is (string? (:system-prompt sd)))
        (is (= :session/refresh-system-prompt (:event-type entry)))
        (is (= :core (:origin entry)))))))

(deftest prompt-contribution-dispatch-test
  (testing "register/update/unregister prompt contributions route through dispatch and preserve return payloads"
    (let [[ctx session-id] (create-session-context)
          _                  (kernel/clear-event-log!)
          r1  (session/dispatch-in! ctx :session/register-prompt-contribution
                                    {:session-id session-id :ext-path "/ext/a" :id "c1"
                                     :contribution {:section "Extension Capabilities"
                                                    :content "tool: x"
                                                    :priority 10}}
                                    {:origin :core})
          e1  (last (kernel/event-log-entries))
          r2  (session/dispatch-in! ctx :session/update-prompt-contribution
                                    {:session-id session-id :ext-path "/ext/a" :id "c1" :patch {:content "tool: y"}}
                                    {:origin :core})
          e2  (last (kernel/event-log-entries))
          r3  (session/dispatch-in! ctx :session/unregister-prompt-contribution
                                    {:session-id session-id :ext-path "/ext/a" :id "c1"}
                                    {:origin :core})
          e3  (last (kernel/event-log-entries))]
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
      (kernel/clear-event-log!)
      (session/dispatch-in! ctx :session/set-session-name {:session-id session-id :name "dispatch-visible"} {:origin :core})
      (let [result (session/query-in ctx
                                     [:psi.agent-session/dispatch-event-log-count
                                      {:psi.agent-session/dispatch-event-log
                                       [:psi.dispatch-event/event-type
                                        :psi.dispatch-event/origin
                                        :psi.dispatch-event/event-data
                                        :psi.dispatch-event/replaying?
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
          (is (= :root-state-update (:psi.dispatch-event/pure-result-kind entry)))
          (let [persist-effect (first (:psi.dispatch-event/declared-effects entry))
                projection-effect (second (:psi.dispatch-event/declared-effects entry))
                applied-persist-effect (first (:psi.dispatch-event/applied-effects entry))
                applied-projection-effect (second (:psi.dispatch-event/applied-effects entry))]
            (is (= :session/append-journal-entry (:event-type persist-effect)))
            (is (= session-id (get-in persist-effect [:event-data :session-id])))
            (is (= :session-info (get-in persist-effect [:event-data :entry :kind])))
            (is (= "dispatch-visible" (get-in persist-effect [:event-data :entry :data :name])))
            (is (= {:effect/type :projection/context-changed
                    :session-id session-id
                    :reason :session/set-session-name}
                   projection-effect))
            (is (= :session/append-journal-entry (:event-type applied-persist-effect)))
            (is (= session-id (get-in applied-persist-effect [:event-data :session-id])))
            (is (= :session-info (get-in applied-persist-effect [:event-data :entry :kind])))
            (is (= "dispatch-visible" (get-in applied-persist-effect [:event-data :entry :data :name])))
            (is (= {:effect/type :projection/context-changed
                    :session-id session-id
                    :reason :session/set-session-name}
                   applied-projection-effect)))
          (is (= {:root-keys [:agent-session :background-jobs :oauth :recursion :runtime :ui :workflows]
                  :root-key-count 7}
                 (:psi.dispatch-event/db-summary-before entry)))
          (is (= {:root-keys [:agent-session :background-jobs :oauth :recursion :runtime :ui :workflows]
                  :root-key-count 7}
                 (:psi.dispatch-event/db-summary-after entry)))))))

  (testing "replay-dispatch-event-log-in! replays retained entries against session state"
    (let [[ctx session-id] (create-session-context)]
      (kernel/clear-event-log!)
      (session/dispatch-in! ctx :session/set-session-name {:session-id session-id :name "replay me"} {:origin :core})
      (session/dispatch-in! ctx :session/set-worktree-path {:session-id session-id :worktree-path "/repo/replay"} {:origin :core})
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
          _                (kernel/clear-event-log!)
          sd               (session/new-session-in! ctx nil {:session-name "dispatch-new"
                                                             :worktree-path "/repo/dispatch-new"})
          session-id       (:session-id sd)
          ctx              (retarget ctx sd)
          entry            (first (filter #(= :session/new-initialize (:event-type %))
                                          (kernel/event-log-entries)))]
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
        _   (kernel/clear-event-log!)
        f   (File/createTempFile "psi-resume-dispatch" ".ndedn")]
    (.deleteOnExit f)
    (spit f (str "{:type :session :version 4 :id \"sess-dispatch\" :timestamp #inst \"2024-01-01T00:00:00Z\" :cwd \"/legacy/cwd\" :worktree-path \"/repo/resume-dispatch\"}\n"
                 "{:id \"e1\" :parent-id nil :timestamp #inst \"2024-01-01T00:00:01Z\" :kind :thinking-level :data {:thinking-level :medium}}\n"
                 "{:id \"e2\" :parent-id \"e1\" :timestamp #inst \"2024-01-01T00:00:02Z\" :kind :session-info :data {:name \"Resume Dispatch\"}}\n"))
    (let [sd                 (session/resume-session-in! ctx session-id (.getAbsolutePath f))
          session-id         (:session-id sd)
          ctx                (retarget ctx sd)
          entry              (first (filter #(= :session/resume-loaded (:event-type %))
                                            (kernel/event-log-entries)))]
      (is (= "Resume Dispatch" (:session-name (ss/get-session-data-in ctx session-id))))
      (is (= :core (:origin entry)))
      (is (= "sess-dispatch" (get-in entry [:event-data :session-id])))
      (is (= "/repo/resume-dispatch" (get-in entry [:event-data :worktree-path])))
      (is (= :medium (get-in entry [:event-data :thinking-level]))))))

(testing "fork-session-in! initialization is logged through dispatch"
  (let [[ctx _session-id] (create-session-context)
        _                 (kernel/clear-event-log!)
        parent-sd          (session/new-session-in! ctx nil {})
        parent-id          (:session-id parent-sd)
        entry-id  (:id (ss/append-journal-entry-in! ctx parent-id (persist/message-entry {:role "user"
                                                                                          :content [{:type :text :text "fork-dispatch"}]
                                                                                          :timestamp (java.time.Instant/now)})))]
    (session/fork-session-in! ctx parent-id entry-id)
    (let [entry (first (filter #(= :session/fork-initialize (:event-type %))
                               (kernel/event-log-entries)))]
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
      (kernel/clear-event-log!)
      (session/dispatch-in! ctx :session/set-rpc-trace {:enabled? true :file "/tmp/rpc-trace.ndedn"} {:origin :core})
      (sa/set-nrepl-runtime-in! ctx session-id {:host "localhost" :port 5555 :endpoint "localhost:5555"})
      (sa/set-oauth-projection-in! ctx {:authenticated-providers ["anthropic"]})
      (sa/set-recursion-state-in! ctx session-id {:status :idle})
      (let [state  @(:state* ctx)
            events (mapv :event-type (kernel/event-log-entries))]
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
          _                  (kernel/clear-event-log!)
          stat       {:tool-name "bash" :context-bytes-added 12}
          _          (turn-state/set-turn-context-in! ctx session-id {:turn-id "t-1"})
          _          (turn-state/append-tool-call-attempt-in! ctx session-id {:id "tc-1" :name "read"})
          _          (turn-state/append-provider-request-capture-in! ctx session-id {:provider "anthropic" :turn-id "t-1"})
          _          (turn-state/append-provider-reply-capture-in! ctx session-id {:provider "anthropic" :turn-id "t-1" :event {:type :done}})
          _          (sa/record-tool-output-stat-in! ctx session-id stat 12 false)
          state      @(:state* ctx)
          events     (mapv :event-type (kernel/event-log-entries))]
      (let [sid session-id]
        (is (= {:turn-id "t-1"} (get-in state [:agent-session :sessions sid :turn :ctx])))
        (is (= "tc-1" (get-in state [:agent-session :sessions sid :telemetry :tool-call-attempts 0 :id])))
        (is (= "anthropic" (get-in state [:agent-session :sessions sid :telemetry :provider-requests 0 :provider])))
        (is (= "anthropic" (get-in state [:agent-session :sessions sid :telemetry :provider-replies 0 :provider])))
        (is (= [stat] (get-in state [:agent-session :sessions sid :telemetry :tool-output-stats :calls]))))
      (is (= [:session/record-tool-output-stat]
             events)))))

(deftest interrupt-and-bootstrap-prompt-dispatch-test
  (testing "request-interrupt-in! routes session-data changes through dispatch"
    (let [[ctx session-id] (create-session-context)]
      (kernel/clear-event-log!)
      (test-support/update-state! ctx :session-data assoc
                                  :interrupt-pending false
                                  :steering-messages ["queued steer"])
      (swap! (:data-atom (ss/agent-ctx-in ctx session-id)) assoc :pending-tool-calls #{"tc-interrupt-test"})
      ;; Force streaming deterministically so interrupt behavior does not depend
      ;; on prompt runtime timing.
      (sc/send-event! (:sc-env ctx)
                      (ss/sc-session-id-in ctx session-id)
                      :session/prompt
                      {:ctx ctx :session-id session-id})
      (session/request-interrupt-in! ctx session-id)
      (let [sd    (ss/get-session-data-in ctx session-id)
            entry (last (kernel/event-log-entries))]
        (is (true? (:interrupt-pending sd)))
        (is (= :deferred-interrupt (:interrupt-reason sd)))
        (is (= [] (:steering-messages sd)))
        (is (instance? java.time.Instant (:interrupt-requested-at sd)))
        (is (= :session/request-interrupt (:event-type entry)))
        (is (= :core (:origin entry))))))

  (testing "bootstrap-prompt-state dispatch seeds prompt metadata"
    (let [[ctx session-id] (create-session-context)]
      (kernel/clear-event-log!)
      (session/dispatch-in! ctx :session/bootstrap-prompt-state
                            {:session-id              session-id
                             :system-prompt           "sys"
                             :developer-prompt        "dev"
                             :developer-prompt-source :explicit}
                            {:origin :core})
      (let [sd    (ss/get-session-data-in ctx session-id)
            entry (first (filter #(= :session/bootstrap-prompt-state (:event-type %))
                                 (kernel/event-log-entries)))]
        (is (= "sys" (:base-system-prompt sd)))
        (is (= "sys" (:system-prompt sd)))
        (is (= "dev" (:developer-prompt sd)))
        (is (= :explicit (:developer-prompt-source sd)))
        (is (= :core (:origin entry)))
        (is (= {:system-prompt "sys"
                :developer-prompt "dev"
                :developer-prompt-source :explicit}
               (dissoc (:event-data entry) :session-id))))))

  (testing "bootstrap-prompt-state leaves developer layer unset when none is provided"
    (let [[ctx session-id] (create-session-context)]
      (session/dispatch-in! ctx :session/bootstrap-prompt-state
                            {:session-id    session-id
                             :system-prompt "sys"}
                            {:origin :core})
      (let [sd (ss/get-session-data-in ctx session-id)]
        (is (= "sys" (:base-system-prompt sd)))
        (is (nil? (:developer-prompt sd)))
        (is (nil? (:developer-prompt-source sd)))))))

(deftest runtime-agent-set-model-effect-test
  (testing "runtime agent-set-model effect accepts scoped shape without changing transient runtime behavior"
    (let [[ctx session-id] (create-session-context)
          model {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}]
      (session/dispatch-in! ctx :session/set-model {:session-id session-id :model model :scope :session} {:origin :core})
      (is (= model (:model (ss/get-session-data-in ctx session-id)))))))

(deftest thinking-level-test
  (testing "set-thinking-level-in! updates level"
    (let [[ctx session-id] (create-session-context)]
      (session/dispatch-in! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning true}} {:origin :core})
      (session/dispatch-in! ctx :session/set-thinking-level {:session-id session-id :level :medium} {:origin :core})
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
      (session/dispatch-in! ctx :session/set-model {:session-id session-id :model model} {:origin :core})
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

  (testing "explicit session-scoped model changes do not persist project or user config"
    (let [cwd        (str (System/getProperty "java.io.tmpdir") "/psi-session-scope-prefs-" (java.util.UUID/randomUUID))
          _          (.mkdirs (java.io.File. cwd))
          shared-f   (project-prefs/project-preferences-file cwd)
          local-f    (project-prefs/project-local-preferences-file cwd)
          user-calls* (atom [])
          _          (.mkdirs (.getParentFile shared-f))
          _          (spit shared-f (pr-str {:version 1
                                             :agent-session {:prompt-mode :prose}}))
          [ctx session-id] (create-session-context {:cwd cwd})
          model      {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}]
      (with-redefs [user-config/update-agent-session! (fn [prefs]
                                                        (swap! user-calls* conj prefs)
                                                        {:version 1 :agent-session prefs})]
        (session/set-model-in! ctx session-id model :session))
      (is (= model (:model (ss/get-session-data-in ctx session-id))))
      (is (= {:version 1
              :agent-session {:prompt-mode :prose}}
             (edn/read-string (slurp shared-f))))
      (is (false? (.exists local-f)))
      (is (= [] @user-calls*))))

  (testing "explicit user-scoped model changes persist only user config"
    (let [cwd        (str (System/getProperty "java.io.tmpdir") "/psi-user-scope-prefs-" (java.util.UUID/randomUUID))
          _          (.mkdirs (java.io.File. cwd))
          shared-f   (project-prefs/project-preferences-file cwd)
          local-f    (project-prefs/project-local-preferences-file cwd)
          user-f     (java.io.File. (str cwd "/user-home/.psi/agent/config.edn"))
          _          (.mkdirs (.getParentFile shared-f))
          _          (.mkdirs (.getParentFile user-f))
          _          (spit shared-f (pr-str {:version 1
                                             :agent-session {:prompt-mode :prose}}))
          [ctx session-id] (create-session-context {:cwd cwd})
          model      {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}]
      (with-redefs [user-config/user-config-file (fn [] user-f)]
        (session/set-model-in! ctx session-id model :user))
      (is (= model (:model (ss/get-session-data-in ctx session-id))))
      (is (= {:version 1
              :agent-session {:prompt-mode :prose}}
             (edn/read-string (slurp shared-f))))
      (is (false? (.exists local-f)))
      (is (= "anthropic" (get-in (edn/read-string (slurp user-f)) [:agent-session :model-provider])))
      (is (= "claude-sonnet-4-6" (get-in (edn/read-string (slurp user-f)) [:agent-session :model-id])))))

  (testing "set-thinking-level-in! persists project preferences to the local project layer"
    (let [cwd      (str (System/getProperty "java.io.tmpdir") "/psi-project-prefs-" (java.util.UUID/randomUUID))
          _        (.mkdirs (java.io.File. cwd))
          shared-f (project-prefs/project-preferences-file cwd)
          local-f  (project-prefs/project-local-preferences-file cwd)
          _        (.mkdirs (.getParentFile shared-f))
          _        (spit shared-f (pr-str {:version 1
                                           :agent-session {:prompt-mode :prose}}))
          [ctx session-id] (create-session-context {:cwd cwd})]
      (session/dispatch-in! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning true}} {:origin :core})
      (session/dispatch-in! ctx :session/set-thinking-level {:session-id session-id :level :high} {:origin :core})
      (let [prefs (project-prefs/read-preferences cwd)]
        (is (= :high (get-in prefs [:agent-session :thinking-level])))
        (is (= :prose (get-in prefs [:agent-session :prompt-mode]))))
      (is (= {:version 1
              :agent-session {:prompt-mode :prose}}
             (edn/read-string (slurp shared-f))))
      (is (= :high (get-in (edn/read-string (slurp local-f)) [:agent-session :thinking-level])))))

  (testing "cycle-thinking-level-in! advances level for reasoning model"
    (let [[ctx session-id] (create-session-context {:session-defaults {:thinking-level :off}})]
      (session/dispatch-in! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning true}} {:origin :core})
      (session/cycle-thinking-level-in! ctx session-id)
      (is (= :minimal (:thinking-level (ss/get-session-data-in ctx session-id))))))

  (testing "cycle-thinking-level-in! is no-op for non-reasoning model"
    (let [[ctx session-id] (create-session-context {:session-defaults {:thinking-level :off}})]
      (session/dispatch-in! ctx :session/set-model {:session-id session-id :model {:provider "x" :id "y" :reasoning false}} {:origin :core})
      (session/cycle-thinking-level-in! ctx session-id)
      (is (= :off (:thinking-level (ss/get-session-data-in ctx session-id)))))))

(deftest bootstrap-resource-registration-test
  (testing "load-startup-resources-in! with templates, skills, and tools registers all resources"
    (let [[ctx session-id] (create-session-context)
          template {:name "greet" :description "Greeting" :content "Hello" :source :project :file-path "/tmp/greet.md"}
          skill    {:name "test-skill" :description "A test skill" :lambda "λtest" :entrypoint "SKILL.md"}
          tool     {:name "test-tool" :description "A test tool" :parameters {:type "object" :properties {}}}
          result   (bootstrap/load-startup-resources-in!
                    ctx session-id
                    {:templates [template]
                     :skills    [skill]
                     :tools     [tool]})
          sd         (ss/get-session-data-in ctx session-id)
          agent-data (agent/get-data-in (ss/agent-ctx-in ctx session-id))]
      (testing "return counts match registered resources"
        (is (= 1 (:prompt-count result))
            "prompt-count = 1")
        (is (= 1 (:skill-count result))
            "skill-count = 1")
        (is (pos? (:tool-count result))
            "tool-count ≥ 1"))
      (is (= 1 (count (:prompt-templates sd)))
          "one template registered in session-data")
      (is (= 1 (count (:skill-ids sd)))
          "one skill id registered in session-data")
      (is (nil? (:skills sd))
          "embedded session :skills removed from canonical session-data")
      (is (= "greet" (:name (first (:prompt-templates sd)))))
      (is (= ["test-skill"] (:skill-ids sd)))
      (is (some #(= "test-tool" (:name %)) (:tools agent-data))))))

(deftest bootstrap-dispatch-event-log-test
  (testing "load-startup-resources-in! produces dispatch events for resource registration"
    (let [[ctx session-id] (create-session-context)
          template {:name "greet" :description "Greeting" :content "Hello" :source :project :file-path "/tmp/greet.md"}
          skill    {:name "test-skill" :description "A test skill" :lambda "λtest" :entrypoint "SKILL.md"}
          tool     {:name "test-tool" :description "A test tool" :parameters {:type "object" :properties {}}}]
      (kernel/clear-event-log!)
      (bootstrap/load-startup-resources-in!
       ctx session-id
       {:templates [template]
        :skills    [skill]
        :tools     [tool]})
      (let [entries    (kernel/event-log-entries)
            event-types (set (map :event-type entries))]
        (is (contains? event-types :session/register-prompt-template)
            "template registration event in log")
        (is (not (contains? event-types :session/register-skill))
            "startup skill hydration no longer routes through session/register-skill")
        (is (contains? event-types :session/add-tool)
            "tool addition event in log")
        (testing "all resource registration events have expected origin"
          (let [resource-events (filter #(#{:session/register-prompt-template
                                            :session/add-tool} (:event-type %))
                                        entries)]
            (is (pos? (count resource-events)))
            (doseq [e resource-events]
              (is (= :core (:origin e))
                  (str (:event-type e) " should have :origin :core")))))))))
