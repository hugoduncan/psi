(ns psi.agent-session.tool-execution-test
  "Tests for tool execution — execute-tool-call, recording, output accounting,
  dispatch lifecycle, runtime-effect helper."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.post-tool :as post-tool]
   [psi.agent-session.tool-runtime-adapter :as tool-runtime-adapter]
   [psi.session-state.state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.metrics.extension :as metrics-ext]
   [psi.turn-runtime.accumulator :as accum])
  (:import
   [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn- setup-agent-ctx!
  []
  (let [ctx (agent/create-context)]
    (agent/create-agent-in! ctx {:system-prompt "test prompt"
                                 :tools []})
    ctx))

(defn- setup-session-ctx!
  "Returns [ctx session-id]."
  [agent-ctx]
  (test-support/make-session-ctx {:agent-ctx agent-ctx}))

(deftest execute-tool-call-test
  (testing "tool execution is shaped before recording"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          tc          {:id "call-x" :name "read" :arguments "{}"}
          q           (LinkedBlockingQueue.)]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ opts]
                      ((:on-update opts) {:content "partial" :details {:phase :running}})
                      {:content [{:type :text :text "hello"}]
                       :is-error false
                       :details {:truncation {:truncated false}}})]
        (let [result (#'tool-runtime-adapter/execute-tool-call! session-ctx session-ctx-id tc q)]
          (is (= tc (:tool-call result)))
          (is (= "call-x" (get-in result [:result-message :tool-call-id])))
          (is (= [{:type :text :text "hello"}] (get-in result [:result-message :content])))
          (is (= false (get-in result [:tool-result :is-error])))
          (is (= 1000 (get-in result [:effective-policy :max-lines])))
          (is (= 51200 (get-in result [:effective-policy :max-bytes]))))))))

(deftest record-tool-call-result-test
  (testing "recording step emits progress, telemetry, and agent-core result from shaped execution"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          q           (LinkedBlockingQueue.)
          recorded    (atom nil)
          shaped      {:tool-call {:id "call-y" :name "bash" :arguments "{}"}
                       :tool-result {:content "trimmed"
                                     :is-error false
                                     :details {:truncation {:truncated true :truncated-by :bytes}}}
                       :result-message {:role "toolResult"
                                        :tool-call-id "call-y"
                                        :tool-name "bash"
                                        :content [{:type :text :text "trimmed"}]
                                        :is-error false
                                        :details {:truncation {:truncated true :truncated-by :bytes}}
                                        :result-text "trimmed"}
                       :effective-policy {:max-lines 10 :max-bytes 20}}]
      (with-redefs [agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in! (fn [_ msg] (reset! recorded msg) nil)]
        (let [result (#'tool-runtime-adapter/record-tool-call-result! session-ctx session-ctx-id shaped q)
              stats  (ss/get-state-value-in session-ctx (ss/state-path :tool-output-stats session-ctx-id))]
          (is (= "call-y" (:tool-call-id result)))
          (is (= "call-y" (:tool-call-id @recorded)))
          (is (= 1 (count (:calls stats))))
          (is (= 1 (get-in stats [:aggregates :limit-hits-by-tool "bash"]))))))))

