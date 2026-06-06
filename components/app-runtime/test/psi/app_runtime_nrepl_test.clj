(ns psi.app-runtime-nrepl-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.app-runtime :as app-runtime]
   [psi.app-runtime.nrepl-runtime :as app-nrepl]
   [psi.session-state.state :as ss]))

(defn- preserving-nrepl-port-file
  "Run `thunk` with the real-cwd `.nrepl-port` file snapshotted and restored.

   `start-nrepl!` writes `.nrepl-port` via a RELATIVE `java.io.File`, which
   resolves against the process working directory captured at FileSystem init,
   NOT a runtime-mutated `user.dir` property. So these characterization tests
   cannot isolate the file by rebinding `user.dir`; they observe the real cwd
   file and restore any pre-existing one (e.g. a live dev nREPL port file)."
  [thunk]
  (let [f        (java.io.File. ".nrepl-port")
        existed? (.exists f)
        saved    (when existed? (slurp f))]
    (try
      (thunk)
      (finally
        (if existed?
          (spit f saved)
          (when (.exists f) (.delete f)))))))

(deftest nrepl-runtime-eql-reflects-live-start-stop-test
  (let [orig-runtime @app-runtime/nrepl-runtime
        orig-user-dir (System/getProperty "user.dir")
        tmp-dir-file (java.io.File.
                      (str (System/getProperty "java.io.tmpdir")
                           "/psi-main-nrepl-runtime-"
                           (java.util.UUID/randomUUID)))
        _ (.mkdirs tmp-dir-file)
        tmp-dir (.getAbsolutePath tmp-dir-file)]
    (try
      (System/setProperty "user.dir" tmp-dir)
      (let [srv (#'app-runtime/start-nrepl! 0)
            cwd (System/getProperty "user.dir")]
        (try
          (is (pos-int? (:port srv)))
          (let [ctx    (session/create-context {:persist? false
                                                :cwd cwd
                                                :nrepl-runtime-atom app-runtime/nrepl-runtime})
                _      (session/new-session-in! ctx nil {})
                result (session/query-in ctx [:psi.runtime/nrepl-host
                                              :psi.runtime/nrepl-port
                                              :psi.runtime/nrepl-endpoint])
                expected-endpoint (str (:psi.runtime/nrepl-host result)
                                       ":"
                                       (:psi.runtime/nrepl-port result))]
            (is (= "localhost" (:psi.runtime/nrepl-host result)))
            (is (integer? (:psi.runtime/nrepl-port result)))
            (is (= (:port srv) (:psi.runtime/nrepl-port result)))
            (is (= expected-endpoint (:psi.runtime/nrepl-endpoint result))))
          (finally
            (#'app-runtime/stop-nrepl! srv))))
      (let [ctx-after-stop    (session/create-context {:persist? false
                                                       :cwd (System/getProperty "user.dir")
                                                       :nrepl-runtime-atom app-runtime/nrepl-runtime})
            _                 (session/new-session-in! ctx-after-stop nil {})
            result-after-stop (session/query-in ctx-after-stop
                                                [:psi.runtime/nrepl-host
                                                 :psi.runtime/nrepl-port
                                                 :psi.runtime/nrepl-endpoint])]
        (is (nil? (:psi.runtime/nrepl-host result-after-stop)))
        (is (nil? (:psi.runtime/nrepl-port result-after-stop)))
        (is (nil? (:psi.runtime/nrepl-endpoint result-after-stop))))
      (finally
        (System/setProperty "user.dir" orig-user-dir)
        (reset! app-runtime/nrepl-runtime orig-runtime)))))

(deftest route-stdout-to-stderr-redirects-both-stdout-channels-test
  ;; Characterizes the stdout-suppression seam directly with a REAL
  ;; known-printing thunk — no infra stub. This is the routing mechanism nREPL
  ;; startup chatter flows through inside `start-server-quietly`; testing it on a
  ;; thunk we control gives deterministic dual-channel output (`*out*` via
  ;; `println` + `System/out` via Java interop) without stubbing nREPL. Both
  ;; channels must land on stderr while the thunk runs, `System/out` must be
  ;; restored afterwards, and the thunk's value passes through unchanged.
  (let [out           (java.io.StringWriter.)
        err           (java.io.StringWriter.)
        orig-out      System/out
        orig-err      System/err
        out-bytes     (java.io.ByteArrayOutputStream.)
        err-bytes     (java.io.ByteArrayOutputStream.)
        installed-out (java.io.PrintStream. out-bytes true)]
    (try
      (System/setOut installed-out)
      (System/setErr (java.io.PrintStream. err-bytes true))
      (let [result (binding [*out* out
                             *err* err]
                     (app-nrepl/route-stdout-to-stderr
                      (fn []
                        (println "chatter via out-writer")
                        (.println System/out "chatter via system-out")
                        :handle)))]
        ;; thunk value passes through
        (is (= :handle result))
        ;; `*out*` (println) was routed to `*err*`, not the bound *out* writer
        (is (not (str/includes? (str out) "chatter via out-writer")))
        (is (str/includes? (str err) "chatter via out-writer"))
        ;; `System/out` interop was routed to System/err, not the installed stdout
        (is (not (str/includes? (str out-bytes) "chatter via system-out")))
        (is (str/includes? (str err-bytes) "chatter via system-out"))
        ;; `System/out` restored to its pre-call value by the seam's finally
        (is (identical? installed-out System/out)
            "seam restores System/out"))
      (finally
        (System/setOut orig-out)
        (System/setErr orig-err)))))

(deftest nrepl-port-file-records-bound-port-and-stop-deletes-on-match-test
  ;; Characterizes the `.nrepl-port` side effect (gap 1) + the matching-port
  ;; deletion branch of `stop-nrepl!` (gap 2). `start-nrepl!` writes the bound
  ;; (random) port; `stop-nrepl!` removes the file when its contents match.
  ;; `deleteOnExit` registration is a JVM-exit interaction, not externally
  ;; observable mid-test, so it is intentionally not asserted.
  (preserving-nrepl-port-file
   (fn []
     (let [orig-runtime @app-runtime/nrepl-runtime]
       (try
         (let [srv       (#'app-runtime/start-nrepl! 0)
               port-file (java.io.File. ".nrepl-port")]
           (is (pos-int? (:port srv)))
           (is (.exists port-file))
           (is (= (str (:port srv)) (str/trim (slurp port-file)))
               "writes exactly the bound port to .nrepl-port")
           (#'app-runtime/stop-nrepl! srv)
           (is (not (.exists port-file))
               "matching-port stop deletes .nrepl-port"))
         (finally
           (reset! app-runtime/nrepl-runtime orig-runtime)))))))

(deftest stop-nrepl-preserves-nrepl-port-file-when-contents-differ-test
  ;; Characterizes the non-matching branch of `stop-nrepl!` deletion (gap 2):
  ;; when `.nrepl-port` does not contain the running server's port, the file is
  ;; left intact.
  (preserving-nrepl-port-file
   (fn []
     (let [orig-runtime @app-runtime/nrepl-runtime]
       (try
         (let [srv       (#'app-runtime/start-nrepl! 0)
               port-file (java.io.File. ".nrepl-port")]
           ;; port 0 is never a bound port, so contents never match the server
           (spit port-file "0")
           (#'app-runtime/stop-nrepl! srv)
           (is (.exists port-file)
               "non-matching .nrepl-port is preserved across stop")
           (is (= "0" (str/trim (slurp port-file)))
               "preserved file contents are unchanged"))
         (finally
           (reset! app-runtime/nrepl-runtime orig-runtime)))))))

(deftest start-nrepl-publishes-bound-port-into-session-runtime-test
  ;; Characterizes the gated session publication branch (gap 3): with a ctx and
  ;; an active session id present, `start-nrepl!` publishes the BOUND (random)
  ;; port into session `:nrepl-runtime` via `accessors/set-nrepl-runtime-in!`.
  ;; The context is created while the nrepl-runtime-atom is nil, so the seeded
  ;; `[:runtime :nrepl]` value is nil; a non-nil EQL result can only come from
  ;; the publication branch.
  (preserving-nrepl-port-file
   (fn []
     (let [nrepl-atom         (atom nil)
           ctx                (session/create-context
                               {:persist?           false
                                :cwd                (System/getProperty "user.dir")
                                :nrepl-runtime-atom nrepl-atom})
           _                  (session/new-session-in! ctx nil {})
           session-state-atom (atom {:ctx ctx})
           default-fn         (fn [c] (-> (ss/list-context-sessions-in c) first :session-id))
           srv                (app-nrepl/start-nrepl! session-state-atom nrepl-atom default-fn 0)]
       (try
         (let [result (session/query-in ctx [:psi.runtime/nrepl-host
                                             :psi.runtime/nrepl-port
                                             :psi.runtime/nrepl-endpoint])]
           (is (pos-int? (:port srv)))
           (is (= "localhost" (:psi.runtime/nrepl-host result)))
           (is (= (:port srv) (:psi.runtime/nrepl-port result))
               "publishes the bound (random) port, not the requested 0")
           (is (= (str "localhost:" (:port srv)) (:psi.runtime/nrepl-endpoint result))))
         (finally
           (app-nrepl/stop-nrepl! session-state-atom nrepl-atom default-fn srv)))))))

(deftest start-nrepl-skips-session-publication-without-active-session-test
  ;; Characterizes the negative path of the publication gate (gap 3): with a ctx
  ;; present but no active session id, the inner `when-let` short-circuits and no
  ;; session `:nrepl-runtime` value is published.
  (preserving-nrepl-port-file
   (fn []
     (let [nrepl-atom         (atom nil)
           ctx                (session/create-context
                               {:persist?           false
                                :cwd                (System/getProperty "user.dir")
                                :nrepl-runtime-atom nrepl-atom})
           session-state-atom (atom {:ctx ctx})
           default-fn         (constantly nil)
           srv                (app-nrepl/start-nrepl! session-state-atom nrepl-atom default-fn 0)]
       (try
         (let [result (session/query-in ctx [:psi.runtime/nrepl-host
                                             :psi.runtime/nrepl-port
                                             :psi.runtime/nrepl-endpoint])]
           (is (pos-int? (:port srv)))
           (is (nil? (:psi.runtime/nrepl-host result)))
           (is (nil? (:psi.runtime/nrepl-port result)))
           (is (nil? (:psi.runtime/nrepl-endpoint result))))
         (finally
           (app-nrepl/stop-nrepl! session-state-atom nrepl-atom default-fn srv)))))))

(deftest start-nrepl-emits-connection-notice-to-stderr-not-stdout-test
  ;; Characterizes the connection-notice boundary output (gap 4): the literal
  ;; "  nREPL : host:port (connect with your editor)" is emitted to stderr (real
  ;; System/err), not stdout. Uses real captured streams (preferred over
  ;; with-redefs) since the notice is written via `(.println System/err ...)`.
  (preserving-nrepl-port-file
   (fn []
     (let [orig-runtime @app-runtime/nrepl-runtime
           orig-out     System/out
           orig-err     System/err
           out-bytes    (java.io.ByteArrayOutputStream.)
           err-bytes    (java.io.ByteArrayOutputStream.)]
       (try
         (System/setOut (java.io.PrintStream. out-bytes true))
         (System/setErr (java.io.PrintStream. err-bytes true))
         (let [srv (#'app-runtime/start-nrepl! 0)]
           (System/setOut orig-out)
           (System/setErr orig-err)
           (try
             (let [out-str (str out-bytes)
                   err-str (str err-bytes)
                   notice  (str "  nREPL : localhost:" (:port srv)
                                " (connect with your editor)")]
               (is (str/includes? err-str notice)
                   "connection notice is emitted to stderr")
               (is (not (str/includes? out-str notice))
                   "connection notice does not leak to stdout"))
             (finally
               (#'app-runtime/stop-nrepl! srv))))
         (finally
           (System/setOut orig-out)
           (System/setErr orig-err)
           (reset! app-runtime/nrepl-runtime orig-runtime)))))))
