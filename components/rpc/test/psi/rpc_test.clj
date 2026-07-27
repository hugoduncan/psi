(ns psi.rpc-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.session-state.state :as ss]
   [psi.session-persistence.core :as persist]
   [psi.agent-session.mutations :as mutations]
   [psi.rpc :as rpc]
   [psi.rpc.session.projections :as rpc.projections]
   [psi.rpc.events :as rpc.events]
   [psi.agent-session.runtime :as runtime]
   [psi.query.core :as query]
   [psi.provider-auth.oauth.core :as oauth]
   [psi.rpc-test-support :as support]
   [psi.rpc.session.command-pickers]))

(deftest footer-updated-payload-uses-default-footer-projection-values-test
  (testing "footer payload mirrors default footer path/stats/status composition"
    (let [home    (System/getProperty "user.home")
          cwd     (str home "/tmp/psi-rpc-footer-default")
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
      (is (= "~/tmp/psi-rpc-footer-default (master) • xhig"
             (:path-line payload)))
      (is (= ["↑172k" "↓17k" "CR5.2M" "CW1.2k" "$1.444" "31.9%/272k (auto)"]
             (:usage-parts payload)))
      (is (= "(openai-codex) gpt-5.3-codex • thinking high"
             (:model-text payload)))
      (is (= "Formatter formatter TS+ESL,Prett"
             (:status-line payload))))))

(deftest footer-updated-payload-prefers-session-display-name-test
  (testing "footer payload uses derived display name when explicit session name is absent"
    (let [home    (System/getProperty "user.home")
          cwd     (str home "/tmp/psi-rpc-footer-display-name")
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
      (is (= "~/tmp/psi-rpc-footer-display-name (master) • Investigate failing tests"
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
          _         (ss/apply-root-state-update-in! ctx
                                                    (ss/session-update sid #(assoc %
                                                                                   :retry-attempt 2
                                                                                   :steering-messages [{:content "a"}]
                                                                                   :follow-up-messages [{:content "b"}])))
          payload   (rpc.events/session-updated-payload ctx sid)]
      (is (= sid (:session-id payload)))
      (is (= "openai" (:model-provider payload)))
      (is (= "gpt-5.3-codex" (:model-id payload)))
      (is (= true (:model-reasoning payload)))
      (is (= "xhigh" (:thinking-level payload)))
      (is (= "xhigh" (:effective-reasoning-effort payload)))
      (is (= "(openai) gpt-5.3-codex • thinking xhigh" (:header-model-label payload)))
      (is (= (str "session: " sid " phase:idle streaming:no compacting:no pending:2 retry:2")
             (:status-session-line payload)))
      (is (= 2 (:pending-message-count payload)))
      (is (= 2 (:retry-attempt payload))))))

(deftest session-updated-payload-includes-derived-session-display-name-test
  (testing "session payload includes derived display name from latest non-command user message"
    (let [[ctx sid] (support/create-session-context)
          _         (runtime/journal-user-message-in! ctx sid "Investigate failing tests in RPC footer" nil)
          _         (runtime/journal-user-message-in! ctx sid "/tree" nil)
          payload   (rpc.events/session-updated-payload ctx sid)]
      (is (= "Investigate failing tests in RPC footer"
             (:session-display-name payload))))))

(deftest rpc-new-session-uses-callback-rehydrate-payload-test
  (testing "new_session uses on-new-session! callback when provided"
    (let [[ctx session-id] (support/create-session-context)
          called? (atom 0)
          state (atom {:transport {:ready? true :pending {}}
                       :connection {:subscribed-topics #{"session/rehydrated"}}})
          handler (rpc/make-session-request-handler ctx {:on-new-session! (fn [_source-session-id]
                                                                            (swap! called? inc)
                                                                            {:agent-messages [{:role "assistant"
                                                                                               :content [{:type :text :text "startup reply"}]}]
                                                                             :messages [{:role :assistant :text "startup reply"}]
                                                                             :tool-calls {"call-1" {:name "read"}}
                                                                             :tool-order ["call-1"]})})
          input (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"n1\" :kind :request :op \"new_session\" :params {:session-id \"" session-id "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames (support/parse-frames out-lines)
          rehydrate-event (some #(when (= "session/rehydrated" (:event %)) %) frames)]
      (is (= 1 @called?))
      (is (some? rehydrate-event))
      (is (= [{:role "assistant"
               :content [{:type :text :text "startup reply"}]}]
             (get-in rehydrate-event [:data :messages])))
      (is (= ["call-1"]
             (get-in rehydrate-event [:data :tool-order]))))))

(deftest rpc-new-session-footer-usage-is-session-scoped-test
  (testing "new_session footer/updated does not carry usage totals from previous session"
    (let [[ctx session-id] (support/create-session-context {:session-defaults {:model {:provider "openai"
                                                                                       :id "gpt-5.4"
                                                                                       :reasoning false}}})
          state      (atom {:transport {:ready? true :pending {}}
                            :connection {:subscribed-topics #{"footer/updated"}}})
          handler (support/make-handler ctx state)
          _          (ss/append-journal-entry-in! ctx session-id
                                                  {:kind :message
                                                   :session-id session-id
                                                   :data {:message {:role "assistant"
                                                                    :usage {:input-tokens 111
                                                                            :output-tokens 22}}}})
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"n1\" :kind :request :op \"new_session\"}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames (support/parse-frames out-lines)
          footer-event (some #(when (= "footer/updated" (:event %)) %) frames)]
      (is (some? footer-event))
      (is (= ["?/0"] (get-in footer-event [:data :usage-parts])))
      (is (= "(openai) gpt-5.4"
             (get-in footer-event [:data :model-text]))))))

(deftest footer-updated-payload-includes-model-and-thinking-when-session-reasoning-enabled-test
  (testing "footer payload includes model/thinking details from active session query"
    (let [[ctx session-id] (support/create-session-context)
          _          (session/dispatch-in! ctx :session/set-model
                                           {:session-id session-id :model {:provider "openai" :id "gpt-5.3-codex" :reasoning true}}
                                           {:origin :core})
          _          (session/dispatch-in! ctx :session/set-thinking-level {:session-id session-id :level :high} {:origin :core})
          _          (session/dispatch-in! ctx :session/update-context-usage {:session-id session-id :tokens 4000 :window 100000} {:origin :core})
          _          (ss/append-journal-entry-in! ctx session-id
                                                  {:kind :message
                                                   :session-id session-id
                                                   :data {:message {:role "assistant"
                                                                    :usage {:input-tokens 111
                                                                            :output-tokens 22}}}})
          payload (rpc.events/footer-updated-payload ctx session-id)]
      (is (= ["↑111" "↓22" "4.0%/100k"]
             (:usage-parts payload)))
      (is (= "(openai) gpt-5.3-codex • thinking high"
             (:model-text payload))))))

(deftest rpc-subscribe-emits-context-updated-test
  (testing "subscribe emits context/updated with active-session-id and sessions list"
    (let [[ctx session-id] (support/create-session-context)
          user-ts          (java.time.Instant/parse "2026-03-16T10:47:00Z")
          _                (ss/append-journal-entry-in! ctx session-id
                                                        (persist/message-entry {:role "user"
                                                                                :content [{:type :text :text "hi"}]
                                                                                :timestamp user-ts}))
          state            (atom {:transport {:ready? true :pending {}}
                                  :connection {:subscribed-topics #{"context/updated"}}})
          handler          (support/make-handler ctx state)
          input            (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"context/updated\"]}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames           (support/parse-frames out-lines)
          context-evt      (some #(when (= "context/updated" (:event %)) %) frames)
          session-slot     (some #(when (= session-id (:id %)) %) (get-in context-evt [:data :sessions]))]
      (is (some? context-evt) "context/updated must be emitted on subscribe")
      (is (= #{:active-session-id :sessions :session-tree-widget}
             (set (keys (:data context-evt)))))
      (is (vector? (get-in context-evt [:data :sessions])))
      (is (every? #(= #{:id :item-kind :name :display-name :worktree-path :runtime-state :is-streaming :is-active :parent-session-id :created-at :updated-at}
                      (set (keys %)))
                  (get-in context-evt [:data :sessions])))
      (is (= #{:extension-id :widget-id :placement :content-lines}
             (set (keys (get-in context-evt [:data :session-tree-widget])))))
      (is (= "psi-session" (get-in context-evt [:data :session-tree-widget :extension-id])))
      (is (= "session-tree" (get-in context-evt [:data :session-tree-widget :widget-id])))
      (is (vector? (get-in context-evt [:data :session-tree-widget :content-lines])))
      (is (= "hi" (:display-name session-slot)) "display-name should reflect inferred latest user message")
      (is (= (str user-ts) (:updated-at session-slot)) "updated-at should reflect latest message timestamp"))))

(deftest rpc-fork-emits-context-updated-test
  (testing "fork emits rehydration and context/updated with new session in sessions list"
    (let [cwd     (str (System/getProperty "java.io.tmpdir") "/psi-rpc-fork-" (java.util.UUID/randomUUID))
          _       (.mkdirs (java.io.File. cwd))
          [ctx session-id] (support/create-session-context {:cwd cwd})
          _       (session/dispatch-in! ctx :session/set-model {:session-id session-id :model {:provider "anthropic" :id "claude-sonnet"}} {:origin :core})
          ;; Append a message entry so fork has an entry-id to branch from
          entry   (persist/message-entry {:role "user" :content "hi"})
          _       (ss/append-journal-entry-in! ctx session-id entry)
          _       (ss/append-journal-entry-in! ctx session-id (persist/message-entry {:role "assistant" :content [{:type :text :text "reply"}]}))
          entry-id (:id entry)
          state   (atom {:transport {:ready? true :pending {}}
                         :connection {:subscribed-topics #{"context/updated" "session/rehydrated"}}})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"f1\" :kind :request :op \"fork\" :params {:entry-id \"" entry-id "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames      (support/parse-frames out-lines)
          fork-resp   (some #(when (and (= :response (:kind %)) (= "fork" (:op %))) %) frames)
          rehyd-evt   (some #(when (= "session/rehydrated" (:event %)) %) frames)
          context-evts (filter #(= "context/updated" (:event %)) frames)
          context-evt (first context-evts)
          new-sid     (get-in fork-resp [:data :session-id])]
      (is (some? fork-resp) "fork must return a response")
      (is (string? new-sid) "fork must return a new session-id")
      (is (some? rehyd-evt) "fork must emit session/rehydrated")
      (is (= [{:role "user" :content "hi"}
              {:role "assistant" :content [{:type :text :text "reply"}]}]
             (get-in rehyd-evt [:data :messages])))
      (is (= 2 (count context-evts)) "fork must emit subscribe bootstrap + navigation context/updated")
      (is (some? context-evt) "fork must emit context/updated")
      (is (= new-sid (get-in context-evt [:data :active-session-id]))
          "context/updated active-session-id must be the forked session")
      (is (some #(= new-sid (:id %)) (get-in context-evt [:data :sessions]))
          "context/updated sessions must include the forked session")
      (is (every? #(contains? % :worktree-path) (get-in context-evt [:data :sessions])))
      (is (every? #(contains? % :created-at) (get-in context-evt [:data :sessions])))
      (is (every? #(contains? % :updated-at) (get-in context-evt [:data :sessions])))))

  (testing "frontend_action_result select-session accepts fork-point payload and forks"
    (let [cwd      (str (System/getProperty "java.io.tmpdir") "/psi-rpc-frontend-fork-" (java.util.UUID/randomUUID))
          _        (.mkdirs (java.io.File. cwd))
          [ctx sid] (support/create-session-context {:cwd cwd})
          entry     (persist/message-entry {:role "user" :content "branch here"})
          _         (ss/append-journal-entry-in! ctx sid entry)
          _         (ss/append-journal-entry-in! ctx sid (persist/message-entry {:role "assistant" :content [{:type :text :text "reply here"}]}))
          state     (atom {:transport {:ready? true :pending {}}
                           :connection {:subscribed-topics #{"session/resumed" "session/rehydrated" "context/updated"}}})
          handler   (support/make-handler ctx state)
          input     (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"a1\" :kind :request :op \"frontend_action_result\" :params {:request-id \"req-1\" :action-name \"select-session\" :status \"submitted\" :value {:action/kind :fork-session :action/entry-id \"" (:id entry) "\" :action/session-id \"" sid "\"}}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames      (support/parse-frames out-lines)
          resumed-evt (some #(when (= "session/resumed" (:event %)) %) frames)
          rehyd-evt   (some #(when (= "session/rehydrated" (:event %)) %) frames)
          context-evt (some #(when (= "context/updated" (:event %)) %) frames)
          new-sid     (get-in resumed-evt [:data :session-id])]
      (is (some? resumed-evt))
      (is (some? rehyd-evt))
      (is (some? context-evt))
      (is (string? new-sid))
      (is (not= sid new-sid))
      (is (= new-sid (get-in context-evt [:data :active-session-id])))
      (is (= [{:role "user" :content "branch here"}
              {:role "assistant" :content [{:type :text :text "reply here"}]}]
             (get-in rehyd-evt [:data :messages])))))

  (testing "frontend_action_result select-session switches to existing child session and emits canonical navigation events"
    (let [[ctx sid] (support/create-session-context {:persist? false})
          qctx      (query/create-query-context)
          mutate    (fn [op params]
                      (get (query/query-in qctx
                                           {:psi/agent-session-ctx ctx}
                                           [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                       (not (contains? params :session-id))
                                                       (assoc :session-id sid)))])
                           op))
          _         (session/register-resolvers-in! qctx false)
          _         (session/register-mutations-in! qctx mutations/all-mutations true)
          child-id  (:psi.agent-session/session-id
                     (mutate 'psi.extension/create-child-session
                             {:session-name "child"
                              :tool-ids []
                              :preloaded-messages [{:role "user" :content [{:type :text :text "hello child"}]}
                                                   {:role "assistant" :content [{:type :text :text "child reply"}]}]}))
          state     (atom {:transport {:ready? true :pending {}}
                           :connection {:subscribed-topics #{"session/resumed" "session/rehydrated" "context/updated"}}})
          handler   (support/make-handler ctx state)
          input     (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"a1\" :kind :request :op \"frontend_action_result\" :params {:request-id \"req-2\" :action-name \"select-session\" :status \"submitted\" :value {:action/kind :switch-session :action/session-id \"" child-id "\"}}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames      (support/parse-frames out-lines)
          resumed-evt (some #(when (= "session/resumed" (:event %)) %) frames)
          rehyd-evt   (some #(when (= "session/rehydrated" (:event %)) %) frames)
          context-evt (some #(when (= "context/updated" (:event %)) %) frames)]
      (is (some? resumed-evt))
      (is (some? rehyd-evt))
      (is (some? context-evt))
      (is (= child-id (get-in resumed-evt [:data :session-id])))
      (is (= child-id (get-in context-evt [:data :active-session-id]))))))

(deftest rpc-model-and-thinking-picker-frontend-actions-test
  ;; Characterizes RPC-owned picker command protocol adaptation and submitted
  ;; frontend action results for model/thinking selection.
  (testing "/model command emits a frontend action request with model picker payload"
    (let [[ctx session-id] (support/create-session-context)
          state            (atom {:transport {:ready? true :pending {}}
                                  :connection {:focus-session-id session-id
                                               :subscribed-topics #{"ui/frontend-action-requested"}}})
          handler          (support/make-handler ctx state)
          input            (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/model\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames           (support/parse-frames out-lines)
          command-response (some #(when (and (= :response (:kind %))
                                             (= "command" (:op %))) %)
                                 frames)
          action-event     (some #(when (= "ui/frontend-action-requested" (:event %)) %) frames)
          action           (get-in action-event [:data :ui/action])
          items            (:ui/items action)
          model-keys       (mapv (juxt #(get-in % [:ui.item/value :provider])
                                       #(get-in % [:ui.item/value :id]))
                                 items)]
      (is (= {:accepted true :handled true}
             (:data command-response)))
      (is (= "c1" (get-in action-event [:data :request-id])))
      (is (= :select-model (:ui/action-name action)))
      (is (= "Select a model" (:ui/prompt action)))
      (is (= {:submit/kind :set-model} (:ui/on-submit action)))
      (is (seq items))
      (is (= model-keys (sort model-keys))
          "RPC model picker payload preserves backend-owned provider/id order")
      (is (some #(= ["openai" "gpt-5.4-mini"] %) model-keys))
      (is (every? #(= (:ui.item/value %)
                      (select-keys (:ui.item/meta %) [:provider :id]))
                  items))))

  (testing "/thinking command emits a frontend action request with thinking picker payload"
    (let [[ctx session-id] (support/create-session-context)
          state            (atom {:transport {:ready? true :pending {}}
                                  :connection {:focus-session-id session-id
                                               :subscribed-topics #{"ui/frontend-action-requested"}}})
          handler          (support/make-handler ctx state)
          input            (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/thinking\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames           (support/parse-frames out-lines)
          command-response (some #(when (and (= :response (:kind %))
                                             (= "command" (:op %))) %)
                                 frames)
          action-event     (some #(when (= "ui/frontend-action-requested" (:event %)) %) frames)
          action           (get-in action-event [:data :ui/action])
          items            (:ui/items action)]
      (is (= {:accepted true :handled true}
             (:data command-response)))
      (is (= "c1" (get-in action-event [:data :request-id])))
      (is (= :select-thinking-level (:ui/action-name action)))
      (is (= "Select a thinking level" (:ui/prompt action)))
      (is (= {:submit/kind :set-thinking-level} (:ui/on-submit action)))
      (is (= ["off" "minimal" "low" "medium" "high" "xhigh"]
             (mapv :ui.item/value items)))))

  (testing "submitted select-model frontend action updates model and emits command/session snapshots"
    (let [[ctx session-id] (support/create-session-context)
          state            (atom {:transport {:ready? true :pending {}}
                                  :connection {:focus-session-id session-id
                                               :subscribed-topics #{"command-result"
                                                                    "session/updated"
                                                                    "footer/updated"}}})
          handler          (support/make-handler ctx state)
          input            (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                "{:id \"a1\" :kind :request :op \"frontend_action_result\" :params {:request-id \"req-model\" :action-name \"select-model\" :status \"submitted\" :value {:provider \"openai\" :id \"gpt-5.4-mini\"}}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames           (support/parse-frames out-lines)
          response         (some #(when (and (= :response (:kind %))
                                             (= "frontend_action_result" (:op %))) %)
                                 frames)
          command-result   (some #(when (= "command-result" (:event %)) %) frames)
          session-updated  (some #(when (= "session/updated" (:event %)) %) frames)
          footer-updated   (some #(when (= "footer/updated" (:event %)) %) frames)]
      (is (= {:accepted true :request-id "req-model"}
             (:data response)))
      (is (= {:type "text"
              :message "✓ Model set to openai gpt-5.4-mini"}
             (:data command-result)))
      (is (= "openai" (get-in session-updated [:data :model-provider])))
      (is (= "gpt-5.4-mini" (get-in session-updated [:data :model-id])))
      (is (some? footer-updated))))

  (testing "submitted select-model frontend action rejects unsupported runtime models without mutating session model"
    (let [oauth-ctx        (oauth/create-null-context
                            {:credentials {:openai {:type :oauth
                                                    :access "tok"
                                                    :refresh "ref"
                                                    :expires 99999999999999}}})
          [ctx session-id] (support/create-session-context {:oauth-ctx oauth-ctx})
          original         (:model (ss/get-session-data-in ctx session-id))
          state            (atom {:transport {:ready? true :pending {}}
                                  :connection {:focus-session-id session-id
                                               :subscribed-topics #{"command-result"
                                                                    "session/updated"
                                                                    "footer/updated"}}})
          handler          (support/make-handler ctx state)
          input            (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                "{:id \"a1\" :kind :request :op \"frontend_action_result\" :params {:request-id \"req-model\" :action-name \"select-model\" :status \"submitted\" :value {:provider \"openai\" :id \"gpt-5.6\"}}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames           (support/parse-frames out-lines)
          response         (some #(when (and (= :response (:kind %))
                                             (= "frontend_action_result" (:op %))) %)
                                 frames)
          command-result   (some #(when (= "command-result" (:event %)) %) frames)]
      (is (= {:accepted true :request-id "req-model"}
             (:data response)))
      (is (= "unsupported_model" (get-in command-result [:data :type])))
      (is (str/includes? (get-in command-result [:data :message])
                         "Unsupported model: openai gpt-5.6"))
      (is (str/includes? (get-in command-result [:data :message])
                         "not supported for OpenAI OAuth"))
      (is (= "openai" (get-in command-result [:data :provider])))
      (is (= "gpt-5.6" (get-in command-result [:data :model-id])))
      (is (= original (:model (ss/get-session-data-in ctx session-id))))))

  (testing "submitted select-thinking-level frontend action updates thinking and emits command/session snapshots"
    (let [[ctx session-id] (support/create-session-context)
          state            (atom {:transport {:ready? true :pending {}}
                                  :connection {:focus-session-id session-id
                                               :subscribed-topics #{"command-result"
                                                                    "session/updated"
                                                                    "footer/updated"}}})
          handler          (support/make-handler ctx state)
          input            (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                "{:id \"a1\" :kind :request :op \"frontend_action_result\" :params {:request-id \"req-thinking\" :action-name \"select-thinking-level\" :status \"submitted\" :value \"high\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames           (support/parse-frames out-lines)
          response         (some #(when (and (= :response (:kind %))
                                             (= "frontend_action_result" (:op %))) %)
                                 frames)
          command-result   (some #(when (= "command-result" (:event %)) %) frames)
          session-updated  (some #(when (= "session/updated" (:event %)) %) frames)
          footer-updated   (some #(when (= "footer/updated" (:event %)) %) frames)]
      (is (= {:accepted true :request-id "req-thinking"}
             (:data response)))
      (is (= {:type "text"
              :message "✓ Thinking level set to high"}
             (:data command-result)))
      (is (= "high" (get-in session-updated [:data :thinking-level])))
      (is (some? footer-updated)))))

(deftest rpc-frontend-action-cancelled-and-failed-result-test
  ;; Characterizes RPC frontend_action_result cancelled/failed observable payloads.
  (testing "cancelled frontend action emits text command-result and accepted response"
    (let [[ctx session-id]    (support/create-session-context)
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:focus-session-id session-id
                                                  :subscribed-topics #{"command-result"
                                                                       "session/updated"
                                                                       "footer/updated"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"a1\" :kind :request :op \"frontend_action_result\" :params {:request-id \"req-cancel\" :action-name \"select-model\" :status \"cancelled\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          response            (some #(when (and (= :response (:kind %))
                                                (= "frontend_action_result" (:op %))) %)
                                    frames)
          command-result      (some #(when (= "command-result" (:event %)) %) frames)]
      (is (= {:accepted true} (:data response)))
      (is (= {:type "text"
              :message "Cancelled select-model."}
             (:data command-result)))
      (is (not-any? #(= "session/updated" (:event %)) frames))
      (is (not-any? #(= "footer/updated" (:event %)) frames))))

  (testing "failed frontend action emits error command-result and accepted response"
    (let [[ctx session-id]    (support/create-session-context)
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:focus-session-id session-id
                                                  :subscribed-topics #{"command-result"
                                                                       "session/updated"
                                                                       "footer/updated"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"a1\" :kind :request :op \"frontend_action_result\" :params {:request-id \"req-failed\" :action-name \"select-model\" :status \"failed\" :error-message \"Picker exploded\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          response            (some #(when (and (= :response (:kind %))
                                                (= "frontend_action_result" (:op %))) %)
                                    frames)
          command-result      (some #(when (= "command-result" (:event %)) %) frames)]
      (is (= {:accepted true} (:data response)))
      (is (= {:type "error"
              :message "Picker exploded"}
             (:data command-result)))
      (is (not-any? #(= "session/updated" (:event %)) frames))
      (is (not-any? #(= "footer/updated" (:event %)) frames)))))

(deftest rpc-new-session-emits-context-updated-test
  (testing "new_session emits context/updated event"
    (let [[ctx session-id] (support/create-session-context)
          state   (atom {:transport {:ready? true :pending {}}
                         :connection {:subscribed-topics #{"context/updated"}}})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"n1\" :kind :request :op \"new_session\" :params {:session-id \"" session-id "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames    (support/parse-frames out-lines)
          new-resp  (some #(when (and (= :response (:kind %)) (= "new_session" (:op %))) %) frames)
          context-evts (filter #(= "context/updated" (:event %)) frames)
          context-evt  (first context-evts)
          new-sid   (get-in new-resp [:data :session-id])]
      (is (= 2 (count context-evts)) "new_session must emit subscribe bootstrap + navigation context/updated")
      (is (some? context-evt) "new_session must emit context/updated")
      (is (= new-sid (get-in context-evt [:data :active-session-id])))
      (is (vector? (get-in context-evt [:data :sessions])))
      (is (every? #(contains? % :worktree-path) (get-in context-evt [:data :sessions])))
      (is (every? #(contains? % :created-at) (get-in context-evt [:data :sessions])))
      (is (every? #(contains? % :updated-at) (get-in context-evt [:data :sessions]))))))

(deftest rpc-create-child-session-emits-context-updated-without-tree-test
  (testing "child session creation emits context/updated to subscribed clients without manual refresh"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          qctx             (query/create-query-context)
          mutate           (fn [op params]
                             (get (query/query-in qctx
                                                  {:psi/agent-session-ctx ctx}
                                                  [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                              (not (contains? params :session-id))
                                                              (assoc :session-id session-id)))])
                                  op))
          state            (atom {:transport {:ready? true :pending {}}
                                  :connection {:focus-session-id session-id
                                               :subscribed-topics #{"context/updated"}
                                               :event-seq 0}
                                  :workers {}})
          emitted          (atom [])
          emit-frame!      (fn [frame] (swap! emitted conj frame))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (rpc.projections/ensure-projection-listener! ctx emit-frame! state)
      (try
        (let [child-id (:psi.agent-session/session-id
                        (mutate 'psi.extension/create-child-session
                                {:session-name "child"
                                 :tool-ids []
                                 :thinking-level :off}))
              context-evt (some #(when (= "context/updated" (:event %)) %) @emitted)]
          (is (some? context-evt))
          (is (= session-id (get-in context-evt [:data :active-session-id])))
          (is (some #(= child-id (:id %)) (get-in context-evt [:data :sessions]))))
        (finally
          (rpc.projections/unregister-projection-listener! ctx state))))))

(deftest rpc-out-of-band-child-session-create-streams-context-updated-test
  (testing "after subscribe, out-of-band child session creation streams context/updated without a refresh request"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          qctx             (query/create-query-context)
          mutate           (fn [op params]
                             (get (query/query-in qctx
                                                  {:psi/agent-session-ctx ctx}
                                                  [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                              (not (contains? params :session-id))
                                                              (assoc :session-id session-id)))])
                                  op))
          state            (atom {:transport {:ready? true :pending {}}
                                  :connection {:subscribed-topics #{}}})
          handler          (support/make-handler ctx state)
          in-reader        (java.io.PipedReader.)
          in-writer        (java.io.PipedWriter. in-reader)
          out-writer       (java.io.StringWriter.)
          err-writer       (java.io.StringWriter.)
          write-line!      (fn [line]
                             (.write in-writer (str line "\n"))
                             (.flush in-writer))
          loop-future      (future
                             (rpc/run-stdio-loop! {:in              in-reader
                                                   :out             out-writer
                                                   :err             err-writer
                                                   :state           state
                                                   :request-handler handler}))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (try
        (write-line! "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}")
        (write-line! "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"context/updated\"]}}")
        (let [bootstrap-context (support/await-frame!
                                 out-writer
                                 #(when (= "context/updated" (:event %)) %)
                                 1000)]
          (is (not= support/timeout-token bootstrap-context) "timed out waiting for subscribe bootstrap context/updated")
          (let [child-id (:psi.agent-session/session-id
                          (mutate 'psi.extension/create-child-session
                                  {:session-name "child"
                                   :tool-ids []
                                   :thinking-level :off}))
                context-evts (support/await-frames!
                              out-writer
                              (fn [frames]
                                (let [context-evts (filter #(= "context/updated" (:event %)) frames)
                                      latest (last context-evts)]
                                  (when (and (<= 2 (count context-evts))
                                             (= session-id (get-in latest [:data :active-session-id]))
                                             (some #(= child-id (:id %)) (get-in latest [:data :sessions])))
                                    context-evts)))
                              1000)]
            (.close in-writer)
            (deref loop-future 500 support/timeout-token)
            (is (not= support/timeout-token context-evts) "timed out waiting for context/updated with new child session")
            (let [latest (last context-evts)]
              (is (<= 2 (count context-evts)))
              (is (= session-id (get-in latest [:data :active-session-id])))
              (is (some #(= child-id (:id %)) (get-in latest [:data :sessions]))))))
        (finally
          (future-cancel loop-future)
          (try (.close in-writer) (catch Exception _ nil))
          (try (.close in-reader) (catch Exception _ nil)))))))

(deftest rpc-set-model-scope-test
  (testing "set_model accepts explicit session scope without persisting project preferences"
    (let [cwd        (str (System/getProperty "java.io.tmpdir") "/psi-rpc-model-scope-" (java.util.UUID/randomUUID))
          _          (.mkdirs (java.io.File. cwd))
          [ctx sid]  (support/create-session-context {:cwd cwd})
          state      (atom {:transport {:ready? true :pending {}}
                            :connection {}})
          handler    (support/make-handler ctx state)
          input      (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                          "{:id \"m1\" :kind :request :op \"set_model\" :params {:provider \"openai\" :model-id \"gpt-5.3-codex\" :scope \"session\" :session-id \"" sid "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames     (support/parse-frames out-lines)
          resp       (some #(when (and (= :response (:kind %)) (= "set_model" (:op %))) %) frames)]
      (is (some? resp))
      (is (= true (:ok resp)))
      (is (= "openai" (get-in (ss/get-session-data-in ctx sid) [:model :provider])))
      (is (false? (.exists (java.io.File. (str cwd "/.psi/preferences.local.edn")))))))

  (testing "set_model rejects invalid explicit scope"
    (let [[ctx sid] (support/create-session-context)
          state     (atom {:transport {:ready? true :pending {}}
                           :connection {}})
          handler   (support/make-handler ctx state)
          input     (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"m1\" :kind :request :op \"set_model\" :params {:provider \"openai\" :model-id \"gpt-5.3-codex\" :scope \"bogus\" :session-id \"" sid "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames    (support/parse-frames out-lines)
          err       (some #(when (= :error (:kind %)) %) frames)]
      (is (some? err))
      (is (= "request/invalid-params" (:error-code err)))
      (is (= "invalid request parameter :scope: session, project, or user"
             (:error-message err)))))

  (testing "set_model rejects unsupported runtime models without mutating session model"
    (let [oauth-ctx (oauth/create-null-context
                     {:credentials {:openai {:type :oauth
                                             :access "tok"
                                             :refresh "ref"
                                             :expires 99999999999999}}})
          [ctx sid] (support/create-session-context {:oauth-ctx oauth-ctx})
          original  (:model (ss/get-session-data-in ctx sid))
          state     (atom {:transport {:ready? true :pending {}}
                           :connection {}})
          handler   (support/make-handler ctx state)
          input     (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"m1\" :kind :request :op \"set_model\" :params {:provider \"openai\" :model-id \"gpt-5.6\" :scope \"session\" :session-id \"" sid "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames    (support/parse-frames out-lines)
          err       (some #(when (and (= :error (:kind %)) (= "set_model" (:op %))) %) frames)]
      (is (some? err))
      (is (= "request/unsupported-model" (:error-code err)))
      (is (str/includes? (:error-message err) "Unsupported model: openai gpt-5.6"))
      (is (str/includes? (:error-message err) "not supported for OpenAI OAuth"))
      (is (= original (:model (ss/get-session-data-in ctx sid))))))

  (testing "picker-backed model selection preserves omitted-scope/default helper semantics"
    (let [[ctx sid] (support/create-session-context)
          captured  (atom nil)
          emit!     (fn [& _])]
      (with-redefs [session/set-model-in! (fn [ctx' session-id' model & [scope]]
                                            (reset! captured {:ctx ctx'
                                                              :session-id session-id'
                                                              :model model
                                                              :scope scope})
                                            {:model model})]
        (psi.rpc.session.command-pickers/handle-model-selection!
         ctx sid
         (fn [ctx' provider id]
           (when (and (= ctx' ctx)
                      (= [provider id] ["openai" "gpt-5.3-codex"]))
             {:provider :openai :id "gpt-5.3-codex" :supports-reasoning true}))
         emit!
         {:provider "openai" :id "gpt-5.3-codex"}))
      (is (= {:ctx ctx
              :session-id sid
              :model {:provider "openai"
                      :id "gpt-5.3-codex"
                      :reasoning true}
              :scope nil}
             @captured))))

  (testing "picker-backed model selection rejects unsupported resolved models without persisting"
    (let [[ctx sid]  (support/create-session-context)
          emitted    (atom [])
          set-model? (atom false)
          emit!      (fn [event payload]
                       (swap! emitted conj {:event event :payload payload}))]
      (with-redefs [session/set-model-in! (fn [& _]
                                            (reset! set-model? true)
                                            (throw (ex-info "set-model-in! should not be called" {})))]
        (psi.rpc.session.command-pickers/handle-model-selection!
         ctx sid
         (fn [ctx' provider id]
           (when (and (= ctx' ctx)
                      (= [provider id] ["openai" "gpt-5.6"]))
             {:provider :openai
              :id "gpt-5.6"
              :runtime/unsupported? true
              :runtime/unsupported-message "gpt-5.6 is not supported for OpenAI OAuth"}))
         emit!
         {:provider "openai" :id "gpt-5.6"}))
      (is (false? @set-model?))
      (is (= [{:event "command-result"
               :payload {:type "unsupported_model"
                         :message "Unsupported model: openai gpt-5.6 — gpt-5.6 is not supported for OpenAI OAuth"
                         :provider "openai"
                         :model-id "gpt-5.6"}}]
             @emitted)))))

(deftest rpc-e2e-handshake-query-and-streaming-test
  (testing "handshake -> query_eql -> prompt with interleaved events"
    (let [[ctx _] (support/create-session-context)
          state (atom {:transport {:ready? true :pending {}}
                       :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                       :execute-prepared-request-fn (fn [_ai-ctx _ctx _session-id _prepared-request progress-queue]
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :text-delta :text "Hello" :type :agent-event})
                                                      (support/assistant-msg->execution-result _session-id {:role "assistant" :content [{:type :text :text "Hello final"}] :stop-reason :stop :usage {:total-tokens 2}}))})
          handler (support/make-handler ctx state)
          input (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"q1\" :kind :request :op \"query_eql\" :params {:query \"[:psi.graph/domain-coverage :psi.memory/status]\"}}\n"
                     "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/delta\" \"assistant/message\" \"session/updated\" \"footer/updated\"]}}\n"
                     "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"hi\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state 250)
          frames (support/parse-frames out-lines)
          handshake-frame (some #(when (and (= :response (:kind %)) (= "handshake" (:op %))) %) frames)
          query-frame (some #(when (and (= :response (:kind %)) (= "query_eql" (:op %))) %) frames)
          prompt-response-index (first (keep-indexed (fn [i f] (when (and (= :response (:kind f)) (= "prompt" (:op f))) i)) frames))
          event-indexes (vec (keep-indexed (fn [i f] (when (= :event (:kind f)) i)) frames))]
      (is handshake-frame)
      (is (= true (:ok handshake-frame)))
      (is query-frame)
      (is (= true (:ok query-frame)))
      (is (contains? (get-in query-frame [:data :result]) :psi.graph/domain-coverage))
      (is (contains? (get-in query-frame [:data :result]) :psi.memory/status))
      (is (number? prompt-response-index))
      (is (seq event-indexes))
      (is (some #(< prompt-response-index %) event-indexes))
      (is (some #(= "assistant/delta" (:event %)) (filter #(= :event (:kind %)) frames))))))

