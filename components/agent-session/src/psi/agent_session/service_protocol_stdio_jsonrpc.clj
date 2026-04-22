(ns psi.agent-session.service-protocol-stdio-jsonrpc
  "Runtime adapter for stdio JSON-RPC managed services.

   Provides framed JSON-RPC write + correlated response waiting and installs the
   resulting runtime fns onto an existing managed service registry entry. This
   keeps transport/runtime ownership in core while leaving protocol policy to
   extensions such as LSP."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.services :as services])
  (:import
   (java.io BufferedInputStream BufferedOutputStream ByteArrayOutputStream BufferedReader InputStreamReader)
   (java.nio.charset StandardCharsets)
   (java.util.concurrent ConcurrentHashMap LinkedBlockingQueue TimeUnit)))

(defn- utf8-bytes [s]
  (.getBytes ^String s StandardCharsets/UTF_8))

(defn- parse-header-line [line]
  (let [[k v] (str/split line #":\s*" 2)]
    [(some-> k str/lower-case) v]))

(defn- read-line-crlf [^BufferedInputStream in]
  (let [line-bytes (ByteArrayOutputStream.)]
    (loop [prev nil]
      (let [b (.read in)]
        (cond
          (= -1 b)
          (when (pos? (.size line-bytes))
            (.toString line-bytes "UTF-8"))

          (and (= prev 13) (= b 10))
          (.toString line-bytes "UTF-8")

          :else
          (do
            (when-not (= b 13)
              (.write line-bytes b))
            (recur b)))))))

(defn- read-headers [^BufferedInputStream in]
  (loop [headers {}]
    (let [line (read-line-crlf in)]
      (cond
        (nil? line)
        (when (seq headers) headers)

        (empty? line)
        headers

        :else
        (let [[k v] (parse-header-line line)]
          (recur (assoc headers k v)))))))

(defn- read-exact-bytes [^BufferedInputStream in n]
  (let [buf (byte-array n)]
    (loop [offset 0]
      (if (= offset n)
        buf
        (let [read-n (.read in buf offset (- n offset))]
          (when (= -1 read-n)
            (throw (ex-info "Unexpected EOF while reading JSON-RPC body"
                            {:expected n :offset offset})))
          (recur (+ offset read-n)))))))

(defn- read-jsonrpc-message! [^BufferedInputStream in]
  (when-let [headers (read-headers in)]
    (let [content-length (some-> (get headers "content-length") Long/parseLong)
          body-bytes     (read-exact-bytes in content-length)
          body-text      (String. body-bytes StandardCharsets/UTF_8)]
      {:headers headers
       :body-text body-text
       :payload (json/parse-string body-text)})))

(defn- write-jsonrpc-message! [^BufferedOutputStream out payload]
  (let [body  (json/generate-string payload)
        bytes (utf8-bytes body)
        head  (str "Content-Length: " (alength bytes) "\r\n\r\n")]
    (.write out (utf8-bytes head))
    (.write out bytes)
    (.flush out)))

(defn create-jsonrpc-runtime
  "Create a stdio JSON-RPC runtime adapter for a managed subprocess service.

   Returns runtime fns suitable for `services/set-service-runtime-fns-in!`:
   - :send-fn
   - :notify-fn
   - :await-response-fn

   The adapter starts a daemon reader thread that correlates inbound messages by
   JSON-RPC id and also records notifications for optional debugging hooks."
  [service & [{:keys [on-notification on-error]}]]
  (let [process             (:process service)
        in                  (BufferedInputStream. (io/input-stream (.getInputStream ^Process process)))
        err-reader          (BufferedReader. (InputStreamReader. (.getErrorStream ^Process process) StandardCharsets/UTF_8))
        out                 (BufferedOutputStream. (io/output-stream (.getOutputStream ^Process process)))
        pending             (ConcurrentHashMap.)
        notifications*      (atom [])
        published-diags*    (atom {})
        debug*              (atom [])
        stderr*             (atom [])
        running?            (atom true)
        stderr-thread  (doto
                        (Thread.
                         ^Runnable
                         (fn []
                           (try
                             (loop []
                               (when-let [line (.readLine err-reader)]
                                 (swap! stderr* conj line)
                                 (recur)))
                             (catch Throwable _))))
                         (.setDaemon true)
                         (.setName (str "psi-jsonrpc-stderr-" (:key service)))
                         (.start))
        reader-thread  (doto
                        (Thread.
                         ^Runnable
                         (fn []
                           (try
                             (while @running?
                               (when-let [{:keys [payload] :as msg} (read-jsonrpc-message! in)]
                                 (swap! debug* conj {:event :read :payload payload})
                                 (if-let [id (get payload "id")]
                                   (if-let [^LinkedBlockingQueue q (.get pending id)]
                                     (do
                                       (swap! debug* conj {:event :correlated-read :request-id id})
                                       (.offer q msg)
                                       (.remove pending id))
                                     (do
                                       (swap! debug* conj {:event :uncorrelated-read :request-id id})
                                       (swap! notifications* conj msg)))
                                   (do
                                     (swap! notifications* conj msg)
                                     (when (= "textDocument/publishDiagnostics"
                                              (get-in payload ["method"]))
                                       (let [uri         (get-in payload ["params" "uri"])
                                             diagnostics (vec (or (get-in payload ["params" "diagnostics"])
                                                                  []))]
                                         (when (string? uri)
                                           (swap! published-diags* assoc uri diagnostics))))
                                     (when on-notification
                                       (on-notification msg))))))
                             (catch Throwable t
                               (when on-error
                                 (on-error t))))))
                         (.setDaemon true)
                         (.setName (str "psi-jsonrpc-" (:key service)))
                         (.start))]
    {:send-fn
     (fn [payload]
       (swap! debug* conj {:event :send :payload payload})
       (write-jsonrpc-message! out payload))

     :notify-fn
     (fn [payload]
       (swap! debug* conj {:event :notify :payload payload})
       (write-jsonrpc-message! out payload))

     :await-response-fn
     (fn [{:keys [request-id timeout-ms payload send-fn send-already?]}]
       (let [q (LinkedBlockingQueue.)]
         (.put pending request-id q)
         (swap! debug* conj {:event :pending-register :request-id request-id})
         (when (and send-fn (not send-already?))
           (send-fn payload))
         (let [result (.poll q (long (or timeout-ms 500)) TimeUnit/MILLISECONDS)]
           (if result
             result
             (do
               (.remove pending request-id)
               (swap! debug* conj {:event :timeout :request-id request-id})
               {:payload {"error" {"message" "Timed out waiting for JSON-RPC response"
                                   "request-id" request-id}}
                :is-error true})))))

     :await-response-sends? false

     :close-fn
     (fn []
       (reset! running? false)
       (try (.interrupt ^Thread reader-thread) (catch Throwable _))
       (try (.interrupt ^Thread stderr-thread) (catch Throwable _))
       (try (.close in) (catch Throwable _))
       (try (.close err-reader) (catch Throwable _))
       (try (.close out) (catch Throwable _)))

     :notifications-atom notifications*
     :published-diagnostics-atom published-diags*
     :debug-atom debug*
     :stderr-atom stderr*}))

(defn attach-jsonrpc-runtime-in!
  "Install a stdio JSON-RPC runtime adapter onto an existing managed service."
  [ctx service-key & [opts]]
  (let [service (services/service-in ctx service-key)
        runtime (create-jsonrpc-runtime service opts)]
    (services/set-service-runtime-fns-in! ctx service-key runtime)))

(defn auto-attach-jsonrpc-runtime-in!
  "Attach stdio JSON-RPC runtime adapter when service spec opts in via
   `:protocol :json-rpc` or `:protocol :jsonrpc`."
  [ctx service-key]
  (let [service (services/service-in ctx service-key)
        protocol (or (:protocol service)
                     (get-in service [:spec :protocol]))]
    (when (#{:json-rpc :jsonrpc} protocol)
      (attach-jsonrpc-runtime-in! ctx service-key))))
