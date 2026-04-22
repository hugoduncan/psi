(ns extensions.commit-checks
  "Run configurable external commit checks after commit creation.

   Trigger:
   - extension event: git_commit_created

   Config:
   - <workspace-dir>/.psi/commit-checks.edn

   Behavior:
   - runs configured commands with babashka.process in the session workspace dir
   - collects only non-zero exit results
   - injects one combined prompt when any checks fail"
  (:require
   [babashka.process :as proc]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private custom-type "commit-checks")
(def ^:private default-max-output-chars 12000)
(def ^:private default-prompt-header
  "Commit checks failed after the latest commit. Diagnose and fix the problems with minimal changes.")
(def ^:private default-timeout-ms 30000)

(defn- workspace-dir
  [{:keys [workspace-dir cwd]}]
  (or (some-> workspace-dir str not-empty)
      (some-> cwd str not-empty)))

(defn- config-path [workspace-dir]
  (str (io/file workspace-dir ".psi" "commit-checks.edn")))

(defn- read-config [workspace-dir]
  (let [path (config-path workspace-dir)
        f    (io/file path)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn- truncate-output [max-chars s]
  (let [text (str (or s ""))
        n    (max 0 (long (or max-chars default-max-output-chars)))]
    (if (<= (count text) n)
      text
      (str (subs text 0 n)
           "\n\n[output truncated to " n " chars]"))))

(defn- valid-cmd?
  [cmd]
  (and (vector? cmd)
       (seq cmd)
       (every? string? cmd)))

(defn- run-command!
  [{:keys [id cmd timeout-ms]} workspace-dir]
  (when-not (valid-cmd? cmd)
    (throw (ex-info "commit check command must be a non-empty vector of strings"
                    {:id id :cmd cmd})))
  (let [timeout-ms* (or timeout-ms default-timeout-ms)
        proc*       (apply proc/process
                           {:dir workspace-dir
                            :out :string
                            :err :string
                            :in :inherit}
                           cmd)
        result      (deref proc* timeout-ms* ::timeout)]
    (if (= ::timeout result)
      (do
        (try
          (.destroyForcibly ^Process (:proc proc*))
          (catch Exception _ nil))
        {:id id
         :cmd cmd
         :timeout-ms timeout-ms*
         :exit :timeout
         :output (str "Command timed out after " timeout-ms* "ms")})
      {:id id
       :cmd cmd
       :timeout-ms timeout-ms*
       :exit (:exit result)
       :output (str (:out result) (:err result))})))

(defn- failing-result?
  [{:keys [exit]}]
  (or (= :timeout exit)
      (and (number? exit) (not= 0 exit))))

(defn- render-command [cmd]
  (str/join " " cmd))

(defn- render-failure-section
  [max-output-chars {:keys [id cmd exit output]}]
  (str "## " (or (not-empty (str id)) (render-command cmd)) "\n"
       "command: " (render-command cmd) "\n"
       "exit: " exit "\n"
       "output:\n"
       (truncate-output max-output-chars output) "\n"))

(defn- build-prompt
  [{:keys [prompt-header max-output-chars]} {:keys [workspace-dir head session-id]} failures]
  (str (or (not-empty prompt-header) default-prompt-header)
       "\n\n"
       "workspace-dir: " workspace-dir "\n"
       "session-id: " session-id "\n"
       "commit: " (or head "unknown") "\n\n"
       "Failed checks:\n\n"
       (str/join "\n" (map #(render-failure-section (or max-output-chars default-max-output-chars) %) failures))
       "\nPlease inspect these failures and make the minimal necessary fixes."))

(defn- notify!
  [mutate-fn text]
  (mutate-fn 'psi.extension/notify
             {:role "assistant"
              :content text
              :custom-type custom-type}))

(defn- send-prompt!
  [mutate-fn text source]
  (mutate-fn 'psi.extension/send-prompt
             {:content text
              :source source}))

(defn- run-configured-checks!
  [config workspace-dir]
  (->> (:commands config)
       (mapv #(run-command! % workspace-dir))
       (filterv failing-result?)))

(defn handle-git-commit-created
  [mutate-fn {:keys [session-id head] :as ev}]
  (when-let [workspace-dir* (workspace-dir ev)]
    (when-let [config (read-config workspace-dir*)]
      (when (not= false (:enabled config))
        (let [failures (run-configured-checks! config workspace-dir*)]
          (when (seq failures)
            (let [prompt (build-prompt config {:workspace-dir workspace-dir*
                                               :head head
                                               :session-id session-id}
                                       failures)
                  result (send-prompt! mutate-fn prompt {:type "commit-checks"
                                                         :session-id session-id
                                                         :workspace-dir workspace-dir*
                                                         :head head})]
              (notify! mutate-fn
                       (str "Commit checks found " (count failures)
                            " failing command"
                            (when (not= 1 (count failures)) "s")
                            "; injected follow-up prompt ("
                            (name (or (:psi.extension/prompt-delivery result) :unknown)) ")."))
              {:failures failures
               :prompt-sent? true
               :workspace-dir workspace-dir*})))))))

(defn init [api]
  (let [mutate-fn (:mutate api)]
    (mutate-fn 'psi.extension/register-handler
               {:event-name "git_commit_created"
                :handler-fn (fn [ev]
                              (try
                                (handle-git-commit-created mutate-fn ev)
                                (catch Exception e
                                  (notify! mutate-fn
                                           (str "Commit checks error: " (ex-message e)))
                                  {:error (ex-message e)})))})
    nil))
