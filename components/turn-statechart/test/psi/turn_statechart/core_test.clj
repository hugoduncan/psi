(ns psi.turn-statechart.core-test
  "Tests for the per-turn streaming statechart.

  Every test creates its own turn context via the Nullable pattern —
  no shared state, no ordering dependencies."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.turn-statechart.core :as turn-sc]))

;; ── Helper ──────────────────────────────────────────────────

(defn- create-test-ctx
  "Create a turn context with accumulation-only actions (no agent-core)."
  ([]
   (create-test-ctx nil))
  ([done-p]
   (let [actions (turn-sc/make-accumulation-actions done-p)]
     (turn-sc/create-turn-context actions))))

;; ── Initial state ───────────────────────────────────────────

(deftest initial-state-test
  (testing "turn context starts in :idle"
    (let [ctx (create-test-ctx)]
      (is (= :idle (turn-sc/turn-phase ctx)))))

  (testing "turn data is fresh"
    (let [ctx (create-test-ctx)
          td  (turn-sc/get-turn-data ctx)]
      (is (= "" (:text-buffer td)))
      (is (empty? (:tool-calls td)))
      (is (empty? (:content-blocks td)))
      (is (nil? (:last-provider-event td)))
      (is (nil? (:final-message td)))
      (is (nil? (:error-message td))))))

;; ── WM persistence debug ────────────────────────────────────

(deftest actions-fn-receives-data-test
  (testing "actions-fn is called with correct data"
    (let [received (atom [])
          custom-actions (fn [action-key data]
                           (swap! received conj
                                  {:action-key   action-key
                                   :has-delta    (some? (:delta data))
                                   :has-td       (some? (:turn-data data))
                                   :has-af       (some? (:actions-fn data))
                                   :delta-val    (:delta data)}))
          ctx (turn-sc/create-turn-context custom-actions)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/text-delta {:delta "hello"})
      (is (= 2 (count @received)) "two actions should have fired")
      (let [first-call (first @received)
            second-call (second @received)]
        (is (= :on-stream-start (:action-key first-call)))
        (is (= true (:has-td first-call)) "first call: turn-data present")
        (is (= true (:has-af first-call)) "first call: actions-fn present")
        (is (= :on-text-delta (:action-key second-call)))
        (is (= true (:has-delta second-call)) "second call: delta present")
        (is (= "hello" (:delta-val second-call)) "second call: delta value")
        (is (= true (:has-td second-call)) "second call: turn-data present")))))

;; ── State transitions ───────────────────────────────────────

(deftest start-transition-test
  (testing ":turn/start transitions from :idle to :text-accumulating"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (is (= :text-accumulating (turn-sc/turn-phase ctx))))))

(deftest terminal-from-idle-test
  ;; Review 51: :idle previously accepted only :turn/start — :turn/error and
  ;; :turn/done were silently DROPPED there (enabled transitions => #{}), so
  ;; a direct create-turn-context consumer feeding a provider :error/:done as
  ;; the FIRST event (tests, embeddings, any future turn path that skips the
  ;; turn-level :turn/start) got a silent drop, done-p never delivered, and
  ;; only the 20-minute llm-stream-idle-timeout-ms ended the turn (the
  ;; timeout branch's own :turn/error send was dropped too). The chart now
  ;; mirrors the :text-accumulating / :tool-accumulating terminal transitions
  ;; on :idle so terminal events are accepted from ANY state.
  (testing ":turn/done from :idle transitions to :done and delivers done-p"
    (let [done-p (promise)
          ctx    (create-test-ctx done-p)]
      (turn-sc/send-event! ctx :turn/done {:reason :stop})
      (is (= :done (turn-sc/turn-phase ctx))
          "the terminal phase is recorded, not silently dropped")
      (let [result (deref done-p 1000 ::timeout)]
        (is (not= ::timeout result)
            "done-p is delivered from the initial state")
        (is (= "assistant" (:role result)))
        (is (= :stop (:stop-reason result))))))

  (testing ":turn/error from :idle transitions to :error and delivers done-p"
    (let [done-p (promise)
          ctx    (create-test-ctx done-p)]
      (turn-sc/send-event! ctx :turn/error {:error-message "Connection failed"})
      (is (= :error (turn-sc/turn-phase ctx))
          "the terminal phase is recorded, not silently dropped")
      (let [result (deref done-p 1000 ::timeout)]
        (is (not= ::timeout result)
            "done-p is delivered from the initial state")
        (is (= :error (:stop-reason result)))
        (is (= "Connection failed" (:error-message result)))))))

(deftest text-accumulation-test
  (testing "text deltas accumulate in text-buffer"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/text-delta {:delta "hello "})
      (is (= "hello " (:text-buffer (turn-sc/get-turn-data ctx))))
      (turn-sc/send-event! ctx :turn/text-delta {:delta "world"})
      (is (= "hello world" (:text-buffer (turn-sc/get-turn-data ctx))))))

  (testing "stays in :text-accumulating during text deltas"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/text-delta {:delta "a"})
      (turn-sc/send-event! ctx :turn/text-delta {:delta "b"})
      (is (= :text-accumulating (turn-sc/turn-phase ctx))))))

