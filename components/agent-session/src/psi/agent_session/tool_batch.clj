(ns psi.agent-session.tool-batch
  "Dispatch-owned tool batch execution helpers.

   Canonical home for shared-session tool batch execution ordering,
   parallelism, and result recording."
  (:require
   [psi.turn-runtime.tool-args :as tool-args]
   [psi.agent-session.dispatch :as dispatch])
  (:import
   (java.util.concurrent Callable ConcurrentHashMap ExecutorService Future)
   (java.util.concurrent.locks ReentrantLock)))

(defn run-tool-call!
  "Dispatch one tool call through the runtime-effect boundary."
  [ctx session-id tool-call progress-queue]
  (dispatch/dispatch! ctx :session/tool-run
                      {:session-id     session-id
                       :tool-call      tool-call
                       :parsed-args    (or (:parsed-args tool-call)
                                           (tool-args/parse-args (:arguments tool-call)))
                       :progress-queue progress-queue}
                      {:origin :core}))

(defn- tool-batch-executor [ctx]
  (or (:tool-batch-executor ctx)
      (throw (ex-info "No tool batch executor configured on ctx"
                      {:missing :tool-batch-executor}))))

(defn- tool-call-file-key
  "Return a canonical file-path key for a tool call, or nil if the call has no
   file argument. Checks the most common argument spellings across built-in
   tools (path, file, file_path) in both string and keyword forms."
  [tool-call]
  (let [args (or (:parsed-args tool-call)
                 (tool-args/parse-args (:arguments tool-call)))]
    (some (fn [k] (when-let [v (get args k)]
                    (when (string? v) (not-empty v))))
          ["path" "file" "file_path" :path :file :file_path])))

(defn- acquire-file-lock!
  "Acquire the ReentrantLock for `file-key` from `lock-map`, creating one if absent."
  ^ReentrantLock [^ConcurrentHashMap lock-map file-key]
  (let [lock (or (.get lock-map file-key)
                 (let [new-lock (ReentrantLock.)]
                   (or (.putIfAbsent lock-map file-key new-lock) new-lock)))]
    (.lock lock)
    lock))

(defn- make-tool-call-task
  "Build a Callable for one tool call. When `file-key` is non-nil the task
   acquires the corresponding per-file lock before executing and releases it
   afterwards, serialising concurrent calls that target the same file."
  [ctx session-id tool-call progress-queue file-key lock-map]
  ^Callable
  (fn []
    (let [parsed-args (or (:parsed-args tool-call)
                          (tool-args/parse-args (:arguments tool-call)))]
      (if file-key
        (let [^ReentrantLock lk (acquire-file-lock! lock-map file-key)]
          (try
            (dispatch/dispatch! ctx :session/tool-execute-prepared
                                {:session-id     session-id
                                 :tool-call      tool-call
                                 :parsed-args    parsed-args
                                 :progress-queue progress-queue}
                                {:origin :core})
            (finally (.unlock lk))))
        (dispatch/dispatch! ctx :session/tool-execute-prepared
                            {:session-id     session-id
                             :tool-call      tool-call
                             :parsed-args    parsed-args
                             :progress-queue progress-queue}
                            {:origin :core})))))

(defn run-tool-calls!
  "Execute a batch of tool calls and return tool results in tool-call order.

   Tool calls that target the same file path are serialised via per-file locks
   so that concurrent reads/writes to the same file cannot interleave. Calls
   targeting different files (or no file at all) still execute in parallel up
   to the configured batch parallelism limit."
  [ctx session-id tool-calls progress-queue]
  (let [tool-calls* (vec tool-calls)
        task-count  (count tool-calls*)]
    (cond
      (zero? task-count)
      []

      (= 1 task-count)
      [(run-tool-call! ctx session-id (first tool-calls*) progress-queue)]

      :else
      (let [executor  ^ExecutorService (tool-batch-executor ctx)
            lock-map  (ConcurrentHashMap.)
            tasks     (mapv (fn [tc]
                              (make-tool-call-task ctx session-id tc progress-queue
                                                   (tool-call-file-key tc) lock-map))
                            tool-calls*)
            futures   (.invokeAll executor ^java.util.Collection tasks)]
        (mapv (fn [^Future future]
                (let [shaped-result (.get future)]
                  (dispatch/dispatch! ctx :session/tool-record-result
                                      {:session-id     session-id
                                       :shaped-result  shaped-result
                                       :progress-queue progress-queue}
                                      {:origin :core})))
              futures)))))

