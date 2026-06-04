(ns psi.project-nrepl.config
  "Config, targeting, and file-based discovery helpers for managed project nREPL support."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.shared-config.project :as project-config]
   [psi.shared-config.resolution :as shared-resolution]
   [psi.shared-config.user :as user-config]))

(def ^:private system-defaults
  {:project-nrepl {}})

(defn read-user-config
  []
  (user-config/read-config))

(defn read-project-preferences
  [cwd]
  (project-config/read-preferences cwd))

(defn resolve-config
  "Return merged project nREPL config for `cwd`.

   Precedence: system < user < project.
   Config lives under [:agent-session :project-nrepl] in the existing user and
   project config files."
  [cwd]
  (let [user    (or (:project-nrepl (shared-resolution/agent-session-map (read-user-config))) {})
        project (or (:project-nrepl (shared-resolution/agent-session-map (read-project-preferences cwd))) {})]
    (project-config/deep-merge system-defaults {:project-nrepl user} {:project-nrepl project})))

(defn resolve-target-worktree
  "Resolve the effective project nREPL target worktree.

   Precedence:
   1. explicit target worktree/path
   2. invoking session worktree-path
   3. explicit error"
  [{:keys [target-worktree-path session-worktree-path]}]
  (let [target (or target-worktree-path session-worktree-path)]
    (when-not (and (string? target) (not (str/blank? target)))
      (throw (ex-info "project nREPL operations require explicit target-worktree-path or invoking session worktree-path"
                      {:phase :target-resolution
                       :target-worktree-path target-worktree-path
                       :session-worktree-path session-worktree-path})))
    target))

(defn absolute-directory-path!
  [path]
  (when-not (and (string? path) (not (str/blank? path)))
    (throw (ex-info "project nREPL worktree-path must be a non-blank string"
                    {:phase :validate :worktree-path path})))
  (let [f (io/file path)]
    (when-not (.isAbsolute f)
      (throw (ex-info "project nREPL worktree-path must be absolute"
                      {:phase :validate :worktree-path path})))
    (when-not (and (.exists f) (.isDirectory f))
      (throw (ex-info "project nREPL worktree-path must resolve to an existing directory"
                      {:phase :validate :worktree-path path})))
    (.getAbsolutePath f)))

(defn resolved-start-command
  "Return the validated started-mode start command vector or nil when unset."
  [cfg]
  (let [start-command (get-in cfg [:project-nrepl :start-command])]
    (cond
      (nil? start-command) nil
      (not (vector? start-command))
      (throw (ex-info "project nREPL start-command must be a vector of strings"
                      {:phase :validate :start-command start-command}))

      (empty? start-command)
      (throw (ex-info "project nREPL start-command must not be empty"
                      {:phase :validate :start-command start-command}))

      (not-every? string? start-command)
      (throw (ex-info "project nREPL start-command entries must all be strings"
                      {:phase :validate :start-command start-command}))

      (str/blank? (first start-command))
      (throw (ex-info "project nREPL start-command first element must be a non-blank command path string"
                      {:phase :validate :start-command start-command}))

      :else start-command)))

(defn resolved-start-readiness-timeout-ms
  "Return the validated started-mode readiness-timeout in ms or nil when unset.

   Read from [:project-nrepl :start-readiness-timeout-ms]. When set it must be an
   integer in the range [1000 600000] (1 s–10 min). nil when unset so callers fall
   back to the started.clj default. Out-of-range / non-integer throws
   `ex-info` `{:phase :validate}`, mirroring `resolved-attach-endpoint`'s port-range
   idiom."
  [cfg]
  (let [timeout-ms (get-in cfg [:project-nrepl :start-readiness-timeout-ms])]
    (cond
      (nil? timeout-ms) nil
      (not (and (integer? timeout-ms) (<= 1000 timeout-ms 600000)))
      (throw (ex-info "project nREPL start-readiness-timeout-ms must be an integer in the range 1000-600000 when provided"
                      {:phase :validate :start-readiness-timeout-ms timeout-ms}))

      :else timeout-ms)))

(defn resolved-attach-endpoint
  "Return the validated attach endpoint map or nil when unset.

   Supports explicit port with optional host. Port may be omitted so callers can
   fall back to worktree-local .nrepl-port discovery."
  [cfg]
  (let [attach (get-in cfg [:project-nrepl :attach])
        {:keys [host port]} attach]
    (cond
      (nil? attach) nil
      (not (map? attach))
      (throw (ex-info "project nREPL attach config must be a map"
                      {:phase :validate :attach attach}))

      (and (contains? attach :host)
           (not (and (string? host) (not (str/blank? host)))))
      (throw (ex-info "project nREPL attach host must be a non-blank string when provided"
                      {:phase :validate :attach attach}))

      (and (contains? attach :port)
           (not (and (integer? port) (<= 1 port 65535))))
      (throw (ex-info "project nREPL attach port must be an integer in the range 1-65535 when provided"
                      {:phase :validate :attach attach}))

      (and (not (contains? attach :port))
           (not (contains? attach :host)))
      nil

      :else (cond-> {}
              (contains? attach :host) (assoc :host host)
              (contains? attach :port) (assoc :port port)))))

(defn dot-nrepl-port-file
  [worktree-path]
  (io/file worktree-path ".nrepl-port"))

(defn read-dot-nrepl-port
  "Read and validate .nrepl-port from `worktree-path`. Returns {:port p :port-source :dot-nrepl-port}."
  [worktree-path]
  (let [target    (absolute-directory-path! worktree-path)
        port-file (dot-nrepl-port-file target)]
    (when-not (.exists port-file)
      (throw (ex-info "project nREPL endpoint discovery requires .nrepl-port in the target worktree"
                      {:phase :discover-endpoint
                       :worktree-path target
                       :path (.getAbsolutePath port-file)})))
    (let [raw  (str/trim (slurp port-file))
          port (try
                 (edn/read-string raw)
                 (catch Exception _ ::invalid))]
      (when-not (integer? port)
        (throw (ex-info "project nREPL .nrepl-port must contain an integer port"
                        {:phase :discover-endpoint
                         :worktree-path target
                         :path (.getAbsolutePath port-file)
                         :value raw})))
      (when-not (<= 1 port 65535)
        (throw (ex-info "project nREPL .nrepl-port must contain a port in the range 1-65535"
                        {:phase :discover-endpoint
                         :worktree-path target
                         :path (.getAbsolutePath port-file)
                         :value raw
                         :port port})))
      {:port port
       :port-source :dot-nrepl-port})))