(deftest tool-accumulation-test
  (testing "toolcall-start transitions to :tool-accumulating"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/toolcall-start
                           {:content-index 0 :tool-id "t1" :tool-name "read"})
      (is (= :tool-accumulating (turn-sc/turn-phase ctx)))))

  (testing "tool call arguments accumulate"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/toolcall-start
                           {:content-index 0 :tool-id "t1" :tool-name "read"})
      (turn-sc/send-event! ctx :turn/toolcall-delta
                           {:content-index 0 :delta "{\"path\":"})
      (turn-sc/send-event! ctx :turn/toolcall-delta
                           {:content-index 0 :delta "\"a.txt\"}"})
      (let [tc (get-in (turn-sc/get-turn-data ctx) [:tool-calls 0])]
        (is (= "t1" (:id tc)))
        (is (= "read" (:name tc)))
        (is (= "{\"path\":\"a.txt\"}" (:arguments tc))))))

  (testing "toolcall-end returns to :text-accumulating"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/toolcall-start
                           {:content-index 0 :tool-id "t1" :tool-name "read"})
      (turn-sc/send-event! ctx :turn/toolcall-end {:content-index 0})
      (is (= :text-accumulating (turn-sc/turn-phase ctx))))))

(deftest multiple-tool-calls-test
  (testing "multiple tool calls tracked by content-index"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      ;; First tool call
      (turn-sc/send-event! ctx :turn/toolcall-start
                           {:content-index 1 :tool-id "t1" :tool-name "read"})
      (turn-sc/send-event! ctx :turn/toolcall-delta
                           {:content-index 1 :delta "{\"path\":\"a.txt\"}"})
      (turn-sc/send-event! ctx :turn/toolcall-end {:content-index 1})
      ;; Second tool call
      (turn-sc/send-event! ctx :turn/toolcall-start
                           {:content-index 2 :tool-id "t2" :tool-name "bash"})
      (turn-sc/send-event! ctx :turn/toolcall-delta
                           {:content-index 2 :delta "{\"command\":\"ls\"}"})
      (turn-sc/send-event! ctx :turn/toolcall-end {:content-index 2})
      (let [td (turn-sc/get-turn-data ctx)]
        (is (= 2 (count (:tool-calls td))))
        (is (= "read" (get-in td [:tool-calls 1 :name])))
        (is (= "bash" (get-in td [:tool-calls 2 :name])))))))

(deftest interleaved-text-and-tools-test
  (testing "text then tool calls"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/text-delta {:delta "Let me read that."})
      (turn-sc/send-event! ctx :turn/toolcall-start
                           {:content-index 1 :tool-id "t1" :tool-name "read"})
      (turn-sc/send-event! ctx :turn/toolcall-delta
                           {:content-index 1 :delta "{}"})
      (turn-sc/send-event! ctx :turn/toolcall-end {:content-index 1})
      (let [td (turn-sc/get-turn-data ctx)]
        (is (= "Let me read that." (:text-buffer td)))
        (is (= 1 (count (:tool-calls td))))))))

;; ── Done ────────────────────────────────────────────────────

(deftest done-transition-test
  (testing ":turn/done transitions to :done from text-accumulating"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/text-delta {:delta "hello"})
      (turn-sc/send-event! ctx :turn/done {:reason :stop})
      (is (= :done (turn-sc/turn-phase ctx)))))

  (testing ":turn/done transitions to :done from tool-accumulating"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/toolcall-start
                           {:content-index 0 :tool-id "t1" :tool-name "read"})
      (turn-sc/send-event! ctx :turn/toolcall-delta
                           {:content-index 0 :delta "{}"})
      (turn-sc/send-event! ctx :turn/done {:reason :tool-use})
      (is (= :done (turn-sc/turn-phase ctx))))))

