(ns psi.extension-install-startup-test
  (:require
   [clojure.java.io :as io]
   [clojure.repl.deps]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.extension-installs :as installs]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.system-prompt :as sys-prompt]
   [psi.agent-session.test-support :as test-support]
   [psi.ai.model-registry :as model-registry]
   [psi.app-runtime :as app-runtime]))

(defn- manifest-file [root rel]
  (let [f (io/file root rel)]
    (.mkdirs (.getParentFile f))
    f))

(defn- write-local-extension! [root ns-sym body]
  (let [src-dir  (io/file root "src")
        rel-path (str (-> (str ns-sym)
                          (str/replace "." "/")
                          (str/replace "-" "_"))
                      ".clj")
        f        (io/file src-dir rel-path)]
    (.mkdirs (.getParentFile f))
    (spit (io/file root "deps.edn") (pr-str {:paths ["src"]}))
    (spit f body)
    f))

(defn- define-runtime-extension-ns! [ns-sym cmd-name]
  (create-ns ns-sym)
  (binding [*ns* (the-ns ns-sym)]
    (clojure.core/refer 'clojure.core)
    (intern *ns* 'init
            (fn [api]
              ((:register-command api) cmd-name
                                       {:description (str "hello from " cmd-name)
                                        :handler (fn [_] nil)})))))

(defn- startup-bootstrap-bindings [cwd home]
  {#'installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
   #'installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))
   #'oauth/create-context (fn [] nil)
   #'pt/discover-templates (fn [] [])
   #'skills/discover-skills (fn [] {:skills [] :diagnostics []})
   #'sys-prompt/discover-context-files (fn [_] [])
   #'sys-prompt/build-system-prompt (fn [_] "")})

(defn- bootstrap-runtime-session-for-test
  [cwd home]
  (with-redefs-fn (startup-bootstrap-bindings cwd home)
    (fn []
      (let [result (app-runtime/bootstrap-runtime-session! {:provider :anthropic :id "claude-sonnet-4-6" :supports-reasoning true} {:cwd cwd})]
        (model-registry/init! {:user-models-path nil :project-models-path nil})
        result))))

(defn- startup-registry-paths [ctx]
  (set (map str (ext/extensions-in (:extension-registry ctx)))))

(defn- startup-entry-status [ctx lib]
  (get-in (installs/extension-installs-state-in ctx)
          [:psi.extensions/effective :entries-by-lib lib :status]))

(defn- bootstrap-with-manifest
  [manifest {:keys [cwd home sync-deps]}]
  (let [cwd  (or cwd (test-support/temp-cwd))
        home (or home (test-support/temp-cwd))
        bootstrap! #(do
                      (spit (manifest-file cwd ".psi/extensions.edn") (pr-str manifest))
                      (bootstrap-runtime-session-for-test cwd home))]
    {:cwd cwd
     :home home
     :result (if sync-deps
               (with-redefs [clojure.repl.deps/sync-deps sync-deps]
                 (bootstrap!))
               (bootstrap!))}))

(deftest startup-persists-install-state-and-loads-manifest-extension-paths-test
  (testing "bootstrap startup computes install state and loads manifest-backed local-root extensions"
    (let [ext-root (test-support/temp-cwd)
          _        (write-local-extension! ext-root
                                           'psi.test.startup-ext
                                           "(ns psi.test.startup-ext)\n\n(defn init [api]\n  ((:register-command api) \"startup-hello\" {:description \"hello from startup manifest\" :handler (fn [_] nil)}))\n")
          {:keys [result]} (bootstrap-with-manifest
                            {:deps {'psi/test-startup-ext {:local/root ext-root
                                                           :psi/init 'psi.test.startup-ext/init}}}
                            {})
          {:keys [ctx summary]} result]
      (is (= 1 (:extension-loaded-count summary)))
      (is (some #(re-find #"startup_ext.clj" %) (startup-registry-paths ctx)))
      (is (= :loaded (startup-entry-status ctx 'psi/test-startup-ext))))))

(deftest startup-loads-non-local-manifest-extension-via-init-var-test
  (testing "bootstrap startup loads non-local manifest extensions through :psi/init"
    (let [ns-sym 'psi.test.startup-remote-ext
          _      (define-runtime-extension-ns! ns-sym "startup-remote-hello")
          {:keys [result]} (bootstrap-with-manifest
                            {:deps {'psi/test-startup-remote-ext {:git/url "https://example.com/ext"
                                                                  :git/sha "abc123"
                                                                  :psi/init 'psi.test.startup-remote-ext/init}}}
                            {:sync-deps (fn [& _] :ok)})
          {:keys [ctx summary]} result]
      (is (= 1 (:extension-loaded-count summary)))
      (is (= [] (:extension-errors summary)))
      (is (contains? (startup-registry-paths ctx) "manifest:psi/test-startup-remote-ext")))))

(deftest startup-loads-mvn-manifest-extension-via-init-var-test
  (testing "bootstrap startup loads mvn manifest extensions through :psi/init"
    (let [ns-sym 'psi.test.startup-mvn-ext
          _      (define-runtime-extension-ns! ns-sym "startup-mvn-hello")
          {:keys [result]} (bootstrap-with-manifest
                            {:deps {'psi/test-startup-mvn-ext {:mvn/version "1.2.3"
                                                               :psi/init 'psi.test.startup-mvn-ext/init}}}
                            {:sync-deps (fn [& _] :ok)})
          {:keys [ctx summary]} result]
      (is (= 1 (:extension-loaded-count summary)))
      (is (= [] (:extension-errors summary)))
      (is (contains? (startup-registry-paths ctx) "manifest:psi/test-startup-mvn-ext")))))

(deftest startup-records-dependency-realization-failure-test
  (testing "startup records dependency realization failures as startup extension errors"
    (let [{:keys [result]} (bootstrap-with-manifest
                            {:deps {'psi/test-startup-bad-deps {:git/url "https://example.com/ext"
                                                                :git/sha "abc123"
                                                                :psi/init 'psi.test.startup.bad.deps/init}}}
                            {:sync-deps (fn [& _] (throw (ex-info "boom deps" {})))})
          {:keys [ctx summary]} result]
      (is (= 0 (:extension-loaded-count summary)))
      (is (= 1 (:extension-error-count summary)))
      (is (= :failed (startup-entry-status ctx 'psi/test-startup-bad-deps)))
      (is (some #(= "manifest:psi/test-startup-bad-deps" (:path %))
                (:extension-errors summary))))))

(deftest startup-records-init-resolution-failure-test
  (testing "startup records init-var resolution failures as startup extension errors"
    (let [{:keys [result]} (bootstrap-with-manifest
                            {:deps {'psi/test-startup-missing-init {:git/url "https://example.com/ext"
                                                                    :git/sha "abc123"
                                                                    :psi/init 'psi.test.startup.missing/init}}}
                            {:sync-deps (fn [& _] :ok)})
          {:keys [ctx summary]} result]
      (is (= 0 (:extension-loaded-count summary)))
      (is (= 1 (:extension-error-count summary)))
      (is (= :failed (startup-entry-status ctx 'psi/test-startup-missing-init)))
      (is (some #(= "manifest:psi/test-startup-missing-init" (:path %))
                (:extension-errors summary))))))

(deftest startup-records-init-execution-failure-test
  (testing "startup records init-var execution failures as startup extension errors"
    (let [ns-sym 'psi.test.startup-throws
          _      (create-ns ns-sym)
          _      (binding [*ns* (the-ns ns-sym)]
                   (clojure.core/refer 'clojure.core)
                   (intern *ns* 'init (fn [_] (throw (ex-info "boom init" {})))))
          {:keys [result]} (bootstrap-with-manifest
                            {:deps {'psi/test-startup-throws {:git/url "https://example.com/ext"
                                                              :git/sha "abc123"
                                                              :psi/init 'psi.test.startup-throws/init}}}
                            {:sync-deps (fn [& _] :ok)})
          {:keys [ctx summary]} result]
      (is (= 0 (:extension-loaded-count summary)))
      (is (= 1 (:extension-error-count summary)))
      (is (= :failed (startup-entry-status ctx 'psi/test-startup-throws)))
      (is (some #(= "manifest:psi/test-startup-throws" (:path %))
                (:extension-errors summary))))))

(deftest startup-summary-and-persisted-state-converge-with-live-registry-test
  (testing "startup summary, live registry, and persisted install state converge for mixed manifest extension outcomes"
    (let [ext-root (test-support/temp-cwd)
          _        (write-local-extension! ext-root
                                           'psi.test.startup-mixed-local
                                           "(ns psi.test.startup-mixed-local)\n\n(defn init [api]\n  ((:register-command api) \"startup-mixed-local\" {:description \"local ok\" :handler (fn [_] nil)}))\n")
          _        (define-runtime-extension-ns! 'psi.test.startup-mixed-remote "startup-mixed-remote")
          {:keys [result]} (bootstrap-with-manifest
                            {:deps {'psi/test-startup-mixed-local {:local/root ext-root
                                                                   :psi/init 'psi.test.startup-mixed-local/init}
                                    'psi/test-startup-mixed-remote {:mvn/version "1.2.3"
                                                                    :psi/init 'psi.test.startup-mixed-remote/init}
                                    'psi/test-startup-mixed-missing {:git/url "https://example.com/ext"
                                                                     :git/sha "abc123"
                                                                     :psi/init 'psi.test.startup.missing/init}}}
                            {:sync-deps (fn [& _] :ok)})
          {:keys [ctx summary]} result
          registry-paths (startup-registry-paths ctx)
          persisted      (installs/extension-installs-state-in ctx)]
      (is (= 2 (:extension-loaded-count summary)))
      (is (= 1 (:extension-error-count summary)))
      (is (some #(re-find #"startup_mixed_local.clj" %) registry-paths))
      (is (contains? registry-paths "manifest:psi/test-startup-mixed-remote"))
      (is (not (contains? registry-paths "manifest:psi/test-startup-mixed-missing")))
      (is (= :loaded
             (get-in persisted [:psi.extensions/effective :entries-by-lib 'psi/test-startup-mixed-local :status])))
      (is (= :loaded
             (get-in persisted [:psi.extensions/effective :entries-by-lib 'psi/test-startup-mixed-remote :status])))
      (is (= :failed
             (get-in persisted [:psi.extensions/effective :entries-by-lib 'psi/test-startup-mixed-missing :status]))))))

(deftest reload-extension-installs-applies-manifest-local-root-in-project-nrepl-friendly-runtime-test
  (testing "reload/apply still loads manifest-backed local-root extensions after startup state persistence"
    (let [cwd        (test-support/temp-cwd)
          home       (test-support/temp-cwd)
          ext-root   (test-support/temp-cwd)
          _          (write-local-extension! ext-root
                                             'psi.test.reload-ext
                                             "(ns psi.test.reload-ext)\n\n(defn init [api]\n  ((:register-command api) \"reload-hello\" {:description \"hello from reload manifest\" :handler (fn [_] nil)}))\n")
          [ctx sid]  (test-support/create-test-session {:persist? false :cwd cwd})]
      (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                    installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))]
        (spit (installs/project-manifest-file cwd)
              (pr-str {:deps {'psi/test-reload-ext {:local/root ext-root
                                                    :psi/init 'psi.test.reload-ext/init}}}))
        (installs/persist-install-state-in! ctx (installs/compute-install-state cwd))
        (let [result (ext-rt/reload-extensions-in! ctx sid [] cwd)]
          (is (= :loaded
                 (get-in result [:install-state :psi.extensions/effective :entries-by-lib 'psi/test-reload-ext :status])))
          (is (= :applied
                 (get-in result [:install-state :psi.extensions/last-apply :status]))))))))