(deftest tool-output-accounting-test
  (testing "captures per-call stats and aggregates, including limit-hit"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          tc          {:id "call-1" :name "bash" :arguments "{}"}]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ _]
                      {:content "trimmed"
                       :is-error false
                       :details {:truncation {:truncated true :truncated-by :bytes}}})
                    agent/emit-tool-start-in! (fn [_ _] nil)
                    agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in! (fn [_ _] nil)]
        (#'tool-runtime-adapter/run-tool-call! session-ctx session-ctx-id tc nil)
        (let [stats (ss/get-state-value-in session-ctx (ss/state-path :tool-output-stats session-ctx-id))
              call  (first (:calls stats))]
          (is (= "call-1" (:tool-call-id call)))
          (is (= "bash" (:tool-name call)))
          (is (= true (:limit-hit call)))
          (is (= :bytes (:truncated-by call)))
          (is (number? (:effective-max-lines call)))
          (is (number? (:effective-max-bytes call)))
          (is (= (:output-bytes call) (:context-bytes-added call)))
          (is (= (:context-bytes-added call)
                 (get-in stats [:aggregates :total-context-bytes])))
          (is (= 1 (get-in stats [:aggregates :limit-hits-by-tool "bash"])))))))

  (testing "context-bytes-added reflects shaped content"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          tc          {:id "call-2" :name "read" :arguments "{}"}
          raw         (apply str (repeat 1000 "x"))
          shaped      (subs raw 0 20)]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ _]
                      {:content shaped
                       :is-error false
                       :details {:truncation {:truncated true :truncated-by :bytes}}})
                    agent/emit-tool-start-in! (fn [_ _] nil)
                    agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in! (fn [_ _] nil)]
        (#'tool-runtime-adapter/run-tool-call! session-ctx session-ctx-id tc nil)
        (let [call (first (:calls (ss/get-state-value-in session-ctx (ss/state-path :tool-output-stats session-ctx-id))))]
          (is (= (count (.getBytes shaped "UTF-8"))
                 (:context-bytes-added call)))
          (is (= (:context-bytes-added call) (:output-bytes call)))))))

  (testing "structured content blocks are preserved and progress events include rich payload"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          tc          {:id "call-3" :name "read" :arguments "{}"}
          q           (LinkedBlockingQueue.)
          blocks      [{:type :text :text "hello"}
                       {:type :image :mime-type "image/png" :data "<base64>"}]
          results     (atom nil)]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ opts]
                      ((:on-update opts) {:content "partial" :details {:phase :running}})
                      {:content blocks
                       :is-error false
                       :details {:truncation {:truncated false}}})
                    agent/emit-tool-start-in! (fn [_ _] nil)
                    agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in!
                    (fn [_ msg]
                      (reset! results msg)
                      nil)]
        (#'tool-runtime-adapter/run-tool-call! session-ctx session-ctx-id tc q)
        (let [events   (loop [acc []]
                         (if-let [e (.poll q 5 TimeUnit/MILLISECONDS)]
                           (recur (conj acc e))
                           acc))
              update-e (some #(when (= :tool-execution-update (:event-kind %)) %) events)
              result-e (some #(when (= :tool-result (:event-kind %)) %) events)]
          (is (= blocks (:content @results)))
          (is (= "hello" (:result-text @results)))
          (is (= [{:type :text :text "partial"}] (:content update-e)))
          (is (= "partial" (:result-text update-e)))
          (is (= blocks (:content result-e)))
          (is (= "hello" (:result-text result-e))))))))

(deftest psi-tool-lifecycle-telemetry-test
  (testing "psi-tool lifecycle captures canonical action arguments"
    (let [agent-ctx                (setup-agent-ctx!)
          [session-ctx session-id] (setup-session-ctx! agent-ctx)
          tc                       {:id "call-psi" :name "psi-tool" :arguments "{\"action\":\"eval\",\"ns\":\"clojure.core\",\"form\":\"(+ 1 2)\"}"}]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ _]
                      {:content "#:psi-tool{:action :eval, :psi-tool/ns \"clojure.core\", :psi-tool/value \"3\"}"
                       :is-error false
                       :details {:truncation {:truncated false}}})
                    agent/emit-tool-start-in! (fn [_ _] nil)
                    agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in! (fn [_ _] nil)]
        (#'tool-runtime-adapter/run-tool-call! session-ctx session-id tc nil)
        (let [events (ss/get-state-value-in session-ctx (ss/state-path :tool-lifecycle-events session-id))
              exec-event (some #(when (= :tool-executing (:event-kind %)) %) events)]
          (is (= "{\"action\":\"eval\",\"ns\":\"clojure.core\",\"form\":\"(+ 1 2)\"}"
                 (:arguments exec-event)))
          (is (= {"action" "eval"
                  "ns" "clojure.core"
                  "form" "(+ 1 2)"}
                 (:parsed-args exec-event))))))))

