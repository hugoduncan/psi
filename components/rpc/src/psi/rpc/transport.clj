(ns psi.rpc.transport
  "EDN-lines RPC transport runtime helpers."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [psi.agent-session.session-state :as ss]
   [psi.rpc.state :as rpc.state]))

(def protocol-version "1.0")

(defn- stop-all-managed-threads!
  [state]
  ;; Stop long-lived transport-owned loops. Do not interrupt in-flight prompt
  ;; workers here: existing RPC contract/tests expect accepted prompt work to
  ;; continue emitting events briefly after input EOF in harness scenarios.
  (rpc.state/stop-managed-workers! state))

(def ^:private request-required-keys #{:id :kind :op})
(def ^:private request-allowed-keys  #{:id :kind :op :params})

(def ^:private response-allowed-keys [:id :kind :op :ok :data])
(def ^:private error-allowed-keys    [:kind :id :op :error-code :error-message :retryable :data])
(def ^:private event-allowed-keys    [:kind :event :data :id :seq :ts])

(def supported-rpc-ops
  ["handshake"
   "ping"
   "query_eql"
   "command"
   "frontend_action_result"
   "prompt"
   "prompt_while_streaming"
   "steer"
   "follow_up"
   "abort"
   "interrupt"
   "list_background_jobs"
   "inspect_background_job"
   "cancel_background_job"
   "login_begin"
   "login_complete"
   "new_session"
   "switch_session"
   "list_sessions"
   "fork"
   "set_session_name"
   "set_model"
   "cycle_model"
   "set_thinking_level"
   "cycle_thinking_level"
   "compact"
   "set_auto_compaction"
   "set_auto_retry"
   "get_state"
   "get_messages"
   "get_session_stats"
   "subscribe"
   "unsubscribe"
   "resolve_dialog"
   "cancel_dialog"])

(def targetable-rpc-ops
  #{"query_eql"
    "command"
    "frontend_action_result"
    "prompt"
    "prompt_while_streaming"
    "steer"
    "follow_up"
    "abort"
    "interrupt"
    "login_begin"
    "new_session"
    "switch_session"
    "list_sessions"
    "list_background_jobs"
    "inspect_background_job"
    "cancel_background_job"
    "subscribe"
    "fork"
    "set_session_name"
    "set_model"
    "cycle_model"
    "set_thinking_level"
    "cycle_thinking_level"
    "compact"
    "set_auto_compaction"
    "set_auto_retry"
    "get_state"
    "get_messages"
    "get_session_stats"
    "resolve_dialog"
    "cancel_dialog"})

(defn default-session-id-in
  [ctx]
  (some-> (ss/list-context-sessions-in ctx) first :session-id))

(defn response-frame
  ([id op ok]
   (response-frame id op ok nil))
  ([id op ok data]
   (cond-> (array-map :id id :kind :response :op op :ok ok)
     (some? data) (assoc :data data))))

(defn error-frame
  [{:keys [id op error-code error-message retryable data]}]
  (cond-> (array-map :kind :error
                     :error-code error-code
                     :error-message error-message)
    (some? id)        (assoc :id id)
    (some? op)        (assoc :op op)
    (some? retryable) (assoc :retryable retryable)
    (some? data)      (assoc :data data)))

(defn- normalize-ts [ts]
  (cond
    (nil? ts) nil
    (string? ts) ts
    :else (str ts)))

(defn event-frame
  [{:keys [event data id seq ts]}]
  (let [ts* (normalize-ts ts)]
    (cond-> (array-map :kind :event :event event :data data)
      (some? id)  (assoc :id id)
      (some? seq) (assoc :seq seq)
      (some? ts*) (assoc :ts ts*))))

(defn- normalize-kind [k]
  (cond
    (keyword? k) k
    (string? k)  (keyword k)
    :else        k))

(defn- canonicalize-outbound-frame [frame]
  (case (normalize-kind (:kind frame))
    :response (select-keys frame response-allowed-keys)
    :error    (select-keys frame error-allowed-keys)
    :event    (select-keys frame event-allowed-keys)
    (error-frame {:id            (:id frame)
                  :op            (:op frame)
                  :error-code    "protocol/invalid-envelope"
                  :error-message "unsupported outbound frame kind"
                  :data          {:kind (:kind frame)}})))

(defn- edn-wire-safe
  "Recursively coerce values to EDN-safe transport shapes.

   Notably converts java.time values to strings because `pr-str` renders
   java.time.Instant as `#object[...]`, which is not EDN-readable."
  [x]
  (cond
    (or (nil? x)
        (string? x)
        (number? x)
        (keyword? x)
        (symbol? x)
        (boolean? x)
        (char? x))
    x

    (instance? java.time.temporal.TemporalAccessor x)
    (str x)

    (map? x)
    (into (empty x)
          (map (fn [[k v]] [k (edn-wire-safe v)]))
          x)

    (vector? x)
    (mapv edn-wire-safe x)

    (set? x)
    (set (map edn-wire-safe x))

    (sequential? x)
    (mapv edn-wire-safe x)

    :else
    (str x)))

(defn- safe-trace!
  [trace-fn payload]
  (when trace-fn
    (try
      (trace-fn payload)
      (catch Throwable _
        nil))))

(defn make-frame-writer
  "Return a serialized frame emitter writing one EDN map per line to `out-writer`.

   Optional `trace-fn` receives transport trace payloads:
   {:dir :out :raw <wire-line> :frame <canonical-frame>}"
  ([^java.io.Writer out-writer]
   (make-frame-writer out-writer nil))
  ([^java.io.Writer out-writer trace-fn]
   (let [lock   (Object.)
         writer (java.io.BufferedWriter. out-writer)]
     (fn emit-frame! [frame]
       (let [canonical (edn-wire-safe (canonicalize-outbound-frame frame))
             line      (pr-str canonical)]
         (locking lock
           (.write writer (str line "\n"))
           (.flush writer))
         (safe-trace! trace-fn {:dir :out
                                :raw line
                                :frame canonical}))))))

(defn- invalid-envelope [frame-id frame-op message]
  (error-frame {:id            frame-id
                :op            frame-op
                :error-code    "protocol/invalid-envelope"
                :error-message message}))

(defn- parse-request-line [line]
  (try
    (let [frame (edn/read-string line)]
      (cond
        (not (map? frame))
        {:error (invalid-envelope nil nil "request frame must be an EDN map")}

        (not= :request (normalize-kind (:kind frame)))
        {:error (invalid-envelope (:id frame) (:op frame) "request frame :kind must be :request")}

        (not (every? #(contains? frame %) request-required-keys))
        {:error (invalid-envelope (:id frame) (:op frame) "request frame missing required keys")}

        (not (every? request-allowed-keys (keys frame)))
        {:error (invalid-envelope (:id frame) (:op frame) "request frame contains unsupported keys")}

        :else
        {:ok frame}))
    (catch Throwable _
      {:error (error-frame {:error-code    "transport/invalid-frame"
                            :error-message "unable to parse EDN request frame"})})))

(defn default-request-handler
  "Default request handler used before per-op routing is implemented."
  [request]
  (let [op (:op request)]
    (case op
      "ping"
      (response-frame (:id request) "ping" true {:pong true :protocol-version protocol-version})

      (error-frame {:id            (:id request)
                    :op            (:op request)
                    :error-code    "request/op-not-supported"
                    :error-message (str "unsupported op: " op)
                    :data          {:supported-ops supported-rpc-ops}}))))

(defn- valid-request-id? [id]
  (and (string? id) (not (str/blank? id))))

(defn- valid-request-op? [op]
  (and (string? op) (not (str/blank? op))))

(defn- terminal-frame? [frame]
  (contains? #{:response :error} (normalize-kind (:kind frame))))

(defn- clear-pending-if-terminal! [state frame]
  (when-let [id (:id frame)]
    (when (and (string? id) (terminal-frame? frame))
      (rpc.state/clear-pending! state id))))

(defn- make-tracked-emitter [emit-frame! state]
  (fn emit-tracked! [frame]
    (emit-frame! frame)
    (clear-pending-if-terminal! state frame)))

(defn- request-error
  [request error-code error-message]
  (error-frame {:id            (:id request)
                :op            (:op request)
                :error-code    error-code
                :error-message error-message}))

(defn- protocol-major [version]
  (when (string? version)
    (some->> (re-find #"^(\d+)(?:\..*)?$" version)
             second
             Integer/parseInt)))

(defn- handle-handshake!
  [request _emit-frame! state {:keys [handshake-server-info-fn]}]
  (let [version (or (get-in request [:params :client-info :protocol-version])
                    (get-in request [:params :protocol-version]))
        major   (protocol-major version)]
    (cond
      (not (string? version))
      (request-error request
                     "request/invalid-params"
                     "handshake requires :params :client-info :protocol-version string")

      (not= 1 major)
      (request-error request
                     "protocol/unsupported-version"
                     (str "unsupported protocol major: " (or major "unknown")))

      :else
      (let [server-info-fn (or handshake-server-info-fn
                               (fn [_state] {:protocol-version protocol-version}))
            server-info (merge {:protocol-version protocol-version}
                               (or (server-info-fn state)
                                   {}))]
        (rpc.state/mark-ready! state protocol-version)
        (response-frame (:id request)
                        "handshake"
                        true
                        {:server-info server-info})))))

(defn- accept-request!
  "Register a request as pending when valid and capacity allows.
   Returns {:ok true} or {:error <canonical-error-frame>}"
  [request state]
  (let [id      (:id request)
        op      (:op request)
        pending (rpc.state/pending state)
        max-p   (long (rpc.state/max-pending-requests state))]
    (cond
      (not (valid-request-id? id))
      {:error (request-error request "request/invalid-id" "request :id must be a non-empty string")}

      (not (valid-request-op? op))
      {:error (request-error request "request/invalid-op" "request :op must be a non-empty string")}

      (contains? pending id)
      {:error (request-error request "request/invalid-id" "duplicate request :id")}

      (>= (count pending) max-p)
      {:error (request-error request "transport/max-pending-exceeded" "maximum pending requests exceeded")}

      :else
      (do
        (rpc.state/add-pending! state id op)
        {:ok true}))))

(defn- process-request!
  [request {:keys [state request-handler emit-tracked! handshake-server-info-fn]}]
  (let [op (:op request)
        ready? (rpc.state/ready? state)]
    (cond
      (and (not ready?) (not= "handshake" op))
      (emit-tracked! (request-error request
                                    "transport/not-ready"
                                    "handshake must complete before non-handshake requests"))

      (= "handshake" op)
      (if-let [error (:error (accept-request! request state))]
        (emit-tracked! error)
        (emit-tracked! (handle-handshake! request emit-tracked! state {:handshake-server-info-fn handshake-server-info-fn})))

      :else
      (if-let [error (:error (accept-request! request state))]
        (emit-tracked! error)
        (try
          (let [result (binding [*out* (rpc.state/err-writer state)]
                         (request-handler request emit-tracked! state))]
            (cond
              (nil? result)
              nil

              (map? result)
              (emit-tracked! result)

              (sequential? result)
              (doseq [frame result]
                (emit-tracked! frame))

              :else
              (emit-tracked! (error-frame {:id            (:id request)
                                           :op            (:op request)
                                           :error-code    "runtime/failed"
                                           :error-message "request handler returned unsupported result type"}))))
          (catch Throwable t
            (emit-tracked! (error-frame {:id            (:id request)
                                         :op            (:op request)
                                         :error-code    "runtime/failed"
                                         :error-message (or (ex-message t)
                                                            "unhandled runtime exception")}))))))))

(defn run-stdio-loop!
  "Run an EDN-lines RPC loop.

   Options:
   - :in               java.io.Reader (default *in*)
   - :out              java.io.Writer (default *out*)
   - :err              java.io.Writer (default *err*)
   - :request-handler  (fn [request emit-frame! state] -> frame | [frame*] | nil)
   - :state            mutable transport state passed to request-handler
   - :trace-fn         optional (fn [{:dir :in|:out :raw string :frame map :parse-error string?}])
   - :handshake-server-info-fn optional fn of state -> server-info map"
  [{:keys [in out err request-handler state trace-fn handshake-server-info-fn]
    :or   {in *in*
           out *out*
           err *err*
           request-handler (fn [request _emit! _state]
                             (default-request-handler request))
           state (atom {})}}]
  (let [reader      (java.io.BufferedReader. in)
        emit-frame! (make-frame-writer out trace-fn)]
    (rpc.state/initialize-transport-state! state err)
    (let [emit-tracked! (make-tracked-emitter emit-frame! state)
          emit-error!   (fn [error-code error-message]
                          (emit-frame! (error-frame {:error-code error-code
                                                     :error-message error-message})))]
      (try
        (doseq [line (line-seq reader)]
          (if (str/blank? line)
            (do
              (safe-trace! trace-fn {:dir :in
                                     :raw line
                                     :parse-error "empty frame"})
              (emit-error! "transport/invalid-frame" "empty frame"))
            (let [{:keys [ok error]} (parse-request-line line)]
              (if error
                (do
                  (safe-trace! trace-fn {:dir :in
                                         :raw line
                                         :frame error
                                         :parse-error (:error-message error)})
                  (emit-frame! error))
                (do
                  (safe-trace! trace-fn {:dir :in
                                         :raw line
                                         :frame ok})
                  (process-request! ok {:state state
                                        :request-handler request-handler
                                        :emit-tracked! emit-tracked!
                                        :handshake-server-info-fn handshake-server-info-fn}))))))
        (finally
          (stop-all-managed-threads! state))))))

