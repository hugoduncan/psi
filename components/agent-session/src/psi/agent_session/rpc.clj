(ns psi.agent-session.rpc
  "EDN-lines RPC transport runtime helpers.

   Transport guarantees in this namespace:
   - one top-level EDN map per input line
   - canonical outbound frame envelopes
   - serialized outbound frame writing
   - protocol-only stdout (handler diagnostics are rebound to stderr)"
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def protocol-version "1.0")

(def ^:private request-required-keys #{:id :kind :op})
(def ^:private request-allowed-keys  #{:id :kind :op :params})

(def ^:private response-allowed-keys [:id :kind :op :ok :data])
(def ^:private error-allowed-keys    [:kind :id :op :error-code :error-message :retryable :data])
(def ^:private event-allowed-keys    [:kind :event :data :id :seq :ts])

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

(defn event-frame
  [{:keys [event data id seq ts]}]
  (cond-> (array-map :kind :event :event event :data data)
    (some? id)  (assoc :id id)
    (some? seq) (assoc :seq seq)
    (some? ts)  (assoc :ts ts)))

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
      "handshake"
      (response-frame (:id request) "handshake" true
                      {:server-info {:protocol-version protocol-version}})

      "ping"
      (response-frame (:id request) "ping" true {:pong true :protocol-version protocol-version})

      (error-frame {:id            (:id request)
                    :op            (:op request)
                    :error-code    "request/op-not-supported"
                    :error-message (str "unsupported op: " op)}))))

(defn run-stdio-loop!
  "Run an EDN-lines RPC loop.

   Options:
   - :in               java.io.Reader (default *in*)
   - :out              java.io.Writer (default *out*)
   - :err              java.io.Writer (default *err*)
   - :request-handler  (fn [request emit-frame! state] -> frame | [frame*] | nil)
   - :state            mutable transport state passed to request-handler"
  [{:keys [in out err request-handler state]
    :or   {in *in*
           out *out*
           err *err*
           request-handler (fn [request _emit! _state]
                             (default-request-handler request))
           state (atom {})}}]
  (let [reader      (java.io.BufferedReader. in)
        emit-frame! (make-frame-writer out)
        emit-error! (fn [error-code error-message]
                      (emit-frame! (error-frame {:error-code error-code
                                                 :error-message error-message})))]
    (doseq [line (line-seq reader)]
      (if (str/blank? line)
        (emit-error! "transport/invalid-frame" "empty frame")
        (let [{:keys [ok error]} (parse-request-line line)]
          (if error
            (emit-frame! error)
            (try
              (let [result (binding [*out* err]
                             (request-handler ok emit-frame! state))]
                (cond
                  (nil? result)
                  nil

                  (map? result)
                  (emit-frame! result)

                  (sequential? result)
                  (doseq [frame result]
                    (emit-frame! frame))

                  :else
                  (emit-frame! (error-frame {:id            (:id ok)
                                             :op            (:op ok)
                                             :error-code    "runtime/failed"
                                             :error-message "request handler returned unsupported result type"}))))
              (catch Throwable t
                (emit-frame! (error-frame {:id            (:id ok)
                                           :op            (:op ok)
                                           :error-code    "runtime/failed"
                                           :error-message (or (ex-message t)
                                                              "unhandled runtime exception")}))))))))))
