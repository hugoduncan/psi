(ns psi.rpc-prompt-test
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.ai.models :as ai-models]
   [psi.agent-session.core :as session]
   [psi.app-runtime.retry-display :as retry-display]
   [psi.rpc.events :as rpc.events]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.tools :as tools]
   [psi.rpc.session.emit :as rpc.emit]
   [psi.rpc.session.streams :as streams]
   [psi.rpc.state :as rpc.state]
   [psi.turn-runtime.core :as turn-runtime]
   [psi.rpc-test-support :as support]))

(deftest rpc-prompt-streams-events-and-interleaves-test
  (testing "prompt emits canonical events that interleave with accepted response"
    (let [[ctx _] (support/create-session-context)
          _   (session/dispatch-in! ctx :session/ui-set-status {:extension-id "ext.demo" :text "ready"} {:origin :test})
          state (atom {:transport {:ready? true :pending {}}
                       :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                       :execute-prepared-request-fn (fn [_ai-ctx _ctx _session-id _prepared-request progress-queue]
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :text-delta :text "Hello" :type :agent-event})
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :thinking-delta :text "thinking..." :type :agent-event})
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :tool-start :tool-id "tc-1" :tool-name "read" :type :agent-event})
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :tool-result
                                                               :tool-id "tc-1"
                                                               :tool-name "read"
                                                               :content [{:type :text :text "done"}]
                                                               :result-text "done"
                                                               :details nil
                                                               :is-error false
                                                               :type :agent-event})
                                                      (support/assistant-msg->execution-result _session-id {:role "assistant" :content [{:type :text :text "Hello final"}] :stop-reason :stop :usage {:total-tokens 3}}))})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"p1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/delta\" \"assistant/thinking-delta\" \"assistant/message\" \"tool/start\" \"tool/result\" \"session/updated\" \"footer/updated\"]}}\n"
                       "{:id \"r1\" :kind :request :op \"prompt\" :params {:message \"hi\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state 250)
          frames (->> out-lines
                      (keep (fn [line]
                              (try
                                (edn/read-string line)
                                (catch Throwable _ nil))))
                      vec)
          response-index (first (keep-indexed (fn [i f] (when (and (= :response (:kind f)) (= "prompt" (:op f))) i)) frames))
          event-indexes  (keep-indexed (fn [i f] (when (= :event (:kind f)) i)) frames)
          seqs           (->> frames (filter #(= :event (:kind %))) (map :seq) (remove nil?))
          topics         (->> frames (filter #(= :event (:kind %))) (map :event) set)]
      (is (number? response-index))
      (is (seq event-indexes))
      (is (some #(< response-index %) event-indexes))
      (is (contains? topics "assistant/delta"))
      (is (contains? topics "assistant/thinking-delta"))
      (is (contains? topics "assistant/message"))
      (is (contains? topics "tool/start"))
      (is (contains? topics "tool/result"))
      (is (contains? topics "session/updated"))
      (is (contains? topics "footer/updated"))
      (is (= seqs (sort seqs)))
      (is (every? #(contains? % :data) (filter #(= :event (:kind %)) frames)))
      (is (contains? #{:response :event} (:kind (last frames)))))))

(deftest rpc-prompt-footer-updated-tolerates-keyword-sentinel-values-test
  (testing "prompt completion does not fail when footer query returns keyword sentinels"
    (let [[ctx _] (support/create-session-context)
          state (atom {:transport {:ready? true :pending {}}
                       :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                       :execute-prepared-request-fn (fn [_ai-ctx _ctx _session-id _prepared-request progress-queue]
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :text-delta :text "Hello" :type :agent-event})
                                                      (support/assistant-msg->execution-result _session-id {:role "assistant" :content [{:type :text :text "Hello final"}] :stop-reason :stop :usage {:total-tokens 3}}))})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"p1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/delta\" \"assistant/message\" \"session/updated\" \"footer/updated\" \"error\"]}}\n"
                       "{:id \"r1\" :kind :request :op \"prompt\" :params {:message \"hi\"}}\n")
          footer-data {:psi.agent-session/worktree-path "/repo/project"
                       :psi.agent-session/git-branch :pathom/unknown
                       :psi.agent-session/session-display-name :pathom/unknown
                       :psi.agent-session/context-window 400000
                       :psi.agent-session/model-provider :pathom/unknown
                       :psi.agent-session/model-id "stub"
                       :psi.agent-session/model-reasoning false
                       :psi.agent-session/thinking-level :off
                       :psi.ui/statuses :pathom/unknown}
          orig-query-in session/query-in
          {:keys [out-lines]}
          (with-redefs [session/query-in
                        (fn
                          ([ctx q]
                           (if (= @#'rpc.events/footer-query q)
                             footer-data
                             (orig-query-in ctx q)))
                          ([ctx x y]
                           (if (or (= @#'rpc.events/footer-query x)
                                   (= @#'rpc.events/footer-query y))
                             footer-data
                             (orig-query-in ctx x y)))
                          ([ctx session-id q extra-entity]
                           (if (= @#'rpc.events/footer-query q)
                             footer-data
                             (orig-query-in ctx session-id q extra-entity))))]
            (support/run-loop input handler state 250))
          frames         (support/parse-frames out-lines)
          prompt-frame   (some #(when (and (= :response (:kind %))
                                           (= "prompt" (:op %))) %) frames)
          assistant-evt  (some #(when (= "assistant/message" (:event %)) %) frames)
          footer-events  (filterv #(= "footer/updated" (:event %)) frames)
          runtime-failed (filterv #(= "runtime/failed"
                                      (or (:error-code %)
                                          (get-in % [:data :error-code])))
                                  frames)]
      (is (some? prompt-frame))
      (is (true? (get-in prompt-frame [:data :accepted])))
      (is (some? assistant-evt))
      (is (seq footer-events))
      (is (= "/repo/project"
             (get-in (last footer-events) [:data :path-line])))
      (is (empty? runtime-failed)))))

(def ^:private retry-footer-sync-timeout-ms
  "Bounded deadline for the deterministic retry-footer sync used by the
   retry/footer E2E tests' `:provider-retry-sleep-fn`. Single authority so the
   bound has one place to tune; both harnesses now route their sleep-fn through
   the shared `await-retry-footer-text!` helper, which consumes this deadline."
  500)

(defn- frame-status-line
  "Single nil-safe accessor for a `footer/updated` frame's status-line text,
   returning `\"\"` when absent (task 242 Slice 17). One authority for the
   `[:data :status-line]` path so a frame-shape change is edited once, not at
   every retry-footer matcher/predicate site."
  [frame]
  (or (get-in frame [:data :status-line]) ""))

(defn- footer-updated-frames
  "Single selector for `footer/updated` frames in a captured-frames coll (task
   242 Slice 20). One authority for the `\"footer/updated\"` event-topic literal
   + `:event` frame path so a change to the event name / frame shape is edited
   once, and a typo in the topic string cannot silently filter to `[]` and pass
   downstream `empty?`/`seq` assertions vacuously at each retry-footer site."
  [frames]
  (filterv #(= "footer/updated" (:event %)) frames))

(defn- await-retry-footer-text!
  "Blocks (bounded) until `captured` contains a frame whose `:data
   :status-line` includes `expected-text`. Used as a `:provider-retry-sleep-fn`
   by both retry-footer E2E harnesses
   (`rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`
   and `rpc-prompt-provider-retry-state-publishes-footer-updated-test`) so the
   retry state stays live until the progress loop has actually delivered the
   corresponding footer/updated frame, avoiding a race where the retry clears
   before the async progress loop drains it.

   Fails fast (rather than returning silently) if the awaited footer never
   arrives within the bound: `support/await-until` returns
   `support/timeout-token` on timeout, so a swallowed timeout would let the
   retry clear before delivery and surface later as a generic \"retry in Ns
   not found\" assertion — indistinguishable from a genuine focus-gate
   regression. Detecting the timeout here names the missing text, so a sync
   timeout is diagnosable as its own failure."
  [captured expected-text]
  (let [result (support/await-until
                #(some (fn [frame]
                         (str/includes? (frame-status-line frame) expected-text))
                       @captured)
                retry-footer-sync-timeout-ms)]
    (is (not= support/timeout-token result)
        (str "retry footer sync timed out awaiting status-line text: " expected-text))
    result))

(def ^:private active-retry-text-prefix
  "Single authority for the active-retry status-line prefix (task 242 Slice 19).
   Both the positive `expected-retry-text` builder and the `retry-status-line?`
   substring predicate share this so the positive-match and negated-clear
   spellings of \"active retry text\" cannot diverge on a footer-format change."
  "retry in ")

(defn- expected-retry-text
  "Single authority for the retry-footer status-line text a retry of `delay-ms`
   produces (`\"retry in Ns\"`). Derives the seconds from the *same* production
   authority the footer uses — `retry-display/format-relative-seconds`
   (`Math/ceil`) — rather than a `(quot delay-ms 1000)` floor approximation, so
   the deterministic sync waits for the text production will actually emit
   instead of one that coincides only for whole-second `Retry-After` values
   (task 242 Slice 16). `resume-at = now₀ + delay-ms`, so at delivery the footer
   shows `ceil((resume-at - now-delivery)/1000)`; matching production's `ceil`
   formula keeps the awaited text aligned even for non-whole-second delays
   (sub-`retry-footer-sync-timeout-ms` delivery drift stays within the same
   second)."
  [delay-ms]
  (str active-retry-text-prefix (retry-display/format-relative-seconds (long delay-ms))))

;; Shared retry-frame matchers (task 242 Slice 17). The `8000`/`4000` activation
;; and changed delays are the delivered-footer form of
;; `drive-provider-retry-through-progress-loop!`'s first/second `Retry-After`
;; headers (8s/4s), and the `remaining` fragment is that second attempt's
;; `RateLimit-Remaining`/`RateLimit-Limit` (2/5000). Deriving the activation and
;; changed text from the single `expected-retry-text` authority (the same one
;; the sync-side sleep-fn uses, aligned to production's `ceil` in Slice 16)
;; rather than re-inlining raw `"retry in 8s"`/`"retry in 4s"` literals keeps the
;; assertion matchers from drifting from the config that produces them: a
;; footer-format or delay change updates one place, not ≥3 assertion sites.

;; Single authorities for the retry delays / rate-limit metadata (task 242
;; Slices 17/18). `drive-provider-retry-through-progress-loop!` derives its 429
;; `error-turn` headers *from* these constants (ms → whole-second `Retry-After`,
;; and the rate-limit values), and the assertion matchers derive their awaited
;; footer text from them too — so the driver config and the matchers cannot
;; drift (a delay/rate-limit change updates one place, not both the driver
;; headers and the matcher constants).

(def ^:private activation-retry-delay-ms
  "First-attempt retry delay. Single authority: `drive-provider-retry-through-progress-loop!`
   derives its first 429 `Retry-After` header from this (ms → seconds), and the
   activation matcher derives its awaited text from it."
  8000)

(def ^:private changed-retry-delay-ms
  "Second-attempt retry delay. Single authority: `drive-provider-retry-through-progress-loop!`
   derives its second 429 `Retry-After` header from this (ms → seconds), and the
   changed-metadata matcher derives its awaited text from it."
  4000)

(def ^:private retry-rate-limit
  "Second 429's `RateLimit-Limit`. Single authority: driven into the
   `error-turn` header and matched by the changed-retry footer's
   `remaining R/L` fragment."
  5000)

(def ^:private changed-retry-remaining
  "Second 429's `RateLimit-Remaining`. Single authority: driven into the
   `error-turn` header and matched by the changed-retry footer's
   `remaining R/L` fragment."
  2)

(defn- retry-after-seconds
  "Whole-second `Retry-After` header value the driver emits for a retry
   `delay-ms`, so the driver header and the matcher constant share one
   authority."
  [delay-ms]
  (str (quot (long delay-ms) 1000)))

(defn- remaining-fragment
  "The `\"remaining R/L\"` status-line fragment for a changed-retry footer,
   derived from the rate-limit metadata `drive-provider-retry-through-progress-loop!`'s
   second 429 supplies (`RateLimit-Remaining`, `RateLimit-Limit`). Built from the
   *same production authority* the footer uses — `retry-display/retry-status-text`
   (which composes `\"remaining \" + \"R/L\"`) — rather than a hand-rolled
   `(str \"remaining \" remaining \"/\" limit)` copy, so a footer-format change to
   the `\"remaining \"` prefix or the `\"/\"` separator in `retry_display.clj`
   tracks the matcher automatically (task 242 Slice 21). The `retry-status-text`
   output joins fragments with `\" · \"`; the remaining fragment is extracted as
   the part after the leading `\"retry in Ns · \"` delay fragment."
  [remaining limit]
  (let [now-ms 0
        status-line (retry-display/retry-status-text
                     {:active? true
                      :resume-at now-ms
                      :rate-limit {:remaining remaining :limit limit}}
                     now-ms)]
    ;; Drop the leading delay fragment ("retry in 0s · "), leaving "remaining R/L".
    (second (str/split status-line #" · " 2))))

(defn- retry-footer-session-context!
  "Builds the non-persisted `[ctx session-id]` the retry-footer E2E harnesses
   share (task 242 Slice 18): a single authority for the retry test session
   config so the `:auto-retry-base-delay-ms` (the same first-attempt delay
   `activation-retry-delay-ms` / the driver `Retry-After` encode) and
   `:auto-retry-max-retries` are not duplicated at each `create-session-context`
   site."
  []
  (support/create-session-context
   {:persist? false
    :config {:auto-retry-base-delay-ms activation-retry-delay-ms
             :auto-retry-max-retries 2}}))

(defn- activation-retry-footer?
  "Matches the retry-*activation* footer frame (first attempt: `\"retry in 8s\"`)."
  [frame]
  (str/includes? (frame-status-line frame)
                 (expected-retry-text activation-retry-delay-ms)))

(defn- changed-retry-footer?
  "Matches the *changed-metadata* retry footer frame (second attempt:
   `\"retry in 4s\"` + `\"remaining 2/5000\"`)."
  [frame]
  (let [status-line (frame-status-line frame)]
    (and (str/includes? status-line (expected-retry-text changed-retry-delay-ms))
         (str/includes? status-line (remaining-fragment changed-retry-remaining
                                                        retry-rate-limit)))))

(defn- retry-footer-sleep-fn
  "Builds the deterministic `:provider-retry-sleep-fn` used by the retry-footer
   E2E harnesses: for each retry `delay-ms` it blocks (bounded) until `captured`
   has delivered the corresponding `\"retry in Ns\"` footer, keeping the retry
   state live until the async progress loop drains it. Constructs the sleep-fn
   once from `captured`, folding the delay→text derivation in (via
   `expected-retry-text`) so it is no longer hand-built per call site."
  [captured]
  (fn [delay-ms]
    (await-retry-footer-text! captured (expected-retry-text delay-ms))))

(defn- retry-status-line?
  "A `footer/updated` frame whose `:status-line` still carries active-retry
   text (`\"retry in Ns\"`). Shares `active-retry-text-prefix` with
   `expected-retry-text` so the positive-match and negated-clear checks recognise
   the same active-retry literal (task 242 Slice 19)."
  [frame]
  (str/includes? (frame-status-line frame) active-retry-text-prefix))

(defn- clear-footer-produced-after-retry
  "Positive control for the retry→inactive **clear** footer (task 242 Slice 14):
   returns the `footer/updated` frame that follows the last active-retry frame,
   or `nil` if no footer was emitted after the retry frames. A non-nil result
   proves the clear path actually *produced* a distinguishable footer after the
   retry sequence — unlike a bare negative on `(last footer-events)`, which
   passes both when a real clear footer landed last and when some unrelated
   non-retry footer incidentally trailed with no clear ever emitted."
  [footer-events]
  (let [last-retry-idx (some (fn [[i frame]] (when (retry-status-line? frame) i))
                             (reverse (map-indexed vector footer-events)))]
    (when (and last-retry-idx
               (< (inc last-retry-idx) (count footer-events)))
      (nth footer-events (inc last-retry-idx)))))

(defn- drive-provider-retry-through-progress-loop!
  "Runs the real provider-boundary retry → progress-queue → footer-refresh
   pipeline (`mark-active-retry!` → `:retry-updated` → `footer-refresh-progress-event?`
   → `emit-footer-updated!`) with `emit!` supplied by the caller, then blocks
   until the retry sequence (activate → change → clear) completes."
  [ctx session-id emit!]
  (let [attempts*  (atom 0)
        progress-q (java.util.concurrent.LinkedBlockingQueue.)
        error-turn (fn [headers]
                     {:turn-id "turn-retry-footer"
                      :model {:provider "anthropic" :id "stub"}
                      :ai-options {}
                      :turn-ctx nil
                      :assistant-message {:role "assistant"
                                          :content [{:type :error :text "rate limit exceeded"}]
                                          :stop-reason :error
                                          :error-message "rate limit exceeded"
                                          :http-status 429
                                          :provider-error/headers headers
                                          :timestamp (java.time.Instant/now)}})
        {:keys [stop? thread]} (streams/start-progress-loop!
                                {:start-daemon-thread! (fn [f name]
                                                         (doto (Thread. ^Runnable f name)
                                                           (.setDaemon true)
                                                           (.start)))
                                 :ctx ctx
                                 :session-id session-id
                                 :emit! emit!
                                 :progress-q progress-q
                                 :thread-name "rpc-retry-footer-focus-test"})]
    (try
      ;; NOTE (task 242 test-review, deliberate bounded exception to ¬mock/¬stub):
      ;; `execute-live-turn!` is redefined here to fabricate 429/recovery turns.
      ;; A clean injectable seam does exist — `psi.ai.core/create-context` seeds
      ;; a per-ctx `:provider-registry`, and a stub provider emitting stream
      ;; `:error` events carrying `:http-status`/`:provider-error/headers` would
      ;; drive the same retry path (the stream consumer propagates those keys to
      ;; the assistant-message). Migrating to that seam is deferred: it would
      ;; also require rewriting the sibling
      ;; `rpc-prompt-provider-retry-state-publishes-footer-updated-test` and
      ;; standing up a stub provider matching the provider protocol, which
      ;; exceeds task 242's frozen behaviour-preserving test-coverage scope.
      ;; Recorded in implementation.md as a bounded, intentional exception.
      (with-redefs [turn-runtime/execute-live-turn!
                    (fn [& _]
                      (case (swap! attempts* inc)
                        1 (error-turn {"Retry-After" (retry-after-seconds activation-retry-delay-ms)
                                       "RateLimit-Limit" (str retry-rate-limit)
                                       "RateLimit-Remaining" "0"})
                        2 (error-turn {"Retry-After" (retry-after-seconds changed-retry-delay-ms)
                                       "RateLimit-Limit" (str retry-rate-limit)
                                       "RateLimit-Remaining" (str changed-retry-remaining)})
                        {:turn-id "turn-retry-footer"
                         :model {:provider "anthropic" :id "stub"}
                         :ai-options {}
                         :turn-ctx nil
                         :assistant-message {:role "assistant"
                                             :content [{:type :text :text "recovered"}]
                                             :stop-reason :stop
                                             :timestamp (java.time.Instant/now)}}))]
        (turn-runtime/execute-prepared-request!
         {:provider-registry (atom {})}
         ctx
         session-id
         {:prepared-request/id "turn-retry-footer"
          :prepared-request/model {:provider :anthropic :id "stub"}
          :prepared-request/ai-options {}
          :prepared-request/response-mode :streaming}
         progress-q)
        (streams/stop-progress-loop! {:stop? stop?
                                      :thread thread
                                      :progress-q progress-q
                                      :emit! emit!
                                      :ctx ctx
                                      :session-id session-id}))
      (finally
        (reset! stop? true)
        (.join ^Thread thread 200)))
    @attempts*))

(defn- focus-gated-emitter!
  "Builds the focus-gated `emit!` boundary the two focus-gated retry-footer
   sub-tests share (task 242 Slice 20): wires a capture atom through the real
   `rpc.events/emit-event!` → `focus-allows?` path via `make-request-emitter`,
   with `focus-session-id` set as effective focus. Returns `[emit! captured]`.
   Single authority for the emitter-construction sequence (`make-rpc-state` →
   `subscribe-topics!` → `set-focus-session-id!` → `make-request-emitter`) so
   the focused-vs-background pair cannot drift in anything but the focus
   session-id under test."
  [focus-session-id]
  (let [captured    (atom [])
        emit-frame! (fn [frame] (swap! captured conj frame))
        state       (rpc.state/make-rpc-state {:session-id focus-session-id})
        _           (rpc.state/subscribe-topics! state rpc.events/event-topics)
        _           (rpc.state/set-focus-session-id! state focus-session-id)
        emit!       (rpc.emit/make-request-emitter emit-frame! state "req-1")]
    [emit! captured]))

(deftest rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test
  ;; Regression lock (task 242): the retry footer must still reach
  ;; `emit-frame!` at the RPC focus-gate boundary (`rpc.events/emit-event!` →
  ;; `focus-allows?`) for the focused/retrying session, not just at the
  ;; pre-gate `emit!` used by task 242's earlier characterization test above.
  (testing "focused session: retry footer/updated frames pass the focus gate"
    (let [[ctx session-id] (retry-footer-session-context!)
          [emit! captured] (focus-gated-emitter! session-id)
          ctx         (assoc ctx :provider-retry-sleep-fn
                             (retry-footer-sleep-fn captured))
          attempts    (drive-provider-retry-through-progress-loop! ctx session-id emit!)]
      ;; Positive control (matches the sibling
      ;; `rpc-prompt-provider-retry-state-publishes-footer-updated-test`): prove
      ;; the full activate→change→clear retry sequence actually ran, so the
      ;; footer assertions below are credited against a live retry pipeline, not
      ;; a no-op/mis-wired one.
      (is (= 3 attempts)
          "the full activate→change→clear retry sequence must have executed")
      (let [footer-events (footer-updated-frames @captured)]
        (is (seq footer-events)
            "focused session must still receive footer/updated frames through the focus gate")
        ;; Per-frame focus gating is the regression under test: assert every
        ;; retry frame the sibling pre-gate test verifies (activation, changed
        ;; metadata, clear) also crosses the RPC focus gate, so a regression
        ;; that gates only the later frames cannot go undetected.
        (is (some activation-retry-footer? footer-events)
            "focused session must receive the retry-activation footer text through the focus gate")
        (is (some changed-retry-footer? footer-events)
            "focused session must receive the changed retry metadata footer through the focus gate")
        ;; Positive control (task 242 Slice 14): the bare negative below passes
        ;; both when a real clear footer landed last and when some unrelated
        ;; non-retry footer incidentally trailed with no clear ever emitted.
        ;; Assert a distinguishable clear footer was actually *produced* after
        ;; the last active-retry frame, so the no-stale-`retry in` check is
        ;; credited against a live clear-path emission crossing the focus gate.
        (let [clear-footer (clear-footer-produced-after-retry footer-events)]
          (is (some? clear-footer)
              "focused session must receive a clear footer/updated frame after the retry sequence through the focus gate")
          (is (not (retry-status-line? clear-footer))
              "focused session's clear footer must carry no stale retry text (through the focus gate)"))
        (is (every? #(= session-id (get-in % [:data :session-id])) footer-events)))))
  (testing "background session: retry footer/updated frames stay suppressed by design (task 241 invariant)"
    ;; Pre-gate production control (task 242 Slice 12): the gated `(is (empty?
    ;; footer-events))` below cannot, on its own, distinguish "footer frames
    ;; were produced then suppressed by `focus-allows?`" (the intended
    ;; behaviour) from "footer frames were never produced for this background
    ;; config" (a footer-production regression, e.g. in
    ;; `footer-refresh-progress-event?` matching `:retry-updated` or in
    ;; `emit-footer-updated!` / status-line construction). `(= 3 attempts)`
    ;; below only proves the retry *turns* fired, not that `footer/updated`
    ;; frames were produced. So first drive the background config through a
    ;; pre-gate raw `emit!` (no focus gate) and prove it produces ≥1 retry
    ;; footer with live `retry in Ns` text — this credits the gated `empty?`
    ;; assertion against a live-and-producing pipeline rather than a dead/no-op
    ;; one.
    ;;
    ;; DELIBERATE sleep-fn divergence (task 242 Slice 15): the pre-gate control
    ;; uses the *blocking* `retry-footer-sleep-fn`, while the gated run below
    ;; uses a *no-op* `(fn [_delay-ms] nil)` sleep. The two are NOT identical,
    ;; and cannot be:
    ;;   - The pre-gate control must positively assert `retry in Ns` *text* was
    ;;     produced. That text is only live while `:retry {:active? true}` is
    ;;     set; the async progress loop (10ms poll) reads live session data at
    ;;     delivery time. Under a no-op sleep the retry activates and clears
    ;;     before the loop polls, so every delivered footer carries a `nil`
    ;;     status-line (verified: 4 frames, all `:status-line nil`). Only the
    ;;     blocking sleep keeps the retry state live until the loop delivers the
    ;;     `retry in Ns` footer, so the production assertion is meaningful.
    ;;   - The gated run asserts only that *no* `footer/updated` frame (of any
    ;;     status-line) crosses the focus gate. That is credited by the
    ;;     synchronous `stop-progress-loop!` drain alone; it needs no live retry
    ;;     text, so the no-op sleep suffices and avoids coupling to the blocking
    ;;     helper's timing.
    ;; The divergence does not undermine the control: the two runs drive the
    ;; *same* retry scenario (identical config, identical
    ;; `drive-provider-retry-through-progress-loop!` 429→429→recovery sequence,
    ;; identical synchronous drain). Only the sleep-fn differs, and each run
    ;; uses the sleep-fn appropriate to what it must prove — production (needs
    ;; live text → blocking) vs suppression (needs only drained frames → no-op).
    ;; A footer-production regression under the background config fails the
    ;; pre-gate production assertion; the gated `empty?` proves those same
    ;; frames are dropped by `focus-allows?`.
    (let [pre-gate-captured (atom [])
          [pre-gate-ctx pre-gate-session-id] (retry-footer-session-context!)
          pre-gate-ctx (assoc pre-gate-ctx :provider-retry-sleep-fn
                              (retry-footer-sleep-fn pre-gate-captured))
          pre-gate-emit! (fn [event data]
                           (swap! pre-gate-captured conj {:event event :data data}))
          pre-gate-attempts (drive-provider-retry-through-progress-loop!
                             pre-gate-ctx pre-gate-session-id pre-gate-emit!)
          pre-gate-footers (footer-updated-frames @pre-gate-captured)]
      (is (= 3 pre-gate-attempts)
          "the full activate→change→clear retry sequence must have executed (pre-gate control)")
      (is (some retry-status-line? pre-gate-footers)
          "the background retry config must produce retry footer/updated frames absent the focus gate — otherwise the gated `empty?` assertion below is vacuous"))
    (let [[ctx session-id] (retry-footer-session-context!)
          ctx         (assoc ctx :provider-retry-sleep-fn (fn [_delay-ms] nil))
          other-session-id "some-other-focused-session"
          [emit! captured] (focus-gated-emitter! other-session-id)
          attempts    (drive-provider-retry-through-progress-loop! ctx session-id emit!)]
      ;; Positive control: `(is (empty? footer-events))` alone passes both when
      ;; the retry fired-but-was-gated (intended) and when the retry never fired
      ;; at all (e.g. the no-op sleep-fn or a mis-wired background config
      ;; silently skips the loop). Assert the full activate→change→clear retry
      ;; sequence ran (matching the sibling test) so the empty-footer assertion
      ;; is only credited when the retry pipeline is proven live.
      (is (= 3 attempts)
          "the full activate→change→clear retry sequence must have executed")
      ;; This `(is (empty? ...))` is non-vacuous only because
      ;; `drive-provider-retry-through-progress-loop!` calls
      ;; `streams/stop-progress-loop!` (which drains the progress queue
      ;; synchronously) before this assertion runs — unlike the focused sub-test,
      ;; this background sub-test has no `await-retry-footer-text!` guard, so any
      ;; change to the drain path / sleep-fn must preserve that synchronous drain
      ;; or this assertion could pass vacuously. The pre-gate production control
      ;; above drives the *same retry scenario and config* (differing only in a
      ;; blocking vs no-op sleep-fn — see the divergence note above) and proves
      ;; it *would* emit retry footers absent the gate, so `empty?` here credits
      ;; suppression, not footer-non-production.
      (let [footer-events (footer-updated-frames @captured)]
        (is (empty? footer-events)
            "retry footer for a non-focused (background) session must not leak to the focused connection")))))

(deftest rpc-prompt-provider-retry-state-publishes-footer-updated-test
  ;; Provider-boundary retry state changes drive Emacs-visible footer refreshes.
  ;; This sibling verifies the pre-gate `emit!` path (raw `emitted*` capture,
  ;; not routed through `rpc.events/emit-event!`/`focus-allows?`); the focus-gate
  ;; boundary is covered by
  ;; `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`.
  ;; The retry-driving body is shared via
  ;; `drive-provider-retry-through-progress-loop!` (parameterized on `emit!`,
  ;; returns the attempt count) so the two harnesses cannot drift in the 429
  ;; headers, attempt sequence, or progress-loop lifecycle.
  (testing "provider retry activation, visible change, and clear emit footer/updated"
    (let [emitted* (atom [])
          [ctx0 session-id] (retry-footer-session-context!)
          ctx (assoc ctx0
                     :provider-retry-sleep-fn
                     ;; Route through the hardened `await-retry-footer-text!`
                     ;; helper (shared with the focused sub-test of
                     ;; `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`)
                     ;; so a sync timeout surfaces as its own diagnosable
                     ;; failure (naming the missing text) instead of silently
                     ;; letting the retry clear and masquerading as a generic
                     ;; "must publish footer/updated" regression. `emitted*`
                     ;; frames carry `:status-line` at `[:data :status-line]`,
                     ;; the path the helper inspects.
                     (retry-footer-sleep-fn emitted*))
          ;; Pre-gate raw emit!: capture every event directly, without routing
          ;; through the RPC focus gate.
          emit! (fn [event data] (swap! emitted* conj {:event event :data data}))
          attempts (drive-provider-retry-through-progress-loop! ctx session-id emit!)
          footer-events (footer-updated-frames @emitted*)
          first-retry-footer (some #(when (activation-retry-footer? %) %) footer-events)
          changed-retry-footer (some #(when (changed-retry-footer? %) %) footer-events)
          ;; Positive control (task 242 Slice 14): the clear footer is the
          ;; footer *produced after* the last active-retry frame, not merely the
          ;; last footer overall — so the no-stale-`retry in` assertion is
          ;; credited against a live clear-path emission rather than an
          ;; incidentally-trailing unrelated footer.
          clear-footer (clear-footer-produced-after-retry footer-events)]
      (is (= 3 attempts))
      (is (some? first-retry-footer)
          "retry activation must publish footer/updated with retry text")
      (is (some? changed-retry-footer)
          "changed retry metadata must publish footer/updated with latest visible text")
      (is (some? clear-footer)
          "retry clear must publish a distinguishable footer/updated frame after the retry sequence")
      (is (= (get-in first-retry-footer [:data :session-id])
             (get-in changed-retry-footer [:data :session-id])
             (get-in clear-footer [:data :session-id])))
      (is (not (retry-status-line? clear-footer))
          "retry clear must publish a footer without stale retry text"))))

(deftest rpc-thinking-delta-after-tool-start-begins-fresh-segment-test
  (testing "post-tool thinking delta can start a fresh cumulative segment"
    (let [[ctx _] (support/create-session-context)
          state (atom {:transport {:ready? true :pending {}}
                       :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                       :execute-prepared-request-fn (fn [_ai-ctx _ctx _session-id _prepared-request progress-queue]
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :thinking-delta :text "plan-1" :type :agent-event})
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :tool-start :tool-id "tc-1" :tool-name "read" :type :agent-event})
                                                      (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
                                                              {:event-kind :thinking-delta :text "plan-2" :type :agent-event})
                                                      (support/assistant-msg->execution-result _session-id {:role "assistant" :content [{:type :text :text "done"}] :stop-reason :stop :usage {:total-tokens 3}}))})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"p1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/thinking-delta\" \"tool/start\" \"assistant/message\"]}}\n"
                       "{:id \"r1\" :kind :request :op \"prompt\" :params {:message \"hi\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state 250)
          frames (support/parse-frames out-lines)
          thinking-events (->> frames
                               (filter #(and (= :event (:kind %))
                                             (= "assistant/thinking-delta" (:event %)))))
          tool-start-index (first (keep-indexed (fn [i f]
                                                  (when (and (= :event (:kind f))
                                                             (= "tool/start" (:event f)))
                                                    i))
                                                frames))
          second-thinking-index (first (keep-indexed (fn [i f]
                                                       (when (and (= :event (:kind f))
                                                                  (= "assistant/thinking-delta" (:event f))
                                                                  (= "plan-2" (get-in f [:data :text])))
                                                         i))
                                                     frames))]
      (is (= ["plan-1" "plan-2"] (mapv #(get-in % [:data :text]) thinking-events)))
      (is (number? tool-start-index))
      (is (number? second-thinking-index))
      (is (< tool-start-index second-thinking-index)))))

(deftest rpc-openai-codex-prompt-emits-tool-events-with-final-args-test
  (testing "openai codex tool args from response.output_item.done flow through RPC tool events"
    (let [[ctx session-id]   (support/create-session-context)
          _                  (session/dispatch-in! ctx :session/set-active-tools {:session-id session-id :tool-maps [tools/bash-tool]} {:origin :core})
          state              (atom {:transport {:ready? true :pending {}}
                                    :sync-on-git-head-change? false
                                    :rpc-ai-model (ai-models/get-model :gpt-5.3-codex)})
          handler            (support/make-handler ctx state)
          requests           (atom [])
          call-n             (atom 0)
          marker             (str "rpc-codex-tool-args-" (random-uuid))
          first-sse          (str
                              "data: " (json/generate-string
                                        {:type "response.output_item.added"
                                         :output_index 0
                                         :item {:type "function_call"
                                                :id "fc_1"
                                                :call_id "call_1"
                                                :name "bash"
                                                :arguments ""}}) "\n\n"
                              "data: " (json/generate-string
                                        {:type "response.output_item.done"
                                         :output_index 0
                                         :item {:type "function_call"
                                                :id "fc_1"
                                                :call_id "call_1"
                                                :name "bash"
                                                :arguments "{\"command\":\"pwd\"}"}}) "\n\n"
                              "data: " (json/generate-string
                                        {:type "response.completed"
                                         :response {:status "completed"}}) "\n\n")
          second-sse         (str
                              "data: " (json/generate-string
                                        {:type "response.output_item.added"
                                         :item {:type "message"
                                                :id "msg_2"
                                                :role "assistant"
                                                :status "in_progress"
                                                :content []}}) "\n\n"
                              "data: " (json/generate-string
                                        {:type "response.output_text.delta"
                                         :delta "Final response"}) "\n\n"
                              "data: " (json/generate-string
                                        {:type "response.completed"
                                         :response {:status "completed"}}) "\n\n")
          input              (str
                              "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                              "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"tool/start\" \"tool/executing\" \"tool/result\" \"assistant/message\"]}}\n"
                              "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"run pwd " marker "\"}}\n")
          {:keys [out-lines]}
          (with-redefs [runtime/resolve-api-key-in (fn [_ctx _session-id _model] support/openai-chatgpt-test-token)
                        http/post (fn [url req]
                                    (swap! requests conj {:url url :req req})
                                    (let [n (swap! call-n inc)]
                                      {:body (support/stream-body (if (= 1 n) first-sse second-sse))}))]
            (support/run-loop input handler state 900))
          relevant-requests (->> @requests
                                 (filter (fn [{:keys [url req]}]
                                           (and (= "https://chatgpt.com/backend-api/codex/responses" url)
                                                (str/includes? (str (:body req)) marker))))
                                 vec)
          frames         (support/parse-frames out-lines)
          events         (filter #(= :event (:kind %)) frames)
          prompt-frame   (some #(when (and (= :response (:kind %))
                                           (= "prompt" (:op %))) %) frames)
          tool-start-evt  (some #(when (and (= "tool/start" (:event %))
                                            (= "call_1|fc_1" (get-in % [:data :tool-id]))
                                            (= "bash" (get-in % [:data :tool-name]))) %) events)
          tool-exec-evt   (some #(when (and (= "tool/executing" (:event %))
                                            (= "call_1|fc_1" (get-in % [:data :tool-id]))
                                            (= "bash" (get-in % [:data :tool-name]))) %) events)
          tool-result-evt (some #(when (and (= "tool/result" (:event %))
                                            (= "call_1|fc_1" (get-in % [:data :tool-id]))
                                            (= "bash" (get-in % [:data :tool-name]))) %) events)
          assistant-evt   (some #(when (= "assistant/message" (:event %)) %) events)]
      (is (some? prompt-frame))
      (is (true? (get-in prompt-frame [:data :accepted])))
      (is (= 2 (count relevant-requests)))
      (is (= (str "Bearer " support/openai-chatgpt-test-token)
             (get-in (first relevant-requests) [:req :headers "Authorization"])))
      (is (= "acc_test"
             (get-in (first relevant-requests) [:req :headers "chatgpt-account-id"])))
      (let [body (json/parse-string (get-in (first relevant-requests) [:req :body]) true)]
        (is (= "gpt-5.3-codex" (:model body)))
        (is (= true (:stream body)))
        (is (= "bash" (get-in body [:tools 0 :name]))))
      (is (= "call_1|fc_1" (get-in tool-start-evt [:data :tool-id])))
      (is (= "bash" (get-in tool-start-evt [:data :tool-name])))
      (is (= {"command" "pwd"}
             (get-in tool-exec-evt [:data :parsed-args])))
      (is (false? (get-in tool-result-evt [:data :is-error])))
      (is (string? (get-in tool-result-evt [:data :result-text])))
      (is (not (str/blank? (get-in tool-result-evt [:data :result-text]))))
      (is (= "assistant" (get-in assistant-evt [:data :role])))
      (is (some #(= "Final response" (:text %))
                (get-in assistant-evt [:data :content]))))))
