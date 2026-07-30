(ns psi.rpc-session-navigation-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-core.core :as agent]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.mutations :as mutations]
   [psi.session-persistence.core :as persist]
   [psi.session-journal.store :as journal-store]
   [psi.session-state.state :as ss]
   [psi.rpc-test-support :as support]))

(deftest rpc-extension-command-after-new-emits-assistant-message-for-new-session-test
  (testing "subscribe + /new + extension command emits assistant/message for the new active session"
    (let [[ctx session-id] (support/create-session-context {:mutations mutations/all-mutations
                                                            :event-queue (java.util.concurrent.LinkedBlockingQueue.)})
          reg              (:extension-registry ctx)
          ext-path         "/ext/which-session"
          _                (ext/register-extension-in! reg ext-path)
          runtime-fns*     (runtime-fns/make-extension-runtime-fns ctx session-id ext-path)
          api              (ext/create-extension-api reg ext-path runtime-fns*)
          _                ((:register-command api)
                            "which-session"
                            {:description "Append implicit extension session id"
                             :handler     (fn [_args]
                                            ((:append-message api)
                                             "assistant"
                                             (:psi.agent-session/session-id
                                              ((:query api) [:psi.agent-session/session-id]))))})
          state            (atom {:transport {:ready? true :pending {}}
                                  :connection {:subscribed-topics #{"assistant/message"
                                                                    "session/resumed"
                                                                    "session/rehydrated"
                                                                    "command-result"
                                                                    "footer/updated"}}})
          handler          (support/make-handler ctx state)
          input            (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\" \"session/resumed\" \"session/rehydrated\" \"command-result\" \"footer/updated\"]}}\n"
                                "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/new\"}}\n"
                                "{:id \"c2\" :kind :request :op \"command\" :params {:text \"/which-session\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state 80)
          frames           (support/parse-frames out-lines)
          events           (filter #(= :event (:kind %)) frames)
          rehydrate-evt    (some #(when (= "session/rehydrated" (:event %)) %) events)
          new-session-id   (get-in rehydrate-evt [:data :session-id])
          assistant-events (filter #(= "assistant/message" (:event %)) events)
          matching-msg     (some #(when (= new-session-id (get-in % [:data :text])) %) assistant-events)]
      (is (some? rehydrate-evt))
      (is (string? new-session-id))
      (is (some? matching-msg)
          "extension assistant message should carry the new session id after /new"))))

(deftest rpc-session-resume-and-rehydrate-events-test
  (testing "new_session emits session/resumed and session/rehydrated canonical events"
    (let [[ctx session-id]    (support/create-session-context)
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:subscribed-topics #{"session/resumed" "session/rehydrated"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"n1\" :kind :request :op \"new_session\" :params {:session-id \"" session-id "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          events              (filter #(= :event (:kind %)) frames)
          event-topics        (set (map :event events))]
      (is (contains? event-topics "session/resumed"))
      (is (contains? event-topics "session/rehydrated"))
      (is (some #(contains? (:data %) :session-id) events))
      (is (some #(contains? (:data %) :messages) events))))

  (testing "command /new emits session/resumed and session/rehydrated canonical events"
    (let [[ctx _]             (support/create-session-context {:session-defaults {:model {:provider "openai"
                                                                                          :id "gpt-5.4"
                                                                                          :reasoning false}}})
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:subscribed-topics #{"session/resumed" "session/rehydrated" "command-result" "footer/updated"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/new\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          events              (filter #(= :event (:kind %)) frames)
          event-topics        (set (map :event events))
          command-result      (some #(when (= "command-result" (:event %)) %) events)
          footer-event        (some #(when (= "footer/updated" (:event %)) %) events)]
      (is (contains? event-topics "session/resumed"))
      (is (contains? event-topics "session/rehydrated"))
      (is (some? command-result))
      (is (= "new_session" (get-in command-result [:data :type])))
      (is (some? footer-event))
      (is (= "(openai) gpt-5.4"
             (get-in footer-event [:data :model-text])))))

  (testing "command /new with callback emits callback rehydration and command result"
    (let [[ctx session-id] (support/create-session-context)
          callback-sources (atom [])
          state            (atom {:transport {:ready? true :pending {}}
                                  :connection {:focus-session-id session-id}
                                  :on-new-session!
                                  (fn [source-session-id]
                                    (swap! callback-sources conj source-session-id)
                                    (let [sd (session/new-session-in! ctx source-session-id {})]
                                      {:session-id (:session-id sd)
                                       :agent-messages [{:role "assistant"
                                                         :content [{:type :text
                                                                    :text "command startup reply"}]}]
                                       :messages [{:role :assistant
                                                   :text "legacy command startup reply"}]
                                       :tool-calls {"call-command" {:name "bash"}}
                                       :tool-order ["call-command"]}))})
          handler          (support/make-handler ctx state)
          input            (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"session/resumed\" \"session/rehydrated\" \"command-result\"]}}\n"
                                "{:id \"c-new-callback\" :kind :request :op \"command\" :params {:text \"/new\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state 300)
          frames           (support/parse-frames out-lines)
          command-response (some #(when (and (= :response (:kind %))
                                             (= "c-new-callback" (:id %))
                                             (= "command" (:op %))) %)
                                 frames)
          events           (filter #(and (= :event (:kind %))
                                         (= "c-new-callback" (:id %)))
                                   frames)
          rehydrated       (some #(when (= "session/rehydrated" (:event %)) %) events)
          resumed          (some #(when (= "session/resumed" (:event %)) %) events)
          command-result   (some #(when (= "command-result" (:event %)) %) events)
          new-session-id   (get-in rehydrated [:data :session-id])]
      (is (= [session-id] @callback-sources)
          "command /new callback should receive the source session id")
      (is (= {:accepted true :handled true}
             (:data command-response))
          "command /new should return the accepted command response")
      (is (string? new-session-id)
          "command /new must rehydrate the callback-created session")
      (is (= new-session-id (get-in resumed [:data :session-id]))
          "resumed and rehydrated events must describe the same callback-created session")
      (is (= 1 (get-in resumed [:data :message-count]))
          "resumed event must count callback-provided startup transcript messages")
      (is (= [{:role "assistant"
               :content [{:type :text :text "command startup reply"}]}]
             (get-in rehydrated [:data :messages]))
          "rehydrated event must prefer callback-provided agent transcript messages")
      (is (= {"call-command" {:name "bash"}}
             (get-in rehydrated [:data :tool-calls]))
          "rehydrated event must include callback-provided tool metadata")
      (is (= ["call-command"]
             (get-in rehydrated [:data :tool-order]))
          "rehydrated event must include callback-provided tool ordering")
      (is (= new-session-id (get-in @state [:connection :focus-session-id]))
          "RPC focus should move to the callback-created new session")
      (is (= {:type "new_session"
              :message "[New session started]"}
             (:data command-result))
          "command-result output should be emitted through the shared new-session helper")))

  (testing "command /resume <path> emits session/resumed and session/rehydrated canonical events"
    (let [cwd                 (str (System/getProperty "java.io.tmpdir") "/psi-rpc-resume-" (java.util.UUID/randomUUID))
          _                   (.mkdirs (java.io.File. cwd))
          [ctx _]             (support/create-session-context {:cwd cwd :persist? true :session-root cwd})
          sd1                 (session/new-session-in! ctx nil {})
          session-id          (:session-id sd1)
          path1               (:session-file sd1)
          _                   (journal-store/flush-journal! (java.io.File. path1)
                                                            session-id
                                                            cwd
                                                            nil
                                                            nil
                                                            [(persist/message-entry {:role "user" :content "hi"})
                                                             (persist/message-entry {:role "assistant" :content [{:type :text :text "there"}]})])
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:subscribed-topics #{"session/resumed" "session/rehydrated" "command-result"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/resume " path1 "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          events              (filter #(= :event (:kind %)) frames)
          event-topics        (set (map :event events))]
      (is (contains? event-topics "session/resumed"))
      (is (contains? event-topics "session/rehydrated"))
      (is (= path1 (get-in (some #(when (= "session/resumed" (:event %)) %) events)
                           [:data :session-file])))
      (is (= [{:role "user" :content "hi"}
              {:role "assistant" :content [{:type :text :text "there"}]}]
             (get-in (some #(when (= "session/rehydrated" (:event %)) %) events)
                     [:data :messages])))))

  (testing "command /resume <path> emits the trailing snapshot stamped with the newly-focused session"
    ;; Pins the load-bearing handle-command! trailing-snapshot fix
    ;; (commands.clj: (or (focus-session-id state) session-id)) for the /resume
    ;; focus-switch path. Subscribing to session/updated + footer/updated forces
    ;; the trailing snapshot to pass the focus gate only if it is stamped with
    ;; the POST-command focus (the resumed session). A regression to the bare
    ;; pre-command session-id would gate the snapshot out and fail these asserts.
    (let [cwd                 (str (System/getProperty "java.io.tmpdir") "/psi-rpc-resume-snap-" (java.util.UUID/randomUUID))
          _                   (.mkdirs (java.io.File. cwd))
          [ctx _]             (support/create-session-context {:cwd cwd :persist? true :session-root cwd})
          sd1                 (session/new-session-in! ctx nil {})
          resumed-sid         (:session-id sd1)
          path1               (:session-file sd1)
          _                   (journal-store/flush-journal! (java.io.File. path1)
                                                            resumed-sid
                                                            cwd
                                                            nil
                                                            nil
                                                            [(persist/message-entry {:role "assistant" :content [{:type :text :text "resumed"}]})])
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:subscribed-topics #{"session/resumed" "session/rehydrated"
                                                                       "session/updated" "footer/updated"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/resume " path1 "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          events              (filter #(= :event (:kind %)) frames)
          session-updated     (some #(when (= "session/updated" (:event %)) %) events)
          footer-updated      (some #(when (= "footer/updated" (:event %)) %) events)]
      (is (some? session-updated)
          "trailing session/updated snapshot must be emitted after /resume focus switch")
      (is (= resumed-sid (get-in session-updated [:data :session-id]))
          "trailing session/updated snapshot must be stamped with the newly-focused (resumed) session")
      (is (some? footer-updated)
          "trailing footer/updated snapshot must be emitted after /resume focus switch")
      (is (= resumed-sid (get-in footer-updated [:data :session-id]))
          "trailing footer/updated snapshot must be stamped with the newly-focused (resumed) session")))

  (testing "command /tree <prefix> emits the trailing snapshot stamped with the newly-focused session"
    ;; Companion to the /resume case: pins the same L160 fix for the /tree
    ;; focus-switch path. The pre-existing /tree <prefix> test does not
    ;; subscribe to session/updated or footer/updated, so a regression of the
    ;; trailing snapshot to the stale pre-command session-id would go
    ;; undetected there. Here the snapshot must be stamped with the switched-to
    ;; child session to pass the focus gate.
    (let [[ctx session-id]    (support/create-session-context {:persist? false})
          child-id            (:session-id (session/new-session-in! ctx session-id {}))
          prefix              (subs child-id 0 8)
          _                   (ss/append-journal-entry-in! ctx child-id
                                                           (persist/message-entry {:role "assistant"
                                                                                   :content [{:type :text :text "child"}]}))
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:focus-session-id session-id
                                                  :subscribed-topics #{"session/resumed" "session/rehydrated"
                                                                       "session/updated" "footer/updated"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/tree " prefix "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          events              (filter #(= :event (:kind %)) frames)
          session-updated     (some #(when (= "session/updated" (:event %)) %) events)
          footer-updated      (some #(when (= "footer/updated" (:event %)) %) events)]
      (is (some? session-updated)
          "trailing session/updated snapshot must be emitted after /tree focus switch")
      (is (= child-id (get-in session-updated [:data :session-id]))
          "trailing session/updated snapshot must be stamped with the newly-focused (child) session")
      (is (some? footer-updated)
          "trailing footer/updated snapshot must be emitted after /tree focus switch")
      (is (= child-id (get-in footer-updated [:data :session-id]))
          "trailing footer/updated snapshot must be stamped with the newly-focused (child) session")))

  (testing "command /tree <session-id> emits session/resumed and session/rehydrated canonical events"
    (let [cwd                 (str (System/getProperty "java.io.tmpdir") "/psi-rpc-tree-" (java.util.UUID/randomUUID))
          _                   (.mkdirs (java.io.File. cwd))
          [ctx _]             (support/create-session-context {:cwd cwd :persist? true :session-root cwd})
          sd1                 (session/new-session-in! ctx nil {})
          sid1                (:session-id sd1)
          path1               (:session-file sd1)
          _                   (journal-store/flush-journal! (java.io.File. path1)
                                                            sid1
                                                            cwd
                                                            nil
                                                            nil
                                                            [(persist/message-entry {:role "assistant" :content [{:type :text :text "root"}]})])
          _                   (session/new-session-in! ctx sid1 {})
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:subscribed-topics #{"session/resumed" "session/rehydrated" "command-result" "context/updated"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/tree " sid1 "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          events              (filter #(= :event (:kind %)) frames)
          event-topics        (set (map :event events))
          context-evts        (filter #(= "context/updated" (:event %)) events)]
      (is (contains? event-topics "session/resumed"))
      (is (contains? event-topics "session/rehydrated"))
      (is (= sid1 (get-in (some #(when (= "session/resumed" (:event %)) %) events)
                          [:data :session-id])))
      (is (vector? (get-in (some #(when (= "session/rehydrated" (:event %)) %) events)
                           [:data :messages])))
      (is (= 1 (count context-evts)))
      (is (= sid1 (get-in (first context-evts) [:data :active-session-id])))))

  (testing "command /tree emits frontend selector payload with backend-owned order"
    (let [cwd                 (str (System/getProperty "java.io.tmpdir") "/psi-rpc-tree-picker-" (java.util.UUID/randomUUID))
          _                   (.mkdirs (java.io.File. cwd))
          [ctx session-id]    (support/create-session-context {:cwd cwd :persist? true :session-root cwd})
          child-sd            (session/new-session-in! ctx session-id {})
          child-id            (:session-id child-sd)
          _                   (ss/update-state-value-in! ctx
                                                         (ss/state-path :session-data child-id)
                                                         assoc
                                                         :parent-session-id session-id)
          _                   (session/set-session-name-in! ctx session-id "main")
          _                   (session/set-session-name-in! ctx child-id "child")
          entry               (persist/message-entry {:role    "user"
                                                      :content [{:type :text :text "Branch from this prompt"}]})
          _                   (ss/append-journal-entry-in! ctx session-id entry)
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:focus-session-id session-id
                                                  :subscribed-topics #{"ui/frontend-action-requested"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/tree\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          action-evt          (some #(when (= "ui/frontend-action-requested" (:event %)) %) frames)
          selector            (get-in action-evt [:data :ui/action])
          items               (mapv :ui.item/meta (:ui/items selector))
          fork-slot           (some #(when (= :fork-point (:item/kind %)) %) items)
          main-idx            (first (keep-indexed (fn [i item]
                                                     (when (and (= :session (:item/kind item))
                                                                (= session-id (:item/session-id item)))
                                                       i))
                                                   items))
          fork-idx            (first (keep-indexed (fn [i item]
                                                     (when (= (:id entry) (:item/entry-id item)) i))
                                                   items))]
      (is (some? action-evt))
      (is (nil? (get-in action-evt [:data :action-name])))
      (is (= :select-session (:ui/action-name selector)))
      (is (vector? items))
      (is (some? fork-slot))
      (is (= (:id entry) (:item/entry-id fork-slot)))
      (is (= "Branch from this prompt" (:item/display-name fork-slot)))
      (is (some? main-idx))
      (is (some? fork-idx))
      (is (< main-idx fork-idx)
          "backend payload must place current-session fork points after their containing session")))

  (testing "switch_session and get_messages derive transcript from ctx journal when agent messages drift"
    (let [[ctx _]             (support/create-session-context {:persist? false})
          sd1                 (session/new-session-in! ctx nil {})
          sid1                (:session-id sd1)
          canonical-messages  [{:role "user" :content "who are you?"}
                               {:role "assistant" :content [{:type :text :text "I am psi."}]}]
          _                   (doseq [m canonical-messages]
                                (ss/append-journal-entry-in! ctx sid1 (persist/message-entry m)))
          _                   (agent/replace-messages-in! (ss/agent-ctx-in ctx sid1)
                                                          [{:role    "assistant"
                                                            :content [{:type :text :text "stale in-memory tail"}]}])
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:subscribed-topics #{"session/resumed" "session/rehydrated"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"s1\" :kind :request :op \"switch_session\" :params {:session-id \"" sid1 "\"}}\n"
                                   "{:id \"g1\" :kind :request :op \"get_messages\" :params {:session-id \"" sid1 "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          rehydrate-event     (some #(when (and (= :event (:kind %))
                                                (= "session/rehydrated" (:event %))) %)
                                    frames)
          get-messages-resp   (some #(when (and (= :response (:kind %))
                                                (= "get_messages" (:op %))) %)
                                    frames)]
      (is (= canonical-messages
             (get-in rehydrate-event [:data :messages])))
      (is (= canonical-messages
             (get-in get-messages-resp [:data :messages]))))))

(deftest rpc-tree-command-edge-behaviour-test
  ;; Characterizes target-local /tree edge outputs before architecture simplification.
  (testing "/tree reports already-active sessions without rehydrating"
    (let [[ctx session-id]    (support/create-session-context {:persist? false})
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:focus-session-id session-id
                                                  :subscribed-topics #{"command-result" "session/resumed"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/tree " session-id "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          command-result      (some #(when (= "command-result" (:event %)) %) frames)]
      (is (= {:type "text"
              :message (str "Already active session: " session-id)}
             (:data command-result)))
      (is (not-any? #(= "session/resumed" (:event %)) frames))))

  (testing "/tree reports missing sessions as command-result text"
    (let [[ctx session-id]    (support/create-session-context {:persist? false})
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:focus-session-id session-id
                                                  :subscribed-topics #{"command-result" "session/resumed"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/tree definitely-missing\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          command-result      (some #(when (= "command-result" (:event %)) %) frames)]
      (is (= {:type "text"
              :message "Session not found in context: definitely-missing"}
             (:data command-result)))
      (is (not-any? #(= "session/resumed" (:event %)) frames))))

  (testing "/tree name renames a matched session and reports the exact text result"
    (let [[ctx session-id]    (support/create-session-context {:persist? false})
          child-id            (:session-id (session/new-session-in! ctx session-id {}))
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:focus-session-id session-id
                                                  :subscribed-topics #{"command-result"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/tree name " child-id " renamed child\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          command-result      (some #(when (= "command-result" (:event %)) %) frames)]
      (is (= {:type "text"
              :message (str "Renamed session " child-id " to \"renamed child\"")}
             (:data command-result)))
      (is (= "renamed child" (:session-name (ss/get-session-data-in ctx child-id))))))

  (testing "/tree accepts a unique session-id prefix and switches to the matching session"
    (let [[ctx session-id]    (support/create-session-context {:persist? false})
          child-id            (:session-id (session/new-session-in! ctx session-id {}))
          prefix              (subs child-id 0 8)
          _                   (ss/append-journal-entry-in! ctx child-id
                                                           (persist/message-entry {:role "assistant"
                                                                                   :content [{:type :text :text "child"}]}))
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:focus-session-id session-id
                                                  :subscribed-topics #{"session/resumed" "session/rehydrated" "context/updated"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/tree " prefix "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          resumed-event       (some #(when (= "session/resumed" (:event %)) %) frames)
          rehydrated-event    (some #(when (= "session/rehydrated" (:event %)) %) frames)
          context-event       (some #(when (= "context/updated" (:event %)) %) frames)]
      (is (= child-id (get-in resumed-event [:data :session-id])))
      (is (= [{:role "assistant" :content [{:type :text :text "child"}]}]
             (get-in rehydrated-event [:data :messages])))
      (is (= child-id (get-in context-event [:data :active-session-id]))))))
