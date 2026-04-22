(ns psi.agent-session.project-nrepl-started
  "Started-mode acquisition for managed project nREPL instances."
  (:require
   [clojure.java.io :as io]
   [psi.agent-session.project-nrepl-client :as project-nrepl-client]
   [psi.agent-session.project-nrepl-config :as project-nrepl-config]
   [psi.agent-session.project-nrepl-runtime :as project-nrepl-runtime])
  (:import
   (java.io File)
   (java.util UUID)))

(def ^:private default-readiness-timeout-ms 5000)
(def ^:private default-poll-interval-ms 50)

(defn- now []
  (java.time.Instant/now))

(defn- normalize-command
  [command-vector]
  (mapv str command-vector))

(defn- start-process!
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

(defn wait-for-started-endpoint!
  "Wait for `.nrepl-port` to appear and parse successfully for a started process.
   Returns {:host :port :port-source} or throws with diagnosable failure context."
  ([worktree-path process]
   (wait-for-started-endpoint! worktree-path process {}))
  ([worktree-path process opts]
   (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)
         deadline           (+ (System/currentTimeMillis) (long (or (:timeout-ms opts) default-readiness-timeout-ms)))
         poll-ms            (long (or (:poll-interval-ms opts) default-poll-interval-ms))]
     (loop []
       (if-let [endpoint (read-dot-nrepl-port-safe effective-worktree)]
         (assoc endpoint :host "127.0.0.1")
         (do
           (when (process-exited? process)
             (throw (ex-info "Started project nREPL process exited before .nrepl-port became ready"
                             {:phase :started-readiness
                              :worktree-path effective-worktree
                              :command-exited? true
                              :exit-code (.exitValue process)})))
           (when (>= (System/currentTimeMillis) deadline)
             (throw (ex-info "Timed out waiting for started project nREPL .nrepl-port"
                             {:phase :started-readiness
                              :worktree-path effective-worktree
                              :timeout-ms (:timeout-ms opts)
                              :path (.getAbsolutePath (io/file effective-worktree ".nrepl-port"))})))
           (Thread/sleep poll-ms)
           (recur)))))))

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
                              :command-vector validated-command})]
     (try
       (let [process  (start-process! effective-worktree validated-command)
             endpoint (wait-for-started-endpoint! effective-worktree process opts)]
         (project-nrepl-runtime/update-instance-in!
          ctx effective-worktree
          #(assoc %
                  :lifecycle-state :starting
                  :readiness false
                  :endpoint endpoint
                  :runtime-handle {:process process
                                   :pid (.pid process)
                                   :started-at (now)
                                   :launch-id (str (UUID/randomUUID))}
                  :last-error nil))
         (project-nrepl-client/connect-instance-in! ctx effective-worktree))
       (catch Throwable t
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
