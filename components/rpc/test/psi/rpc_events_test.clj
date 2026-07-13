(ns psi.rpc-events-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.runtime :as runtime]
   [psi.session-state.state :as ss]
   [psi.rpc.events :as rpc.events]
   [psi.rpc.session.command-results :as command-results]
   [psi.rpc.state :as rpc.state]
   [psi.rpc-test-support :as support]))

(defn- captured-emit-frame!
  [captured]
  (fn [frame] (swap! captured conj frame)))

(deftest emit-event-suppresses-session-scoped-event-for-non-focused-session-test
  (testing "session-scoped event for a non-focused session is not emitted"
    (let [state     (rpc.state/make-rpc-state {:session-id "s1"})
          captured  (atom [])
          emit-frame! (captured-emit-frame! captured)]
      (rpc.state/set-focus-session-id! state "s1")
      (rpc.events/emit-event! emit-frame! state
                              {:event "assistant/delta"
                               :data {:session-id "s2" :text "hi"}})
      (is (= [] @captured)))))

(deftest emit-event-emits-session-scoped-event-for-focused-session-test
  (testing "session-scoped event for the focused session is emitted"
    (let [state     (rpc.state/make-rpc-state {:session-id "s1"})
          captured  (atom [])
          emit-frame! (captured-emit-frame! captured)]
      (rpc.state/set-focus-session-id! state "s1")
      (rpc.events/emit-event! emit-frame! state
                              {:event "assistant/delta"
                               :data {:session-id "s1" :text "hi"}})
      (is (= 1 (count @captured)))
      (is (= "assistant/delta" (:event (first @captured)))))))

(deftest emit-event-nil-focus-uses-default-session-id-test
  (testing "with nil explicit focus, events for the default session emit and others are suppressed"
    (let [state       (rpc.state/make-rpc-state {:session-id "s1"})
          captured    (atom [])
          emit-frame! (captured-emit-frame! captured)]
      (rpc.state/set-focus-session-id! state nil)
      (rpc.events/emit-event! emit-frame! state
                              {:event "assistant/delta"
                               :data {:session-id "s1" :text "hi"}})
      (rpc.events/emit-event! emit-frame! state
                              {:event "assistant/delta"
                               :data {:session-id "s2" :text "hi"}})
      (is (= 1 (count @captured)))
      (is (= "s1" (get-in (first @captured) [:data :session-id]))))))

(deftest emit-event-cross-session-event-emits-regardless-of-focus-test
  (testing "context/updated (no :session-id in payload) emits while a different session has focus"
    (let [state       (rpc.state/make-rpc-state {:session-id "s1"})
          captured    (atom [])
          emit-frame! (captured-emit-frame! captured)]
      (rpc.state/set-focus-session-id! state "s1")
      (rpc.events/emit-event! emit-frame! state
                              {:event "context/updated"
                               :data {:active-session-id "s2" :sessions []}})
      (is (= 1 (count @captured))))))

(deftest emit-event-session-switch-command-result-emits-for-non-focused-target-test
  (testing "a session_switch command-result whose target differs from focus is still emitted"
    (let [state       (rpc.state/make-rpc-state {:session-id "s1"})
          captured    (atom [])
          emit-frame! (captured-emit-frame! captured)]
      (rpc.state/set-focus-session-id! state "s1")
      ;; Target key must not be a bare :session-id, else the structural focus
      ;; gate would suppress this never-gated cross-session notification.
      (rpc.events/emit-event! emit-frame! state
                              {:event "command-result"
                               :data {:type "session_switch"
                                      :target-session-id "s2"}})
      (is (= 1 (count @captured)))
      (is (= "command-result" (:event (first @captured))))
      (is (= "s2" (get-in (first @captured) [:data :target-session-id]))))))

(deftest emit-event-legacy-prompt-assistant-message-suppressed-for-non-focused-session-test
  (testing "a legacy prompt-path assistant/message stamps :session-id so it gates like the streaming path"
    (let [state       (rpc.state/make-rpc-state {:session-id "s1"})
          captured    (atom [])
          emit-frame! (captured-emit-frame! captured)
          emit!       (fn [event data]
                        (rpc.events/emit-event! emit-frame! state
                                                {:event event :data data}))]
      (rpc.state/set-focus-session-id! state "s1")
      ;; Legacy feedback for the FOCUSED session emits.
      (command-results/handle-prompt-command-result!
       "s1" {:type :text :message "focused feedback"} emit!)
      ;; Same legacy feedback path for a NON-FOCUSED session is suppressed by
      ;; the structural focus gate (resolution (a): payload carries :session-id).
      (command-results/handle-prompt-command-result!
       "s2" {:type :text :message "background feedback"} emit!)
      (is (= 1 (count @captured)))
      (is (= "assistant/message" (:event (first @captured))))
      (is (= "s1" (get-in (first @captured) [:data :session-id]))))))

