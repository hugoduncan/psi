(ns psi.agent-core.core-test
  "Tests for agent-core.

  Every test gets its own isolated context via `create-context` (Nullable
  pattern) — no global-state mutations, no test ordering dependencies."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Schema validation — pure, no context
;; ─────────────────────────────────────────────────────────────────────────────

(deftest schema-validation-test
  (testing "valid agent state passes schema"
    (is (agent/valid-agent-state?
         {:system-prompt      ""
          :model              {:provider "google" :id "gemini"}
          :thinking-level     :off
          :tools              []
          :messages           []
          :stream-message     nil
          :pending-tool-calls #{}
          :error              nil
          :steering-queue     []
          :follow-up-queue    []
          :steering-mode      :one-at-a-time
          :follow-up-mode     :one-at-a-time})))

  (testing "invalid thinking level fails schema"
    (is (not (agent/valid-agent-state?
              {:system-prompt      ""
               :model              {:provider "x" :id "y"}
               :thinking-level     :turbo     ; not in enum
               :tools              []
               :messages           []
               :stream-message     nil
               :pending-tool-calls #{}
               :error              nil
               :steering-queue     []
               :follow-up-queue    []
               :steering-mode      :all
               :follow-up-mode     :all}))))

  (testing "valid tool passes schema with string parameters"
    (is (agent/valid-agent-tool?
         {:name        "list-files"
          :label       "List Files"
          :description "Lists directory contents"
          :parameters  "{}"})))

  (testing "valid tool passes schema with structured parameters"
    (is (agent/valid-agent-tool?
         {:name        "list-files"
          :label       "List Files"
          :description "Lists directory contents"
          :parameters  {:type "object"
                        :properties {"path" {:type "string"}}}}))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Lifecycle — statechart drives phase
;; ─────────────────────────────────────────────────────────────────────────────

(deftest lifecycle-test
  (testing "agent starts idle after create"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (is (= :idle (agent/sc-phase-in ctx)))
      (is (agent/idle-in? ctx))
      (is (not (agent/running-in? ctx)))))

  (testing "start-loop transitions to running"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (agent/start-loop-in! ctx [])
      (is (= :running (agent/sc-phase-in ctx)))
      (is (agent/running-in? ctx))
      (is (not (agent/idle-in? ctx)))))

  (testing "end-loop returns to idle"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (agent/start-loop-in! ctx [])
      (agent/end-loop-in! ctx)
      (is (= :idle (agent/sc-phase-in ctx)))
      (is (agent/idle-in? ctx))))

  (testing "abort transitions to aborted"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (agent/start-loop-in! ctx [])
      (agent/abort-in! ctx)
      (is (= :aborted (agent/sc-phase-in ctx)))))

  (testing "reset-agent after abort returns to idle"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (agent/start-loop-in! ctx [])
      (agent/abort-in! ctx)
      (agent/reset-agent-in! ctx)
      (is (= :idle (agent/sc-phase-in ctx)))))

  (testing "error-loop transitions back to idle"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (agent/start-loop-in! ctx [])
      (agent/end-loop-on-error-in! ctx "timeout")
      (is (= :idle (agent/sc-phase-in ctx)))
      (is (= "timeout" (:error (agent/get-data-in ctx)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Configuration setters
;; ─────────────────────────────────────────────────────────────────────────────

(deftest configuration-test
  (testing "set-system-prompt"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (agent/set-system-prompt-in! ctx "You are a helpful assistant.")
      (is (= "You are a helpful assistant."
             (:system-prompt (agent/get-data-in ctx))))))

  (testing "set-model"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (agent/set-model-in! ctx {:provider "anthropic" :id "claude-3-5-sonnet"})
      (is (= {:provider "anthropic" :id "claude-3-5-sonnet"}
             (:model (agent/get-data-in ctx))))))

  (testing "set-thinking-level"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (agent/set-thinking-level-in! ctx :high)
      (is (= :high (:thinking-level (agent/get-data-in ctx))))))

  (testing "set-tools replaces tool list"
    (let [ctx  (agent/create-context)
          tool {:name "bash" :label "Bash" :description "Run shell" :parameters "{}"}]
      (agent/create-agent-in! ctx)
      (agent/set-tools-in! ctx [tool])
      (is (= [tool] (:tools (agent/get-data-in ctx))))))

  (testing "create-agent accepts structured tool parameters during migration"
    (let [ctx (agent/create-context)
          tool {:name "bash"
                :label "Bash"
                :description "Run shell"
                :parameters {:type "object"
                             :properties {"command" {:type "string"}}}}]
      (agent/create-agent-in! ctx {:tools [tool]})
      (is (= [tool] (:tools (agent/get-data-in ctx)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Message management
;; ─────────────────────────────────────────────────────────────────────────────

(deftest message-management-test
  (testing "append-message grows history"
    (let [ctx (agent/create-context)
          msg {:role "user" :content [{:type :text :text "hello"}]}]
      (agent/create-agent-in! ctx)
      (agent/append-message-in! ctx msg)
      (is (= [msg] (:messages (agent/get-data-in ctx))))))

  (testing "replace-messages replaces history"
    (let [ctx  (agent/create-context)
          msg1 {:role "user" :content []}
          msg2 {:role "assistant" :content []}]
      (agent/create-agent-in! ctx)
      (agent/append-message-in! ctx msg1)
      (agent/replace-messages-in! ctx [msg2])
      (is (= [msg2] (:messages (agent/get-data-in ctx))))))

  (testing "clear-messages empties history"
    (let [ctx (agent/create-context)
          msg {:role "user" :content []}]
      (agent/create-agent-in! ctx)
      (agent/append-message-in! ctx msg)
      (agent/clear-messages-in! ctx)
      (is (= [] (:messages (agent/get-data-in ctx)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Streaming state transitions
;; ─────────────────────────────────────────────────────────────────────────────

(deftest streaming-test
  (testing "begin-stream sets partial message"
    (let [ctx  (agent/create-context)
          part {:role "assistant" :content [{:type :text :text ""}]}]
      (agent/create-agent-in! ctx)
      (agent/start-loop-in! ctx [])
      (agent/begin-stream-in! ctx part)
      (is (= part (:stream-message (agent/get-data-in ctx))))))

  (testing "end-stream commits to history and clears partial"
    (let [ctx   (agent/create-context)
          part  {:role "assistant" :content [{:type :text :text "Hi"}]}
          final (assoc part :stop-reason :stop)]
      (agent/create-agent-in! ctx)
      (agent/start-loop-in! ctx [])
      (agent/begin-stream-in! ctx part)
      (agent/end-stream-in! ctx final)
      (is (nil? (:stream-message (agent/get-data-in ctx))))
      (is (= [final] (:messages (agent/get-data-in ctx))))))

  (testing "start-loop injects new-messages into history"
    (let [ctx (agent/create-context)
          msg {:role "user" :content [{:type :text :text "go"}]}]
      (agent/create-agent-in! ctx)
      (agent/start-loop-in! ctx [msg])
      (is (= [msg] (:messages (agent/get-data-in ctx)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Steering / follow-up queues
;; ─────────────────────────────────────────────────────────────────────────────

(deftest queue-test
  (testing "queue-steering enqueues a message"
    (let [ctx (agent/create-context)
          msg {:role "user" :content [{:type :text :text "steer"}]}]
      (agent/create-agent-in! ctx)
      (agent/queue-steering-in! ctx msg)
      (is (= [msg] (:steering-queue (agent/get-data-in ctx))))))

  (testing "has-queued-messages when steering-queue non-empty"
    (let [ctx (agent/create-context)
          msg {:role "user" :content []}]
      (agent/create-agent-in! ctx)
      (agent/queue-steering-in! ctx msg)
      (is (agent/has-queued-messages-in? ctx))))

  (testing "dequeue-messages one-at-a-time returns first"
    (let [m1 {:role "user" :content [{:type :text :text "a"}]}
          m2 {:role "user" :content [{:type :text :text "b"}]}]
      (is (= [m1] (agent/dequeue-messages [m1 m2] :one-at-a-time)))))

  (testing "dequeue-messages all returns all"
    (let [m1 {:role "user" :content []}
          m2 {:role "user" :content []}]
      (is (= [m1 m2] (agent/dequeue-messages [m1 m2] :all)))))

  (testing "check-queues reports :steering when steering non-empty"
    (let [ctx (agent/create-context)
          msg {:role "user" :content []}]
      (agent/create-agent-in! ctx)
      (agent/queue-steering-in! ctx msg)
      (is (= :steering (:action (agent/check-queues-in ctx))))))

  (testing "check-queues reports :done when both queues empty"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (is (= :done (:action (agent/check-queues-in ctx)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Event emission
;; ─────────────────────────────────────────────────────────────────────────────

(deftest event-emission-test
  (testing "start-loop emits agent-start and turn-start"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (agent/start-loop-in! ctx [])
      (let [events (agent/drain-events-in! ctx)
            types  (map :type events)]
        (is (some #{:agent-start} types))
        (is (some #{:turn-start} types)))))

  (testing "start-loop emits message events for each new-message"
    (let [ctx (agent/create-context)
          msg {:role "user" :content []}]
      (agent/create-agent-in! ctx)
      (agent/start-loop-in! ctx [msg])
      (let [events (agent/drain-events-in! ctx)
            types  (map :type events)]
        (is (some #{:message-start} types))
        (is (some #{:message-end}   types)))))

  (testing "end-loop emits agent-end"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (agent/start-loop-in! ctx [])
      (agent/drain-events-in! ctx)   ;; clear start events
      (agent/end-loop-in! ctx)
      (let [events (agent/drain-events-in! ctx)
            types  (map :type events)]
        (is (some #{:agent-end} types)))))

  (testing "drain-events! is atomic — double drain returns empty"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (agent/emit-in! ctx {:type :test-event})
      (agent/drain-events-in! ctx)
      (is (= [] (agent/drain-events-in! ctx))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Pathom3 introspection
;; ─────────────────────────────────────────────────────────────────────────────

(deftest pathom-introspection-test
  (testing "query :psi.agent/phase reflects statechart state"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (let [result (agent/query-in ctx [:psi.agent/phase :psi.agent/is-idle])]
        (is (= :idle (:psi.agent/phase result)))
        (is (true?   (:psi.agent/is-idle result))))))

  (testing "query :psi.agent/is-streaming true while running"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (agent/start-loop-in! ctx [])
      (let [result (agent/query-in ctx [:psi.agent/is-streaming])]
        (is (true? (:psi.agent/is-streaming result))))))

  (testing "query :psi.agent/message-count after appending"
    (let [ctx (agent/create-context)
          msg {:role "user" :content []}]
      (agent/create-agent-in! ctx)
      (agent/append-message-in! ctx msg)
      (let [result (agent/query-in ctx [:psi.agent/message-count])]
        (is (= 1 (:psi.agent/message-count result))))))

  (testing "query :psi.agent/diagnostics returns snapshot"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (let [result (agent/query-in ctx [:psi.agent/diagnostics])]
        (is (map? (:psi.agent/diagnostics result)))
        (is (contains? (:psi.agent/diagnostics result) :phase))
        (is (contains? (:psi.agent/diagnostics result) :message-count)))))

  (testing "query :psi.agent/sc-configuration returns set"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (let [result (agent/query-in ctx [:psi.agent/sc-configuration])]
        (is (set? (:psi.agent/sc-configuration result)))
        (is (contains? (:psi.agent/sc-configuration result) :idle)))))

  (testing "query :psi.agent/tools reflects set-tools-in!"
    (let [ctx  (agent/create-context)
          tool {:name "bash" :label "Bash" :description "Run" :parameters "{}"}]
      (agent/create-agent-in! ctx)
      (agent/set-tools-in! ctx [tool])
      (let [result (agent/query-in ctx [:psi.agent/tools])]
        (is (= [tool] (:psi.agent/tools result))))))

  (testing "query :psi.agent/error after error loop"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (agent/start-loop-in! ctx [])
      (agent/end-loop-on-error-in! ctx "boom")
      (let [result (agent/query-in ctx [:psi.agent/error :psi.agent/has-error])]
        (is (= "boom" (:psi.agent/error result)))
        (is (true?     (:psi.agent/has-error result)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Tool event helpers
;; ─────────────────────────────────────────────────────────────────────────────

(deftest tool-event-helpers-test
  (let [call {:id "call-1" :name "bash" :arguments "{}"}
        res  {:content [{:type :text :text "ok"}]}]

    (testing "emit-tool-start-in! emits tool_execution_start event and marks the call pending"
      (let [ctx (agent/create-context)]
        (agent/create-agent-in! ctx)
        (agent/emit-tool-start-in! ctx call)
        (let [events (agent/drain-events-in! ctx)]
          (is (some #(= :tool-execution-start (:type %)) events))
          (is (= #{"call-1"} (:pending-tool-calls (agent/get-data-in ctx)))))))

    (testing "emit-tool-end-in! emits tool_execution_end event"
      (let [ctx (agent/create-context)]
        (agent/create-agent-in! ctx)
        (agent/emit-tool-end-in! ctx call res false)
        (let [events (agent/drain-events-in! ctx)]
          (is (some #(= :tool-execution-end (:type %)) events)))))

    (testing "emit-turn-end-in! emits turn_end event"
      (let [ctx       (agent/create-context)
            assistant {:role "assistant" :content []}]
        (agent/create-agent-in! ctx)
        (agent/emit-turn-end-in! ctx assistant [])
        (let [events (agent/drain-events-in! ctx)]
          (is (some #(= :turn-end (:type %)) events)))))

    (testing "record-tool-result-in! appends to messages, emits events, and clears pending call"
      (let [ctx    (agent/create-context)
            result {:role "tool-result" :tool-call-id "c1" :tool-name "bash"
                    :content [] :is-error false}]
        (agent/create-agent-in! ctx)
        (swap! (:data-atom ctx) assoc :pending-tool-calls #{"c1"})
        (agent/record-tool-result-in! ctx result)
        (is (= [result] (:messages (agent/get-data-in ctx))))
        (is (= #{} (:pending-tool-calls (agent/get-data-in ctx))))
        (let [events (agent/drain-events-in! ctx)
              types  (map :type events)]
          (is (some #{:message-start} types))
          (is (some #{:message-end}   types)))))

    (testing "update-stream-in! emits message_update"
      (let [ctx  (agent/create-context)
            part {:role "assistant" :content [{:type :text :text "H"}]}
            upd  {:role "assistant" :content [{:type :text :text "Hi"}]}]
        (agent/create-agent-in! ctx)
        (agent/start-loop-in! ctx [])
        (agent/begin-stream-in! ctx part)
        (agent/drain-events-in! ctx)
        (agent/update-stream-in! ctx upd)
        (let [events (agent/drain-events-in! ctx)]
          (is (some #(= :message-update (:type %)) events)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Queue drain helpers
;; ─────────────────────────────────────────────────────────────────────────────

(deftest queue-drain-test
  (testing "drain-steering-in! removes dequeued messages"
    (let [ctx (agent/create-context)
          m1  {:role "user" :content []}
          m2  {:role "user" :content []}]
      (agent/create-agent-in! ctx)
      (agent/queue-steering-in! ctx m1)
      (agent/queue-steering-in! ctx m2)
      (agent/drain-steering-in! ctx [m1])
      (is (= [m2] (:steering-queue (agent/get-data-in ctx))))))

  (testing "drain-follow-up-in! removes dequeued messages"
    (let [ctx (agent/create-context)
          m1  {:role "user" :content []}
          m2  {:role "user" :content []}]
      (agent/create-agent-in! ctx)
      (agent/queue-follow-up-in! ctx m1)
      (agent/queue-follow-up-in! ctx m2)
      (agent/drain-follow-up-in! ctx [m1])
      (is (= [m2] (:follow-up-queue (agent/get-data-in ctx)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Diagnostics snapshot
;; ─────────────────────────────────────────────────────────────────────────────

(deftest diagnostics-test
  (testing "diagnostics contains all expected keys"
    (let [ctx (agent/create-context)]
      (agent/create-agent-in! ctx)
      (let [d (agent/diagnostics-in ctx)]
        (is (contains? d :phase))
        (is (contains? d :is-streaming))
        (is (contains? d :message-count))
        (is (contains? d :pending-tool-calls))
        (is (contains? d :has-error))
        (is (contains? d :error))
        (is (contains? d :steering-depth))
        (is (contains? d :follow-up-depth))
        (is (contains? d :model))
        (is (contains? d :thinking-level))
        (is (contains? d :tool-count)))))

  (testing "diagnostics reflects current state"
    (let [ctx  (agent/create-context)
          tool {:name "t" :label "T" :description "d" :parameters "{}"}]
      (agent/create-agent-in! ctx)
      (agent/set-tools-in! ctx [tool tool])
      (agent/start-loop-in! ctx [])
      (let [d (agent/diagnostics-in ctx)]
        (is (= :running (:phase d)))
        (is (true?       (:is-streaming d)))
        (is (= 2         (:tool-count d)))))))
