(ns psi.agent-session.extensions-io-test
  "Tests for extension loading, discovery, shortcuts, and EQL introspection."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.tool-registry.registry :as tool-registry]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]))

;; ── Extension loading from file ─────────────────────────────────────────────

(deftest load-extension-from-file-test
  (testing "loads a .clj extension file and invokes init"
    (let [tmp-dir  (io/file (System/getProperty "java.io.tmpdir")
                            (str "psi-ext-test-" (System/nanoTime)))
          _        (.mkdirs tmp-dir)
          ext-file (io/file tmp-dir "hello_ext.clj")
          reg      (ext/create-registry)]
      (try
        ;; Write a minimal extension file
        (spit ext-file
              "(ns psi.test-extensions.hello-ext)

(defn init [api]
  ((:register-command api) \"hello\" {:description \"Say hello\"
                                     :handler (fn [args] (println \"Hello\" args))})
  ((:register-flag api) \"verbose\" {:type :boolean :default true})
  ((:on api) \"session_switch\" (fn [ev] (println \"switched!\" ev))))")
        (let [result (ext/load-extension-in! reg (.getAbsolutePath ext-file) {})]
          (is (nil? (:error result)))
          (is (= (.getAbsolutePath ext-file) (:extension result)))
          ;; Verify registrations took effect
          (is (= 1 (ext/extension-count-in reg)))
          (is (contains? (ext/command-names-in reg) "hello"))
          (is (contains? (ext/flag-names-in reg) "verbose"))
          (is (= true (ext/get-flag-in reg "verbose")))
          (is (= 1 (ext/handler-count-in reg))))
        (finally
          (.delete ext-file)
          (.delete tmp-dir))))))

(deftest add-extension-runtime-keeps-registered-extension-visible-test
  (testing "add-extension-in! keeps successfully loaded extensions in registration order"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false
                                                              :cwd (test-support/temp-cwd)
                                                              :mutations mutations/all-mutations})
          reg              (:extension-registry ctx)
          tmp-dir          (io/file (System/getProperty "java.io.tmpdir")
                                    (str "psi-ext-runtime-" (System/nanoTime)))
          ext-file         (io/file tmp-dir "runtime_ext.clj")]
      (try
        (.mkdirs tmp-dir)
        (spit ext-file
              "(ns psi.test-extensions.runtime-ext)

(defn init [api]
  ((:register-tool api) {:name \"runtime-tool\"
                         :label \"Runtime Tool\"
                         :description \"Runtime-added test tool\"
                         :format-request (fn [_] \"runtime-tool\")
                         :parameters {:type \"object\"}}))")
        (let [result (ext-rt/add-extension-in! ctx session-id (.getAbsolutePath ext-file))]
          (is (= {:loaded? true
                  :path (.getAbsolutePath ext-file)
                  :error nil}
                 result))
          (is (= [(.getAbsolutePath ext-file)] (ext/extensions-in reg)))
          (is (= 1 (ext/extension-count-in reg))
              (pr-str @(:state reg)))
          (is (contains? (tool-registry/tool-names-in reg) "runtime-tool")
              (pr-str @(:state reg))))
        (finally
          (.delete ext-file)
          (.delete tmp-dir))))))

(deftest activate-manifest-extensions-refreshes-session-tool-defs-test
  (testing "manifest activation refreshes session tool defs so newly registered tools become active"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false
                                                              :cwd (test-support/temp-cwd)
                                                              :mutations mutations/all-mutations})
          reg (:extension-registry ctx)
          ns-sym 'psi.test-extensions.runtime-manifest-ext]
      (create-ns ns-sym)
      (binding [*ns* (the-ns ns-sym)]
        (clojure.core/refer 'clojure.core)
        (intern *ns* 'init
                (fn [api]
                  ((:register-tool api) {:name "manifest-runtime-tool"
                                         :label "Manifest Runtime Tool"
                                         :description "Tool from manifest activation"
                                         :format-request (fn [_] "manifest-runtime-tool")
                                         :parameters {:type "object"}}))))
      (let [result (ext-rt/activate-manifest-extensions-in!
                    ctx
                    session-id
                    {:local-activation-entries []
                     :entries-by-lib {'psi/test-manifest-ext {:dep {:mvn/version "1.0.0"
                                                                    :psi/init 'psi.test-extensions.runtime-manifest-ext/init}
                                                              :extension? true
                                                              :enabled? true}}}
                    {:deps-realized? true})
            tool-ids (set (:tool-ids (ss/get-session-data-in ctx session-id)))]
        (is (= ["manifest:psi/test-manifest-ext"] (:loaded result)))
        (is (contains? (tool-registry/tool-names-in reg) "manifest-runtime-tool"))
        (is (contains? tool-ids "manifest-runtime-tool"))))))

(deftest load-extension-missing-file-test
  (testing "returns error for missing file"
    (let [reg    (ext/create-registry)
          result (ext/load-extension-in! reg "/nonexistent/ext.clj" {})]
      (is (some? (:error result)))
      (is (nil? (:extension result))))))

(deftest failed-extension-activation-rolls-back-live-registry-test
  (testing "file-backed activation rollback removes failed extension from live registry"
    (let [tmp-dir  (io/file (System/getProperty "java.io.tmpdir")
                            (str "psi-ext-fail-file-" (System/nanoTime)))
          ext-file (io/file tmp-dir "failing_ext.clj")
          reg      (ext/create-registry)]
      (try
        (.mkdirs tmp-dir)
        (spit ext-file
              "(ns psi.test-extensions.failing-ext)\n\n(defn init [_]\n  (throw (ex-info \"boom file init\" {})))\n")
        (let [result (ext/load-extension-in! reg (.getAbsolutePath ext-file) {})]
          (is (some? (:error result)))
          (is (nil? (:extension result)))
          (is (= [] (ext/extensions-in reg)))
          (is (= 0 (ext/extension-count-in reg))))
        (finally
          (.delete ext-file)
          (.delete tmp-dir)))))

  (testing "init-var activation rollback removes failed manifest identity from live registry"
    (let [ns-sym 'psi.test-extensions.failing-init-var
          _      (create-ns ns-sym)
          _      (binding [*ns* (the-ns ns-sym)]
                   (clojure.core/refer 'clojure.core)
                   (intern *ns* 'init (fn [_] (throw (ex-info "boom init-var init" {})))))
          reg    (ext/create-registry)
          ext-id "manifest:psi/test-failing-init-var"
          result (ext/load-init-var-extension-in! reg ext-id 'psi.test-extensions.failing-init-var/init {})]
      (is (some? (:error result)))
      (is (nil? (:extension result)))
      (is (= [] (ext/extensions-in reg)))
      (is (= 0 (ext/extension-count-in reg))))))

;; ── Extension discovery ─────────────────────────────────────────────────────

(deftest discover-extension-paths-test
  (testing "discovers .clj files in a directory"
    (let [tmp-dir  (io/file (System/getProperty "java.io.tmpdir")
                            (str "psi-discover-" (System/nanoTime)))
          ext-dir  (io/file tmp-dir ".psi" "extensions")
          ext-file (io/file ext-dir "my_ext.clj")]
      (try
        (.mkdirs ext-dir)
        (spit ext-file "(ns test-ext)")
        (let [paths (ext/discover-extension-paths [] (.getAbsolutePath tmp-dir))]
          (is (some #(str/ends-with? % "my_ext.clj") paths)))
        (finally
          (.delete ext-file)
          (.delete ext-dir)
          (.delete (io/file tmp-dir ".psi"))
          (.delete tmp-dir)))))

  (testing "discovers extension.clj in subdirectories"
    (let [tmp-dir  (io/file (System/getProperty "java.io.tmpdir")
                            (str "psi-discover-sub-" (System/nanoTime)))
          ext-dir  (io/file tmp-dir ".psi" "extensions" "my-ext")
          ext-file (io/file ext-dir "extension.clj")]
      (try
        (.mkdirs ext-dir)
        (spit ext-file "(ns test-ext-sub)")
        (let [paths (ext/discover-extension-paths [] (.getAbsolutePath tmp-dir))]
          (is (some #(str/ends-with? % "extension.clj") paths)))
        (finally
          (.delete ext-file)
          (.delete ext-dir)
          (.delete (io/file tmp-dir ".psi" "extensions"))
          (.delete (io/file tmp-dir ".psi"))
          (.delete tmp-dir)))))

  (testing "explicit path included"
    (let [tmp-file (io/file (System/getProperty "java.io.tmpdir")
                            (str "explicit-ext-" (System/nanoTime) ".clj"))]
      (try
        (spit tmp-file "(ns explicit)")
        (let [paths (ext/discover-extension-paths [(.getAbsolutePath tmp-file)]
                                                  "/nonexistent")]
          (is (some #(= (.getAbsolutePath tmp-file) %) paths)))
        (finally
          (.delete tmp-file))))))

;; ── Shortcut registration ───────────────────────────────────────────────────

(deftest shortcut-registration-test
  (testing "register-shortcut-in! adds shortcut"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-shortcut-in! reg "/ext/a" {:key "ctrl+k" :handler (fn [] nil)})
      (let [d (ext/extension-detail-in reg "/ext/a")]
        (is (= 1 (:shortcut-count d)))))))

;; ── EQL introspection ───────────────────────────────────────────────────────

(deftest eql-extension-introspection-test
  (testing "resolvers expose extension data via EQL"
    (let [;; Need a full session context for resolver tests
          reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-handler-in! reg "/ext/a" "tool_call" (fn [_] nil))
      (ext/register-tool-in! reg "/ext/a" {:name "ext-read" :label "R" :description "ext read" :format-request (fn [_] "ext-read")})
      (ext/register-command-in! reg "/ext/a" {:name "greet" :description "Say hi"})
      (ext/register-flag-in! reg "/ext/a" {:name "debug" :type :boolean :default false})
      ;; Test the inspection functions that resolvers will call
      (is (= ["/ext/a"] (vec (ext/extensions-in reg))))
      (is (= 1 (ext/handler-count-in reg)))
      (is (= ["tool_call"] (vec (ext/handler-event-names-in reg))))
      (is (= 1 (count (ext/all-commands-in reg))))
      (is (= 1 (count (ext/all-flags-in reg))))
      (is (= false (:current-value (first (ext/all-flags-in reg)))))
      (is (= 1 (count (ext/extension-details-in reg)))))))