(deftest emit-event-after-refocus-suppresses-previous-session-events-test
  (testing "after focus moves to session B, session-scoped events stamped with A's session-id are suppressed"
    (let [state       (rpc.state/make-rpc-state {:session-id "a"})
          captured    (atom [])
          emit-frame! (captured-emit-frame! captured)]
      (rpc.state/set-focus-session-id! state "b")
      (rpc.events/emit-event! emit-frame! state
                              {:event "assistant/delta"
                               :data {:session-id "a" :text "background"}})
      (rpc.events/emit-event! emit-frame! state
                              {:event "assistant/delta"
                               :data {:session-id "b" :text "foreground"}})
      (is (= 1 (count @captured)))
      (is (= "foreground" (get-in (first @captured) [:data :text]))))))

(deftest emit-event-single-session-connection-behaviour-preserved-test
  (testing "a single-session connection emits everything as before (common case)"
    (let [state       (rpc.state/make-rpc-state {:session-id "only"})
          captured    (atom [])
          emit-frame! (captured-emit-frame! captured)]
      (doseq [event ["session/updated" "assistant/delta" "tool/start"
                     "footer/updated" "session/resumed" "session/rehydrated"]]
        (rpc.events/emit-event! emit-frame! state
                                {:event event
                                 :data (merge {:session-id "only"}
                                              (case event
                                                "session/updated" {:phase :idle :is-streaming false
                                                                   :is-compacting false :pending-message-count 0
                                                                   :retry-attempt 0 :retry nil :interrupt-pending false}
                                                "tool/start" {:tool-id "t1" :tool-name "read" :call-summary "read"}
                                                "session/resumed" {:session-file "f" :message-count 0}
                                                "session/rehydrated" {:messages [] :tool-calls {} :tool-order []}
                                                "footer/updated" {:path-line "p" :stats-line "s"}
                                                {:text "hi"}))}))
      (is (= 6 (count @captured))))))

(deftest emit-event-ui-and-command-result-and-error-emit-regardless-of-focus-test
  (testing "non-session-scoped topics emit regardless of focus"
    (let [state       (rpc.state/make-rpc-state {:session-id "s1"})
          captured    (atom [])
          emit-frame! (captured-emit-frame! captured)]
      (rpc.state/set-focus-session-id! state "s1")
      (rpc.events/emit-event! emit-frame! state
                              {:event "ui/widget-specs-updated" :data {}})
      (rpc.events/emit-event! emit-frame! state
                              {:event "command-result" :data {:type "ok"}})
      (rpc.events/emit-event! emit-frame! state
                              {:event "error" :data {:error-code "e" :error-message "m"}})
      (is (= 3 (count @captured))))))