(deftest dispatch-visible-tool-lifecycle-test
  (testing "tool lifecycle stages are appended through dispatch-visible session events"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          tc          {:id "call-life" :name "read" :arguments "{}"}]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ opts]
                      ((:on-update opts) {:content "partial" :details {:phase :running}})
                      {:content "done"
                       :is-error false
                       :details {:truncation {:truncated false}}})
                    agent/emit-tool-start-in! (fn [_ _] nil)
                    agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in! (fn [_ _] nil)]
        (#'tool-runtime-adapter/run-tool-call! session-ctx session-ctx-id tc nil)
        (let [events (ss/get-state-value-in session-ctx (ss/state-path :tool-lifecycle-events session-ctx-id))
              lifecycle (filterv #(contains? #{:tool-start :tool-executing :tool-execution-update :tool-result}
                                             (:event-kind %))
                                 events)]
          (is (= [:tool-start :tool-executing :tool-execution-update :tool-result]
                 (mapv :event-kind lifecycle)))
          (is (= "call-life" (:tool-id (first lifecycle))))
          (is (= "read" (:tool-name (first lifecycle)))))))))

(deftest dispatch-owned-tool-run-composes-execute-and-record-phases-test
  (testing "session/tool-run owns the full transaction via dispatch execute+record phases"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          tc          {:id "call-effect" :name "read" :arguments "{}"}
          events      (atom [])]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ _]
                      {:content "done"
                       :is-error false
                       :details {:truncation {:truncated false}}})
                    agent/emit-tool-start-in! (fn [_ _] nil)
                    agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in! (fn [_ _] nil)
                    dispatch/dispatch!
                    (let [orig dispatch/dispatch!]
                      (fn [ctx event-type event-data opts]
                        (swap! events conj event-type)
                        (orig ctx event-type event-data opts)))]
        (let [result (session/dispatch-in! session-ctx :session/tool-run
                                           {:session-id session-ctx-id
                                            :tool-call tc
                                            :parsed-args {}
                                            :progress-queue nil}
                                           {:origin :core})]
          (is (= "call-effect" (:tool-call-id result)))
          (is (some #{:session/tool-execute-prepared} @events))
          (is (some #{:session/tool-record-result} @events))
          (is (some #{:session/tool-agent-start} @events))
          (is (some #{:session/tool-execute} @events))
          (is (some #{:session/tool-agent-end} @events))
          (is (some #{:session/tool-agent-record-result} @events)))))))

(deftest tool-run-dispatch-boundary-test
  (testing "tool execution now enters through one explicit session/tool-run dispatch boundary"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          tc          {:id "call-dispatch" :name "read" :arguments "{}"}
          q           (LinkedBlockingQueue.)
          events      (atom [])]
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ _]
                      {:content "hello"
                       :is-error false})
                    dispatch/dispatch!
                    (let [orig dispatch/dispatch!]
                      (fn [ctx event-type event-data opts]
                        (swap! events conj event-type)
                        (orig ctx event-type event-data opts)))]
        (let [result (#'tool-runtime-adapter/run-tool-call! session-ctx session-ctx-id tc q)]
          (is (= "call-dispatch" (:tool-call-id result)))
          (is (some #{:session/tool-run} @events))
          (is (some #{:session/tool-execute-prepared} @events))
          (is (some #{:session/tool-record-result} @events))
          (is (some #{:session/tool-execute} @events)))))))

(deftest post-tool-enrichment-is-in-provider-facing-tool-result-test
  (testing "post-tool content append is recorded into the final provider-facing toolResult message"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          tc          {:id "call-lsp" :name "write" :arguments "{}"}
          q           (LinkedBlockingQueue.)
          recorded    (atom nil)]
      (post-tool/register-processor-in!
       session-ctx
       {:name "generic-enrichment"
        :match {:tools #{"write"}}
        :timeout-ms 100
        :handler (fn [_]
                   {:content/append "\nService notes for /tmp/example.clj:\n- follow-up available"
                    :details/merge {:service {:note-count 1}}
                    :enrichments [{:type "service/notes"
                                   :label "Service notes: /tmp/example.clj"}]})})
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ _]
                      {:content "Successfully wrote 10 bytes to /tmp/example.clj"
                       :is-error false
                       :details nil
                       :effects [{:type "file/write"
                                  :path "/tmp/example.clj"}]
                       :enrichments []})
                    agent/emit-tool-start-in! (fn [_ _] nil)
                    agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in!
                    (fn [_ msg]
                      (reset! recorded msg)
                      nil)]
        (#'tool-runtime-adapter/run-tool-call! session-ctx session-ctx-id tc q)
        (is (= [{:type :text
                 :text (str "Successfully wrote 10 bytes to /tmp/example.clj"
                            "\nService notes for /tmp/example.clj:\n- follow-up available")}]
               (:content @recorded)))
        (is (= (str "Successfully wrote 10 bytes to /tmp/example.clj"
                    "\nService notes for /tmp/example.clj:\n- follow-up available")
               (:result-text @recorded)))
        (is (= {:service {:note-count 1}}
               (:details @recorded)))))))

(deftest tool-lifecycle-progress-derived-from-canonical-event-test
  (testing "progress projection uses the same canonical lifecycle event shape"
    (let [q      (LinkedBlockingQueue.)
          event  {:event-kind :tool-result
                  :tool-id "call-proj"
                  :tool-name "read"
                  :content [{:type :text :text "ok"}]
                  :result-text "ok"
                  :details {:phase :done}
                  :is-error false}]
      (#'accum/emit-progress! q event)
      (let [projected (.poll q 5 TimeUnit/MILLISECONDS)]
        (is (= :agent-event (:type projected)))
        (is (= event (dissoc projected :type)))))))

(deftest emit-tool-lifecycle-bridge-fires-extension-handlers-test
  (testing "run-tool-call! fires registered tool_call and tool_result extension handlers (regression guard for emit-tool-lifecycle! bridge)"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          tc          {:id "call-bridge" :name "read" :arguments "{\"path\":\"x\"}"
                       :parsed-args {:path "x"}}
          reg         (:extension-registry session-ctx)
          calls       (atom [])]
      (ext/register-extension-in! reg "/ext/test-bridge")
      (ext/register-handler-in! reg "/ext/test-bridge" "tool_call"
                                (fn [event] (swap! calls conj [:tool-call event]) nil))
      (ext/register-handler-in! reg "/ext/test-bridge" "tool_result"
                                (fn [event] (swap! calls conj [:tool-result event]) nil))
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ _]
                      {:content "result-text"
                       :is-error false
                       :details {:truncation {:truncated false}}})
                    agent/emit-tool-start-in! (fn [_ _] nil)
                    agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in! (fn [_ _] nil)]
        (#'tool-runtime-adapter/run-tool-call! session-ctx session-ctx-id tc nil)
        (let [recorded @calls
              call-event   (second (first (filter #(= :tool-call (first %)) recorded)))
              result-event (second (first (filter #(= :tool-result (first %)) recorded)))]
          (is (= 2 (count recorded)) "both tool_call and tool_result handlers fire")
          (is (= "tool_call" (:type call-event)))
          (is (= "read" (:tool-name call-event)))
          (is (= "call-bridge" (:tool-call-id call-event)))
          (is (= "tool_result" (:type result-event)))
          (is (= "read" (:tool-name result-event)))
          (is (= "call-bridge" (:tool-call-id result-event)))
          (is (= false (:is-error result-event)))
          ;; Contract: both tool_call and tool_result carry :input (parsed-args),
          ;; matching the plan-path dispatch-tool-{call,result}-in shape.
          (is (= {:path "x"} (:input call-event)))
          (is (= {:path "x"} (:input result-event))
              "interactive-path tool_result carries :input, unifying the cross-path contract"))))))

(deftest tool-call-handler-block-ignored-on-interactive-path-test
  (testing "{:block true} from a tool_call handler does NOT block execution on the interactive path (intentional non-enforcement)"
    (let [agent-ctx   (setup-agent-ctx!)
          [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
          tc          {:id "call-block" :name "bash" :arguments "{}"}
          reg         (:extension-registry session-ctx)
          result-atom (atom nil)]
      (ext/register-extension-in! reg "/ext/test-block")
      (ext/register-handler-in! reg "/ext/test-block" "tool_call"
                                (fn [_] {:block true :reason "blocked by extension"}))
      (with-redefs [tool-plan/execute-tool-runtime-in!
                    (fn [_ _ _ _]
                      {:content "executed despite block"
                       :is-error false
                       :details {:truncation {:truncated false}}})
                    agent/emit-tool-start-in! (fn [_ _] nil)
                    agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                    agent/record-tool-result-in!
                    (fn [_ msg] (reset! result-atom msg) nil)]
        (let [result (#'tool-runtime-adapter/run-tool-call! session-ctx session-ctx-id tc nil)]
          (is (some? result) "tool execution completes even when handler returns {:block true}")
          (is (= "call-block" (:tool-call-id result)) "result carries the tool-call-id")
          (is (some? @result-atom) "tool result was recorded — execution was not blocked"))))))

;;; End-to-end: metrics extension accumulates :tools via the bridge

(defn- run-tool-call-through-metrics-ext!
  "Drive `tool-call` through `run-tool-call!` with the real metrics extension loaded
  on a fresh session ctx, while `execute-tool-runtime-in!` returns `runtime-result`.

  Compresses the e2e ceremony — defonce-atom reset/cleanup, registry-only runtime-fns,
  metrics-ext load, and infra `with-redefs` — so each test states only its per-test
  intent (the runtime result and the store assertions, run after this returns)."
  [tool-call runtime-result]
  (reset! metrics-ext/store nil)
  (reset! metrics-ext/writing? false)
  (let [agent-ctx   (setup-agent-ctx!)
        [session-ctx session-ctx-id] (setup-session-ctx! agent-ctx)
        reg         (:extension-registry session-ctx)
        ;; Minimal runtime-fns: query-fn returns nil worktree-path; no mutate-fn so
        ;; (:on api) falls back to register-handler-in! directly on the registry.
        runtime-fns {:query-fn (fn [q]
                                 (when (= q [:psi.agent-session/worktree-path])
                                   {:psi.agent-session/worktree-path nil}))}]
    (ext/load-init-var-extension-in! reg "manifest:psi/metrics" 'psi.metrics.extension/init runtime-fns)
    (with-redefs [tool-plan/execute-tool-runtime-in! (fn [_ _ _ _] runtime-result)
                  agent/emit-tool-start-in! (fn [_ _] nil)
                  agent/emit-tool-end-in! (fn [_ _ _ _] nil)
                  agent/record-tool-result-in! (fn [_ _] nil)]
      (#'tool-runtime-adapter/run-tool-call! session-ctx session-ctx-id tool-call nil))))

(deftest metrics-extension-accumulates-tools-via-bridge-test
  (testing "register metrics ext on real session ctx, call run-tool-call!, assert :tools entry accumulated — full path: adapter → bridge → metrics handler → store"
    (try
      (run-tool-call-through-metrics-ext!
       {:id "call-e2e" :name "read" :arguments "{}"}
       {:content "file contents"
        :is-error false
        :details {:truncation {:truncated false}}})
      ;; Assert the full path fired: metrics store now has a :tools entry for "read".
      (is (= 1 (get-in @metrics-ext/store [:metrics :tools "read" :invocations]))
          "metrics extension accumulated :tools entry via adapter → bridge → handler → store")
      (finally
        (reset! metrics-ext/store nil)
        (reset! metrics-ext/writing? false)))))

(deftest metrics-extension-accumulates-errors-via-bridge-test
  (testing "register metrics ext on real session ctx, drive run-tool-call! with :is-error true, assert :errors entry accumulated — full error path: adapter → bridge → metrics on-tool-result → store (AC2)"
    (try
      (run-tool-call-through-metrics-ext!
       {:id "call-e2e-err" :name "bash" :arguments "{}"}
       {:content "boom: command failed"
        :is-error true
        :details {:truncation {:truncated false}}})
      ;; Assert the full error path fired: the bridge propagated :is-error true so
      ;; on-tool-result incremented :errors (and on-tool-call recorded the invocation).
      (is (= 1 (get-in @metrics-ext/store [:metrics :tools "bash" :invocations]))
          "invocation counter incremented via bridge")
      (is (= 1 (get-in @metrics-ext/store [:metrics :tools "bash" :errors]))
          "error counter incremented via adapter → bridge → on-tool-result → store")
      ;; The lifecycle :tool-result event carries shaped structured-block content; the
      ;; metrics handler derives the reason from (str content). Assert a single reason
      ;; was recorded with count 1 (exact text is the stringified content block).
      (let [reasons (get-in @metrics-ext/store [:metrics :tools "bash" :error-reasons])]
        (is (= 1 (count reasons)) "one error reason recorded")
        (is (= 1 (val (first reasons))) "reason count is 1")
        (is (str/includes? (key (first reasons)) "boom: command failed")
            "reason derived from propagated :content"))
      (finally
        (reset! metrics-ext/store nil)
        (reset! metrics-ext/writing? false)))))
