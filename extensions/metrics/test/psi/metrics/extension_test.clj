(ns psi.metrics.extension-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]
   [psi.extension-test-helpers.nullable-api :as nullable]
   [psi.metrics.extension :as ext]
   [psi.metrics.schema :as schema]))

;;; Fixtures

(use-fixtures :each
  (fn [f]
    ;; Reset the defonce atoms between tests so each test starts clean.
    (reset! ext/store nil)
    (reset! ext/writing? false)
    (f)
    (reset! ext/store nil)
    (reset! ext/writing? false)))

;;; Helpers

(defn- make-api
  "Create a nullable extension API augmented with :register-operation support.
   opts are forwarded to create-nullable-extension-api.
   Returns {:api ... :state atom :ops atom}."
  ([] (make-api {}))
  ([opts]
   (let [{:keys [api state]} (nullable/create-nullable-extension-api opts)
         ops (atom [])
         api* (assoc api
                     :register-operation
                     (fn [op-spec]
                       (swap! ops conj op-spec)
                       nil))]
     {:api api* :state state :ops ops})))

(defn- fire-event
  "Fire all registered handlers for event-name with payload."
  [state event-name payload]
  (doseq [h (get-in @state [:handlers event-name])]
    (h payload)))

;;; init registration

(deftest init-registers-provider-and-core-event-handlers-test
  ;; init subscribes to tool_call, tool_result, session_turn_finished, and provider telemetry events.
  (let [{:keys [api state]} (make-api)]
    (ext/init api)
    (is (seq (get-in @state [:handlers "tool_call"])))
    (is (seq (get-in @state [:handlers "tool_result"])))
    (is (seq (get-in @state [:handlers "session_turn_finished"])))
    (is (seq (get-in @state [:handlers "provider_request_started"])))
    (is (seq (get-in @state [:handlers "provider_retry_scheduled"])))
    (is (seq (get-in @state [:handlers "provider_request_finished"])))))