(deftest footer-updated-payload-uses-default-footer-projection-values-test
  (testing "footer payload mirrors default footer path/stats/status composition"
    (let [home    (System/getProperty "user.home")
          cwd     (str home "/tmp/psi-rpc-events-footer-default")
          [ctx session-id] (support/create-session-context {:cwd cwd})
          payload (with-redefs [session/query-in
                                (fn [_ctx sid q]
                                  (is (= session-id sid))
                                  (is (= @#'rpc.events/footer-query q))
                                  {:psi.agent-session/worktree-path cwd
                                   :psi.agent-session/git-branch "master"
                                   :psi.agent-session/session-name "xhig"
                                   :psi.agent-session/session-display-name "xhig"
                                   :psi.agent-session/usage-input 172000
                                   :psi.agent-session/usage-output 17000
                                   :psi.agent-session/usage-cache-read 5200000
                                   :psi.agent-session/usage-cache-write 1200
                                   :psi.agent-session/usage-cost-total 1.444
                                   :psi.agent-session/context-fraction 0.319
                                   :psi.agent-session/context-window 272000
                                   :psi.agent-session/auto-compaction-enabled true
                                   :psi.agent-session/model-provider "openai-codex"
                                   :psi.agent-session/model-id "gpt-5.3-codex"
                                   :psi.agent-session/model-reasoning true
                                   :psi.agent-session/thinking-level :xhigh
                                   :psi.agent-session/effective-reasoning-effort "high"
                                   :psi.ui/statuses [{:extension-id "b" :text "TS+ESL,Prett"}
                                                     {:extension-id "a" :text "Formatter\nformatter"}]})]
                    (rpc.events/footer-updated-payload ctx session-id))]
      (is (= "~/tmp/psi-rpc-events-footer-default (master) • xhig"
             (:path-line payload)))
      (is (= ["↑172k" "↓17k" "CR5.2M" "CW1.2k" "$1.444" "31.9%/272k (auto)"]
             (:usage-parts payload)))
      (is (= "(openai-codex) gpt-5.3-codex • thinking high"
             (:model-text payload)))
      (is (= "Formatter formatter TS+ESL,Prett"
             (:status-line payload)))
      (is (nil? (:session-activity-line payload))))))

(deftest footer-updated-payload-includes-session-activity-line-test
  (testing "footer payload includes canonical backend session activity text"
    (let [[ctx session-id] (support/create-session-context {:cwd "/repo/project"})
          _               (ss/apply-root-state-update-in! ctx
                                                          (ss/session-update session-id #(assoc % :session-name "main")))
          _               (session/dispatch-in! ctx :session/prompt {:session-id session-id} {:origin :test})
          child           (session/new-session-in! ctx session-id {:session-name "helper"})
          payload         (rpc.events/footer-updated-payload ctx session-id)]
      (is (= "/repo/project • helper"
             (get-in (rpc.events/footer-updated-payload ctx (:session-id child)) [:path-line])))
      (is (= "sessions: waiting helper · running main"
             (:session-activity-line payload)))
      (is (= [{:state "waiting" :labels ["helper"]}
              {:state "running" :labels ["main"]}]
             (:session-activity-buckets payload))))))

(deftest footer-updated-payload-prefers-session-display-name-test
  (testing "footer payload uses derived display name when explicit session name is absent"
    (let [home    (System/getProperty "user.home")
          cwd     (str home "/tmp/psi-rpc-events-footer-display-name")
          [ctx session-id] (support/create-session-context {:cwd cwd})
          payload (with-redefs [session/query-in
                                (fn [_ctx sid q]
                                  (is (= session-id sid))
                                  (is (= @#'rpc.events/footer-query q))
                                  {:psi.agent-session/worktree-path cwd
                                   :psi.agent-session/git-branch "master"
                                   :psi.agent-session/session-name nil
                                   :psi.agent-session/session-display-name "Investigate failing tests"
                                   :psi.agent-session/usage-input 0
                                   :psi.agent-session/usage-output 0
                                   :psi.agent-session/usage-cache-read 0
                                   :psi.agent-session/usage-cache-write 0
                                   :psi.agent-session/usage-cost-total 0.0
                                   :psi.agent-session/context-fraction nil
                                   :psi.agent-session/context-window 272000
                                   :psi.agent-session/auto-compaction-enabled false
                                   :psi.agent-session/model-provider "openai-codex"
                                   :psi.agent-session/model-id "gpt-5.3-codex"
                                   :psi.agent-session/model-reasoning true
                                   :psi.agent-session/thinking-level :high
                                   :psi.agent-session/effective-reasoning-effort "high"
                                   :psi.ui/statuses []})]
                    (rpc.events/footer-updated-payload ctx session-id))]
      (is (= "~/tmp/psi-rpc-events-footer-display-name (master) • Investigate failing tests"
             (:path-line payload)))
      (is (= ["?/272k"]
             (:usage-parts payload)))
      (is (= "(openai-codex) gpt-5.3-codex • thinking high"
             (:model-text payload))))))

(deftest session-updated-payload-includes-model-metadata-test
  (testing "session payload includes model metadata for frontend header projection"
    (let [[ctx sid] (support/create-session-context)
          _         (session/dispatch-in! ctx :session/set-model
                                          {:session-id sid
                                           :model {:provider "openai"
                                                   :id "gpt-5.3-codex"
                                                   :reasoning true}}
                                          {:origin :core})
          _         (session/dispatch-in! ctx :session/set-thinking-level
                                          {:session-id sid :level :xhigh}
                                          {:origin :core})
          payload   (rpc.events/session-updated-payload ctx sid)]
      (is (= sid (:session-id payload)))
      (is (= "openai" (:model-provider payload)))
      (is (= "gpt-5.3-codex" (:model-id payload)))
      (is (= true (:model-reasoning payload)))
      (is (= "xhigh" (:thinking-level payload)))
      (is (= "xhigh" (:effective-reasoning-effort payload)))
      (is (= "(openai) gpt-5.3-codex • thinking xhigh" (:header-model-label payload))))))

(deftest session-updated-payload-includes-retry-contract-test
  (testing "session payload includes canonical retry payload and summary text"
    (let [[ctx sid] (support/create-session-context)
          now-ms    (System/currentTimeMillis)
          _         (ss/apply-root-state-update-in! ctx
                                                    (ss/session-update sid #(assoc %
                                                                                   :retry-attempt 2
                                                                                   :retry {:active? true
                                                                                           :attempt 2
                                                                                           :delay-ms 8000
                                                                                           :delay-source :retry-after
                                                                                           :resume-at (+ now-ms 8000)
                                                                                           :rate-limit {:remaining 0
                                                                                                        :limit 5000
                                                                                                        :reset-at (+ now-ms 32000)}}
                                                                                   :steering-messages [{:content "a"}]
                                                                                   :follow-up-messages [{:content "b"}])))
          payload   (rpc.events/session-updated-payload ctx sid)
          status    (:status-session-line payload)]
      (is (= #{:session-id :session-file :session-name :session-display-name :phase :is-streaming :is-compacting :pending-message-count :retry-attempt :retry :interrupt-pending :model-provider :model-id :model-reasoning :thinking-level :effective-reasoning-effort :header-model-label :status-session-line :extension-command-names :prompt-templates}
             (set (keys payload))))
      (is (= {:active? true
              :attempt 2
              :delay-ms 8000
              :delay-source :retry-after
              :resume-at (+ now-ms 8000)
              :rate-limit {:remaining 0
                           :limit 5000
                           :reset-at (+ now-ms 32000)}}
             (:retry payload)))
      (is (= 2 (:pending-message-count payload)))
      (is (= 2 (:retry-attempt payload)))
      (is (re-find (re-pattern (str "^session: " sid " phase:retrying streaming:no compacting:no pending:2 retry:2"))
                   status))
      (is (re-find #"retrying-in:[78]s" status))
      (is (re-find #"source:retry-after" status))
      (is (re-find #"remaining:0/5000" status))
      (is (re-find #"reset-in:3[12]s" status)))))

(deftest session-updated-payload-includes-derived-session-display-name-test
  (testing "session payload includes derived display name from latest non-command user message"
    (let [[ctx sid] (support/create-session-context)
          _         (runtime/journal-user-message-in! ctx sid "Investigate failing tests in RPC footer" nil)
          _         (runtime/journal-user-message-in! ctx sid "/tree" nil)
          payload   (rpc.events/session-updated-payload ctx sid)]
      (is (= "Investigate failing tests in RPC footer"
             (:session-display-name payload))))))

(deftest progress-event-thinking-delta-maps-to-rpc-thinking-topic-test
  (let [{:keys [event data]}
        (rpc.events/progress-event->rpc-event {:event-kind :thinking-delta :text "plan"})]
    (is (= "assistant/thinking-delta" event))
    (is (= "plan" (:text data)))))

(deftest footer-updated-payload-includes-model-and-thinking-when-session-reasoning-enabled-test
  (testing "footer payload includes model/thinking details from active session query"
    (let [[ctx session-id] (support/create-session-context)
          _                (session/dispatch-in! ctx :session/set-model
                                                 {:session-id session-id
                                                  :model {:provider "openai"
                                                          :id "gpt-5.3-codex"
                                                          :reasoning true}}
                                                 {:origin :core})
          _                (session/dispatch-in! ctx :session/set-thinking-level
                                                 {:session-id session-id :level :high}
                                                 {:origin :core})
          _                (session/dispatch-in! ctx :session/update-context-usage
                                                 {:session-id session-id :tokens 4000 :window 100000}
                                                 {:origin :core})
          _                (ss/append-journal-entry-in! ctx session-id
                                                        {:kind :message
                                                         :session-id session-id
                                                         :data {:message {:role "assistant"
                                                                          :usage {:input-tokens 111
                                                                                  :output-tokens 22}}}})
          payload          (rpc.events/footer-updated-payload ctx session-id)]
      (is (= ["↑111" "↓22" "4.0%/100k"]
             (:usage-parts payload)))
      (is (= "(openai) gpt-5.3-codex • thinking high"
             (:model-text payload))))))

