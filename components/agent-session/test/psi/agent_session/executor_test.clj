(ns psi.agent-session.executor-test
  "Integration tests for the executor with per-turn statechart.

  Uses with-redefs on private vars to inject stub provider/tool behavior
  (no HTTP calls, no API keys required)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.turn-statechart :as turn-sc])
  (:import
   [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn- stub-text-stream
  [text]
  (fn [_ai-ctx _conv _model _opts consume-fn]
    (consume-fn {:type :start})
    (consume-fn {:type :text-delta :delta text})
    (consume-fn {:type :done :reason :stop})))

(defn- stub-error-stream
  [err-msg]
  (fn [_ai-ctx _conv _model _opts consume-fn]
    (consume-fn {:type :start})
    (consume-fn {:type :error :error-message err-msg})))

(defn- setup-agent-ctx!
  []
  (let [ctx (agent/create-context)]
    (agent/create-agent-in! ctx {:system-prompt "test prompt"
                                 :tools []})
    ctx))

(defn- setup-session-ctx!
  [agent-ctx]
  {:agent-ctx agent-ctx
   :session-data-atom (atom {:tool-output-overrides {}})
   :tool-output-stats-atom (atom {:calls []
                                  :aggregates {:total-context-bytes 0
                                               :by-tool {}
                                               :limit-hits-by-tool {}}})})

(def ^:private stub-model
  {:provider "stub" :id "stub-model"})

(deftest text-only-response-test
  (let [agent-ctx    (setup-agent-ctx!)
        session-ctx  (setup-session-ctx! agent-ctx)
        turn-atom    (atom nil)
        user-msg     {:role "user" :content [{:type :text :text "hello"}]}]
    (with-redefs [psi.agent-session.executor/do-stream!
                  (stub-text-stream "Hello! I'm here to help.")]
      (let [result (executor/run-agent-loop!
                    nil session-ctx agent-ctx stub-model [user-msg]
                    {:turn-ctx-atom turn-atom})]
        (is (= "assistant" (:role result)))
        (is (= :stop (:stop-reason result)))
        (is (= "Hello! I'm here to help."
               (some #(when (= :text (:type %)) (:text %))
                     (:content result))))
        (is (some? @turn-atom))
        (is (= :done (turn-sc/turn-phase @turn-atom)))))))

(deftest error-response-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        turn-atom   (atom nil)
        user-msg    {:role "user" :content [{:type :text :text "hello"}]}]
    (with-redefs [psi.agent-session.executor/do-stream!
                  (stub-error-stream "Connection refused")]
      (let [result (executor/run-agent-loop!
                    nil session-ctx agent-ctx stub-model [user-msg]
                    {:turn-ctx-atom turn-atom})]
        (is (= :error (:stop-reason result)))
        (is (= :error (turn-sc/turn-phase @turn-atom)))
        (is (= "Connection refused"
               (:error-message (turn-sc/get-turn-data @turn-atom))))))))

(deftest agent-core-lifecycle-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}]
    (with-redefs [psi.agent-session.executor/do-stream!
                  (stub-text-stream "response")]
      (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg])
      (is (= :idle (agent/sc-phase-in agent-ctx)))
      (let [msgs (:messages (agent/get-data-in agent-ctx))]
        (is (>= (count msgs) 2))
        (is (= "user" (:role (first msgs))))
        (is (= "assistant" (:role (second msgs))))))))

(deftest turn-ctx-atom-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}]
    (with-redefs [psi.agent-session.executor/do-stream!
                  (stub-text-stream "ok")]
      (let [result (executor/run-agent-loop!
                    nil session-ctx agent-ctx stub-model [user-msg])]
        (is (= "assistant" (:role result))))))

  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        turn-atom   (atom nil)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}]
    (with-redefs [psi.agent-session.executor/do-stream!
                  (stub-text-stream "hello world")]
      (executor/run-agent-loop! nil session-ctx agent-ctx stub-model [user-msg]
                                {:turn-ctx-atom turn-atom})
      (let [td (turn-sc/get-turn-data @turn-atom)]
        (is (= "hello world" (:text-buffer td)))
        (is (some? (:final-message td)))))))

(deftest multiple-text-deltas-test
  (let [agent-ctx   (setup-agent-ctx!)
        session-ctx (setup-session-ctx! agent-ctx)
        turn-atom   (atom nil)
        user-msg    {:role "user" :content [{:type :text :text "hi"}]}
        stream-fn   (fn [_ai-ctx _conv _model _opts consume-fn]
                      (consume-fn {:type :start})
                      (consume-fn {:type :text-delta :delta "Hello"})
                      (consume-fn {:type :text-delta :delta " there"})
                      (consume-fn {:type :text-delta :delta "!"})
                      (consume-fn {:type :done :reason :stop}))]
    (with-redefs [psi.agent-session.executor/do-stream! stream-fn]
      (let [result (executor/run-agent-loop!
                    nil session-ctx agent-ctx stub-model [user-msg]
                    {:turn-ctx-atom turn-atom})]
        (is (= "Hello there!"
               (some #(when (= :text (:type %)) (:text %))
                     (:content result))))))))

(deftest tool-output-accounting-test
  (testing "captures per-call stats and aggregates, including limit-hit"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          tc          {:id "call-1" :name "bash" :arguments "{}"}]
      (with-redefs [psi.agent-session.executor/execute-tool-with-registry
                    (fn [_ _ _ _]
                      {:content "trimmed"
                       :is-error false
                       :details {:truncation {:truncated true :truncated-by :bytes}}})]
        (#'psi.agent-session.executor/run-tool-call! session-ctx tc nil)
        (let [stats @(:tool-output-stats-atom session-ctx)
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
          session-ctx (setup-session-ctx! agent-ctx)
          tc          {:id "call-2" :name "read" :arguments "{}"}
          raw         (apply str (repeat 1000 "x"))
          shaped      (subs raw 0 20)]
      (with-redefs [psi.agent-session.executor/execute-tool-with-registry
                    (fn [_ _ _ _]
                      {:content shaped
                       :is-error false
                       :details {:truncation {:truncated true :truncated-by :bytes}}})]
        (#'psi.agent-session.executor/run-tool-call! session-ctx tc nil)
        (let [call (first (:calls @(:tool-output-stats-atom session-ctx)))]
          (is (= (count (.getBytes shaped "UTF-8"))
                 (:context-bytes-added call)))
          (is (= (:context-bytes-added call) (:output-bytes call)))))))

  (testing "structured content blocks are preserved and progress events include rich payload"
    (let [agent-ctx   (setup-agent-ctx!)
          session-ctx (setup-session-ctx! agent-ctx)
          tc          {:id "call-3" :name "read" :arguments "{}"}
          q           (LinkedBlockingQueue.)
          blocks      [{:type :text :text "hello"}
                       {:type :image :mime-type "image/png" :data "<base64>"}]
          results     (atom nil)]
      (with-redefs [psi.agent-session.executor/execute-tool-with-registry
                    (fn [_ _ _ opts]
                      ((:on-update opts) {:content "partial" :details {:phase :running}})
                      {:content blocks
                       :is-error false
                       :details {:truncation {:truncated false}}})
                    agent/record-tool-result-in!
                    (fn [_ msg]
                      (reset! results msg)
                      nil)]
        (#'psi.agent-session.executor/run-tool-call! session-ctx tc q)
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
