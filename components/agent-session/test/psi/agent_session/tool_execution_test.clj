(ns psi.agent-session.tool-execution-test
  "Tests for tool execution — execute-tool-call, recording, output accounting,
  dispatch lifecycle, runtime-effect helper."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.post-tool :as post-tool]
   [psi.agent-session.tool-batch :as tool-batch]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tool-execution :as tool-exec]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.agent-session.turn-accumulator :as accum])
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
        (let [result (#'tool-exec/execute-tool-call! session-ctx session-ctx-id tc q)]
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
        (let [result (#'tool-exec/record-tool-call-result! session-ctx session-ctx-id shaped q)
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
        (#'tool-batch/run-tool-call! session-ctx session-ctx-id tc nil)
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
        (#'tool-batch/run-tool-call! session-ctx session-ctx-id tc nil)
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
        (#'tool-batch/run-tool-call! session-ctx session-ctx-id tc q)
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
        (#'tool-batch/run-tool-call! session-ctx session-id tc nil)
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
        (#'tool-batch/run-tool-call! session-ctx session-ctx-id tc nil)
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
        (let [result (dispatch/dispatch! session-ctx :session/tool-run
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
        (let [result (#'tool-batch/run-tool-call! session-ctx session-ctx-id tc q)]
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
        (#'tool-batch/run-tool-call! session-ctx session-ctx-id tc q)
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
