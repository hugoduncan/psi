(ns extensions.workflow-loader.orchestration
  (:require
   [clojure.string :as str]
   [extensions.workflow-loader.text :as text])
  (:import
   (java.util UUID)
   (java.util.concurrent Future)))

(def workflow-loader-ext-path
  "extensions/workflow-loader")

(defn background-job-tool-call-id
  [run-id]
  (str "delegate/" run-id))

(defn start-background-job!
  [mutate! session-id run-id _workflow-name]
  (let [job-id (str "job-" (UUID/randomUUID))
        result (mutate! 'psi.extension/start-background-job
                        {:session-id session-id
                         :tool-call-id (background-job-tool-call-id run-id)
                         :tool-name "delegate"
                         :job-id job-id
                         :job-kind :workflow
                         :workflow-ext-path workflow-loader-ext-path
                         :workflow-id run-id})]
    {:job-id (:psi.background-job/job-id result)
     :status (:psi.background-job/status result)}))

(defn mark-background-job-terminal!
  [mutate! job-id status payload {:keys [suppress-terminal-message?] :as _opts}]
  (let [outcome (case status
                  :completed :completed
                  :cancelled :cancelled
                  :timeout :timed-out
                  :timed-out :timed-out
                  :failed :failed
                  :failed)]
    (mutate! 'psi.extension/mark-background-job-terminal
             (cond-> {:job-id job-id
                      :outcome outcome
                      :payload payload}
               (some? suppress-terminal-message?)
               (assoc :suppress-terminal-message? suppress-terminal-message?)))))

(defn continue-workflow-input
  [prompt-text]
  {:input prompt-text
   :original prompt-text})

(defn- uninteresting-stack-frame?
  [^StackTraceElement ste]
  (let [cn (.getClassName ste)]
    (or (str/starts-with? cn "clojure.lang.")
        (str/starts-with? cn "clojure.core$")
        (str/starts-with? cn "java.")
        (str/starts-with? cn "javax.")
        (str/starts-with? cn "jdk.")
        (str/starts-with? cn "sun."))))

(defn exception-summary
  ([e] (exception-summary e 4))
  ([e n]
   (let [msg (or (ex-message e) (.getMessage e) (str e))
         stack (.getStackTrace e)
         preferred (->> stack
                        (remove uninteresting-stack-frame?)
                        (take n))
         chosen (if (seq preferred)
                  preferred
                  (take n stack))
         frames (map (fn [^StackTraceElement ste]
                       (str (.getClassName ste) "/" (.getMethodName ste)
                            " (" (.getFileName ste) ":" (.getLineNumber ste) ")"))
                     chosen)]
     (if (seq frames)
       (str msg " | " (str/join " <= " frames))
       msg))))

(defn delegated-result-publication
  [{:keys [run-id workflow-name parent-session-id include-result? exec-result]}]
  (let [status (:psi.workflow/status exec-result)
        result-text (some-> (:psi.workflow/result exec-result) str/trim not-empty)
        ok? (= :completed status)
        chat-delivery? (and include-result? ok? (some? result-text))]
    {:completion {:run-id run-id
                  :workflow workflow-name
                  :parent-session-id parent-session-id
                  :status status
                  :result-text result-text
                  :error (:psi.workflow/error exec-result)
                  :include-result? include-result?
                  :ok? ok?}
     :background-job {:payload {:run-id run-id
                                :workflow workflow-name
                                :status status
                                :result result-text
                                :error (:psi.workflow/error exec-result)}
                      :suppress-terminal-message? chat-delivery?}
     :chat-injection {:enabled? chat-delivery?
                      :parent-session-id parent-session-id
                      :run-id run-id
                      :result-text result-text}
     :notification {:enabled? (not chat-delivery?)
                    :message (text/completion-notification-text workflow-name status run-id)
                    :level (if ok? :info :warn)}
     :append-entry {:enabled? (not include-result?)
                    :custom-type "delegate-result"
                    :data (text/completion-entry-content workflow-name
                                                         status
                                                         run-id
                                                         result-text
                                                         include-result?)}}))

(defn on-async-completion!
  "Handle async workflow completion — notify, inject results, update canonical job state, and clean up waits."
  [{:keys [mutate! notify! mark-background-job-terminal! inject-result-into-context!
           refresh-widgets! inflight-runs]}
   run-id workflow-name parent-session-id include-result? exec-result]
  (let [{:keys [completion background-job chat-injection notification append-entry] :as _publication}
        (delegated-result-publication
         {:run-id run-id
          :workflow-name workflow-name
          :parent-session-id parent-session-id
          :include-result? include-result?
          :exec-result exec-result})
        {:keys [status]} completion
        job-id (get-in @inflight-runs [run-id :job-id])]
    (when job-id
      (mark-background-job-terminal!
       job-id
       status
       (:payload background-job)
       {:suppress-terminal-message? (:suppress-terminal-message? background-job)}))
    (when (:enabled? chat-injection)
      (inject-result-into-context! (:parent-session-id chat-injection)
                                   (:run-id chat-injection)
                                   (:result-text chat-injection)))
    (when (:enabled? notification)
      (notify! (:message notification)
               (:level notification)))
    (when-let [mf mutate!]
      (when (:enabled? append-entry)
        (try
          (mf 'psi.extension/append-entry
              {:custom-type (:custom-type append-entry)
               :data (:data append-entry)})
          (catch Exception _ nil))))
    (swap! inflight-runs dissoc run-id)
    (refresh-widgets!)))

