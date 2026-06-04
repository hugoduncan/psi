(ns psi.project-nrepl.started
  "Started-mode acquisition for managed project nREPL instances."
  (:require
   [clojure.java.io :as io]
   [psi.project-nrepl.client :as project-nrepl-client]
   [psi.project-nrepl.config :as project-nrepl-config]
   [psi.project-nrepl.runtime :as project-nrepl-runtime])
  (:import
   (java.io File)
   (java.util UUID)))

(def ^:private default-readiness-timeout-ms 120000)
(def ^:private default-poll-interval-ms 50)

(defn- now []
  (java.time.Instant/now))

(defn- normalize-command
  [command-vector]
  (mapv str command-vector))

(defn real-process-launcher
  "Real-default `:process-launcher` seam implementation.

   Launches `command-vector` as a real OS process in `worktree-path`, returning
   the `java.lang.Process`."
  [worktree-path command-vector]
  (let [pb (ProcessBuilder. ^java.util.List (normalize-command command-vector))]
    (.directory pb (File. worktree-path))
    (.start pb)))

(defn- read-dot-nrepl-port-safe
  [worktree-path]
  (try
    (project-nrepl-config/read-dot-nrepl-port worktree-path)
    (catch clojure.lang.ExceptionInfo _
      nil)))

(defn- process-exited?
  [^Process process]
  (not (.isAlive process)))

(defn- floored-to-whole-seconds
  "Floor an epoch-milliseconds instant to whole seconds.

   The mtime acceptance gate compares against this floor so a legitimately-fresh
   `.nrepl-port` written in the same second as launch is not rejected under
   coarse filesystem mtime granularity (AMB4)."
  [epoch-ms]
  (* 1000 (quot epoch-ms 1000)))

(defn- port-file-mtime-ms
  "Last-modified epoch-millis of `<worktree>/.nrepl-port`, or nil when absent."
  [worktree-path]
  (let [f (io/file worktree-path ".nrepl-port")]
    (when (.exists f)
      (.lastModified f))))

(defn wait-for-started-endpoint!
  "Wait for `.nrepl-port` to appear and parse successfully for a started process.
   Returns {:host :port :port-source} or throws with diagnosable failure context.

   When `opts` carries `:launched-at` (a `java.time.Instant`), a `.nrepl-port`
   is accepted only when its last-modified time is `≥ (:launched-at floored to
   whole seconds)` — the stale-port ownership gate (A1). A present-but-too-old
   port is treated as not-yet-ready (poll continues); a deadline hit while only
   a too-old port exists is reported as `:phase :started-stale-port`."
  ([worktree-path process]
   (wait-for-started-endpoint! worktree-path process {}))
  ([worktree-path process opts]
   (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)
         effective-timeout-ms (long (or (:timeout-ms opts) default-readiness-timeout-ms))
         deadline           (+ (System/currentTimeMillis) effective-timeout-ms)
         poll-ms            (long (or (:poll-interval-ms opts) default-poll-interval-ms))
         launched-at        (:launched-at opts)
         min-mtime-ms       (when launched-at
                              (floored-to-whole-seconds (.toEpochMilli ^java.time.Instant launched-at)))]
     (loop []
       (let [endpoint (read-dot-nrepl-port-safe effective-worktree)
             mtime-ms (when endpoint (port-file-mtime-ms effective-worktree))
             fresh?   (or (nil? min-mtime-ms)
                          (nil? mtime-ms)
                          (>= mtime-ms min-mtime-ms))]
         (if (and endpoint fresh?)
           (assoc endpoint :host "127.0.0.1")
           (do
             (when (process-exited? process)
               ;; A2/IR1: when the exited process left only a too-old port, the
               ;; stale-port diagnostic wins so the :started-stale-port
               ;; distinction is not lost on the exit-with-stale-port path.
               (if (and endpoint (not fresh?))
                 (throw (ex-info "Started project nREPL process exited leaving only a stale .nrepl-port"
                                 {:phase :started-stale-port
                                  :worktree-path effective-worktree
                                  :command-exited? true
                                  :exit-code (.exitValue process)
                                  :path (.getAbsolutePath (io/file effective-worktree ".nrepl-port"))
                                  :port-mtime-ms mtime-ms
                                  :min-mtime-ms min-mtime-ms
                                  :launched-at launched-at}))
                 (throw (ex-info "Started project nREPL process exited before .nrepl-port became ready"
                                 {:phase :started-readiness
                                  :worktree-path effective-worktree
                                  :command-exited? true
                                  :exit-code (.exitValue process)}))))
             (when (>= (System/currentTimeMillis) deadline)
               (if (and endpoint (not fresh?))
                 (throw (ex-info "Timed out waiting for a fresh started project nREPL .nrepl-port (only a stale port was present)"
                                 {:phase :started-stale-port
                                  :worktree-path effective-worktree
                                  :timeout-ms effective-timeout-ms
                                  :path (.getAbsolutePath (io/file effective-worktree ".nrepl-port"))
                                  :port-mtime-ms mtime-ms
                                  :min-mtime-ms min-mtime-ms
                                  :launched-at launched-at}))
                 (throw (ex-info "Timed out waiting for started project nREPL .nrepl-port"
                                 {:phase :started-readiness
                                  :worktree-path effective-worktree
                                  :timeout-ms effective-timeout-ms
                                  :path (.getAbsolutePath (io/file effective-worktree ".nrepl-port"))}))))
             (Thread/sleep poll-ms)
             (recur))))))))

