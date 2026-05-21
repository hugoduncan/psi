(ns psi.app-runtime-nrepl-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.app-runtime :as app-runtime]))

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

(deftest start-nrepl-redirects-startup-chatter-to-stderr-test
  (let [orig-runtime @app-runtime/nrepl-runtime
        out          (java.io.StringWriter.)
        err          (java.io.StringWriter.)]
    (try
      (with-redefs [requiring-resolve (fn [sym]
                                        (case sym
                                          nrepl.server/start-server
                                          (fn [& {:keys [port]}]
                                            (println "nREPL server started on port" (or port 0))
                                            (.println System/out (str "system-out port " (or port 0)))
                                            {:port (or port 5555)})
                                          nrepl.server/stop-server
                                          (fn [_] nil)))]
        (binding [*out* out
                  *err* err]
          (let [srv (#'app-runtime/start-nrepl! 5555)]
            (is (= 5555 (:port srv)))
            (is (not (str/includes? (str out) "nREPL server started on port")))
            (is (not (str/includes? (str out) "system-out port 5555")))
            (is (str/includes? (str err) "nREPL server started on port"))
            (#'app-runtime/stop-nrepl! srv))))
      (finally
        (reset! app-runtime/nrepl-runtime orig-runtime)))))
