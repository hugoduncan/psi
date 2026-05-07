(ns psi.tool-runtime.batch
  "Lower-level tool batch execution helpers: canonical arg parsing, per-file
   serialization, bounded parallelism, and ordered result recording."
  (:require
   [psi.tool-runtime.args :as tool-args])
  (:import
   (java.util.concurrent Callable ConcurrentHashMap ExecutorService Future)
   (java.util.concurrent.locks ReentrantLock)))

(defn run-tool-call!
  "Run one tool call through the provided lower-level callback.

   Services map keys:
   - :run-one!       fn [tool-call parsed-args] -> result
   - :parse-args-fn  optional fn [arguments] -> parsed args"
  [{:keys [run-one! parse-args-fn]} tool-call]
  (let [parse-fn    (or parse-args-fn tool-args/parse-args)
        parsed-args (or (:parsed-args tool-call)
                        (parse-fn (:arguments tool-call)))]
    (run-one! tool-call parsed-args)))

(defn tool-call-file-key
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
  [{:keys [execute-prepared! parse-args-fn]} tool-call file-key lock-map]
  ^Callable
  (fn []
    (let [parse-fn    (or parse-args-fn tool-args/parse-args)
          parsed-args (or (:parsed-args tool-call)
                          (parse-fn (:arguments tool-call)))]
      (if file-key
        (let [^ReentrantLock lk (acquire-file-lock! lock-map file-key)]
          (try
            (execute-prepared! tool-call parsed-args)
            (finally
              (.unlock lk))))
        (execute-prepared! tool-call parsed-args)))))

(defn run-tool-calls!
  "Execute a batch of tool calls and return results in tool-call order.

   Services map keys:
   - :executor           shared ExecutorService for parallel batches
   - :run-one!           fn [tool-call parsed-args] -> final result
   - :execute-prepared!  fn [tool-call parsed-args] -> shaped result
   - :record-result!     fn [shaped-result] -> final result
   - :parse-args-fn      optional fn [arguments] -> parsed args
   - :file-key-fn        optional fn [tool-call] -> file-key"
  [{:keys [executor record-result! file-key-fn] :as services}
   tool-calls]
  (let [tool-calls* (vec tool-calls)
        task-count  (count tool-calls*)
        key-fn      (or file-key-fn tool-call-file-key)]
    (cond
      (zero? task-count)
      []

      (= 1 task-count)
      [(run-tool-call! services (first tool-calls*))]

      :else
      (let [executor* ^ExecutorService (or executor
                                           (throw (ex-info "No tool batch executor configured"
                                                           {:missing :executor})))
            lock-map  (ConcurrentHashMap.)
            tasks     (mapv (fn [tc]
                              (make-tool-call-task services tc (key-fn tc) lock-map))
                            tool-calls*)
            futures   (.invokeAll executor* ^java.util.Collection tasks)]
        (mapv (fn [^Future future]
                (record-result! (.get future)))
              futures)))))