(deftest init-registers-metrics-summary-operation-test
  ;; init registers the metrics/summary deterministic operation via :register-operation.
  (let [{:keys [api ops]} (make-api)]
    (ext/init api)
    (let [registered (filter #(= "metrics/summary" (:id %)) @ops)]
      (is (= 1 (count registered)))
      (is (fn? (:handler (first registered)))))))

(deftest init-registers-metrics-command-test
  ;; init registers the /metrics slash command when :register-command is available.
  (let [{:keys [api state]} (make-api)]
    (ext/init api)
    (is (contains? (:commands @state) "metrics"))))

(deftest init-returns-nil-test
  ;; init must return nil.
  (let [{:keys [api]} (make-api)]
    (is (nil? (ext/init api)))))

;;; tool_call event

(deftest tool-call-increments-invocation-counter-test
  ;; tool_call events increment the invocation counter for the named tool.
  (let [{:keys [api state]} (make-api)]
    (ext/init api)
    (fire-event state "tool_call" {:tool-name "bash" :tool-call-id "c1" :input {}})
    (fire-event state "tool_call" {:tool-name "bash" :tool-call-id "c2" :input {}})
    (fire-event state "tool_call" {:tool-name "read" :tool-call-id "c3" :input {}})
    (let [metrics (:metrics @ext/store)]
      (is (= 2 (get-in metrics [:tools "bash" :invocations])))
      (is (= 1 (get-in metrics [:tools "read" :invocations]))))))

(deftest tool-call-without-tool-name-is-ignored-test
  ;; tool_call events with no :tool-name are silently ignored.
  (let [{:keys [api state]} (make-api)]
    (ext/init api)
    (fire-event state "tool_call" {:tool-call-id "c1" :input {}})
    (is (= {} (get-in @ext/store [:metrics :tools])))))

;;; tool_result event

(deftest tool-result-error-increments-error-counter-test
  ;; tool_result with :is-error true increments the error counter.
  (let [{:keys [api state]} (make-api)]
    (ext/init api)
    (fire-event state "tool_result"
                {:tool-name "bash" :tool-call-id "c1" :is-error true :content "Command not found"})
    (let [metrics (:metrics @ext/store)]
      (is (= 1 (get-in metrics [:tools "bash" :errors])))
      (is (= 1 (get-in metrics [:tools "bash" :error-reasons "Command not found"]))))))

(deftest tool-result-success-does-not-increment-error-counter-test
  ;; tool_result with :is-error false does not touch error counters.
  (let [{:keys [api state]} (make-api)]
    (ext/init api)
    (fire-event state "tool_result"
                {:tool-name "bash" :tool-call-id "c1" :is-error false :content "ok"})
    (let [metrics (:metrics @ext/store)]
      (is (nil? (get-in metrics [:tools "bash" :errors]))))))

(deftest tool-result-error-reason-extracts-text-from-content-blocks-test
  ;; On both paths :content is normalised to a vec of {:type :text :text ...}
  ;; blocks. The reason key must be the human-readable :text, not a stringified
  ;; data structure.
  (let [{:keys [api state]} (make-api)]
    (ext/init api)
    (fire-event state "tool_result"
                {:tool-name "bash" :tool-call-id "c1" :is-error true
                 :content [{:type :text :text "boom: command failed"}]})
    (let [reasons (get-in @ext/store [:metrics :tools "bash" :error-reasons])]
      (is (= 1 (count reasons)))
      (is (= 1 (get reasons "boom: command failed"))
          "reason key is the human-readable :text from the content block"))))

(deftest tool-result-error-reason-joins-multiple-content-blocks-test
  ;; Multiple text blocks are joined; the first-line/80-char rule then applies.
  (let [{:keys [api state]} (make-api)]
    (ext/init api)
    (fire-event state "tool_result"
                {:tool-name "bash" :tool-call-id "c1" :is-error true
                 :content [{:type :text :text "first part"}
                           {:type :text :text "second part"}]})
    (let [reasons (get-in @ext/store [:metrics :tools "bash" :error-reasons])]
      (is (= 1 (count reasons)))
      (is (= 1 (get reasons "first part second part"))
          "text blocks joined into a single reason key"))))

(deftest tool-result-error-reason-non-text-blocks-dropped-test
  ;; content->text uses (keep :text content): non-text blocks (no :text key,
  ;; e.g. image blocks preserved by normalize-tool-content) are silently
  ;; dropped. Pin the chosen behaviour at the boundary:
  ;; - image-only error content -> empty-string reason key
  ;; - mixed text+image -> text-only key (image dropped)
  (let [{:keys [api state]} (make-api)]
    (ext/init api)
    (fire-event state "tool_result"
                {:tool-name "bash" :tool-call-id "c1" :is-error true
                 :content [{:type :image :data "x"}]})
    (let [reasons (get-in @ext/store [:metrics :tools "bash" :error-reasons])]
      (is (= {"" 1} reasons)
          "image-only content has no :text, so the reason key is empty"))
    (fire-event state "tool_result"
                {:tool-name "read" :tool-call-id "c2" :is-error true
                 :content [{:type :text :text "boom"} {:type :image :data "x"}]})
    (let [reasons (get-in @ext/store [:metrics :tools "read" :error-reasons])]
      (is (= {"boom" 1} reasons)
          "mixed content keeps text blocks only; image block dropped"))))

(deftest tool-result-error-reason-multiline-truncated-to-first-line-80-chars-test
  ;; Multi-line error content >80 chars: reason is the first line, trimmed,
  ;; capped at 80 chars (single key, no StringIndexOutOfBounds on the bound).
  (let [{:keys [api state]} (make-api)
        long-first-line (apply str (repeat 100 "x"))
        content         (str "   " long-first-line "\nsecond line\nthird line")]
    (ext/init api)
    (fire-event state "tool_result"
                {:tool-name "read" :tool-call-id "c1" :is-error true :content content})
    (let [reasons (get-in @ext/store [:metrics :tools "read" :error-reasons])
          reason  (first (keys reasons))]
      (is (= 1 (count reasons)))
      (is (= 80 (count reason)))
      (is (= (apply str (repeat 80 "x")) reason)))))

;;; session_turn_finished event — token accumulation

(deftest turn-finished-accumulates-token-delta-per-model-test
  ;; session_turn_finished queries usage and accumulates delta under the model-id key.
  (let [query-session-fn (fn [_session-id _eql]
                           {:psi.agent-session/usage-input 100
                            :psi.agent-session/usage-output 50
                            :psi.agent-session/usage-cache-read 0
                            :psi.agent-session/usage-cache-write 0
                            :psi.agent-session/model-id "claude-3"})
        {:keys [api state]} (make-api {:query-fn (fn [_q] {})})
        api* (assoc api :query-session query-session-fn)]
    (ext/init api*)
    (fire-event state "session_turn_finished" {:session-id "s1" :turn-id "t1"})
    (let [metrics (:metrics @ext/store)]
      (is (= 100 (get-in metrics [:tokens "claude-3" :input])))
      (is (= 50  (get-in metrics [:tokens "claude-3" :output]))))))

(deftest turn-finished-computes-delta-on-second-turn-test
  ;; The second turn for a session contributes only the incremental delta.
  (let [call-count (atom 0)
        responses  [{:psi.agent-session/usage-input 100
                     :psi.agent-session/usage-output 50
                     :psi.agent-session/usage-cache-read 0
                     :psi.agent-session/usage-cache-write 0
                     :psi.agent-session/model-id "claude-3"}
                    {:psi.agent-session/usage-input 160
                     :psi.agent-session/usage-output 80
                     :psi.agent-session/usage-cache-read 0
                     :psi.agent-session/usage-cache-write 0
                     :psi.agent-session/model-id "claude-3"}]
        query-session-fn (fn [_session-id _eql]
                           (let [idx @call-count]
                             (swap! call-count inc)
                             (nth responses idx nil)))
        {:keys [api state]} (make-api {:query-fn (fn [_q] {})})
        api* (assoc api :query-session query-session-fn)]
    (ext/init api*)
    (fire-event state "session_turn_finished" {:session-id "s1" :turn-id "t1"})
    (fire-event state "session_turn_finished" {:session-id "s1" :turn-id "t2"})
    (let [metrics (:metrics @ext/store)]
      ;; Total input: 100 (turn1) + 60 (turn2 delta: 160-100) = 160.
      (is (= 160 (get-in metrics [:tokens "claude-3" :input])))
      ;; Total output: 50 + 30 = 80.
      (is (= 80  (get-in metrics [:tokens "claude-3" :output]))))))

(deftest turn-finished-swallows-query-error-and-returns-nil-test
  ;; When query-session throws, make-turn-finished-handler must swallow the
  ;; exception, return nil, and leave the metrics store unchanged (the
  ;; design's swallow-and-return-nil acceptance criterion).
  (let [query-session-fn (fn [_session-id _eql]
                           (throw (ex-info "boom" {})))
        {:keys [api state]} (make-api {:query-fn (fn [_q] {})})
        api* (assoc api :query-session query-session-fn)]
    (ext/init api*)
    (let [metrics-before (:metrics @ext/store)
          handler        (first (get-in @state [:handlers "session_turn_finished"]))]
      (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
          "handler returns nil even when the query throws")
      (is (= metrics-before (:metrics @ext/store))
          "metrics store is unchanged when token tracking is skipped"))))

(deftest turn-finished-uses-unknown-when-model-id-nil-test
  ;; When model-id is nil, token delta is accumulated under "unknown".
  (let [query-session-fn (fn [_session-id _eql]
                           {:psi.agent-session/usage-input 10
                            :psi.agent-session/usage-output 5
                            :psi.agent-session/usage-cache-read 0
                            :psi.agent-session/usage-cache-write 0
                            :psi.agent-session/model-id nil})
        {:keys [api state]} (make-api {:query-fn (fn [_q] {})})
        api* (assoc api :query-session query-session-fn)]
    (ext/init api*)
    (fire-event state "session_turn_finished" {:session-id "s1" :turn-id "t1"})
    (is (= 10 (get-in @ext/store [:metrics :tokens "unknown" :input])))))

;;; metrics/summary operation

(deftest invoke-summary-returns-ok-with-schema-conforming-data-test
  ;; metrics/summary returns {:status :ok :data <metrics-map>} conforming to schema.
  (let [{:keys [api ops state]} (make-api)]
    (ext/init api)
    (fire-event state "tool_call" {:tool-name "read" :tool-call-id "c1" :input {}})
    (let [op-handler (:handler (first (filter #(= "metrics/summary" (:id %)) @ops)))
          result (op-handler {:args {}})]
      (is (= :ok (:status result)))
      (is (schema/valid? (:data result))))))

;;; /metrics command

(deftest metrics-command-calls-notify-with-markdown-test
  ;; The /metrics command calls (:notify api) with a markdown-formatted string.
  (let [{:keys [api state]} (make-api)]
    (ext/init api)
    (fire-event state "tool_call" {:tool-name "bash" :tool-call-id "c1" :input {}})
    (let [cmd-handler (get-in @state [:commands "metrics" :handler])]
      (cmd-handler {})
      (let [msgs (:messages @state)]
        (is (seq msgs))
        (is (str/includes? (:content (last msgs)) "## Usage Metrics"))))))

(deftest metrics-command-includes-tool-section-when-tools-tracked-test
  ;; The /metrics output includes a Tools section when tool invocations exist.
  (let [{:keys [api state]} (make-api)]
    (ext/init api)
    (fire-event state "tool_call" {:tool-name "edit" :tool-call-id "c1" :input {}})
    (let [cmd-handler (get-in @state [:commands "metrics" :handler])]
      (cmd-handler {})
      (is (str/includes? (:content (last (:messages @state))) "### Tools")))))

(deftest metrics-command-shows-commands-section-when-invoked-test
  ;; Invoking /metrics self-tracks the invocation under :commands.
  ;; Even with no other events, the commands section appears.
  (let [{:keys [api state]} (make-api)]
    (ext/init api)
    (let [cmd-handler (get-in @state [:commands "metrics" :handler])]
      (cmd-handler {})
      (let [content (:content (last (:messages @state)))]
        (is (str/includes? content "## Usage Metrics"))
        (is (str/includes? content "metrics"))))))

;;; Reload behaviour

(deftest reload-preserves-counters-test
  ;; A second call to init (simulating reload) preserves existing counters.
  (let [{:keys [api state]} (make-api)]
    (ext/init api)
    (fire-event state "tool_call" {:tool-name "bash" :tool-call-id "c1" :input {}})
    ;; Simulate reload: call init again.
    (ext/init api)
    (is (= 1 (get-in @ext/store [:metrics :tools "bash" :invocations])))))

;;; Schema conformance of returned data

(deftest provider-events-update-metrics-and-render-provider-section-test
  (let [{:keys [api state]} (make-api)]
    (ext/init api)
    (fire-event state "provider_request_started"
                {:provider "openai" :model-id "gpt-5.4" :session-id "s1" :turn-id "t1"})
    (fire-event state "provider_retry_scheduled"
                {:provider "openai" :model-id "gpt-5.4" :session-id "s1" :turn-id "t1" :delay-ms 2000})
    (fire-event state "provider_request_finished"
                {:provider "openai" :model-id "gpt-5.4" :session-id "s1" :turn-id "t1"
                 :status :failed :final? true :error-kind :rate-limit})
    (let [metrics (:metrics @ext/store)
          cmd-handler (get-in @state [:commands "metrics" :handler])]
      (is (= 1 (get-in metrics [:providers "openai" :requests])))
      (is (= 1 (get-in metrics [:providers "openai" :retries])))
      (is (= 2000 (get-in metrics [:providers "openai" :retry-backoff-ms])))
      (is (= 1 (get-in metrics [:providers "openai" :failures])))
      (is (= 1 (get-in metrics [:providers "openai" :final-failures])))
      (is (= 1 (get-in metrics [:providers "openai" :error-types "rate-limit"])))
      (cmd-handler {})
      (let [content (:content (last (:messages @state)))]
        (is (str/includes? content "### Providers"))
        (is (str/includes? content "### Provider Models"))
        (is (str/includes? content "openai"))))))

(deftest summary-data-conforms-to-schema-after-events-test
  ;; After processing several events the metrics map still conforms to schema.
  (let [{:keys [api ops state]} (make-api)]
    (ext/init api)
    (fire-event state "tool_call" {:tool-name "bash" :tool-call-id "c1" :input {}})
    (fire-event state "tool_result" {:tool-name "bash" :tool-call-id "c1"
                                     :is-error true :content "fail"})
    (fire-event state "provider_request_started"
                {:provider "openai" :model-id "gpt-5.4" :session-id "s1" :turn-id "t1"})
    (fire-event state "provider_request_finished"
                {:provider "openai" :model-id "gpt-5.4" :session-id "s1" :turn-id "t1"
                 :status :succeeded :final? true})
    (let [op-handler (:handler (first (filter #(= "metrics/summary" (:id %)) @ops)))
          result (op-handler {:args {}})]
      (is (schema/valid? (:data result))))))
