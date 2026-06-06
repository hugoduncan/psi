(ns psi.app-runtime.nrepl-runtime
  (:require
   [clojure.string :as str]
   [psi.agent-session.state-accessors :as accessors]))

(defn active-session-id-in-session-state
  [session-state-atom default-session-id-fn]
  (let [{:keys [ctx tui-focus*]} @session-state-atom]
    (or (some-> tui-focus* deref)
        (default-session-id-fn ctx))))

(defn start-server-quietly
  "Start an nREPL server on `port`, suppressing the server's startup chatter from
  protocol stdout by routing it to stderr. Owns nREPL-start resolution and all
  stdout-suppression Java interop; returns the server map."
  [port]
  (let [start-server       (requiring-resolve 'nrepl.server/start-server)
        original-systemout System/out]
    (try
      (binding [*out* *err*]
        (System/setOut (java.io.PrintStream. System/err true))
        (start-server :port port))
      (finally
        (System/setOut original-systemout)))))

(defn start-nrepl!
  [session-state-atom nrepl-runtime-atom default-session-id-fn port]
  (let [server    (start-server-quietly port)
        host      "localhost"
        port-file (java.io.File. ".nrepl-port")]
    (reset! nrepl-runtime-atom {:host host
                                :port (:port server)
                                :endpoint (str host ":" (:port server))})
    (when-let [ctx (:ctx @session-state-atom)]
      (when-let [session-id (active-session-id-in-session-state session-state-atom default-session-id-fn)]
        (accessors/set-nrepl-runtime-in!
         ctx session-id
         {:host host
          :port (:port server)
          :endpoint (str host ":" (:port server))})))
    (spit port-file (str (:port server)))
    (.deleteOnExit port-file)
    (.println System/err (str "  nREPL : " host ":" (:port server)
                              " (connect with your editor)"))
    server))

(defn stop-nrepl!
  [session-state-atom nrepl-runtime-atom default-session-id-fn server]
  (when server
    (let [stop-server (requiring-resolve 'nrepl.server/stop-server)
          port-file   (java.io.File. ".nrepl-port")]
      (reset! nrepl-runtime-atom nil)
      (when-let [ctx (:ctx @session-state-atom)]
        (when-let [session-id (active-session-id-in-session-state session-state-atom default-session-id-fn)]
          (accessors/set-nrepl-runtime-in! ctx session-id nil)))
      (stop-server server)
      (when (.exists port-file)
        (when (= (str/trim (slurp port-file)) (str (:port server)))
          (.delete port-file))))))
