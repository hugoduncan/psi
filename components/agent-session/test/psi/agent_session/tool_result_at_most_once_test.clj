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

(def ^:private test-instant
  "Fixed instant for test toolResult messages. The timestamp is never asserted
   and no de-dup/ordering keys off it, so a constant keeps setup deterministic
   and free of incidental wall-clock detail."
  java.time.Instant/EPOCH)

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
   :timestamp test-instant})

(defn- interrupt-result-msg [tool-call-id]
  {:role "toolResult"
   :tool-call-id tool-call-id
   :tool-name "interrupted"
   :content [{:type :text :text "Tool execution interrupted before completion."}]
   :is-error true
   :timestamp test-instant})

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

(defn- assert-single-recorded-result
  "Assert exactly one toolResult is recorded for `tool-call-id` on both recorded
   layers (journal + agent-core in-memory history), and that the surviving
   result's `:tool-name` is `expected-tool-name` on both layers. Compresses the
   repeated count+winner both-layer ceremony into one enforced contract with the
   established layer-naming failure messages; the winner name is passed at the
   call site so intent stays locally visible."
  [ctx session-id tool-call-id expected-tool-name]
  (let [journal (journal-tool-results ctx session-id tool-call-id)
        memory  (memory-tool-results ctx session-id tool-call-id)]
    (is (= 1 (count journal))
        "exactly one toolResult entry for the id in the journal")
    (is (= 1 (count memory))
        "exactly one toolResult entry for the id in the in-memory history")
    (is (= expected-tool-name (:tool-name (first journal)))
        "the surviving result wins on the journal layer")
    (is (= expected-tool-name (:tool-name (first memory)))
        "the surviving result wins on the in-memory history layer")))

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
      ;; first writer (the interrupt) wins for the in-flight headline case
      (assert-single-recorded-result ctx session-id tool-call-id "interrupted"))))

(deftest recorded-ids-survive-turn-boundary-test
  (testing "recorded-tool-result-ids is session-scoped, not turn-scoped: an
            interrupt records the synthetic result in one turn, the turn ends
            (resetting :pending-tool-calls), and a late real result for the same
            id in a later turn is still suppressed — exactly one toolResult at
            the raw recorded layer (journal + in-memory history). Fails if
            recorded-ids were cleared at the per-turn boundary."
    (let [[ctx session-id] (create-session-context)
          agent-ctx        (ss/agent-ctx-in ctx session-id)
          tool-call-id     "tc-cross-turn"]
      ;; turn N: tool-call in-flight, :user-abort records the synthetic interrupt
      (agent/emit-tool-start-in! agent-ctx {:id tool-call-id :name "bash" :arguments "{}"})
      (session/abort-in! ctx session-id)
      ;; turn boundary: end the loop, resetting :pending-tool-calls to #{}
      ;; (mirrors the per-turn reset the recorded-ids set must NOT share)
      (agent/end-loop-in! agent-ctx)
      (is (empty? (:pending-tool-calls (agent/get-data-in agent-ctx)))
          ":pending-tool-calls cleared at the turn boundary")
      ;; turn N+1: the late real result arrives for the same id
      (record-result! ctx session-id (real-result-msg tool-call-id))
      ;; first writer (the interrupt) still wins across the turn boundary
      (assert-single-recorded-result ctx session-id tool-call-id "interrupted"))))

(deftest normal-single-result-path-unaffected-test
  (testing "a normal tool call records exactly one real result (happy path)"
    (let [[ctx session-id] (create-session-context)
          tool-call-id     "tc-normal"]
      (record-result! ctx session-id (real-result-msg tool-call-id))
      ;; the real result wins on both recorded layers
      (assert-single-recorded-result ctx session-id tool-call-id "bash"))))

(deftest interrupt-only-path-yields-one-result-test
  (testing "an interrupt for a pending tool-call with no later real result yields
            exactly one interrupted result"
    (let [[ctx session-id] (create-session-context)
          agent-ctx        (ss/agent-ctx-in ctx session-id)
          tool-call-id     "tc-interrupt-only"]
      (agent/emit-tool-start-in! agent-ctx {:id tool-call-id :name "bash" :arguments "{}"})
      (session/abort-in! ctx session-id)
      ;; the interrupt wins on both recorded layers
      (assert-single-recorded-result ctx session-id tool-call-id "interrupted"))))

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
      ;; first writer (the real result) wins
      (assert-single-recorded-result ctx session-id tool-call-id "bash"))))

(deftest distinct-tool-call-ids-both-recorded-test
  (testing "the recorded-ids guard is per-tool-call-id, not per-session: two
            distinct tool-call-ids in one session each record their real result
            (no cross-id suppression). A per-session boolean guard would suppress
            the second distinct call and fail this test."
    (let [[ctx session-id] (create-session-context)
          id-a             "tc-distinct-a"
          id-b             "tc-distinct-b"]
      (record-result! ctx session-id (real-result-msg id-a))
      (record-result! ctx session-id (real-result-msg id-b))
      ;; each distinct id records its own real result — no cross-id suppression
      (assert-single-recorded-result ctx session-id id-a "bash")
      (assert-single-recorded-result ctx session-id id-b "bash"))))
