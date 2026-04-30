(ns psi.rpc-smoke-test
  "Subprocess smoke test: launch psi --rpc-edn, perform a handshake, assert
   a valid server-info response, then close stdin and wait for clean exit.

   Uses the same launcher resolution strategy as the TUI tmux harness:
   prefer the installed `psi` binary, fall back to `bb bb/psi.clj`."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.util.concurrent TimeUnit)))

;; ── launcher resolution ──────────────────────────────────────────────────────

(defn- command-available? [cmd]
  (zero? (:exit (shell/sh "bash" "-lc" (str "command -v " cmd " >/dev/null 2>&1")))))

(defn- launcher-command
  "Return [program arg…] for launching the RPC backend.
   Mirrors the strategy used by the TUI tmux harness."
  []
  (cond
    (command-available? "psi")
    ["psi" "--rpc-edn"]

    (command-available? "bb")
    ["bb" "bb/psi.clj" "--" "--rpc-edn"]

    :else
    ["psi" "--rpc-edn"]))

;; ── subprocess helpers ───────────────────────────────────────────────────────

(def ^:private default-handshake-timeout-ms 60000)
(def ^:private default-exit-timeout-ms 10000)

(defn- read-line-timeout
  "Read one line from `reader` within `timeout-ms`.
   Returns the line string, or nil on timeout/EOF."
  [^java.io.BufferedReader reader timeout-ms]
  (let [f (future (.readLine reader))]
    (try
      (deref f timeout-ms nil)
      (finally
        (future-cancel f)))))

(defn- write-line!
  [^java.io.BufferedWriter writer s]
  (.write writer (str s "\n"))
  (.flush writer))

;; ── smoke scenario ───────────────────────────────────────────────────────────

(defn run-rpc-handshake-scenario!
  "Launch psi --rpc-edn as a subprocess, send a handshake request, assert a
   valid handshake response, close stdin, and wait for the process to exit.

   Returns a result map:
   - {:status :passed}
   - {:status :failed :reason keyword :detail string}"
  ([] (run-rpc-handshake-scenario! {}))
  ([{:keys [handshake-timeout-ms exit-timeout-ms working-dir]
     :or   {handshake-timeout-ms default-handshake-timeout-ms
            exit-timeout-ms     default-exit-timeout-ms
            working-dir         (str (.getCanonicalPath (io/file ".")))}}]
   (let [cmd (launcher-command)
         pb  (doto (ProcessBuilder. ^java.util.List cmd)
               (.directory (io/file working-dir))
               (.redirectErrorStream false))
         proc (.start pb)]
     (try
       (let [stdin  (io/writer (.getOutputStream proc))
             stdout (io/reader (.getInputStream proc))]
         ;; Send handshake request
         (write-line! stdin
                      (pr-str {:id   "smoke-1"
                               :kind :request
                               :op   "handshake"
                               :params {:client-info {:protocol-version "1.0"}}}))
         ;; Read response with timeout
         (let [line (read-line-timeout stdout handshake-timeout-ms)]
           (if (nil? line)
             {:status :failed
              :reason :handshake-timeout
              :detail (str "no response within " handshake-timeout-ms "ms")}
             (let [frame (try (edn/read-string line)
                              (catch Exception e
                                {:parse-error (ex-message e) :raw line}))]
               (cond
                 (:parse-error frame)
                 {:status :failed
                  :reason :parse-error
                  :detail (str "could not parse response: " (:raw frame))}

                 (not= "smoke-1" (:id frame))
                 {:status :failed
                  :reason :id-mismatch
                  :detail (str "expected id \"smoke-1\", got " (pr-str (:id frame)))}

                 (not= "handshake" (:op frame))
                 {:status :failed
                  :reason :op-mismatch
                  :detail (str "expected op \"handshake\", got " (pr-str (:op frame)))}

                 (not (:ok frame))
                 {:status :failed
                  :reason :handshake-rejected
                  :detail (str "handshake not ok: " (pr-str frame))}

                 (not (map? (get-in frame [:data :server-info])))
                 {:status :failed
                  :reason :missing-server-info
                  :detail (str "no :data :server-info in response: " (pr-str frame))}

                 :else
                 (do
                   ;; Close stdin — process should exit cleanly
                   (.close stdin)
                   (let [exited? (.waitFor proc exit-timeout-ms TimeUnit/MILLISECONDS)]
                     (if exited?
                       {:status :passed}
                       {:status :failed
                        :reason :exit-timeout
                        :detail (str "process did not exit within " exit-timeout-ms "ms after stdin close")}))))))))
       (finally
         (when (.isAlive proc)
           (.destroyForcibly proc)))))))

;; ── test ─────────────────────────────────────────────────────────────────────

(deftest ^:integration rpc-smoke-handshake-test
  (testing "psi --rpc-edn starts, responds to handshake with server-info, exits on stdin close"
    (let [result (run-rpc-handshake-scenario! {})]
      (is (= :passed (:status result))
          (str "RPC smoke test failed: " (pr-str (dissoc result :status)))))))
