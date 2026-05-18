(ns psi.main-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.app-runtime :as app-runtime]
   [psi.main :as main]))

(deftest main-routes-console-mode-test
  (let [called (atom nil)
        exited (atom nil)]
    (with-redefs [main/run-console-session! (fn [args] (reset! called [:console args]))
                  main/run-rpc-session! (fn [args] (reset! called [:rpc args]))
                  main/run-tui-session! (fn [args] (reset! called [:tui args]))
                  main/exit! (fn [code] (reset! exited code))]
      (main/-main "--model" "sonnet-4.6")
      (is (= [:console ["--model" "sonnet-4.6"]] @called))
      (is (= 0 @exited)))))

(deftest main-help-exits-without-starting-runtime-test
  (let [called (atom nil)
        exited (atom nil)
        output (with-out-str
                 (with-redefs [main/run-console-session! (fn [_args] (reset! called :console))
                               main/run-rpc-session! (fn [_args] (reset! called :rpc))
                               main/run-tui-session! (fn [_args] (reset! called :tui))
                               main/exit! (fn [code] (reset! exited code))]
                   (main/-main "--help")))]
    (is (nil? @called))
    (is (= 0 @exited))
    (is (.contains output "Usage:"))
    (is (.contains output "/help for commands"))))

(deftest main-routes-rpc-mode-test
  (let [called (atom nil)
        exited (atom nil)]
    (with-redefs [main/run-console-session! (fn [args] (reset! called [:console args]))
                  main/run-rpc-session! (fn [args] (reset! called [:rpc args]))
                  main/run-tui-session! (fn [args] (reset! called [:tui args]))
                  main/exit! (fn [code] (reset! exited code))]
      (main/-main "--rpc-edn")
      (is (= [:rpc ["--rpc-edn"]] @called))
      (is (= 0 @exited)))))

(deftest main-routes-tui-mode-test
  (let [called (atom nil)
        exited (atom nil)]
    (with-redefs [main/run-console-session! (fn [args] (reset! called [:console args]))
                  main/run-rpc-session! (fn [args] (reset! called [:rpc args]))
                  main/run-tui-session! (fn [args] (reset! called [:tui args]))
                  main/exit! (fn [code] (reset! exited code))]
      (main/-main "--tui")
      (is (= [:tui ["--tui"]] @called))
      (is (= 0 @exited)))))