(defn execute-async!
  "Launch workflow execution asynchronously on a separate thread.
   Canonical job state is authoritative; inflight-runs only supports local sync waits."
  [{:keys [mutate! start-background-job! mark-background-job-terminal! notify!
           refresh-widgets! inflight-runs on-async-completion-fn]}
   run-id session-id workflow-name include-result?]
  (let [parent-session-id session-id
        {:keys [job-id]} (start-background-job! session-id run-id workflow-name)
        fut (future
              (try
                (let [exec-result (mutate! 'psi.workflow/execute-run
                                           {:run-id run-id
                                            :session-id session-id})]
                  (on-async-completion-fn run-id workflow-name parent-session-id include-result? exec-result)
                  (if (:psi.workflow/error exec-result)
                    {:run-id run-id
                     :workflow workflow-name
                     :status :failed
                     :error (:psi.workflow/error exec-result)}
                    {:run-id run-id
                     :workflow workflow-name
                     :status (:psi.workflow/status exec-result)
                     :result (:psi.workflow/result exec-result)}))
                (catch Exception e
                  (when job-id
                    (try
                      (mark-background-job-terminal!
                       job-id
                       :failed
                       {:run-id run-id
                        :workflow workflow-name
                        :status :failed
                        :error (exception-summary e)})
                      (catch Exception _ nil)))
                  (notify! (str "Workflow '" workflow-name "' failed: " (exception-summary e)) :error)
                  (swap! inflight-runs dissoc run-id)
                  (refresh-widgets!)
                  {:psi.workflow/status :failed
                   :psi.workflow/error (exception-summary e)})))]
    (swap! inflight-runs assoc run-id
           {:future fut
            :job-id job-id})
    (refresh-widgets!)
    run-id))

(defn continue-terminal-run-async!
  [{:keys [mutate! execute-async! find-run-summary-fn]}
   run-id session-id prompt-text include?]
  (let [{:keys [source-definition-id]} (find-run-summary-fn run-id)]
    (when-not source-definition-id
      (throw (ex-info "Cannot continue run without source definition" {:run-id run-id})))
    (let [new-run-name (str source-definition-id "-continue-" (System/currentTimeMillis))
          create-result (mutate! 'psi.workflow/create-run
                                 {:definition-id source-definition-id
                                  :workflow-input (continue-workflow-input prompt-text)
                                  :run-id new-run-name})
          new-run-id (:psi.workflow/run-id create-result)]
      (when-not new-run-id
        (throw (ex-info (or (:psi.workflow/error create-result)
                            "Failed to create continuation run")
                        {:run-id run-id
                         :definition-id source-definition-id})))
      (execute-async! new-run-id session-id source-definition-id include?)
      {:ok true
       :run-id new-run-id
       :status :running
       :continued-from run-id})))

(defn continue-blocked-run-async!
  [{:keys [mutate! start-background-job! mark-background-job-terminal! notify!
           refresh-widgets! inflight-runs on-async-completion-fn]}
   run-id session-id prompt-text include?]
  (let [parent-session-id session-id
        workflow-name (str "resume-" run-id)
        {:keys [job-id]} (start-background-job! session-id run-id workflow-name)
        fut (future
              (try
                (let [result (mutate! 'psi.workflow/resume-run
                                      {:run-id run-id
                                       :session-id session-id
                                       :workflow-input (continue-workflow-input prompt-text)})]
                  (when-not (:psi.workflow/error result)
                    (on-async-completion-fn run-id workflow-name parent-session-id include? result))
                  (if (:psi.workflow/error result)
                    {:run-id run-id
                     :workflow workflow-name
                     :status :failed
                     :error (:psi.workflow/error result)}
                    {:run-id run-id
                     :workflow workflow-name
                     :status (:psi.workflow/status result)
                     :result (:psi.workflow/result result)}))
                (catch Exception e
                  (when job-id
                    (try
                      (mark-background-job-terminal!
                       job-id
                       :failed
                       {:run-id run-id
                        :workflow workflow-name
                        :status :failed
                        :error (exception-summary e)})
                      (catch Exception _ nil)))
                  (notify! (str "Resume of run '" run-id "' failed: " (exception-summary e)) :error)
                  (swap! inflight-runs dissoc run-id)
                  (refresh-widgets!)
                  {:psi.workflow/status :failed
                   :psi.workflow/error (exception-summary e)})))]
    (swap! inflight-runs assoc run-id
           {:future fut
            :job-id job-id})
    (refresh-widgets!)
    {:ok true
     :run-id run-id
     :status :resuming}))

(defn await-run-completion
  "Block until a workflow run completes or timeout is reached.
   Returns the execution result."
  [inflight-runs run-id timeout-ms]
  (let [^Future fut (get-in @inflight-runs [run-id :future])]
    (if fut
      (try
        (.get fut timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
        (catch java.util.concurrent.TimeoutException _
          {:psi.workflow/status :timeout
           :psi.workflow/error (str "Timed out after " timeout-ms "ms")})
        (catch Exception e
          {:psi.workflow/status :failed
           :psi.workflow/error (ex-message e)}))
      {:psi.workflow/error "Run not found in active tracking"})))
