(ns psi.provider-auth.oauth.callback-server
  "Ephemeral local HTTP server for OAuth redirect callbacks.

   Nullable pattern: use `start-server` for production (real HTTP server
   on localhost), `create-null-server` for tests (no network, deliver
   results directly via `:deliver` fn).

   Both return the same map interface:
     :port           — bound port (0 for null)
     :wait-for-code  — (fn [timeout-ms]) → {:code \"...\" :state \"...\"} | nil
     :cancel         — (fn []) stop waiting
     :close          — (fn []) shut down
     :deliver        — (fn [result]) inject result (null server only)"
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;;; Shared helpers

(def ^:private success-html
  "<!doctype html>
<html><body>
<p>Authentication successful. Return to your terminal.</p>
</body></html>")

(defn- send-response
  "Write status + body to the exchange and close it."
  [^HttpExchange exchange ^long status ^String body]
  (let [bs (.getBytes body "UTF-8")]
    (.add (.getResponseHeaders exchange) "Content-Type" "text/html; charset=utf-8")
    (.sendResponseHeaders exchange status (alength bs))
    (with-open [out (.getResponseBody exchange)]
      (.write out bs))))

(defn- parse-query-params
  "Parse a query string into a map of string→string."
  [^String query]
  (when query
    (into {}
          (for [^String pair (.split query "&")
                :let  [[k v] (.split pair "=" 2)]
                :when k]
            [k (or v "")]))))

;;; Waiting loop (shared by both impls)

(defn- wait-loop
  "Block until `queue` has a result or `timeout-ms` expires.
   Checks `cancelled` volatile every 100ms."
  [^LinkedBlockingQueue queue cancelled timeout-ms]
  (loop [remaining timeout-ms]
    (when (and (pos? remaining) (not @cancelled))
      (let [result (.poll queue 100 TimeUnit/MILLISECONDS)]
        (if result
          result
          (recur (- remaining 100)))))))

;;; Production server

(defn start-server
  "Start a local HTTP server on `port` to receive an OAuth callback.

   Options:
     :port — port to bind (default 0 = OS-assigned)
     :path — callback path (default \"/oauth/callback\")

   Returns a server map (see namespace docstring)."
  ([] (start-server {}))
  ([{:keys [port path] :or {port 0 path "/oauth/callback"}}]
   (let [result-queue (LinkedBlockingQueue. 1)
         cancelled    (volatile! false)
         handler      (reify HttpHandler
                        (handle [_ exchange]
                          (let [uri    (.getRequestURI exchange)
                                params (parse-query-params (.getQuery uri))]
                            (if (= (.getPath uri) path)
                              (let [code  (get params "code")
                                    state (get params "state")
                                    error (get params "error")]
                                (if error
                                  (send-response exchange 400
                                                 (str "<p>Authentication failed: " error "</p>"))
                                  (do
                                    (send-response exchange 200 success-html)
                                    (when code
                                      (.offer result-queue {:code code :state state})))))
                              (send-response exchange 404 "Not found")))))
         server       (doto (HttpServer/create
                             (InetSocketAddress. "127.0.0.1" (int port)) 0)
                        (.createContext "/" handler)
                        (.start))
         bound-port   (.getPort (.getAddress server))]
     {:port          bound-port
      :wait-for-code (fn [timeout-ms] (wait-loop result-queue cancelled timeout-ms))
      :cancel        (fn [] (vreset! cancelled true))
      :close         (fn [] (.stop server 0))})))

;;; Null server (tests)

(defn create-null-server
  "Create a null callback server for testing.  No network, no port.

   Use `:deliver` to inject a result that `:wait-for-code` will return:
     (let [srv (create-null-server)]
       (future ((:deliver srv) {:code \"abc\" :state \"xyz\"}))
       ((:wait-for-code srv) 5000))
     ;; => {:code \"abc\" :state \"xyz\"}"
  []
  (let [result-queue (LinkedBlockingQueue. 1)
        cancelled    (volatile! false)]
    {:port          0
     :wait-for-code (fn [timeout-ms] (wait-loop result-queue cancelled timeout-ms))
     :cancel        (fn [] (vreset! cancelled true))
     :close         (fn [])
     :deliver       (fn [result] (.offer result-queue result))}))
