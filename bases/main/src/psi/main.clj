(ns psi.main
  "Polylith base entry point for psi.

   Parses CLI flags, selects an interface, and delegates to the session app runtime."
  (:require
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [psi.app-runtime :as app-runtime]
   [psi.version :as version])
  (:gen-class))

(def ^:private valid-log-levels
  #{:trace :debug :info :warn :error :fatal :report})

(def ^:private default-model-key :sonnet-4.6)

(defn get-env
  "Read an environment variable. Public to allow redefinition in tests."
  [k]
  (System/getenv k))

(def ^:private provider-preferred-models
  "Ordered pairs of [env-var model-key] consulted when no explicit model is configured.
   First provider with a non-blank key wins."
  [["ANTHROPIC_API_KEY" :sonnet-4.6]
   ["OPENAI_API_KEY"    :gpt-5]])

(defn- detect-model-key-from-env
  "Return the preferred model key for the first provider whose API key is set,
  or `default-model-key` when no known key is present."
  []
  (or (some (fn [[env-var model-key]]
              (when-not (str/blank? (get-env env-var))
                model-key))
            provider-preferred-models)
      default-model-key))

(defn- has-flag?
  [args flag]
  (some #(= flag %) args))

(defn- arg-value
  [args flag]
  (second (drop-while #(not= flag %) args)))

(defn- parse-bool-arg
  [v]
  (when (string? v)
    (let [x (str/lower-case (str/trim v))]
      (cond
        (contains? #{"1" "true" "yes" "y" "on"} x) true
        (contains? #{"0" "false" "no" "n" "off"} x) false
        :else nil))))

(defn- parse-positive-int-arg
  [v]
  (when (string? v)
    (try
      (let [n (Integer/parseInt (str/trim v))]
        (when (pos? n) n))
      (catch Exception _
        nil))))

(defn- set-log-level!
  [level-str]
  (let [kw    (keyword (str/lower-case (or level-str "info")))
        level (if (valid-log-levels kw) kw :info)]
    (timbre/set-min-level! level)))

(defn- log-level-from-args
  [args]
  (or (arg-value args "--log-level")
      "INFO"))

(defn- model-key-from-args
  "Resolve model key in priority order:
   1. --model CLI flag
   2. PSI_MODEL env var (explicit override)
   3. Detected from available provider API keys (ANTHROPIC_API_KEY, OPENAI_API_KEY)
   4. Compiled-in default-model-key"
  [args]
  (keyword
   (or (arg-value args "--model")
       (some-> (get-env "PSI_MODEL") str/trim not-empty)
       (name (detect-model-key-from-env)))))

(defn- thinking-level-from-args
  "Resolve thinking level in priority order:
   1. --thinking-level CLI flag
   2. PSI_THINKING_LEVEL env var
   3. nil (config resolution handles project/user config → :off system default)"
  [args]
  (some-> (or (arg-value args "--thinking-level")
              (some-> (get-env "PSI_THINKING_LEVEL") str/trim not-empty))
          str/lower-case
          keyword))

(defn- nrepl-port-from-args
  [args]
  (when (has-flag? args "--nrepl")
    (let [after (second (drop-while #(not= "--nrepl" %) args))]
      (if (and after (re-matches #"\d+" after))
        (Integer/parseInt after)
        0))))

(defn- rpc-trace-file-from-args
  [args]
  (let [v (arg-value args "--rpc-trace-file")]
    (when-not (str/blank? v)
      v)))

(defn- memory-runtime-opts-from-args
  [args]
  (let [store-provider      (arg-value args "--memory-store")
        fallback            (parse-bool-arg (arg-value args "--memory-store-fallback"))
        history-limit       (parse-positive-int-arg (arg-value args "--memory-history-limit"))
        retention-snapshots (parse-positive-int-arg (arg-value args "--memory-retention-snapshots"))
        retention-deltas    (parse-positive-int-arg (arg-value args "--memory-retention-deltas"))]
    (cond-> {}
      (some? store-provider)      (assoc :store-provider store-provider)
      (some? fallback)            (assoc :auto-store-fallback? fallback)
      (some? history-limit)       (assoc :history-commit-limit history-limit)
      (some? retention-snapshots) (assoc :retention-snapshots retention-snapshots)
      (some? retention-deltas)    (assoc :retention-deltas retention-deltas))))

(defn- llm-idle-timeout-ms-from-env
  []
  (parse-positive-int-arg (System/getenv "PSI_LLM_IDLE_TIMEOUT_MS")))

(defn- session-runtime-config-from-args
  [args]
  (let [idle-timeout-arg-raw (arg-value args "--llm-idle-timeout-ms")
        idle-timeout-arg     (parse-positive-int-arg idle-timeout-arg-raw)
        idle-timeout-ms      (if (some? idle-timeout-arg-raw)
                               idle-timeout-arg
                               (llm-idle-timeout-ms-from-env))]
    (cond-> {}
      (some? idle-timeout-ms) (assoc :llm-stream-idle-timeout-ms idle-timeout-ms))))

(defn run-console-session!
  [args]
  (let [model-key            (model-key-from-args args)
        thinking-level       (thinking-level-from-args args)
        memory-runtime-opts  (memory-runtime-opts-from-args args)
        session-runtime-opts (session-runtime-config-from-args args)
        nrepl-port           (nrepl-port-from-args args)
        nrepl-srv            (when nrepl-port
                               (app-runtime/start-nrepl! nrepl-port))]
    (try
      (app-runtime/run-session model-key memory-runtime-opts session-runtime-opts
                               {:thinking-level-override thinking-level})
      (finally
        (app-runtime/stop-nrepl! nrepl-srv)))))

(defn run-rpc-session!
  [args]
  (let [start-runtime!       @(requiring-resolve 'psi.rpc/start-runtime!)
        model-key            (model-key-from-args args)
        thinking-level       (thinking-level-from-args args)
        memory-runtime-opts  (memory-runtime-opts-from-args args)
        session-runtime-opts (session-runtime-config-from-args args)
        rpc-trace-file       (rpc-trace-file-from-args args)
        nrepl-port           (nrepl-port-from-args args)
        nrepl-srv            (when nrepl-port
                               (binding [*out* *err*]
                                 (app-runtime/start-nrepl! nrepl-port)))]
    (try
      (start-runtime!
       {:model-key           model-key
        :memory-runtime-opts memory-runtime-opts
        :session-config      session-runtime-opts
        :rpc-trace-file      rpc-trace-file
        :session-state*      app-runtime/session-state
        :nrepl-runtime       app-runtime/nrepl-runtime
        :resolve-model       app-runtime/resolve-model
        :session-ctx-factory (fn [ai-model session-config]
                               (app-runtime/create-runtime-session-context ai-model {:event-queue             (java.util.concurrent.LinkedBlockingQueue.)
                                                                                     :session-config          session-config
                                                                                     :ui-type                 :emacs
                                                                                     :thinking-level-override thinking-level}))
        :bootstrap-fn!       (fn [ctx session-id ai-model memory-runtime-opts]
                               (app-runtime/bootstrap-runtime-session! ctx session-id ai-model {:memory-runtime-opts memory-runtime-opts
                                                                                                :cwd (:cwd ctx)}))
        :on-new-session!     (fn [source-session-id]
                               (app-runtime/new-session-with-startup-in! (:ctx @app-runtime/session-state)
                                                                         source-session-id
                                                                         nil
                                                                         (:ai-model @app-runtime/session-state)))})
      (finally
        (app-runtime/stop-nrepl! nrepl-srv)))))

(defn run-tui-session!
  [args]
  (let [start!               @(requiring-resolve 'psi.tui.app/start!)
        model-key            (model-key-from-args args)
        thinking-level       (thinking-level-from-args args)
        memory-runtime-opts  (memory-runtime-opts-from-args args)
        session-runtime-opts (session-runtime-config-from-args args)
        nrepl-port           (nrepl-port-from-args args)
        nrepl-srv            (when nrepl-port
                               (app-runtime/start-nrepl! nrepl-port))]
    (try
      (app-runtime/start-tui-runtime! start! model-key memory-runtime-opts session-runtime-opts
                                      {:thinking-level-override thinking-level})
      (finally
        (app-runtime/stop-nrepl! nrepl-srv)))))

(defn- exit!
  [code]
  (System/exit code))

(defn -main
  [& args]
  (when (has-flag? args "--version")
    (println (str "psi " (version/version-string)))
    (System/exit 0))
  (set-log-level! (log-level-from-args args))
  (let [tui?     (has-flag? args "--tui")
        rpc-edn? (has-flag? args "--rpc-edn")]
    (cond
      tui?
      (run-tui-session! args)

      rpc-edn?
      (run-rpc-session! args)

      :else
      (run-console-session! args))
    (exit! 0)))