(deftest run-rpc-session-delegates-to-rpc-runtime-test
  (let [captured (atom nil)]
    (with-redefs [app-runtime/start-nrepl! (fn [port] {:server port})
                  app-runtime/stop-nrepl! (fn [server] (swap! captured assoc :stopped server))
                  requiring-resolve (fn [sym]
                                      (is (= 'psi.rpc/start-runtime! sym))
                                      (atom (fn [{:keys [model-key memory-runtime-opts session-config rpc-trace-file]}]
                                              (reset! captured {:model-key model-key
                                                                :memory-runtime-opts memory-runtime-opts
                                                                :session-config session-config
                                                                :rpc-trace-file rpc-trace-file}))))]
      (main/run-rpc-session! ["--rpc-edn"
                              "--model" "sonnet-4.6"
                              "--memory-store" "in-memory"
                              "--llm-idle-timeout-ms" "90000"
                              "--rpc-trace-file" "/tmp/rpc.ndedn"
                              "--nrepl" "7888"])
      (is (= :sonnet-4.6 (:model-key @captured)))
      (is (= {:store-provider "in-memory"} (:memory-runtime-opts @captured)))
      (is (= {:llm-stream-idle-timeout-ms 90000} (:session-config @captured)))
      (is (= "/tmp/rpc.ndedn" (:rpc-trace-file @captured)))
      (is (= {:server 7888} (:stopped @captured))))))

(deftest run-tui-session-delegates-to-app-runtime-component-test
  (let [captured (atom nil)
        start-fn (fn [])]
    (with-redefs [app-runtime/start-nrepl! (fn [port] {:server port})
                  app-runtime/stop-nrepl! (fn [server] (swap! captured assoc :stopped server))
                  app-runtime/start-tui-runtime! (fn [resolved-start-fn model-key memory-runtime-opts session-config startup-opts]
                                                   (reset! captured {:start-fn    resolved-start-fn
                                                                     :model-key   model-key
                                                                     :memory-runtime-opts memory-runtime-opts
                                                                     :session-config session-config
                                                                     :startup-opts startup-opts}))
                  requiring-resolve (fn [sym]
                                      (is (= 'psi.tui.app/start! sym))
                                      (atom start-fn))]
      (main/run-tui-session! ["--tui"
                              "--model" "sonnet-4.6"
                              "--thinking-level" "medium"
                              "--memory-store-fallback" "off"
                              "--llm-idle-timeout-ms" "42000"
                              "--nrepl" "7777"])
      (is (= start-fn (:start-fn @captured)))
      (is (= :sonnet-4.6 (:model-key @captured)))
      (is (= {:auto-store-fallback? false} (:memory-runtime-opts @captured)))
      (is (= {:llm-stream-idle-timeout-ms 42000} (:session-config @captured)))
      (is (= {:thinking-level-override :medium} (:startup-opts @captured)))
      (is (= {:server 7777} (:stopped @captured))))))

(deftest run-console-session-delegates-to-app-runtime-component-test
  (let [captured (atom nil)]
    (with-redefs [app-runtime/start-nrepl! (fn [port] {:server port})
                  app-runtime/stop-nrepl! (fn [server] (swap! captured assoc :stopped server))
                  app-runtime/run-session (fn [model-key memory-runtime-opts session-config startup-opts]
                                            (reset! captured {:model-key           model-key
                                                              :memory-runtime-opts memory-runtime-opts
                                                              :session-config      session-config
                                                              :startup-opts        startup-opts}))]
      (main/run-console-session! ["--model" "sonnet-4.6"
                                  "--thinking-level" "high"
                                  "--memory-retention-snapshots" "12"
                                  "--llm-idle-timeout-ms" "30000"
                                  "--nrepl" "7001"])
      (is (= :sonnet-4.6 (:model-key @captured)))
      (is (= {:retention-snapshots 12} (:memory-runtime-opts @captured)))
      (is (= {:llm-stream-idle-timeout-ms 30000} (:session-config @captured)))
      (is (= {:thinking-level-override :high} (:startup-opts @captured)))
      (is (= {:server 7001} (:stopped @captured))))))

(deftest detect-model-key-from-env-test
  (testing "prefers anthropic when ANTHROPIC_API_KEY is set"
    (with-redefs [main/get-env (fn [k] ({"ANTHROPIC_API_KEY" "sk-ant-123"} k))]
      (is (= :sonnet-4.6 (#'main/detect-model-key-from-env)))))
  (testing "falls back to openai when only OPENAI_API_KEY is set"
    (with-redefs [main/get-env (fn [k] ({"OPENAI_API_KEY" "sk-oai-123"} k))]
      (is (= :gpt-5 (#'main/detect-model-key-from-env)))))
  (testing "returns default when no known key is present"
    (with-redefs [main/get-env (fn [_] nil)]
      (is (= :sonnet-4.6 (#'main/detect-model-key-from-env)))))
  (testing "anthropic wins when both keys present"
    (with-redefs [main/get-env (fn [k] ({"ANTHROPIC_API_KEY" "sk-ant-123"
                                         "OPENAI_API_KEY"    "sk-oai-123"} k))]
      (is (= :sonnet-4.6 (#'main/detect-model-key-from-env)))))
  (testing "blank key treated as absent"
    (with-redefs [main/get-env (fn [k] ({"ANTHROPIC_API_KEY" "   "
                                         "OPENAI_API_KEY"    "sk-oai-123"} k))]
      (is (= :gpt-5 (#'main/detect-model-key-from-env))))))

(deftest model-key-from-args-test
  (testing "--model flag wins over env detection"
    (with-redefs [main/get-env (fn [k] ({"ANTHROPIC_API_KEY" "sk-ant-123"} k))]
      (is (= :opus-4 (#'main/model-key-from-args ["--model" "opus-4"])))))
  (testing "PSI_MODEL env var wins over key detection"
    (with-redefs [main/get-env (fn [k] ({"PSI_MODEL" "opus-4"} k))]
      (is (= :opus-4 (#'main/model-key-from-args [])))))
  (testing "falls through to key detection when no explicit flag/env"
    (with-redefs [main/get-env (fn [k] ({"OPENAI_API_KEY" "sk-oai-123"} k))]
      (is (= :gpt-5 (#'main/model-key-from-args []))))))

(deftest thinking-level-from-args-test
  (testing "--thinking-level flag"
    (is (= :medium (#'main/thinking-level-from-args ["--thinking-level" "medium"]))))
  (testing "PSI_THINKING_LEVEL env var"
    (with-redefs [main/get-env (fn [k] ({"PSI_THINKING_LEVEL" "high"} k))]
      (is (= :high (#'main/thinking-level-from-args [])))))
  (testing "--thinking-level flag wins over PSI_THINKING_LEVEL"
    (with-redefs [main/get-env (fn [k] ({"PSI_THINKING_LEVEL" "high"} k))]
      (is (= :low (#'main/thinking-level-from-args ["--thinking-level" "low"])))))
  (testing "returns nil when neither flag nor env is set"
    (with-redefs [main/get-env (fn [_] nil)]
      (is (nil? (#'main/thinking-level-from-args [])))))
  (testing "case-insensitive flag value"
    (is (= :xhigh (#'main/thinking-level-from-args ["--thinking-level" "XHIGH"])))))

(deftest arg-parsing-helpers-test
  (testing "rpc trace file parsing"
    (is (= "/tmp/rpc.ndedn"
           (#'main/rpc-trace-file-from-args ["--rpc-trace-file" "/tmp/rpc.ndedn"])))
    (is (nil? (#'main/rpc-trace-file-from-args ["--rpc-trace-file" "   "]))))
  (testing "memory runtime opts parsing"
    (is (= {:store-provider "in-memory"
            :auto-store-fallback? false
            :history-commit-limit 99
            :retention-snapshots 12
            :retention-deltas 34}
           (#'main/memory-runtime-opts-from-args
            ["--memory-store" "in-memory"
             "--memory-store-fallback" "off"
             "--memory-history-limit" "99"
             "--memory-retention-snapshots" "12"
             "--memory-retention-deltas" "34"]))))
  (testing "session runtime config parsing"
    (is (= {:llm-stream-idle-timeout-ms 90000}
           (#'main/session-runtime-config-from-args ["--llm-idle-timeout-ms" "90000"]))))
  (testing "nrepl port parsing"
    (is (= 7888 (#'main/nrepl-port-from-args ["--nrepl" "7888"])))
    (is (= 0 (#'main/nrepl-port-from-args ["--nrepl"])))
    (is (nil? (#'main/nrepl-port-from-args [])))))
