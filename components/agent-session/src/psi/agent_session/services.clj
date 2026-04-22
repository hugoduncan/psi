(ns psi.agent-session.services
  "Ctx-owned managed subprocess services keyed by logical identity."
  (:import
   (java.io File)
   (java.util UUID)))

(defn create-registry
  "Create an isolated managed-service registry. Raw process handles remain in the
   registry state and are not intended for direct EQL projection."
  []
  {:state (atom {:services {}})})

(defn- now []
  (java.time.Instant/now))

(defn- normalize-command [command]
  (cond
    (vector? command) command
    (sequential? command) (vec command)
    (string? command) [command]
    :else (throw (ex-info "Service command must be a string or seq"
                          {:command command}))))

(defn- process-alive? [process]
  (and process (.isAlive ^Process process)))

(defn- service-running? [service]
  (and (= :running (:status service))
       (process-alive? (:process service))))

(defn- spawn-subprocess! [{:keys [command cwd env]}]
  (let [cmd (normalize-command command)
        pb  (ProcessBuilder. ^java.util.List cmd)]
    (when cwd
      (.directory pb (File. (str cwd))))
    (when (map? env)
      (let [penv (.environment pb)]
        (doseq [[k v] env]
          (.put penv (name k) (str v)))))
    (.start pb)))

(defn service-in [ctx key]
  (get-in @(:state (:service-registry ctx)) [:services key]))

(defn update-service-in!
  [ctx key f & args]
  (apply swap! (:state (:service-registry ctx)) update-in [:services key] f args)
  (service-in ctx key))

(defn set-service-runtime-fns-in!
  "Attach transport/runtime adapter fns to an existing managed service.

   Intended for protocol adapters and tests. These fns remain runtime-only and
   are not projected through EQL.

   Supported keys:
   - :send-fn            fire-and-forget write function
   - :notify-fn          optional notification-specific write function
   - :request-fn         synchronous request/response shim
   - :await-response-fn  async response waiter: (fn [{:request-id :payload :timeout-ms}])"
  [ctx key runtime-fns]
  (update-service-in! ctx key merge runtime-fns))

(defn services-in [ctx]
  (vals (get-in @(:state (:service-registry ctx)) [:services])))

(defn service-keys-in [ctx]
  (keys (get-in @(:state (:service-registry ctx)) [:services])))

(defn service-count-in [ctx]
  (count (service-keys-in ctx)))

(defn ensure-service-in!
  [ctx {:keys [key type spec ext-path]
        :or {type :subprocess}}]
  (when-not key
    (throw (ex-info "Managed service requires :key" {:input {:key key :type type :spec spec}})))
  (when-not (= :subprocess type)
    (throw (ex-info "Only :subprocess managed services are supported"
                    {:type type :key key})))
  (let [reg   (:service-registry ctx)
        state (:state reg)
        svc   (get-in @state [:services key])]
    (if (service-running? svc)
      svc
      (let [process (spawn-subprocess! spec)
            svc'    {:id            (str (UUID/randomUUID))
                     :key           key
                     :type          :subprocess
                     :status        :running
                     :command       (normalize-command (:command spec))
                     :cwd           (:cwd spec)
                     :env           (:env spec)
                     :transport     (or (:transport spec) :stdio)
                     :protocol      (:protocol spec)
                     :ext-path      ext-path
                     :process       process
                     :pid           (.pid process)
                     :started-at    (now)
                     :stopped-at    nil
                     :restart-count (if svc (long (inc (or (:restart-count svc) 0))) 0)
                     :last-error    nil}]
        (swap! state assoc-in [:services key] svc')
        (service-in ctx key)))))

(defn stop-service-in!
  [ctx key]
  (let [reg   (:service-registry ctx)
        state (:state reg)
        svc   (get-in @state [:services key])]
    (when-let [process (:process svc)]
      (when (process-alive? process)
        (.destroy ^Process process)))
    (when svc
      (let [stopped (assoc svc
                           :status :stopped
                           :stopped-at (now))]
        (swap! state assoc-in [:services key] stopped)
        stopped))))

(defn project-service
  "Project a service into a pure map for resolvers / extension surfaces."
  [service]
  (when service
    (select-keys service [:id :key :type :status :command :cwd :transport
                          :ext-path :pid :started-at :stopped-at
                          :restart-count :last-error])))

(defn projected-services-in [ctx]
  (mapv project-service (services-in ctx)))
