(ns psi.agent-session.tool-result-at-most-once-test
  "At-most-once toolResult per tool-call-id (first-writer-wins).

   Guards the agent-session core race where a turn interrupt (`:user-abort`)
   records a synthetic `\"interrupted\"` toolResult for a still-pending tool-call
   while that tool also records its real result, producing two journal
   `toolResult` entries for one tool_use id. The `:session/tool-agent-record-result`
   handler enforces at-most-once against the canonical recorded-tool-result-ids
   set in `:state*`, covering both the in-memory record and the journal append as
   one both-or-neither decision."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-core.core :as agent]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.session-persistence.core :as persist]
   [psi.session-state.state :as ss]))

(defn- create-session-context []
  (let [ctx (session/create-context (test-support/safe-context-opts {:persist? false}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(defn- real-result-msg [tool-call-id]
  {:role "toolResult"
   :tool-call-id tool-call-id
   :tool-name "bash"
   :content [{:type :text :text "real output"}]
   :is-error false
   :timestamp (java.time.Instant/now)})

(defn- interrupt-result-msg [tool-call-id]
  {:role "toolResult"
   :tool-call-id tool-call-id
   :tool-name "interrupted"
   :content [{:type :text :text "Tool execution interrupted before completion."}]
   :is-error true
   :timestamp (java.time.Instant/now)})

(defn- journal-tool-results [ctx session-id tool-call-id]
  (->> (persist/all-entries-in ctx session-id)
       (filter #(= :message (:kind %)))
       (map #(get-in % [:data :message]))
       (filter #(and (= "toolResult" (:role %))
                     (= tool-call-id (:tool-call-id %))))))

(defn- memory-tool-results [ctx session-id tool-call-id]
  (->> (agent/get-data-in (ss/agent-ctx-in ctx session-id))
       :messages
       (filter #(and (= "toolResult" (:role %))
                     (= tool-call-id (:tool-call-id %))))))

(defn- record-result! [ctx session-id tool-result-msg]
  (session/dispatch-in! ctx :session/tool-agent-record-result
                        {:session-id session-id
                         :tool-result-msg tool-result-msg}
                        {:origin :core}))

(deftest abort-races-real-result-yields-one-tool-result-test
  (testing "an interrupt (:user-abort abort-in!) for a pending tool-call plus a
            late real result records exactly one toolResult for the id in the
            journal and the agent-core in-memory history (interrupt wins)"
    (let [[ctx session-id] (create-session-context)
          agent-ctx        (ss/agent-ctx-in ctx session-id)
          tool-call-id     "tc-abort-race"]
      ;; tool-call genuinely in-flight: pending, real result not yet produced
      (agent/emit-tool-start-in! agent-ctx {:id tool-call-id :name "bash" :arguments "{}"})
      ;; :user-abort synchronous abort-in! records the synthetic interrupt first
      (session/abort-in! ctx session-id)
      ;; the in-flight tool then completes and dispatches its real result
      (record-result! ctx session-id (real-result-msg tool-call-id))
      (let [journal (journal-tool-results ctx session-id tool-call-id)
            memory  (memory-tool-results ctx session-id tool-call-id)]
        (is (= 1 (count journal))
            "exactly one toolResult entry for the id in the journal")
        (is (= 1 (count memory))
            "exactly one toolResult entry for the id in the in-memory history")
        ;; first writer (the interrupt) wins for the in-flight headline case
        (is (= "interrupted" (:tool-name (first journal))))
        (is (= "interrupted" (:tool-name (first memory))))))))

(deftest normal-single-result-path-unaffected-test
  (testing "a normal tool call records exactly one real result (happy path)"
    (let [[ctx session-id] (create-session-context)
          tool-call-id     "tc-normal"]
      (record-result! ctx session-id (real-result-msg tool-call-id))
      (let [journal (journal-tool-results ctx session-id tool-call-id)
            memory  (memory-tool-results ctx session-id tool-call-id)]
        (is (= 1 (count journal)))
        (is (= 1 (count memory)))
        (is (= "bash" (:tool-name (first journal))))))))

(deftest interrupt-only-path-yields-one-result-test
  (testing "an interrupt for a pending tool-call with no later real result yields
            exactly one interrupted result"
    (let [[ctx session-id] (create-session-context)
          agent-ctx        (ss/agent-ctx-in ctx session-id)
          tool-call-id     "tc-interrupt-only"]
      (agent/emit-tool-start-in! agent-ctx {:id tool-call-id :name "bash" :arguments "{}"})
      (session/abort-in! ctx session-id)
      (let [journal (journal-tool-results ctx session-id tool-call-id)
            memory  (memory-tool-results ctx session-id tool-call-id)]
        (is (= 1 (count journal)))
        (is (= 1 (count memory)))
        (is (= "interrupted" (:tool-name (first journal))))))))

(deftest concurrent-completion-real-result-wins-test
  (testing "at-most-once under the concurrent-completion window: when the real
            result's record-event is serialized first, the real result is kept
            and the later interrupt is suppressed — exactly one result remains"
    ;; Direct dispatch of the two record events (real first, then a synthetic
    ;; interrupt for the same id) exercises the handler chokepoint's first-writer
    ;; suppression. abort-in! is NOT the vehicle: the real result's record effect
    ;; disj's the id from :pending-tool-calls after its handler applies, so a
    ;; sequential abort-in! would enumerate no pending id and dispatch no
    ;; interrupt (vacuous pass).
    (let [[ctx session-id] (create-session-context)
          tool-call-id     "tc-concurrent"]
      (record-result! ctx session-id (real-result-msg tool-call-id))
      (record-result! ctx session-id (interrupt-result-msg tool-call-id))
      (let [journal (journal-tool-results ctx session-id tool-call-id)
            memory  (memory-tool-results ctx session-id tool-call-id)]
        (is (= 1 (count journal))
            "exactly one toolResult entry for the id, not two")
        (is (= 1 (count memory)))
        ;; first writer (the real result) wins
        (is (= "bash" (:tool-name (first journal))))
        (is (= "bash" (:tool-name (first memory))))))))
