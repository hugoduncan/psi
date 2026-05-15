(ns psi.agent-session.reload-namespace-mode-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.psi-tool :as psi-tool]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tools :as tools]))

(defn- delete-tree! [path]
  (when path
    (doseq [f (reverse (file-seq (io/file path)))]
      (.delete ^java.io.File f))))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest reload-code-namespace-mode-loads-unloaded-namespaces-test
  (testing "namespace mode accepts unloaded namespaces when they resolve under the target worktree"
    (let [tool      (tools/make-psi-tool (fn [_q] {}) {:cwd (System/getProperty "user.dir")})
          captured* (atom nil)
          result    (with-redefs [psi-tool/target-source-path-for-ns (fn [worktree-path ns-name]
                                                                       (str worktree-path "/src/" (str/replace ns-name "." "/") ".clj"))
                                  clojure.core/load-file (fn [path]
                                                           (reset! captured* path)
                                                           :loaded)]
                      ((:execute tool) {"action" "reload-code"
                                        "namespaces" ["psi.no.such.ns"]}))
          parsed    (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :reload-code (:psi-tool/action parsed)))
      (is (= :ok (get-in parsed [:psi-tool/code-reload :status])))
      (is (= ["psi.no.such.ns"] (get-in parsed [:psi-tool/code-reload :namespaces])))
      (is (= (str (System/getProperty "user.dir") "/src/psi/no/such/ns.clj") @captured*)))))

(deftest reload-code-namespace-mode-warning-behavior-test
  (testing "namespace mode reloads from target worktree and reports mismatch as warning"
    (let [captured* (atom nil)
          tmpdir    (str (java.nio.file.Files/createTempDirectory "psi-tool-reload-outside-worktree-"
                                                                  (make-array java.nio.file.attribute.FileAttribute 0)))]
      (try
        (let [src-dir     (io/file tmpdir "src/clojure")
              src-file    (io/file src-dir "string.clj")
              loaded-file (str (System/getProperty "user.dir") "/src/outside.clj")]
          (.mkdirs src-dir)
          (spit src-file "(ns clojure.string)")
          (with-redefs [psi-tool/canonical-source-path-for-ns (fn [_] loaded-file)
                        clojure.core/load-file (fn [path]
                                                 (reset! captured* path)
                                                 :loaded)]
            (let [tool   (tools/make-psi-tool (fn [_q] {}) {:cwd tmpdir})
                  result ((:execute tool) {"action" "reload-code"
                                           "namespaces" ["clojure.string"]})
                  parsed (read-string (:content result))]
              (is (false? (:is-error result)))
              (is (= (.getAbsolutePath (.getCanonicalFile src-file)) @captured*))
              (is (= :ok (:psi-tool/overall-status parsed)))
              (is (= (.getAbsolutePath (.getCanonicalFile (io/file tmpdir)))
                     (:psi-tool/worktree-path parsed)))
              (is (= :session (:psi-tool/worktree-source parsed)))
              (is (= [{:type :warning
                       :namespace "clojure.string"
                       :message "Reload namespace source path differs from target worktree source: clojure.string"
                       :loaded-source-path loaded-file
                       :target-source-path (.getAbsolutePath (.getCanonicalFile src-file))}]
                     (get-in parsed [:psi-tool/code-reload :warnings]))))))
        (finally
          (delete-tree! tmpdir)))))

  (testing "namespace mode loads previously unloaded namespaces with target-source warning when current runtime already knows a different loaded source"
    (let [captured* (atom nil)
          tmpdir    (str (java.nio.file.Files/createTempDirectory "psi-tool-reload-unloaded-"
                                                                  (make-array java.nio.file.attribute.FileAttribute 0)))]
      (try
        (let [src-dir  (io/file tmpdir "src/psi/app_runtime")
              src-file (io/file src-dir "retry_display.clj")]
          (.mkdirs src-dir)
          (spit src-file "(ns psi.app-runtime.retry-display)")
          (with-redefs [clojure.core/load-file (fn [path]
                                                 (reset! captured* path)
                                                 :loaded)]
            (let [tool   (tools/make-psi-tool (fn [_q] {}) {:cwd tmpdir})
                  result ((:execute tool) {"action" "reload-code"
                                           "namespaces" ["psi.app-runtime.retry-display"]})
                  parsed (read-string (:content result))]
              (is (false? (:is-error result)))
              (is (= (.getAbsolutePath (.getCanonicalFile src-file)) @captured*))
              (is (= [{:type :warning
                       :namespace "psi.app-runtime.retry-display"
                       :message "Reload namespace source path differs from target worktree source: psi.app-runtime.retry-display"
                       :loaded-source-path (str (System/getProperty "user.dir") "/components/app-runtime/src/psi/app_runtime/retry_display.clj")
                       :target-source-path (.getAbsolutePath (.getCanonicalFile src-file))}]
                     (get-in parsed [:psi-tool/code-reload :warnings]))))))
        (finally
          (delete-tree! tmpdir))))))

(deftest reload-code-worktree-mode-session-target-test
  (testing "worktree mode uses session worktree-path when explicit target absent"
    (with-redefs [psi-tool/worktree-reload-candidates (fn [worktree-path]
                                                        [{:ns-name "clojure.string"
                                                          :loaded-source-path (str worktree-path "/loaded/clojure/string.clj")
                                                          :target-source-path (str worktree-path "/components/agent-session/src/psi/agent_session/tools.clj")
                                                          :warning nil}])]
      (let [[ctx session-id] (create-session-context {:persist? false
                                                      :session-defaults {:worktree-path (System/getProperty "user.dir")}})
            tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id :cwd (System/getProperty "user.dir")})
            result           (with-redefs [clojure.core/load-file (fn [_] :loaded)]
                               ((:execute tool) {"action" "reload-code"}))
            parsed           (read-string (:content result))]
        (is (= :worktree (:psi-tool/reload-mode parsed)))
        (is (= :session (:psi-tool/worktree-source parsed)))
        (is (string? (:psi-tool/worktree-path parsed)))
        (is (= :ok (get-in parsed [:psi-tool/code-reload :status])))
        (is (contains? parsed :psi-tool/graph-refresh))
        (is (contains? (get-in parsed [:psi-tool/graph-refresh :steps 5]) :install))))))