(defn start-instance-in!
  "Start a managed started-mode project nREPL instance for `worktree-path`.
   First slice only launches the configured command and discovers the endpoint
   via `.nrepl-port`; nREPL socket/session connection follows in a later slice."
  ([ctx worktree-path command-vector]
   (start-instance-in! ctx worktree-path command-vector {}))
  ([ctx worktree-path command-vector opts]
   (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)
         validated-command  (project-nrepl-config/resolved-start-command
                             {:project-nrepl {:start-command command-vector}})
         _                  (project-nrepl-runtime/ensure-instance-in!
                             ctx
                             {:worktree-path effective-worktree
                              :acquisition-mode :started
                              :command-vector validated-command
                              :runtime-handle (:runtime-handle opts)})
         ;; Hold the launched process in a scope visible to the catch so an
         ;; alive-but-port-less hang (the headline slow-boot scenario) is reaped
         ;; on the readiness-failure path rather than orphaned (IR2).
         launched-process   (volatile! nil)]
     (try
       (let [instance (project-nrepl-runtime/instance-in ctx effective-worktree)
             launcher (or (get-in instance [:runtime-handle :process-launcher])
                          real-process-launcher)
             effective-timeout-ms (long (or (:timeout-ms opts) default-readiness-timeout-ms))
             ;; Launch instant captured once (INC1): the sole source for both the
             ;; runtime-handle :started-at and the mtime-gate reference, written
             ;; pre-wait so both survive the throwing failure path (PA1/PA2).
             launched-at (now)]
         ;; Pre-launch removal (Q2/A1): delete any pre-existing .nrepl-port so
         ;; any subsequently-observed port file is necessarily new.
         (.delete (project-nrepl-config/dot-nrepl-port-file effective-worktree))
         ;; Launch-site update (pre-wait): records the effective resolved timeout
         ;; and the launch-instant :started-at before the wait can throw.
         (project-nrepl-runtime/update-instance-in!
          ctx effective-worktree
          #(-> %
               (assoc :readiness-timeout-ms effective-timeout-ms)
               (update :runtime-handle merge {:started-at launched-at})))
         (let [process  (launcher effective-worktree validated-command)
               ;; Record the process onto the runtime-handle pre-wait (and the
               ;; outer volatile) so the readiness-failure catch and a later
               ;; stop-started-instance-in! can reap it (IR2).
               _        (vreset! launched-process process)
               _        (project-nrepl-runtime/update-instance-in!
                         ctx effective-worktree
                         #(update % :runtime-handle merge
                                  {:process process :pid (.pid process)}))
               endpoint (wait-for-started-endpoint!
                         effective-worktree process
                         (assoc opts :launched-at launched-at))]
           (project-nrepl-runtime/update-instance-in!
            ctx effective-worktree
            #(-> %
                 (assoc :lifecycle-state :starting
                        :readiness false
                        :endpoint endpoint
                        :last-error nil)
                 (update :runtime-handle merge {:launch-id (str (UUID/randomUUID))})))
           (project-nrepl-client/connect-instance-in! ctx effective-worktree)))
       (catch Throwable t
         ;; Reap an alive launched process on the readiness-failure path so a
         ;; hung/slow-boot child JVM is not orphaned (IR2). destroy is a no-op
         ;; on an already-exited process.
         (when-let [^Process process @launched-process]
           (when (.isAlive process)
             (.destroy process)))
         (project-nrepl-runtime/update-instance-in!
          ctx effective-worktree
          #(assoc %
                  :lifecycle-state :failed
                  :readiness false
                  :last-error {:message (.getMessage t)
                               :data (ex-data t)
                               :at (now)}))
         (throw t))))))

(defn stop-started-instance-in!
  [ctx worktree-path]
  (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)
        instance           (project-nrepl-runtime/instance-in ctx effective-worktree)
        process            (get-in instance [:runtime-handle :process])]
    (project-nrepl-client/disconnect-instance-in! ctx effective-worktree)
    (when (and process (.isAlive ^Process process))
      (.destroy ^Process process))
    (project-nrepl-runtime/update-instance-in!
     ctx effective-worktree
     #(assoc %
             :lifecycle-state :stopping
             :readiness false))
    (project-nrepl-runtime/remove-instance-in! ctx effective-worktree)))