(deftest final-message-assembly-test
  (testing "text-only final message"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/text-delta {:delta "hello world"})
      (turn-sc/send-event! ctx :turn/done {:reason :stop})
      (let [fm (:final-message (turn-sc/get-turn-data ctx))]
        (is (= "assistant" (:role fm)))
        (is (= :stop (:stop-reason fm)))
        (is (= 1 (count (:content fm))))
        (is (= {:type :text :text "hello world"} (first (:content fm)))))))

  (testing "tool-call final message"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/text-delta {:delta "Let me help."})
      (turn-sc/send-event! ctx :turn/toolcall-start
                           {:content-index 1 :tool-id "t1" :tool-name "read"})
      (turn-sc/send-event! ctx :turn/toolcall-delta
                           {:content-index 1 :delta "{\"path\":\"f.txt\"}"})
      (turn-sc/send-event! ctx :turn/done {:reason :tool-use})
      (let [fm  (:final-message (turn-sc/get-turn-data ctx))
            cnt (:content fm)]
        (is (= 2 (count cnt)))
        (is (= :text (:type (first cnt))))
        (is (= :tool-call (:type (second cnt))))
        (is (= "t1" (:id (second cnt))))
        (is (= "read" (:name (second cnt))))
        (is (= "{\"path\":\"f.txt\"}" (:arguments (second cnt)))))))

  (testing "done delivers the promise"
    (let [done-p (promise)
          ctx    (create-test-ctx done-p)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/text-delta {:delta "hello"})
      (turn-sc/send-event! ctx :turn/done {:reason :stop})
      (let [result (deref done-p 1000 ::timeout)]
        (is (not= ::timeout result))
        (is (= "assistant" (:role result)))))))

;; ── Error ───────────────────────────────────────────────────

(deftest error-transition-test
  (testing ":turn/error transitions to :error"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/error {:error-message "Connection failed"})
      (is (= :error (turn-sc/turn-phase ctx)))))

  (testing "error records message in turn data"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/text-delta {:delta "partial"})
      (turn-sc/send-event! ctx :turn/error {:error-message "timeout"})
      (let [td (turn-sc/get-turn-data ctx)]
        (is (= "timeout" (:error-message td)))
        (is (some? (:final-message td)))
        (is (= :error (:stop-reason (:final-message td)))))))

  (testing "error preserves partial text in final message"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/text-delta {:delta "partial text"})
      (turn-sc/send-event! ctx :turn/error {:error-message "broken"})
      (let [fm (:final-message (turn-sc/get-turn-data ctx))]
        (is (= 2 (count (:content fm))))
        (is (= "partial text" (:text (first (:content fm)))))
        (is (= "broken" (:text (second (:content fm))))))))

  (testing "error delivers the promise"
    (let [done-p (promise)
          ctx    (create-test-ctx done-p)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/error {:error-message "fail"})
      (let [result (deref done-p 1000 ::timeout)]
        (is (not= ::timeout result))
        (is (= :error (:stop-reason result)))))))

;; ── Reset ───────────────────────────────────────────────────

(deftest reset-test
  (testing ":turn/reset returns to :idle from :done"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/text-delta {:delta "hello"})
      (turn-sc/send-event! ctx :turn/done {:reason :stop})
      (turn-sc/send-event! ctx :turn/reset)
      (is (= :idle (turn-sc/turn-phase ctx)))))

  (testing ":turn/reset returns to :idle from :error"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/error {:error-message "fail"})
      (turn-sc/send-event! ctx :turn/reset)
      (is (= :idle (turn-sc/turn-phase ctx)))))

  (testing ":turn/reset clears turn data"
    (let [ctx (create-test-ctx)]
      (turn-sc/send-event! ctx :turn/start)
      (turn-sc/send-event! ctx :turn/text-delta {:delta "hello"})
      (turn-sc/send-event! ctx :turn/done {:reason :stop})
      (turn-sc/send-event! ctx :turn/reset)
      (let [td (turn-sc/get-turn-data ctx)]
        (is (= "" (:text-buffer td)))
        (is (empty? (:tool-calls td)))
        (is (nil? (:final-message td)))))))

;; ── Context isolation ───────────────────────────────────────

(deftest context-isolation-test
  (testing "two turn contexts are independent"
    (let [ctx-a (create-test-ctx)
          ctx-b (create-test-ctx)]
      (turn-sc/send-event! ctx-a :turn/start)
      (turn-sc/send-event! ctx-a :turn/text-delta {:delta "alpha"})
      (is (= "alpha" (:text-buffer (turn-sc/get-turn-data ctx-a))))
      (is (= "" (:text-buffer (turn-sc/get-turn-data ctx-b))))
      (is (= :idle (turn-sc/turn-phase ctx-b))))))

;; ── Configuration set ───────────────────────────────────────

(deftest configuration-test
  (testing "turn-configuration returns a set"
    (let [ctx (create-test-ctx)]
      (is (set? (turn-sc/turn-configuration ctx)))
      (is (contains? (turn-sc/turn-configuration ctx) :idle)))))

(deftest send-event-return-contract-test
  (testing "send-event! returns a narrowed component-level snapshot"
    (let [ctx     (create-test-ctx)
          started (turn-sc/send-event! ctx :turn/start)]
      (is (= :text-accumulating (:turn-phase started)))
      (is (= (turn-sc/get-turn-data ctx) (:turn-data started)))
      (let [updated (turn-sc/send-event! ctx :turn/text-delta {:delta "hi"})]
        (is (= :text-accumulating (:turn-phase updated)))
        (is (= (turn-sc/get-turn-data ctx) (:turn-data updated)))
        (is (= "hi" (get-in updated [:turn-data :text-buffer])))))))
