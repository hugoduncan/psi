(ns psi.rpc-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.mutations :as mutations]
   [psi.rpc :as rpc]
   [psi.rpc.session.projections :as rpc.projections]
   [psi.rpc.events :as rpc.events]
   [psi.agent-session.runtime :as runtime]
   [psi.query.core :as query]
   [psi.rpc-test-support :as support]))

(deftest footer-updated-payload-uses-default-footer-projection-values-test
  (testing "footer payload mirrors default footer path/stats/status composition"
    (let [home    (System/getProperty "user.home")
          cwd     (str home "/projects/hugoduncan/psi/psi-main")
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
                                                     {:extension-id "a" :text "Clojure-LSP\nclojure-lsp"}]})]
                    (rpc.events/footer-updated-payload ctx session-id))]
      (is (= "~/projects/hugoduncan/psi/psi-main (master) • xhig"
             (:path-line payload)))
      (is (= ["↑172k" "↓17k" "CR5.2M" "CW1.2k" "$1.444" "31.9%/272k (auto)"]
             (:usage-parts payload)))
      (is (= "(openai-codex) gpt-5.3-codex • thinking high"
             (:model-text payload)))
      (is (= "Clojure-LSP clojure-lsp TS+ESL,Prett"
             (:status-line payload))))))

(deftest footer-updated-payload-prefers-session-display-name-test
  (testing "footer payload uses derived display name when explicit session name is absent"
    (let [home    (System/getProperty "user.home")
          cwd     (str home "/projects/hugoduncan/psi/psi-main")
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
      (is (= "~/projects/hugoduncan/psi/psi-main (master) • Investigate failing tests"
             (:path-line payload)))
      (is (= ["?/272k"]
             (:usage-parts payload)))
      (is (= "(openai-codex) gpt-5.3-codex • thinking high"
             (:model-text payload))))))

(deftest session-updated-payload-includes-model-metadata-test
  (testing "session payload includes model metadata for frontend header projection"
    (let [[ctx sid] (support/create-session-context)
          _         (dispatch/dispatch! ctx :session/set-model
                                        {:session-id sid
                                         :model {:provider "openai"
                                                 :id "gpt-5.3-codex"
                                                 :reasoning true}}
                                        {:origin :core})
          _         (dispatch/dispatch! ctx :session/set-thinking-level
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
      (is (= "high" (:effective-reasoning-effort payload)))
      (is (= "(openai) gpt-5.3-codex • thinking high" (:header-model-label payload)))
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
          _          (ss/journal-append-in! ctx session-id
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
          _          (dispatch/dispatch! ctx :session/set-model
                                         {:session-id session-id :model {:provider "openai" :id "gpt-5.3-codex" :reasoning true}}
                                         {:origin :core})
          _          (dispatch/dispatch! ctx :session/set-thinking-level {:session-id session-id :level :high} {:origin :core})
          _          (dispatch/dispatch! ctx :session/update-context-usage {:session-id session-id :tokens 4000 :window 100000} {:origin :core})
          _          (ss/journal-append-in! ctx session-id
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
          _                (ss/journal-append-in! ctx session-id
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
          _       (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model {:provider "anthropic" :id "claude-sonnet"}} {:origin :core})
          ;; Append a message entry so fork has an entry-id to branch from
          entry   (persist/message-entry {:role "user" :content "hi"})
          _       (ss/journal-append-in! ctx session-id entry)
          _       (ss/journal-append-in! ctx session-id (persist/message-entry {:role "assistant" :content [{:type :text :text "reply"}]}))
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
          _         (ss/journal-append-in! ctx sid entry)
          _         (ss/journal-append-in! ctx sid (persist/message-entry {:role "assistant" :content [{:type :text :text "reply here"}]}))
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
                              :tool-defs []
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
                                 :tool-defs []
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
        (Thread/sleep 100)
        (let [child-id (:psi.agent-session/session-id
                        (mutate 'psi.extension/create-child-session
                                {:session-name "child"
                                 :tool-defs []
                                 :thinking-level :off}))]
          (Thread/sleep 150)
          (.close in-writer)
          (deref loop-future 500 nil)
          (let [frames        (support/parse-frames (->> (str/split-lines (str out-writer))
                                                         (remove str/blank?)
                                                         vec))
                context-evts (filter #(= "context/updated" (:event %)) frames)
                latest       (last context-evts)]
            (is (<= 2 (count context-evts)))
            (is (= session-id (get-in latest [:data :active-session-id])))
            (is (some #(= child-id (:id %)) (get-in latest [:data :sessions])))))
        (finally
          (future-cancel loop-future)
          (try (.close in-writer) (catch Exception _ nil))
          (try (.close in-reader) (catch Exception _ nil)))))))

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

