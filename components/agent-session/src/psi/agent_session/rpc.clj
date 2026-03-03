(ns psi.agent-session.rpc
  "EDN-lines RPC transport runtime helpers.

   Transport guarantees in this namespace:
   - one top-level EDN map per input line
   - canonical outbound frame envelopes
   - serialized outbound frame writing
   - protocol-only stdout (handler diagnostics are rebound to stderr)"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-core.core :as agent]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.persistence :as persist]
   [psi.ai.models :as ai-models]
   [psi.tui.extension-ui :as ext-ui]))

(def protocol-version "1.0")
(def ^:private default-max-pending-requests 64)

(def ^:private request-required-keys #{:id :kind :op})
(def ^:private request-allowed-keys  #{:id :kind :op :params})

(def ^:private response-allowed-keys [:id :kind :op :ok :data])
(def ^:private error-allowed-keys    [:kind :id :op :error-code :error-message :retryable :data])
(def ^:private event-allowed-keys    [:kind :event :data :id :seq :ts])

(def ^:private supported-rpc-ops
  ["handshake"
   "ping"
   "query_eql"
   "prompt"
   "steer"
   "follow_up"
   "abort"
   "new_session"
   "switch_session"
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

(defn make-frame-writer
  "Return a serialized frame emitter writing one EDN map per line to `out-writer`."
  [^java.io.Writer out-writer]
  (let [lock   (Object.)
        writer (java.io.BufferedWriter. out-writer)]
    (fn emit-frame! [frame]
      (locking lock
        (.write writer (str (pr-str (canonicalize-outbound-frame frame)) "\n"))
        (.flush writer)))))

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
      (swap! state update :pending dissoc id))))

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

(defn- handle-handshake! [request state]
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
      (let [server-info-fn (or (:handshake-server-info-fn @state)
                               (fn [] {:protocol-version protocol-version}))
            server-info    (merge {:protocol-version protocol-version}
                                  (or (server-info-fn) {}))]
        (swap! state assoc
               :ready? true
               :negotiated-protocol-version protocol-version)
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
        pending (:pending @state)
        max-p   (long (or (:max-pending-requests @state)
                          default-max-pending-requests))]
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
        (swap! state update :pending assoc id op)
        {:ok true}))))

(defn- process-request!
  [request {:keys [state request-handler emit-tracked!]}]
  (let [op (:op request)
        ready? (:ready? @state)]
    (cond
      (and (not ready?) (not= "handshake" op))
      (emit-tracked! (request-error request
                                    "transport/not-ready"
                                    "handshake must complete before non-handshake requests"))

      (= "handshake" op)
      (if-let [error (:error (accept-request! request state))]
        (emit-tracked! error)
        (emit-tracked! (handle-handshake! request state)))

      :else
      (if-let [error (:error (accept-request! request state))]
        (emit-tracked! error)
        (try
          (let [result (binding [*out* (:err @state)]
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

(defn- params-map
  [request]
  (let [params (:params request)]
    (when-not (or (nil? params) (map? params))
      (throw (ex-info "request :params must be a map"
                      {:error-code "request/invalid-params"})))
    (or params {})))

(defn- req-arg!
  [request params k pred desc]
  (let [v (get params k)]
    (when-not (pred v)
      (throw (ex-info (str "invalid request parameter " k ": " desc)
                      {:error-code "request/invalid-params"})))
    v))

(defn- parse-query-edn!
  [query-str]
  (let [q (try
            (binding [*read-eval* false]
              (edn/read-string query-str))
            (catch Throwable _
              (throw (ex-info "query must be valid EDN"
                              {:error-code "request/invalid-query"}))))]
    (when-not (vector? q)
      (throw (ex-info "query must be an EDN vector"
                      {:error-code "request/invalid-query"})))
    q))

(defn- dialog-result-valid?
  [v]
  (or (nil? v) (boolean? v) (string? v)))

(defn- active-dialog-or-error!
  [ctx]
  (let [ui-state-atom (:ui-state-atom ctx)
        active        (when ui-state-atom (ext-ui/active-dialog ui-state-atom))]
    (when-not (map? active)
      (throw (ex-info "no active dialog"
                      {:error-code "request/no-active-dialog"})))
    active))

(defn- handle-resolve-dialog!
  [ctx request params]
  (let [dialog-id (req-arg! request params :dialog-id #(and (string? %) (not (str/blank? %))) "non-empty string")
        result    (get params :result ::missing)
        _         (when (= ::missing result)
                    (throw (ex-info "invalid request parameter :result: boolean, string, or nil"
                                    {:error-code "request/invalid-params"})))
        _         (when-not (dialog-result-valid? result)
                    (throw (ex-info "invalid request parameter :result: boolean, string, or nil"
                                    {:error-code "request/invalid-params"})))
        active    (active-dialog-or-error! ctx)
        active-id (:id active)]
    (when-not (= dialog-id active-id)
      (throw (ex-info "dialog-id mismatch"
                      {:error-code "request/dialog-id-mismatch"})))
    (when-not (ext-ui/resolve-dialog! (:ui-state-atom ctx) dialog-id result)
      (throw (ex-info "no active dialog"
                      {:error-code "request/no-active-dialog"})))
    (response-frame (:id request) "resolve_dialog" true {:accepted true})))

(defn- handle-cancel-dialog!
  [ctx request params]
  (let [dialog-id (req-arg! request params :dialog-id #(and (string? %) (not (str/blank? %))) "non-empty string")
        active    (active-dialog-or-error! ctx)
        active-id (:id active)]
    (when-not (= dialog-id active-id)
      (throw (ex-info "dialog-id mismatch"
                      {:error-code "request/dialog-id-mismatch"})))
    (when-not (ext-ui/cancel-dialog! (:ui-state-atom ctx))
      (throw (ex-info "no active dialog"
                      {:error-code "request/no-active-dialog"})))
    (response-frame (:id request) "cancel_dialog" true {:accepted true})))

(defn- normalize-provider [provider]
  (cond
    (keyword? provider) provider
    (string? provider)  (keyword provider)
    :else               nil))

(defn resolve-model
  [provider model-id]
  (let [provider* (normalize-provider provider)]
    (some (fn [[_ model]]
            (when (and (= provider* (:provider model))
                       (= model-id (:id model)))
              model))
          ai-models/all-models)))

(defn session->handshake-server-info
  [ctx]
  (let [sd (session/get-session-data-in ctx)]
    {:protocol-version protocol-version
     :features         ["eql-graph" "eql-memory"]
     :session-id       (:session-id sd)
     :model-id         (get-in sd [:model :id])
     :thinking-level   (some-> (:thinking-level sd) name)}))

(defn- exception->error-frame
  [request e]
  (let [code    (or (:error-code (ex-data e))
                    (when (= "Session is not idle" (ex-message e))
                      "request/session-not-idle")
                    "runtime/failed")
        message (or (ex-message e) "runtime request failed")]
    (error-frame {:id            (:id request)
                  :op            (:op request)
                  :error-code    code
                  :error-message message})))

(def ^:private event-topics
  #{"session/updated"
    "session/resumed"
    "session/rehydrated"
    "assistant/delta"
    "assistant/message"
    "tool/start"
    "tool/delta"
    "tool/executing"
    "tool/update"
    "tool/result"
    "ui/dialog-requested"
    "ui/widgets-updated"
    "ui/status-updated"
    "ui/notification"
    "footer/updated"
    "error"})

(def ^:private required-event-payload-keys
  {"session/updated" #{:session-id :phase :is-streaming :is-compacting :pending-message-count :retry-attempt}
   "session/resumed" #{:session-id :session-file :message-count}
   "session/rehydrated" #{:messages :tool-calls :tool-order}
   "assistant/delta" #{:text}
   "assistant/message" #{:role :content}
   "tool/start" #{:tool-id :tool-name}
   "tool/delta" #{:tool-id :arguments}
   "tool/executing" #{:tool-id :tool-name}
   "tool/update" #{:tool-id :tool-name :content :result-text :is-error}
   "tool/result" #{:tool-id :tool-name :content :result-text :is-error}
   "ui/dialog-requested" #{:dialog-id :kind :title}
   "ui/widgets-updated" #{:widgets}
   "ui/status-updated" #{:statuses}
   "ui/notification" #{:id :message :level}
   "footer/updated" #{:path-line :stats-line}
   "error" #{:error-code :error-message}})

(defn- topic-subscribed?
  [state topic]
  (let [subs (:subscribed-topics @state)]
    (or (empty? subs)
        (contains? subs topic))))

(defn- next-event-seq!
  [state]
  (-> (swap! state update :event-seq (fnil inc 0))
      :event-seq))

(defn- emit-event!
  [emit-frame! state {:keys [event data id]}]
  (when (and (contains? event-topics event)
             (topic-subscribed? state event))
    (let [required (get required-event-payload-keys event #{})
          payload  (or data {})
          missing  (seq (remove #(contains? payload %) required))]
      (if missing
        (emit-frame! (event-frame {:event "error"
                                   :id id
                                   :seq (next-event-seq! state)
                                   :ts (java.time.Instant/now)
                                   :data {:error-code "protocol/invalid-event-payload"
                                          :error-message "missing required event payload keys"
                                          :event event
                                          :missing-keys (vec missing)}}))
        (emit-frame! (event-frame {:event event
                                   :data payload
                                   :id id
                                   :seq (next-event-seq! state)
                                   :ts (java.time.Instant/now)}))))))

(defn- normalize-level [lvl]
  (cond
    (keyword? lvl) (name lvl)
    (string? lvl)  lvl
    :else          "info"))

(defn- session-updated-payload
  [ctx]
  (let [sd (session/get-session-data-in ctx)]
    {:session-id            (:session-id sd)
     :phase                 (some-> (session/sc-phase-in ctx) name)
     :is-streaming          (boolean (:is-streaming sd))
     :is-compacting         (boolean (:is-compacting sd))
     :pending-message-count (+ (count (:steering-messages sd))
                               (count (:follow-up-messages sd)))
     :retry-attempt         (or (:retry-attempt sd) 0)}))

(defn- footer-updated-payload
  [ctx]
  (let [sd    (session/get-session-data-in ctx)
        phase (some-> (session/sc-phase-in ctx) name)]
    {:path-line   (str "cwd: " (:cwd ctx))
     :stats-line  (str "session=" (:session-id sd) " phase=" phase)
     :status-line (when (:is-streaming sd) "streaming")}))

(defn- progress-event->rpc-event
  [progress-event]
  (let [k (:event-kind progress-event)]
    (case k
      :text-delta
      {:event "assistant/delta"
       :data  {:text (or (:text progress-event) "")}}

      :tool-start
      {:event "tool/start"
       :data  {:tool-id   (:tool-id progress-event)
               :tool-name (:tool-name progress-event)}}

      :tool-delta
      {:event "tool/delta"
       :data  {:tool-id   (:tool-id progress-event)
               :arguments (or (:arguments progress-event) "")}}

      :tool-executing
      {:event "tool/executing"
       :data  (cond-> {:tool-id   (:tool-id progress-event)
                       :tool-name (:tool-name progress-event)}
                (some? (:parsed-args progress-event)) (assoc :parsed-args (:parsed-args progress-event)))}

      :tool-execution-update
      {:event "tool/update"
       :data  {:tool-id     (:tool-id progress-event)
               :tool-name   (:tool-name progress-event)
               :content     (or (:content progress-event) [])
               :result-text (or (:result-text progress-event) "")
               :details     (:details progress-event)
               :is-error    (boolean (:is-error progress-event))}}

      :tool-result
      {:event "tool/result"
       :data  {:tool-id     (:tool-id progress-event)
               :tool-name   (:tool-name progress-event)
               :content     (or (:content progress-event) [])
               :result-text (or (:result-text progress-event) "")
               :details     (:details progress-event)
               :is-error    (boolean (:is-error progress-event))}}

      nil)))

(defn- emit-progress-queue!
  [progress-q emit!]
  (loop []
    (when-let [evt (.poll progress-q)]
      (when-let [{:keys [event data]} (progress-event->rpc-event evt)]
        (emit! event data))
      (recur))))

(defn- ui-snapshot->events
  [previous current]
  (let [events []
        events (if (and (not= (:active-dialog previous) (:active-dialog current))
                        (map? (:active-dialog current)))
                 (conj events {:event "ui/dialog-requested"
                               :data (let [d (:active-dialog current)]
                                       (cond-> {:dialog-id (:id d)
                                                :kind      (some-> (:kind d) name)
                                                :title     (:title d)}
                                         (contains? d :message) (assoc :message (:message d))
                                         (contains? d :options) (assoc :options (:options d))
                                         (contains? d :placeholder) (assoc :placeholder (:placeholder d))))})
                 events)
        events (if (not= (:widgets previous) (:widgets current))
                 (conj events {:event "ui/widgets-updated"
                               :data  {:widgets (or (:widgets current) [])}})
                 events)
        events (if (not= (:statuses previous) (:statuses current))
                 (conj events {:event "ui/status-updated"
                               :data  {:statuses (or (:statuses current) [])}})
                 events)
        previous-notes (into {} (map (juxt :id identity) (or (:visible-notifications previous) [])))
        current-notes  (into {} (map (juxt :id identity) (or (:visible-notifications current) [])))
        new-notes      (remove #(contains? previous-notes (:id %)) (vals current-notes))]
    (reduce (fn [acc n]
              (conj acc {:event "ui/notification"
                         :data  {:id           (:id n)
                                 :extension-id (:extension-id n)
                                 :message      (:message n)
                                 :level        (normalize-level (:level n))}}))
            events
            new-notes)))

(defn- handle-command-result!
  "Map a commands/dispatch result to canonical RPC event emissions.
   Emits assistant/message (and session/updated + footer/updated via caller)
   for each command result type without invoking the agent loop."
  [cmd-result emit!]
  (let [result-type (:type cmd-result)]
    (case result-type
      (:text :logout :login-error :new-session)
      (emit! "assistant/message"
             {:role    "assistant"
              :content [{:type :text :text (str (:message cmd-result))}]})

      :login-start
      (emit! "assistant/message"
             {:role    "assistant"
              :content [{:type :text
                         :text (str "Login: " (get-in cmd-result [:provider :name])
                                    " — open URL: " (:url cmd-result))}]})

      :quit
      (emit! "assistant/message"
             {:role    "assistant"
              :content [{:type :text
                         :text "[/quit is not supported over RPC prompt — use the abort op or close the connection]"}]})

      :resume
      (emit! "assistant/message"
             {:role    "assistant"
              :content [{:type :text
                         :text "[/resume is not supported over RPC prompt — use switch_session op]"}]})

      :extension-cmd
      (let [output (try
                     (let [out (with-out-str
                                 ((:handler cmd-result) (:args cmd-result)))]
                       (if (str/blank? out)
                         "[extension command returned no output]"
                         out))
                     (catch Throwable e
                       (str "[extension command error: " (ex-message e) "]")))]
        (emit! "assistant/message"
               {:role    "assistant"
                :content [{:type :text :text output}]}))

      ;; Unknown result type — emit a fallback
      (emit! "assistant/message"
             {:role    "assistant"
              :content [{:type :text :text (str "[command result: " result-type "]")}]}))))

(defn- run-prompt-async!
  [ctx request emit-frame! state]
  (let [message      (get-in request [:params :message])
        images       (get-in request [:params :images])
        request-id   (:id request)
        run-loop-fn  (or (:run-agent-loop-fn @state) executor/run-agent-loop!)
        progress-q   (java.util.concurrent.LinkedBlockingQueue.)
        ui-state-atom (:ui-state-atom ctx)
        stop?        (atom false)
        worker       (future
                       (binding [*out* (:err @state)
                                 *err* (:err @state)]
                         (let [emit! (fn [event payload]
                                       (emit-event! emit-frame! state {:event event :data payload :id request-id}))
                               ui-loop (future
                                         (loop [last-snap (or (ext-ui/snapshot ui-state-atom) {})]
                                           (when-not @stop?
                                             (let [current (or (ext-ui/snapshot ui-state-atom) {})]
                                               (doseq [{:keys [event data]} (ui-snapshot->events last-snap current)]
                                                 (emit! event data))
                                               (Thread/sleep 50)
                                               (recur current)))))]
                           (try
                             (let [sd         (session/get-session-data-in ctx)
                                   ai-model   (or (when-let [provider (get-in sd [:model :provider])]
                                                    (when-let [model-id (get-in sd [:model :id])]
                                                      (resolve-model provider model-id)))
                                                  (:rpc-ai-model @state))
                                   _          (when-not ai-model
                                                (throw (ex-info "session model is not configured"
                                                                {:error-code "request/invalid-params"})))
                                   oauth-ctx  (:oauth-ctx ctx)
                                   user-msg   {:role      "user"
                                               :content   (cond-> [{:type :text :text message}]
                                                            (seq images) (into images))
                                               :timestamp (java.time.Instant/now)}
                                   ;; Journal the user message before dispatch so slash commands
                                   ;; leave a trace in session history regardless of command match.
                                   _          (session/journal-append-in! ctx (persist/message-entry user-msg))
                                   cmd-result (commands/dispatch ctx message {:oauth-ctx oauth-ctx
                                                                              :ai-model  ai-model})]
                               (if (some? cmd-result)
                                 ;; Slash command matched — handle result, skip agent loop
                                 (do
                                   (handle-command-result! cmd-result emit!)
                                   (emit! "session/updated" (session-updated-payload ctx))
                                   (emit! "footer/updated" (footer-updated-payload ctx)))
                                 ;; Not a command — run normal agent loop
                                 (let [_        (emit! "session/updated" (session-updated-payload ctx))
                                       _        (emit! "footer/updated" (footer-updated-payload ctx))
                                       result   (run-loop-fn nil ctx (:agent-ctx ctx) ai-model [user-msg]
                                                             {:turn-ctx-atom  (:turn-ctx-atom ctx)
                                                              :progress-queue progress-q})]
                                   (emit-progress-queue! progress-q emit!)
                                   (emit! "assistant/message"
                                          (cond-> {:role    (:role result)
                                                   :content (or (:content result) [])}
                                            (contains? result :stop-reason)   (assoc :stop-reason (:stop-reason result))
                                            (contains? result :error-message) (assoc :error-message (:error-message result))
                                            (contains? result :usage)         (assoc :usage (:usage result))))
                                   (emit! "session/updated" (session-updated-payload ctx))
                                   (emit! "footer/updated" (footer-updated-payload ctx)))))
                             (catch Throwable t
                               (emit! "error" {:error-code "runtime/failed"
                                               :error-message (or (ex-message t) "prompt execution failed")
                                               :id request-id
                                               :op "prompt"})
                               (emit! "session/updated" (session-updated-payload ctx))
                               (emit! "footer/updated" (footer-updated-payload ctx)))
                             (finally
                               (reset! stop? true)
                               (future-cancel ui-loop))))))]
    (swap! state update :inflight-futures (fnil conj []) worker)
    (response-frame (:id request) "prompt" true {:accepted true})))

(defn make-session-request-handler
  "Create a canonical op router bound to an agent-session context.

   Returned fn signature matches `run-stdio-loop!` request-handler:
   (fn [request emit-frame! state] -> frame | [frame*] | nil)

   Runtime state mutations used by this handler:
   - :subscribed-topics (set of topic strings)"
  [ctx]
  (fn [request emit-frame! state]
    (try
      (let [op     (:op request)
            params (params-map request)]
        (case op
          "ping"
          (response-frame (:id request) "ping" true {:pong true :protocol-version protocol-version})

          "query_eql"
          (let [query-str (req-arg! request params :query #(and (string? %) (not (str/blank? %))) "non-empty EDN string")
                q         (parse-query-edn! query-str)
                result    (try
                            (session/query-in ctx q)
                            (catch Throwable e
                              (throw (ex-info (or (ex-message e) "query execution failed")
                                              {:error-code "runtime/query-failed"}
                                              e))))]
            (response-frame (:id request) op true {:result result}))

          "prompt"
          (let [_message (req-arg! request params :message #(and (string? %) (not (str/blank? %))) "non-empty string")]
            (run-prompt-async! ctx request emit-frame! state))

          "steer"
          (let [message (req-arg! request params :message #(and (string? %) (not (str/blank? %))) "non-empty string")]
            (session/steer-in! ctx message)
            (response-frame (:id request) op true {:accepted true}))

          "follow_up"
          (let [message (req-arg! request params :message #(and (string? %) (not (str/blank? %))) "non-empty string")]
            (session/follow-up-in! ctx message)
            (response-frame (:id request) op true {:accepted true}))

          "abort"
          (do
            (session/abort-in! ctx)
            (response-frame (:id request) op true {:accepted true}))

          "new_session"
          (let [sd (session/new-session-in! ctx)
                msgs (:messages (agent/get-data-in (:agent-ctx ctx)))]
            (emit-event! emit-frame! state {:event "session/resumed"
                                            :id (:id request)
                                            :data {:session-id   (:session-id sd)
                                                   :session-file (:session-file sd)
                                                   :message-count (count msgs)}})
            (emit-event! emit-frame! state {:event "session/rehydrated"
                                            :id (:id request)
                                            :data {:messages msgs
                                                   :tool-calls {}
                                                   :tool-order []}})
            (response-frame (:id request) op true {:session-id (:session-id sd)
                                                   :session-file (:session-file sd)}))

          "switch_session"
          (let [session-path (req-arg! request params :session-path #(and (string? %) (not (str/blank? %))) "non-empty path string")]
            (when-not (.exists (io/file session-path))
              (throw (ex-info "session file not found"
                              {:error-code "request/not-found"})))
            (let [sd   (session/resume-session-in! ctx session-path)
                  msgs (:messages (agent/get-data-in (:agent-ctx ctx)))]
              (emit-event! emit-frame! state {:event "session/resumed"
                                              :id (:id request)
                                              :data {:session-id   (:session-id sd)
                                                     :session-file (:session-file sd)
                                                     :message-count (count msgs)}})
              (emit-event! emit-frame! state {:event "session/rehydrated"
                                              :id (:id request)
                                              :data {:messages msgs
                                                     :tool-calls {}
                                                     :tool-order []}})
              (response-frame (:id request) op true {:session-id (:session-id sd)
                                                     :session-file (:session-file sd)})))

          "fork"
          (let [entry-id (req-arg! request params :entry-id #(and (string? %) (not (str/blank? %))) "non-empty entry id")
                sd       (session/fork-session-in! ctx entry-id)]
            (response-frame (:id request) op true {:session-id (:session-id sd)
                                                   :session-file (:session-file sd)}))

          "set_session_name"
          (let [name (req-arg! request params :name #(and (string? %) (not (str/blank? %))) "non-empty string")
                sd   (session/set-session-name-in! ctx name)]
            (response-frame (:id request) op true {:session-name (:session-name sd)}))

          "set_model"
          (let [provider (req-arg! request params :provider #(or (keyword? %) (string? %)) "string or keyword")
                model-id (req-arg! request params :model-id #(and (string? %) (not (str/blank? %))) "non-empty string")
                resolved (resolve-model provider model-id)]
            (when-not resolved
              (throw (ex-info "unknown model"
                              {:error-code "request/unknown-model"})))
            (let [provider-str (name (:provider resolved))
                  model       {:provider provider-str
                               :id (:id resolved)
                               :reasoning (:supports-reasoning resolved)}
                  sd          (session/set-model-in! ctx model)]
              (response-frame (:id request) op true {:model {:provider (:provider (:model sd))
                                                             :id (:id (:model sd))}})))

          "cycle_model"
          (let [direction (case (:direction params)
                            "prev" :backward
                            "next" :forward
                            :backward :backward
                            :forward :forward
                            :forward)
                sd        (session/cycle-model-in! ctx direction)]
            (response-frame (:id request) op true {:model (some-> (:model sd)
                                                                  (select-keys [:provider :id]))}))

          "set_thinking_level"
          (let [level (req-arg! request params :level some? "keyword, string, or integer")
                level* (cond
                         (keyword? level) level
                         (string? level)  (keyword level)
                         :else            level)
                sd    (session/set-thinking-level-in! ctx level*)]
            (response-frame (:id request) op true {:thinking-level (:thinking-level sd)}))

          "cycle_thinking_level"
          (let [sd (session/cycle-thinking-level-in! ctx)]
            (response-frame (:id request) op true {:thinking-level (:thinking-level sd)}))

          "compact"
          (let [result (session/manual-compact-in! ctx (:custom-instructions params))]
            (response-frame (:id request) op true {:compacted (boolean result)
                                                   :summary   result}))

          "set_auto_compaction"
          (let [enabled (req-arg! request params :enabled boolean? "boolean")
                sd      (session/set-auto-compaction-in! ctx enabled)]
            (response-frame (:id request) op true {:enabled (:auto-compaction-enabled sd)}))

          "set_auto_retry"
          (let [enabled (req-arg! request params :enabled boolean? "boolean")
                sd      (session/set-auto-retry-in! ctx enabled)]
            (response-frame (:id request) op true {:enabled (:auto-retry-enabled sd)}))

          "get_state"
          (response-frame (:id request) op true {:state (session/get-session-data-in ctx)})

          "get_messages"
          (response-frame (:id request) op true {:messages (:messages (agent/get-data-in (:agent-ctx ctx)))})

          "get_session_stats"
          (response-frame (:id request) op true {:stats (session/diagnostics-in ctx)})

          "subscribe"
          (let [topics  (or (:topics params) [])
                _       (when-not (sequential? topics)
                          (throw (ex-info "subscribe :topics must be sequential"
                                          {:error-code "request/invalid-params"})))
                topics* (->> topics (filter #(contains? event-topics %)) set)]
            (swap! state update :subscribed-topics (fnil into #{}) topics*)
            (response-frame (:id request) op true {:subscribed (->> (:subscribed-topics @state) sort vec)}))

          "unsubscribe"
          (let [topics  (or (:topics params) [])
                _       (when-not (sequential? topics)
                          (throw (ex-info "unsubscribe :topics must be sequential"
                                          {:error-code "request/invalid-params"})))
                topics* (->> topics (filter string?) set)]
            (if (seq topics*)
              (swap! state update :subscribed-topics (fn [s] (apply disj (or s #{}) topics*)))
              (swap! state assoc :subscribed-topics #{}))
            (response-frame (:id request) op true {:subscribed (->> (:subscribed-topics @state) sort vec)}))

          "resolve_dialog"
          (handle-resolve-dialog! ctx request params)

          "cancel_dialog"
          (handle-cancel-dialog! ctx request params)

          (error-frame {:id            (:id request)
                        :op            op
                        :error-code    "request/op-not-supported"
                        :error-message (str "unsupported op: " op)
                        :data          {:supported-ops supported-rpc-ops}})))
      (catch Throwable e
        (exception->error-frame request e)))))

(defn run-stdio-loop!
  "Run an EDN-lines RPC loop.

   Options:
   - :in               java.io.Reader (default *in*)
   - :out              java.io.Writer (default *out*)
   - :err              java.io.Writer (default *err*)
   - :request-handler  (fn [request emit-frame! state] -> frame | [frame*] | nil)
   - :state            mutable transport state passed to request-handler

   State keys:
   - :ready?                   handshake readiness gate (default false)
   - :pending                  map of request-id -> op for in-flight requests
   - :max-pending-requests     guard limit (default 64)"
  [{:keys [in out err request-handler state]
    :or   {in *in*
           out *out*
           err *err*
           request-handler (fn [request _emit! _state]
                             (default-request-handler request))
           state (atom {})}}]
  (let [reader      (java.io.BufferedReader. in)
        emit-frame! (make-frame-writer out)]
    (swap! state #(merge {:ready? false
                          :pending {}
                          :max-pending-requests default-max-pending-requests
                          :subscribed-topics #{}
                          :event-seq 0
                          :inflight-futures []}
                         %
                         {:err err}))
    (let [emit-tracked! (make-tracked-emitter emit-frame! state)
          emit-error!   (fn [error-code error-message]
                          (emit-frame! (error-frame {:error-code error-code
                                                     :error-message error-message})))]
      (doseq [line (line-seq reader)]
        (if (str/blank? line)
          (emit-error! "transport/invalid-frame" "empty frame")
          (let [{:keys [ok error]} (parse-request-line line)]
            (if error
              (emit-frame! error)
              (process-request! ok {:state           state
                                    :request-handler request-handler
                                    :emit-tracked!   emit-tracked!}))))))))